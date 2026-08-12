export type OrderStatus =
    | "PENDING_PAYMENT"
    | "PAID"
    | "REFUNDING"
    | "REFUNDED"
    | "SHIPPED"
    | "DELIVERED"
    | "COMPLETED"
    | "CANCELLED"
    | "DELETED";

export type StripeCollectionStatus =
    | "REQUIRES_ACTION"
    | "PENDING"
    | "PROCESSING"
    | "SUCCEEDED"
    | "FAILED"
    | "CANCELLED"
    | "PARTIALLY_REFUNDED"
    | "REFUNDED"
    | "UNKNOWN";

export type OrderPaymentQuerySource = "PAYMENT_INTENT" | "CHECKOUT_SESSION";

export type OrderPaymentStatus =
    | "PENDING_PAYMENT"
    | "PAID"
    | "REFUNDING"
    | "PARTIALLY_REFUNDED"
    | "REFUNDED"
    | "CANCELLED";

export interface OrderListQuery {
    page: number;
    size: number;
    status?: OrderStatus;
    customer_username?: string;
    order_no?: string;
}

export interface OrderListItem {
    id: number;
    order_no: string;
    customer_id: number;
    customer_username: string;
    status: OrderStatus;
    payment_status: OrderPaymentStatus;
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
    payment_status: OrderPaymentStatus;
    cancel_reason: string | null;
    updated_at: string | null;
}

export interface DeleteOrderResponse {
    order_no: string;
    status: "DELETED";
}

export interface PermanentDeleteOrderResponse {
    order_no: string;
    physically_deleted: boolean;
}

export interface OrderDetailItem {
    id: number;
    product_id: number;
    variant_id: number;
    sku: string;
    product_snapshot: string;
    unit_price: number | string;
    quantity: number;
    line_total: number | string;
    allocated_quantity: number;
    remaining_quantity: number;
    allocated: boolean;
    created_at: string | null;
}

export interface OrderShippingAddress {
    name: string;
    phone: string;
    country: string;
    state_or_province: string | null;
    city: string;
    district: string | null;
    postal_code: string | null;
    address1: string;
    address2: string | null;
    company: string | null;
    delivery_instructions: string | null;
}

export interface OrderDetail {
    id: number;
    order_no: string;
    customer_id: number;
    customer_username: string;
    status: OrderStatus;
    payment_status: OrderPaymentStatus;
    items_subtotal: number | string;
    shipping_fee: number | string;
    tax_amount: number | string;
    discount_amount: number | string;
    total_amount: number | string;
    currency: string;
    payment_intent_id: string | null;
    stripe_checkout_session_id: string | null;
    stripe_refund_id: string | null;
    shipping_address: OrderShippingAddress;
    client_message: string | null;
    expires_at: string | null;
    paid_at: string | null;
    refund_requested_at: string | null;
    refunded_at: string | null;
    cancelled_at: string | null;
    shipped_at: string | null;
    delivered_at: string | null;
    cancel_reason: string | null;
    created_at: string | null;
    updated_at: string | null;
    items: OrderDetailItem[];
}

export interface OrderPaymentStatusResponse {
    order_no: string;
    order_status: OrderStatus;
    payment_status: OrderPaymentStatus;
    provider: string;
    provider_status: StripeCollectionStatus;
    query_source: OrderPaymentQuerySource;
    payment_intent_id: string | null;
    stripe_checkout_session_id: string | null;
    payment_intent_status: string | null;
    checkout_session_status: string | null;
    checkout_payment_status: string | null;
    amount: number | string | null;
    currency: string | null;
    amount_matches_order: boolean | null;
    failure_code: string | null;
    failure_message: string | null;
}

export interface OrderRefundStatusResponse {
    order_no: string;
    order_status: OrderStatus;
    payment_status: OrderPaymentStatus;
    stripe_refund_id: string | null;
    provider_refund_status: string | null;
    refund_amount: number | string | null;
    currency: string | null;
    amount_matches_order: boolean | null;
}
