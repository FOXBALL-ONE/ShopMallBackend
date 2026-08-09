export interface SystemStatusSnapshot {
  status: string
  source: string
  generated_at: string
  collection_duration_ms: number
  application: {
    name: string
    version: string
    started_at: string | null
    uptime_seconds: number
  }
  system: {
    available_processors: number
    system_load_average: number | null
    process_cpu_usage: number | null
    system_cpu_usage: number | null
    disk_total_bytes: number | null
    disk_free_bytes: number | null
  }
  jvm: {
    heap_used_bytes: number
    heap_committed_bytes: number
    heap_max_bytes: number
    non_heap_used_bytes: number
    live_threads: number
    peak_threads: number
    daemon_threads: number
    gc_collection_count: number
    gc_collection_time_ms: number
  }
  http: {
    request_count: number
    active_requests: number
    server_error_count: number
    average_duration_ms: number | null
    max_duration_ms: number | null
  }
  database: {
    status: string
    active_connections: number | null
    idle_connections: number | null
    min_connections: number | null
    max_connections: number | null
  }
  redis: {
    status: string
    version: string | null
    mode: string | null
    role: string | null
    database: number | null
    key_count: number | null
    expiring_key_count: number | null
    average_ttl_ms: number | null
    uptime_seconds: number | null
    used_memory_bytes: number | null
    peak_memory_bytes: number | null
    max_memory_bytes: number | null
    connected_clients: number | null
    max_clients: number | null
    blocked_clients: number | null
    total_commands_processed: number | null
    operations_per_second: number | null
    keyspace_hits: number | null
    keyspace_misses: number | null
    evicted_keys: number | null
  }
  health_components: Array<{
    id: string
    status: string
    details: Record<string, string>
  }>
}

export interface SystemMetricSample {
  captured_at: string
  process_cpu_percent: number | null
  system_cpu_percent: number | null
  heap_usage_percent: number | null
}
