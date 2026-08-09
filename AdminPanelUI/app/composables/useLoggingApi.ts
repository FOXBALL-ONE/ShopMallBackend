import type {
  HistoryContentResponse,
  HistoryDatesResponse,
  HistoryFilesResponse,
  LiveLogBatch,
  LoggingSettings,
  LoggingSettingsUpdate,
  LoggingTemplatePreview,
} from '~/types/logging'

export const useLoggingApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put } = useHttp(adminApiBase)

  return {
    getSettings() {
      return get<LoggingSettings>('/logs/settings')
    },

    previewTemplate(outputTemplate: string) {
      return post<LoggingTemplatePreview, Record<string, string>>('/logs/settings/preview', {
        output_template: outputTemplate,
      })
    },

    updateSettings(payload: LoggingSettingsUpdate) {
      const query: Record<string, unknown> = {
        root_level: payload.root_level,
        output_template: payload.output_template,
        expected_version: payload.expected_version,
      }
      if (payload.logger_overrides.length > 0) {
        query.logger_override = payload.logger_overrides.map(item => `${item.logger_name}=${item.level}`)
      }
      return put<LoggingSettings, Record<string, unknown>>('/logs/settings', query)
    },

    getLive(params: Record<string, unknown>, signal?: AbortSignal) {
      return get<LiveLogBatch>('/logs/live', params, { signal, retry: 0 })
    },

    getDates(params?: Record<string, unknown>) {
      return get<HistoryDatesResponse>('/logs/history/dates', params)
    },

    getFiles(date: string, cursor = 0, limit = 100) {
      return get<HistoryFilesResponse>('/logs/history/files', { date, cursor, limit })
    },

    getContent(params: Record<string, unknown>) {
      return get<HistoryContentResponse>('/logs/history/content', params)
    },
  }
}
