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

export interface DashboardRevenueAmount {
    currency: string;
    amount: number;
}

export interface DashboardOperationsPeriod {
    orders: number;
    paid_orders: number;
    new_customers: number;
    revenue: DashboardRevenueAmount[];
}

export interface DashboardDailyOperations extends DashboardOperationsPeriod {
    date: string;
}

export interface DashboardOperationsReport {
    period_days: number;
    current_period: DashboardOperationsPeriod;
    previous_period: DashboardOperationsPeriod;
    daily: DashboardDailyOperations[];
}
