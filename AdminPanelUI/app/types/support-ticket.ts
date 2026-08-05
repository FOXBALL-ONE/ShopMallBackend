export type SupportServiceType = "PRE_SALES" | "AFTER_SALES";
export type SupportTicketPriority = "LOW" | "MEDIUM" | "HIGH";
export type SupportTicketStatus = "OPEN" | "IN_PROGRESS" | "RESOLVED" | "CLOSED";
export type SupportTicketMessageSender = "CUSTOMER" | "ADMIN";

export interface SupportTicketListQuery {
    page: number;
    size: number;
    status?: SupportTicketStatus;
    service_type?: SupportServiceType;
    priority?: SupportTicketPriority;
    customer_id?: number;
    order_no?: string;
}

export interface SupportTicketListItem {
    id: number;
    customer_id: number;
    service_type: SupportServiceType;
    priority: SupportTicketPriority;
    order_no: string | null;
    subject: string;
    status: SupportTicketStatus;
    handled_by: number | null;
    created_at: string | null;
    updated_at: string | null;
}

export interface SupportTicketListResponse {
    list: SupportTicketListItem[];
    /** 后端 pagination.count 表示总页数。 */
    pagination: {
        count: number;
    };
}

export interface SupportTicketAttachment {
    id: number;
    file_id: string;
    file_name: string;
    content_type: string | null;
    size_bytes: number;
    download_url: string;
    download_expires_at: string;
}

export interface SupportTicketMessage {
    id: number;
    sender_id: number;
    sender_type: SupportTicketMessageSender;
    content: string | null;
    attachments: SupportTicketAttachment[];
    created_at: string | null;
}

export interface SupportTicketMessageResponse extends SupportTicketMessage {
    ticket_id: number;
}

export interface SupportTicketDetail {
    id: number;
    customer_id: number;
    service_type: SupportServiceType;
    priority: SupportTicketPriority;
    order_no: string | null;
    subject: string;
    content: string;
    status: SupportTicketStatus;
    admin_reply: string | null;
    handled_by: number | null;
    replied_at: string | null;
    resolved_at: string | null;
    closed_at: string | null;
    created_at: string | null;
    updated_at: string | null;
    messages: SupportTicketMessage[];
    message_pagination: {
        count: number;
        total: number;
    };
}

export type SupportTicketUpdateResponse = Omit<SupportTicketDetail, "messages" | "message_pagination">;

export interface UpdateSupportTicketPayload {
    status?: SupportTicketStatus;
    priority?: SupportTicketPriority;
    reply?: string;
}
