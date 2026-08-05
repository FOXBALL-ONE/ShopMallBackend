import type {
    OrderListQuery,
    OrderListResponse,
    OrderDetail,
    OrderStatus,
    RefundOrderResponse,
} from "~/types/order";

export const ORDER_STATUS_OPTIONS: Array<{ label: string; value: OrderStatus }> = [
    {label: "待付款", value: "PENDING_PAYMENT"},
    {label: "已支付 / 待发货", value: "PAID"},
    {label: "已发货", value: "SHIPPED"},
    {label: "已送达", value: "DELIVERED"},
    {label: "已完成", value: "COMPLETED"},
    {label: "已取消 / 已退款", value: "CANCELLED"},
];

export const useOrderApi = () => {
    const config = useRuntimeConfig();
    const adminApiBase = (config.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const {get, post} = useHttp(adminApiBase);

    function list(query: OrderListQuery): Promise<OrderListResponse> {
        return get<OrderListResponse>("/orders", {...query});
    }

    function refund(orderNo: string, reason: string): Promise<RefundOrderResponse> {
        return post<RefundOrderResponse>(`/orders/${encodeURIComponent(orderNo)}/refund`, {reason});
    }

    function detail(orderNo: string): Promise<OrderDetail> {
        return get<OrderDetail>(`/orders/${encodeURIComponent(orderNo)}`);
    }

    return {
        list,
        detail,
        refund,
    };
};
