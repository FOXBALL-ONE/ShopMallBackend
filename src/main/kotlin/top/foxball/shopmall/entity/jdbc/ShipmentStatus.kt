package top.foxball.shopmall.entity.jdbc

enum class ShipmentStatus {
    LABEL_PENDING,
    LABEL_CREATED,
    CANCEL_PENDING,
    IN_TRANSIT,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
}

enum class AllocationStatus { ALLOCATED, RELEASED }

enum class NormalizedTrackingStatus { IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN }

enum class TrackSource { WEBHOOK, POLL, MANUAL }
