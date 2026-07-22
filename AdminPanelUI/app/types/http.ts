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
 * 登录成功响应（落在 ApiResult.data 内），对齐后端
 * top.foxball.shopmall.authentication.LoginTokenAuthentication.LoginResult.Response。
 * 字段名为 @JsonProperty 指定的 snake_case（user_id / frp_token / user_info / register_time）。
 */
export interface LoginResponse {
    token: string;
    user_id: number;
    frp_token: string;
    user_info: UserInfo;
}

export interface UserInfo {
    username: string;
    email: string;
    avatar: string;
    traffic: string;
    register_time: string;
    group: { id: number; name: string };
    limit: {
        tunnel: number | null;
        inbound: number;
        outbound: number;
    };
}
