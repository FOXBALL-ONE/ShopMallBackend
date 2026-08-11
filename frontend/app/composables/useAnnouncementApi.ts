import type {
  CustomerAnnouncementDetail,
  CustomerAnnouncementHistoryItem,
  CustomerAnnouncementHistoryQuery,
  CustomerAnnouncementStateResponse,
  CustomerAnnouncementSummary,
  CustomerAnnouncementUserState,
  CustomerAutoShowAnnouncement,
} from '~/types/announcement'

interface CurrentAnnouncementsResponse {
  items: CustomerAnnouncementSummary[]
}

interface AutoShowAnnouncementResponse {
  announcement: CustomerAutoShowAnnouncement | null
}

interface HistoryAnnouncementsResponse {
  items: CustomerAnnouncementHistoryItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export function useAnnouncementApi() {
  const http = useHttp()

  return {
    getCurrent(includeRead = true, limit = 20) {
      return http.get<CurrentAnnouncementsResponse>('/announcements/current', {
        include_read: includeRead,
        limit,
      })
    },

    async getAutoShow(excludedIds: number[] = []) {
      const response = await http.get<AutoShowAnnouncementResponse>('/announcements/auto-show', {
        ...(excludedIds.length ? { excluded_ids: excludedIds } : {}),
      })
      return response.announcement
    },

    getHistory(query: CustomerAnnouncementHistoryQuery) {
      return http.get<HistoryAnnouncementsResponse>('/announcements/history', { ...query })
    },

    getOne(id: number) {
      return http.get<CustomerAnnouncementDetail>(`/announcements/${encodeURIComponent(id)}`)
    },

    recordState(id: number, state: CustomerAnnouncementUserState) {
      return http.post<CustomerAnnouncementStateResponse, { state: CustomerAnnouncementUserState }>(
        `/announcements/${encodeURIComponent(id)}/state`,
        { state },
      )
    },
  }
}
