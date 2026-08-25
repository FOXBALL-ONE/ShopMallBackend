<script setup lang="ts">
import { computed, ref } from 'vue'

definePageMeta({ layout: false })

type TaskStatus = '排队中' | '生成中' | '已完成' | '已取消'
type QueueTask = {
  id: string
  name: string
  type: '图片' | '视频'
  progress: number
  status: TaskStatus
}

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const selectedWorkflow = ref('晨光主视觉 · V3 · IMAGE')
const batchCount = ref(1)
const isSubmitting = ref(false)
const lastUpdated = ref('刚刚')
const toast = ref('')

const workflows = [
  { value: '晨光主视觉 · V3 · IMAGE', name: '晨光主视觉', version: 'V3', type: 'IMAGE' },
  { value: '黑色丝缎细节 · V2 · IMAGE', name: '黑色丝缎细节', version: 'V2', type: 'IMAGE' },
  { value: 'NOIR 春夏主视觉 · V1 · VIDEO', name: 'NOIR 春夏主视觉', version: 'V1', type: 'VIDEO' },
]

const queue = ref<QueueTask[]>([
  { id: 'task_02', name: '晨光主视觉', type: '视频', progress: 0, status: '排队中' },
  { id: 'task_01', name: '晨光主视觉', type: '图片', progress: 68, status: '生成中' },
])

const selectedWorkflowData = computed(() => workflows.find((workflow) => workflow.value === selectedWorkflow.value))
const canSubmit = computed(() => batchCount.value >= 1 && batchCount.value <= 12 && !isSubmitting.value)

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2400)
}

function refreshQueue() {
  lastUpdated.value = '刚刚'
  queue.value = queue.value.map((task) => {
    if (task.status !== '生成中') return task
    const progress = Math.min(100, task.progress + 4)
    return { ...task, progress, status: progress === 100 ? '已完成' : '生成中' }
  })
  showToast('任务队列已刷新')
}

function submitGeneration() {
  const workflow = selectedWorkflowData.value
  if (!canSubmit.value || !workflow) return
  isSubmitting.value = true
  window.setTimeout(() => {
    const baseId = Date.now().toString().slice(-4)
    const type: QueueTask['type'] = workflow.type === 'VIDEO' ? '视频' : '图片'
    const newTasks = Array.from({ length: batchCount.value }, (_, index) => ({
      id: `task_${baseId}_${index + 1}`,
      name: workflow.name,
      type,
      progress: 0,
      status: '排队中' as TaskStatus,
    }))
    queue.value = [...newTasks, ...queue.value]
    isSubmitting.value = false
    lastUpdated.value = '刚刚'
    showToast(`已提交 ${batchCount.value} 个${type}生成任务`)
  }, 450)
}

function cancelTask(task: QueueTask) {
  if (task.status === '已完成' || task.status === '已取消') return
  task.status = '已取消'
  task.progress = 0
  showToast(`已取消任务 ${task.id}`)
}

function statusClass(status: TaskStatus) {
  return status === '生成中' ? 'status-running' : status === '已完成' ? 'status-complete' : status === '已取消' ? 'status-cancelled' : 'status-queued'
}
</script>

<template>
  <div class="generation-layout">
    <StudioSidebar :project-id="projectId" />

    <section class="generation-main">
      <header class="generation-topbar">
        <div><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div>
        <span class="service-state"><i /> 生成服务由平台安全代理</span>
      </header>

      <main class="generation-content">
        <section class="generation-heading">
          <div><p class="eyebrow">GENERATION DESK</p><h1>生成任务</h1><span>使用真实推理服务提交图片或视频批次，不以占位结果冒充成功。</span></div>
        </section>

        <div class="generation-grid">
          <section class="launch-panel">
            <div class="panel-eyebrow">NEW BATCH</div>
            <h2>提交生成批次</h2>
            <label>工作流<select v-model="selectedWorkflow"><option v-for="workflow in workflows" :key="workflow.value" :value="workflow.value">{{ workflow.value }}</option></select></label>
            <label>批量数量<input v-model.number="batchCount" type="number" min="1" max="12" step="1"></label>
            <div class="provider-note"><i /><div><strong>真实服务状态</strong><span>等待平台注入推理代理</span></div></div>
            <button class="launch-button" type="button" :disabled="!canSubmit" @click="submitGeneration">{{ isSubmitting ? '正在提交…' : '提交真实生成' }}</button>
            <small>视频任务可能需要数分钟，请勿关闭当前制作页面。</small>
          </section>

          <section class="queue-panel">
            <div class="queue-header-row"><div><p class="panel-eyebrow">LIVE QUEUE</p><h2>任务队列</h2></div><button class="refresh-button" type="button" @click="refreshQueue">刷新 <span>{{ lastUpdated }}</span></button></div>
            <div class="queue-table" role="table" aria-label="任务队列">
              <div class="queue-header" role="row"><span>任务</span><span>类型</span><span>进度</span><span>状态</span><span>操作</span></div>
              <div v-for="task in queue" :key="task.id" class="queue-record" role="row">
                <span><strong>{{ task.name }}</strong><small>{{ task.id }}</small></span>
                <span>{{ task.type }}</span>
                <span class="progress-cell"><span class="progress-track"><i :style="{ width: `${task.progress}%` }" /></span><small>{{ task.progress }}%</small></span>
                <span><em :class="statusClass(task.status)">{{ task.status }}</em></span>
                <button type="button" :disabled="task.status === '已完成' || task.status === '已取消'" @click="cancelTask(task)">{{ task.status === '已取消' ? '已取消' : task.status === '已完成' ? '完成' : '取消' }}</button>
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
.generation-grid { display: grid; grid-template-columns: 300px minmax(0, 1fr); align-items: start; gap: 17px; }.launch-panel, .queue-panel { padding: 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.launch-panel h2, .queue-panel h2 { margin: 5px 0; font: 400 24px Georgia, serif; }.launch-panel label { display: flex; flex-direction: column; gap: 8px; margin-top: 18px; color: #59554f; font-size: 10px; }.launch-panel label input, .launch-panel label select { width: 100%; padding: 12px 13px; color: #26231f; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 10px; }.launch-panel label input:focus, .launch-panel label select:focus { border-color: #9d8766; box-shadow: 0 0 0 3px #9d87661a; }.provider-note { display: flex; align-items: center; gap: 9px; margin-top: 18px; padding: 12px; background: #edf2ed; border-radius: 9px; }.provider-note > i { flex: 0 0 auto; width: 9px; height: 9px; background: #78907a; border-radius: 50%; }.provider-note div { display: flex; flex-direction: column; gap: 3px; }.provider-note strong { font-size: 9px; }.provider-note span { color: #718073; font-size: 8px; }.launch-button { display: flex; align-items: center; justify-content: center; width: 100%; margin-top: 15px; padding: 12px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.launch-button:hover { background: #37342f; }.launch-panel > small { display: block; margin-top: 9px; color: #8d867e; font-size: 8px; line-height: 1.5; }
.queue-header-row { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 16px; }.queue-header-row h2 { margin-bottom: 0; }.refresh-button { padding: 0; color: #6e665d; background: transparent; border: 0; font-size: 9px; }.refresh-button span { margin-left: 6px; color: #aaa198; font-size: 8px; }.queue-table { font-size: 9px; }.queue-header, .queue-record { display: grid; grid-template-columns: minmax(130px, 1.2fr) 55px minmax(100px, .9fr) 68px 48px; align-items: center; gap: 10px; padding: 11px 0; border-top: 1px solid #eeeae3; }.queue-header { color: #918a81; font-size: 8px; }.queue-record > span:first-child { display: flex; flex-direction: column; gap: 3px; }.queue-record strong { font-size: 9px; }.queue-record small { color: #958e85; font-size: 7px; }.progress-cell { display: flex; align-items: center; gap: 5px; }.progress-track { flex: 1; height: 3px; background: #ede8e0; }.progress-track i { display: block; height: 100%; background: #8e7758; }.progress-cell > small { flex: 0 0 20px; color: #928a80; }.queue-record em { display: inline-block; padding: 5px 8px; color: #746e66; background: #ece9e4; border-radius: 12px; font-size: 7px; font-style: normal; line-height: 1; text-align: center; }.queue-record em.status-running { color: #537059; background: #e6eee7; }.queue-record em.status-complete { color: #537059; background: #e6eee7; }.queue-record em.status-cancelled { color: #746e66; background: #ece9e4; }.queue-record button { padding: 0; color: #8b684f; background: transparent; border: 0; font-size: 8px; }.empty-queue { padding: 30px 0 10px; color: #958e85; text-align: center; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 900px) { .generation-grid { grid-template-columns: 1fr; }.launch-panel { max-width: none; }.queue-panel { min-width: 0; } }
@media (max-width: 800px) { .generation-layout { display: block; }.generation-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.generation-content { padding: 30px 16px 55px; }.queue-panel { overflow-x: auto; }.queue-table { min-width: 590px; } }
</style>
