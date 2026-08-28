<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

definePageMeta({ layout: false })

type TaskStatus = string
type QueueTask = {
  id: number
  name: string
  type: '图片' | '视频'
  provider: string
  progress: number
  status: TaskStatus
  stage: string
  submittedAt: string
  generatedAt: string | null
  errorCode: string | null
  errorMessage: string | null
}
type ProviderModel = { id: number; name: string }
type Provider = { id: number; name: string; type: string; enabled: boolean; modelId: number | null; model: string; models: ProviderModel[] }
type TaskResponse = { id: number; workflowName: string; media: 'IMAGE' | 'VIDEO'; type: '图片' | '视频'; statusLabel: TaskStatus; status: string; stage: string; progress: number; errorCode?: string | null; errorMessage?: string | null; provider: {name: string; modelId: number | null; model: string}; batchIndex: number; batchCount: number; createdAt: string; completedAt?: string | null }
type Workflow = { id: number; name: string; version: number; versionLabel: string; savedAt: string; definition: {creativePrompt: string; garmentAssetId?: number; modelAssetId?: number; referenceImages?: Array<{assetId: number; role: string; instruction: string}>} }
type Resolution = '1k' | '2k' | '4k'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const { activeProjectId } = useProjectWorkspace(projectId)
const selectedWorkflowId = ref<number | null>(null)
const selectedProviderId = ref<number | null>(null)
const selectedModelId = ref<number | null>(null)
const batchCount = ref(1)
const resolution = ref<Resolution>('1k')
const isSubmitting = ref(false)
const lastUpdated = ref('刚刚')
const toast = ref('')
const loadingProviders = ref(true)
const providerError = ref('')
const refreshingProviderModels = ref(false)
const loadingWorkflows = ref(true)
const workflowError = ref('')
const workflows = ref<Workflow[]>([])

const providers = ref<Provider[]>([])
const enabledProviders = computed(() => providers.value.filter((provider) => provider.enabled))
const selectedProvider = computed(() => enabledProviders.value.find((provider) => provider.id === selectedProviderId.value))
const selectedModel = computed(() => selectedProvider.value?.models.find((model) => model.id === selectedModelId.value))
const resolutionOptions: Array<{value: Resolution; label: string; size: string; hint: string}> = [
  {value: '1k', label: '1K', size: '1024x1024', hint: '1024 × 1024'},
  {value: '2k', label: '2K', size: '2048x2048', hint: '2048 × 2048'},
  {value: '4k', label: '4K', size: '3840x2160', hint: '3840 × 2160（文档支持的 4K 横向尺寸）'},
]
const selectedResolution = computed(() => resolutionOptions.find((option) => option.value === resolution.value) ?? resolutionOptions[0]!)

const queue = ref<QueueTask[]>([])
let workspaceLoadVersion = 0
let queuePollTimer: number | undefined
let queueRequestInFlight = false

const selectedWorkflow = computed(() => workflows.value.find((workflow) => workflow.id === selectedWorkflowId.value))
const canSubmit = computed(() => Number.isInteger(batchCount.value) && batchCount.value >= 1 && batchCount.value <= 12 && Boolean(selectedWorkflow.value && selectedProvider.value && selectedModel.value) && !isSubmitting.value)

function showToast(message: string) {
  if (!import.meta.client) return
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2400)
}

async function loadQueue(showMessage = false) {
  if (queueRequestInFlight) return
  queueRequestInFlight = true
  lastUpdated.value = '刚刚'
  const requestProjectId = activeProjectId.value
  try {
    const response = await $fetch<{tasks: TaskResponse[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/generation-tasks`)
    if (activeProjectId.value !== requestProjectId) return
    queue.value = response.tasks.map(taskFromResponse)
    if (showMessage) showToast('任务队列已刷新')
  } catch (error: unknown) {
    if (activeProjectId.value === requestProjectId) showToast(requestError(error, '任务状态刷新失败，请重试'))
  } finally {
    queueRequestInFlight = false
  }
}

function refreshQueue() { void loadQueue(true) }

function requestError(error: unknown, fallback: string) {
  const request = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  return request.data?.statusMessage ?? request.data?.message ?? request.statusMessage ?? request.message ?? fallback
}

function formatDateTime(value: string | null | undefined) {
  if (!value) return '暂无'
  return value.replace('T', ' ').slice(0, 19)
}

function taskFromResponse(task: TaskResponse): QueueTask {
  return {id: task.id, name: task.workflowName, type: task.type, provider: `${task.provider.name} · ${task.provider.model}`, progress: task.progress, status: task.statusLabel, stage: task.stage, submittedAt: task.createdAt, generatedAt: task.completedAt ?? null, errorCode: task.errorCode ?? null, errorMessage: task.errorMessage ?? null}
}

function openTaskResult(task: QueueTask) {
  void navigateTo({
    path: `/projects/${encodeURIComponent(activeProjectId.value)}/results`,
    query: {task_id: String(task.id)},
  })
}

function selectProvider(providerId: number | null) {
  selectedProviderId.value = providerId
  selectedModelId.value = enabledProviders.value.find((provider) => provider.id === providerId)?.modelId ?? null
}

async function refreshProviderModels() {
  const provider = selectedProvider.value
  if (!provider || refreshingProviderModels.value) return
  refreshingProviderModels.value = true
  try {
    const response = await $fetch<{provider: Provider}>(`/api/providers/${provider.id}/models`, {method: 'POST'})
    const index = providers.value.findIndex((item) => item.id === provider.id)
    if (index >= 0) providers.value[index] = response.provider
    selectProvider(provider.id)
    showToast(`已更新 ${response.provider.models.length} 个模型`)
  } catch (error: unknown) {
    showToast(requestError(error, '模型列表刷新失败，请到 API 管理中检查连接配置'))
  } finally {
    refreshingProviderModels.value = false
  }
}

async function submitGeneration() {
  const workflow = selectedWorkflow.value
  const provider = selectedProvider.value
  const model = selectedModel.value
  if (!canSubmit.value || !workflow || !provider || !model) return
  isSubmitting.value = true
  const requestProjectId = activeProjectId.value
  const randomPart = typeof globalThis.crypto?.randomUUID === 'function'
    ? globalThis.crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`
  try {
    const response = await $fetch<{tasks: TaskResponse[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/generation-tasks`, {
      method: 'POST',
      headers: {'Idempotency-Key': `generation-${randomPart}`},
      body: {
        provider_id: provider.id,
        model_id: model.id,
        workflow_id: workflow.id,
        batch_count: batchCount.value,
        size: selectedResolution.value.size,
      },
    })
    if (activeProjectId.value !== requestProjectId) return
    const newTasks = response.tasks.map(taskFromResponse)
    queue.value = [...newTasks, ...queue.value]
    lastUpdated.value = '刚刚'
    showToast(`已提交 ${newTasks.length} 个生成任务，使用 ${provider.name} · ${model.name}`)
  } catch (error: unknown) {
    if (activeProjectId.value === requestProjectId) showToast(requestError(error, '生成任务保存失败，请重试'))
  } finally {
    isSubmitting.value = false
  }
}

async function cancelTask(task: QueueTask) {
  if (task.status === '已完成' || task.status === '已取消') return
  const requestProjectId = activeProjectId.value
  try {
    const response = await $fetch<{task: TaskResponse}>(`/api/projects/${encodeURIComponent(requestProjectId)}/generation-tasks/${task.id}/cancel`, {method: 'POST'})
    if (activeProjectId.value !== requestProjectId) return
    Object.assign(task, taskFromResponse(response.task))
    showToast(task.status === '取消中' ? `已发送任务 ${task.id} 的取消请求` : `已取消任务 ${task.id}`)
  } catch (error: unknown) {
    if (activeProjectId.value === requestProjectId) showToast(requestError(error, '任务取消失败，请重试'))
  }
}

function statusClass(status: TaskStatus) {
  return status === '生成中' ? 'status-running' : status === '已完成' ? 'status-complete' : status === '已取消' || status === '取消中' ? 'status-cancelled' : status === '生成失败' ? 'status-failed' : 'status-queued'
}

function startQueuePolling() {
  if (!import.meta.client || queuePollTimer) return
  queuePollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible' && queue.value.some((task) => ['排队中', '生成中', '等待重试', '取消中'].includes(task.status))) void loadQueue()
  }, 3000)
  document.addEventListener('visibilitychange', handleQueueVisibility)
}

function stopQueuePolling() {
  if (queuePollTimer) window.clearInterval(queuePollTimer)
  queuePollTimer = undefined
  document.removeEventListener('visibilitychange', handleQueueVisibility)
}

function handleQueueVisibility() {
  if (document.visibilityState === 'visible' && queue.value.some((task) => ['排队中', '生成中', '等待重试', '取消中'].includes(task.status))) void loadQueue()
}

const requestFetch = import.meta.server ? useRequestFetch() : $fetch
const initialLoadVersion = ++workspaceLoadVersion
const initialLoadProjectId = activeProjectId.value
try {
  const response = await requestFetch<{workflows: Workflow[]}>(`/api/projects/${encodeURIComponent(initialLoadProjectId)}/workflows`)
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) {
    workflows.value = response.workflows
    selectedWorkflowId.value = workflows.value[0]?.id ?? null
  }
} catch (error: unknown) {
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) workflowError.value = requestError(error, '工作流加载失败，请先在工作流模块保存工作流。')
} finally {
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) loadingWorkflows.value = false
}

try {
  const response = await requestFetch<{providers: Provider[]}>('/api/providers')
  providers.value = response.providers
  selectProvider(enabledProviders.value[0]?.id ?? null)
} catch (error: unknown) {
  const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  providerError.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '模型提供商加载失败，请重试。'
} finally {
  loadingProviders.value = false
}

try {
  const response = await requestFetch<{tasks: TaskResponse[]}>(`/api/projects/${encodeURIComponent(initialLoadProjectId)}/generation-tasks`)
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) queue.value = response.tasks.map(taskFromResponse)
} catch (error: unknown) {
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) showToast(requestError(error, '任务队列加载失败，请重试'))
}

watch(activeProjectId, (nextProjectId, previousProjectId) => {
  if (!previousProjectId || nextProjectId === previousProjectId) return
  const requestVersion = ++workspaceLoadVersion
  const requestProjectId = nextProjectId
  selectedWorkflowId.value = null
  queue.value = []
  workflowError.value = ''
  providerError.value = ''
  loadingWorkflows.value = true
  void (async () => {
    try {
      const [workflowResponse, taskResponse] = await Promise.all([
        $fetch<{workflows: Workflow[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/workflows`),
        $fetch<{tasks: TaskResponse[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/generation-tasks`),
      ])
      if (requestVersion !== workspaceLoadVersion || activeProjectId.value !== requestProjectId) return
      workflows.value = workflowResponse.workflows
      selectedWorkflowId.value = workflows.value[0]?.id ?? null
      queue.value = taskResponse.tasks.map(taskFromResponse)
    } catch (error: unknown) {
      if (requestVersion !== workspaceLoadVersion || activeProjectId.value !== requestProjectId) return
      workflowError.value = requestError(error, '工作流或任务队列加载失败，请重试')
    } finally {
      if (requestVersion === workspaceLoadVersion && activeProjectId.value === requestProjectId) loadingWorkflows.value = false
    }
  })()
})

onMounted(startQueuePolling)
onBeforeUnmount(stopQueuePolling)
</script>

<template>
  <div class="generation-layout">
    <StudioSidebar :project-id="activeProjectId" />

    <section class="generation-main">
      <StudioTopbar :project-id="activeProjectId">
        <span class="service-state"><i /> 生成服务由平台安全代理</span>
      </StudioTopbar>

      <main class="generation-content">
        <section class="generation-heading">
          <div><p class="eyebrow">GENERATION DESK</p><h1>生成任务</h1><span>使用真实推理服务提交图片或视频批次，不以占位结果冒充成功。</span></div>
        </section>

        <div class="generation-grid">
          <section class="launch-panel">
            <div class="panel-eyebrow">NEW BATCH</div>
            <h2>提交生成批次</h2>
            <label>工作流<select v-model="selectedWorkflowId" :disabled="loadingWorkflows || !workflows.length"><option v-if="loadingWorkflows" :value="null">正在加载已保存工作流…</option><option v-else-if="workflowError || !workflows.length" :value="null">暂无已保存工作流</option><option v-for="workflow in workflows" :key="workflow.id" :value="workflow.id">{{ workflow.name }} · {{ workflow.versionLabel }}</option></select></label>
            <p v-if="workflowError" class="provider-error" role="alert">{{ workflowError }}</p>
            <label>模型提供商<select :value="selectedProviderId ?? ''" :disabled="loadingProviders || !enabledProviders.length" @change="selectProvider(Number(($event.target as HTMLSelectElement).value) || null)"><option v-if="loadingProviders" value="">正在加载已启用提供商…</option><option v-else-if="!enabledProviders.length" value="">暂无已启用提供商</option><option v-for="provider in enabledProviders" :key="provider.id" :value="provider.id">{{ provider.name }} · {{ provider.type }}</option></select></label>
            <label>使用模型<select v-model="selectedModelId" :disabled="!selectedProvider || !selectedProvider.models.length"><option v-if="!selectedProvider" :value="null">请先选择提供商</option><option v-else-if="!selectedProvider.models.length" :value="null">暂无已保存模型</option><option v-for="model in (selectedProvider?.models ?? [])" :key="model.id" :value="model.id">{{ model.name }} · ID {{ model.id }}</option></select></label>
            <button class="model-refresh" type="button" :disabled="!selectedProvider || refreshingProviderModels" @click="refreshProviderModels">{{ refreshingProviderModels ? '正在刷新模型…' : '刷新当前提供商模型' }}</button>
            <p v-if="providerError" class="provider-error" role="alert">{{ providerError }}</p>
            <label>批量数量<input v-model.number="batchCount" type="number" min="1" max="12" step="1"><small class="form-hint">请输入 1-12 的整数；每个任务都会独立生成一张图片。</small></label>
            <label>输出分辨率<select v-model="resolution"><option v-for="option in resolutionOptions" :key="option.value" :value="option.value">{{ option.label }} · {{ option.hint }}</option></select><small class="form-hint">请求会按 OpenAI Images API 的 size 字段发送：1K=1024×1024，2K=2048×2048，4K=3840×2160。</small></label>
            <div class="provider-note" :class="{ unavailable: !selectedProvider || !selectedModel }"><i /><div><strong>{{ selectedProvider ? selectedProvider.name : '没有可用的模型提供商' }}</strong><span>{{ selectedProvider && selectedModel ? `${selectedProvider.type} · ${selectedModel.name} · 模型 ID ${selectedModel.id} · 已启用` : '请先选择已启用提供商和模型' }}</span></div></div>
            <button class="launch-button" type="button" :disabled="!canSubmit" @click="submitGeneration">{{ isSubmitting ? '正在提交…' : '提交真实生成' }}</button>
            <small>{{ selectedWorkflow && selectedProvider && selectedModel ? '任务将使用所选工作流的数据库版本、提示词和参考图要求。' : '请先选择已保存工作流、启用提供商并选择模型。' }}</small>
          </section>

          <section class="queue-panel">
            <div class="queue-header-row"><div><p class="panel-eyebrow">LIVE QUEUE</p><h2>任务队列</h2></div><button class="refresh-button" type="button" @click="refreshQueue">刷新 <span>{{ lastUpdated }}</span></button></div>
            <div class="queue-table" role="table" aria-label="任务队列">
              <div class="queue-header" role="row"><span>任务</span><span>类型</span><span>进度</span><span>状态</span><span>提交时间</span><span>生成时间</span><span>操作</span></div>
              <div v-for="task in queue" :key="task.id" class="queue-record" role="row">
                <button class="queue-task-link" type="button" @click="openTaskResult(task)"><strong>{{ task.name }}</strong><small>{{ task.id }} · {{ task.provider }} · {{ task.stage }}</small><small v-if="task.errorMessage" class="queue-error"><b>{{ task.errorCode || 'ERROR' }}</b> {{ task.errorMessage }}</small></button>
                <span>{{ task.type }}</span>
                <span class="progress-cell"><span class="progress-track"><i :style="{ width: `${task.progress}%` }" /></span><small>{{ task.progress }}%</small></span>
                <span><em :class="statusClass(task.status)">{{ task.status }}</em></span>
                <time class="time-cell" :datetime="task.submittedAt" :title="task.submittedAt">{{ formatDateTime(task.submittedAt) }}</time>
                <time class="time-cell" :datetime="task.generatedAt || undefined" :title="task.generatedAt || '尚未生成完成'">{{ formatDateTime(task.generatedAt) }}</time>
                <button type="button" :disabled="['已完成', '已取消', '生成失败'].includes(task.status)" @click="cancelTask(task)">{{ task.status === '已取消' ? '已取消' : task.status === '已完成' ? '完成' : task.status === '生成失败' ? '失败' : '取消' }}</button>
              </div>
              <p v-if="!queue.length" class="empty-queue">当前没有生成任务</p>
            </div>
          </section>
        </div>
      </main>
    </section>

    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
.form-hint { display: block; margin: -2px 0 0; color: #8d867e; font-size: 8px; font-weight: 400; line-height: 1.45; }
.model-refresh { width: 100%; margin-top: 9px; padding: 8px 10px; color: #6e665d; background: #faf8f4; border: 1px solid #e2dbd2; border-radius: 7px; font-size: 8px; }.model-refresh:disabled { cursor: wait; opacity: .55; }
:root { --ink: #24221f; --muted: #7d776f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; }
html, body, #__nuxt { min-height: 100%; margin: 0; }
body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; }
button, input, select { font: inherit; }
button { cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .5; }
.generation-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }
.generation-main { min-width: 0; }
.generation-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.generation-topbar > div { display: flex; flex-direction: column; gap: 2px; }.generation-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.generation-topbar strong { font: 500 12px Georgia, serif; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }
.generation-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.generation-heading { margin-bottom: 32px; }.eyebrow, .panel-eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.generation-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.generation-heading > div > span { color: #817b73; font-size: 12px; }
.generation-grid { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; gap: 17px; }.launch-panel, .queue-panel { padding: 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.launch-panel h2, .queue-panel h2 { margin: 5px 0; font: 400 24px Georgia, serif; }.launch-panel label { display: flex; flex-direction: column; gap: 8px; margin-top: 18px; color: #59554f; font-size: 10px; }.launch-panel label input, .launch-panel label select { width: 100%; padding: 12px 13px; color: #26231f; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 10px; }.launch-panel label input:focus, .launch-panel label select:focus { border-color: #9d8766; box-shadow: 0 0 0 3px #9d87661a; }.launch-panel label select:disabled { color: #958e85; background: #f4f1ec; }.provider-error { margin: 8px 0 -7px; color: #9b6254; font-size: 8px; line-height: 1.5; }.provider-note { display: flex; align-items: center; gap: 9px; margin-top: 18px; padding: 12px; background: #edf2ed; border-radius: 9px; }.provider-note.unavailable { background: #f8ece7; }.provider-note > i { flex: 0 0 auto; width: 9px; height: 9px; background: #78907a; border-radius: 50%; }.provider-note.unavailable > i { background: #b37867; }.provider-note div { display: flex; flex-direction: column; gap: 3px; }.provider-note strong { font-size: 9px; }.provider-note span { color: #718073; font-size: 8px; }.provider-note.unavailable span { color: #9b6254; }.launch-button { display: flex; align-items: center; justify-content: center; width: 100%; margin-top: 15px; padding: 12px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.launch-button:hover { background: #37342f; }.launch-panel > small { display: block; margin-top: 9px; color: #8d867e; font-size: 8px; line-height: 1.5; }
.queue-header-row { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 16px; }.queue-header-row h2 { margin-bottom: 0; }.refresh-button { padding: 0; color: #6e665d; background: transparent; border: 0; font-size: 9px; }.refresh-button span { margin-left: 6px; color: #aaa198; font-size: 8px; }.queue-table { font-size: 9px; }.queue-header, .queue-record { display: grid; grid-template-columns: minmax(130px, 1.25fr) 45px minmax(95px, .8fr) 65px 130px 130px 42px; align-items: center; gap: 10px; padding: 11px 0; border-top: 1px solid #eeeae3; }.queue-header { color: #918a81; font-size: 8px; }.queue-record strong { font-size: 9px; }.queue-record small { color: #958e85; font-size: 7px; }.queue-task-link { display: flex; min-width: 0; flex-direction: column; gap: 3px; padding: 0; color: inherit; background: transparent; border: 0; text-align: left; }.queue-task-link:hover strong, .queue-task-link:focus-visible strong { color: #8b684f; text-decoration: underline; text-underline-offset: 3px; }.queue-error { max-width: 100%; color: #a25749 !important; line-height: 1.45; overflow-wrap: anywhere; white-space: pre-wrap; }.queue-error b { margin-right: 4px; font-weight: 700; }.progress-cell { display: flex; align-items: center; gap: 5px; }.progress-track { flex: 1; height: 3px; background: #ede8e0; }.progress-track i { display: block; height: 100%; background: #8e7758; }.progress-cell > small { flex: 0 0 20px; color: #928a80; }.time-cell { color: #807970; font-size: 8px; white-space: nowrap; }.queue-record em { display: inline-block; padding: 5px 8px; color: #746e66; background: #ece9e4; border-radius: 12px; font-size: 7px; font-style: normal; line-height: 1; text-align: center; }.queue-record em.status-running { color: #537059; background: #e6eee7; }.queue-record em.status-complete { color: #537059; background: #e6eee7; }.queue-record em.status-cancelled { color: #746e66; background: #ece9e4; }.queue-record em.status-failed { color: #9b6254; background: #f8ece7; }.queue-record > button:not(.queue-task-link) { padding: 0; color: #8b684f; background: transparent; border: 0; font-size: 8px; }.empty-queue { padding: 30px 0 10px; color: #958e85; text-align: center; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 900px) { .generation-grid { grid-template-columns: 1fr; }.launch-panel { max-width: none; }.queue-panel { min-width: 0; } }
@media (max-width: 800px) { .generation-layout { display: block; }.generation-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.generation-content { padding: 30px 16px 55px; }.queue-panel { overflow-x: auto; }.queue-table { min-width: 790px; } }
</style>
