export interface CloudflareTraceResult {
  target: string
  checked_at: string
  duration_ms: number
  cloudflare_timestamp: string | null
  colo: string
  location: string
  host: string
  ip: string
  visit_scheme: string
  user_agent: string
  http_protocol: string
  tls_version: string
  sni: string
  warp: string
  gateway: string
  rbi: string
  key_exchange: string
  fl: string
  sliver: string
  raw: string
}
