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

export type ProviderModel = {
  id: number
  name: string
}

export type ProviderInput = {
  name?: unknown
  type?: unknown
  baseUrl?: unknown
  auth?: unknown
  credentialValue?: unknown
  model?: unknown
  modelId?: unknown
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

function serializeProvider(row: ProviderRow, models: ProviderModel[] = []) {
  const normalizedModels = models.length ? models : [{id: 0, name: row.model}]
  const currentModel = normalizedModels.find((item) => item.name === row.model) ?? normalizedModels[0]
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
    modelId: currentModel?.id || null,
    model: currentModel?.name ?? row.model,
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
      api_provider_models.id AS catalog_id,
      api_provider_models.model AS catalog_model,
      api_provider_models.position AS catalog_position
    FROM api_providers
    LEFT JOIN api_provider_models ON api_provider_models.provider_id = api_providers.id
    ORDER BY api_providers.id ASC, api_provider_models.position ASC, api_provider_models.model ASC
  `).all() as Array<ProviderRow & {catalog_id: number | null; catalog_model: string | null; catalog_position: number | null}>
  const grouped = new Map<number, {row: ProviderRow; models: ProviderModel[]}>()
  rows.forEach(({catalog_id, catalog_model, ...row}) => {
    const current = grouped.get(row.id) ?? {row, models: []}
    if (catalog_id && catalog_model) current.models.push({id: catalog_id, name: catalog_model})
    grouped.set(row.id, current)
  })
  return [...grouped.values()].map(({row, models}) => serializeProvider(row, models))
}

export function getProvider(providerId: number) {
  const row = getDatabase().prepare('SELECT id, name, type, base_url, auth, credential_value, model, enabled, updated_at FROM api_providers WHERE id = ?').get(providerId) as ProviderRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  const models = getDatabase().prepare('SELECT id, model AS name FROM api_provider_models WHERE provider_id = ? ORDER BY position ASC, model ASC').all(providerId) as ProviderModel[]
  return serializeProvider(row, models)
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
  const models = [...new Set(values.map((item) => {
    if (typeof item === 'string') return item.trim()
    if (item && typeof item === 'object' && 'name' in item && typeof item.name === 'string') return item.name.trim()
    if (item && typeof item === 'object' && 'model' in item && typeof item.model === 'string') return item.model.trim()
    return ''
  }).filter(Boolean))]
  if (!models.length) models.push(currentModel)
  if (models.length > 100) {
    throw createError({statusCode: 400, statusMessage: '模型列表最多只能保存 100 个模型。'})
  }
  if (!models.includes(currentModel)) models.unshift(currentModel)
  return models
}

function readOptionalModelId(value: unknown) {
  if (value === undefined || value === null || value === '') return null
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw createError({statusCode: 400, statusMessage: '模型 ID 必须是正整数。'})
  }
  return parsed
}

export function validateProviderInput(body: ProviderInput, existingCredential = '') {
  const name = readString(body.name, '提供商名称', 120)
  const type = readProviderType(body.type)
  const baseUrl = readBaseUrl(body.baseUrl)
  const auth = readProviderAuth(body.auth)
  const model = readString(body.model, '当前模型', 200)
  const modelId = readOptionalModelId(body.modelId)
  const credentialValue = auth === '无需认证'
    ? ''
    : typeof body.credentialValue === 'string' && body.credentialValue.trim()
      ? body.credentialValue.trim()
      : existingCredential

  if (credentialValue.length > 20000) {
    throw createError({statusCode: 400, statusMessage: '访问密钥长度不能超过 20000 个字符。'})
  }

  return {name, type, baseUrl, auth, model, modelId, credentialValue, models: readModels(body.models, model)}
}

export async function fetchProviderModels(providerId: number) {
  const provider = getDatabase().prepare('SELECT id, base_url, auth, credential_value FROM api_providers WHERE id = ?').get(providerId) as {id: number; base_url: string; auth: ProviderAuth; credential_value: string} | undefined
  if (!provider) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  const endpoint = `${provider.base_url.replace(/\/$/, '')}/models`
  const headers: Record<string, string> = {Accept: 'application/json'}
  if (provider.credential_value && provider.auth !== '无需认证') {
    if (provider.auth === 'Custom Header') headers['x-api-key'] = provider.credential_value
    else headers.Authorization = `Bearer ${provider.credential_value}`
  }
  let response: Response
  try {
    response = await fetch(endpoint, {
      method: 'GET',
      headers,
      signal: AbortSignal.timeout(15000),
    })
  } catch {
    throw createError({statusCode: 502, statusMessage: '模型列表请求失败，请检查基础路由和网络连接。'})
  }
  if (!response.ok) {
    throw createError({statusCode: 502, statusMessage: `模型列表请求失败（HTTP ${response.status}）。`})
  }
  let payload: unknown
  try {
    payload = await response.json()
  } catch {
    throw createError({statusCode: 502, statusMessage: '模型列表响应不是有效的 JSON。'})
  }
  const source = payload && typeof payload === 'object' && 'data' in payload && Array.isArray(payload.data)
    ? payload.data
    : payload && typeof payload === 'object' && 'models' in payload && Array.isArray(payload.models)
      ? payload.models
      : Array.isArray(payload) ? payload : []
  const models = [...new Map<string, string>(source.map((item): [string, string] => {
    const value = typeof item === 'string' ? item : item && typeof item === 'object' && 'id' in item ? item.id : item && typeof item === 'object' && 'name' in item ? item.name : null
    const name = typeof value === 'string' ? value.trim() : ''
    return [name, name]
  }).filter(([name]) => Boolean(name)))].map(([name]) => ({id: name, name}))
  if (!models.length) throw createError({statusCode: 502, statusMessage: '模型列表响应中没有可用模型。'})
  if (models.length > 100) throw createError({statusCode: 502, statusMessage: '模型列表超过 100 个，请在提供商侧筛选后重试。'})
  return models
}

export async function refreshProviderModels(providerId: number) {
  const models = await fetchProviderModels(providerId)
  const database = getDatabase()
  const provider = database.prepare('SELECT id, model FROM api_providers WHERE id = ?').get(providerId) as {id: number; model: string} | undefined
  if (!provider) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  const names = models.map((item) => item.name)
  const currentModel = names.includes(provider.model) ? provider.model : names[0]
  const upsertModel = database.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?) ON CONFLICT(provider_id, model) DO UPDATE SET position = excluded.position')
  const deleteModel = database.prepare('DELETE FROM api_provider_models WHERE id = ?')
  const historicalModel = database.prepare('SELECT 1 FROM generation_task_specs WHERE model_id = ? LIMIT 1')
  database.transaction(() => {
    names.forEach((name, position) => upsertModel.run(providerId, name, position))
    const existingModels = database.prepare('SELECT id, model FROM api_provider_models WHERE provider_id = ?').all(providerId) as Array<{id: number; model: string}>
    existingModels.forEach((item) => {
      if (!names.includes(item.model) && !historicalModel.get(item.id)) deleteModel.run(item.id)
    })
    database.prepare("UPDATE api_providers SET model = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(currentModel, providerId)
  })()
  return getProvider(providerId)
}

/** Performs a minimal request against one persisted model to verify connectivity and credentials. */
export async function testProviderModel(providerId: number, modelId: number) {
  const database = getDatabase()
  const record = database.prepare(`
    SELECT
      api_providers.id,
      api_providers.type,
      api_providers.base_url,
      api_providers.auth,
      api_providers.credential_value,
      api_provider_models.id AS model_id,
      api_provider_models.model
    FROM api_providers
    INNER JOIN api_provider_models ON api_provider_models.provider_id = api_providers.id
    WHERE api_providers.id = ? AND api_provider_models.id = ?
  `).get(providerId, modelId) as {
    id: number
    type: ProviderType
    base_url: string
    auth: ProviderAuth
    credential_value: string
    model_id: number
    model: string
  } | undefined

  if (!record) {
    const provider = database.prepare('SELECT id FROM api_providers WHERE id = ?').get(providerId)
    if (!provider) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
    throw createError({statusCode: 400, statusMessage: '模型 ID 不属于当前模型提供商。'})
  }

  const headers: Record<string, string> = {Accept: 'application/json', 'Content-Type': 'application/json'}
  if (record.credential_value && record.auth !== '无需认证') {
    if (record.type === 'Anthropic' || record.auth === 'Custom Header') headers['x-api-key'] = record.credential_value
    else headers.Authorization = `Bearer ${record.credential_value}`
  }

  const baseUrl = record.base_url.replace(/\/$/, '')
  const isAnthropic = record.type === 'Anthropic'
  const endpoint = `${baseUrl}/${isAnthropic ? 'messages' : 'chat/completions'}`
  const body = isAnthropic
    ? {
        model: record.model,
        max_tokens: 1,
        messages: [{role: 'user', content: 'ping'}],
      }
    : {
        model: record.model,
        messages: [{role: 'user', content: 'ping'}],
        max_tokens: 1,
      }
  if (isAnthropic) headers['anthropic-version'] = '2023-06-01'

  const startedAt = Date.now()
  let response: Response
  try {
    response = await fetch(endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(15000),
    })
  } catch {
    throw createError({statusCode: 502, statusMessage: '模型测活请求失败，请检查基础路由、凭据和网络连接。'})
  }
  if (!response.ok) {
    const reason = response.status === 401 || response.status === 403
      ? '凭据无效或无权访问该模型。'
      : response.status === 404
        ? '模型接口或模型不存在，请检查基础路由和模型配置。'
        : `上游服务返回 HTTP ${response.status}。`
    throw createError({statusCode: 502, statusMessage: `模型测活失败：${reason}`})
  }

  return {
    ok: true,
    provider_id: providerId,
    model_id: record.model_id,
    model: record.model,
    latency_ms: Math.max(0, Date.now() - startedAt),
  }
}

export function saveProviderModelCatalog(providerId: number, modelsValue: unknown, modelIdValue: unknown) {
  const database = getDatabase()
  const provider = database.prepare('SELECT id, model FROM api_providers WHERE id = ?').get(providerId) as {id: number; model: string} | undefined
  if (!provider) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
  const models = readModels(modelsValue, provider.model)
  const modelId = readOptionalModelId(modelIdValue)
  const upsertModel = database.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?) ON CONFLICT(provider_id, model) DO UPDATE SET position = excluded.position')
  const deleteModel = database.prepare('DELETE FROM api_provider_models WHERE id = ?')
  const historicalModel = database.prepare('SELECT 1 FROM generation_task_specs WHERE model_id = ? LIMIT 1')
  database.transaction(() => {
    models.forEach((model, position) => upsertModel.run(providerId, model, position))
    const selectedModel = modelId
      ? database.prepare('SELECT model FROM api_provider_models WHERE id = ? AND provider_id = ?').get(modelId, providerId) as {model: string} | undefined
      : database.prepare('SELECT model FROM api_provider_models WHERE provider_id = ? AND model = ?').get(providerId, provider.model) as {model: string} | undefined
    if (modelId && !selectedModel) throw createError({statusCode: 400, statusMessage: '模型 ID 不属于当前模型提供商。'})
    const currentModel = selectedModel?.model ?? models[0]
    database.prepare("UPDATE api_providers SET model = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(currentModel, providerId)
    const existingModels = database.prepare('SELECT id, model FROM api_provider_models WHERE provider_id = ?').all(providerId) as Array<{id: number; model: string}>
    existingModels.forEach((item) => {
      if (!models.includes(item.model) && !historicalModel.get(item.id)) deleteModel.run(item.id)
    })
  })()
  return getProvider(providerId)
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
  const selectedModel = input.modelId
    ? database.prepare('SELECT model FROM api_provider_models WHERE id = ? AND provider_id = ?').get(input.modelId, providerId) as {model: string} | undefined
    : undefined
  if (input.modelId && !selectedModel) throw createError({statusCode: 400, statusMessage: '模型 ID 不属于当前模型提供商。'})
  const currentModel = selectedModel?.model ?? input.model
  const update = database.prepare("UPDATE api_providers SET name = ?, type = ?, base_url = ?, auth = ?, credential_value = ?, model = ?, enabled = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?")
  const upsertModel = database.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?) ON CONFLICT(provider_id, model) DO UPDATE SET position = excluded.position')
  const deleteModel = database.prepare('DELETE FROM api_provider_models WHERE id = ?')
  const historicalModel = database.prepare('SELECT 1 FROM generation_task_specs WHERE model_id = ? LIMIT 1')
  database.transaction(() => {
    const result = update.run(input.name, input.type, input.baseUrl, input.auth, input.credentialValue, currentModel, enabled ? 1 : 0, providerId)
    if (result.changes === 0) throw createError({statusCode: 404, statusMessage: '提供商不存在。'})
    input.models.forEach((model, position) => upsertModel.run(providerId, model, position))
    const existingModels = database.prepare('SELECT id, model FROM api_provider_models WHERE provider_id = ?').all(providerId) as Array<{id: number; model: string}>
    existingModels.forEach((item) => {
      if (!input.models.includes(item.model) && !historicalModel.get(item.id)) deleteModel.run(item.id)
    })
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
