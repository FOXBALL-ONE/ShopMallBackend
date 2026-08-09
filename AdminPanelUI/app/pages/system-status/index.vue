<script setup lang="ts">
import {
  Activity,
  Clock3,
  Cpu,
  Database,
  HardDrive,
  MemoryStick,
  Network,
  RefreshCw,
  Server,
} from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { SystemMetricSample, SystemStatusSnapshot } from '~/types/system-status'

definePageMeta({ layout: 'default' })

const api = useSystemStatusApi()
const snapshot = ref<SystemStatusSnapshot | null>(null)
const samples = ref<SystemMetricSample[]>([])
const loading = ref(false)
const error = ref('')
const autoRefresh = ref(true)
const refreshInterval = ref(30_000)
let refreshTimer: number | undefined

const refreshOptions = [
  { label: '15 秒', value: 15_000 },
  { label: '30 秒', value: 30_000 },
  { label: '1 分钟', value: 60_000 },
]

const heapLimit = computed(() => {
  const jvm = snapshot.value?.jvm
  if (!jvm) return 0
  return jvm.heap_max_bytes > 0 ? jvm.heap_max_bytes : jvm.heap_committed_bytes
})

const heapUsagePercent = computed(() => {
  const used = snapshot.value?.jvm.heap_used_bytes ?? 0
  return heapLimit.value > 0 ? Math.min(100, (used / heapLimit.value) * 100) : 0
})

const diskUsagePercent = computed(() => {
  const total = snapshot.value?.system.disk_total_bytes
  const free = snapshot.value?.system.disk_free_bytes
  if (total === null || total === undefined || free === null || free === undefined || total <= 0) return null
  return Math.min(100, Math.max(0, ((total - free) / total) * 100))
})

const diskUsedBytes = computed(() => {
  const total = snapshot.value?.system.disk_total_bytes
  const free = snapshot.value?.system.disk_free_bytes
  if (total === null || total === undefined || free === null || free === undefined) return null
  return Math.max(0, total - free)
})

const diskProgressColor = computed(() => {
  const usage = diskUsagePercent.value
  if (usage === null) return '#a1a1aa'
  if (usage >= 90) return '#dc2626'
  if (usage >= 80) return '#d97706'
  return '#2563eb'
})

const databasePoolUsage = computed(() => {
  const database = snapshot.value?.database
  if (database?.active_connections === null || database?.active_connections === undefined || !database.max_connections) return 0
  return Math.min(100, (database.active_connections / database.max_connections) * 100)
})

const serverErrorRate = computed(() => {
  const http = snapshot.value?.http
  if (!http?.request_count) return 0
  return (http.server_error_count / http.request_count) * 100
})

const redisClientUsagePercent = computed(() => {
  const redis = snapshot.value?.redis
  if (redis?.connected_clients === null || redis?.connected_clients === undefined || !redis.max_clients) return 0
  return Math.min(100, (redis.connected_clients / redis.max_clients) * 100)
})

const redisMemoryUsagePercent = computed(() => {
  const redis = snapshot.value?.redis
  if (redis?.used_memory_bytes === null || redis?.used_memory_bytes === undefined || !redis.max_memory_bytes) return null
  return Math.min(100, (redis.used_memory_bytes / redis.max_memory_bytes) * 100)
})

const redisHitRate = computed(() => {
  const redis = snapshot.value?.redis
  const hits = redis?.keyspace_hits
  const misses = redis?.keyspace_misses
  if (hits === null || hits === undefined || misses === null || misses === undefined || hits + misses === 0) return null
  return hits / (hits + misses)
})

const healthyComponents = computed(() => snapshot.value?.health_components.filter(component => component.status === 'UP').length ?? 0)
const totalComponents = computed(() => snapshot.value?.health_components.length ?? 0)
const otherHealthComponents = computed(() => snapshot.value?.health_components.filter(component => (
  component.id !== 'db'
  && !component.id.startsWith('db.')
  && component.id !== 'redis'
  && !component.id.startsWith('redis.')
)) ?? [])

function getErrorMessage(reason: unknown): string {
  const failure = reason as { statusMessage?: string; message?: string }
  return failure?.statusMessage || failure?.message || '请求失败'
}

function statusLabel(status?: string): string {
  switch (status) {
    case 'UP': return '正常'
    case 'DOWN': return '故障'
    case 'OUT_OF_SERVICE': return '停止服务'
    case 'DEGRADED': return '降级'
    default: return '未知'
  }
}

function statusType(status?: string): 'success' | 'error' | 'warning' | 'default' {
  if (status === 'UP') return 'success'
  if (status === 'DOWN' || status === 'OUT_OF_SERVICE') return 'error'
  if (status === 'DEGRADED') return 'warning'
  return 'default'
}

function componentLabel(id: string): string {
  const labels: Record<string, string> = {
    db: '数据库',
    diskSpace: '磁盘空间',
    livenessState: '存活探针',
    mail: '邮件服务',
    ping: '应用探活',
    readinessState: '就绪探针',
    redis: 'Redis',
    ssl: 'SSL 证书',
  }
  const [root = id, ...children] = id.split('.')
  const rootLabel = labels[root] ?? root
  return children.length > 0 ? `${rootLabel} / ${children.join(' / ')}` : rootLabel
}

function componentDetailLabel(key: string): string {
  const labels: Record<string, string> = {
    database: '数据库类型',
    validationQuery: '连接验证',
    version: '版本',
    total: '总容量',
    free: '可用容量',
    threshold: '告警阈值',
    path: '检查路径',
    exists: '路径存在',
    location: '服务地址',
    validChains: '有效证书链',
    invalidChains: '无效证书链',
    error: '异常',
    cluster_size: '集群分片',
    known_nodes: '集群节点',
    slots_up: '正常槽位',
    slots_fail: '故障槽位',
  }
  return labels[key] ?? key
}

function healthDetail(componentId: string, key: string): string | undefined {
  return snapshot.value?.health_components.find(component => (
    (component.id === componentId || component.id.startsWith(`${componentId}.`))
    && component.details?.[key] !== undefined
  ))?.details?.[key]
}

function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '--' : new Intl.NumberFormat('zh-CN').format(value)
}

function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || bytes < 0) return '--'
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

function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '--'
  const days = Math.floor(seconds / 86_400)
  const hours = Math.floor((seconds % 86_400) / 3_600)
  const minutes = Math.floor((seconds % 3_600) / 60)
  if (days > 0) return `${days} 天 ${hours} 小时`
  if (hours > 0) return `${hours} 小时 ${minutes} 分钟`
  return `${minutes} 分钟`
}

function formatMemoryLimit(bytes: number | null | undefined): string {
  if (bytes === 0) return '未设置'
  return formatBytes(bytes)
}

function formatTtl(milliseconds: number | null | undefined): string {
  if (milliseconds === null || milliseconds === undefined) return '--'
  if (milliseconds < 60_000) return `${Math.round(milliseconds / 1000)} 秒`
  return formatDuration(milliseconds / 1000)
}

function formatRedisMode(mode: string | null | undefined): string {
  const labels: Record<string, string> = {
    standalone: '单机',
    cluster: '集群',
    sentinel: '哨兵',
  }
  return mode ? labels[mode] ?? mode : '--'
}

function formatRedisRole(role: string | null | undefined): string {
  const labels: Record<string, string> = {
    master: '主节点',
    slave: '从节点',
    replica: '从节点',
    cluster: '集群',
  }
  return role ? labels[role] ?? role : '--'
}

function formatComponentDetail(key: string, value: string): string {
  if (['total', 'free', 'threshold', 'used_memory_bytes', 'peak_memory_bytes', 'max_memory_bytes'].includes(key)) {
    const bytes = Number(value)
    return Number.isFinite(bytes) ? formatBytes(bytes) : value
  }
  if (key.endsWith('_seconds')) {
    const seconds = Number(value)
    return Number.isFinite(seconds) ? formatDuration(seconds) : value
  }
  if (key.endsWith('_ms')) {
    const milliseconds = Number(value)
    return Number.isFinite(milliseconds) ? `${formatNumber(milliseconds)} ms` : value
  }
  if (value === 'true') return '是'
  if (value === 'false') return '否'
  if (/^-?\d+$/.test(value)) return formatNumber(Number(value))
  return value
}

function formatPercent(value: number | null | undefined): string {
  return value === null || value === undefined ? '--' : `${(value * 100).toFixed(1)}%`
}

function formatMilliseconds(value: number | null | undefined): string {
  return value === null || value === undefined ? '--' : `${value.toFixed(value >= 100 ? 0 : 1)} ms`
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(date)
}

function appendSample(value: SystemStatusSnapshot) {
  if (samples.value.at(-1)?.captured_at === value.generated_at) return
  const limit = value.jvm.heap_max_bytes > 0 ? value.jvm.heap_max_bytes : value.jvm.heap_committed_bytes
  samples.value.push({
    captured_at: value.generated_at,
    process_cpu_percent: value.system.process_cpu_usage === null ? null : value.system.process_cpu_usage * 100,
    system_cpu_percent: value.system.system_cpu_usage === null ? null : value.system.system_cpu_usage * 100,
    heap_usage_percent: limit > 0 ? Math.min(100, (value.jvm.heap_used_bytes / limit) * 100) : null,
  })
  if (samples.value.length > 40) samples.value.splice(0, samples.value.length - 40)
}

async function loadStatus() {
  if (loading.value) return
  loading.value = true
  try {
    const value = await api.getStatus()
    snapshot.value = value
    appendSample(value)
    error.value = ''
  } catch (reason) {
    error.value = getErrorMessage(reason)
  } finally {
    loading.value = false
  }
}

function scheduleRefresh() {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
  refreshTimer = undefined
  if (autoRefresh.value) {
    refreshTimer = window.setInterval(() => void loadStatus(), refreshInterval.value)
  }
}

watch([autoRefresh, refreshInterval], scheduleRefresh)

onMounted(() => {
  void loadStatus()
  scheduleRefresh()
})

onBeforeUnmount(() => {
  if (refreshTimer !== undefined) window.clearInterval(refreshTimer)
})
</script>

<template>
  <div class="status-page">
    <div class="page-heading">
      <div>
        <div class="title-row">
          <h2>系统监控</h2>
          <NTag size="small" :type="statusType(snapshot?.status)" :bordered="false">
            {{ statusLabel(snapshot?.status) }}
          </NTag>
        </div>
        <NText depth="3">应用、运行时与基础设施状态</NText>
      </div>
      <div class="heading-actions">
        <div class="refresh-control">
          <span>自动刷新</span>
          <NSwitch v-model:value="autoRefresh" size="small" aria-label="自动刷新" />
        </div>
        <NSelect
          v-model:value="refreshInterval"
          class="interval-select"
          size="small"
          :options="refreshOptions"
          :disabled="!autoRefresh"
          aria-label="刷新间隔"
        />
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :loading="loading" aria-label="刷新系统状态" @click="loadStatus">
              <template #icon><RefreshCw :size="17" /></template>
            </NButton>
          </template>
          刷新系统状态
        </NTooltip>
      </div>
    </div>

    <NAlert v-if="error" type="error" :bordered="false" closable class="status-alert" @close="error = ''">
      系统状态加载失败：{{ error }}
    </NAlert>

    <NSpin :show="loading && !snapshot">
      <div class="summary-grid">
        <NCard size="small" class="summary-card health-summary">
          <div class="summary-heading">
            <span class="metric-icon icon-green"><Activity :size="19" /></span>
            <span>整体健康</span>
          </div>
          <strong class="summary-value">{{ statusLabel(snapshot?.status) }}</strong>
          <span class="summary-note">{{ healthyComponents }} / {{ totalComponents }} 个组件正常</span>
        </NCard>

        <NCard size="small" class="summary-card">
          <div class="summary-heading">
            <span class="metric-icon icon-blue"><Clock3 :size="19" /></span>
            <span>连续运行</span>
          </div>
          <strong class="summary-value">{{ formatDuration(snapshot?.application.uptime_seconds) }}</strong>
          <span class="summary-note">启动于 {{ formatDateTime(snapshot?.application.started_at) }}</span>
        </NCard>

        <NCard size="small" class="summary-card">
          <div class="summary-heading">
            <span class="metric-icon icon-cyan"><Cpu :size="19" /></span>
            <span>系统 CPU</span>
          </div>
          <strong class="summary-value">{{ formatPercent(snapshot?.system.system_cpu_usage) }}</strong>
          <span class="summary-note">进程 {{ formatPercent(snapshot?.system.process_cpu_usage) }}</span>
        </NCard>

        <NCard size="small" class="summary-card">
          <div class="summary-heading">
            <span class="metric-icon icon-amber"><MemoryStick :size="19" /></span>
            <span>JVM 堆内存</span>
          </div>
          <strong class="summary-value">{{ heapUsagePercent.toFixed(1) }}%</strong>
          <NProgress
            type="line"
            :percentage="heapUsagePercent"
            :color="heapUsagePercent >= 85 ? '#dc2626' : heapUsagePercent >= 70 ? '#d97706' : '#0f9f6e'"
            :height="6"
            :show-indicator="false"
            :border-radius="2"
          />
        </NCard>
      </div>

      <section class="status-section">
        <div class="section-heading">
          <div>
            <h3>资源趋势</h3>
            <NText depth="3">当前会话最近 {{ samples.length }} 次采样</NText>
          </div>
          <NText depth="3" class="sample-time">更新于 {{ formatDateTime(snapshot?.generated_at) }}</NText>
        </div>
        <div class="chart-frame">
          <SystemMetricsChart :samples="samples" />
        </div>
      </section>

      <section class="status-section">
        <div class="section-heading">
          <div>
            <h3>运行指标</h3>
            <NText depth="3">{{ snapshot?.source ?? 'Spring Boot Actuator' }}</NText>
          </div>
          <NText depth="3">采集耗时 {{ snapshot?.collection_duration_ms ?? '--' }} ms</NText>
        </div>

        <div class="details-grid">
          <NCard size="small" class="detail-card">
            <div class="card-heading">
              <span class="metric-icon icon-blue"><Server :size="18" /></span>
              <strong>应用与主机</strong>
            </div>
            <div class="detail-list">
              <div><span>应用</span><strong>{{ snapshot?.application.name ?? '--' }}</strong></div>
              <div><span>版本</span><strong>{{ snapshot?.application.version ?? '--' }}</strong></div>
              <div><span>处理器</span><strong>{{ formatNumber(snapshot?.system.available_processors) }}</strong></div>
              <div><span>1 分钟负载</span><strong>{{ snapshot?.system.system_load_average?.toFixed(2) ?? '--' }}</strong></div>
            </div>
          </NCard>

          <NCard size="small" class="detail-card">
            <div class="card-heading">
              <span class="metric-icon icon-green"><MemoryStick :size="18" /></span>
              <strong>JVM</strong>
            </div>
            <div class="detail-list">
              <div><span>堆内存</span><strong>{{ formatBytes(snapshot?.jvm.heap_used_bytes) }} / {{ formatBytes(heapLimit) }}</strong></div>
              <div><span>非堆内存</span><strong>{{ formatBytes(snapshot?.jvm.non_heap_used_bytes) }}</strong></div>
              <div><span>线程</span><strong>{{ formatNumber(snapshot?.jvm.live_threads) }} / 峰值 {{ formatNumber(snapshot?.jvm.peak_threads) }}</strong></div>
              <div><span>GC</span><strong>{{ formatNumber(snapshot?.jvm.gc_collection_count) }} 次 · {{ formatNumber(snapshot?.jvm.gc_collection_time_ms) }} ms</strong></div>
            </div>
          </NCard>

          <NCard size="small" class="detail-card">
            <div class="card-heading">
              <span class="metric-icon icon-amber"><HardDrive :size="18" /></span>
              <strong>磁盘</strong>
            </div>
            <div class="large-metric">{{ diskUsagePercent === null ? '--' : `${diskUsagePercent.toFixed(1)}%` }}</div>
            <NProgress
              type="line"
              :percentage="diskUsagePercent ?? 0"
              :color="diskProgressColor"
              :height="7"
              :show-indicator="false"
              :border-radius="2"
            />
            <div class="metric-caption">
              <span>已用 {{ formatBytes(diskUsedBytes) }}</span>
              <span>可用 {{ formatBytes(snapshot?.system.disk_free_bytes) }}</span>
            </div>
          </NCard>

          <NCard size="small" class="detail-card">
            <div class="card-heading">
              <span class="metric-icon icon-cyan"><Network :size="18" /></span>
              <strong>HTTP 请求</strong>
            </div>
            <div class="detail-list">
              <div><span>累计请求</span><strong>{{ formatNumber(snapshot?.http.request_count) }}</strong></div>
              <div><span>活跃请求</span><strong>{{ formatNumber(snapshot?.http.active_requests) }}</strong></div>
              <div><span>平均 / 最大耗时</span><strong>{{ formatMilliseconds(snapshot?.http.average_duration_ms) }} / {{ formatMilliseconds(snapshot?.http.max_duration_ms) }}</strong></div>
              <div><span>服务端错误</span><strong :class="{ 'danger-text': serverErrorRate > 1 }">{{ formatNumber(snapshot?.http.server_error_count) }} · {{ serverErrorRate.toFixed(2) }}%</strong></div>
            </div>
          </NCard>
        </div>
      </section>

      <section class="status-section last-section">
        <div class="section-heading">
          <div>
            <h3>依赖健康</h3>
            <NText depth="3">Actuator Health Contributors</NText>
          </div>
        </div>

        <div class="dependency-grid">
          <NCard size="small" class="dependency-card">
            <div class="dependency-title">
              <div class="card-heading">
                <span class="metric-icon icon-blue"><Database :size="18" /></span>
                <strong>数据库连接池</strong>
              </div>
              <NTag size="small" :type="statusType(snapshot?.database.status)" :bordered="false">
                {{ statusLabel(snapshot?.database.status) }}
              </NTag>
            </div>
            <div class="large-metric">{{ formatNumber(snapshot?.database.active_connections) }}</div>
            <div class="metric-label">活跃连接</div>
            <NProgress
              type="line"
              :percentage="databasePoolUsage"
              color="#2563eb"
              :height="7"
              :show-indicator="false"
              :border-radius="2"
            />
            <div class="metric-caption">
              <span>空闲 {{ formatNumber(snapshot?.database.idle_connections) }}</span>
              <span>上限 {{ formatNumber(snapshot?.database.max_connections) }}</span>
            </div>
            <div class="detail-list dependency-detail-list">
              <div><span>最小连接</span><strong>{{ formatNumber(snapshot?.database.min_connections) }}</strong></div>
              <div><span>数据库类型</span><strong>{{ healthDetail('db', 'database') ?? '--' }}</strong></div>
              <div><span>连接验证</span><strong>{{ healthDetail('db', 'validationQuery') ?? '--' }}</strong></div>
            </div>
          </NCard>

          <NCard size="small" class="dependency-card redis-card">
            <div class="dependency-title">
              <div class="card-heading">
                <span class="metric-icon icon-green"><Database :size="18" /></span>
                <strong>Redis</strong>
              </div>
              <NTag size="small" :type="statusType(snapshot?.redis.status)" :bordered="false">
                {{ statusLabel(snapshot?.redis.status) }}
              </NTag>
            </div>
            <div class="large-metric">{{ formatNumber(snapshot?.redis.key_count) }}</div>
            <div class="metric-label">
              DB {{ snapshot?.redis.database ?? '--' }} Key 数，其中 {{ formatNumber(snapshot?.redis.expiring_key_count) }} 个设有过期时间
            </div>
            <NProgress
              type="line"
              :percentage="redisClientUsagePercent"
              color="#059669"
              :height="7"
              :show-indicator="false"
              :border-radius="2"
            />
            <div class="metric-caption">
              <span>客户端 {{ formatNumber(snapshot?.redis.connected_clients) }}</span>
              <span>上限 {{ formatNumber(snapshot?.redis.max_clients) }}</span>
            </div>
            <div class="detail-list dependency-detail-list">
              <div>
                <span>内存</span>
                <strong>
                  {{ formatBytes(snapshot?.redis.used_memory_bytes) }} / {{ formatMemoryLimit(snapshot?.redis.max_memory_bytes) }}
                  <template v-if="redisMemoryUsagePercent !== null"> · {{ redisMemoryUsagePercent.toFixed(1) }}%</template>
                </strong>
              </div>
              <div><span>峰值内存</span><strong>{{ formatBytes(snapshot?.redis.peak_memory_bytes) }}</strong></div>
              <div><span>平均 TTL</span><strong>{{ formatTtl(snapshot?.redis.average_ttl_ms) }}</strong></div>
              <div><span>连接状态</span><strong>{{ formatNumber(snapshot?.redis.blocked_clients) }} 阻塞 · {{ formatRedisRole(snapshot?.redis.role) }}</strong></div>
              <div><span>命中 / 未命中</span><strong>{{ formatNumber(snapshot?.redis.keyspace_hits) }} / {{ formatNumber(snapshot?.redis.keyspace_misses) }} · {{ redisHitRate === null ? '--' : formatPercent(redisHitRate) }}</strong></div>
              <div><span>实时 / 累计命令</span><strong>{{ formatNumber(snapshot?.redis.operations_per_second) }} ops/s · {{ formatNumber(snapshot?.redis.total_commands_processed) }}</strong></div>
              <div><span>淘汰 Key</span><strong>{{ formatNumber(snapshot?.redis.evicted_keys) }}</strong></div>
              <div><span>实例</span><strong>{{ formatRedisMode(snapshot?.redis.mode) }} · Redis {{ snapshot?.redis.version ?? '--' }}</strong></div>
              <div><span>连续运行</span><strong>{{ formatDuration(snapshot?.redis.uptime_seconds) }}</strong></div>
            </div>
          </NCard>

          <div class="health-components">
            <div class="health-list-heading">
              <strong>其他健康贡献者</strong>
              <span>{{ otherHealthComponents.length }} 项</span>
            </div>
            <div
              v-for="component in otherHealthComponents"
              :key="component.id"
              class="health-row"
            >
              <div class="health-row-heading">
                <div>
                  <strong>{{ componentLabel(component.id) }}</strong>
                  <span>{{ component.id }}</span>
                </div>
                <NTag size="small" :type="statusType(component.status)" :bordered="false">
                  {{ statusLabel(component.status) }}
                </NTag>
              </div>
              <div v-if="Object.keys(component.details ?? {}).length" class="component-details">
                <div v-for="(value, key) in component.details ?? {}" :key="key" class="component-detail">
                  <span>{{ componentDetailLabel(key) }}</span>
                  <strong>{{ formatComponentDetail(key, value) }}</strong>
                </div>
              </div>
            </div>
            <NEmpty v-if="!otherHealthComponents.length" size="small" description="暂无其他健康组件" />
          </div>
        </div>
      </section>
    </NSpin>
  </div>
</template>

<style scoped>
.status-page {
  width: 100%;
  max-width: 1440px;
  color: #27272a;
}

.page-heading,
.title-row,
.heading-actions,
.refresh-control,
.summary-heading,
.section-heading,
.card-heading,
.dependency-title,
.metric-caption,
.health-row-heading,
.health-list-heading {
  display: flex;
  align-items: center;
}

.page-heading,
.section-heading,
.dependency-title,
.metric-caption,
.health-row-heading,
.health-list-heading {
  justify-content: space-between;
}

.page-heading {
  min-height: 48px;
  margin-bottom: 18px;
  gap: 18px;
}

.title-row,
.heading-actions,
.refresh-control,
.summary-heading,
.card-heading {
  gap: 8px;
}

.page-heading h2,
.section-heading h3 {
  margin: 0;
  letter-spacing: 0;
}

.page-heading h2 {
  font-size: 22px;
}

.section-heading h3 {
  margin-bottom: 2px;
  font-size: 16px;
}

.refresh-control {
  color: #52525b;
  font-size: 13px;
  white-space: nowrap;
}

.interval-select {
  width: 104px;
}

.status-alert {
  margin-bottom: 16px;
}

.summary-grid,
.details-grid,
.dependency-grid {
  display: grid;
  gap: 14px;
}

.summary-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.summary-card,
.detail-card,
.dependency-card {
  min-width: 0;
  border-radius: 6px;
}

.summary-card {
  min-height: 142px;
}

.summary-heading {
  color: #52525b;
  font-size: 13px;
  font-weight: 600;
}

.metric-icon {
  width: 31px;
  height: 31px;
  flex: 0 0 31px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  color: #3f3f46;
  background: #f4f4f5;
}

.icon-blue {
  color: #1d4ed8;
  background: #eff6ff;
}

.icon-green {
  color: #047857;
  background: #ecfdf5;
}

.icon-cyan {
  color: #0e7490;
  background: #ecfeff;
}

.icon-amber {
  color: #b45309;
  background: #fffbeb;
}

.summary-value {
  display: block;
  margin-top: 17px;
  font-size: 24px;
  line-height: 1.2;
  overflow-wrap: anywhere;
}

.summary-note {
  display: block;
  min-height: 20px;
  margin-top: 8px;
  color: #71717a;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.status-section {
  margin-top: 28px;
}

.last-section {
  padding-bottom: 8px;
}

.section-heading {
  min-height: 44px;
  margin-bottom: 10px;
  gap: 14px;
}

.sample-time {
  white-space: nowrap;
}

.chart-frame {
  min-height: 280px;
  padding: 16px 14px 4px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  background: #fff;
}

.details-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.detail-card :deep(.n-card__content),
.dependency-card :deep(.n-card__content) {
  min-height: 205px;
}

.dependency-card :deep(.n-card__content) {
  min-height: 360px;
}

.card-heading strong {
  font-size: 14px;
}

.detail-list {
  margin-top: 18px;
}

.detail-list > div {
  min-height: 34px;
  display: grid;
  grid-template-columns: minmax(90px, 0.8fr) minmax(0, 1.4fr);
  align-items: center;
  gap: 14px;
  border-bottom: 1px solid #f1f1f3;
  font-size: 13px;
}

.detail-list > div:last-child {
  border-bottom: 0;
}

.detail-list span,
.metric-caption,
.metric-label {
  color: #71717a;
}

.detail-list strong {
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}

.dependency-detail-list {
  margin-top: 18px;
  border-top: 1px solid #e4e4e7;
}

.dependency-detail-list > div {
  min-height: 32px;
}

.large-metric {
  margin: 22px 0 3px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.15;
}

.metric-label {
  margin-bottom: 14px;
  font-size: 12px;
}

.metric-caption {
  margin-top: 10px;
  gap: 12px;
  font-size: 12px;
}

.danger-text {
  color: #dc2626;
}

.dependency-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: stretch;
}

.health-components {
  grid-column: 1 / -1;
  min-height: 205px;
  padding: 0 16px 8px;
  border: 1px solid #e4e4e7;
  border-radius: 6px;
  background: #fff;
}

.health-list-heading {
  min-height: 50px;
  border-bottom: 1px solid #e4e4e7;
  font-size: 13px;
}

.health-list-heading > span {
  color: #71717a;
}

.health-row {
  padding: 13px 0;
  border-bottom: 1px solid #f1f1f3;
}

.health-row:last-child {
  border-bottom: 0;
}

.health-row-heading {
  gap: 16px;
}

.health-row-heading > div {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.health-row strong {
  font-size: 13px;
  overflow-wrap: anywhere;
}

.health-row span {
  color: #a1a1aa;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.component-details {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(210px, 1fr));
  gap: 0 20px;
  margin-top: 10px;
  padding-top: 7px;
  border-top: 1px dashed #e4e4e7;
}

.component-detail {
  min-width: 0;
  min-height: 30px;
  display: grid;
  grid-template-columns: minmax(76px, auto) minmax(0, 1fr);
  align-items: center;
  gap: 10px;
}

.component-detail strong {
  min-width: 0;
  text-align: right;
  overflow-wrap: anywhere;
}

.health-components :deep(.n-empty) {
  padding: 50px 0;
}

@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dependency-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .page-heading,
  .section-heading {
    align-items: flex-start;
  }

  .page-heading {
    flex-direction: column;
  }

  .heading-actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .heading-actions > :last-child {
    margin-left: auto;
  }

  .summary-grid,
  .details-grid,
  .dependency-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .health-components {
    grid-column: auto;
  }

  .sample-time {
    white-space: normal;
    text-align: right;
  }

  .chart-frame {
    padding-right: 4px;
    padding-left: 4px;
  }
}

@media (max-width: 420px) {
  .summary-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .section-heading {
    flex-direction: column;
  }

  .sample-time {
    text-align: left;
  }

  .detail-list > div {
    grid-template-columns: minmax(82px, 0.8fr) minmax(0, 1.2fr);
    gap: 8px;
  }
}
</style>
