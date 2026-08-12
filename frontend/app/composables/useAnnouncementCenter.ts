import type { CustomerAnnouncementSummary } from '~/types/announcement'

const CURRENT_ANNOUNCEMENTS_TTL_MS = 60_000
let currentRequest: Promise<CustomerAnnouncementSummary[]> | null = null

export function useAnnouncementCenter() {
  const announcementApi = useAnnouncementApi()
  const currentAnnouncements = useState<CustomerAnnouncementSummary[]>('customer-current-announcements', () => [])
  const currentAnnouncementsLoadedAt = useState<number>('customer-current-announcements-loaded-at', () => 0)
  const isCurrentAnnouncementsLoading = useState<boolean>('customer-current-announcements-loading', () => false)

  const currentCount = computed(() => currentAnnouncements.value.length)
  const leadingAnnouncement = computed(() => currentAnnouncements.value[0] ?? null)

  async function refreshCurrentAnnouncements(force = false) {
    if (import.meta.server) return currentAnnouncements.value
    if (!force && currentAnnouncementsLoadedAt.value > Date.now() - CURRENT_ANNOUNCEMENTS_TTL_MS) {
      return currentAnnouncements.value
    }
    if (currentRequest) return currentRequest

    isCurrentAnnouncementsLoading.value = true
    currentRequest = announcementApi.getCurrent(true, 50)
      .then(response => {
        currentAnnouncements.value = response.items
        currentAnnouncementsLoadedAt.value = Date.now()
        return response.items
      })
      .finally(() => {
        isCurrentAnnouncementsLoading.value = false
        currentRequest = null
      })
    return currentRequest
  }

  return {
    currentAnnouncements: readonly(currentAnnouncements),
    currentCount,
    leadingAnnouncement,
    isCurrentAnnouncementsLoading: readonly(isCurrentAnnouncementsLoading),
    refreshCurrentAnnouncements,
  }
}
