<script setup lang="ts">
import { Activity, ClipboardList, Gauge, LayoutDashboard, LifeBuoy, LogOut, Megaphone, Package, RefreshCw, ScrollText, Truck, Users } from '@lucide/vue'
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'
import type { Component } from 'vue'
import type { MenuOption } from 'naive-ui'
import type { AdminSession } from '~/types/http'

const route = useRoute()

function renderMenuIcon(icon: Component) {
  return () => h(icon, { size: 20, strokeWidth: 1.8 })
}

const menuOptions: MenuOption[] = [
  { label: '仪表盘', key: 'dashboard', icon: renderMenuIcon(LayoutDashboard) },
  { label: '用户管理', key: 'users', icon: renderMenuIcon(Users) },
  { label: '商品管理', key: 'products', icon: renderMenuIcon(Package) },
  { label: '订单管理', key: 'orders', icon: renderMenuIcon(ClipboardList) },
  { label: '物流管理', key: 'shipments', icon: renderMenuIcon(Truck) },
  { label: '工单支持', key: 'support-tickets', icon: renderMenuIcon(LifeBuoy) },
  { label: '公告管理', key: 'announcements', icon: renderMenuIcon(Megaphone) },
  { label: '系统监控', key: 'system-status', icon: renderMenuIcon(Activity) },
  { label: '限速设置', key: 'rate-limits', icon: renderMenuIcon(Gauge) },
  { label: '日志中心', key: 'logs', icon: renderMenuIcon(ScrollText) },
]

const activeMenuKey = computed(() => {
  if (route.path.startsWith('/users')) return 'users'
  if (route.path.startsWith('/products')) return 'products'
  if (route.path.startsWith('/orders')) return 'orders'
  if (route.path.startsWith('/shipments')) return 'shipments'
  if (route.path.startsWith('/support-tickets')) return 'support-tickets'
  if (route.path.startsWith('/announcements')) return 'announcements'
  if (route.path.startsWith('/system-status')) return 'system-status'
  if (route.path.startsWith('/rate-limits')) return 'rate-limits'
  if (route.path.startsWith('/logs')) return 'logs'
  return 'dashboard'
})

function handleMenuSelect(key: string) {
  if (key === 'dashboard') {
    navigateTo('/')
  } else if (key === 'users') {
    navigateTo('/users')
  } else if (key === 'products') {
    navigateTo('/products')
  } else if (key === 'orders') {
    navigateTo('/orders')
  } else if (key === 'shipments') {
    navigateTo('/shipments')
  } else if (key === 'support-tickets') {
    navigateTo('/support-tickets')
  } else if (key === 'announcements') {
    navigateTo('/announcements')
  } else if (key === 'system-status') {
    navigateTo('/system-status')
  } else if (key === 'rate-limits') {
    navigateTo('/rate-limits')
  } else if (key === 'logs') {
    navigateTo('/logs')
  }
}

const logoutLoading = ref(false)
const runtimeConfig = useRuntimeConfig()
const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
const { post, clearAuth } = useHttp()
const { get: getAdmin, token, user, setAuth } = useHttp(adminApiBase)
const administratorName = computed(() => {
  const fullName = [user.value?.first_name, user.value?.last_name].filter(Boolean).join(' ')
  return fullName || user.value?.username || '管理员'
})

const siderCollapsed = ref(false)
let narrowScreenQuery: MediaQueryList | undefined

function handleNarrowScreenChange(event: MediaQueryListEvent) {
  if (event.matches) siderCollapsed.value = true
}

onMounted(async () => {
  narrowScreenQuery = window.matchMedia('(max-width: 900px)')
  siderCollapsed.value = narrowScreenQuery.matches
  narrowScreenQuery.addEventListener('change', handleNarrowScreenChange)

  if (!token.value) return
  try {
    const session = await getAdmin<AdminSession>('/session')
    const currentToken = token.value
    if (currentToken) setAuth(currentToken, session)
  } catch {
    // 401 由 useHttp 统一清理并跳转；短暂网络故障保留当前本地会话。
  }
})

onBeforeUnmount(() => {
  narrowScreenQuery?.removeEventListener('change', handleNarrowScreenChange)
})

async function handleLogout() {
  logoutLoading.value = true
  try {
    await post<Record<string, never>>('/auth/logout')
  } catch {
    // 本地登录态仍需清除，后端刷新令牌会按自身有效期失效。
  } finally {
    clearAuth()
    logoutLoading.value = false
    await navigateTo('/login')
  }
}
</script>

<template>
  <NConfigProvider>
    <NMessageProvider>
      <NDialogProvider>
        <NNotificationProvider>
          <NLayout has-sider style="height: 100vh">
            <NLayoutSider
              bordered
              collapse-mode="width"
              :collapsed="siderCollapsed"
              :collapsed-width="64"
              :width="220"
              show-trigger
              @collapse="siderCollapsed = true"
              @expand="siderCollapsed = false"
            >
              <div class="logo">
                <span>{{ siderCollapsed ? 'SM' : 'ShopMall' }}</span>
              </div>
              <NMenu
                :value="activeMenuKey"
                :options="menuOptions"
                :collapsed-width="64"
                :collapsed-icon-size="22"
                @update:value="handleMenuSelect"
              />
            </NLayoutSider>

            <NLayout>
              <NLayoutHeader bordered class="header">
                <div class="header-title">管理后台</div>
                <NSpace>
                  <NText depth="3" class="administrator-name">{{ administratorName }}</NText>
                  <NTooltip>
                    <template #trigger>
                      <NButton class="header-action" aria-label="刷新" @click="$router.go(0)">
                        <template #icon><RefreshCw :size="16" /></template>
                        <span class="header-action-label">刷新</span>
                      </NButton>
                    </template>
                    刷新
                  </NTooltip>
                  <NTooltip>
                    <template #trigger>
                      <NButton
                        class="header-action"
                        type="primary"
                        tertiary
                        aria-label="退出登录"
                        :loading="logoutLoading"
                        @click="handleLogout"
                      >
                        <template #icon><LogOut :size="16" /></template>
                        <span class="header-action-label">退出登录</span>
                      </NButton>
                    </template>
                    退出登录
                  </NTooltip>
                </NSpace>
              </NLayoutHeader>

              <NLayoutContent class="content">
                <slot />
              </NLayoutContent>
            </NLayout>
          </NLayout>
        </NNotificationProvider>
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>

<style scoped>
.logo {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 600;
  border-bottom: 1px solid var(--n-border-color, #efeff5);
}

.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
}

.content {
  padding: 24px;
}

@media (max-width: 720px) {
  .header {
    padding: 0 12px;
  }

  .administrator-name {
    display: none;
  }

  .content {
    padding: 12px;
  }
}

@media (max-width: 480px) {
  .header {
    padding: 0 8px;
  }

  .header-title {
    font-size: 14px;
    white-space: nowrap;
  }

  .header-action {
    width: 32px;
    padding: 0;
  }

  .header-action-label {
    display: none;
  }
}
</style>
