export interface DashboardSummary {
    orders: {
        pending_payment: number;
        paid: number;
        shipped: number;
        delivered: number;
        completed: number;
        cancelled: number;
    };
    shipments: {
        label_pending: number;
        label_created: number;
        cancel_pending: number;
        in_transit: number;
        out_for_delivery: number;
        delivered: number;
        cancelled: number;
        errors: number;
    };
    tickets: {
        open: number;
        in_progress: number;
        high_priority: number;
    };
    products: {
        active: number;
        inactive: number;
        deleted: number;
        low_stock: number;
    };
}
