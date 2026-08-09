export default defineNuxtRouteMiddleware(to => {
  const session = useCustomerSession()

  if (session.isAuthenticated.value) return

  return navigateTo({
    path: '/login',
    query: to.fullPath && to.fullPath !== '/login' ? { redirect: to.fullPath } : undefined
  })
})
