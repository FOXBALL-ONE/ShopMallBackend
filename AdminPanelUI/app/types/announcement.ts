export type AnnouncementType = 'GENERAL' | 'IMPORTANT' | 'MAINTENANCE' | 'PROMOTION'
export type AnnouncementStatus = 'DRAFT' | 'SCHEDULED' | 'PUBLISHED' | 'OFFLINE' | 'EXPIRED' | 'ARCHIVED'
export type AnnouncementAutoShowMode = 'ONCE_PER_ANNOUNCEMENT' | 'ONCE_PER_BROWSER_SESSION' | 'COOLDOWN' | 'EVERY_LOAD'
export type AnnouncementSortBy = 'PRIORITY' | 'EFFECTIVE_FROM' | 'PUBLISHED_AT' | 'CREATED_AT' | 'UPDATED_AT'
export type AnnouncementSortDirection = 'ASC' | 'DESC'

export interface AnnouncementListQuery {
  page: number
  size: number
  keyword?: string
  status?: AnnouncementStatus
  type?: AnnouncementType
  priority_min?: number
  priority_max?: number
  auto_show_enabled?: boolean
  effective_from_start?: string
  effective_from_end?: string
  sort_by?: AnnouncementSortBy
  sort_direction?: AnnouncementSortDirection
}

export interface AdminAnnouncementListItem {
  id: number
  version: number
  title: string
  summary: string
  type: AnnouncementType
  priority: number
  status: AnnouncementStatus
  public_history: boolean
  auto_show_enabled: boolean
  auto_show_mode: AnnouncementAutoShowMode
  auto_show_cooldown_hours: number | null
  effective_from: string
  effective_until: string | null
  published_at: string | null
  updated_at: string | null
}

export interface AdminAnnouncementDetail extends AdminAnnouncementListItem {
  content: string
  channel: 'CUSTOMER_WEB'
  action_url: string | null
  created_by: number
  updated_by: number
  archived_at: string | null
  created_at: string | null
}

export interface AnnouncementListResponse {
  items: AdminAnnouncementListItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface AnnouncementFormInput {
  title: string
  summary: string
  content: string
  type: AnnouncementType
  priority: number
  publicHistory: boolean
  autoShowEnabled: boolean
  autoShowMode: AnnouncementAutoShowMode
  autoShowCooldownHours: number | null
  actionUrl: string
  effectiveFrom: string
  effectiveUntil: string | null
}

export interface AnnouncementMutationResponse {
  id: number
  version: number
  title?: string
  status: AnnouncementStatus
  effective_from?: string
  effective_until?: string | null
  published_at?: string | null
  public_history?: boolean
  archived_at?: string | null
  created_at?: string | null
  updated_at?: string | null
}

export interface AnnouncementAuditLog {
  id: number
  announcement_id: number
  operator_id: number
  action: 'CREATED' | 'UPDATED' | 'PUBLISHED' | 'SYSTEM_PUBLISHED' | 'SYSTEM_EXPIRED' | 'OFFLINE' | 'ARCHIVED' | 'COPIED'
  before_snapshot: string | null
  after_snapshot: string
  reason: string | null
  created_at: string | null
}

export interface AnnouncementAuditLogResponse {
  items: AnnouncementAuditLog[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}
