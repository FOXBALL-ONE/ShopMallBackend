<script setup lang="ts">
import { computed, ref } from 'vue'

definePageMeta({ layout: false })

type Project = {
  id: string
  code: string
  name: string
  season: string
  assets: number
  tasks: number
  tone: 'light' | 'dark'
}

type Task = {
  id: number
  title: string
  project: string
  type: '展示视频' | '展示图片'
  progress: number
  status: '运行中' | '已完成' | '排队中' | '已取消'
}
type PendingReview = {id: number; workflow: string; version: string; media: 'IMAGE' | 'VIDEO'; prompt: string}
type DashboardResponse = {
  stats: {activeProjects: number; assets: number; runningTasks: number; pendingReviews: number}
  projects: Project[]
  tasks: Task[]
  pendingReview: PendingReview | null
}

const activeNav = ref('概览')
const projectMenuOpen = ref(false)
const activeProject = ref('NOIR · 春夏系列')
const activeProjectId = ref('prj_noir')
const taskFilter = ref<'全部' | Task['status']>('全部')
const toast = ref('')
const {user, refresh} = useAuthUser()

await refresh()

const navItems = [
  { label: '概览', icon: '⌂' },
  { label: '素材库', icon: '▧' },
  { label: '工作流', icon: '◇' },
  { label: '生成任务', icon: '✦' },
  { label: '结果中心', icon: '◫' },
  { label: '审核中心', icon: '✓' },
  { label: '团队成员', icon: '♧' },
]

const projects = ref<Project[]>([])
const tasks = ref<Task[]>([])
const stats = ref<DashboardResponse['stats']>({activeProjects: 0, assets: 0, runningTasks: 0, pendingReviews: 0})
const pendingReview = ref<PendingReview | null>(null)
const loading = ref(true)
const loadError = ref('')

const filteredTasks = computed(() => taskFilter.value === '全部' ? tasks.value : tasks.value.filter((task) => task.status === taskFilter.value))

function requestError(error: unknown, fallback: string) {
  const request = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  return request.data?.statusMessage ?? request.data?.message ?? request.statusMessage ?? request.message ?? fallback
}

function selectNav(label: string) {
  const routes: Record<string, string> = {
    概览: '/dashboard',
    素材库: `/projects/${activeProjectId.value}/assets`,
    工作流: `/projects/${activeProjectId.value}/workflows`,
    生成任务: `/projects/${activeProjectId.value}/generate`,
    结果中心: `/projects/${activeProjectId.value}/results`,
    审核中心: '/review',
    团队成员: '/team',
  }
  if (routes[label]) navigateTo(routes[label])
}

function selectProject(project: Project) {
  activeProjectId.value = project.id
  activeProject.value = project.name
  projectMenuOpen.value = false
}

const requestFetch = import.meta.server ? useRequestFetch() : $fetch
try {
  const response = await requestFetch<DashboardResponse>('/api/dashboard')
  projects.value = response.projects
  tasks.value = response.tasks
  stats.value = response.stats
  pendingReview.value = response.pendingReview
  if (projects.value[0]) {
    activeProjectId.value = projects.value[0].id
    activeProject.value = projects.value[0].name
  }
} catch (error: unknown) {
  loadError.value = requestError(error, '概览数据加载失败，请重试。')
} finally {
  loading.value = false
}
</script>

<template>
  <div class="workspace-layout">
    <StudioSidebar />

    <section class="main-area">
      <header class="topbar">
        <div class="project-switcher">
          <span>品牌工作空间</span>
          <button type="button" @click="projectMenuOpen = !projectMenuOpen">
            {{ activeProject }} <span class="chevron">⌄</span>
          </button>
          <div v-if="projectMenuOpen" class="project-menu">
            <button v-for="project in projects" :key="project.code" type="button" @click="selectProject(project)">
              <span>{{ project.name }}</span><small>{{ project.season }}</small>
            </button>
          </div>
        </div>
        <div class="top-actions">
          <span class="service-state"><i /> 生成服务由平台安全代理</span>
          <button class="icon-button" type="button" aria-label="通知" @click="toast = '暂无新的通知'">⌁</button>
          <button class="new-button" type="button" @click="selectNav('工作流')"><span>＋</span> 新建工作流</button>
        </div>
      </header>

      <main class="content">
        <section class="welcome">
          <div>
            <p class="eyebrow">CREATIVE OPERATIONS</p>
            <h1>欢迎回来，{{ user?.username ?? '当前用户' }}</h1>
            <p>管理今天的素材、生成与审核节奏。</p>
          </div>
          <div class="welcome-actions">
            <button class="quiet-button" type="button" @click="selectNav('素材库')">查看素材库 <span>↗</span></button>
            <button class="dark-button" type="button" @click="selectNav('生成任务')">新建生成 <span>＋</span></button>
          </div>
        </section>

        <section class="stats-grid" aria-label="工作台统计">
          <article><span>活跃项目</span><strong>{{ stats.activeProjects }}</strong><small>品牌制作空间</small></article>
          <article><span>素材资产</span><strong>{{ stats.assets }}</strong><small>已纳入项目管理</small></article>
          <article><span>运行任务</span><strong>{{ stats.runningTasks }}</strong><small><i class="pulse" />队列持续同步</small></article>
          <article><span>等待审核</span><strong>{{ stats.pendingReviews }}</strong><small>需要团队判断</small></article>
        </section>

        <section class="section-heading">
          <div><p class="eyebrow">COLLECTIONS</p><h2>最近项目</h2></div>
          <button type="button" @click="selectNav('素材库')">查看全部 <span>→</span></button>
        </section>

        <p v-if="loadError" class="load-error" role="alert">{{ loadError }}</p>
        <p v-else-if="loading" class="loading-state">正在从数据库读取概览数据…</p>
        <section class="dashboard-grid">
          <div class="panel collections-panel">
            <div class="collection-list">
              <button v-for="project in projects" :key="project.code" class="collection-card" :class="`tone-${project.tone}`" type="button" @click="selectProject(project)">
                <div class="collection-art"><span>{{ project.code }}</span><i /></div>
                <div class="collection-info"><small>{{ project.season }}</small><h3>{{ project.name }}</h3><p>{{ project.assets }} 份素材 · {{ project.tasks }} 个任务</p></div>
                <b>↗</b>
              </button>
              <button class="new-project-card" type="button" @click="toast = '新项目创建流程已准备'">
                <span>＋</span><strong>创建新项目</strong><small>从一个系列开始</small>
              </button>
            </div>
          </div>

          <div class="panel tasks-panel">
            <div class="panel-head"><div><p class="eyebrow">QUEUE</p><h2>任务动态</h2></div><button type="button" @click="selectNav('生成任务')">全部 →</button></div>
            <div class="task-tabs">
              <button v-for="filter in ['全部', '运行中', '已完成']" :key="filter" type="button" :class="{ active: taskFilter === filter }" @click="taskFilter = filter as typeof taskFilter">{{ filter }}</button>
            </div>
            <div class="task-list">
              <div v-for="task in filteredTasks" :key="`${task.project}-${task.title}`" class="task-row">
                <i :class="['task-dot', task.status === '运行中' ? 'running' : task.status === '已完成' ? 'succeeded' : 'queued']" />
                <span><strong>{{ task.type }}</strong><small>{{ task.project }}</small></span>
                <em>{{ task.progress }}%</em>
              </div>
              <p v-if="!filteredTasks.length" class="empty-task">暂无符合条件的任务</p>
            </div>
            <button class="panel-link" type="button" @click="selectNav('生成任务')">查看生成队列 <span>→</span></button>
          </div>

          <div class="panel review-panel">
            <p class="eyebrow">REVIEW</p><h2>等待你的判断</h2>
            <div class="review-art"><i /><span>{{ pendingReview?.media === 'VIDEO' ? 'VIDEO' : 'IMAGE' }} · {{ pendingReview?.workflow || '暂无待审核结果' }}</span></div>
            <p>{{ pendingReview?.prompt || '生成完成后，结果会自动进入审核队列。' }}</p>
            <button type="button" @click="selectNav('审核中心')">进入结果中心 <span>→</span></button>
          </div>
        </section>
      </main>
    </section>

    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --muted: #7d776f; --line: #e7e1d8; --paper: #f7f5f0; --gold: #a18455; }
* { box-sizing: border-box; }
html, body, #__nuxt { min-height: 100%; margin: 0; }
body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; }
button { font: inherit; cursor: pointer; }
.workspace-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }
.sidebar { position: sticky; top: 0; z-index: 20; height: 100vh; display: flex; flex-direction: column; padding: 25px 18px; background: #fcfbf8; border-right: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 11px; padding: 0 8px 24px; color: var(--ink); text-align: left; background: transparent; border: 0; }
.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid; border-radius: 50%; font: 18px Georgia, serif; }
.brand-copy { display: flex; flex-direction: column; gap: 3px; letter-spacing: .15em; }.brand-copy strong { font: 500 14px Georgia, serif; }.brand-copy small { color: #847e75; font-size: 7px; }
.space-card { display: flex; flex-direction: column; gap: 4px; margin-bottom: 18px; padding: 13px; background: #eee9e1; border-radius: 10px; }.space-label, .space-season { color: #8d867d; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }.space-card strong { font: 400 12px Georgia, serif; }
.nav-list { display: flex; flex-direction: column; gap: 4px; }.nav-label { margin: 0 0 5px; padding: 0 11px; color: #999188; font-size: 9px; font-weight: 700; letter-spacing: .16em; }.nav-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 11px; color: #716c65; text-align: left; background: transparent; border: 0; border-radius: 8px; font-size: 12px; }.nav-item:hover, .nav-item.active { color: var(--ink); background: #eae5dd; }.nav-item.active { font-weight: 600; }.nav-icon { display: grid; place-items: center; width: 16px; color: #938b81; font-size: 14px; }.nav-item.active .nav-icon { color: #8c7657; }.nav-item em { display: grid; place-items: center; width: 19px; height: 19px; margin-left: auto; color: #fff; background: #292722; border-radius: 50%; font-size: 9px; font-style: normal; }
.sidebar-bottom { margin-top: auto; }.tip-card { display: flex; flex-direction: column; gap: 7px; padding: 16px; background: #eee9e1; border-radius: 11px; }.tip-icon { display: grid; place-items: center; width: 24px; height: 24px; color: #fff; background: #292722; border-radius: 50%; font-size: 11px; }.tip-card strong { font: 400 14px Georgia, serif; }.tip-card p { margin: 0; color: #777169; font-size: 10px; line-height: 1.5; }.tip-card button { display: flex; justify-content: space-between; margin-top: 3px; padding: 0; color: #575149; text-align: left; background: transparent; border: 0; font-size: 10px; }.profile { display: flex; align-items: center; gap: 9px; margin-top: 16px; padding: 16px 5px 0; border-top: 1px solid var(--line); }.avatar { display: grid; place-items: center; width: 31px; height: 31px; color: #fff; background: #282622; border-radius: 50%; font-size: 9px; }.profile > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }.profile strong { font-size: 10px; }.profile small { color: #8b857d; font-size: 8px; }.profile button { margin-left: auto; color: #8b857d; background: transparent; border: 0; font-size: 14px; }
.main-area { min-width: 0; }.topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.project-switcher { position: relative; display: flex; flex-direction: column; gap: 2px; }.project-switcher > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.project-switcher > button { padding: 0; color: var(--ink); text-align: left; background: transparent; border: 0; font: 500 12px Georgia, serif; }.chevron { margin-left: 5px; color: #898178; }.project-menu { position: absolute; top: 44px; left: -10px; z-index: 30; width: 210px; padding: 6px; background: #fff; border: 1px solid var(--line); border-radius: 9px; box-shadow: 0 12px 28px #25231f1c; }.project-menu button { display: flex; flex-direction: column; gap: 3px; width: 100%; padding: 9px; text-align: left; background: transparent; border: 0; border-radius: 6px; }.project-menu button:hover { background: #f2eee7; }.project-menu span { font-size: 11px; }.project-menu small { color: #9a9288; font-size: 8px; }.top-actions { display: flex; align-items: center; gap: 12px; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i, .pulse { display: inline-block; width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }.icon-button { display: grid; place-items: center; width: 34px; height: 34px; color: #69635b; background: #fff; border: 1px solid var(--line); border-radius: 50%; font-size: 17px; }.new-button, .dark-button { display: flex; align-items: center; gap: 7px; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.new-button span { font-size: 14px; }
.content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.welcome { display: flex; align-items: flex-end; justify-content: space-between; gap: 22px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.welcome h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.welcome p:not(.eyebrow) { margin: 0; color: #817b73; font-size: 12px; }.welcome-actions { display: flex; gap: 9px; }.quiet-button { display: flex; align-items: center; gap: 7px; padding: 10px 13px; color: #5d5750; background: #fff; border: 1px solid var(--line); border-radius: 8px; font-size: 10px; }
.loading-state, .load-error { margin: 0 0 18px; padding: 11px 14px; color: #817b73; background: #f0ece6; border-radius: 8px; font-size: 9px; }.load-error { color: #9b6254; background: #fff4f1; }
.stats-grid { display: grid; grid-template-columns: repeat(4, 1fr); margin: 38px 0 42px; border-top: 1px solid #e2ddd4; border-bottom: 1px solid #e2ddd4; }.stats-grid article { display: grid; grid-template-columns: 1fr auto; gap: 8px; padding: 20px 22px 20px 0; }.stats-grid article + article { padding-left: 22px; border-left: 1px solid #e2ddd4; }.stats-grid span { color: #756f67; font-size: 10px; }.stats-grid strong { grid-column: 2; grid-row: 1 / 3; font: 400 30px Georgia, serif; }.stats-grid small { color: #a09a91; font-size: 8px; }.stats-grid small .pulse { width: 6px; height: 6px; margin-right: 5px; box-shadow: none; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 17px; }.section-heading h2, .panel-head h2, .review-panel h2 { margin: 4px 0 0; font: 400 22px Georgia, serif; }.section-heading button, .panel-head button { display: flex; gap: 4px; align-items: center; padding: 0; color: #686159; background: transparent; border: 0; font-size: 10px; }.dashboard-grid { display: grid; grid-template-columns: minmax(0, 1.6fr) minmax(250px, .7fr); gap: 18px; }.panel { padding: 21px; background: #fff; border: 1px solid #e7e2d9; border-radius: 13px; }.collections-panel { grid-row: span 2; }.collection-list { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.collection-card { position: relative; overflow: hidden; padding: 0; color: #26231f; text-align: left; background: #fff; border: 1px solid #ece7df; border-radius: 10px; }.collection-art { position: relative; height: 145px; overflow: hidden; background: linear-gradient(135deg, #d4c8b9, #f1ebe2); }.tone-dark .collection-art { background: linear-gradient(135deg, #322f2b, #8b8177); }.collection-art > span { position: absolute; top: 10px; left: 11px; color: #6b6259; font-size: 7px; letter-spacing: .12em; }.tone-dark .collection-art > span { color: #d8d1c8; }.collection-art i { position: absolute; left: 50%; top: 18px; width: 82px; height: 140px; background: #fff9; border-radius: 40%; clip-path: polygon(24% 0, 76% 0, 89% 25%, 100% 100%, 0 100%, 11% 25%); transform: translateX(-50%); }.tone-dark .collection-art i { background: #1c1a18a8; }.collection-info { padding: 12px; }.collection-info small { color: #9b805b; font-size: 8px; }.collection-info h3 { margin: 4px 0; font: 400 14px Georgia, serif; }.collection-info p { margin: 0; color: #8a847c; font-size: 8px; }.collection-card > b { position: absolute; right: 11px; bottom: 12px; font-size: 13px; font-weight: 400; }.new-project-card { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; min-height: 207px; color: #6f6961; background: #fcfbf8; border: 1px dashed #cfc8bd; border-radius: 10px; }.new-project-card > span { display: grid; place-items: center; width: 35px; height: 35px; color: #555049; background: #ebe6de; border-radius: 50%; font-size: 18px; }.new-project-card strong { color: #403c36; font: 400 14px Georgia, serif; }.new-project-card small { font-size: 9px; }
.panel-head { display: flex; align-items: flex-end; justify-content: space-between; margin-bottom: 10px; }.task-tabs { display: flex; gap: 4px; margin: 12px 0 3px; padding: 3px; background: #f1ede7; border-radius: 7px; }.task-tabs button { flex: 1; padding: 6px 4px; color: #8b837a; background: transparent; border: 0; border-radius: 5px; font-size: 9px; }.task-tabs button.active { color: #292622; background: #fff; box-shadow: 0 2px 6px #322a2210; }.task-list { display: flex; flex-direction: column; }.task-row { display: flex; align-items: center; gap: 9px; padding: 11px 0; border-top: 1px solid #efebe4; }.task-dot { width: 7px; height: 7px; flex: 0 0 auto; background: #a2988b; border-radius: 50%; }.task-dot.running { background: #718e76; }.task-dot.succeeded { background: #596d5c; }.task-row > span { display: flex; flex-direction: column; gap: 2px; }.task-row strong { font-size: 9px; }.task-row small { color: #8d877f; font-size: 8px; }.task-row em { margin-left: auto; color: #8a7352; font-size: 9px; font-style: normal; }.empty-task { padding: 18px 0; color: #999188; font-size: 9px; text-align: center; }.panel-link { display: flex; justify-content: space-between; width: 100%; margin-top: 5px; padding: 12px 0 0; color: #5f5a53; text-align: left; background: transparent; border: 0; border-top: 1px solid #efebe4; font-size: 9px; }.review-panel { color: #fff; background: #292722; }.review-panel .eyebrow { color: #b7a88f; }.review-panel h2 { margin-bottom: 14px; }.review-art { position: relative; height: 94px; overflow: hidden; background: linear-gradient(135deg, #a99885, #dfd4c7); border-radius: 8px; }.review-art i { position: absolute; left: 43%; top: 8px; width: 58px; height: 100px; background: #403b36; border-radius: 40%; clip-path: polygon(28% 0, 72% 0, 88% 25%, 100% 100%, 0 100%, 12% 25%); }.review-art span { position: absolute; right: 9px; bottom: 8px; color: #fff; font-size: 7px; letter-spacing: .1em; }.review-panel > p:not(.eyebrow) { color: #c3bdb3; font-size: 10px; line-height: 1.5; }.review-panel button { display: flex; justify-content: space-between; align-items: center; width: 100%; padding: 10px 12px; color: #24221f; background: #f5f1ea; border: 0; border-radius: 7px; font-size: 10px; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 1050px) { .stats-grid { grid-template-columns: repeat(2, 1fr); }.stats-grid article:nth-child(3) { padding-left: 0; border-left: 0; border-top: 1px solid #e2ddd4; }.stats-grid article:nth-child(4) { border-top: 1px solid #e2ddd4; }.dashboard-grid { grid-template-columns: 1fr; }.collections-panel { grid-row: auto; } }
@media (max-width: 760px) { .workspace-layout { display: block; }.sidebar { position: static; flex-direction: row; align-items: center; height: auto; padding: 13px 16px; }.brand { padding: 0; }.space-card, .nav-list, .tip-card, .profile > span:nth-child(2), .profile button { display: none; }.profile { margin: 0 0 0 auto; padding: 0; border: 0; }.topbar { height: 58px; padding: 0 18px; }.service-state, .icon-button { display: none; }.content { padding: 30px 18px 55px; }.welcome { align-items: flex-start; flex-direction: column; }.welcome-actions { width: 100%; }.welcome-actions button { flex: 1; justify-content: center; }.stats-grid { margin: 28px 0 38px; }.stats-grid article { padding: 15px 12px 15px 0; }.stats-grid article + article { padding-left: 12px; }.collection-list { grid-template-columns: 1fr; }.new-project-card { min-height: 150px; }.dashboard-grid { gap: 14px; } }
@media (max-width: 480px) { .topbar { padding: 0 14px; }.project-switcher > button { max-width: 170px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.new-button { padding: 9px; font-size: 0; }.new-button span { font-size: 16px; }.content { padding-inline: 15px; }.welcome h1 { font-size: 37px; }.stats-grid strong { font-size: 26px; }.panel { padding: 16px; }.section-heading h2 { font-size: 20px; } }
</style>
