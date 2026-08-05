<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { MenuOption } from 'naive-ui'
import type { AdminSession } from '~/types/http'

const route = useRoute()
const menuOptions: MenuOption[] = [
  { label: '仪表盘', key: 'dashboard' },
  { label: '商品管理', key: 'products' },
  { label: '订单管理', key: 'orders' },
  { label: '物流管理', key: 'shipments' },
  { label: '工单支持', key: 'support-tickets' },
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

onMounted(async () => {
  const currentToken = token.value
  if (!currentToken) return
  try {
    const session = await getAdmin<AdminSession>('/session')
    setAuth(currentToken, session)
  } catch {
    // 401 由 useHttp 统一清理并跳转；短暂网络故障保留当前本地会话。
  }
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
              :collapsed-width="64"
              :width="220"
              show-trigger
            >
              <div class="logo">
                <span>ShopMall</span>
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
                  <NText depth="3">{{ administratorName }}</NText>
                  <NButton @click="$router.go(0)">刷新</NButton>
                  <NButton type="primary" tertiary :loading="logoutLoading" @click="handleLogout">退出登录</NButton>
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
</style>
