<script setup lang="ts">
import { computed, ref } from 'vue'

definePageMeta({ layout: false })

const { projects, loading, error, loadProjects, createProject, updateProject, selectProject } = useProjects()
const route = useRoute()
const modalOpen = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const toast = ref('')
const form = ref({ id: '', name: '', season: '' })

const activeProjects = computed(() => projects.value.filter((project) => project.status !== 'ARCHIVED'))
const archivedProjects = computed(() => projects.value.filter((project) => project.status === 'ARCHIVED'))

function showToast(message: string) {
  toast.value = message
  if (import.meta.client) window.setTimeout(() => { if (toast.value === message) toast.value = '' }, 2400)
}

function openCreate() {
  editingId.value = null
  form.value = { id: '', name: '', season: '' }
  modalOpen.value = true
}

if (route.query.create === '1') openCreate()

function openEdit(project: { id: string; name: string; season: string }) {
  editingId.value = project.id
  form.value = { id: project.id, name: project.name, season: project.season }
  modalOpen.value = true
}

async function save() {
  if (!form.value.name.trim()) return showToast('请输入工程名称。')
  saving.value = true
  try {
    if (editingId.value) {
      await updateProject(editingId.value, { name: form.value.name, season: form.value.season })
      showToast('工程信息已更新。')
    } else {
      const project = await createProject({ id: form.value.id || undefined, name: form.value.name, season: form.value.season })
      selectProject(project.id)
      showToast('工程已创建并切换。')
    }
    modalOpen.value = false
  } catch (cause: unknown) {
    const request = cause as { data?: { statusMessage?: string; message?: string }; statusMessage?: string; message?: string }
    showToast(request.data?.statusMessage ?? request.data?.message ?? request.statusMessage ?? request.message ?? '保存工程失败。')
  } finally {
    saving.value = false
  }
}

async function archive(project: { id: string; name: string }) {
  if (activeProjects.value.length <= 1) return showToast('至少保留一个活动工程作为工作区。')
  if (!import.meta.client || !window.confirm(`确认归档工程“${project.name}”？归档后数据会保留且可恢复。`)) return
  try {
    await updateProject(project.id, { status: 'ARCHIVED' })
    showToast('工程已归档，数据仍可恢复。')
  } catch {
    showToast('工程归档失败，请重试。')
  }
}

async function restore(project: { id: string }) {
  try { await updateProject(project.id, { status: 'ACTIVE' }); showToast('工程已恢复。') } catch { showToast('工程恢复失败，请重试。') }
}

function enter(projectId: string) {
  selectProject(projectId)
  navigateTo(`/projects/${encodeURIComponent(projectId)}/assets`)
}

await loadProjects().catch(() => undefined)
</script>

<template>
  <div class="workspace-layout">
    <StudioSidebar />
    <section class="main-area">
      <StudioTopbar>
        <span class="service-state"><i /> 全局 API 模块 · 安全代理</span>
        <button class="new-button" type="button" @click="openCreate"><span>＋</span> 新建工程</button>
      </StudioTopbar>
      <main class="content">
        <section class="heading"><div><p class="eyebrow">PROJECT OPERATIONS</p><h1>工程管理</h1><span>以工程为工作区单位，管理从创建到归档的完整生命周期。</span></div><button class="dark-button" type="button" @click="openCreate">＋ 创建工程</button></section>

        <p v-if="error" class="load-error" role="alert">{{ error }} <button type="button" @click="loadProjects(true)">重试</button></p>
        <section class="lifecycle"><div class="lifecycle-step active"><b>01</b><span>规划中</span><small>定义系列目标</small></div><div class="lifecycle-line" /><div class="lifecycle-step"><b>02</b><span>制作中</span><small>素材与工作流</small></div><div class="lifecycle-line" /><div class="lifecycle-step"><b>03</b><span>审核中</span><small>结果集中决策</small></div><div class="lifecycle-line" /><div class="lifecycle-step"><b>04</b><span>已归档</span><small>沉淀可复用资产</small></div></section>

        <section v-if="loading" class="empty-state"><strong>正在加载工程</strong><span>读取工程列表与工作区统计…</span></section>
        <section v-else-if="activeProjects.length" class="project-grid">
          <article v-for="project in activeProjects" :key="project.id" class="project-card">
            <div class="project-card-head"><span class="project-code">{{ project.id }}</span><span class="project-status"><i /> 进行中</span></div>
            <button class="project-art" type="button" @click="enter(project.id)"><span>{{ project.season }}</span><b>↗</b></button>
            <div class="project-info"><h2>{{ project.name }}</h2><p>工程工作区 · {{ project.season }}</p><div class="project-metrics"><span><b>{{ project.assets ?? 0 }}</b> 素材</span><span><b>{{ project.workflows ?? 0 }}</b> 工作流</span><span><b>{{ project.tasks ?? 0 }}</b> 任务</span></div></div>
            <div class="project-actions"><button type="button" @click="enter(project.id)">进入工作区 <span>→</span></button><button type="button" aria-label="编辑工程" @click="openEdit(project)">编辑</button><button type="button" aria-label="归档工程" @click="archive(project)">归档</button></div>
          </article>
          <button class="new-project-card" type="button" @click="openCreate"><span>＋</span><strong>创建新工程</strong><small>从一个系列开始</small></button>
        </section>
        <section v-if="archivedProjects.length" class="archived-section"><div class="archived-head"><h2>已归档工程</h2><span>数据保留，可随时恢复</span></div><div class="archived-list"><article v-for="project in archivedProjects" :key="project.id"><div><strong>{{ project.name }}</strong><small>{{ project.season }} · {{ project.assets ?? 0 }} 份素材</small></div><button type="button" @click="restore(project)">恢复工程</button></article></div></section>
        <section v-else-if="!loading && !activeProjects.length" class="empty-state"><strong>还没有工程</strong><span>创建第一个工程，开始管理专属的素材、工作流与生成结果。</span><button class="dark-button" type="button" @click="openCreate">创建第一个工程</button></section>

        <section class="scope-note"><div><span class="scope-icon">◎</span><div><strong>共享范围清晰可控</strong><p>素材可在“全局共享”和“工程共享”之间转换；API 模块始终为全局共享，工作流、生成任务和结果中心仅属于当前工程。</p></div></div><NuxtLink to="/api-management">管理全局 API <span>→</span></NuxtLink></section>
      </main>
    </section>
    <div v-if="modalOpen" class="modal-backdrop" @click.self="modalOpen = false"><form class="modal-card" @submit.prevent="save"><div class="modal-head"><div><p class="eyebrow">{{ editingId ? 'EDIT PROJECT' : 'NEW PROJECT' }}</p><h2>{{ editingId ? '编辑工程' : '创建新工程' }}</h2></div><button type="button" class="close-button" aria-label="关闭" @click="modalOpen = false">×</button></div><label>工程名称<input v-model="form.name" maxlength="120" placeholder="例如：NOIR · 春夏系列" required><small>用于工作区导航，最多 120 个字符。</small></label><label v-if="!editingId">工程 ID（可选）<input v-model="form.id" maxlength="120" pattern="[A-Za-z0-9_-]+" placeholder="自动生成，例如 prj_noir_ss26"><small>仅支持字母、数字、下划线和连字符；留空将自动生成。</small></label><label>系列 / 季节<input v-model="form.season" maxlength="80" placeholder="例如：SS 2026"><small>最多 80 个字符，可留空。</small></label><button class="dark-button modal-submit" type="submit" :disabled="saving">{{ saving ? '保存中…' : editingId ? '保存修改' : '创建并进入工程' }}</button></form></div>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button, input { font: inherit; } button { cursor: pointer; }
.workspace-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.main-area { min-width: 0; }.topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.project-switcher { display: flex; flex-direction: column; gap: 2px; }.project-switcher span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.project-switcher strong { font: 500 12px Georgia, serif; }.top-actions { display: flex; align-items: center; gap: 12px; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }.new-button, .dark-button { display: flex; align-items: center; gap: 7px; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.new-button span { font-size: 14px; }.content { max-width: 1320px; margin: 0 auto; padding: 42px 4% 65px; }.heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 30px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; }.heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.heading > div > span { color: #817b73; font-size: 12px; }.lifecycle { display: flex; align-items: center; margin-bottom: 32px; padding: 18px 22px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.lifecycle-step { display: flex; flex-direction: column; gap: 3px; min-width: 110px; }.lifecycle-step b { color: #aaa197; font-size: 9px; }.lifecycle-step span { font: 400 14px Georgia, serif; }.lifecycle-step small { color: #999188; font-size: 8px; }.lifecycle-step.active b, .lifecycle-step.active span { color: #8a7659; }.lifecycle-line { flex: 1; height: 1px; margin: 0 16px; background: #e1dbd2; }.project-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }.project-card { overflow: hidden; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.project-card-head { display: flex; justify-content: space-between; padding: 14px 15px 0; }.project-code { color: #9b805b; font-size: 8px; letter-spacing: .12em; }.project-status { display: flex; align-items: center; gap: 4px; color: #627666; font-size: 8px; }.project-status i { width: 6px; height: 6px; background: #7f9b82; border-radius: 50%; }.project-art { position: relative; width: 100%; height: 150px; margin-top: 9px; overflow: hidden; color: #65594e; text-align: left; background: linear-gradient(135deg, #d9cdbf, #f2ece4); border: 0; }.project-card:nth-child(3n+2) .project-art { background: linear-gradient(135deg, #332f2a, #958b7f); color: #ded4c8; }.project-card:nth-child(3n+3) .project-art { background: linear-gradient(135deg, #b7aaa0, #ebe2d8); }.project-art::after { position: absolute; left: 50%; top: 20px; width: 90px; height: 140px; background: #fff9; border-radius: 40%; clip-path: polygon(24% 0, 76% 0, 90% 25%, 100% 100%, 0 100%, 10% 25%); transform: translateX(-50%); content: ''; }.project-art span { position: absolute; top: 12px; left: 14px; z-index: 1; font-size: 9px; letter-spacing: .14em; }.project-art b { position: absolute; right: 14px; bottom: 11px; z-index: 1; font-size: 16px; font-weight: 400; }.project-info { padding: 14px 15px 12px; }.project-info h2 { margin: 0 0 4px; font: 400 18px Georgia, serif; }.project-info p { margin: 0 0 13px; color: #89827a; font-size: 9px; }.project-metrics { display: flex; gap: 15px; color: #938b82; font-size: 8px; }.project-metrics b { color: #3d3934; font: 400 16px Georgia, serif; }.project-actions { display: flex; align-items: center; gap: 13px; padding: 11px 15px; border-top: 1px solid #eee9e2; }.project-actions button { padding: 0; color: #655e56; background: transparent; border: 0; font-size: 9px; }.project-actions button:first-child { margin-right: auto; color: #3e3933; }.project-actions button:last-child { color: #a27468; }.new-project-card { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 300px; color: #6f6961; background: #fcfbf8; border: 1px dashed #cfc8bd; border-radius: 12px; }.new-project-card > span { display: grid; place-items: center; width: 38px; height: 38px; color: #555049; background: #ebe6de; border-radius: 50%; font-size: 20px; }.new-project-card strong { color: #403c36; font: 400 15px Georgia, serif; }.new-project-card small { font-size: 9px; }.scope-note { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-top: 22px; padding: 16px 18px; background: #eee9e1; border-radius: 11px; }.scope-note > div { display: flex; align-items: flex-start; gap: 11px; }.scope-note strong { font: 400 15px Georgia, serif; }.scope-note p { max-width: 700px; margin: 4px 0 0; color: #777169; font-size: 10px; line-height: 1.5; }.scope-icon { display: grid; place-items: center; width: 27px; height: 27px; color: #fff; background: #292722; border-radius: 50%; }.scope-note a { color: #575149; font-size: 10px; text-decoration: none; white-space: nowrap; }.load-error { margin: 0 0 18px; padding: 11px 14px; color: #9b6254; background: #fff4f1; border-radius: 8px; font-size: 10px; }.load-error button { margin-left: 8px; color: inherit; background: transparent; border: 0; text-decoration: underline; }.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 70px 20px; color: #898279; border: 1px dashed #d3ccc2; border-radius: 12px; text-align: center; }.empty-state strong { color: #4b4640; font: 400 17px Georgia, serif; }.empty-state span { font-size: 10px; }.modal-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 20px; background: #1d1b1894; backdrop-filter: blur(4px); }.modal-card { width: min(520px, 100%); padding: 25px; background: #f8f6f1; border-radius: 14px; box-shadow: 0 30px 90px #00000040; }.modal-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }.modal-head h2 { margin: 5px 0; font: 400 25px Georgia, serif; }.close-button { color: #766f67; background: transparent; border: 0; font-size: 24px; }.modal-card label { display: flex; flex-direction: column; gap: 7px; margin-bottom: 16px; color: #59554f; font-size: 11px; }.modal-card label small { color: #928a81; font-size: 8px; }.modal-card input { width: 100%; padding: 11px 12px; color: #26231f; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 11px; }.modal-submit { justify-content: center; width: 100%; margin-top: 5px; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 110; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; font-size: 10px; box-shadow: 0 10px 30px #0002; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 980px) { .project-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 800px) { .workspace-layout { display: block; }.topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.content { padding: 30px 16px 55px; }.heading { align-items: flex-start; flex-direction: column; }.heading > .dark-button { width: 100%; justify-content: center; }.lifecycle { overflow-x: auto; }.lifecycle-step { min-width: 100px; }.project-grid { grid-template-columns: 1fr; }.scope-note { align-items: flex-start; flex-direction: column; } }
</style>
