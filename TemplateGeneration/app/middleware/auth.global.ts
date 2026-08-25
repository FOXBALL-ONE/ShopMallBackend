export default defineNuxtRouteMiddleware(async (to) => {
  if (to.path.startsWith('/api/')) return

  const requestFetch = import.meta.server ? useRequestFetch() : $fetch
  const session = await requestFetch<{authenticated: boolean}>('/api/auth/session').catch(() => ({authenticated: false}))

  if (to.path === '/login') {
    if (session.authenticated) return navigateTo('/dashboard')
    return
  }

  if (!session.authenticated) {
    return navigateTo({path: '/login', query: {redirect: to.fullPath}})
  }
})
