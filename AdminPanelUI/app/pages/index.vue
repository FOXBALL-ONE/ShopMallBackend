<script setup lang="ts">
import {
  Activity,
  BarChart3,
  CircleDollarSign,
  Database,
  Gauge,
  LifeBuoy,
  MemoryStick,
  Package,
  RefreshCw,
  ShoppingCart,
  Truck,
  Users,
} from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  DashboardOperationsReport,
  DashboardRevenueAmount,
  DashboardSummary,
  DashboardSystemStatus,
} from '~/types/dashboard'

definePageMeta({ layout: 'default' })

const api = useDashboardApi()
const summary = ref<DashboardSummary | null>(null)
const operations = ref<DashboardOperationsReport | null>(null)
const systemStatus = ref<DashboardSystemStatus | null>(null)
const selectedDays = ref(14)
const loading = ref(false)
const operationsLoading = ref(false)
const systemLoading = ref(false)
const error = ref('')
let statusRefreshTimer: number | undefined

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

const heapLimit = computed(() => {
  const jvm = systemStatus.value?.jvm
  if (!jvm) return 0
  return jvm.heap_max_bytes > 0 ? jvm.heap_max_bytes : jvm.heap_committed_bytes
})

const heapUsagePercent = computed(() => {
  const used = systemStatus.value?.jvm.heap_used_bytes ?? 0
  return heapLimit.value > 0 ? Math.min(100, Math.round((used / heapLimit.value) * 100)) : 0
})

const databasePoolUsage = computed(() => {
  const database = systemStatus.value?.database
  if (!database?.active_connections || !database.max_connections) return 0
  return Math.min(100, Math.round((database.active_connections / database.max_connections) * 100))
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

function formatBytes(bytes: number | null): string {
  if (bytes === null || bytes < 0) return '--'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value >= 100 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`
}

function formatDuration(seconds: number): string {
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`
  return `${minutes} 分钟`
}

function formatCpu(value: number | null): string {
  return value === null ? '--' : `${(value * 100).toFixed(1)}%`
}

function formatGeneratedAt(value: string | undefined): string {
  if (!value) return '--'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
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

async function loadSystemStatus() {
  if (systemLoading.value) return
  systemLoading.value = true
  try {
    systemStatus.value = await api.systemStatus()
  } catch (reason) {
    error.value = `系统状态：${getErrorMessage(reason)}`
  } finally {
    systemLoading.value = false
  }
}

async function loadDashboard() {
  loading.value = true
  error.value = ''
  const [summaryResult, operationsResult, statusResult] = await Promise.allSettled([
    api.summary(),
    api.operations(selectedDays.value),
    api.systemStatus(),
  ] as const)
  const failures: string[] = []
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  else failures.push(`运营待办：${getErrorMessage(summaryResult.reason)}`)
  if (operationsResult.status === 'fulfilled') operations.value = operationsResult.value
  else failures.push(`运营报表：${getErrorMessage(operationsResult.reason)}`)
  if (statusResult.status === 'fulfilled') systemStatus.value = statusResult.value
  else failures.push(`系统状态：${getErrorMessage(statusResult.reason)}`)
  error.value = failures.join('；')
  loading.value = false
}

watch(selectedDays, () => void loadOperations())

onMounted(() => {
  void loadDashboard()
  statusRefreshTimer = window.setInterval(() => void loadSystemStatus(), 30_000)
})

onBeforeUnmount(() => {
  if (statusRefreshTimer !== undefined) window.clearInterval(statusRefreshTimer)
})
</script>

<template>
  <div class="dashboard-page">
    <div class="page-heading">
      <div>
        <h2>仪表盘</h2>
        <NText depth="3">经营、履约与系统运行总览</NText>
      </div>
      <div class="heading-actions">
        <NText depth="3" class="updated-at">更新于 {{ formatGeneratedAt(systemStatus?.generated_at) }}</NText>
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

    <NSpin :show="loading && !summary && !operations && !systemStatus">
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

      <section class="dashboard-section system-section">
        <div class="section-heading">
          <div>
            <div class="system-title-row">
              <h3>系统运行状态</h3>
              <NTag
                size="small"
                :type="systemStatus?.status === 'UP' ? 'success' : 'warning'"
                :bordered="false"
              >
                {{ systemStatus?.status === 'UP' ? '正常' : '部分异常' }}
              </NTag>
            </div>
            <NText depth="3">{{ systemStatus?.application.name ?? 'ShopMall' }} · {{ systemStatus?.application.version ?? '--' }}</NText>
          </div>
          <NSpin v-if="systemLoading" :size="18" />
        </div>

        <div class="system-grid">
          <div>
            <NCard size="small" class="system-card">
              <div class="system-card-heading">
                <span class="system-icon"><Gauge :size="18" /></span>
                <span>应用资源</span>
              </div>
              <div class="system-primary-value">{{ formatDuration(systemStatus?.application.uptime_seconds ?? 0) }}</div>
              <div class="system-primary-label">连续运行</div>
              <div class="system-details">
                <div><span>进程 CPU</span><strong>{{ formatCpu(systemStatus?.application.process_cpu_usage ?? null) }}</strong></div>
                <div><span>系统 CPU</span><strong>{{ formatCpu(systemStatus?.application.system_cpu_usage ?? null) }}</strong></div>
                <div><span>处理器</span><strong>{{ systemStatus?.application.available_processors ?? '--' }}</strong></div>
                <div><span>平均负载</span><strong>{{ systemStatus?.application.system_load_average?.toFixed(2) ?? '--' }}</strong></div>
              </div>
            </NCard>
          </div>

          <div>
            <NCard size="small" class="system-card">
              <div class="system-card-heading">
                <span class="system-icon system-icon-green"><MemoryStick :size="18" /></span>
                <span>JVM 内存</span>
              </div>
              <div class="system-primary-value">{{ heapUsagePercent }}%</div>
              <div class="system-primary-label">堆内存占用</div>
              <NProgress
                type="line"
                :percentage="heapUsagePercent"
                :color="heapUsagePercent >= 85 ? '#dc2626' : heapUsagePercent >= 70 ? '#d97706' : '#0f9f6e'"
                :height="7"
                :show-indicator="false"
                :border-radius="2"
              />
              <div class="system-details memory-details">
                <div><span>已用 / 上限</span><strong>{{ formatBytes(systemStatus?.jvm.heap_used_bytes ?? 0) }} / {{ formatBytes(heapLimit) }}</strong></div>
                <div><span>已提交</span><strong>{{ formatBytes(systemStatus?.jvm.heap_committed_bytes ?? 0) }}</strong></div>
                <div><span>非堆内存</span><strong>{{ formatBytes(systemStatus?.jvm.non_heap_used_bytes ?? 0) }}</strong></div>
              </div>
            </NCard>
          </div>

          <div>
            <NCard size="small" class="system-card">
              <div class="system-card-heading">
                <span class="system-icon system-icon-amber"><Activity :size="18" /></span>
                <span>GC 与线程</span>
              </div>
              <div class="system-primary-value">{{ formatNumber(systemStatus?.jvm.gc_collection_count ?? 0) }}</div>
              <div class="system-primary-label">GC 累计次数</div>
              <div class="system-details">
                <div><span>GC 耗时</span><strong>{{ formatNumber(systemStatus?.jvm.gc_collection_time_ms ?? 0) }} ms</strong></div>
                <div><span>活跃线程</span><strong>{{ formatNumber(systemStatus?.jvm.live_threads ?? 0) }}</strong></div>
                <div><span>峰值线程</span><strong>{{ formatNumber(systemStatus?.jvm.peak_threads ?? 0) }}</strong></div>
                <div><span>守护线程</span><strong>{{ formatNumber(systemStatus?.jvm.daemon_threads ?? 0) }}</strong></div>
              </div>
            </NCard>
          </div>

          <div>
            <NCard size="small" class="system-card infrastructure-card">
              <div class="system-card-heading">
                <span class="system-icon system-icon-cyan"><Database :size="18" /></span>
                <span>基础设施</span>
              </div>
              <div class="dependency-row">
                <div class="dependency-heading">
                  <strong>PostgreSQL</strong>
                  <NTag size="tiny" :type="systemStatus?.database.available ? 'success' : 'error'" :bordered="false">
                    {{ systemStatus?.database.available ? '正常' : '异常' }}
                  </NTag>
                </div>
                <span>{{ systemStatus?.database.latency_ms ?? '--' }} ms · {{ systemStatus?.database.active_connections ?? '--' }}/{{ systemStatus?.database.max_connections ?? '--' }} 连接</span>
                <NProgress
                  type="line"
                  :percentage="databasePoolUsage"
                  color="#2563eb"
                  :height="5"
                  :show-indicator="false"
                  :border-radius="2"
                />
              </div>
              <div class="dependency-row redis-row">
                <div class="dependency-heading">
                  <strong>Redis {{ systemStatus?.redis.version ?? '' }}</strong>
                  <NTag size="tiny" :type="systemStatus?.redis.available ? 'success' : 'error'" :bordered="false">
                    {{ systemStatus?.redis.available ? '正常' : '异常' }}
                  </NTag>
                </div>
                <div class="redis-metrics">
                  <div><span>Key</span><strong>{{ systemStatus?.redis.key_count ?? '--' }}</strong></div>
                  <div><span>内存</span><strong>{{ formatBytes(systemStatus?.redis.used_memory_bytes ?? null) }}</strong></div>
                  <div><span>客户端</span><strong>{{ systemStatus?.redis.connected_clients ?? '--' }}</strong></div>
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
.system-title-row,
.card-title,
.metric-topline,
.revenue-row-main,
.revenue-comparison,
.revenue-footnote,
.distribution-label,
.system-card-heading,
.dependency-heading {
  display: flex;
  align-items: center;
}

.page-heading,
.section-heading,
.metric-topline,
.revenue-row-main,
.revenue-comparison,
.revenue-footnote,
.distribution-label,
.dependency-heading {
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

.updated-at {
  font-size: 12px;
  white-space: nowrap;
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
.operations-grid,
.system-grid {
  display: grid;
  gap: 12px;
}

.metric-grid,
.system-grid {
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
.system-grid > div,
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

.metric-icon,
.system-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 5px;
  color: #2563eb;
  background: #eff6ff;
}

.metric-icon-green,
.system-icon-green {
  color: #087f5b;
  background: #ecfdf5;
}

.metric-icon-amber,
.system-icon-amber {
  color: #b45309;
  background: #fffbeb;
}

.metric-icon-cyan,
.system-icon-cyan {
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

.metric-label,
.system-primary-label {
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

.system-section {
  padding-bottom: 8px;
}

.system-title-row {
  gap: 8px;
}

.system-card {
  min-height: 310px;
  border-radius: 6px;
}

.system-card-heading {
  gap: 9px;
  margin-bottom: 18px;
  color: #3f3f46;
  font-size: 13px;
  font-weight: 650;
}

.system-primary-value {
  color: #18181b;
  font-size: 25px;
  font-weight: 650;
  line-height: 1.25;
  overflow-wrap: anywhere;
}

.system-primary-label {
  margin-top: 3px;
  margin-bottom: 13px;
}

.system-details {
  margin-top: 12px;
  border-top: 1px solid #f1f1f3;
}

.system-details > div {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 35px;
  gap: 8px;
  color: #71717a;
  font-size: 12px;
}

.system-details strong {
  color: #3f3f46;
  font-weight: 600;
  text-align: right;
  overflow-wrap: anywhere;
}

.memory-details > div:first-child {
  align-items: flex-start;
}

.infrastructure-card .system-card-heading {
  margin-bottom: 10px;
}

.dependency-row {
  padding: 12px 0;
  border-bottom: 1px solid #f1f1f3;
}

.dependency-row:last-child {
  border-bottom: 0;
}

.dependency-heading {
  gap: 8px;
  margin-bottom: 7px;
}

.dependency-row > span {
  display: block;
  margin-bottom: 8px;
  color: #71717a;
  font-size: 12px;
}

.redis-row {
  padding-bottom: 0;
}

.redis-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.redis-metrics div {
  min-width: 0;
}

.redis-metrics span,
.redis-metrics strong {
  display: block;
}

.redis-metrics span {
  color: #71717a;
  font-size: 11px;
}

.redis-metrics strong {
  margin-top: 3px;
  color: #3f3f46;
  font-size: 12px;
  overflow-wrap: anywhere;
}

@media (max-width: 1279px) {
  .report-grid,
  .operations-grid {
    grid-template-columns: 1fr;
  }

  .system-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
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

  .updated-at {
    display: none;
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

  .system-card,
  .operations-card {
    min-height: auto;
  }
}

@media (max-width: 639px) {
  .metric-grid,
  .system-grid {
    grid-template-columns: 1fr;
  }
}
</style>
