import { computed, onMounted, watch } from 'vue'

export type StudioProject = {
  id: string
  code: string
  name: string
  season: string
  status: 'ACTIVE' | 'ARCHIVED'
  assets?: number
  workflows?: number
  tasks?: number
  createdAt?: string
  updatedAt?: string
}

/** Shared project workspace state. Generation, workflows, results and assets are always addressed by project id. */
export function useProjects() {
  const route = useRoute()
  const projects = useState<StudioProject[]>('studio-projects', () => [])
  const selectedProjectId = useState<string>('studio-current-project', () => 'prj_noir')
  const loading = useState<boolean>('studio-projects-loading', () => false)
  const error = useState<string>('studio-projects-error', () => '')
  const loaded = useState<boolean>('studio-projects-loaded', () => false)
  // Forward the incoming SSR request (including the auth cookie) when the
  // project list is loaded during server rendering. The browser still uses
  // the regular relative $fetch client.
  const requestFetch = import.meta.server ? useRequestFetch() : $fetch

  const selectedProject = computed(() => projects.value.find((project) => project.id === selectedProjectId.value) ?? projects.value[0] ?? null)

  function selectProject(projectId: string) {
    if (!projectId) return
    selectedProjectId.value = projectId
    if (import.meta.client) localStorage.setItem('studio-current-project', projectId)
  }

  async function loadProjects(force = false) {
    if (loaded.value && !force) return projects.value
    loading.value = true
    error.value = ''
    try {
      const response = await requestFetch<{ projects: StudioProject[] }>('/api/projects')
      projects.value = response.projects ?? []
      const stored = import.meta.client ? localStorage.getItem('studio-current-project') : null
      const isActive = (id: string | null | undefined) => Boolean(id && projects.value.some((project) => project.id === id && project.status === 'ACTIVE'))
      const exists = (id: string | null | undefined) => Boolean(id && projects.value.some((project) => project.id === id))
      // A direct project URL is an explicit workspace choice. Prefer it over
      // the persisted browser selection while hydrating so `/projects/B/...`
      // cannot briefly (or permanently) switch back to project A.
      const routeProjectId = typeof route.params.projectId === 'string' ? route.params.projectId : null
      const preferred = exists(routeProjectId)
        ? routeProjectId
        : (isActive(stored) ? stored : (isActive(selectedProjectId.value) ? selectedProjectId.value : projects.value.find((project) => project.status === 'ACTIVE')?.id))
      selectProject(preferred ?? projects.value[0]?.id ?? '')
      loaded.value = true
      return projects.value
    } catch (cause: unknown) {
      const request = cause as { data?: { statusMessage?: string; message?: string }; statusMessage?: string; message?: string }
      error.value = request.data?.statusMessage ?? request.data?.message ?? request.statusMessage ?? request.message ?? '项目加载失败。'
      throw cause
    } finally {
      loading.value = false
    }
  }

  async function createProject(input: { name: string; season?: string; id?: string }) {
    const response = await $fetch<{ project: StudioProject }>('/api/projects', { method: 'POST', body: input })
    projects.value = [response.project, ...projects.value.filter((project) => project.id !== response.project.id)]
    selectProject(response.project.id)
    return response.project
  }

  async function updateProject(projectId: string, input: { name?: string; season?: string; status?: 'ACTIVE' | 'ARCHIVED' }) {
    const response = await $fetch<{ project: StudioProject }>(`/api/projects/${encodeURIComponent(projectId)}`, { method: 'PATCH', body: input })
    projects.value = projects.value.map((project) => project.id === projectId ? response.project : project)
    if (response.project.status === 'ARCHIVED' && selectedProjectId.value === projectId) {
      const next = projects.value.find((project) => project.status === 'ACTIVE')
      selectProject(next?.id ?? '')
    }
    return response.project
  }

  async function deleteProject(projectId: string) {
    const response = await $fetch<{ project: StudioProject }>(`/api/projects/${encodeURIComponent(projectId)}`, { method: 'DELETE' })
    projects.value = projects.value.map((project) => project.id === projectId ? response.project : project)
    if (selectedProjectId.value === projectId) {
      const next = projects.value.find((project) => project.status === 'ACTIVE')
      selectProject(next?.id ?? '')
    }
    return response.project
  }

  onMounted(() => { void loadProjects() })
  watch(selectedProjectId, (id) => { if (import.meta.client && id) localStorage.setItem('studio-current-project', id) })

  return { projects, selectedProjectId, selectedProject, loading, error, loadProjects, selectProject, createProject, updateProject, deleteProject }
}
