export type CustomerAnnouncementType = 'GENERAL' | 'IMPORTANT' | 'MAINTENANCE' | 'PROMOTION'
export type CustomerAnnouncementAutoShowMode = 'ONCE_PER_ANNOUNCEMENT' | 'ONCE_PER_BROWSER_SESSION' | 'COOLDOWN' | 'EVERY_LOAD'
export type CustomerAnnouncementUserState = 'SEEN' | 'DISMISSED' | 'ACKNOWLEDGED'

export interface CustomerAnnouncementSummary {
  id: number
  title: string
  summary: string
  type: CustomerAnnouncementType
  priority: number
  auto_show_enabled: boolean
  auto_show_mode: CustomerAnnouncementAutoShowMode
  auto_show_cooldown_hours: number | null
  action_url: string | null
  effective_from: string
  effective_until: string | null
  published_at: string | null
  is_read: boolean
  user_state: CustomerAnnouncementUserState | null
}

export interface CustomerAutoShowAnnouncement {
  id: number
  title: string
  summary: string
  content: string
  type: CustomerAnnouncementType
  priority: number
  auto_show_mode: CustomerAnnouncementAutoShowMode
  auto_show_cooldown_hours: number | null
  action_url: string | null
  effective_from: string
  effective_until: string | null
  published_at: string | null
  is_read: boolean
  user_state: CustomerAnnouncementUserState | null
}

export interface CustomerAnnouncementDetail {
  id: number
  title: string
  summary: string
  content: string
  type: CustomerAnnouncementType
  priority: number
  action_url: string | null
  effective_from: string
  effective_until: string | null
  published_at: string | null
  is_read: boolean
  user_state: CustomerAnnouncementUserState | null
}

export interface CustomerAnnouncementHistoryItem {
  id: number
  title: string
  summary: string
  type: CustomerAnnouncementType
  priority: number
  action_url: string | null
  effective_from: string
  effective_until: string | null
  published_at: string | null
  is_read: boolean
}

export interface CustomerAnnouncementHistoryQuery {
  page: number
  size: number
  type?: CustomerAnnouncementType
  year?: number
  keyword?: string
}

export interface CustomerAnnouncementStateResponse {
  id: number
  state: CustomerAnnouncementUserState
  first_seen_at: string
  last_seen_at: string
  dismissed_at: string | null
  acknowledged_at: string | null
}

export const CUSTOMER_ANNOUNCEMENT_TYPE_OPTIONS: Array<{ label: string, value: CustomerAnnouncementType }> = [
  { label: 'General', value: 'GENERAL' },
  { label: 'Important', value: 'IMPORTANT' },
  { label: 'Maintenance', value: 'MAINTENANCE' },
  { label: 'Promotion', value: 'PROMOTION' },
]
