import {createFetch, type FetchOptions} from "ofetch";
import type {ApiResult} from "~/types/http";

type ParamMode = "query" | "json";
type QueryParams = Record<string, unknown>;
type JsonBody = BodyInit | Record<string, unknown> | null | undefined;
type NotificationType = "warning" | "error";

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
    expiresIn: number | null;
    status: number;
    message: string;
    missingRefreshToken: boolean;
}

interface JwtTiming {
    issuedAt: number;
    expiresAt: number;
}

export interface HttpRequestOptions<T> extends Omit<FetchOptions<"json">, "baseURL" | "query" | "params" | "body"> {
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    payloadMode?: ParamMode;
    params?: QueryParams;
    body?: T;
}

const AUTH_FAILURE_STATUSES = new Set([401, 403]);
const MAX_REFRESH_ATTEMPTS = 2;
const REFRESH_REMAINING_RATIO = 0.2;
const REFRESH_RETRY_DELAY_MS = 30_000;
const SESSION_TTL_SECONDS = 60 * 60 * 24 * 7;
const TOKEN_COOKIE = "chat_auth_token";
const LOGIN_PATH = "/login";

// 浏览器内全局单飞：并发业务请求只共享一次 refresh，避免后端轮换 Refresh Token 时互相竞争。
let clientRefreshPromise: Promise<RefreshAttempt> | null = null;
let clientSilentRefreshPromise: Promise<void> | null = null;
let clientSessionCleanupPromise: Promise<void> | null = null;
let clientSessionExpired = false;
let clientRefreshTimer: ReturnType<typeof setTimeout> | null = null;
let scheduledAccessToken: string | null = null;

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
    const status = Number(response.status);
    return Number.isFinite(status) ? status : 500;
}

function getResponseMessage(response: ApiResult<unknown>): string {
    return response.message ?? "Request failed";
}

function isSuccessfulStatus(status: number): boolean {
    return status === 0 || (status >= 200 && status < 300);
}

function isAuthenticationFailure(status: number): boolean {
    return AUTH_FAILURE_STATUSES.has(status);
}

function requestPath(url: string): string {
    try {
        return new URL(url, "http://localhost:8080").pathname.replace(/\/+$/, "");
    } catch {
        return url.split(/[?#]/, 1)[0]?.replace(/\/+$/, "") ?? url;
    }
}

function isLoginEndpoint(url: string): boolean {
    return /\/(?:api\/)?auth\/login(?:\/|$)/.test(requestPath(url));
}

function isRefreshEndpoint(url: string): boolean {
    return /\/(?:api\/)?auth\/refresh$/.test(requestPath(url));
}

function isLogoutEndpoint(url: string): boolean {
    return /\/(?:api\/)?auth\/logout$/.test(requestPath(url));
}

function isAuthenticationEndpoint(url: string): boolean {
    return isLoginEndpoint(url) || isRefreshEndpoint(url) || isLogoutEndpoint(url);
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

function readJwtTiming(token: string): JwtTiming | null {
    try {
        const rawToken = token.replace(/^bearer\s+/i, "");
        const payload = rawToken.split(".")[1];
        if (!payload) {
            return null;
        }

        const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
        const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
        const binary = globalThis.atob(padded);
        const bytes = Uint8Array.from(binary, character => character.charCodeAt(0));
        const claims = JSON.parse(new TextDecoder().decode(bytes)) as {iat?: unknown; exp?: unknown};
        const issuedAt = Number(claims.iat) * 1000;
        const expiresAt = Number(claims.exp) * 1000;

        if (!Number.isFinite(issuedAt) || !Number.isFinite(expiresAt) || expiresAt <= issuedAt) {
            return null;
        }

        return {issuedAt, expiresAt};
    } catch {
        return null;
    }
}

function refreshDelay(token: string, fallbackExpiresIn?: number): number | null {
    const timing = readJwtTiming(token);
    if (timing) {
        const lifetime = timing.expiresAt - timing.issuedAt;
        const refreshAt = timing.expiresAt - lifetime * REFRESH_REMAINING_RATIO;
        return refreshAt - Date.now();
    }

    if (fallbackExpiresIn && Number.isFinite(fallbackExpiresIn) && fallbackExpiresIn > 0) {
        return fallbackExpiresIn * 1000 * (1 - REFRESH_REMAINING_RATIO);
    }

    return null;
}

function clearClientRefreshTimer(token?: string | null) {
    if (import.meta.server || (token && scheduledAccessToken !== token)) {
        return;
    }

    if (clientRefreshTimer) {
        clearTimeout(clientRefreshTimer);
        clientRefreshTimer = null;
    }
    scheduledAccessToken = null;
}

export const useHttp = (baseURL?: string) => {
    // Access Token 由前端保存并写入 Authorization；Refresh Token 由后端保存到 HttpOnly Cookie。
    // Refresh Token 后端 TTL 为 7 天；Access Token cookie 同样保留 7 天，便于页面重开后继续自动续期。
    const authToken = useCookie<string | null>(TOKEN_COOKIE, {
        maxAge: SESSION_TTL_SECONDS,
        sameSite: "lax",
        path: "/",
    });
    const toast = useToast();
    const router = useRouter();
    const configuredApiBase = useRuntimeConfig().public.apiBase;
    const authApiBase = typeof configuredApiBase === "string" && configuredApiBase
        ? configuredApiBase
        : "http://127.0.0.1:8080/api";
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

    const notify = (type: NotificationType, title: string, description?: string) => {
        if (import.meta.server) {
            return;
        }

        toast.add({
            title,
            description,
            color: type,
        });
    };

    const scheduleSilentRefresh = (token: string | null, expiresIn?: number, minimumDelay = 0) => {
        if (import.meta.server || !token || clientSessionExpired) {
            clearClientRefreshTimer();
            return;
        }

        const delay = refreshDelay(token, expiresIn);
        if (delay === null) {
            return;
        }
        if (scheduledAccessToken === token && clientRefreshTimer && minimumDelay === 0) {
            return;
        }

        clearClientRefreshTimer();
        scheduledAccessToken = token;
        clientRefreshTimer = setTimeout(() => {
            if (scheduledAccessToken !== token) {
                return;
            }
            clientRefreshTimer = null;
            scheduledAccessToken = null;
            void silentlyRefreshTokens().catch(() => undefined);
        }, Math.max(delay, minimumDelay, 0));
    };

    const persistAccessToken = (token: string, expiresIn?: number) => {
        authToken.value = token;
        clientSessionExpired = false;
        scheduleSilentRefresh(token, expiresIn);
    };

    const expireSession = async (message: string, failure: RequestFailure): Promise<never> => {
        if (import.meta.server) {
            authToken.value = null;
            throw toRequestError(failure);
        }

        if (!clientSessionExpired) {
            clientSessionExpired = true;
            authToken.value = null;
            clearClientRefreshTimer();
            notify("error", message);

            clientSessionCleanupPromise = (async () => {
                // Refresh Token 是 HttpOnly Cookie，JS 无法直接删除；调用 logout 让后端清空它。
                try {
                    await authHttp("/auth/logout", {method: "POST"});
                } catch {
                    // 无论服务端清理是否可达，本地 Access Token 都必须清除并返回登录页。
                }

                if (router.currentRoute.value.path !== LOGIN_PATH) {
                    try {
                        await router.replace(LOGIN_PATH);
                    } catch {
                        window.location.assign(LOGIN_PATH);
                    }
                }
            })().finally(() => {
                clientSessionCleanupPromise = null;
            });
        }

        if (clientSessionCleanupPromise) {
            await clientSessionCleanupPromise;
        }
        throw toRequestError(failure);
    };

    const refreshAccessToken = (): Promise<RefreshAttempt> => {
        const refresh = async (): Promise<RefreshAttempt> => {
            try {
                const response = await authHttp<ApiResult<RefreshResponse>>("/auth/refresh", {
                    method: "POST",
                });
                const status = getResponseStatus(response);
                if (!isSuccessfulStatus(status)) {
                    const failure = {
                        status,
                        message: getResponseMessage(response),
                        data: response,
                    };
                    return {
                        token: null,
                        expiresIn: null,
                        status,
                        message: failure.message,
                        missingRefreshToken: isRefreshTokenMissing(failure),
                    };
                }

                const token = response.data?.access_token?.trim();
                const expiresIn = Number(response.data?.expires_in);
                if (!token) {
                    return {
                        token: null,
                        expiresIn: null,
                        status: 500,
                        message: "刷新接口未返回 Access Token",
                        missingRefreshToken: false,
                    };
                }

                persistAccessToken(token, expiresIn);
                return {
                    token,
                    expiresIn: Number.isFinite(expiresIn) ? expiresIn : null,
                    status,
                    message: getResponseMessage(response),
                    missingRefreshToken: false,
                };
            } catch (error: unknown) {
                const failure = requestFailure(error);
                return {
                    token: null,
                    expiresIn: null,
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

        clientRefreshPromise = refresh().finally(() => {
            clientRefreshPromise = null;
        });
        return clientRefreshPromise;
    };

    async function silentlyRefreshTokens() {
        if (import.meta.server || clientSessionExpired) {
            return;
        }
        if (clientSilentRefreshPromise) {
            return clientSilentRefreshPromise;
        }

        const tokenAtStart = authToken.value;
        if (!tokenAtStart) {
            return;
        }
        const delay = refreshDelay(tokenAtStart);
        if (delay === null || delay > 0) {
            scheduleSilentRefresh(tokenAtStart);
            return;
        }

        clientSilentRefreshPromise = (async () => {
            for (let attempt = 1; attempt <= MAX_REFRESH_ATTEMPTS; attempt++) {
                if (attempt > 1) {
                    notify("warning", "Token 无感刷新未成功，正在重试（2/2）");
                }

                const refreshed = await refreshAccessToken();
                if (refreshed.token) {
                    // refresh 响应会同时返回新 Access Token，并通过 Set-Cookie 轮换 Refresh Token。
                    return;
                }

                const failure = {
                    status: refreshed.status,
                    message: refreshed.message,
                };
                if (refreshed.missingRefreshToken) {
                    await expireSession("未检测到 Refresh Token，正在跳转登录页", failure);
                }
                if (attempt === MAX_REFRESH_ATTEMPTS && isAuthenticationFailure(refreshed.status)) {
                    await expireSession("登录状态刷新失败，已清除所有 Token，请重新登录", failure);
                }
            }

            // 网络或服务端临时错误不应直接注销仍有效的会话；保留当前 token，稍后再无感重试。
            notify("error", "Token 无感刷新失败，将稍后重试");
            if (authToken.value === tokenAtStart) {
                scheduleSilentRefresh(tokenAtStart, undefined, REFRESH_RETRY_DELAY_MS);
            }
        })().finally(() => {
            clientSilentRefreshPromise = null;
        });

        return clientSilentRefreshPromise;
    }

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
            if (!isSuccessfulStatus(status)) {
                throw createError({
                    statusCode: status,
                    statusMessage: getResponseMessage(response),
                    data: response,
                });
            }

            if (isLoginEndpoint(url) || isRefreshEndpoint(url)) {
                const data = response.data as unknown as Partial<RefreshResponse> | undefined;
                const token = data?.access_token?.trim();
                const expiresIn = Number(data?.expires_in);
                if (token) {
                    persistAccessToken(token, expiresIn);
                }
            } else if (isLogoutEndpoint(url)) {
                authToken.value = null;
                clientSessionExpired = true;
                clearClientRefreshTimer();
            }

            return response;
        };

        // 在 Access Token 剩余寿命不超过 20% 时先无感续期；刷新成功也会轮换 7 天 Refresh Token。
        if (import.meta.client && !isAuthenticationEndpoint(url) && authToken.value) {
            const delay = refreshDelay(authToken.value);
            if (delay !== null && delay <= 0) {
                await silentlyRefreshTokens();
            }
        }

        let failure: RequestFailure;
        try {
            return await send();
        } catch (error: unknown) {
            failure = requestFailure(error);
        }

        // 登录/刷新/登出不能递归刷新；SSR 也不尝试消费浏览器侧 HttpOnly Refresh Cookie。
        if (import.meta.server || isAuthenticationEndpoint(url) || !isAuthenticationFailure(failure.status)) {
            throw toRequestError(failure);
        }
        if (clientSessionExpired) {
            throw createError({
                statusCode: failure.status,
                statusMessage: "登录已失效，请重新登录",
                data: failure.data,
            });
        }

        // HTTP/body 401 或 403：最多刷新并重放两轮。第一次静默，第二次与最终失败均提示。
        for (let attempt = 1; attempt <= MAX_REFRESH_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                notify("warning", "Token 刷新后仍未通过验权，正在重试（2/2）");
            }

            const refreshed = await refreshAccessToken();
            if (!refreshed.token) {
                failure = {
                    status: refreshed.status,
                    message: refreshed.message,
                };
                if (refreshed.missingRefreshToken) {
                    return expireSession("未检测到 Refresh Token，正在跳转登录页", failure);
                }
                if (attempt === MAX_REFRESH_ATTEMPTS) {
                    if (isAuthenticationFailure(refreshed.status)) {
                        return expireSession("登录状态刷新失败，已清除所有 Token，请重新登录", failure);
                    }
                    notify("error", "Token 刷新失败，请稍后重试");
                    throw toRequestError(failure);
                }
                continue;
            }

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

        return expireSession("两次刷新后仍未通过验权，已清除所有 Token，请重新登录", failure);
    };

    const request = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<TResponse> => {
        const response = await requestBase<TResponse, TPayload>(url, payload, options);
        return response.data;
    };

    if (import.meta.client) {
        watch(authToken, (token, previousToken) => {
            if (!token) {
                clearClientRefreshTimer(previousToken);
                return;
            }
            clientSessionExpired = false;
            scheduleSilentRefresh(token);
        }, {immediate: true});
    }

    return {
        request,
        requestRaw: requestBase,
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
