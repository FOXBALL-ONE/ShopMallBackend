import type {
  AdminAnnouncementDetail,
  AnnouncementAuditLogResponse,
  AnnouncementFormInput,
  AnnouncementListQuery,
  AnnouncementListResponse,
  AnnouncementMutationResponse,
} from '~/types/announcement'

export const ANNOUNCEMENT_TYPE_OPTIONS = [
  { label: '常规公告', value: 'GENERAL' },
  { label: '重要公告', value: 'IMPORTANT' },
  { label: '维护公告', value: 'MAINTENANCE' },
  { label: '促销公告', value: 'PROMOTION' },
]

export const ANNOUNCEMENT_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已排期', value: 'SCHEDULED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已下线', value: 'OFFLINE' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '已归档', value: 'ARCHIVED' },
]

export const ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS = [
  { label: '每条公告仅一次', value: 'ONCE_PER_ANNOUNCEMENT' },
  { label: '每个浏览器会话一次', value: 'ONCE_PER_BROWSER_SESSION' },
  { label: '按冷却时间重复', value: 'COOLDOWN' },
  { label: '每次加载', value: 'EVERY_LOAD' },
]

export const useAnnouncementApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put } = useHttp(adminApiBase)

  function formData(input: AnnouncementFormInput, expectedVersion?: number) {
    const data = new FormData()
    data.append('title', input.title)
    data.append('summary', input.summary)
    data.append('content', input.content)
    data.append('type', input.type)
    data.append('priority', String(input.priority))
    data.append('public_history', String(input.publicHistory))
    data.append('auto_show_enabled', String(input.autoShowEnabled))
    data.append('auto_show_mode', input.autoShowMode)
    if (input.autoShowCooldownHours !== null) {
      data.append('auto_show_cooldown_hours', String(input.autoShowCooldownHours))
    }
    if (input.actionUrl.trim()) data.append('action_url', input.actionUrl.trim())
    data.append('effective_from', input.effectiveFrom)
    if (input.effectiveUntil) data.append('effective_until', input.effectiveUntil)
    if (expectedVersion !== undefined) data.append('expected_version', String(expectedVersion))
    return data
  }

  return {
    list(query: AnnouncementListQuery) {
      return get<AnnouncementListResponse>('/announcements', { ...query })
    },
    getOne(id: number) {
      return get<AdminAnnouncementDetail>(`/announcements/${id}`)
    },
    create(input: AnnouncementFormInput) {
      return post<AdminAnnouncementDetail, FormData>('/announcements', formData(input), { payloadMode: 'json' })
    },
    update(id: number, input: AnnouncementFormInput, expectedVersion: number) {
      return put<AdminAnnouncementDetail, FormData>(`/announcements/${id}`, formData(input, expectedVersion), { payloadMode: 'json' })
    },
    publish(id: number, expectedVersion: number) {
      return post<AnnouncementMutationResponse>(`/announcements/${id}/publish`, { expected_version: expectedVersion })
    },
    offline(id: number, expectedVersion: number, reason: string) {
      return post<AnnouncementMutationResponse>(`/announcements/${id}/offline`, {
        expected_version: expectedVersion,
        reason,
      })
    },
    archive(id: number, expectedVersion: number, publicHistory: boolean) {
      return post<AnnouncementMutationResponse>(`/announcements/${id}/archive`, {
        expected_version: expectedVersion,
        public_history: publicHistory,
      })
    },
    copy(id: number, expectedVersion: number) {
      return post<AnnouncementMutationResponse>(`/announcements/${id}/copy`, { expected_version: expectedVersion })
    },
    auditLogs(id: number, page = 0, size = 50) {
      return get<AnnouncementAuditLogResponse>(`/announcements/${id}/audit-logs`, { page, size })
    },
  }
}
