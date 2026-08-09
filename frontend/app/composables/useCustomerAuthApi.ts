import type { CustomerSessionUser } from '~/types/customer-account'

export interface CustomerLoginResponse {
  access_token: string
  expires_in: number
  user_id: number
  user_info: CustomerSessionUser
}

export interface CustomerRegistrationInput {
  email: string
  username: string
  password: string
  verificationCode: string
  firstName?: string
  lastName?: string
  marketingConsent: boolean
}

export interface CustomerRegistrationResponse {
  id: number
  email: string
  username: string
  first_name: string
  last_name: string
  email_verified: boolean
  marketing_consent: boolean
  role: string
  status: string
  created_at: string | null
}

const formRequestOptions = {
  payloadMode: 'json' as const,
  headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
}

function formBody(parameters: Record<string, string | boolean | undefined>) {
  const body = new URLSearchParams()
  for (const [name, value] of Object.entries(parameters)) {
    if (value !== undefined) body.set(name, String(value))
  }
  return body
}

export function useCustomerAuthApi() {
  const http = useHttp()
  const session = useCustomerSession()

  return {
    async login(identifier: string, password: string) {
      const response = await http.post<CustomerLoginResponse, URLSearchParams>('/auth/login', formBody({
        identifier: identifier.trim(),
        password
      }), formRequestOptions)
      session.rememberUserId(response.user_id)
      return response
    },

    async sendRegistrationCode(email: string) {
      const response = await http.postRaw<unknown, URLSearchParams>('/auth/verification-code', formBody({
        email: email.trim()
      }), formRequestOptions)
      return response.message
    },

    registerAccount(input: CustomerRegistrationInput) {
      return http.post<CustomerRegistrationResponse, URLSearchParams>('/users/Register', formBody({
        email: input.email.trim(),
        username: input.username.trim(),
        password: input.password,
        verification_code: input.verificationCode.trim(),
        first_name: input.firstName?.trim() || undefined,
        last_name: input.lastName?.trim() || undefined,
        marketing_consent: input.marketingConsent
      }), formRequestOptions)
    },

    async logout() {
      try {
        await http.postRaw<unknown>('/auth/logout')
      } finally {
        session.clear()
      }
    }
  }
}
