<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

definePageMeta({ layout: false })

type ResultStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
type ResultItem = {
  id: number
  taskId: number | null
  workflow: string
  version: number
  workflowVersion: string
  media: 'IMAGE' | 'VIDEO'
  status: ResultStatus
  reviewStatus: ResultStatus
  generationStatus: 'GENERATING' | 'READY' | 'FAILED' | 'CANCELLED'
  progress: number
  stage: string
  prompt: string
  uri: string | null
  file: {id: number; downloadUrl: string; originalName: string; contentType: string} | null
  errorMessage: string | null
  createdAt: string
}

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const { activeProjectId } = useProjectWorkspace(projectId)
const filter = ref<'ALL' | ResultStatus>('ALL')
const selectedIds = ref<number[]>([])
const activeResult = ref<ResultItem | null>(null)
const toast = ref('')
const results = ref<ResultItem[]>([])
const loading = ref(true)
const loadError = ref('')
const reviewSaving = ref(false)
const openedTaskId = ref<number | null>(null)
const downloadDialogOpen = ref(false)
const downloading = ref(false)
const downloadResultIds = ref<number[] | null>(null)
let resultsLoadVersion = 0
let resultsPollTimer: number | undefined
let resultsRequestInFlight = false

const shownResults = computed(() => filter.value === 'ALL' ? results.value : results.value.filter((result) => result.status === filter.value))
const statusLabel = (status: ResultStatus) => ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已驳回' })[status]
const selectedResults = computed(() => selectedIds.value.map((id) => results.value.find((result) => result.id === id)).filter(Boolean) as ResultItem[])
const downloadableResults = computed(() => {
  if (downloadResultIds.value) {
    const resultIds = new Set(downloadResultIds.value)
    return results.value.filter((result) => resultIds.has(result.id) && result.generationStatus === 'READY' && result.file)
  }
  const selected = selectedResults.value.filter((result) => result.generationStatus === 'READY' && result.file)
  if (selected.length) return selected
  const taskId = typeof route.query.task_id === 'string' ? Number(route.query.task_id) : NaN
  const taskResult = results.value.find((result) => result.taskId === taskId && result.generationStatus === 'READY' && result.file)
  if (taskResult) return [taskResult]
  return shownResults.value.filter((result) => result.generationStatus === 'READY' && result.file)
})
const paletteFor = (result: ResultItem) => ['cream', 'stone', 'charcoal', 'sand'][result.id % 4]

function requestError(error: unknown, fallback: string) {
  const request = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  return request.data?.statusMessage ?? request.data?.message ?? request.statusMessage ?? request.message ?? fallback
}

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2400)
}

function applyResults(nextResults: ResultItem[]) {
  results.value = nextResults
  if (activeResult.value) {
    activeResult.value = nextResults.find((result) => result.id === activeResult.value?.id) ?? null
  }
  openResultFromTaskQuery()
}

async function loadResults() {
  if (resultsRequestInFlight) return
  resultsRequestInFlight = true
  const requestProjectId = activeProjectId.value
  try {
    const response = await $fetch<{results: ResultItem[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/results`)
    if (activeProjectId.value === requestProjectId) {
      applyResults(response.results)
    }
  } catch (error: unknown) {
    if (activeProjectId.value === requestProjectId) loadError.value = requestError(error, '结果加载失败，请重试')
  } finally {
    resultsRequestInFlight = false
  }
}

function startResultsPolling() {
  if (!import.meta.client || resultsPollTimer) return
  resultsPollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible' && results.value.some((result) => result.generationStatus === 'GENERATING')) void loadResults()
  }, 3000)
  document.addEventListener('visibilitychange', handleResultsVisibility)
}

function stopResultsPolling() {
  if (resultsPollTimer) window.clearInterval(resultsPollTimer)
  resultsPollTimer = undefined
  document.removeEventListener('visibilitychange', handleResultsVisibility)
}

function handleResultsVisibility() {
  if (document.visibilityState === 'visible' && results.value.some((result) => result.generationStatus === 'GENERATING')) void loadResults()
}

function toggleSelected(id: number) {
  selectedIds.value = selectedIds.value.includes(id)
    ? selectedIds.value.filter((selectedId) => selectedId !== id)
    : selectedIds.value.length < 2 ? [...selectedIds.value, id] : [selectedIds.value.at(-1)!, id]
}

function openResultFromTaskQuery() {
  const rawTaskId = route.query.task_id
  const taskId = typeof rawTaskId === 'string' ? Number(rawTaskId) : NaN
  if (!Number.isSafeInteger(taskId) || taskId <= 0 || openedTaskId.value === taskId) return
  const result = results.value.find((item) => item.taskId === taskId)
  if (!result) return
  openedTaskId.value = taskId
  filter.value = 'ALL'
  activeResult.value = result
}

function exportPackage() {
  downloadResultIds.value = null
  if (!downloadableResults.value.length) {
    showToast('当前没有可下载的已完成结果')
    return
  }
  downloadDialogOpen.value = true
}

function exportTaskResults(result: ResultItem) {
  downloadResultIds.value = results.value
    .filter((item) => item.taskId === result.taskId && item.generationStatus === 'READY' && item.file)
    .map((item) => item.id)
  if (!downloadableResults.value.length) {
    downloadResultIds.value = null
    showToast('当前任务尚无可下载的已完成结果')
    return
  }
  downloadDialogOpen.value = true
}

function triggerBrowserDownload(url: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = ''
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
}

function closeDownloadDialog() {
  downloadDialogOpen.value = false
  downloadResultIds.value = null
}

function downloadAsZip() {
  if (downloading.value) return
  downloading.value = true
  const count = downloadableResults.value.length
  const query = new URLSearchParams()
  downloadableResults.value.forEach((result) => query.append('result_id', String(result.id)))
  triggerBrowserDownload(`/api/projects/${encodeURIComponent(activeProjectId.value)}/results/download?${query.toString()}`)
  closeDownloadDialog()
  downloading.value = false
  showToast(`正在打包下载 ${count} 个结果`)
}

function downloadIndividually() {
  if (downloading.value) return
  downloading.value = true
  const items = downloadableResults.value
  closeDownloadDialog()
  items.forEach((result) => triggerBrowserDownload(`${result.file!.downloadUrl}?download=1`))
  downloading.value = false
  showToast(`已发起 ${items.length} 个图片下载请求`)
}

async function updateReview(result: ResultItem, status: ResultStatus) {
  if (reviewSaving.value) return
  reviewSaving.value = true
  const requestProjectId = activeProjectId.value
  try {
    const response = await $fetch<{result: ResultItem}>(`/api/projects/${encodeURIComponent(requestProjectId)}/results/${result.id}`, {method: 'PATCH', body: {status}})
    if (activeProjectId.value !== requestProjectId) return
    Object.assign(result, response.result)
    activeResult.value = null
    showToast(status === 'APPROVED' ? '审核记录已保存：已通过' : '审核记录已保存：已驳回')
  } catch (error: unknown) {
    if (activeProjectId.value === requestProjectId) showToast(requestError(error, '审核记录保存失败，请重试'))
  } finally {
    reviewSaving.value = false
  }
}

const requestFetch = import.meta.server ? useRequestFetch() : $fetch
const initialLoadVersion = ++resultsLoadVersion
const initialLoadProjectId = activeProjectId.value
try {
  const response = await requestFetch<{results: ResultItem[]}>(`/api/projects/${encodeURIComponent(initialLoadProjectId)}/results`)
  if (initialLoadVersion === resultsLoadVersion && activeProjectId.value === initialLoadProjectId) {
    applyResults(response.results)
  }
} catch (error: unknown) {
  if (initialLoadVersion === resultsLoadVersion && activeProjectId.value === initialLoadProjectId) loadError.value = requestError(error, '结果加载失败，请重试')
} finally {
  if (initialLoadVersion === resultsLoadVersion && activeProjectId.value === initialLoadProjectId) loading.value = false
}

watch(activeProjectId, (nextProjectId, previousProjectId) => {
  if (!previousProjectId || nextProjectId === previousProjectId) return
  const requestVersion = ++resultsLoadVersion
  const requestProjectId = nextProjectId
  selectedIds.value = []
  activeResult.value = null
  loadError.value = ''
  loading.value = true
  void (async () => {
    try {
      const response = await $fetch<{results: ResultItem[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/results`)
      if (requestVersion !== resultsLoadVersion || activeProjectId.value !== requestProjectId) return
      openedTaskId.value = null
      applyResults(response.results)
    } catch (error: unknown) {
      if (requestVersion !== resultsLoadVersion || activeProjectId.value !== requestProjectId) return
      loadError.value = requestError(error, '结果加载失败，请重试')
    } finally {
      if (requestVersion === resultsLoadVersion && activeProjectId.value === requestProjectId) loading.value = false
    }
  })()
})

watch(() => route.query.task_id, () => {
  openedTaskId.value = null
  openResultFromTaskQuery()
})

onMounted(startResultsPolling)
onBeforeUnmount(stopResultsPolling)
</script>

<template>
  <div class="results-layout">
    <StudioSidebar :project-id="activeProjectId" />
    <section class="results-main">
      <StudioTopbar :project-id="activeProjectId"><span class="service-state"><i /> 生成服务由平台安全代理</span></StudioTopbar>
      <main class="results-content">
        <section class="results-heading"><div><p class="eyebrow">GENERATED RESULTS</p><h1>结果中心</h1><span>对比不同版本，挑选成片并导出生产资料。</span></div><button class="dark-button" type="button" @click="exportPackage">下载结果</button></section>
        <div class="result-toolbar"><div class="filter-tabs"><button v-for="item in [{ value: 'ALL', label: '全部' }, { value: 'PENDING', label: '待审核' }, { value: 'APPROVED', label: '已通过' }, { value: 'REJECTED', label: '已驳回' }]" :key="item.value" type="button" :class="{ active: filter === item.value }" @click="filter = item.value as typeof filter">{{ item.label }}</button></div><span>已选 {{ selectedIds.length }}/2 · 选择两项进行并排对比</span></div>

        <p v-if="loadError" class="load-error" role="alert">{{ loadError }}</p>
        <p v-else-if="loading" class="loading-state">正在从数据库读取生成结果…</p>
        <section v-if="selectedResults.length === 2" class="compare-panel"><div v-for="result in selectedResults" :key="result.id" class="compare-item"><div v-if="result.file" class="result-art"><img :src="result.file.downloadUrl" :alt="result.workflow"></div><div v-else class="result-art" :class="`palette-${paletteFor(result)}`"><span>{{ result.media === 'VIDEO' ? 'VIDEO PREVIEW' : 'IMAGE PREVIEW' }}</span><i /></div><small>{{ result.workflow }} · V{{ result.version }}</small></div></section>

        <section v-if="shownResults.length" class="result-grid"><article v-for="result in shownResults" :key="result.id" class="result-card" :class="{ selected: selectedIds.includes(result.id) }"><button class="result-media" type="button" @click="toggleSelected(result.id)"><div v-if="result.generationStatus === 'READY' && result.file" class="result-art"><img :src="result.file.downloadUrl" :alt="result.workflow"></div><div v-else class="result-art" :class="`palette-${paletteFor(result)}`"><span>{{ result.generationStatus === 'FAILED' ? 'FAILED' : result.generationStatus === 'CANCELLED' ? 'CANCELLED' : `${result.stage} · ${result.progress}%` }}</span><i /></div><em class="review-badge" :class="result.status.toLowerCase()">{{ result.generationStatus === 'READY' ? statusLabel(result.status) : result.generationStatus === 'FAILED' ? '生成失败' : result.generationStatus === 'CANCELLED' ? '已取消' : '生成中' }}</em></button><div class="result-info"><small>{{ result.media }} · {{ result.workflowVersion || `V${result.version}` }}</small><h3>{{ result.workflow }}</h3><p>{{ result.generationStatus === 'FAILED' ? (result.errorMessage || '生成失败') : result.prompt || '该结果暂无提示词记录。' }}</p><div><button type="button" @click="activeResult = result">查看详情</button><button type="button" :disabled="result.generationStatus !== 'READY'" @click="toggleSelected(result.id)">{{ selectedIds.includes(result.id) ? '取消选择' : '选择对比' }}</button></div></div></article></section>
        <section v-else class="empty-state"><strong>当前没有结果</strong><span>真实生成完成后会自动进入这里。</span></section>
      </main>
    </section>

    <div v-if="downloadDialogOpen" class="modal-backdrop download-backdrop" @click.self="closeDownloadDialog"><section class="download-dialog" role="dialog" aria-modal="true" aria-labelledby="download-dialog-title"><div class="modal-head"><div><p class="eyebrow">DOWNLOAD RESULTS</p><h2 id="download-dialog-title">选择下载方式</h2></div><button class="close-button" type="button" aria-label="关闭" @click="closeDownloadDialog">×</button></div><p>将下载 {{ downloadableResults.length }} 个已完成的生成结果。</p><div class="download-options"><button type="button" :disabled="downloading" @click="downloadAsZip"><strong>打包为 ZIP 压缩包</strong><span>由服务器打包后发起一次下载，适合多个结果。</span></button><button type="button" :disabled="downloading" @click="downloadIndividually"><strong>直接下载图片</strong><span>为每个结果分别发起一次下载请求，浏览器可能询问是否允许多个文件。</span></button></div><button class="download-cancel" type="button" :disabled="downloading" @click="closeDownloadDialog">取消</button></section></div>
    <div v-if="activeResult" class="modal-backdrop" @click.self="activeResult = null"><section class="result-modal"><div class="modal-head"><div><p class="eyebrow">RESULT DETAIL</p><h2>{{ activeResult.workflow }}</h2></div><button class="close-button" type="button" aria-label="关闭" @click="activeResult = null">×</button></div><div v-if="activeResult.generationStatus === 'READY' && activeResult.file" class="modal-art has-image"><img :src="activeResult.file.downloadUrl" :alt="activeResult.workflow"></div><div v-else class="modal-art" :class="`palette-${paletteFor(activeResult)}`"><i /><span>{{ activeResult.stage }} · {{ activeResult.progress }}%</span></div><dl><div><dt>任务 ID</dt><dd>{{ activeResult.taskId ?? '无关联任务' }}</dd></div><div><dt>生成状态</dt><dd>{{ activeResult.generationStatus === 'READY' ? '已完成' : activeResult.generationStatus === 'FAILED' ? '生成失败' : activeResult.generationStatus === 'CANCELLED' ? '已取消' : `${activeResult.stage} · ${activeResult.progress}%` }}</dd></div><div><dt>审核状态</dt><dd>{{ statusLabel(activeResult.status) }}</dd></div><div><dt>工作流版本</dt><dd>{{ activeResult.workflow }} · {{ activeResult.workflowVersion || `V${activeResult.version}` }}</dd></div><div><dt>生成类型</dt><dd>{{ activeResult.media === 'VIDEO' ? '视频' : '图片' }}</dd></div><div><dt>提示词</dt><dd>{{ activeResult.prompt || '暂无提示词记录。' }}</dd></div><div v-if="activeResult.errorMessage" class="error-detail"><dt>原始错误响应</dt><dd>{{ activeResult.errorMessage }}</dd></div></dl><div class="modal-actions"><button class="quiet-button" type="button" :disabled="reviewSaving || activeResult.generationStatus !== 'READY' || !activeResult.file" @click="exportTaskResults(activeResult!)">下载任务结果</button><button class="quiet-button" type="button" :disabled="reviewSaving || activeResult.generationStatus !== 'READY'" @click="updateReview(activeResult!, 'REJECTED')">驳回</button><button class="dark-button" type="button" :disabled="reviewSaving || activeResult.generationStatus !== 'READY'" @click="updateReview(activeResult!, 'APPROVED')">通过并记录</button></div></section></div>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button { font: inherit; cursor: pointer; } button:disabled { cursor: not-allowed; opacity: .5; }
.results-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.results-main { min-width: 0; }.results-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.results-topbar > div { display: flex; flex-direction: column; gap: 2px; }.results-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.results-topbar strong { font: 500 12px Georgia, serif; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }
.results-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.results-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 31px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.results-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.results-heading > div > span { color: #817b73; font-size: 12px; }.dark-button, .quiet-button { display: flex; align-items: center; gap: 7px; min-height: 42px; padding: 10px 14px; border-radius: 8px; font-size: 10px; }.dark-button { color: #fff; background: #1d1c19; border: 0; }.quiet-button { color: #5d5750; background: #fff; border: 1px solid var(--line); }
.result-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 20px; }.filter-tabs { display: flex; gap: 4px; padding: 4px; background: #ede9e2; border-radius: 9px; }.filter-tabs button { padding: 8px 13px; color: #746e66; background: transparent; border: 0; border-radius: 7px; font-size: 10px; }.filter-tabs button.active { color: #24211e; background: #fff; box-shadow: 0 2px 8px #322a2212; }.result-toolbar > span { color: #878078; font-size: 9px; }
.loading-state, .load-error { margin: 0 0 20px; padding: 12px 14px; color: #817b73; background: #f0ece6; border-radius: 8px; font-size: 9px; }.load-error { color: #9b6254; background: #fff4f1; }
.result-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 15px; }.result-card { overflow: hidden; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.result-card.selected { outline: 2px solid #9a825f; outline-offset: 2px; }.result-media { position: relative; display: block; width: 100%; aspect-ratio: 1; overflow: hidden; padding: 0; background: #ddd4c8; border: 0; }.result-art { position: relative; display: grid; place-items: center; width: 100%; height: 100%; overflow: hidden; }.result-art > span { position: absolute; top: 11px; left: 11px; z-index: 1; color: #665f57; font-size: 7px; letter-spacing: .13em; }.result-art > i { display: block; width: 88px; height: 165px; background: #fff9; border-radius: 43% 43% 14% 14%; clip-path: polygon(27% 0, 73% 0, 87% 25%, 100% 100%, 0 100%, 13% 25%); box-shadow: 0 20px 25px #42372b2b; }.result-art > b { position: absolute; right: 13px; bottom: 12px; display: grid; place-items: center; width: 24px; height: 24px; color: #fff; background: #292722cc; border-radius: 50%; font-size: 9px; }.palette-cream { background: linear-gradient(135deg, #d8cfc3, #f5f0e9); }.palette-stone { background: linear-gradient(135deg, #b7b6b1, #e5e1d8); }.palette-charcoal { background: linear-gradient(135deg, #302e2b, #827b73); }.palette-charcoal .result-art > i { background: #25221fdd; }.palette-sand { background: linear-gradient(135deg, #d2c4b4, #f1e9df); }.review-badge { position: absolute; top: 10px; right: 10px; z-index: 2; padding: 5px 8px; color: #746e66; background: #ffffffd9; border-radius: 20px; font-size: 7px; font-style: normal; }.review-badge.pending { color: #8a6947; }.review-badge.approved { color: #537059; }.review-badge.rejected { color: #945b52; }.result-info { padding: 13px; }.result-info > small { color: #9b805b; font-size: 7px; }.result-info h3 { margin: 5px 0; font: 400 13px Georgia, serif; }.result-info p { height: 24px; overflow: hidden; margin: 0; color: #898279; font-size: 8px; line-height: 1.4; }.result-info > div { display: flex; justify-content: space-between; margin-top: 10px; }.result-info button { padding: 0; color: #5f5952; background: transparent; border: 0; font-size: 8px; }
.result-art img { display: block; width: 100%; height: 100%; object-fit: contain; }.modal-art img { display: block; width: auto; max-width: 100%; height: auto; max-height: min(68vh, 720px); object-fit: contain; }
.compare-panel { display: grid; grid-template-columns: 1fr 1fr; gap: 2px; margin-bottom: 20px; padding: 12px; background: #2a2824; border-radius: 12px; }.compare-item { position: relative; height: 320px; overflow: hidden; background: #171613; }.compare-item .result-art { height: 100%; }.compare-item > small { position: absolute; left: 9px; bottom: 9px; padding: 5px 7px; color: #322e29; background: #ffffffd9; font-size: 7px; }.empty-state { display: flex; flex-direction: column; align-items: center; gap: 9px; padding: 70px 20px; color: #898279; border: 1px dashed #d3ccc2; border-radius: 12px; text-align: center; }.empty-state strong { color: #4b4640; font: 400 17px Georgia, serif; }.empty-state span { font-size: 10px; }
.modal-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 20px; background: #1d1b1894; backdrop-filter: blur(4px); }.result-modal { width: min(900px, 100%); max-height: 92vh; overflow: auto; padding: 25px; background: #f8f6f1; border-radius: 14px; box-shadow: 0 30px 90px #00000040; }.modal-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 18px; }.modal-head h2 { margin: 5px 0; font: 400 25px Georgia, serif; }.close-button { color: #766f67; background: transparent; border: 0; font-size: 24px; }.modal-art { height: 280px; overflow: hidden; border-radius: 8px; }.modal-art.has-image { display: grid; place-items: center; width: 100%; height: auto; min-height: 220px; max-height: min(68vh, 720px); background: #eeeae3; }.modal-art > i { display: block; width: 130px; height: 230px; margin: 25px auto; background: #fff9; border-radius: 43% 43% 14% 14%; clip-path: polygon(27% 0, 73% 0, 87% 25%, 100% 100%, 0 100%, 13% 25%); }.modal-art > span { position: relative; top: -55px; left: 14px; padding: 5px 7px; color: #5b554e; background: #ffffffd9; font-size: 7px; }.result-modal dl { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin: 17px 0; }.result-modal dl div { padding: 9px; background: #f0ece6; border-radius: 7px; }.result-modal dt { color: #8c857c; font-size: 7px; }.result-modal dd { margin: 4px 0 0; word-break: break-word; font-size: 9px; }.modal-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 15px; border-top: 1px solid #ddd7ce; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 110; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 1150px) { .result-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 800px) { .results-layout { display: block; }.results-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.results-content { padding: 30px 16px 55px; }.results-heading { align-items: flex-start; flex-direction: column; }.results-heading .dark-button { width: 100%; justify-content: center; }.result-toolbar { align-items: stretch; flex-direction: column; }.filter-tabs { overflow-x: auto; }.result-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.compare-panel { grid-template-columns: 1fr; }.compare-item { height: 260px; } }
@media (max-width: 500px) { .result-grid { grid-template-columns: 1fr; }.result-modal { padding: 19px; }.result-modal dl { grid-template-columns: 1fr; } }
.result-modal dl .error-detail { grid-column: 1 / -1; background: #fff0ec; }.result-modal dl .error-detail dt { color: #9b6254; }.result-modal dl .error-detail dd { color: #7d493f; white-space: pre-wrap; }
.download-dialog { width: min(520px, 100%); padding: 25px; background: #f8f6f1; border-radius: 12px; box-shadow: 0 30px 90px #00000040; }.download-dialog h2 { margin: 5px 0; font: 400 25px Georgia, serif; }.download-dialog > p { margin: 0 0 18px; color: #7d776f; font-size: 10px; line-height: 1.5; }.download-options { display: grid; gap: 10px; }.download-options button { display: flex; flex-direction: column; gap: 5px; width: 100%; padding: 15px; color: #292722; background: #fff; border: 1px solid #ded8cf; border-radius: 8px; text-align: left; }.download-options button:hover:not(:disabled), .download-options button:focus-visible { border-color: #9d8766; box-shadow: 0 0 0 3px #9d87661a; }.download-options strong { font-size: 11px; }.download-options span { color: #817b73; font-size: 9px; line-height: 1.45; }.download-cancel { display: block; margin: 18px 0 0 auto; padding: 7px 4px; color: #6e665d; background: transparent; border: 0; font-size: 9px; }
.download-backdrop { z-index: 120; }
</style>
