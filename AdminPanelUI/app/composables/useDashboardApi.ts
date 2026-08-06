import type {
    DashboardOperationsReport,
    DashboardSummary,
    DashboardSystemStatus,
} from "~/types/dashboard";

export const useDashboardApi = () => {
    const config = useRuntimeConfig();
    const adminApiBase = (config.public.adminApiBase as string) || "http://127.0.0.1:8080/admin/api";
    const { get } = useHttp(adminApiBase);

    return {
        summary(lowStockThreshold = 10) {
            return get<DashboardSummary>("/dashboard/summary", { low_stock_threshold: lowStockThreshold });
        },
        operations(days = 14) {
            return get<DashboardOperationsReport>("/dashboard/operations", { days });
        },
        systemStatus() {
            return get<DashboardSystemStatus>("/dashboard/system-status");
        },
    };
};
