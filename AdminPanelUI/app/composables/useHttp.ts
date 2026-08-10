import {createDiscreteApi, type MessageApi} from "naive-ui";
import {createFetch, type FetchOptions} from "ofetch";
import type {ApiResult, UserInfo} from "~/types/http";

type ParamMode = "query" | "json";
type QueryParams = Record<string, unknown>;
type JsonBody = BodyInit | Record<string, any> | null | undefined;

interface RefreshResponse {
    access_token: string;
    expires_in: number;
}

interface RequestFailure {
    status: number;
    message: string;
    data?: ApiResult<unknown>;
}

interface RefreshAttempt {
    token: string | null;
    status: number;
    message: string;
    missingRefreshToken: boolean;
}

export interface HttpRequestOptions<T> extends Omit<FetchOptions<"json">, "baseURL" | "query" | "params" | "body"> {
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    payloadMode?: ParamMode;
    params?: QueryParams;
    body?: T;
}

const UNAUTHORIZED_STATUS = 401;
const MAX_REFRESH_ATTEMPTS = 2;
const SESSION_TTL_SECONDS = 60 * 60 * 24 * 7;
const TOKEN_COOKIE = "admin_auth_token";
const USER_COOKIE = "admin_user_info";

// 同一浏览器中的并发鉴权失败只发起一次刷新，避免 refresh token 轮换时产生不必要的竞争。
let clientRefreshPromise: Promise<RefreshAttempt> | null = null;
let clientSessionExpired = false;
let clientSessionCleanupPromise: Promise<void> | null = null;
let messageApi: MessageApi | null = null;

function normalizeAuthorization(token?: string | null) {
    const rawToken = token?.trim();
    if (!rawToken) {
        return "";
    }

    if (/^bearer\s+/i.test(rawToken)) {
        return rawToken;
    }

    return `Bearer ${rawToken}`;
}

function getResponseStatus(response: ApiResult<unknown>): number {
    return Number(response.status) || 500;
}

function getResponseMessage(response: ApiResult<unknown>): string {
    return response.message ?? "Request failed";
}

function isAuthenticationFailure(status: number): boolean {
    return status === UNAUTHORIZED_STATUS;
}

function isAuthenticationEndpoint(url: string): boolean {
    const path = url.startsWith("/") ? url : `/${url}`;
    return /^\/auth\/(?:login|refresh|logout)(?:[/?]|$)/.test(path);
}

function requestFailure(error: unknown): RequestFailure {
    const value = error as {
        response?: {status?: number; _data?: ApiResult<unknown>};
        data?: ApiResult<unknown>;
        statusCode?: number;
        message?: string;
    };
    const data = value.response?._data ?? value.data;
    const rawStatus = data?.status ?? value.response?.status ?? value.statusCode;
    const status = Number(rawStatus);

    return {
        status: Number.isFinite(status) && status > 0 ? status : 500,
        message: data?.message ?? value.message ?? "Request failed",
        data,
    };
}

function isRefreshTokenMissing(failure: RequestFailure): boolean {
    return isAuthenticationFailure(failure.status)
        && /未提供刷新令牌|refresh[\s_-]*token\s*(?:is\s*)?(?:missing|not provided)|missing\s+refresh/i.test(failure.message);
}

function toRequestError(failure: RequestFailure) {
    return createError({
        statusCode: failure.status,
        statusMessage: failure.message,
        data: failure.data,
    });
}

function notifyAuthentication(type: "warning" | "error", content: string) {
    if (import.meta.server) {
        return;
    }

    messageApi ??= createDiscreteApi(["message"]).message;
    if (type === "error") {
        messageApi.error(content);
    } else {
        messageApi.warning(content);
    }
}

export const useHttp = (baseURL?: string) => {
    // access token 可由前端读取并加入 Bearer 头；refresh token 仅由后端以 HttpOnly Cookie 保存。
    // 两个本地会话 cookie 与后端 refresh cookie 均保持 7 天，重新打开浏览器后仍可用 refresh 恢复 access token。
    const cookieOptions = {
        maxAge: SESSION_TTL_SECONDS,
        sameSite: "lax" as const,
        path: "/",
    };
    const authToken = useCookie<string | null>(TOKEN_COOKIE, cookieOptions);
    const authUser = useCookie<UserInfo | null>(USER_COOKIE, cookieOptions);
    const runtimeConfig = useRuntimeConfig();
    const route = useRoute();
    const authApiBase = (runtimeConfig.public.apiBase as string) || "http://127.0.0.1:8080/api";
    const apiBase = baseURL || authApiBase;

    const http = createFetch({
        defaults: {
            baseURL: apiBase,
            credentials: "include",
            headers: {
                Accept: "application/json",
            },
        },
    });
    const authHttp = createFetch({
        defaults: {
            baseURL: authApiBase,
            credentials: "include",
            headers: {
                Accept: "application/json",
            },
        },
    });

    const clearLocalAuth = () => {
        authToken.value = null;
        authUser.value = null;
    };

    const clearRemoteRefreshToken = async () => {
        try {
            await authHttp<ApiResult<Record<string, never>>>("/auth/logout", {method: "POST"});
        } catch {
            // 即使网络请求失败，本地 access token 仍必须清掉；下次可达后端时 cookie 会自然过期。
        }
    };

    const expireSession = async (message: string) => {
        if (import.meta.server) {
            clearLocalAuth();
            return;
        }
        if (clientSessionCleanupPromise) {
            return clientSessionCleanupPromise;
        }

        clientSessionExpired = true;
        clientSessionCleanupPromise = (async () => {
            clearLocalAuth();
            await clearRemoteRefreshToken();
            notifyAuthentication("error", message);
            if (route.path !== "/login") {
                await navigateTo("/login");
            }
        })().finally(() => {
            clientSessionCleanupPromise = null;
        });
        return clientSessionCleanupPromise;
    };

    const refreshAccessToken = (attempt: number): Promise<RefreshAttempt> => {
        const refresh = async (): Promise<RefreshAttempt> => {
            try {
                const response = await authHttp<ApiResult<RefreshResponse>>("/auth/refresh", {method: "POST"});
                const status = getResponseStatus(response);
                const message = getResponseMessage(response);
                const token = response.data?.access_token?.trim();
                if (status >= 200 && status < 300 && token) {
                    return {token, status, message, missingRefreshToken: false};
                }

                const failure = {status, message};
                return {
                    token: null,
                    status,
                    message,
                    missingRefreshToken: isRefreshTokenMissing(failure),
                };
            } catch (error: unknown) {
                const failure = requestFailure(error);
                return {
                    token: null,
                    status: failure.status,
                    message: failure.message,
                    missingRefreshToken: isRefreshTokenMissing(failure),
                };
            }
        };

        if (import.meta.server) {
            return refresh();
        }
        if (clientRefreshPromise) {
            return clientRefreshPromise;
        }

        notifyAuthentication(
            "warning",
            attempt === 1 ? "登录状态已过期，正在刷新 Access Token" : "登录状态仍未恢复，正在再次刷新 Access Token",
        );
        clientRefreshPromise = (async () => {
            try {
                return await refresh();
            } finally {
                clientRefreshPromise = null;
            }
        })();
        return clientRefreshPromise;
    };

    const requestBase = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<ApiResult<TResponse>> => {
        const {payloadMode = "query", method = "GET", params, body, ...fetchOptions} = options;
        const query = payloadMode === "query"
            ? (params ?? (payload as QueryParams | undefined))
            : params;
        const requestBody = payloadMode === "json"
            ? ((body ?? payload) as JsonBody)
            : undefined;
        let replayWithRefreshedToken = false;

        const send = async () => {
            const requestHeaders = new Headers(fetchOptions.headers as HeadersInit | undefined);
            const authorization = normalizeAuthorization(authToken.value);
            if (authorization && (replayWithRefreshedToken || !requestHeaders.has("Authorization"))) {
                requestHeaders.set("Authorization", authorization);
            }

            const response = await http<ApiResult<TResponse>>(url, {
                method,
                ...fetchOptions,
                query,
                headers: requestHeaders,
                body: requestBody,
            });
            const status = getResponseStatus(response);
            if (status < 200 || status >= 300) {
                throw createError({
                    statusCode: status,
                    statusMessage: getResponseMessage(response),
                    data: response,
                });
            }
            return response;
        };

        let failure: RequestFailure;
        try {
            return await send();
        } catch (error: unknown) {
            failure = requestFailure(error);
        }

        // 登录、刷新和登出自身不代表 access token 失效，不能对它们递归刷新。
        if (isAuthenticationEndpoint(url) || !isAuthenticationFailure(failure.status)) {
            throw toRequestError(failure);
        }
        if (import.meta.client && clientSessionExpired) {
            throw createError({
                statusCode: failure.status,
                statusMessage: "登录已失效，请重新登录",
                data: failure.data,
            });
        }

        for (let attempt = 1; attempt <= MAX_REFRESH_ATTEMPTS; attempt++) {
            const refreshed = await refreshAccessToken(attempt);
            if (!refreshed.token) {
                if (refreshed.missingRefreshToken) {
                    await expireSession("未检测到 Refresh Token，正在返回登录页");
                    throw createError({
                        statusCode: refreshed.status,
                        statusMessage: refreshed.message,
                    });
                }
                if (!isAuthenticationFailure(refreshed.status)) {
                    if (attempt === MAX_REFRESH_ATTEMPTS) {
                        notifyAuthentication("error", "刷新 Access Token 失败，请稍后重试");
                        throw createError({
                            statusCode: refreshed.status,
                            statusMessage: refreshed.message,
                        });
                    }
                    continue;
                }
                if (attempt === MAX_REFRESH_ATTEMPTS) {
                    await expireSession("登录状态已失效，已清除所有 Token，请重新登录");
                    throw createError({
                        statusCode: refreshed.status,
                        statusMessage: refreshed.message,
                    });
                }
                continue;
            }

            authToken.value = refreshed.token;
            replayWithRefreshedToken = true;
            try {
                return await send();
            } catch (error: unknown) {
                failure = requestFailure(error);
            }
            if (!isAuthenticationFailure(failure.status)) {
                throw toRequestError(failure);
            }
        }

        await expireSession("登录状态已失效，已清除所有 Token，请重新登录");
        throw createError({
            statusCode: failure.status,
            statusMessage: failure.message,
            data: failure.data,
        });
    };

    const request = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<TResponse> => {
        const response = await requestBase<TResponse, TPayload>(url, payload, options);

        return response.data;
    };

    /** 持久化登录态：登录成功后写入 access token 与用户信息 cookie。 */
    function setAuth(token: string, userInfo: UserInfo) {
        clientSessionExpired = false;
        authToken.value = token;
        authUser.value = userInfo;
    }

    /** 清空本地 access token 与用户信息；Refresh Token 由 /auth/logout 的 HttpOnly Cookie 清理。 */
    function clearAuth() {
        clearLocalAuth();
    }

    return {
        request,
        requestRaw: requestBase,
        /** 当前 access token（只读引用，写改用 setAuth/clearAuth）。 */
        token: readonly(authToken),
        /** 当前登录用户信息（只读引用）。 */
        user: readonly(authUser),
        setAuth,
        clearAuth,
        get: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            request<TResponse>(url, params, {
                ...options,
                method: "GET",
                payloadMode: "query",
            }),
        getRaw: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            requestBase<TResponse>(url, params, {
                ...options,
                method: "GET",
                payloadMode: "query",
            }),
        post: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {
                ...options,
                method: "POST",
            }),
        postRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {
                ...options,
                method: "POST",
            }),
        put: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {
                ...options,
                method: "PUT",
            }),
        putRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {
                ...options,
                method: "PUT",
            }),
        patch: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {
                ...options,
                method: "PATCH",
            }),
        patchRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {
                ...options,
                method: "PATCH",
            }),
        delete: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            request<TResponse>(url, params, {
                ...options,
                method: "DELETE",
                payloadMode: "query",
            }),
        deleteRaw: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            requestBase<TResponse>(url, params, {
                ...options,
                method: "DELETE",
                payloadMode: "query",
            }),
    };
};
