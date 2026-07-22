<script setup lang="ts">
import type { MenuOption } from 'naive-ui'

const menuOptions: MenuOption[] = [
  { label: '仪表盘', key: 'dashboard' },
  { label: '商品管理', key: 'products' },
  { label: '订单管理', key: 'orders' },
]
</script>

<template>
  <NConfigProvider>
    <NLayout has-sider style="height: 100vh">
      <!-- 侧边导航 -->
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
        <NMenu :options="menuOptions" :collapsed-width="64" :collapsed-icon-size="22" />
      </NLayoutSider>

      <NLayout>
        <!-- 顶栏 -->
        <NLayoutHeader bordered class="header">
          <div class="header-title">管理后台</div>
          <NSpace>
            <NButton>刷新</NButton>
            <NButton type="primary" tertiary>退出登录</NButton>
          </NSpace>
        </NLayoutHeader>

        <!-- 内容区 -->
        <NLayoutContent class="content">
          <slot />
        </NLayoutContent>
      </NLayout>
    </NLayout>
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
