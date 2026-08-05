import type {
    SupportServiceType,
    SupportTicketDetail,
    SupportTicketListQuery,
    SupportTicketListResponse,
    SupportTicketMessageResponse,
    SupportTicketPriority,
    SupportTicketStatus,
    SupportTicketUpdateResponse,
    UpdateSupportTicketPayload,
} from "~/types/support-ticket";

export const SUPPORT_TICKET_STATUS_OPTIONS: Array<{ label: string; value: SupportTicketStatus }> = [
    { label: "待处理", value: "OPEN" },
    { label: "处理中", value: "IN_PROGRESS" },
    { label: "已解决", value: "RESOLVED" },
    { label: "已关闭", value: "CLOSED" },
];

export const SUPPORT_TICKET_PRIORITY_OPTIONS: Array<{ label: string; value: SupportTicketPriority }> = [
    { label: "低", value: "LOW" },
    { label: "中", value: "MEDIUM" },
    { label: "高", value: "HIGH" },
];

export const SUPPORT_SERVICE_TYPE_OPTIONS: Array<{ label: string; value: SupportServiceType }> = [
    { label: "售前咨询", value: "PRE_SALES" },
    { label: "售后支持", value: "AFTER_SALES" },
];

/** 管理端工单 API。列表、详情、处理和消息发送均对齐 AdminSupportTicketController。 */
export const useSupportTicketApi = () => {
    const runtimeConfig = useRuntimeConfig();
    const adminApiBase = (runtimeConfig.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const { get, post, put } = useHttp(adminApiBase);

    function createIdempotencyKey(operation: string): string {
        const randomPart = globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        return `admin-ticket-${operation}-${randomPart}`;
    }

    return {
        list(query: SupportTicketListQuery) {
            return get<SupportTicketListResponse>("/support-tickets", { ...query });
        },

        getOne(ticketId: number, messagePage = 1, messageSize = 50) {
            return get<SupportTicketDetail>(`/support-tickets/${ticketId}`, {
                message_page: messagePage,
                message_size: messageSize,
            });
        },

        update(ticketId: number, payload: UpdateSupportTicketPayload) {
            return put<SupportTicketUpdateResponse, UpdateSupportTicketPayload>(
                `/support-tickets/${ticketId}`,
                payload,
                {
                    payloadMode: "query",
                    headers: {
                        "Idempotency-Key": createIdempotencyKey("update"),
                    },
                },
            );
        },

        sendMessage(ticketId: number, content: string | null, files: File[]) {
            const formData = new FormData();
            if (content) {
                formData.append("content", content);
            }
            files.forEach((file) => formData.append("files", file, file.name));

            return post<SupportTicketMessageResponse, FormData>(
                `/support-tickets/${ticketId}/messages`,
                formData,
                {
                    payloadMode: "json",
                    headers: {
                        "Idempotency-Key": createIdempotencyKey("message"),
                    },
                },
            );
        },
    };
};
