<script setup lang="ts">
import { ClipboardList, LayoutDashboard, LifeBuoy, LogOut, Package, RefreshCw, Truck } from '@lucide/vue'
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
  { label: '商品管理', key: 'products', icon: renderMenuIcon(Package) },
  { label: '订单管理', key: 'orders', icon: renderMenuIcon(ClipboardList) },
  { label: '物流管理', key: 'shipments', icon: renderMenuIcon(Truck) },
  { label: '工单支持', key: 'support-tickets', icon: renderMenuIcon(LifeBuoy) },
]

const activeMenuKey = computed(() => {
  if (route.path.startsWith('/products')) return 'products'
  if (route.path.startsWith('/orders')) return 'orders'
  if (route.path.startsWith('/shipments')) return 'shipments'
  if (route.path.startsWith('/support-tickets')) return 'support-tickets'
  return 'dashboard'
})

function handleMenuSelect(key: string) {
  if (key === 'dashboard') {
    navigateTo('/')
  } else if (key === 'products') {
    navigateTo('/products')
  } else if (key === 'orders') {
    navigateTo('/orders')
  } else if (key === 'shipments') {
    navigateTo('/shipments')
  } else if (key === 'support-tickets') {
    navigateTo('/support-tickets')
  }
}

const logoutLoading = ref(false)
const { post, token, user, setAuth, clearAuth } = useHttp()
const runtimeConfig = useRuntimeConfig()
const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
const { get: getAdmin } = useHttp(adminApiBase)
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

  const currentToken = token.value
  if (!currentToken) return
  try {
    const session = await getAdmin<AdminSession>('/session')
    setAuth(currentToken, session)
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
                  <NButton @click="$router.go(0)">
                    <template #icon><RefreshCw :size="16" /></template>
                    刷新
                  </NButton>
                  <NButton type="primary" tertiary :loading="logoutLoading" @click="handleLogout">
                    <template #icon><LogOut :size="16" /></template>
                    退出登录
                  </NButton>
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
</style>
