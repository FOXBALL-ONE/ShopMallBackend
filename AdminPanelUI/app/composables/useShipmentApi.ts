import type {
    CancelShipmentPayload,
    CarrierCode,
    CreateShipmentPayload,
    DispatchShipmentPayload,
    MarkShipmentDeliveredPayload,
    ShipmentListResponse,
    ShipmentListQuery,
    ShipmentPageResponse,
    ShipmentMutationResponse,
    ShipmentDeleteResponse,
    ShipmentPermanentDeleteResponse,
} from "~/types/shipment";

export const CARRIER_OPTIONS: Array<{ label: string; value: CarrierCode }> = [
    { label: "手动物流", value: "manual" },
    { label: "4PX", value: "4px" },
    { label: "YunExpress", value: "yunexpress" },
    { label: "17TRACK", value: "17track" },
];

/** 管理端运单 API，对齐 AdminShipmentController 的订单运单与状态流转接口。 */
export const useShipmentApi = () => {
    const config = useRuntimeConfig();
    const adminApiBase = (config.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const { get, post, delete: remove } = useHttp(adminApiBase);

    function createIdempotencyKey(operation: string): string {
        const randomPart = globalThis.crypto?.randomUUID?.()
            ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        return `admin-shipment-${operation}-${randomPart}`;
    }

    return {
        listAll(query: ShipmentListQuery) {
            return get<ShipmentPageResponse>("/shipments", { ...query });
        },

        get(shipmentNo: string) {
            return get<ShipmentMutationResponse>(`/shipments/${encodeURIComponent(shipmentNo)}`);
        },

        list(orderNo: string) {
            return get<ShipmentListResponse>(`/orders/${encodeURIComponent(orderNo)}/shipments`);
        },

        create(orderNo: string, payload: CreateShipmentPayload) {
            return post<ShipmentMutationResponse, CreateShipmentPayload>(
                `/orders/${encodeURIComponent(orderNo)}/shipments`,
                payload,
                {
                    payloadMode: "query",
                    headers: { "Idempotency-Key": createIdempotencyKey("create") },
                },
            );
        },

        dispatch(shipmentNo: string, payload: DispatchShipmentPayload) {
            return post<ShipmentMutationResponse, DispatchShipmentPayload>(
                `/shipments/${encodeURIComponent(shipmentNo)}/dispatch`,
                payload,
                {
                    payloadMode: "query",
                    headers: { "Idempotency-Key": createIdempotencyKey("dispatch") },
                },
            );
        },

        cancel(shipmentNo: string, payload: CancelShipmentPayload) {
            return post<ShipmentMutationResponse, CancelShipmentPayload>(
                `/shipments/${encodeURIComponent(shipmentNo)}/cancel`,
                payload,
                {
                    payloadMode: "query",
                    headers: { "Idempotency-Key": createIdempotencyKey("cancel") },
                },
            );
        },

        markDelivered(shipmentNo: string, payload: MarkShipmentDeliveredPayload) {
            return post<ShipmentMutationResponse, MarkShipmentDeliveredPayload>(
                `/shipments/${encodeURIComponent(shipmentNo)}/delivered`,
                payload,
                {
                    payloadMode: "query",
                    headers: { "Idempotency-Key": createIdempotencyKey("delivered") },
                },
            );
        },

        deleteShipment(shipmentNo: string) {
            return remove<ShipmentDeleteResponse>(`/shipments/${encodeURIComponent(shipmentNo)}`);
        },

        permanentlyDeleteShipment(shipmentNo: string) {
            return remove<ShipmentPermanentDeleteResponse>(
                `/shipments/${encodeURIComponent(shipmentNo)}/permanent`,
            );
        },
    };
};
