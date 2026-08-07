import type { RateLimitSettings, RateLimitSettingsUpdate } from '~/types/rate-limit'

export const useRateLimitApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, put } = useHttp(adminApiBase)

  return {
    getSettings() {
      return get<RateLimitSettings>('/rate-limit-settings')
    },

    updateSettings(payload: RateLimitSettingsUpdate) {
      const query: Record<string, unknown> = {
        enabled: payload.enabled,
        authenticated_requests_per_minute: payload.authenticated_requests_per_minute,
        anonymous_requests_per_minute: payload.anonymous_requests_per_minute,
        expected_version: payload.expected_version,
      }
      if (payload.excluded_paths.length > 0) {
        query.excluded_path = payload.excluded_paths
      }
      return put<RateLimitSettings, Record<string, unknown>>('/rate-limit-settings', query)
    },

    updateEnabled(enabled: boolean, expectedVersion: number) {
      return put<RateLimitSettings, Record<string, unknown>>('/rate-limit-settings/enabled', {
        enabled,
        expected_version: expectedVersion,
      })
    },
  }
}
