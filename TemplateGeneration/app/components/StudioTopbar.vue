<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { studioThemes, type StudioThemeId, useTheme } from '../composables/useTheme'

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
const themeMenuOpen = ref(false)
const { selectedTheme, setTheme } = useTheme()

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

function switchTheme(id: StudioThemeId) {
  setTheme(id)
  themeMenuOpen.value = false
}
</script>

<template>
  <header class="studio-topbar">
    <div class="studio-top-actions">
      <slot />
      <NuxtLink v-if="actionLabel && actionTo" class="studio-top-action" :to="actionTo">{{ actionLabel }} <span>＋</span></NuxtLink>
      <div class="studio-theme-switcher">
        <button type="button" class="studio-theme-trigger" :aria-expanded="themeMenuOpen" aria-haspopup="listbox" aria-label="切换主题色" @click="themeMenuOpen = !themeMenuOpen">
          <span class="studio-theme-dot" :style="{ background: studioThemes.find((theme) => theme.id === selectedTheme)?.swatch }" />
          <span class="studio-theme-trigger-label">主题</span>
          <span class="studio-chevron">⌄</span>
        </button>
        <div v-if="themeMenuOpen" class="studio-theme-menu" role="listbox" aria-label="主题配色">
          <div class="studio-theme-menu-title">主题配色</div>
          <button v-for="theme in studioThemes" :key="theme.id" type="button" role="option" :aria-selected="theme.id === selectedTheme" @click="switchTheme(theme.id)">
            <span class="studio-theme-dot" :style="{ background: theme.swatch }" />
            <span><strong>{{ theme.name }}</strong><small>{{ theme.description }}</small></span>
            <span v-if="theme.id === selectedTheme" class="studio-theme-check">✓</span>
          </button>
        </div>
      </div>
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
.studio-theme-switcher { position: relative; }
.studio-theme-trigger { display: inline-flex; align-items: center; gap: 6px; padding: 7px 8px; color: var(--ink, #24221f); background: transparent; border: 1px solid transparent; border-radius: 7px; font-size: 9px; }
.studio-theme-trigger:hover, .studio-theme-trigger[aria-expanded='true'] { background: var(--theme-accent-soft, #f2ece2); border-color: var(--theme-border, #e7e1d8); }
.studio-theme-trigger-label { color: #716c65; }
.studio-theme-dot { display: inline-block; width: 12px; height: 12px; flex: 0 0 auto; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #d9d2c9; }
.studio-theme-menu { position: absolute; top: 42px; right: -8px; z-index: 35; width: 225px; padding: 7px; background: var(--theme-surface, #fff); border: 1px solid var(--theme-border, #e7e1d8); border-radius: 10px; box-shadow: 0 14px 32px #25231f1c; }
.studio-theme-menu-title { padding: 6px 9px 7px; color: #928a81; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }
.studio-theme-menu button { display: flex; align-items: center; gap: 8px; width: 100%; padding: 8px 9px; color: var(--theme-text, #24221f); text-align: left; background: transparent; border: 0; border-radius: 7px; }
.studio-theme-menu button:hover, .studio-theme-menu button[aria-selected='true'] { background: var(--theme-accent-soft, #f2ece2); }
.studio-theme-menu button > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }
.studio-theme-menu strong { font-size: 10px; font-weight: 500; }.studio-theme-menu small { color: var(--theme-muted, #8c867d); font-size: 8px; }.studio-theme-check { margin-left: auto; color: var(--theme-accent, #a18455); font-size: 12px; }
.studio-project-switcher { position: relative; display: flex; flex-direction: column; gap: 2px; min-width: 150px; }
.studio-project-switcher > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }
.studio-project-switcher > button { max-width: min(300px, 45vw); overflow: hidden; padding: 0; color: var(--ink, #24221f); text-align: left; text-overflow: ellipsis; white-space: nowrap; background: transparent; border: 0; font: 500 12px Georgia, serif; }
.studio-chevron { margin-left: 5px; color: #898178; }
.studio-project-menu { position: absolute; top: 46px; right: -10px; z-index: 30; width: 230px; max-height: 330px; overflow: auto; padding: 6px; background: #fff; border: 1px solid var(--line, #e7e1d8); border-radius: 9px; box-shadow: 0 12px 28px #25231f1c; }
.studio-project-menu button { display: flex; flex-direction: column; gap: 3px; width: 100%; padding: 9px; color: var(--ink, #24221f); text-align: left; background: transparent; border: 0; border-radius: 6px; }
.studio-project-menu button:hover, .studio-project-menu button[aria-selected='true'] { background: #f2eee7; }
.studio-project-menu span { font-size: 11px; }.studio-project-menu small { color: #9a9288; font-size: 8px; }.studio-project-menu .studio-create-project { display: block; margin-top: 4px; padding-top: 11px; color: #8a7659; border-top: 1px solid #eee9e2; font-size: 10px; }
.studio-top-action { display: flex; align-items: center; gap: 7px; padding: 10px 14px; color: #fff; background: #1d1c19; border-radius: 8px; font-size: 10px; text-decoration: none; }.studio-top-action span { font-size: 14px; }
@media (max-width: 700px) { .studio-topbar { min-height: 58px; padding: 0 18px; }.studio-project-switcher > button { max-width: 210px; }.studio-top-action { padding: 9px; font-size: 0; }.studio-top-action span { font-size: 16px; }.studio-theme-trigger-label { display: none; }.studio-theme-menu { right: -4px; } }
</style>
