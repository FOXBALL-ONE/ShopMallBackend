export type OrderStatus =
    | "PENDING_PAYMENT"
    | "PAID"
    | "SHIPPED"
    | "DELIVERED"
    | "COMPLETED"
    | "CANCELLED";

export interface OrderListQuery {
    page: number;
    size: number;
    status?: OrderStatus;
    customer_id?: number;
    order_no?: string;
}

export interface OrderListItem {
    id: number;
    order_no: string;
    customer_id: number;
    status: OrderStatus;
    total_amount: number | string;
    currency: string;
    created_at: string | null;
    updated_at: string | null;
}

export interface OrderListResponse {
    list: OrderListItem[];
    /** 后端 pagination.count 表示总页数。 */
    pagination: {
        count: number;
    };
}

export interface RefundOrderResponse {
    id: number;
    order_no: string;
    status: OrderStatus;
    cancel_reason: string | null;
    updated_at: string | null;
}
