import type { CustomerAnnouncementUserState } from '~/types/announcement'

export interface StoredAnnouncementState {
  state: CustomerAnnouncementUserState
  lastSeenAt: string
  dismissedAt?: string
  acknowledgedAt?: string
  synced?: boolean
}

type AnnouncementStateOwner = number | null

const LOCAL_STATE_PREFIX = 'shopmall:announcement:state:v2:'
const LEGACY_ANONYMOUS_STATE_KEY = 'shopmall:announcement:state:v1'
const SESSION_STATE_PREFIX = 'shopmall:announcement:session:v2:'

function ownerScope(userId: AnnouncementStateOwner) {
  return userId === null ? 'anonymous' : `user:${userId}`
}

function stateKey(userId: AnnouncementStateOwner) {
  return `${LOCAL_STATE_PREFIX}${ownerScope(userId)}`
}

function sessionKey(id: number, userId: AnnouncementStateOwner) {
  return `${SESSION_STATE_PREFIX}${ownerScope(userId)}:${id}`
}

export function useAnnouncementClientState() {
  const states = useState<Record<string, StoredAnnouncementState>>('announcement-client-state', () => ({}))
  const loadedOwner = useState<string | null>('announcement-client-state-loaded-owner', () => null)

  function stateRank(state: CustomerAnnouncementUserState) {
    return {
      SEEN: 1,
      DISMISSED: 2,
      ACKNOWLEDGED: 3,
    }[state]
  }

  function load(userId: AnnouncementStateOwner) {
    if (import.meta.server) return
    const scope = ownerScope(userId)
    if (loadedOwner.value === scope) return

    loadedOwner.value = scope
    states.value = {}
    try {
      let stored = window.localStorage.getItem(stateKey(userId))
      if (userId === null && !stored) {
        stored = window.localStorage.getItem(LEGACY_ANONYMOUS_STATE_KEY)
        if (stored) {
          window.localStorage.setItem(stateKey(null), stored)
          window.localStorage.removeItem(LEGACY_ANONYMOUS_STATE_KEY)
        }
      }
      if (!stored) return
      const parsed = JSON.parse(stored) as Record<string, StoredAnnouncementState>
      if (parsed && typeof parsed === 'object') states.value = parsed
    } catch {
      states.value = {}
    }
  }

  function persist(userId: AnnouncementStateOwner) {
    if (import.meta.server) return
    try {
      window.localStorage.setItem(stateKey(userId), JSON.stringify(states.value))
    } catch {
      // 浏览器存储不可用时仍允许公告正常展示。
    }
  }

  function record(
    id: number,
    state: CustomerAnnouncementUserState,
    userId: AnnouncementStateOwner,
  ): StoredAnnouncementState {
    const now = new Date().toISOString()
    if (import.meta.server) return { state, lastSeenAt: now, synced: false }
    load(userId)
    const key = String(id)
    const existing = states.value[key]
    const preferredState = existing && stateRank(existing.state) > stateRank(state) ? existing.state : state

    const recordedState: StoredAnnouncementState = {
      ...existing,
      state: preferredState,
      lastSeenAt: now,
      synced: false,
      ...(state === 'DISMISSED' ? { dismissedAt: now } : {}),
      ...(state === 'ACKNOWLEDGED' ? { acknowledgedAt: now } : {}),
    }
    states.value = { ...states.value, [key]: recordedState }
    persist(userId)
    return recordedState
  }

  function markSynced(
    id: number,
    expectedState: CustomerAnnouncementUserState,
    expectedLastSeenAt: string,
    userId: AnnouncementStateOwner,
  ) {
    load(userId)
    const key = String(id)
    const existing = states.value[key]
    if (!existing || existing.state !== expectedState || existing.lastSeenAt !== expectedLastSeenAt) return

    states.value = { ...states.value, [key]: { ...existing, synced: true } }
    persist(userId)
  }

  function get(id: number, userId: AnnouncementStateOwner) {
    load(userId)
    return states.value[String(id)]
  }

  function pendingEntries(userId: AnnouncementStateOwner) {
    load(userId)
    return Object.entries(states.value).filter(([, state]) => !state.synced)
  }

  function wasShownThisSession(id: number, userId: AnnouncementStateOwner) {
    if (import.meta.server) return false
    try {
      return window.sessionStorage.getItem(sessionKey(id, userId)) === '1'
    } catch {
      return false
    }
  }

  function rememberShownThisSession(id: number, userId: AnnouncementStateOwner) {
    if (import.meta.server) return
    try {
      window.sessionStorage.setItem(sessionKey(id, userId), '1')
    } catch {
      // 会话存储不可用时，本次页面仍只会主动展示一个弹窗。
    }
  }

  function claimAnonymousStates(userId: number) {
    if (import.meta.server) return
    load(null)
    const anonymousStates = states.value
    if (Object.keys(anonymousStates).length === 0) {
      load(userId)
      return
    }

    let userStates: Record<string, StoredAnnouncementState> = {}
    try {
      const stored = window.localStorage.getItem(stateKey(userId))
      if (stored) {
        const parsed = JSON.parse(stored) as Record<string, StoredAnnouncementState>
        if (parsed && typeof parsed === 'object') userStates = parsed
      }
    } catch {
      userStates = {}
    }

    const merged = { ...userStates }
    for (const [id, anonymousState] of Object.entries(anonymousStates)) {
      const existing = merged[id]
      const state = existing && stateRank(existing.state) > stateRank(anonymousState.state)
        ? existing.state
        : anonymousState.state
      merged[id] = {
        ...existing,
        ...anonymousState,
        state,
        lastSeenAt: existing && existing.lastSeenAt > anonymousState.lastSeenAt
          ? existing.lastSeenAt
          : anonymousState.lastSeenAt,
        synced: false,
      }
    }

    try {
      window.localStorage.setItem(stateKey(userId), JSON.stringify(merged))
      window.localStorage.removeItem(stateKey(null))
    } catch {
      // 无法迁移时不阻塞当前已登录用户继续使用公告功能。
    }
    loadedOwner.value = null
    states.value = {}
    load(userId)
  }

  return {
    load,
    get,
    record,
    markSynced,
    pendingEntries,
    wasShownThisSession,
    rememberShownThisSession,
    claimAnonymousStates,
  }
}
