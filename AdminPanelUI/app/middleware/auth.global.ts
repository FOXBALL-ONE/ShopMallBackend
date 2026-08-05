import type { UserInfo } from "~/types/http";

export default defineNuxtRouteMiddleware((to) => {
    const token = useCookie<string | null>("admin_auth_token");
    const user = useCookie<UserInfo | null>("admin_user_info");
    const authenticated = Boolean(token.value && user.value?.role === "ADMIN");

    if (to.path === "/login") {
        if (authenticated) return navigateTo("/");
        return;
    }
    if (!authenticated) {
        token.value = null;
        user.value = null;
        return navigateTo("/login");
    }
});
