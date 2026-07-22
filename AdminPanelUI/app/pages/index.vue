<script setup lang="ts">
import { ref } from 'vue'

// 仪表盘首页：验证 Naive UI 自动引入是否生效
const count = ref(0)
</script>

<template>
  <NSpace vertical :size="16">
    <NCard title="欢迎" size="small">
      <p>ShopMall 管理后台。点击下方按钮验证 Naive UI 是否正常工作。</p>
      <NSpace>
        <NButton @click="count--">
          -1
        </NButton>
        <NTag :type="count > 0 ? 'success' : 'default'" size="large">
          {{ count }}
        </NTag>
        <NButton type="primary" @click="count++">
          +1
        </NButton>
      </NSpace>
    </NCard>

    <NCard title="快速入口" size="small">
      <NSpace>
        <NButton tertiary>商品管理</NButton>
        <NButton tertiary>订单管理</NButton>
      </NSpace>
    </NCard>
  </NSpace>
</template>
