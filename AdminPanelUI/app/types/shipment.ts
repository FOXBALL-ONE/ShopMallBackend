export type CarrierCode = "manual" | "4px" | "yunexpress" | "17track";

export type ShipmentStatus =
    | "LABEL_PENDING"
    | "LABEL_CREATED"
    | "CANCEL_PENDING"
    | "IN_TRANSIT"
    | "OUT_FOR_DELIVERY"
    | "DELIVERED"
    | "CANCELLED"
    | "DELETED";

export type AllocationStatus = "ALLOCATED" | "RELEASED";
export type NormalizedTrackingStatus = "IN_TRANSIT" | "OUT_FOR_DELIVERY" | "DELIVERED" | "EXCEPTION" | "UNKNOWN";
export type ShipmentTrackSource = "WEBHOOK" | "POLL" | "MANUAL";

export interface ShipmentItem {
    order_item_id: number;
    product_snapshot: string;
    quantity: number;
    allocation_status: AllocationStatus;
}

export interface ShipmentTrack {
    carrier_event_id: string;
    status_code: string;
    normalized_status: NormalizedTrackingStatus;
    source: ShipmentTrackSource;
    location: string | null;
    description: string | null;
    occurred_at: string;
    received_at: string | null;
}

export interface Shipment {
    shipment_no: string;
    order_no: string;
    carrier: CarrierCode;
    tracking_no: string | null;
    tracking_url: string | null;
    status: ShipmentStatus;
    shipped_at: string | null;
    delivered_at: string | null;
    last_track_status: string | null;
    last_track_location: string | null;
    last_track_at: string | null;
    items: ShipmentItem[];
    tracks: ShipmentTrack[];
}

export interface AdminShipment {
    shipment: Shipment;
    carrier_label_url: string | null;
    created_by: number;
    note: string | null;
    cancel_reason: string | null;
    consecutive_track_failures: number;
    last_track_error: string | null;
}

export interface ShipmentListResponse {
    list: AdminShipment[];
}

export interface ShipmentListQuery {
    page: number;
    size: number;
    status?: ShipmentStatus;
    carrier?: CarrierCode;
    order_no?: string;
    tracking_no?: string;
    has_error?: boolean;
}

export interface ShipmentPageResponse extends ShipmentListResponse {
    pagination: {
        page: number;
        size: number;
        total_items: number;
        total_pages: number;
    };
}

export type ShipmentMutationResponse = AdminShipment;

export interface ShipmentDeleteResponse {
    shipment_no: string;
    status: "DELETED";
}

export interface ShipmentPermanentDeleteResponse {
    shipment_no: string;
    physically_deleted: boolean;
}

export interface CreateShipmentPayload {
    carrier_code: CarrierCode;
    tracking_no?: string;
    order_item_ids: number[];
    quantities: number[];
    note?: string;
}

export interface DispatchShipmentPayload {
    note?: string;
}

export interface CancelShipmentPayload {
    reason: string;
}

export interface MarkShipmentDeliveredPayload {
    occurred_at?: string;
    reason: string;
}
