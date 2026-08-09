export type LogLevel = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'OFF'
export type LoggingSettingsSource = 'DEFAULT' | 'REDIS'
export type RuntimeLoggingStatus = 'UP' | 'DEGRADED'

export interface LoggerOverride {
  logger_name: string
  level: LogLevel
}

export interface LoggingSettings {
  root_level: LogLevel
  logger_overrides: LoggerOverride[]
  output_template: string
  version: number
  source: LoggingSettingsSource
  updated_at: string | null
  updated_by: number | null
  effective_version: number
  runtime_status: RuntimeLoggingStatus
  instance_id: string
  storage_path: string
  time_zone: string
  max_file_size_bytes: number
  retention_days: number
  active_file: string | null
  active_file_size_bytes: number
  last_file_error: string | null
}

export interface LoggingSettingsUpdate {
  root_level: LogLevel
  logger_overrides: LoggerOverride[]
  output_template: string
  expected_version: number
}

export interface LoggingTemplatePreview {
  rendered: string
  encoded_size_bytes: number
}

export interface LiveLogEvent {
  sequence: number
  timestamp: string
  level: Exclude<LogLevel, 'OFF'>
  logger: string
  thread: string
  request_id: string | null
  rendered: string
  template_version: number
}

export interface LiveLogBatch {
  instance_id: string
  boot_id: string
  reset: boolean
  gap: boolean
  dropped_count: number
  earliest_sequence: number
  next_sequence: number
  events: LiveLogEvent[]
}

export interface LogDateSummary {
  date: string
  file_count: number
  size_bytes: number
}

export interface HistoryDatesResponse {
  dates: LogDateSummary[]
}

export interface HistoricalLogFile {
  date: string
  file_time: string
  rotation_index: number
  filename: string
  size_bytes: number
  modified_at: string
  active: boolean
}

export interface HistoryFilesResponse {
  files: HistoricalLogFile[]
  next_cursor: number | null
}

export interface HistoryLogLine {
  offset: number
  next_offset: number
  text: string
}

export interface HistoryContentResponse {
  filename: string
  file_size_bytes: number
  active: boolean
  lines: HistoryLogLine[]
  next_offset: number
  eof: boolean
}
