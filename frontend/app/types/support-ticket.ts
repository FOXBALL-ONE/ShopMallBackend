export type SupportServiceType = 'PRE_SALES' | 'AFTER_SALES'
export type SupportTicketPriority = 'LOW' | 'MEDIUM' | 'HIGH'
export type SupportTicketStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'
export type SupportTicketMessageSender = 'CUSTOMER' | 'ADMIN'

export interface SupportTicketOption<TValue extends string = string> {
  value: TValue
  label: string
}

export interface CustomerSupportTicketOptions {
  service_types: SupportTicketOption<SupportServiceType>[]
  priorities: SupportTicketOption<SupportTicketPriority>[]
  default_priority: SupportTicketPriority
}

export interface CustomerSupportTicketListQuery {
  page: number
  size: number
  status?: SupportTicketStatus
  service_type?: SupportServiceType
  priority?: SupportTicketPriority
}

export interface CustomerSupportTicketSummary {
  id: number
  service_type: SupportServiceType
  priority: SupportTicketPriority
  order_no: string | null
  subject: string
  status: SupportTicketStatus
  admin_reply: string | null
  created_at: string | null
  updated_at: string | null
}

export interface CustomerSupportTicketListResponse {
  list: CustomerSupportTicketSummary[]
  pagination: {
    count: number
  }
}

export interface CustomerSupportTicketAttachment {
  id: number
  file_id: string
  file_name: string
  content_type: string | null
  size_bytes: number
  download_url: string
  download_expires_at: string
}

export interface CustomerSupportTicketMessage {
  id: number
  sender_type: SupportTicketMessageSender
  content: string | null
  attachments: CustomerSupportTicketAttachment[]
  created_at: string | null
}

export interface CustomerSupportTicketDetail extends CustomerSupportTicketSummary {
  customer_id: number
  content: string
  replied_at: string | null
  resolved_at: string | null
  closed_at: string | null
  messages: CustomerSupportTicketMessage[]
  message_pagination: {
    count: number
    total: number
  }
}

export interface CreateCustomerSupportTicketInput {
  service_type: SupportServiceType
  priority: SupportTicketPriority
  order_no?: string
  subject: string
  content: string
}

export type CreateCustomerSupportTicketResponse = Omit<CustomerSupportTicketDetail, 'messages' | 'message_pagination'>

export interface SendCustomerSupportTicketMessageResponse extends CustomerSupportTicketMessage {
  ticket_id: number
}

export interface CloseCustomerSupportTicketResponse {
  id: number
  status: SupportTicketStatus
  closed_at: string | null
  updated_at: string | null
}
