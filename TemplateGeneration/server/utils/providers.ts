import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'

export const PROVIDER_TYPES = ['OpenAI', 'Anthropic', '兼容网关'] as const
export const PROVIDER_AUTHS = ['Bearer Token', 'Custom Header', '无需认证'] as const

export type ProviderType = typeof PROVIDER_TYPES[number]
export type ProviderAuth = typeof PROVIDER_AUTHS[number]

type ProviderRow = {
  id: number
  name: string
  type: ProviderType
  base_url: string
  auth: ProviderAuth
  credential_value: string
  model: string
  enabled: number
  updated_at: string
}

export type ProviderInput = {
  name?: unknown
  type?: unknown
  baseUrl?: unknown
  auth?: unknown
  credentialValue?: unknown
  model?: unknown
  models?: unknown
  enabled?: unknown
}

function getProviderId(event: H3Event) {
  const rawId = getRouterParam(event, 'id')
  const id = Number(rawId)
  if (!rawId || !Number.isSafeInteger(id) || id <= 0) {
    throw createError({statusCode: 400, statusMessage: '提供商 ID 必须是正整数。'})
  }
  return id
}

function serializeProvider(row: ProviderRow, models: string[] = []) {
  const normalizedModels = models.length ? models : [row.model]
  return {
    id: row.id,
    name: row.name,
    type: row.type,
    baseUrl: row.base_url,
    protocol: row.base_url.startsWith('http://') ? 'HTTP' : 'HTTPS',
    auth: row.auth,
    credentialValue: '',
    credentialConfigured: Boolean(row.credential_value),
    enabled: Boolean(row.enabled),
    model: row.model,
    models: normalizedModels,
    updatedAt: row.updated_at.replace(' ', 'T'),
  }
}

export function listProviders() {
  const rows = getDatabase().prepare(`
    SELECT
      api_providers.id,
      api_providers.name,
      api_providers.type,
      api_providers.base_url,
      api_providers.auth,
      api_providers.credential_value,
      api_providers.model,
      api_providers.enabled,
      api_providers.updated_at,
      api_provider_models.model AS catalog_model,
      api_provider_models.position AS catalog_position
    FROM api_providers
    LEFT JOIN api_provider_models ON api_provider_models.provider_id = api_providers.id
    ORDER BY api_providers.id ASC, api_provider_models.position ASC, api_provider_models.model ASC
  `).all() as Array<ProviderRow & {catalog_model: string | null; catalog_position: number | null}>
  const grouped = new Map<number, {row: ProviderRow; models: string[]}>()
  rows.forEach(({catalog_model, ...row}) => {
    const current = grouped.get(row.id) ?? {row, models: []}
    if (catalog_model) current.models.push(catalog_model)
    grouped.set(row.id, current)
  })
  return [...grouped.values()].map(({row, models}) => serializeProvider(row, models))
}

export function getProvider(providerId: number) {
  const row = getDatabase().prepare('SELECT id, name, type, base_url, auth, credential_value, model, enabled, updated_at FROM api_providers WHERE id = ?').get(providerId) as ProviderRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  const models = getDatabase().prepare('SELECT model FROM api_provider_models WHERE provider_id = ? ORDER BY position ASC, model ASC').all(providerId) as Array<{model: string}>
  return serializeProvider(row, models.map(({model}) => model))
}

function readString(value: unknown, field: string, maxLength: number) {
  if (typeof value !== 'string' || !value.trim()) {
    throw createError({statusCode: 400, statusMessage: `${field}不能为空。`})
  }
  const result = value.trim()
  if (result.length > maxLength) {
    throw createError({statusCode: 400, statusMessage: `${field}长度不能超过 ${maxLength} 个字符。`})
  }
  return result
}

function readProviderType(value: unknown) {
  if (typeof value !== 'string' || !PROVIDER_TYPES.includes(value as ProviderType)) {
    throw createError({statusCode: 400, statusMessage: '提供商类型必须是 OpenAI、Anthropic 或 兼容网关。'})
  }
  return value as ProviderType
}

function readProviderAuth(value: unknown) {
  if (typeof value !== 'string' || !PROVIDER_AUTHS.includes(value as ProviderAuth)) {
    throw createError({statusCode: 400, statusMessage: '认证方式必须是 Bearer Token、Custom Header 或 无需认证。'})
  }
  return value as ProviderAuth
}

function readBaseUrl(value: unknown) {
  const baseUrl = readString(value, '基础路由', 500)
  let parsed: URL
  try {
    parsed = new URL(baseUrl)
  } catch {
    throw createError({statusCode: 400, statusMessage: '基础路由必须是完整的 HTTP 或 HTTPS URL。'})
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw createError({statusCode: 400, statusMessage: '基础路由只支持 HTTP 或 HTTPS 协议。'})
  }
  return baseUrl.replace(/\/$/, '')
}

function readModels(value: unknown, currentModel: string) {
  const values = Array.isArray(value) ? value : [currentModel]
  const models = [...new Set(values.filter((item): item is string => typeof item === 'string').map((item) => item.trim()).filter(Boolean))]
  if (!models.length) models.push(currentModel)
  if (models.length > 100) {
    throw createError({statusCode: 400, statusMessage: '模型列表最多只能保存 100 个模型。'})
  }
  if (!models.includes(currentModel)) models.unshift(currentModel)
  return models
}

export function validateProviderInput(body: ProviderInput, existingCredential = '') {
  const name = readString(body.name, '提供商名称', 120)
  const type = readProviderType(body.type)
  const baseUrl = readBaseUrl(body.baseUrl)
  const auth = readProviderAuth(body.auth)
  const model = readString(body.model, '当前模型', 200)
  const credentialValue = auth === '无需认证'
    ? ''
    : typeof body.credentialValue === 'string' && body.credentialValue.trim()
      ? body.credentialValue.trim()
      : existingCredential

  if (credentialValue.length > 20000) {
    throw createError({statusCode: 400, statusMessage: '访问密钥长度不能超过 20000 个字符。'})
  }

  return {name, type, baseUrl, auth, model, credentialValue, models: readModels(body.models, model)}
}

export function createProvider(input: ReturnType<typeof validateProviderInput>) {
  const database = getDatabase()
  const insert = database.prepare("INSERT INTO api_providers (name, type, base_url, auth, credential_value, model, enabled, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))")
  const insertModel = database.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?)')
  const providerId = database.transaction(() => {
    const result = insert.run(input.name, input.type, input.baseUrl, input.auth, input.credentialValue, input.model, 0)
    input.models.forEach((model, position) => insertModel.run(result.lastInsertRowid, model, position))
    return Number(result.lastInsertRowid)
  })()
  return getProvider(providerId)
}

export function updateProvider(providerId: number, input: ReturnType<typeof validateProviderInput>, enabled: boolean) {
  const database = getDatabase()
  const update = database.prepare("UPDATE api_providers SET name = ?, type = ?, base_url = ?, auth = ?, credential_value = ?, model = ?, enabled = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?")
  const deleteModels = database.prepare('DELETE FROM api_provider_models WHERE provider_id = ?')
  const insertModel = database.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?)')
  database.transaction(() => {
    const result = update.run(input.name, input.type, input.baseUrl, input.auth, input.credentialValue, input.model, enabled ? 1 : 0, providerId)
    if (result.changes === 0) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
    deleteModels.run(providerId)
    input.models.forEach((model, position) => insertModel.run(providerId, model, position))
  })()
  return getProvider(providerId)
}

export function deleteProvider(event: H3Event) {
  const providerId = getProviderId(event)
  const result = getDatabase().prepare('DELETE FROM api_providers WHERE id = ?').run(providerId)
  if (result.changes === 0) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  return providerId
}

export {getProviderId}
