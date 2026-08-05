<script setup lang="ts">
import { computed } from 'vue'
import type { MenuOption } from 'naive-ui'

const route = useRoute()
const menuOptions: MenuOption[] = [
  { label: '仪表盘', key: 'dashboard' },
  { label: '商品管理', key: 'products' },
  { label: '订单管理', key: 'orders' },
  { label: '工单支持', key: 'support-tickets' },
]

const activeMenuKey = computed(() => {
  if (route.path.startsWith('/products')) return 'products'
  if (route.path.startsWith('/orders')) return 'orders'
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
  } else if (key === 'support-tickets') {
    navigateTo('/support-tickets')
  }
}

const { clearAuth } = useHttp()
function handleLogout() {
  clearAuth()
  navigateTo('/login')
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
                  <NButton @click="$router.go(0)">刷新</NButton>
                  <NButton type="primary" tertiary @click="handleLogout">退出登录</NButton>
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
