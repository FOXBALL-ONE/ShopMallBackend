import type {
  CloseCustomerSupportTicketResponse,
  CreateCustomerSupportTicketInput,
  CreateCustomerSupportTicketResponse,
  CustomerSupportTicketDetail,
  CustomerSupportTicketListQuery,
  CustomerSupportTicketListResponse,
  CustomerSupportTicketOptions,
  SendCustomerSupportTicketMessageResponse
} from '~/types/support-ticket'

function createIdempotencyKey(operation: string) {
  const randomPart = globalThis.crypto?.randomUUID?.()
    ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`
  return `customer-ticket-${operation}-${randomPart}`
}

export function useSupportTicketApi() {
  const http = useHttp()

  return {
    getOptions() {
      return http.get<CustomerSupportTicketOptions>('/support-tickets/options')
    },

    listTickets(query: CustomerSupportTicketListQuery) {
      return http.get<CustomerSupportTicketListResponse>('/support-tickets', { ...query })
    },

    getTicket(ticketId: number, messagePage = 1, messageSize = 50) {
      return http.get<CustomerSupportTicketDetail>(`/support-tickets/${ticketId}`, {
        message_page: messagePage,
        message_size: messageSize
      })
    },

    createTicket(input: CreateCustomerSupportTicketInput) {
      return http.post<CreateCustomerSupportTicketResponse, CreateCustomerSupportTicketInput>(
        '/support-tickets',
        input,
        {
          headers: {
            'Idempotency-Key': createIdempotencyKey('create')
          }
        }
      )
    },

    sendTicketMessage(ticketId: number, content: string | null, files: File[]) {
      const formData = new FormData()
      if (content) formData.append('content', content)
      files.forEach(file => formData.append('files', file, file.name))

      return http.post<SendCustomerSupportTicketMessageResponse, FormData>(
        `/support-tickets/${ticketId}/messages`,
        formData,
        {
          payloadMode: 'json',
          headers: {
            'Idempotency-Key': createIdempotencyKey('message')
          }
        }
      )
    },

    closeTicket(ticketId: number) {
      return http.post<CloseCustomerSupportTicketResponse>(`/support-tickets/${ticketId}/close`)
    }
  }
}
