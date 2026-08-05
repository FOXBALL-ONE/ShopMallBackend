import {createFetch, type FetchOptions} from "ofetch";
import type {ApiResult, UserInfo} from "~/types/http";

type ParamMode = "query" | "json";
type QueryParams = Record<string, unknown>;
type JsonBody = BodyInit | Record<string, any> | null | undefined;

export interface HttpRequestOptions<T> extends Omit<FetchOptions<"json">, "baseURL" | "query" | "params" | "body"> {
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    payloadMode?: ParamMode;
    params?: QueryParams;
    body?: T;
}

const UNAUTHORIZED_STATUS = 401;

// 后端 JWT 认证：令牌以 "Authorization: Bearer <jwt>" 携带；
// 登录成功后前端把 token + userInfo 写入这两个 cookie，供后续请求与鉴权展示使用
const TOKEN_COOKIE = "admin_auth_token";
const USER_COOKIE = "admin_user_info";

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
    return response.status ?? 500;
}

function getResponseMessage(response: ApiResult<unknown>): string {
    return response.message ?? "Request failed";
}

export const useHttp = (baseURL?: string) => {
    const authToken = useCookie<string | null>(TOKEN_COOKIE);
    const authUser = useCookie<UserInfo | null>(USER_COOKIE);
    const runtimeConfig = useRuntimeConfig();
    const route = useRoute();
    const apiBase = baseURL || (runtimeConfig.public.apiBase as string) || "http://127.0.0.1:8080/api";

    const http = createFetch({
        defaults: {
            baseURL: apiBase,
            credentials: "include",
            headers: {
                Accept: "application/json",
            },
        },
    });

    /** 401 统一处理：清空登录态并跳转登录页。SSR/客户端两态都覆盖。 */
    function handleUnauthorized() {
        authToken.value = null;
        authUser.value = null;
        if (import.meta.server) {
            // SSR 阶段无法直接跳转，抛 401 交由 Nuxt 渲染错误页 / 中间件处理
            throw createError({statusCode: 401, statusMessage: "Unauthorized"});
        } else if (route.path !== "/login") {
            navigateTo("/login");
        }
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
        const requestHeaders = new Headers(fetchOptions.headers as HeadersInit | undefined);
        const authorization = normalizeAuthorization(authToken.value);
        if (authorization && !requestHeaders.has("Authorization")) {
            requestHeaders.set("Authorization", authorization);
        }

        // ofetch 失败（网络错误或非 2xx）会抛 FetchError；后端统一体 401 会落到此分支
        let response: ApiResult<TResponse>;
        try {
            response = await http<ApiResult<TResponse>>(url, {
                method,
                ...fetchOptions,
                query,
                headers: requestHeaders,
                body: requestBody,
            });
        } catch (error: any) {
            const body = error?.response?._data as ApiResult<unknown> | undefined;
            const status = body?.status ?? error?.statusCode ?? 0;
            if (status === UNAUTHORIZED_STATUS) {
                handleUnauthorized();
            }
            throw createError({
                statusCode: status || (error?.statusCode ?? 500),
                statusMessage: body?.message ?? error?.message ?? "Request failed",
                data: body,
            });
        }

        const responseStatus = getResponseStatus(response);
        if (responseStatus === UNAUTHORIZED_STATUS) {
            handleUnauthorized();
        }
        if (responseStatus < 200 || responseStatus >= 300) {
            throw createError({
                statusCode: responseStatus,
                statusMessage: getResponseMessage(response),
                data: response,
            });
        }

        return response;
    };

    const request = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<TResponse> => {
        const response = await requestBase<TResponse, TPayload>(url, payload, options);

        return response.data;
    };

    /** 持久化登录态：登录成功后写入 token 与用户信息 cookie。 */
    function setAuth(token: string, userInfo: UserInfo) {
        authToken.value = token;
        authUser.value = userInfo;
    }

    /** 清空登录态：登出 / token 失效时调用。 */
    function clearAuth() {
        authToken.value = null;
        authUser.value = null;
    }

    return {
        request,
        requestRaw: requestBase,
        /** 当前登录令牌（只读引用，写改用 setAuth/clearAuth）。 */
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
