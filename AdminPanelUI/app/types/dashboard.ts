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

export interface DashboardSystemStatus {
    status: "UP" | "DEGRADED";
    generated_at: string;
    application: {
        name: string;
        version: string;
        started_at: string;
        uptime_seconds: number;
        available_processors: number;
        system_load_average: number | null;
        process_cpu_usage: number | null;
        system_cpu_usage: number | null;
    };
    jvm: {
        heap_used_bytes: number;
        heap_committed_bytes: number;
        heap_max_bytes: number;
        non_heap_used_bytes: number;
        live_threads: number;
        peak_threads: number;
        daemon_threads: number;
        gc_collection_count: number;
        gc_collection_time_ms: number;
    };
    database: {
        available: boolean;
        latency_ms: number;
        active_connections: number | null;
        idle_connections: number | null;
        max_connections: number | null;
    };
    redis: {
        available: boolean;
        latency_ms: number;
        key_count: number | null;
        used_memory_bytes: number | null;
        connected_clients: number | null;
        version: string | null;
    };
}
