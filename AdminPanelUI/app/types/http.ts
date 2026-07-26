/**
 * 统一响应体，对齐后端 top.foxball.shopmall.shared.Response：
 * { status: number, message: string, data: T }
 * status 为 HTTP 状态码（200/404/401…），data 缺省时后端返回空对象。
 */
export interface ApiResult<T> {
    status: number;
    message: string;
    data: T;
}

/**
 * 登录成功响应（落在 ApiResult.data 内）。
 * refresh token 由 HttpOnly Cookie 携带，不包含在响应体中。
 */
export interface LoginResponse {
    access_token: string;
    expires_in: number;
    user_id: number;
    user_info: UserInfo;
}

export type UserRole = "CUSTOMER" | "ADMIN";

export interface UserInfo {
    username: string;
    email: string;
    first_name: string;
    last_name: string;
    avatar: string | null;
    role: UserRole;
    locale: string | null;
    currency: string | null;
}
