import { computed, type Ref, watch } from 'vue'

/**
 * Resolves a route's project id into the shared workspace context.
 *
 * Nuxt keeps dynamic page components alive when only `:projectId` changes.
 * Keeping this synchronization in one place means every project page reacts
 * to the same global selection, while direct links still establish the
 * correct initial project before the page's first data request.
 */
export function useProjectWorkspace(routeProjectId: Ref<string>) {
  const { projects, selectedProjectId, selectProject } = useProjects()

  // A dynamic route is an explicit workspace choice. Set it immediately so
  // the page's first SSR/client request uses the URL project even before the
  // asynchronous project list has hydrated.
  watch(routeProjectId, (id) => {
    if (id && id !== selectedProjectId.value) selectProject(id)
  }, { immediate: true })

  // `StudioSidebar` and `StudioTopbar` load the project list asynchronously.
  // During that request `loadProjects()` may restore the last project from
  // localStorage. A dynamic route is an explicit workspace context, so once
  // the list arrives, re-assert its id to prevent a direct `/projects/:id`
  // link from being silently switched to the persisted project.
  watch(projects, (availableProjects) => {
    const id = routeProjectId.value
    if (id && availableProjects.some((project) => project.id === id) && selectedProjectId.value !== id) {
      selectProject(id)
    }
  }, { immediate: true })

  const activeProjectId = computed(() => selectedProjectId.value || routeProjectId.value || 'prj_noir')

  return { activeProjectId, selectedProjectId, selectProject }
}
