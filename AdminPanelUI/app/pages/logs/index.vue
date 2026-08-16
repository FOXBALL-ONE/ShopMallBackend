<script setup lang="ts">
import {
  FileText,
  ListRestart,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Save,
  Search,
  Trash2,
} from '@lucide/vue'
import { useMessage, type VirtualListInst } from 'naive-ui'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import type { CSSProperties } from 'vue'
import type {
  HistoricalLogFile,
  HistoryContentResponse,
  HistoryLogLine,
  LiveLogEvent,
  LoggerOverride,
  LoggingSettings,
  LogLevel,
} from '~/types/logging'

definePageMeta({ layout: 'default' })

type TabKey = 'live' | 'history' | 'settings'
type HistoryReadMode = 'tail' | 'start'
type LogVirtualListInst = VirtualListInst & {
  getScrollContainer: () => HTMLElement | null | undefined
}

const LOG_LEVELS: LogLevel[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'OFF']
const LIVE_LEVELS: Exclude<LogLevel, 'OFF'>[] = ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']
const MAX_VISIBLE_LIVE_EVENTS = 2_000
const MAX_VISIBLE_LIVE_CHARACTERS = 4 * 1024 * 1024
const MAX_VISIBLE_HISTORY_LINES = 10_000
const MAX_VISIBLE_HISTORY_CHARACTERS = 8 * 1024 * 1024
const LOG_ROW_SIZE = 26
const LOG_ROW_HORIZONTAL_CHROME = 112
const LOG_FONT = '12px ui-monospace, SFMono-Regular, Consolas, monospace'
const LIVE_RETRY_DELAYS_MS = [500, 1_000, 2_000, 5_000]
const LOGGER_NAME_PATTERN = /^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*$/
const HISTORY_LOG_LEVEL_PATTERN = /(?:^|\s|\[)(TRACE|DEBUG|INFO|WARN|ERROR)(?=$|\s|\])/
const AUDIT_LOGGER = 'top.foxball.shopmall.logging.audit'

const api = useLoggingApi()
const message = useMessage()
const activeTab = ref<TabKey>('live')
const settings = ref<LoggingSettings | null>(null)
const settingsLoading = ref(false)
const settingsSaving = ref(false)
const previewLoading = ref(false)
const templatePreview = ref<string | null>(null)
const templatePreviewBytes = ref<number | null>(null)

const settingsForm = reactive<{
  rootLevel: LogLevel
  loggerOverrides: LoggerOverride[]
  outputTemplate: string
}>({
  rootLevel: 'INFO',
  loggerOverrides: [],
  outputTemplate: '',
})

const levelOptions = LOG_LEVELS.map(level => ({ label: level, value: level }))
const liveLevelOptions = LIVE_LEVELS.map(level => ({ label: level, value: level }))
const runtimeStatusType = computed(() => settings.value?.runtime_status === 'UP' ? 'success' : 'error')
const settingsSourceLabel = computed(() => settings.value?.source === 'REDIS' ? 'Redis 运行时设置' : '部署默认设置')
const settingsDirty = computed(() => {
  const current = settings.value
  if (!current) return false
  return settingsForm.rootLevel !== current.root_level ||
    settingsForm.outputTemplate !== current.output_template ||
    JSON.stringify(settingsForm.loggerOverrides) !== JSON.stringify(current.logger_overrides)
})

const liveMinimumLevel = ref<Exclude<LogLevel, 'OFF'>>('TRACE')
const liveLoggerPrefix = ref('')
const liveQuery = ref('')
const liveEvents = ref<LiveLogEvent[]>([])
const liveRunning = ref(true)
const liveAutoScroll = ref(true)
const liveWaiting = ref(false)
const liveBootId = ref<string | null>(null)
const liveAfterSequence = ref<number | null>(null)
const liveInstanceId = ref<string | null>(null)
const liveGapMessage = ref<string | null>(null)
const liveTemplateVersion = ref<number | null>(null)
const liveTemplateMessage = ref<string | null>(null)
const liveError = ref<string | null>(null)
const liveOutputRef = ref<LogVirtualListInst | null>(null)
const liveContentWidth = ref(0)
let liveAbortController: AbortController | null = null
let liveLoopGeneration = 0
let liveRetryAttempt = 0
let liveRetainedCharacters = 0
let logMeasureContext: CanvasRenderingContext2D | null = null

const historyDates = ref<{ date: string; file_count: number; size_bytes: number }[]>([])
const historyDate = ref<string | null>(null)
const historyFiles = ref<HistoricalLogFile[]>([])
const historyNextCursor = ref<number | null>(null)
const selectedHistoryFile = ref<HistoricalLogFile | null>(null)
const historyContent = ref<HistoryContentResponse | null>(null)
const historyLines = ref<HistoryLogLine[]>([])
const historyQuery = ref('')
const historyReadMode = ref<HistoryReadMode>('tail')
const historyDatesLoading = ref(false)
const historyFilesLoading = ref(false)
const historyContentLoading = ref(false)
const historyOutputRef = ref<LogVirtualListInst | null>(null)
const historyContentWidth = ref(0)
let historyRetainedCharacters = 0
let historySelectionGeneration = 0
let historyDatesRequestId = 0
let historyFilesRequestId = 0
let historyContentRequestId = 0

const historyDateOptions = computed(() => historyDates.value.map(item => ({
  label: `${item.date} · ${item.file_count} 个文件 · ${formatBytes(item.size_bytes)}`,
  value: item.date,
})))
const liveItemsStyle = computed<CSSProperties>(() => ({
  '--log-content-width': `${liveContentWidth.value}px`,
}))
const historyItemsStyle = computed<CSSProperties>(() => ({
  '--log-content-width': `${historyContentWidth.value}px`,
}))

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusCode?: number; statusMessage?: string; message?: string; name?: string }
    return value.statusMessage || value.message || '请求失败'
  }
  return '请求失败'
}

function isAbortError(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && (error as { name?: string }).name === 'AbortError')
}

function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB']
  const index = Math.min(Math.floor(Math.log(value) / Math.log(1024)), units.length - 1)
  const amount = value / (1024 ** index)
  return `${amount >= 100 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '-'
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString('zh-CN', { hour12: false })
}

function historyLineLevelClass(text: string): string | null {
  const level = HISTORY_LOG_LEVEL_PATTERN.exec(text)?.[1]
  return level ? `history-line--${level.toLowerCase()}` : null
}

function measureLogContentWidth(lines: readonly string[]): number {
  if (!import.meta.client || lines.length === 0) return 0
  if (!logMeasureContext) {
    logMeasureContext = document.createElement('canvas').getContext('2d')
  }
  if (!logMeasureContext) return 0
  logMeasureContext.font = LOG_FONT
  let widestLine = 0
  for (const line of lines) {
    for (const segment of line.split(/\r?\n/)) {
      const measured = logMeasureContext.measureText(segment.replaceAll('\t', '        ')).width
      widestLine = Math.max(widestLine, measured)
    }
  }
  return Math.ceil(widestLine) + LOG_ROW_HORIZONTAL_CHROME
}

function syncLogViewportWidth(list: LogVirtualListInst | null) {
  list?.getScrollContainer()?.dispatchEvent(new Event('scroll'))
}

function applySettings(value: LoggingSettings) {
  settings.value = value
  settingsForm.rootLevel = value.root_level
  settingsForm.loggerOverrides = value.logger_overrides.map(item => ({ ...item }))
  settingsForm.outputTemplate = value.output_template
  templatePreview.value = null
  templatePreviewBytes.value = null
}

async function loadSettings(showSuccess = false) {
  settingsLoading.value = true
  try {
    applySettings(await api.getSettings())
    if (showSuccess) message.success('已加载最新日志设置')
  } catch (error) {
    message.error(`加载日志设置失败：${errorMessage(error)}`)
  } finally {
    settingsLoading.value = false
  }
}

function addLoggerOverride() {
  if (settingsForm.loggerOverrides.length >= 50) {
    message.warning('最多配置 50 条 logger 覆盖')
    return
  }
  settingsForm.loggerOverrides.push({ logger_name: '', level: 'DEBUG' })
}

function removeLoggerOverride(index: number) {
  settingsForm.loggerOverrides.splice(index, 1)
}

function normalizedLoggerOverrides(): LoggerOverride[] | null {
  if (settingsForm.loggerOverrides.length > 50) {
    message.error('最多配置 50 条 logger 覆盖')
    return null
  }
  const normalized = settingsForm.loggerOverrides.map(item => ({
    logger_name: item.logger_name.trim(),
    level: item.level,
  }))
  if (normalized.some(item => !LOGGER_NAME_PATTERN.test(item.logger_name))) {
    message.error('logger 名称必须由点分隔的 Java 标识符组成；每段以字母、下划线或 $ 开头，后续可包含字母、数字、下划线或 $')
    return null
  }
  if (normalized.some(item => item.logger_name.length > 200)) {
    message.error('logger 名称不能超过 200 个字符')
    return null
  }
  if (normalized.some(item => item.logger_name === AUDIT_LOGGER || item.logger_name === 'ROOT')) {
    message.error('审计 logger 和 ROOT 不能作为覆盖项')
    return null
  }
  if (new Set(normalized.map(item => item.logger_name)).size !== normalized.length) {
    message.error('logger 覆盖项不能重复')
    return null
  }
  return normalized.sort((left, right) => left.logger_name.localeCompare(right.logger_name))
}

async function previewTemplate() {
  if (!settingsForm.outputTemplate) {
    message.warning('输出模板不能为空')
    return
  }
  previewLoading.value = true
  try {
    const preview = await api.previewTemplate(settingsForm.outputTemplate)
    templatePreview.value = preview.rendered
    templatePreviewBytes.value = preview.encoded_size_bytes
  } catch (error) {
    templatePreview.value = null
    templatePreviewBytes.value = null
    message.error(`模板校验失败：${errorMessage(error)}`)
  } finally {
    previewLoading.value = false
  }
}

async function saveSettings() {
  const current = settings.value
  if (!current) return
  const loggerOverrides = normalizedLoggerOverrides()
  if (!loggerOverrides) return
  if (!settingsForm.outputTemplate || new TextEncoder().encode(settingsForm.outputTemplate).length > 1_024) {
    message.error('输出模板必须为 1 到 1024 个 UTF-8 字节')
    return
  }

  settingsSaving.value = true
  try {
    const saved = await api.updateSettings({
      root_level: settingsForm.rootLevel,
      logger_overrides: loggerOverrides,
      output_template: settingsForm.outputTemplate,
      expected_version: current.version,
    })
    applySettings(saved)
    message.success('日志设置已保存并即时生效')
  } catch (error) {
    if ((error as { statusCode?: number })?.statusCode === 409) {
      await loadSettings()
      message.warning('设置已被其他管理员修改，已重新加载最新值')
    } else {
      message.error(`保存日志设置失败：${errorMessage(error)}`)
    }
  } finally {
    settingsSaving.value = false
  }
}

function cancelLiveRequest() {
  liveLoopGeneration++
  liveAbortController?.abort()
  liveAbortController = null
  liveWaiting.value = false
  liveRetryAttempt = 0
}

function clearLiveEvents() {
  liveEvents.value = []
  liveRetainedCharacters = 0
  liveContentWidth.value = 0
}

function clearHistoryLines() {
  historyLines.value = []
  historyRetainedCharacters = 0
  historyContentWidth.value = 0
}

async function appendLiveBatch(batch: Awaited<ReturnType<typeof api.getLive>>) {
  if (batch.reset) {
    clearLiveEvents()
    liveGapMessage.value = '后端实例已重启，实时游标已重置。'
    liveTemplateVersion.value = null
    liveTemplateMessage.value = null
  } else if (batch.gap) {
    liveGapMessage.value = `内存缓冲区已淘汰 ${batch.dropped_count} 条日志，可在历史日志中补查。`
  }
  liveBootId.value = batch.boot_id
  liveAfterSequence.value = batch.next_sequence
  liveInstanceId.value = batch.instance_id
  if (batch.events.length > 0) {
    for (const event of batch.events) {
      if (liveTemplateVersion.value !== null && event.template_version !== liveTemplateVersion.value) {
        liveTemplateMessage.value = `日志输出模板已切换（版本 ${event.template_version}）`
      }
      liveTemplateVersion.value = event.template_version
    }
    liveEvents.value.push(...batch.events)
    liveRetainedCharacters += batch.events.reduce((total, event) => total + event.rendered.length + 128, 0)
    let removeCount = Math.max(0, liveEvents.value.length - MAX_VISIBLE_LIVE_EVENTS)
    let retainedAfterRemoval = liveRetainedCharacters
    for (let index = 0; index < removeCount; index++) {
      retainedAfterRemoval -= liveEvents.value[index]!.rendered.length + 128
    }
    while (
      retainedAfterRemoval > MAX_VISIBLE_LIVE_CHARACTERS &&
      removeCount < liveEvents.value.length - 1
    ) {
      retainedAfterRemoval -= liveEvents.value[removeCount]!.rendered.length + 128
      removeCount++
    }
    if (removeCount > 0) {
      liveEvents.value.splice(0, removeCount)
      liveRetainedCharacters = retainedAfterRemoval
    }
    liveContentWidth.value = measureLogContentWidth(liveEvents.value.map(event => event.rendered))
    await nextTick()
    syncLogViewportWidth(liveOutputRef.value)
    if (liveAutoScroll.value && liveEvents.value.length > 0) {
      liveOutputRef.value?.scrollTo({ index: liveEvents.value.length - 1, position: 'bottom' })
    }
  }
}

async function runLiveLoop(generation: number) {
  while (liveRunning.value && activeTab.value === 'live' && generation === liveLoopGeneration) {
    const controller = new AbortController()
    liveAbortController = controller
    liveWaiting.value = true
    try {
      const params: Record<string, unknown> = {
        minimum_level: liveMinimumLevel.value,
        limit: 200,
        wait_seconds: 20,
      }
      if (liveBootId.value) params.boot_id = liveBootId.value
      if (liveAfterSequence.value !== null) params.after_sequence = liveAfterSequence.value
      if (liveLoggerPrefix.value.trim()) params.logger_prefix = liveLoggerPrefix.value.trim()
      if (liveQuery.value.trim()) params.query = liveQuery.value.trim()
      const batch = await api.getLive(params, controller.signal)
      if (generation !== liveLoopGeneration) return
      liveError.value = null
      liveRetryAttempt = 0
      await appendLiveBatch(batch)
    } catch (error) {
      if (generation !== liveLoopGeneration || isAbortError(error)) return
      liveError.value = errorMessage(error)
      const delay = LIVE_RETRY_DELAYS_MS[Math.min(liveRetryAttempt, LIVE_RETRY_DELAYS_MS.length - 1)]
      liveRetryAttempt += 1
      await new Promise<void>(resolve => {
        if (controller.signal.aborted) {
          resolve()
          return
        }
        const timeout = window.setTimeout(resolve, delay)
        const onAbort = () => {
          window.clearTimeout(timeout)
          resolve()
        }
        controller.signal.addEventListener('abort', onAbort, { once: true })
      })
      if (generation !== liveLoopGeneration) return
    } finally {
      if (liveAbortController === controller) liveAbortController = null
      if (generation === liveLoopGeneration) liveWaiting.value = false
    }
  }
}

function startLiveLoop() {
  if (!liveRunning.value || activeTab.value !== 'live') return
  if (import.meta.client && document.visibilityState === 'hidden') return
  cancelLiveRequest()
  const generation = liveLoopGeneration
  void runLiveLoop(generation)
}

function handleVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    cancelLiveRequest()
  } else if (liveRunning.value && activeTab.value === 'live') {
    startLiveLoop()
  }
}

function toggleLive() {
  if (liveRunning.value) {
    liveRunning.value = false
    cancelLiveRequest()
  } else {
    liveRunning.value = true
    startLiveLoop()
  }
}

function restartLive() {
  liveBootId.value = null
  liveAfterSequence.value = null
  clearLiveEvents()
  liveGapMessage.value = null
  liveTemplateVersion.value = null
  liveTemplateMessage.value = null
  liveError.value = null
  liveRetryAttempt = 0
  if (liveRunning.value) startLiveLoop()
}

function clearLive() {
  clearLiveEvents()
  liveGapMessage.value = null
  liveTemplateMessage.value = null
}

async function loadHistoryDates(showSuccess = false) {
  const requestId = ++historyDatesRequestId
  const selectionGeneration = historySelectionGeneration
  historyDatesLoading.value = true
  try {
    const response = await api.getDates()
    if (requestId !== historyDatesRequestId || selectionGeneration !== historySelectionGeneration) return
    historyDates.value = response.dates
    const selectedStillExists = historyDate.value && response.dates.some(item => item.date === historyDate.value)
    historyDate.value = selectedStillExists ? historyDate.value : response.dates[0]?.date ?? null
    await loadHistoryFiles(false)
    if (showSuccess) message.success('已刷新历史日志索引')
  } catch (error) {
    if (requestId !== historyDatesRequestId || selectionGeneration !== historySelectionGeneration) return
    message.error(`加载日志日期失败：${errorMessage(error)}`)
  } finally {
    if (requestId === historyDatesRequestId) historyDatesLoading.value = false
  }
}

async function loadHistoryFiles(append: boolean) {
  const date = historyDate.value
  if (!date) {
    historyFiles.value = []
    historyNextCursor.value = null
    selectedHistoryFile.value = null
    historyContent.value = null
    clearHistoryLines()
    return
  }
  const cursor = append ? historyNextCursor.value : 0
  if (append && cursor === null) return

  const requestId = ++historyFilesRequestId
  const selectionGeneration = historySelectionGeneration
  historyFilesLoading.value = true
  try {
    const response = await api.getFiles(date, cursor ?? 0, 100)
    if (
      requestId !== historyFilesRequestId ||
      selectionGeneration !== historySelectionGeneration ||
      historyDate.value !== date
    ) return
    if (append) {
      historyFiles.value.push(...response.files)
    } else {
      const previousFilename = selectedHistoryFile.value?.date === date
        ? selectedHistoryFile.value.filename
        : null
      historyFiles.value = response.files
      selectedHistoryFile.value = response.files.find(file => file.filename === previousFilename) ?? response.files[0] ?? null
      historyContent.value = null
      clearHistoryLines()
    }
    historyNextCursor.value = response.next_cursor
    if (!append && selectedHistoryFile.value) await loadHistoryContent(false)
  } catch (error) {
    if (
      requestId !== historyFilesRequestId ||
      selectionGeneration !== historySelectionGeneration ||
      historyDate.value !== date
    ) return
    message.error(`加载日志文件失败：${errorMessage(error)}`)
  } finally {
    if (requestId === historyFilesRequestId) historyFilesLoading.value = false
  }
}

async function selectHistoryDate(value: string | null) {
  historySelectionGeneration++
  historyDate.value = value
  selectedHistoryFile.value = null
  historyContent.value = null
  clearHistoryLines()
  await loadHistoryFiles(false)
}

async function selectHistoryFile(file: HistoricalLogFile) {
  historySelectionGeneration++
  selectedHistoryFile.value = file
  historyContent.value = null
  clearHistoryLines()
  await loadHistoryContent(false, true)
}

async function loadHistoryContent(append: boolean, force = false) {
  const file = selectedHistoryFile.value
  if (!file || (historyContentLoading.value && !force)) return
  if (append && (historyReadMode.value !== 'start' || historyContent.value?.eof)) return

  const requestId = ++historyContentRequestId
  const selectionGeneration = historySelectionGeneration
  const fileKey = `${file.date}/${file.file_time}/${file.rotation_index}`
  historyContentLoading.value = true
  try {
    const params: Record<string, unknown> = {
      date: file.date,
      file_time: file.file_time,
      rotation_index: file.rotation_index,
      tail: historyReadMode.value === 'tail' && !append,
      limit: 200,
    }
    if (append && historyContent.value) params.after_offset = historyContent.value.next_offset
    if (historyQuery.value.trim()) params.query = historyQuery.value.trim()
    const response = await api.getContent(params)
    const currentFile = selectedHistoryFile.value
    if (
      requestId !== historyContentRequestId ||
      selectionGeneration !== historySelectionGeneration ||
      !currentFile ||
      `${currentFile.date}/${currentFile.file_time}/${currentFile.rotation_index}` !== fileKey
    ) return
    historyContent.value = response
    if (append) {
      historyLines.value.push(...response.lines)
      historyRetainedCharacters += response.lines.reduce((total, line) => total + line.text.length + 64, 0)
    } else {
      historyLines.value = response.lines
      historyRetainedCharacters = response.lines.reduce((total, line) => total + line.text.length + 64, 0)
    }
    let removeCount = Math.max(0, historyLines.value.length - MAX_VISIBLE_HISTORY_LINES)
    let retainedAfterRemoval = historyRetainedCharacters
    for (let index = 0; index < removeCount; index++) {
      retainedAfterRemoval -= historyLines.value[index]!.text.length + 64
    }
    while (
      retainedAfterRemoval > MAX_VISIBLE_HISTORY_CHARACTERS &&
      removeCount < historyLines.value.length - 1
    ) {
      retainedAfterRemoval -= historyLines.value[removeCount]!.text.length + 64
      removeCount++
    }
    if (removeCount > 0) {
      historyLines.value.splice(0, removeCount)
      historyRetainedCharacters = retainedAfterRemoval
    }
    historyContentWidth.value = measureLogContentWidth(historyLines.value.map(line => line.text))
    await nextTick()
    syncLogViewportWidth(historyOutputRef.value)
  } catch (error) {
    if (requestId !== historyContentRequestId || selectionGeneration !== historySelectionGeneration) return
    message.error(`读取日志内容失败：${errorMessage(error)}`)
  } finally {
    if (requestId === historyContentRequestId) historyContentLoading.value = false
  }
}

async function changeHistoryMode(value: HistoryReadMode) {
  historySelectionGeneration++
  historyReadMode.value = value
  historyContent.value = null
  clearHistoryLines()
  await loadHistoryContent(false, true)
}

async function refreshCurrentTab() {
  if (activeTab.value === 'live') {
    restartLive()
  } else if (activeTab.value === 'history') {
    await loadHistoryDates(true)
  } else {
    await loadSettings(true)
  }
}

watch(activeTab, value => {
  if (value === 'live') {
    if (liveRunning.value) startLiveLoop()
  } else {
    cancelLiveRequest()
  }
  if (value === 'history' && historyDates.value.length === 0 && !historyDatesLoading.value) {
    void loadHistoryDates()
  }
})

watch(liveMinimumLevel, () => {
  if (liveRunning.value && activeTab.value === 'live') restartLive()
})

watch(() => settingsForm.outputTemplate, () => {
  templatePreview.value = null
  templatePreviewBytes.value = null
})

onMounted(() => {
  document.addEventListener('visibilitychange', handleVisibilityChange)
  void loadSettings()
  startLiveLoop()
})

onBeforeUnmount(() => {
  document.removeEventListener('visibilitychange', handleVisibilityChange)
  liveRunning.value = false
  cancelLiveRequest()
})
</script>

<template>
  <div class="logs-page">
    <header class="page-heading">
      <div>
        <h2>日志中心</h2>
        <div class="heading-meta">
          <NTag size="small" :type="runtimeStatusType">
            {{ settings?.runtime_status === 'UP' ? '运行正常' : '运行异常' }}
          </NTag>
          <NText depth="3">实例 {{ settings?.instance_id ?? liveInstanceId ?? '-' }}</NText>
          <NText depth="3">{{ settings?.active_file ?? '尚无活动文件' }}</NText>
        </div>
      </div>
      <NTooltip>
        <template #trigger>
          <NButton circle quaternary :loading="settingsLoading || historyDatesLoading" aria-label="刷新当前日志视图" @click="refreshCurrentTab">
            <template #icon><RefreshCw :size="17" /></template>
          </NButton>
        </template>
        刷新
      </NTooltip>
    </header>

    <NAlert v-if="settings?.last_file_error" type="error" :bordered="false">
      {{ settings.last_file_error }}
    </NAlert>

    <NTabs v-model:value="activeTab" type="segment" animated class="log-tabs">
      <NTabPane name="live" tab="实时日志">
        <div class="toolbar live-toolbar">
          <NSelect v-model:value="liveMinimumLevel" :options="liveLevelOptions" class="level-filter" aria-label="最低日志等级" />
          <NInput
            v-model:value="liveLoggerPrefix"
            clearable
            maxlength="200"
            placeholder="logger 前缀"
            spellcheck="false"
            @keyup.enter="restartLive"
          />
          <NInput
            v-model:value="liveQuery"
            clearable
            maxlength="128"
            placeholder="内容筛选"
            @keyup.enter="restartLive"
          />
          <NTooltip>
            <template #trigger>
              <NButton circle tertiary aria-label="应用实时日志筛选" @click="restartLive">
                <template #icon><Search :size="16" /></template>
              </NButton>
            </template>
            应用筛选
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton circle :type="liveRunning ? 'warning' : 'primary'" tertiary :aria-label="liveRunning ? '暂停实时日志' : '继续实时日志'" @click="toggleLive">
                <template #icon>
                  <Pause v-if="liveRunning" :size="16" />
                  <Play v-else :size="16" />
                </template>
              </NButton>
            </template>
            {{ liveRunning ? '暂停' : '继续' }}
          </NTooltip>
          <NTooltip>
            <template #trigger>
              <NButton circle tertiary aria-label="清空实时日志显示" @click="clearLive">
                <template #icon><Trash2 :size="16" /></template>
              </NButton>
            </template>
            清空显示
          </NTooltip>
        </div>

        <div class="stream-state">
          <span class="connection-state" :class="{ 'connection-state--active': liveRunning && !liveError }">
            <span class="connection-dot" />
            {{ liveRunning ? (liveWaiting ? '等待新日志' : '实时连接中') : '已暂停' }}
          </span>
          <NText depth="3">{{ liveEvents.length }} 条已显示</NText>
          <span class="auto-scroll-control">
            <NText depth="3">自动滚动</NText>
            <NSwitch v-model:value="liveAutoScroll" size="small" aria-label="自动滚动" />
          </span>
          <NText v-if="liveBootId" depth="3" class="boot-id">boot {{ liveBootId.slice(0, 8) }}</NText>
        </div>

        <NAlert v-if="liveGapMessage" type="warning" :bordered="false" closable @close="liveGapMessage = null">
          {{ liveGapMessage }}
        </NAlert>
        <NAlert v-if="liveTemplateMessage" type="info" :bordered="false" closable @close="liveTemplateMessage = null">
          {{ liveTemplateMessage }}
        </NAlert>
        <NAlert v-if="liveError" type="error" :bordered="false">
          实时日志暂时中断：{{ liveError }}
        </NAlert>

        <div v-if="liveEvents.length === 0" class="log-viewport" aria-label="实时日志输出">
          <NEmpty v-if="liveEvents.length === 0" size="small" description="暂无符合条件的实时日志" class="viewport-empty" />
        </div>
        <NVirtualList
          v-else
          ref="liveOutputRef"
          class="log-viewport"
          aria-label="实时日志输出"
          :items="liveEvents"
          :item-size="LOG_ROW_SIZE"
          :items-style="liveItemsStyle"
          :scrollbar-props="{ xScrollable: true, trigger: 'none' }"
          key-field="sequence"
        >
          <template #default="{ item: event }">
            <div class="log-line" :class="`log-line--${event.level.toLowerCase()}`">
              <span class="log-sequence">{{ event.sequence }}</span>
              <span class="log-text">{{ event.rendered }}</span>
            </div>
          </template>
        </NVirtualList>
      </NTabPane>

      <NTabPane name="history" tab="历史日志">
        <div class="toolbar history-toolbar">
          <NSelect
            :value="historyDate"
            :options="historyDateOptions"
            :loading="historyDatesLoading"
            clearable
            placeholder="选择日期"
            class="date-filter"
            @update:value="selectHistoryDate"
          />
          <NInput
            v-model:value="historyQuery"
            clearable
            maxlength="128"
            placeholder="内容筛选"
            @keyup.enter="loadHistoryContent(false)"
          />
          <NRadioGroup :value="historyReadMode" size="small" @update:value="changeHistoryMode">
            <NRadioButton value="tail">最近</NRadioButton>
            <NRadioButton value="start">从头</NRadioButton>
          </NRadioGroup>
          <NTooltip>
            <template #trigger>
              <NButton circle tertiary :loading="historyContentLoading" aria-label="查询历史日志内容" @click="loadHistoryContent(false)">
                <template #icon><Search :size="16" /></template>
              </NButton>
            </template>
            查询
          </NTooltip>
        </div>

        <div class="history-browser">
          <aside class="file-panel" aria-label="历史日志文件">
            <div class="panel-heading">
              <strong>日志文件</strong>
              <NText depth="3">{{ historyFiles.length }}</NText>
            </div>
            <NSpin :show="historyFilesLoading && historyFiles.length === 0">
              <NEmpty v-if="historyFiles.length === 0" size="small" description="当前日期没有日志文件" class="file-empty" />
              <div v-else class="file-list">
                <NVirtualList
                  class="file-list-viewport"
                  :items="historyFiles"
                  :item-size="64"
                  key-field="filename"
                >
                  <template #default="{ item: file }">
                    <button
                      type="button"
                      class="file-row"
                      :class="{ 'file-row--selected': selectedHistoryFile?.filename === file.filename }"
                      @click="selectHistoryFile(file)"
                    >
                      <FileText :size="16" aria-hidden="true" />
                      <span class="file-copy">
                        <span class="file-name">{{ file.filename }}</span>
                        <span class="file-meta">{{ formatBytes(file.size_bytes) }} · {{ formatDateTime(file.modified_at) }}</span>
                      </span>
                      <NTag v-if="file.active" type="success" size="tiny">活动</NTag>
                    </button>
                  </template>
                </NVirtualList>
                <NButton
                  v-if="historyNextCursor !== null"
                  block
                  quaternary
                  :loading="historyFilesLoading"
                  @click="loadHistoryFiles(true)"
                >
                  加载更多文件
                </NButton>
              </div>
            </NSpin>
          </aside>

          <section class="history-output" aria-label="历史日志内容">
            <div class="panel-heading content-heading">
              <div>
                <strong>{{ selectedHistoryFile?.filename ?? '日志内容' }}</strong>
                <NText v-if="historyContent" depth="3">
                  {{ formatBytes(historyContent.file_size_bytes) }} · {{ historyContent.active ? '写入中' : '已关闭' }}
                </NText>
              </div>
              <NTooltip v-if="selectedHistoryFile">
                <template #trigger>
                  <NButton circle quaternary :loading="historyContentLoading" aria-label="重新读取当前日志文件" @click="loadHistoryContent(false)">
                    <template #icon><RefreshCw :size="16" /></template>
                  </NButton>
                </template>
                重新读取
              </NTooltip>
            </div>
            <NSpin :show="historyContentLoading && historyLines.length === 0">
              <div v-if="historyLines.length === 0" class="history-viewport">
                <NEmpty size="small" description="暂无可显示的完整日志行" class="viewport-empty" />
              </div>
              <NVirtualList
                v-else
                ref="historyOutputRef"
                class="history-viewport"
                :items="historyLines"
                :item-size="LOG_ROW_SIZE"
                :items-style="historyItemsStyle"
                :scrollbar-props="{ xScrollable: true, trigger: 'none' }"
                key-field="offset"
              >
                <template #default="{ item: line }">
                  <div class="history-line" :class="historyLineLevelClass(line.text)">
                    <span class="log-offset">{{ line.offset }}</span>
                    <span class="log-text">{{ line.text }}</span>
                  </div>
                </template>
              </NVirtualList>
            </NSpin>
            <NButton
              v-if="historyReadMode === 'start' && historyContent && !historyContent.eof"
              class="load-lines"
              :loading="historyContentLoading"
              @click="loadHistoryContent(true)"
            >
              <template #icon><ListRestart :size="16" /></template>
              继续读取
            </NButton>
          </section>
        </div>
      </NTabPane>

      <NTabPane name="settings" tab="运行设置">
        <NSpin :show="settingsLoading && !settings">
          <NAlert v-if="settings?.source === 'DEFAULT'" type="warning" :bordered="false" class="source-alert">
            当前使用部署默认设置，首次保存后写入 Redis。
          </NAlert>

          <section class="settings-section">
            <div class="section-heading">
              <div>
                <h3>日志等级</h3>
                <NText depth="3">配置版本 {{ settings?.version ?? '-' }} · 生效版本 {{ settings?.effective_version ?? '-' }}</NText>
              </div>
              <NButton
                type="primary"
                :loading="settingsSaving"
                :disabled="settingsLoading || !settings || !settingsDirty"
                @click="saveSettings"
              >
                <template #icon><Save :size="17" /></template>
                保存设置
              </NButton>
            </div>

            <NForm label-placement="top" class="root-level-form">
              <NFormItem label="根日志等级">
                <NSelect v-model:value="settingsForm.rootLevel" :options="levelOptions" :disabled="settingsLoading || settingsSaving" />
              </NFormItem>
            </NForm>

            <div class="override-heading">
              <strong>Logger 覆盖</strong>
              <NButton size="small" :disabled="settingsLoading || settingsSaving || settingsForm.loggerOverrides.length >= 50" @click="addLoggerOverride">
                <template #icon><Plus :size="16" /></template>
                添加
              </NButton>
            </div>
            <NEmpty v-if="settingsForm.loggerOverrides.length === 0" size="small" description="暂无 logger 覆盖" class="override-empty" />
            <div v-else class="override-list">
              <div v-for="(override, index) in settingsForm.loggerOverrides" :key="index" class="override-row">
                <div class="field-with-hint">
                  <NInput
                    v-model:value="override.logger_name"
                    maxlength="200"
                    placeholder="top.foxball.shopmall.service"
                    spellcheck="false"
                    :disabled="settingsLoading || settingsSaving"
                  />
                  <small class="field-hint">由点分隔的 Java 标识符组成，每段以字母、_ 或 $ 开头，最多 200 个字符；不可重复或使用 ROOT 与审计 logger。</small>
                </div>
                <NSelect
                  v-model:value="override.level"
                  :options="levelOptions"
                  :disabled="settingsLoading || settingsSaving"
                />
                <NTooltip>
                  <template #trigger>
                    <NButton circle tertiary type="error" :aria-label="`删除第 ${index + 1} 条 logger 覆盖`" :disabled="settingsLoading || settingsSaving" @click="removeLoggerOverride(index)">
                      <template #icon><Trash2 :size="16" /></template>
                    </NButton>
                  </template>
                  删除
                </NTooltip>
              </div>
            </div>
          </section>

          <section class="settings-section">
            <div class="section-heading">
              <div>
                <h3>输出模板</h3>
                <NText depth="3">{timestamp} {level} {thread} {logger} {request_id} {message} {exception}</NText>
              </div>
              <NButton :loading="previewLoading" :disabled="settingsLoading || settingsSaving" @click="previewTemplate">
                <template #icon><Search :size="16" /></template>
                校验预览
              </NButton>
            </div>
            <NInput
              v-model:value="settingsForm.outputTemplate"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 6 }"
              maxlength="1024"
              show-count
              spellcheck="false"
              :disabled="settingsLoading || settingsSaving"
              class="template-input"
            />
            <div v-if="templatePreview !== null" class="template-preview">
              <span class="preview-label">预览</span>
              <div class="preview-copy">
                <code>{{ templatePreview }}</code>
                <NText depth="3" class="preview-size">{{ templatePreviewBytes ?? 0 }} B UTF-8</NText>
              </div>
            </div>
          </section>

          <section class="settings-section runtime-details">
            <div class="section-heading">
              <div>
                <h3>本机输出</h3>
                <NText depth="3">{{ settingsSourceLabel }} · {{ formatDateTime(settings?.updated_at) }}</NText>
              </div>
            </div>
            <NDescriptions :column="3" label-placement="top" size="small" bordered>
              <NDescriptionsItem label="存储路径">{{ settings?.storage_path ?? '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="活动文件">{{ settings?.active_file ?? '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="文件大小">{{ formatBytes(settings?.active_file_size_bytes ?? 0) }}</NDescriptionsItem>
              <NDescriptionsItem label="单文件上限">{{ formatBytes(settings?.max_file_size_bytes ?? 0) }}</NDescriptionsItem>
              <NDescriptionsItem label="保留天数">{{ settings?.retention_days ?? '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="时区">{{ settings?.time_zone ?? '-' }}</NDescriptionsItem>
            </NDescriptions>
          </section>
        </NSpin>
      </NTabPane>
    </NTabs>
  </div>
</template>

<style scoped>
.logs-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.page-heading,
.section-heading,
.override-heading,
.panel-heading,
.stream-state {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.page-heading h2,
.section-heading h3 {
  margin: 0;
}

.page-heading h2 {
  margin-bottom: 8px;
  font-size: 22px;
}

.section-heading h3 {
  margin-bottom: 4px;
  font-size: 16px;
}

.heading-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
}

.log-tabs :deep(.n-tabs-pane-wrapper) {
  overflow: visible;
}

.toolbar {
  display: grid;
  gap: 10px;
  align-items: center;
  margin: 18px 0 12px;
}

.live-toolbar {
  grid-template-columns: 120px minmax(180px, 0.8fr) minmax(220px, 1fr) 34px 34px 34px;
}

.history-toolbar {
  grid-template-columns: minmax(250px, 0.9fr) minmax(220px, 1fr) auto 34px;
}

.stream-state {
  justify-content: flex-start;
  min-height: 28px;
  margin-bottom: 10px;
}

.connection-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #737373;
  font-size: 13px;
}

.connection-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  border-radius: 50%;
  background: #a3a3a3;
}

.connection-state--active {
  color: #16794b;
}

.connection-state--active .connection-dot {
  background: #18a058;
}

.boot-id {
  margin-left: auto;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.auto-scroll-control {
  display: inline-flex;
  align-items: center;
  gap: 7px;
}

.log-viewport,
.history-viewport {
  overflow: auto;
  background: #111418;
  color: #d6d9de;
  border: 1px solid #272c33;
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
}

.log-viewport {
  height: clamp(360px, 58vh, 680px);
  margin-top: 10px;
}

.viewport-empty {
  min-height: 220px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.log-line,
.history-line {
  display: grid;
  grid-template-columns: 68px max-content;
  gap: 12px;
  width: max-content;
  min-width: 100%;
  height: 26px;
  min-height: 26px;
  box-sizing: border-box;
  overflow: visible;
  padding: 2px 12px;
  border-bottom: 1px solid #20252b;
}

.log-viewport :deep(.v-vl-items),
.history-viewport :deep(.v-vl-items),
.log-viewport :deep(.v-vl-visible-items),
.history-viewport :deep(.v-vl-visible-items) {
  width: var(--log-content-width, 100%) !important;
  min-width: 100%;
}

.log-line--trace,
.history-line--trace {
  color: #8b949e;
}

.log-line--debug,
.history-line--debug {
  color: #79c0ff;
}

.log-line--info,
.history-line--info {
  color: #7ee787;
}

.log-line--warn,
.history-line--warn {
  color: #f2cc60;
}

.log-line--error,
.history-line--error {
  color: #ff7b72;
}

.log-sequence,
.log-offset {
  color: #697480;
  text-align: right;
  user-select: none;
}

.log-text {
  width: max-content;
  min-width: max-content;
  white-space: pre;
}

.history-browser {
  display: grid;
  grid-template-columns: minmax(260px, 340px) minmax(0, 1fr);
  min-height: 520px;
  border: 1px solid var(--n-border-color, #e5e7eb);
  border-radius: 6px;
  overflow: hidden;
}

.file-panel {
  min-width: 0;
  border-right: 1px solid var(--n-border-color, #e5e7eb);
  background: #fafafa;
}

.panel-heading {
  min-height: 52px;
  padding: 0 14px;
  border-bottom: 1px solid var(--n-border-color, #e5e7eb);
}

.content-heading > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.file-list {
  padding: 6px;
}

.file-list-viewport {
  height: 540px;
}

.file-row {
  width: 100%;
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  gap: 9px;
  align-items: center;
  padding: 10px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.file-row:hover,
.file-row--selected {
  background: #e9eef5;
}

.file-row:focus-visible {
  outline: 2px solid #2080f0;
  outline-offset: -2px;
}

.file-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.file-name,
.file-meta {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-name {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
}

.file-meta {
  color: #737373;
  font-size: 11px;
}

.file-empty {
  min-height: 240px;
}

.history-output {
  display: flex;
  min-width: 0;
  flex-direction: column;
  background: #fff;
}

.history-viewport {
  height: 548px;
  min-height: 420px;
  max-height: 600px;
  flex: 1;
  border: 0;
  border-radius: 0;
}

.load-lines {
  margin: 10px auto;
}

.source-alert {
  margin-top: 18px;
}

.settings-section {
  padding: 22px 0;
  border-bottom: 1px solid var(--n-border-color, #e5e7eb);
}

.root-level-form {
  max-width: 320px;
  margin-top: 18px;
}

.root-level-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.override-heading {
  margin-top: 18px;
}

.override-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
  margin-top: 12px;
}

.override-row {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) 130px 34px;
  gap: 9px;
  align-items: start;
}

.field-with-hint {
  width: 100%;
  min-width: 0;
}

.field-hint {
  display: block;
  margin-top: 5px;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.4;
}

.override-empty {
  margin: 22px 0 4px;
}

.template-input {
  margin-top: 16px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.template-preview {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 10px;
  margin-top: 12px;
  padding: 12px;
  overflow-x: auto;
  border: 1px solid #30363d;
  border-radius: 6px;
  background: #111418;
  color: #d6d9de;
}

.preview-label {
  color: #8b949e;
}

.template-preview code {
  white-space: pre;
}

.preview-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 6px;
}

.preview-size {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}

.runtime-details {
  border-bottom: 0;
}

.runtime-details :deep(.n-descriptions) {
  margin-top: 16px;
}

@media (max-width: 1000px) {
  .live-toolbar {
    grid-template-columns: 110px minmax(160px, 1fr) minmax(180px, 1fr) 34px 34px 34px;
  }

  .history-browser {
    grid-template-columns: minmax(230px, 290px) minmax(0, 1fr);
  }
}

@media (max-width: 780px) {
  .live-toolbar,
  .history-toolbar {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 34px 34px 34px;
  }

  .live-toolbar .level-filter,
  .history-toolbar .date-filter {
    grid-column: span 2;
  }

  .history-toolbar :deep(.n-radio-group) {
    grid-column: span 2;
  }

  .history-browser {
    grid-template-columns: 1fr;
  }

  .file-panel {
    border-right: 0;
    border-bottom: 1px solid var(--n-border-color, #e5e7eb);
  }

  .file-list {
    padding: 6px;
  }

  .file-list-viewport {
    height: 220px;
  }

  .runtime-details :deep(.n-descriptions-table) {
    display: block;
    overflow-x: auto;
  }
}

@media (max-width: 560px) {
  .page-heading,
  .section-heading {
    align-items: flex-start;
  }

  .section-heading {
    flex-direction: column;
  }

  .live-toolbar,
  .history-toolbar {
    grid-template-columns: minmax(0, 1fr) 34px 34px 34px;
  }

  .live-toolbar .level-filter,
  .live-toolbar > :nth-child(2),
  .live-toolbar > :nth-child(3),
  .history-toolbar .date-filter,
  .history-toolbar > :nth-child(2),
  .history-toolbar :deep(.n-radio-group) {
    grid-column: 1 / -1;
  }

  .history-toolbar > :last-child {
    grid-column: -2;
  }

  .override-row {
    grid-template-columns: minmax(0, 1fr) 112px 34px;
  }

  .log-line,
  .history-line {
    grid-template-columns: 48px max-content;
    gap: 8px;
    padding: 2px 8px;
  }
}
</style>
