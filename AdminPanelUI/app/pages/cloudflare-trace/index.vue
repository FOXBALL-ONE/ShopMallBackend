<script setup lang="ts">
import { Cloud, ExternalLink, Radio, RefreshCw, Trash2 } from '@lucide/vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import type { CloudflareTraceResult } from '~/types/cloudflare-trace'

definePageMeta({ layout: 'default' })

const message = useMessage()
const runtimeConfig = useRuntimeConfig()
const traceTarget = String(runtimeConfig.public.cloudflareTraceUrl || 'https://mall.foxball.dev/cdn-cgi/trace')
const samples = ref<CloudflareTraceResult[]>([])
const loading = ref(false)
const lastError = ref('')
const autoRefresh = ref(true)
const refreshIntervalSeconds = ref(30)
const nodeChanged = ref(false)
const traceStorageKey = 'shopmall_cloudflare_trace_samples'
let refreshTimer: ReturnType<typeof setInterval> | undefined

const refreshIntervalOptions = [
  { label: '15 秒', value: 15 },
  { label: '30 秒', value: 30 },
  { label: '1 分钟', value: 60 },
  { label: '5 分钟', value: 300 },
]

const latest = computed(() => samples.value[0] ?? null)
const uniqueColos = computed(() => new Set(samples.value.map(sample => sample.colo)).size)
const nodeCounts = computed(() => {
  const counts = new Map<string, { colo: string; location: string; count: number; latestAt: string }>()
  for (const sample of samples.value) {
    const current = counts.get(sample.colo)
    if (current) current.count += 1
    else counts.set(sample.colo, { colo: sample.colo, location: sample.location, count: 1, latestAt: sample.checked_at })
  }
  return [...counts.values()].sort((left, right) => right.count - left.count)
})

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string; data?: { statusMessage?: string } }
    return value.data?.statusMessage || value.statusMessage || value.message || '未知错误'
  }
  return String(error || '未知错误')
}

function formatDateTime(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
}

function locationLabel(code: string): string {
  if (!code) return '-'
  try {
    const name = new Intl.DisplayNames(['zh-CN'], { type: 'region' }).of(code)
    return name && name !== code ? `${name} (${code})` : code
  } catch {
    return code
  }
}

function persistSamples() {
  if (!import.meta.client) return
  sessionStorage.setItem(traceStorageKey, JSON.stringify(samples.value))
}

async function loadTrace(background = false) {
  if (loading.value) return
  loading.value = true
  try {
    const targetUrl = new URL(traceTarget)
    if (targetUrl.protocol !== 'https:' || targetUrl.pathname !== '/cdn-cgi/trace') {
      throw new Error('Cloudflare trace 目标必须是 HTTPS /cdn-cgi/trace 地址')
    }

    const startedAt = performance.now()
    const response = await fetch(targetUrl.toString(), {
      cache: 'no-store',
      headers: { accept: 'text/plain' },
    })
    if (!response.ok) throw new Error(`Cloudflare trace 返回 HTTP ${response.status}`)
    const raw = await response.text()
    if (raw.length > 16_384) throw new Error('Cloudflare trace 响应超出预期大小')

    const trace: Record<string, string> = {}
    for (const line of raw.split(/\r?\n/)) {
      const separatorIndex = line.indexOf('=')
      if (separatorIndex <= 0) continue
      trace[line.slice(0, separatorIndex)] = line.slice(separatorIndex + 1)
    }
    if (!trace.colo || !trace.loc || !trace.h || !trace.ip) {
      throw new Error('Cloudflare trace 响应缺少必要字段')
    }

    const timestampSeconds = Number(trace.ts)
    const result: CloudflareTraceResult = {
      target: targetUrl.toString(),
      checked_at: new Date().toISOString(),
      duration_ms: Math.max(0, Math.round(performance.now() - startedAt)),
      cloudflare_timestamp: Number.isFinite(timestampSeconds) ? new Date(timestampSeconds * 1000).toISOString() : null,
      colo: trace.colo,
      location: trace.loc,
      host: trace.h,
      ip: trace.ip,
      visit_scheme: trace.visit_scheme ?? '',
      user_agent: trace.uag ?? '',
      http_protocol: trace.http ?? '',
      tls_version: trace.tls ?? '',
      sni: trace.sni ?? '',
      warp: trace.warp ?? '',
      gateway: trace.gateway ?? '',
      rbi: trace.rbi ?? '',
      key_exchange: trace.kex ?? '',
      fl: trace.fl ?? '',
      sliver: trace.sliver ?? '',
      raw: raw.trim(),
    }
    const previous = samples.value[0]
    nodeChanged.value = Boolean(previous && previous.colo !== result.colo)
    samples.value = [result, ...samples.value].slice(0, 60)
    persistSamples()
    lastError.value = ''
  } catch (error) {
    lastError.value = errorMessage(error)
    if (!background) message.error(`Cloudflare 节点探测失败：${lastError.value}`)
  } finally {
    loading.value = false
  }
}

function clearHistory() {
  samples.value = latest.value ? [latest.value] : []
  nodeChanged.value = false
  persistSamples()
}

function stopAutoRefresh() {
  if (refreshTimer) clearInterval(refreshTimer)
  refreshTimer = undefined
}

function startAutoRefresh() {
  stopAutoRefresh()
  if (!autoRefresh.value) return
  refreshTimer = setInterval(() => { void loadTrace(true) }, refreshIntervalSeconds.value * 1000)
}

watch([autoRefresh, refreshIntervalSeconds], startAutoRefresh)

onMounted(async () => {
  try {
    const storedSamples = JSON.parse(sessionStorage.getItem(traceStorageKey) || '[]') as unknown
    if (Array.isArray(storedSamples)) {
      samples.value = storedSamples
        .filter((sample): sample is CloudflareTraceResult => Boolean(
          sample && typeof sample === 'object'
          && typeof (sample as CloudflareTraceResult).checked_at === 'string'
          && typeof (sample as CloudflareTraceResult).colo === 'string',
        ))
        .slice(0, 60)
    }
  } catch {
    sessionStorage.removeItem(traceStorageKey)
  }
  await loadTrace()
  startAutoRefresh()
})

onBeforeUnmount(stopAutoRefresh)
</script>

<template>
  <div class="trace-page">
    <header class="page-heading">
      <div>
        <div class="eyebrow"><Cloud :size="17" /> Cloudflare</div>
        <h2>节点追踪</h2>
        <NText depth="3">{{ latest?.target ?? traceTarget }}</NText>
      </div>
      <NSpace align="center" :wrap="true">
        <NSwitch v-model:value="autoRefresh">
          <template #checked>自动刷新</template>
          <template #unchecked>手动刷新</template>
        </NSwitch>
        <NSelect v-model:value="refreshIntervalSeconds" :options="refreshIntervalOptions" :disabled="!autoRefresh" style="width: 112px" />
        <NButton :loading="loading" @click="loadTrace(false)">
          <template #icon><RefreshCw :size="16" /></template>
          立即探测
        </NButton>
        <NButton tag="a" :href="latest?.target ?? traceTarget" target="_blank" rel="noopener noreferrer">
          <template #icon><ExternalLink :size="16" /></template>
          源数据
        </NButton>
      </NSpace>
    </header>

    <NAlert v-if="lastError" type="error" :bordered="false" closable @close="lastError = ''">
      {{ lastError }}
    </NAlert>

    <NGrid cols="1 s:2 l:4" responsive="screen" :x-gap="14" :y-gap="14">
      <NGridItem>
        <NCard size="small" class="metric-card">
          <NText depth="3">当前节点</NText>
          <div class="metric-value metric-value--mono">{{ latest?.colo ?? '--' }}</div>
          <NTag v-if="nodeChanged" type="warning" size="small" :bordered="false">节点已变更</NTag>
          <NText v-else depth="3">{{ latest ? locationLabel(latest.location) : '等待探测' }}</NText>
        </NCard>
      </NGridItem>
      <NGridItem>
        <NCard size="small" class="metric-card">
          <NText depth="3">响应耗时</NText>
          <div class="metric-value">{{ latest ? `${latest.duration_ms} ms` : '--' }}</div>
          <NText depth="3">浏览器直连</NText>
        </NCard>
      </NGridItem>
      <NGridItem>
        <NCard size="small" class="metric-card">
          <NText depth="3">连接协议</NText>
          <div class="metric-value metric-value--small">{{ latest?.tls_version || '--' }}</div>
          <NText depth="3">{{ latest?.http_protocol || '等待探测' }}</NText>
        </NCard>
      </NGridItem>
      <NGridItem>
        <NCard size="small" class="metric-card">
          <NText depth="3">会话样本</NText>
          <div class="metric-value">{{ samples.length }}</div>
          <NText depth="3">{{ uniqueColos }} 个节点</NText>
        </NCard>
      </NGridItem>
    </NGrid>

    <NCard title="当前连接" size="small">
      <template #header-extra>
        <NSpace align="center">
          <Radio :size="15" :class="{ 'status-icon--active': !lastError && !!latest }" />
          <NText depth="3">{{ latest ? formatDateTime(latest.checked_at) : '尚未探测' }}</NText>
        </NSpace>
      </template>
      <NDescriptions v-if="latest" :column="3" label-placement="top" bordered responsive="screen">
        <NDescriptionsItem label="Cloudflare 节点"><span class="mono-value">{{ latest.colo }}</span></NDescriptionsItem>
        <NDescriptionsItem label="访问位置">{{ locationLabel(latest.location) }}</NDescriptionsItem>
        <NDescriptionsItem label="目标主机"><span class="mono-value">{{ latest.host }}</span></NDescriptionsItem>
        <NDescriptionsItem label="探测端 IP"><span class="mono-value break-value">{{ latest.ip }}</span></NDescriptionsItem>
        <NDescriptionsItem label="HTTP / TLS">{{ latest.http_protocol || '-' }} / {{ latest.tls_version || '-' }}</NDescriptionsItem>
        <NDescriptionsItem label="密钥交换">{{ latest.key_exchange || '-' }}</NDescriptionsItem>
        <NDescriptionsItem label="SNI">{{ latest.sni || '-' }}</NDescriptionsItem>
        <NDescriptionsItem label="WARP / Gateway">{{ latest.warp || '-' }} / {{ latest.gateway || '-' }}</NDescriptionsItem>
        <NDescriptionsItem label="Cloudflare 时间">{{ formatDateTime(latest.cloudflare_timestamp) }}</NDescriptionsItem>
      </NDescriptions>
      <NEmpty v-else description="暂无探测数据" />
    </NCard>

    <div class="detail-grid">
      <NCard title="节点分布" size="small">
        <div v-if="nodeCounts.length" class="node-list">
          <div v-for="node in nodeCounts" :key="node.colo" class="node-row">
            <div>
              <strong class="mono-value">{{ node.colo }}</strong>
              <span>{{ locationLabel(node.location) }} · 最近 {{ formatDateTime(node.latestAt) }}</span>
            </div>
            <NTag :bordered="false" type="info">{{ node.count }} 次</NTag>
          </div>
        </div>
        <NEmpty v-else description="暂无节点样本" />
      </NCard>

      <NCard title="原始 Trace" size="small">
        <pre v-if="latest" class="raw-trace">{{ latest.raw }}</pre>
        <NEmpty v-else description="暂无原始数据" />
      </NCard>
    </div>

    <NCard title="探测历史" size="small">
      <template #header-extra>
        <NButton size="small" quaternary :disabled="samples.length <= 1" @click="clearHistory">
          <template #icon><Trash2 :size="15" /></template>
          清空历史
        </NButton>
      </template>
      <div v-if="samples.length" class="history-table-wrap">
        <table class="history-table">
          <thead>
            <tr><th>探测时间</th><th>节点</th><th>位置</th><th>IP</th><th>协议</th><th>耗时</th></tr>
          </thead>
          <tbody>
            <tr v-for="(sample, index) in samples" :key="`${sample.checked_at}-${index}`">
              <td>{{ formatDateTime(sample.checked_at) }}</td>
              <td><NTag size="small" :type="index === 0 ? 'success' : 'default'" :bordered="false">{{ sample.colo }}</NTag></td>
              <td>{{ locationLabel(sample.location) }}</td>
              <td class="mono-value break-value">{{ sample.ip }}</td>
              <td>{{ sample.http_protocol || '-' }} / {{ sample.tls_version || '-' }}</td>
              <td>{{ sample.duration_ms }} ms</td>
            </tr>
          </tbody>
        </table>
      </div>
      <NEmpty v-else description="暂无探测历史" />
    </NCard>
  </div>
</template>

<style scoped>
.trace-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.page-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.page-heading h2 {
  margin: 4px 0 5px;
  font-size: 24px;
}

.eyebrow {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #1677a3;
  font-size: 13px;
  font-weight: 600;
}

.metric-card {
  height: 100%;
}

.metric-value {
  margin: 8px 0 5px;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
}

.metric-value--small {
  font-size: 22px;
}

.metric-value--mono,
.mono-value,
.raw-trace {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.status-icon--active {
  color: #18a058;
}

.break-value {
  word-break: break-all;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, .8fr) minmax(0, 1.2fr);
  gap: 16px;
}

.node-list {
  display: flex;
  flex-direction: column;
}

.node-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid #eceef1;
}

.node-row:first-child {
  padding-top: 0;
}

.node-row:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.node-row strong,
.node-row span {
  display: block;
}

.node-row span {
  margin-top: 3px;
  color: #8c8c8c;
  font-size: 12px;
}

.raw-trace {
  max-height: 310px;
  margin: 0;
  overflow: auto;
  padding: 12px;
  border: 1px solid #e5e7eb;
  background: #f7f8fa;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}

.history-table-wrap {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
}

.history-table {
  width: 100%;
  min-width: 840px;
  border-collapse: collapse;
}

.history-table th,
.history-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #eceef1;
  text-align: left;
}

.history-table th {
  background: #f7f8fa;
  font-size: 12px;
}

.history-table tbody tr:last-child td {
  border-bottom: 0;
}

@media (max-width: 840px) {
  .page-heading {
    flex-direction: column;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
