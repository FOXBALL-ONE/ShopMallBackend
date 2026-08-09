<script setup lang="ts">
import {
  BarChart3,
  CircleDollarSign,
  LifeBuoy,
  Package,
  RefreshCw,
  ShoppingCart,
  Truck,
  Users,
} from '@lucide/vue'
import { computed, onMounted, ref, watch } from 'vue'
import type {
  DashboardOperationsReport,
  DashboardRevenueAmount,
  DashboardSummary,
} from '~/types/dashboard'

definePageMeta({ layout: 'default' })

const api = useDashboardApi()
const summary = ref<DashboardSummary | null>(null)
const operations = ref<DashboardOperationsReport | null>(null)
const selectedDays = ref(14)
const loading = ref(false)
const operationsLoading = ref(false)
const error = ref('')

const periodOptions = [
  { label: '7 天', value: 7 },
  { label: '14 天', value: 14 },
  { label: '30 天', value: 30 },
  { label: '90 天', value: 90 },
]

const orderTotal = computed(() => {
  const orders = summary.value?.orders
  return orders ? Object.values(orders).reduce((total, value) => total + value, 0) : 0
})

const shipmentInProgress = computed(() => {
  const shipments = summary.value?.shipments
  return shipments
    ? shipments.label_pending + shipments.label_created + shipments.cancel_pending
      + shipments.in_transit + shipments.out_for_delivery
    : 0
})

const pendingTickets = computed(() => {
  const tickets = summary.value?.tickets
  return tickets ? tickets.open + tickets.in_progress : 0
})

const paymentRate = computed(() => {
  const current = operations.value?.current_period
  return current?.orders ? current.paid_orders / current.orders : 0
})

const primaryRevenue = computed(() => operations.value?.current_period.revenue[0] ?? null)
const previousPrimaryRevenue = computed(() => {
  const currency = primaryRevenue.value?.currency
  if (!currency) return null
  return operations.value?.previous_period.revenue.find(item => item.currency === currency) ?? null
})

const revenueRows = computed(() => {
  const current = operations.value?.current_period.revenue ?? []
  const previous = operations.value?.previous_period.revenue ?? []
  const currencies = [...new Set([...current, ...previous].map(item => item.currency))].sort()
  return currencies.map((currency) => ({
    currency,
    current: current.find(item => item.currency === currency)?.amount ?? 0,
    previous: previous.find(item => item.currency === currency)?.amount ?? 0,
  }))
})

const orderStatusRows = computed(() => {
  const orders = summary.value?.orders
  if (!orders) return []
  return [
    { label: '待支付', value: orders.pending_payment, color: '#d97706' },
    { label: '待发货', value: orders.paid, color: '#2563eb' },
    { label: '已发货', value: orders.shipped, color: '#0891b2' },
    { label: '已送达', value: orders.delivered, color: '#0f9f6e' },
    { label: '已完成', value: orders.completed, color: '#16a34a' },
    { label: '已取消', value: orders.cancelled, color: '#71717a' },
  ]
})

function getErrorMessage(reason: unknown): string {
  const failure = reason as { statusMessage?: string; message?: string }
  return failure?.statusMessage || failure?.message || '请求失败'
}

function formatNumber(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

function formatMoney(amount: number, currency: string): string {
  try {
    return new Intl.NumberFormat('zh-CN', {
      style: 'currency',
      currency,
      maximumFractionDigits: 2,
    }).format(amount)
  } catch {
    return `${currency} ${amount.toFixed(2)}`
  }
}

function formatRevenue(revenue: DashboardRevenueAmount | null): string {
  return revenue ? formatMoney(revenue.amount, revenue.currency) : '0.00'
}

function changePercent(current: number, previous: number): number | null {
  if (previous === 0) return current === 0 ? 0 : null
  return ((current - previous) / previous) * 100
}

function changeLabel(current: number, previous: number): string {
  const change = changePercent(current, previous)
  if (change === null) return '新增'
  if (change === 0) return '持平'
  return `${change > 0 ? '+' : ''}${change.toFixed(1)}%`
}

function changeClass(current: number, previous: number): string {
  if (current > previous) return 'change-up'
  if (current < previous) return 'change-down'
  return 'change-flat'
}

function orderShare(value: number): number {
  return orderTotal.value > 0 ? Math.round((value / orderTotal.value) * 100) : 0
}

async function loadOperations() {
  operationsLoading.value = true
  try {
    operations.value = await api.operations(selectedDays.value)
  } catch (reason) {
    error.value = `运营报表：${getErrorMessage(reason)}`
  } finally {
    operationsLoading.value = false
  }
}

async function loadDashboard() {
  loading.value = true
  error.value = ''
  const [summaryResult, operationsResult] = await Promise.allSettled([
    api.summary(),
    api.operations(selectedDays.value),
  ] as const)
  const failures: string[] = []
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  else failures.push(`运营待办：${getErrorMessage(summaryResult.reason)}`)
  if (operationsResult.status === 'fulfilled') operations.value = operationsResult.value
  else failures.push(`运营报表：${getErrorMessage(operationsResult.reason)}`)
  error.value = failures.join('；')
  loading.value = false
}

watch(selectedDays, () => void loadOperations())

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading">
      <div>
        <h2>仪表盘</h2>
        <NText depth="3">经营与履约总览</NText>
      </div>
      <div class="heading-actions">
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :loading="loading" aria-label="刷新仪表盘" @click="loadDashboard">
              <template #icon><RefreshCw :size="17" /></template>
            </NButton>
          </template>
          刷新仪表盘
        </NTooltip>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false" closable class="dashboard-alert" @close="error = ''">
      {{ error }}
    </NAlert>

    <NSpin :show="loading && !summary && !operations">
      <section class="dashboard-section first-section">
        <div class="section-heading">
          <div>
            <h3>经营概览</h3>
            <NText depth="3">与上一同等周期对比</NText>
          </div>
          <NRadioGroup
            v-model:value="selectedDays"
            size="small"
            :disabled="operationsLoading"
            class="period-selector"
          >
            <NRadioButton v-for="option in periodOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </NRadioButton>
          </NRadioGroup>
        </div>

        <div class="metric-grid">
          <div>
            <NCard size="small" class="metric-card">
              <div class="metric-topline">
                <span class="metric-icon metric-icon-blue"><ShoppingCart :size="18" /></span>
                <span
                  class="metric-change"
                  :class="changeClass(operations?.current_period.orders ?? 0, operations?.previous_period.orders ?? 0)"
                >
                  {{ changeLabel(operations?.current_period.orders ?? 0, operations?.previous_period.orders ?? 0) }}
                </span>
              </div>
              <div class="metric-label">周期订单</div>
              <div class="metric-value">{{ formatNumber(operations?.current_period.orders ?? 0) }}</div>
            </NCard>
          </div>
          <div>
            <NCard size="small" class="metric-card">
              <div class="metric-topline">
                <span class="metric-icon metric-icon-green"><CircleDollarSign :size="18" /></span>
                <span
                  class="metric-change"
                  :class="changeClass(primaryRevenue?.amount ?? 0, previousPrimaryRevenue?.amount ?? 0)"
                >
                  {{ changeLabel(primaryRevenue?.amount ?? 0, previousPrimaryRevenue?.amount ?? 0) }}
                </span>
              </div>
              <div class="metric-label">已支付订单金额</div>
              <div class="metric-value metric-money">{{ formatRevenue(primaryRevenue) }}</div>
            </NCard>
          </div>
          <div>
            <NCard size="small" class="metric-card">
              <div class="metric-topline">
                <span class="metric-icon metric-icon-amber"><BarChart3 :size="18" /></span>
                <span class="metric-context">{{ formatNumber(operations?.current_period.paid_orders ?? 0) }} 单</span>
              </div>
              <div class="metric-label">订单支付率</div>
              <div class="metric-value">{{ (paymentRate * 100).toFixed(1) }}%</div>
            </NCard>
          </div>
          <div>
            <NCard size="small" class="metric-card">
              <div class="metric-topline">
                <span class="metric-icon metric-icon-cyan"><Users :size="18" /></span>
                <span
                  class="metric-change"
                  :class="changeClass(operations?.current_period.new_customers ?? 0, operations?.previous_period.new_customers ?? 0)"
                >
                  {{ changeLabel(operations?.current_period.new_customers ?? 0, operations?.previous_period.new_customers ?? 0) }}
                </span>
              </div>
              <div class="metric-label">新增客户</div>
              <div class="metric-value">{{ formatNumber(operations?.current_period.new_customers ?? 0) }}</div>
            </NCard>
          </div>
        </div>

        <div class="report-grid">
          <div class="trend-panel">
            <NCard size="small" class="report-card">
              <template #header>
                <div class="card-title">
                  <span>订单与客户趋势</span>
                  <NSpin v-if="operationsLoading" :size="16" />
                </div>
              </template>
              <DashboardTrendChart :daily="operations?.daily ?? []" />
            </NCard>
          </div>
          <div>
            <NCard size="small" class="report-card revenue-card">
              <template #header>收入拆分</template>
              <div v-if="revenueRows.length" class="revenue-list">
                <div v-for="row in revenueRows" :key="row.currency" class="revenue-row">
                  <div class="revenue-row-main">
                    <span class="currency-code">{{ row.currency }}</span>
                    <strong>{{ formatMoney(row.current, row.currency) }}</strong>
                  </div>
                  <div class="revenue-comparison">
                    <span>上期 {{ formatMoney(row.previous, row.currency) }}</span>
                    <span :class="changeClass(row.current, row.previous)">
                      {{ changeLabel(row.current, row.previous) }}
                    </span>
                  </div>
                </div>
              </div>
              <NEmpty v-else size="small" description="暂无已支付订单" />
              <div class="revenue-footnote">
                <span>已支付订单</span>
                <strong>{{ formatNumber(operations?.current_period.paid_orders ?? 0) }}</strong>
              </div>
            </NCard>
          </div>
        </div>
      </section>

      <section class="dashboard-section">
        <div class="section-heading">
          <div>
            <h3>实时运营</h3>
            <NText depth="3">当前业务队列与风险项</NText>
          </div>
        </div>
        <div class="operations-grid">
          <div>
            <NCard size="small" class="operations-card">
              <template #header>当前待办</template>
              <button class="operation-row" type="button" @click="navigateTo('/orders')">
                <span><ShoppingCart :size="16" />待发货订单</span>
                <strong>{{ formatNumber(summary?.orders.paid ?? 0) }}</strong>
              </button>
              <button class="operation-row" type="button" @click="navigateTo('/shipments')">
                <span><Truck :size="16" />履约中运单</span>
                <strong>{{ formatNumber(shipmentInProgress) }}</strong>
              </button>
              <button class="operation-row" type="button" @click="navigateTo('/support-tickets')">
                <span><LifeBuoy :size="16" />待处理工单</span>
                <strong>{{ formatNumber(pendingTickets) }}</strong>
              </button>
              <button class="operation-row" type="button" @click="navigateTo('/products')">
                <span><Package :size="16" />低库存商品</span>
                <strong>{{ formatNumber(summary?.products.low_stock ?? 0) }}</strong>
              </button>
            </NCard>
          </div>
          <div>
            <NCard size="small" class="operations-card">
              <template #header>订单状态分布</template>
              <div v-for="row in orderStatusRows" :key="row.label" class="distribution-row">
                <div class="distribution-label">
                  <span>{{ row.label }}</span>
                  <span>{{ formatNumber(row.value) }} · {{ orderShare(row.value) }}%</span>
                </div>
                <NProgress
                  type="line"
                  :percentage="orderShare(row.value)"
                  :color="row.color"
                  :height="6"
                  :show-indicator="false"
                  :border-radius="2"
                />
              </div>
            </NCard>
          </div>
          <div>
            <NCard size="small" class="operations-card">
              <template #header>风险与供给</template>
              <div class="risk-grid">
                <div class="risk-item risk-red">
                  <span>物流异常</span>
                  <strong>{{ formatNumber(summary?.shipments.errors ?? 0) }}</strong>
                </div>
                <div class="risk-item risk-amber">
                  <span>高优工单</span>
                  <strong>{{ formatNumber(summary?.tickets.high_priority ?? 0) }}</strong>
                </div>
                <div class="risk-item risk-blue">
                  <span>在售商品</span>
                  <strong>{{ formatNumber(summary?.products.active ?? 0) }}</strong>
                </div>
                <div class="risk-item risk-gray">
                  <span>下架商品</span>
                  <strong>{{ formatNumber(summary?.products.inactive ?? 0) }}</strong>
                </div>
              </div>
            </NCard>
          </div>
        </div>
      </section>

    </NSpin>
  </div>
</template>

<style scoped>
.dashboard-page {
  width: 100%;
  max-width: 1440px;
  color: #27272a;
}

.page-heading,
.section-heading,
.heading-actions,
.card-title,
.metric-topline,
.revenue-row-main,
.revenue-comparison,
.revenue-footnote,
.distribution-label {
  display: flex;
  align-items: center;
}

.page-heading,
.section-heading,
.metric-topline,
.revenue-row-main,
.revenue-comparison,
.revenue-footnote,
.distribution-label {
  justify-content: space-between;
}

.page-heading {
  min-height: 48px;
  margin-bottom: 16px;
}

.page-heading h2,
.section-heading h3 {
  margin: 0;
  letter-spacing: 0;
}

.page-heading h2 {
  margin-bottom: 3px;
  font-size: 22px;
}

.section-heading h3 {
  margin-bottom: 2px;
  font-size: 16px;
}

.heading-actions {
  gap: 8px;
}

.dashboard-alert {
  margin-bottom: 16px;
}

.dashboard-section {
  margin-top: 28px;
}

.first-section {
  margin-top: 0;
}

.section-heading {
  min-height: 40px;
  margin-bottom: 12px;
}

.period-selector {
  flex-shrink: 0;
}

.metric-grid,
.report-grid,
.operations-grid {
  display: grid;
  gap: 12px;
}

.metric-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.report-grid {
  grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
}

.operations-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.metric-grid > div,
.report-grid > div,
.operations-grid > div,
.trend-panel {
  min-width: 0;
}

.metric-card {
  min-height: 142px;
  border-radius: 6px;
}

.metric-topline {
  min-height: 32px;
  margin-bottom: 13px;
}

.metric-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 5px;
  color: #2563eb;
  background: #eff6ff;
}

.metric-icon-green {
  color: #087f5b;
  background: #ecfdf5;
}

.metric-icon-amber {
  color: #b45309;
  background: #fffbeb;
}

.metric-icon-cyan {
  color: #0e7490;
  background: #ecfeff;
}

.metric-change,
.metric-context {
  font-size: 12px;
  font-weight: 600;
}

.metric-context,
.change-flat {
  color: #71717a;
}

.change-up {
  color: #087f5b;
}

.change-down {
  color: #dc2626;
}

.metric-label {
  color: #71717a;
  font-size: 12px;
}

.metric-value {
  margin-top: 4px;
  color: #18181b;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.metric-money {
  font-size: 24px;
}

.report-grid {
  margin-top: 12px;
}

.report-card {
  height: 372px;
  border-radius: 6px;
}

.card-title {
  min-height: 20px;
  gap: 8px;
}

.revenue-card :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.revenue-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.revenue-row {
  padding: 13px 0;
  border-bottom: 1px solid #f1f1f3;
}

.currency-code {
  display: inline-block;
  min-width: 40px;
  color: #52525b;
  font-size: 12px;
  font-weight: 650;
}

.revenue-row-main strong {
  color: #18181b;
  font-size: 15px;
  overflow-wrap: anywhere;
}

.revenue-comparison {
  margin-top: 5px;
  color: #71717a;
  font-size: 12px;
}

.revenue-footnote {
  padding-top: 13px;
  border-top: 1px solid #e4e4e7;
  color: #52525b;
  font-size: 12px;
}

.revenue-footnote strong {
  color: #18181b;
  font-size: 16px;
}

.operations-card {
  min-height: 288px;
  border-radius: 6px;
}

.operation-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 48px;
  padding: 0;
  border: 0;
  border-bottom: 1px solid #f1f1f3;
  color: #3f3f46;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.operation-row:last-child {
  border-bottom: 0;
}

.operation-row:hover {
  color: #2563eb;
}

.operation-row span {
  display: flex;
  align-items: center;
  gap: 8px;
}

.operation-row strong {
  color: #18181b;
  font-size: 15px;
}

.distribution-row {
  margin-bottom: 12px;
}

.distribution-row:last-child {
  margin-bottom: 0;
}

.distribution-label {
  margin-bottom: 5px;
  color: #52525b;
  font-size: 12px;
}

.risk-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.risk-item {
  min-height: 91px;
  padding: 13px;
  border: 1px solid #e4e4e7;
  border-left-width: 3px;
  border-radius: 5px;
  background: #fafafa;
}

.risk-item span {
  display: block;
  color: #71717a;
  font-size: 12px;
}

.risk-item strong {
  display: block;
  margin-top: 10px;
  color: #18181b;
  font-size: 23px;
}

.risk-red { border-left-color: #dc2626; }
.risk-amber { border-left-color: #d97706; }
.risk-blue { border-left-color: #2563eb; }
.risk-gray { border-left-color: #71717a; }

@media (max-width: 1279px) {
  .report-grid,
  .operations-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 1023px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .page-heading,
  .section-heading {
    align-items: flex-start;
    gap: 10px;
  }

  .section-heading {
    flex-direction: column;
  }

  .period-selector {
    display: flex;
    width: 100%;
  }

  .period-selector :deep(.n-radio-button) {
    flex: 1;
    min-width: 0;
    text-align: center;
  }

  .period-selector :deep(.n-radio-button__label) {
    padding: 0 7px;
  }

  .metric-card {
    min-height: 132px;
  }

  .report-card {
    height: auto;
    min-height: 360px;
  }

  .risk-grid {
    grid-template-columns: 1fr;
  }

  .operations-card {
    min-height: auto;
  }
}

@media (max-width: 639px) {
  .metric-grid {
    grid-template-columns: 1fr;
  }
}
</style>
