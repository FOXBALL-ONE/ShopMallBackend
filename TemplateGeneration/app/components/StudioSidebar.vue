<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  projectId?: string
  projectName?: string
  season?: string
}>(), {
  // An omitted project id means this is a global page. In that case the
  // sidebar must reflect the shared workspace selection instead of pinning
  // itself to the legacy demo project.
  projectId: '',
  projectName: '',
  season: '',
})

const route = useRoute()
const { projects, selectedProject, selectProject, loadProjects } = useProjects()
const {user, refresh, logout} = useAuthUser()

await refresh()
await loadProjects().catch(() => undefined)

const displayName = computed(() => user.value?.username ?? '当前用户')
const initials = computed(() => displayName.value.slice(0, 2).toUpperCase())
// The shared selection is authoritative. The route prop is only a bootstrap
// fallback for direct links before the project list hydrates.
const workspaceProjectId = computed(() => selectedProject.value?.id || props.projectId || 'prj_noir')
const workspaceProject = computed(() => projects.value.find((project) => project.id === workspaceProjectId.value) ?? selectedProject.value)

const items = computed(() => [
  { label: '概览', icon: '⌂', to: '/dashboard' },
  { label: '工程管理', icon: '▦', to: '/projects' },
  { label: '素材库', icon: '▧', to: `/projects/${workspaceProjectId.value}/assets` },
  { label: '工作流', icon: '◇', to: `/projects/${workspaceProjectId.value}/workflows` },
  { label: '生成任务', icon: '✦', to: `/projects/${workspaceProjectId.value}/generate` },
  { label: '结果中心', icon: '◫', to: `/projects/${workspaceProjectId.value}/results` },
  { label: '审核中心', icon: '✓', to: '/review', badge: '1' },
  { label: '团队成员', icon: '♧', to: '/team' },
  { label: 'API 管理', icon: '⌘', to: '/api-management' },
])

function isActive(to: string) {
  return to === '/projects' ? route.path === '/projects' : route.path === to
}

const projectOptions = computed(() => projects.value.map((project) => ({ label: project.name, value: project.id })))

function switchProject(value: string) {
  selectProject(value)
  if (route.path.startsWith('/projects/') && !route.path.startsWith('/projects/[projectId]')) {
    const section = route.path.split('/')[3] || 'assets'
    if (['assets', 'workflows', 'generate', 'results'].includes(section)) navigateTo(`/projects/${value}/${section}`)
  }
}
</script>

<template>
  <aside class="studio-sidebar">
    <NuxtLink class="studio-brand" to="/dashboard" aria-label="返回工作台">
      <span class="studio-brand-mark">A</span>
      <span class="studio-brand-copy"><strong>ATELIER</strong><small>AI STUDIO</small></span>
    </NuxtLink>

    <div class="studio-project-card">
      <span>当前项目</span>
      <select :value="workspaceProjectId" aria-label="切换项目" @change="switchProject(($event.target as HTMLSelectElement).value)">
        <option v-for="project in projectOptions" :key="project.value" :value="project.value">{{ project.label }}</option>
      </select>
      <small>{{ workspaceProject?.season || season }}</small>
    </div>

    <nav class="studio-nav" aria-label="工作台导航">
      <p>WORKSPACE</p>
      <NuxtLink v-for="item in items" :key="item.label" class="studio-nav-item" :class="{ active: isActive(item.to) }" :to="item.to">
        <span class="studio-nav-icon" aria-hidden="true">{{ item.icon }}</span><span>{{ item.label }}</span><em v-if="item.badge">{{ item.badge }}</em>
      </NuxtLink>
    </nav>

    <div class="studio-sidebar-bottom">
      <div class="studio-tip-card"><span class="studio-tip-icon">✦</span><strong>保持灵感流动</strong><p>从一个工作流版本开始，构建下一组画面。</p><NuxtLink :to="`/projects/${workspaceProjectId}/workflows`">查看工作流 <span>→</span></NuxtLink></div>
      <div class="studio-profile"><span class="studio-avatar">{{ initials }}</span><NuxtLink class="studio-profile-user" to="/account"><strong>{{ displayName }}</strong><small>工作区成员</small></NuxtLink><button type="button" aria-label="退出登录" @click="logout">↗</button></div>
    </div>
  </aside>
</template>

<style scoped>
.studio-sidebar { position: sticky; top: 0; z-index: 20; display: flex; flex-direction: column; height: 100vh; padding: 25px 18px; background: #fcfbf8; border-right: 1px solid var(--line, #e7e1d8); }
.studio-brand { display: flex; align-items: center; gap: 11px; padding: 0 8px 24px; color: var(--ink, #24221f); text-decoration: none; }
.studio-brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid currentColor; border-radius: 50%; font: 18px Georgia, serif; }
.studio-brand-copy { display: flex; flex-direction: column; gap: 3px; letter-spacing: .15em; }.studio-brand-copy strong { font: 500 14px Georgia, serif; }.studio-brand-copy small { color: #847e75; font-size: 7px; }
.studio-project-card { display: flex; flex-direction: column; gap: 4px; margin-bottom: 18px; padding: 13px; background: #eee9e1; border-radius: 10px; }.studio-project-card span, .studio-project-card small { color: #8d867d; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }.studio-project-card select { width: 100%; padding: 0 14px 0 0; color: var(--ink, #24221f); background: transparent; border: 0; outline: 0; font: 400 12px Georgia, serif; }
.studio-nav { display: flex; flex-direction: column; gap: 4px; }.studio-nav > p { margin: 0 0 5px; padding: 0 11px; color: #999188; font-size: 9px; font-weight: 700; letter-spacing: .16em; }.studio-nav-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 11px; color: #716c65; border-radius: 8px; font-size: 12px; text-decoration: none; }.studio-nav-item:hover, .studio-nav-item.active { color: var(--ink, #24221f); background: #eae5dd; }.studio-nav-item.active { font-weight: 600; }.studio-nav-icon { display: grid; place-items: center; width: 16px; color: #938b81; font-size: 14px; }.studio-nav-item.active .studio-nav-icon { color: #8a7659; }.studio-nav-item em { display: grid; place-items: center; width: 19px; height: 19px; margin-left: auto; color: #fff; background: #292722; border-radius: 50%; font-size: 9px; font-style: normal; }
.studio-sidebar-bottom { margin-top: auto; }.studio-tip-card { display: flex; flex-direction: column; gap: 7px; padding: 16px; background: #eee9e1; border-radius: 11px; }.studio-tip-icon { display: grid; place-items: center; width: 24px; height: 24px; color: #fff; background: #292722; border-radius: 50%; font-size: 11px; }.studio-tip-card strong { color: var(--ink, #24221f); font: 400 14px Georgia, serif; }.studio-tip-card p { margin: 0; color: #777169; font-size: 10px; line-height: 1.5; }.studio-tip-card a { display: flex; justify-content: space-between; margin-top: 3px; color: #575149; font-size: 10px; text-decoration: none; }.studio-profile { display: flex; align-items: center; gap: 9px; margin-top: 16px; padding: 16px 5px 0; border-top: 1px solid var(--line, #e7e1d8); }.studio-avatar { display: grid; place-items: center; width: 31px; height: 31px; color: #fff; background: #282622; border-radius: 50%; font-size: 9px; }.studio-profile > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }.studio-profile strong { color: var(--ink, #24221f); font-size: 10px; }.studio-profile small { color: #8b857d; font-size: 8px; }.studio-profile a { margin-left: auto; color: #8b857d; font-size: 14px; text-decoration: none; }
@media (max-width: 800px) { .studio-sidebar { position: static; flex-direction: row; align-items: center; height: auto; padding: 13px 16px; border-right: 0; border-bottom: 1px solid var(--line, #e7e1d8); }.studio-brand { padding: 0; }.studio-project-card, .studio-nav, .studio-tip-card, .studio-profile > span:nth-child(2), .studio-profile a { display: none; }.studio-profile { margin: 0 0 0 auto; padding: 0; border: 0; } }
.studio-profile-user { display: flex; flex-direction: column; gap: 2px; margin-left: 0 !important; color: inherit !important; text-decoration: none; }
.studio-profile button { margin-left: auto; padding: 0; color: #8b857d; background: transparent; border: 0; font-size: 14px; }
.studio-profile button:hover { color: var(--ink, #24221f); }
@media (max-width: 800px) { .studio-profile-user, .studio-profile button { display: none; } }
</style>
