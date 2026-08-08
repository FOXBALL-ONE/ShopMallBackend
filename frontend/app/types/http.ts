export interface ApiResult<T> {
    status?: number;
    message?: string;
    data: T;
}
