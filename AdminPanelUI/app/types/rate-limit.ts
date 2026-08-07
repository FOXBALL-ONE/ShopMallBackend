export type RateLimitSettingsSource = 'DEFAULT' | 'REDIS'

export interface RateLimitSettings {
  enabled: boolean
  window_seconds: number
  authenticated_requests_per_minute: number
  anonymous_requests_per_minute: number
  excluded_paths: string[]
  version: number
  source: RateLimitSettingsSource
  updated_at: string | null
  updated_by: number | null
}

export interface RateLimitSettingsUpdate {
  enabled: boolean
  authenticated_requests_per_minute: number
  anonymous_requests_per_minute: number
  excluded_paths: string[]
  expected_version: number
}
