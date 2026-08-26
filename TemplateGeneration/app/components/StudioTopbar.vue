<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = withDefaults(defineProps<{
  projectId?: string
  actionLabel?: string
  actionTo?: string
}>(), {
  projectId: '',
  actionLabel: '',
  actionTo: '',
})

const route = useRoute()
const { projects, selectedProject, selectProject, loadProjects } = useProjects()
const menuOpen = ref(false)

await loadProjects().catch(() => undefined)

watch(() => props.projectId, (id) => {
  if (id && projects.value.some((project) => project.id === id)) selectProject(id)
}, { immediate: true })

const currentProject = computed(() => {
  // The shared selection is authoritative. The route prop is only a
  // bootstrap fallback for direct links before the project list hydrates.
  return selectedProject.value
    ?? (props.projectId ? projects.value.find((project) => project.id === props.projectId) : null)
})

function switchProject(id: string) {
  selectProject(id)
  menuOpen.value = false
  if (route.path.startsWith('/projects/')) {
    const section = route.path.split('/')[3]
    if (section && ['assets', 'workflows', 'generate', 'results'].includes(section)) {
      void navigateTo(`/projects/${encodeURIComponent(id)}/${section}`)
      return
    }
  }
  // Dashboard and the other global pages keep their current view. Only the
  // project-scoped routes above need navigation; API management, account,
  // team and review remain available while the global project context changes.
}

function createProject() {
  menuOpen.value = false
  void navigateTo('/projects?create=1')
}
</script>

<template>
  <header class="studio-topbar">
    <div class="studio-top-actions">
      <slot />
      <NuxtLink v-if="actionLabel && actionTo" class="studio-top-action" :to="actionTo">{{ actionLabel }} <span>＋</span></NuxtLink>
      <div class="studio-project-switcher">
        <span>当前工程</span>
        <button type="button" :aria-expanded="menuOpen" aria-haspopup="listbox" @click="menuOpen = !menuOpen">
          {{ currentProject?.name || '选择工程' }} <span class="studio-chevron">⌄</span>
        </button>
        <div v-if="menuOpen" class="studio-project-menu" role="listbox" @mouseleave="menuOpen = false">
          <button v-for="project in projects" :key="project.id" type="button" role="option" :aria-selected="project.id === currentProject?.id" @click="switchProject(project.id)">
            <span>{{ project.name }}</span><small>{{ project.season }}</small>
          </button>
          <button class="studio-create-project" type="button" @click="createProject">＋ 新建工程</button>
        </div>
      </div>
    </div>
  </header>
</template>

<style scoped>
.studio-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: flex-end; min-height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line, #e7e1d8); backdrop-filter: blur(12px); }
.studio-top-actions { display: flex; align-items: center; justify-content: flex-end; gap: 12px; }
.studio-project-switcher { position: relative; display: flex; flex-direction: column; gap: 2px; min-width: 150px; }
.studio-project-switcher > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }
.studio-project-switcher > button { max-width: min(300px, 45vw); overflow: hidden; padding: 0; color: var(--ink, #24221f); text-align: left; text-overflow: ellipsis; white-space: nowrap; background: transparent; border: 0; font: 500 12px Georgia, serif; }
.studio-chevron { margin-left: 5px; color: #898178; }
.studio-project-menu { position: absolute; top: 46px; right: -10px; z-index: 30; width: 230px; max-height: 330px; overflow: auto; padding: 6px; background: #fff; border: 1px solid var(--line, #e7e1d8); border-radius: 9px; box-shadow: 0 12px 28px #25231f1c; }
.studio-project-menu button { display: flex; flex-direction: column; gap: 3px; width: 100%; padding: 9px; color: var(--ink, #24221f); text-align: left; background: transparent; border: 0; border-radius: 6px; }
.studio-project-menu button:hover, .studio-project-menu button[aria-selected='true'] { background: #f2eee7; }
.studio-project-menu span { font-size: 11px; }.studio-project-menu small { color: #9a9288; font-size: 8px; }.studio-project-menu .studio-create-project { display: block; margin-top: 4px; padding-top: 11px; color: #8a7659; border-top: 1px solid #eee9e2; font-size: 10px; }
.studio-top-action { display: flex; align-items: center; gap: 7px; padding: 10px 14px; color: #fff; background: #1d1c19; border-radius: 8px; font-size: 10px; text-decoration: none; }.studio-top-action span { font-size: 14px; }
@media (max-width: 700px) { .studio-topbar { min-height: 58px; padding: 0 18px; }.studio-project-switcher > button { max-width: 210px; }.studio-top-action { padding: 9px; font-size: 0; }.studio-top-action span { font-size: 16px; } }
</style>
