const ACCESS_TOKEN_COOKIE = 'chat_auth_token'
const CUSTOMER_ID_COOKIE = 'pelissa_customer_id'
const SESSION_TTL_SECONDS = 60 * 60 * 24 * 7

type JwtPayload = {
  sub?: unknown
}

function decodeBase64Url(value: string) {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/')
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  return globalThis.atob(padded)
}

function readUserIdFromToken(token: string | null | undefined) {
  if (!token) return null

  try {
    const payload = token.replace(/^bearer\s+/i, '').split('.')[1]
    if (!payload) return null
    const claims = JSON.parse(decodeBase64Url(payload)) as JwtPayload
    const userId = Number(claims.sub)
    return Number.isSafeInteger(userId) && userId > 0 ? userId : null
  } catch {
    return null
  }
}

export function useCustomerSession() {
  const accessToken = useCookie<string | null>(ACCESS_TOKEN_COOKIE, {
    maxAge: SESSION_TTL_SECONDS,
    sameSite: 'lax',
    path: '/'
  })
  const storedUserId = useCookie<number | null>(CUSTOMER_ID_COOKIE, {
    maxAge: SESSION_TTL_SECONDS,
    sameSite: 'lax',
    path: '/'
  })
  const route = useRoute()
  const router = useRouter()

  const userId = computed(() => storedUserId.value || readUserIdFromToken(accessToken.value))
  const isAuthenticated = computed(() => Boolean(accessToken.value && userId.value))

  if (import.meta.client) {
    watch(accessToken, token => {
      if (!token) storedUserId.value = null
    })
  }

  async function requireSignIn(redirectPath = route.fullPath) {
    if (isAuthenticated.value && userId.value) return userId.value

    await router.replace({
      path: '/login',
      query: redirectPath && redirectPath !== '/login' ? { redirect: redirectPath } : undefined
    })
    return null
  }

  function rememberUserId(id: number) {
    storedUserId.value = id
  }

  function clear() {
    storedUserId.value = null
    accessToken.value = null
  }

  return {
    accessToken,
    userId,
    isAuthenticated,
    requireSignIn,
    rememberUserId,
    clear
  }
}
