<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { DashboardSummary } from '~/types/dashboard'

definePageMeta({ layout: 'default' })

const api = useDashboardApi()
const loading = ref(false)
const error = ref('')
const summary = ref<DashboardSummary | null>(null)

const orderTotal = computed(() => {
  const orders = summary.value?.orders
  return orders ? Object.values(orders).reduce((total, value) => total + value, 0) : 0
})
const shipmentInProgress = computed(() => {
  const shipments = summary.value?.shipments
  return shipments ? shipments.label_pending + shipments.label_created + shipments.cancel_pending
    + shipments.in_transit + shipments.out_for_delivery : 0
})
const pendingTickets = computed(() => {
  const tickets = summary.value?.tickets
  return tickets ? tickets.open + tickets.in_progress : 0
})

async function loadSummary() {
  loading.value = true
  error.value = ''
  try {
    summary.value = await api.summary()
  } catch (reason: any) {
    error.value = reason?.statusMessage || reason?.message || '加载仪表盘失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => void loadSummary())
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading">
      <div>
        <h2>仪表盘</h2>
        <NText depth="3">运营状态总览</NText>
      </div>
      <NButton :loading="loading" @click="loadSummary">刷新</NButton>
    </div>

    <NAlert v-if="error" type="error" :bordered="false" class="dashboard-alert">
      {{ error }}
    </NAlert>

    <NSpin :show="loading && !summary">
      <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="12" responsive="screen">
        <NGridItem><NCard size="small"><NStatistic label="全部订单" :value="orderTotal" /></NCard></NGridItem>
        <NGridItem><NCard size="small"><NStatistic label="待发货订单" :value="summary?.orders.paid ?? 0" /></NCard></NGridItem>
        <NGridItem><NCard size="small"><NStatistic label="履约中运单" :value="shipmentInProgress" /></NCard></NGridItem>
        <NGridItem><NCard size="small"><NStatistic label="待处理工单" :value="pendingTickets" /></NCard></NGridItem>
      </NGrid>

      <div class="dashboard-section">
        <div class="section-heading">
          <h3>需要关注</h3>
        </div>
        <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="12" responsive="screen">
          <NGridItem><NCard size="small"><NStatistic label="物流异常" :value="summary?.shipments.errors ?? 0" /></NCard></NGridItem>
          <NGridItem><NCard size="small"><NStatistic label="高优先级工单" :value="summary?.tickets.high_priority ?? 0" /></NCard></NGridItem>
          <NGridItem><NCard size="small"><NStatistic label="低库存商品" :value="summary?.products.low_stock ?? 0" /></NCard></NGridItem>
          <NGridItem><NCard size="small"><NStatistic label="下架商品" :value="summary?.products.inactive ?? 0" /></NCard></NGridItem>
        </NGrid>
      </div>

      <div class="dashboard-section">
        <div class="section-heading"><h3>快捷入口</h3></div>
        <NSpace>
          <NButton type="primary" @click="navigateTo('/orders')">处理订单</NButton>
          <NButton @click="navigateTo('/shipments')">查看物流</NButton>
          <NButton @click="navigateTo('/support-tickets')">处理工单</NButton>
          <NButton @click="navigateTo('/products')">管理商品</NButton>
        </NSpace>
      </div>
    </NSpin>
  </div>
</template>

<style scoped>
.dashboard-page {
  max-width: 1280px;
}

.page-heading,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.page-heading {
  margin-bottom: 16px;
}

.page-heading h2,
.section-heading h3 {
  margin: 0;
}

.page-heading h2 {
  margin-bottom: 4px;
  font-size: 22px;
}

.section-heading h3 {
  font-size: 16px;
}

.dashboard-section {
  margin-top: 24px;
}

.section-heading {
  margin-bottom: 12px;
}

.dashboard-alert {
  margin-bottom: 16px;
}
</style>
