export type AdminUserRole = 'CUSTOMER' | 'ADMIN'
export type AdminUserStatus = 'ACTIVE' | 'INACTIVE' | 'DELETED'

export interface AdminUserListQuery {
  page: number
  size: number
  keyword?: string
  role?: AdminUserRole
  status?: AdminUserStatus
  enabled?: boolean
}

export interface AdminUserListItem {
  id: number
  email: string
  username: string
  first_name: string
  last_name: string
  role: AdminUserRole
  status: AdminUserStatus
  enabled: boolean
  email_verified: boolean
  marketing_consent: boolean
  last_login_at: string | null
  created_at: string | null
  updated_at: string | null
}

export interface AdminUserDetail extends AdminUserListItem {
  phone: string | null
  avatar: string | null
  locale: string | null
  currency: string | null
  birthday: string | null
  last_login_ip: string | null
}

export interface AdminUserPage {
  list: AdminUserListItem[]
  pagination: {
    page: number
    size: number
    total_items: number
    total_pages: number
  }
}

export interface AdminUserMutation {
  email: string
  username: string
  password?: string
  first_name: string
  last_name: string
  phone?: string
  avatar?: string
  locale?: string
  currency?: string
  birthday?: string
  email_verified: boolean
  marketing_consent: boolean
  role: AdminUserRole
  enabled: boolean
  status: AdminUserStatus
}

export interface AdminUserBatchMutation {
  ids: number[]
  role?: AdminUserRole
  enabled?: boolean
  status?: AdminUserStatus
  email_verified?: boolean
  marketing_consent?: boolean
}

export interface AdminUserBatchResponse {
  list: Array<{
    id: number
    username: string
    role: AdminUserRole
    status: AdminUserStatus
    enabled: boolean
    email_verified: boolean
    marketing_consent: boolean
    updated_at: string | null
  }>
  updated: number
}
