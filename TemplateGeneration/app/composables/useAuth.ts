export type AuthUser = {
  id: number
  username: string
}

type SessionResponse = {
  authenticated: boolean
  user: AuthUser | null
}

export function useAuthUser() {
  const user = useState<AuthUser | null>('auth-user', () => null)
  const loaded = useState('auth-user-loaded', () => false)

  async function refresh() {
    if (loaded.value) return user.value

    const requestFetch = import.meta.server ? useRequestFetch() : $fetch
    const response = await requestFetch<SessionResponse>('/api/auth/session').catch(() => ({authenticated: false, user: null}))
    user.value = response.user
    loaded.value = true
    return user.value
  }

  async function logout() {
    await $fetch('/api/auth/logout', {method: 'POST'})
    user.value = null
    loaded.value = true
    await navigateTo('/login')
  }

  return {user, refresh, logout}
}
