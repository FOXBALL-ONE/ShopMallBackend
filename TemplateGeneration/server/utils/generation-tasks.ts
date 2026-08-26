import {createHash, randomUUID} from 'node:crypto'
import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'

export type GenerationTaskInput = {
  workflow_id?: unknown
  workflow_name?: unknown
  workflow_version?: unknown
  media?: unknown
  provider_id?: unknown
  model_id?: unknown
  batch_count?: unknown
  prompt?: unknown
  negative_prompt?: unknown
  reference_images?: unknown
  size?: unknown
  quality?: unknown
  background?: unknown
  output_format?: unknown
}

type TaskRow = {
  id: number; project_id: string; workflow_id: number | null; batch_id: string | null; status: string; progress: number; stage: string
  attempt_count: number; max_attempts: number; created_at: string; updated_at: string; started_at: string | null; completed_at: string | null
  failed_at: string | null; cancelled_at: string | null; error_code: string | null; error_message: string | null; upstream_request_id: string | null
  provider_id: number | null; model_id: number | null; provider_name: string; provider_type: string; model: string; workflow_name: string
  workflow_version: string; media: string; batch_index: number; batch_count: number; prompt: string; generation_status: string; result_id: number | null
  result_file_id: number | null; result_uri: string | null
}

type InputRow = {position: number; role: string; instruction: string; asset_id: number | null; file_id: number | null; storage_key: string; original_name: string; content_type: string; size_bytes: number; sha256: string}

export const TASK_STATUSES = ['QUEUED', 'RUNNING', 'RETRY_WAITING', 'COMPLETED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED'] as const
const MEDIA_TYPES = ['IMAGE', 'VIDEO'] as const
const QUALITY_VALUES = ['low', 'medium', 'high', 'auto'] as const
const BACKGROUND_VALUES = ['transparent', 'opaque', 'auto'] as const
const OUTPUT_FORMATS = ['png', 'webp', 'jpeg'] as const
const REFERENCE_CONTENT_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const

export function getGenerationTaskProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  return projectId
}

export function getGenerationTaskId(event: H3Event) {
  const taskId = Number(getRouterParam(event, 'taskId'))
  if (!Number.isSafeInteger(taskId) || taskId <= 0) throw createError({statusCode: 400, statusMessage: '生成任务 ID 必须是正整数。'})
  return taskId
}

function readPositiveInteger(value: unknown, field: string) {
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw createError({statusCode: 400, statusMessage: `${field}必须是正整数。`})
  return parsed
}

function readOptionalString(value: unknown, field: string, maxLength: number) {
  if (value === undefined || value === null) return ''
  if (typeof value !== 'string') throw createError({statusCode: 400, statusMessage: `${field}必须是文本。`})
  const result = value.trim()
  if (result.length > maxLength) throw createError({statusCode: 400, statusMessage: `${field}长度不能超过 ${maxLength} 个字符。`})
  return result
}

function readChoice<T extends readonly string[]>(value: unknown, values: T, field: string, fallback: T[number]) {
  if (value === undefined || value === null || value === '') return fallback
  if (typeof value !== 'string' || !values.includes(value)) throw createError({statusCode: 400, statusMessage: `${field}必须是 ${values.join('、')}。`})
  return value as T[number]
}

function readMedia(value: unknown) {
  if (typeof value !== 'string' || !MEDIA_TYPES.includes(value as typeof MEDIA_TYPES[number])) throw createError({statusCode: 400, statusMessage: '生成类型必须是 IMAGE 或 VIDEO。'})
  return value as typeof MEDIA_TYPES[number]
}

function taskQuery() {
  return `
    SELECT generation_tasks.id, generation_tasks.project_id, generation_tasks.workflow_id, generation_tasks.batch_id,
      generation_tasks.status, generation_tasks.progress, generation_tasks.stage, generation_tasks.attempt_count, generation_tasks.max_attempts,
      generation_tasks.created_at, generation_tasks.updated_at, generation_tasks.started_at, generation_tasks.completed_at,
      generation_tasks.failed_at, generation_tasks.cancelled_at, generation_tasks.error_code, generation_tasks.error_message, generation_tasks.upstream_request_id,
      generation_task_specs.provider_id, generation_task_specs.model_id, COALESCE(generation_task_specs.provider_name, '') AS provider_name,
      COALESCE(generation_task_specs.provider_type, '') AS provider_type, COALESCE(generation_task_specs.model, '') AS model,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name, COALESCE(generation_task_specs.workflow_version, '') AS workflow_version,
      COALESCE(generation_task_specs.media, 'IMAGE') AS media, COALESCE(generation_task_specs.batch_index, 1) AS batch_index,
      COALESCE(generation_task_specs.batch_count, 1) AS batch_count, COALESCE(generation_task_specs.prompt, '') AS prompt,
      results.id AS result_id, results.file_id AS result_file_id, results.uri AS result_uri, COALESCE(results.generation_status, 'GENERATING') AS generation_status
    FROM generation_tasks
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id
    LEFT JOIN results ON results.task_id = generation_tasks.id
  `
}

function getTaskRow(taskId: number, projectId: string) {
  const row = getDatabase().prepare(`${taskQuery()} WHERE generation_tasks.id = ? AND generation_tasks.project_id = ?`).get(taskId, projectId) as TaskRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '生成任务不存在。'})
  return row
}

function iso(value: string | null | undefined) { return value ? value.replace(' ', 'T') : null }

function serializeTask(row: TaskRow) {
  const statusLabel: Record<string, string> = {RUNNING: '生成中', COMPLETED: '已完成', FAILED: '生成失败', CANCEL_REQUESTED: '取消中', CANCELLED: '已取消', RETRY_WAITING: '等待重试', QUEUED: '排队中'}
  return {
    id: row.id, projectId: row.project_id, batchId: row.batch_id, workflowId: row.workflow_id, workflowName: row.workflow_name,
    workflowVersion: row.workflow_version, media: row.media, type: row.media === 'VIDEO' ? '视频' : '图片', status: row.status,
    statusLabel: statusLabel[row.status] ?? row.status, generationStatus: row.generation_status, progress: row.progress, stage: row.stage,
    attemptCount: row.attempt_count, maxAttempts: row.max_attempts, errorCode: row.error_code, errorMessage: row.error_message,
    provider: {id: row.provider_id, name: row.provider_name, type: row.provider_type, modelId: row.model_id, model: row.model},
    batchIndex: row.batch_index, batchCount: row.batch_count, prompt: row.prompt, result: row.result_id ? {id: row.result_id, fileId: row.result_file_id, uri: row.result_uri} : null,
    createdAt: iso(row.created_at), updatedAt: iso(row.updated_at), startedAt: iso(row.started_at), completedAt: iso(row.completed_at), failedAt: iso(row.failed_at), cancelledAt: iso(row.cancelled_at),
  }
}

export function getGenerationTask(taskId: number, projectId: string) {
  return {...serializeTask(getTaskRow(taskId, projectId)), events: listTaskEvents(taskId, projectId)}
}

export function listGenerationTasks(projectId: string) {
  const rows = getDatabase().prepare(`${taskQuery()} WHERE generation_tasks.project_id = ? ORDER BY generation_tasks.created_at DESC, generation_tasks.id DESC LIMIT 200`).all(projectId) as TaskRow[]
  return rows.map(serializeTask)
}

function addEvent(database: ReturnType<typeof getDatabase>, taskId: number, eventType: string, fromStatus: string | null, toStatus: string | null, stage: string, progress: number, message: string, workerId: string | null = null, errorCode: string | null = null) {
  database.prepare('INSERT INTO generation_task_events (task_id, event_type, from_status, to_status, stage, progress, message, error_code, worker_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)').run(taskId, eventType, fromStatus, toStatus, stage, progress, message, errorCode, workerId)
}

export function refreshGenerationBatch(database: ReturnType<typeof getDatabase>, batchId: string | null) {
  if (!batchId) return
  const row = database.prepare(`SELECT COUNT(*) AS total,
      SUM(CASE WHEN status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN 1 ELSE 0 END) AS terminal,
      SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,
      SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled
    FROM generation_tasks WHERE batch_id = ?`).get(batchId) as {total: number; terminal: number; failed: number; cancelled: number}
  const status = row.terminal < row.total ? 'RUNNING' : row.failed > 0 ? 'FAILED' : row.cancelled === row.total ? 'CANCELLED' : 'COMPLETED'
  database.prepare("UPDATE generation_batches SET status = ?, completed_at = CASE WHEN ? IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN COALESCE(completed_at, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')) ELSE NULL END, failed_at = CASE WHEN ? = 'FAILED' THEN COALESCE(failed_at, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')) ELSE failed_at END, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(status, status, status, batchId)
}

function getReferenceRows(database: ReturnType<typeof getDatabase>, projectId: string, body: GenerationTaskInput, workflowDefinition: Record<string, unknown> | null) {
  const references: Array<{assetId: number; role: string; instruction: string}> = []
  const add = (value: unknown, role: string, instruction: string) => {
    const assetId = Number(value)
    if (!Number.isSafeInteger(assetId) || assetId <= 0 || references.some((item) => item.assetId === assetId)) return
    references.push({assetId, role, instruction})
  }
  if (Array.isArray(body.reference_images)) {
    if (body.reference_images.length > 8) throw createError({statusCode: 400, statusMessage: '单个生成任务最多支持 8 张参考图。'})
    body.reference_images.forEach((item) => {
      if (!item || typeof item !== 'object') throw createError({statusCode: 400, statusMessage: '参考图必须是对象数组。'})
      const record = item as Record<string, unknown>
      const assetId = Number(record.asset_id)
      if (!Number.isSafeInteger(assetId) || assetId <= 0) throw createError({statusCode: 400, statusMessage: '每张参考图的 asset_id 必须是正整数。'})
      if (references.some((reference) => reference.assetId === assetId)) throw createError({statusCode: 400, statusMessage: '单个生成任务不能重复选择同一张参考图。'})
      const role = record.role === undefined || record.role === null || record.role === '' ? 'reference' : readOptionalString(record.role, '参考图角色', 40)
      if (!role) throw createError({statusCode: 400, statusMessage: '参考图角色必须是 1-40 个字符。'})
      const instruction = readOptionalString(record.instruction, '参考图文字要求', 500)
      add(assetId, role, instruction)
    })
  }
  // Request-level descriptions take precedence over the workflow defaults for the same asset.
  if (workflowDefinition) {
    add(workflowDefinition.garmentAssetId, 'garment', '严格保留服装颜色、材质和剪裁。')
    add(workflowDefinition.modelAssetId, 'model', '参考人物脸部、体态和站姿。')
  }
  if (references.length > 8) throw createError({statusCode: 400, statusMessage: '单个生成任务最多支持 8 张参考图。'})
  return references.map((reference, position) => {
    const row = database.prepare(`SELECT asset_library.id AS asset_id, asset_library.project_id, asset_library.type, asset_library.name,
      stored_files.id AS file_id, stored_files.storage_key, stored_files.original_name, stored_files.content_type, stored_files.size_bytes, stored_files.sha256
      FROM asset_library INNER JOIN stored_files ON stored_files.id = asset_library.file_id
      WHERE asset_library.id = ? AND (asset_library.project_id = ? OR asset_library.project_id = '__global__')`).get(reference.assetId, projectId) as (InputRow & {asset_id: number; project_id: string; type: string; name: string}) | undefined
    if (!row) throw createError({statusCode: 400, statusMessage: '参考素材不存在或不属于当前项目。'})
    if (!REFERENCE_CONTENT_TYPES.includes(row.content_type as typeof REFERENCE_CONTENT_TYPES[number])) throw createError({statusCode: 415, statusMessage: '图生图参考素材必须是 PNG、JPEG 或 WebP 图片。'})
    return {...reference, position: position + 1, row}
  })
}

export function createGenerationTasks(projectId: string, body: GenerationTaskInput, idempotencyKey = '') {
  const database = getDatabase()
  if (!database.prepare('SELECT id FROM projects WHERE id = ?').get(projectId)) throw createError({statusCode: 404, statusMessage: '项目不存在。'})
  const providerId = readPositiveInteger(body.provider_id, '模型提供商 ID')
  const modelId = readPositiveInteger(body.model_id, '模型 ID')
  const provider = database.prepare('SELECT id, name, type, base_url, auth, enabled FROM api_providers WHERE id = ?').get(providerId) as {id: number; name: string; type: string; base_url: string; auth: string; enabled: number} | undefined
  if (!provider) throw createError({statusCode: 404, statusMessage: '模型提供商不存在。'})
  if (!provider.enabled) throw createError({statusCode: 400, statusMessage: '只能使用已启用的模型提供商。'})
  if (provider.type !== 'OpenAI' && provider.type !== '兼容网关') throw createError({statusCode: 400, statusMessage: '图像生成仅支持 OpenAI 或兼容网关提供商。'})
  const model = database.prepare('SELECT id, model FROM api_provider_models WHERE id = ? AND provider_id = ?').get(modelId, providerId) as {id: number; model: string} | undefined
  if (!model) throw createError({statusCode: 400, statusMessage: '模型 ID 不属于所选模型提供商。'})
  const batchCount = Number(body.batch_count)
  if (!Number.isInteger(batchCount) || batchCount < 1 || batchCount > 12) throw createError({statusCode: 400, statusMessage: '批量数量必须是 1-12 的整数。'})
  let workflowId: number | null = null
  let workflowName = readOptionalString(body.workflow_name, '工作流名称', 120)
  let workflowVersion = readOptionalString(body.workflow_version, '工作流版本', 80)
  let media: typeof MEDIA_TYPES[number] = 'IMAGE'
  let prompt = readOptionalString(body.prompt, '提示词', 5000)
  let negativePrompt = readOptionalString(body.negative_prompt, '反向提示词', 3000)
  let workflowDefinition: Record<string, unknown> | null = null
  if (body.workflow_id !== undefined && body.workflow_id !== null && body.workflow_id !== '') {
    workflowId = readPositiveInteger(body.workflow_id, '工作流 ID')
    const workflow = database.prepare('SELECT id, name, version, definition_json FROM workflows WHERE id = ? AND project_id = ?').get(workflowId, projectId) as {id: number; name: string; version: number; definition_json: string} | undefined
    if (!workflow) throw createError({statusCode: 404, statusMessage: '工作流不存在或不属于当前项目。'})
    workflowName = workflow.name; workflowVersion = `IMAGE · V${workflow.version}`; media = 'IMAGE'
    try { workflowDefinition = JSON.parse(workflow.definition_json) as Record<string, unknown> } catch { throw createError({statusCode: 500, statusMessage: '工作流定义数据损坏。'}) }
    prompt = typeof workflowDefinition.creativePrompt === 'string' ? workflowDefinition.creativePrompt.slice(0, 5000) : prompt
    negativePrompt = typeof workflowDefinition.negativePrompt === 'string' ? workflowDefinition.negativePrompt.slice(0, 3000) : negativePrompt
  } else media = readMedia(body.media)
  if (media !== 'IMAGE') throw createError({statusCode: 400, statusMessage: '当前 OpenAI 图像模块只支持 IMAGE 任务。'})
  if (!workflowName) throw createError({statusCode: 400, statusMessage: '工作流名称不能为空。'})
  if (!workflowVersion) throw createError({statusCode: 400, statusMessage: '工作流版本不能为空。'})
  const size = readOptionalString(body.size, '输出尺寸', 30) || 'auto'
  const quality = readChoice(body.quality, QUALITY_VALUES, '输出质量', 'auto')
  const background = readChoice(body.background, BACKGROUND_VALUES, '背景模式', 'auto')
  const outputFormat = readChoice(body.output_format, OUTPUT_FORMATS, '输出格式', 'png')
  const references = getReferenceRows(database, projectId, body, workflowDefinition)
  const normalizedKey = idempotencyKey.trim()
  const keyHash = normalizedKey ? createHash('sha256').update(`${projectId}:${normalizedKey}`).digest('hex') : null
  if (keyHash) {
    const existing = database.prepare('SELECT id FROM generation_batches WHERE project_id = ? AND idempotency_key_hash = ?').get(projectId, keyHash) as {id: string} | undefined
    if (existing) {
      const existingRows = database.prepare(`${taskQuery()} WHERE generation_tasks.batch_id = ? ORDER BY generation_tasks.id ASC`).all(existing.id) as TaskRow[]
      return existingRows.map(serializeTask)
    }
  }
  const batchId = randomUUID()
  const snapshot = JSON.stringify({providerId, modelId, providerName: provider.name, providerType: provider.type, baseUrl: provider.base_url, model: model.model, size, quality, background, outputFormat})
  const insertBatch = database.prepare("INSERT INTO generation_batches (id, project_id, provider_id, model_id, batch_count, status, idempotency_key_hash) VALUES (?, ?, ?, ?, ?, 'QUEUED', ?)")
  const insertTask = database.prepare("INSERT INTO generation_tasks (project_id, workflow_id, batch_id, status, progress, stage, max_attempts, updated_at) VALUES (?, ?, ?, 'QUEUED', 0, '排队中', 2, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))")
  const insertSpec = database.prepare("INSERT INTO generation_task_specs (task_id, provider_id, model_id, provider_base_url, provider_name, provider_type, model, workflow_name, workflow_version, media, batch_index, batch_count, prompt, negative_prompt, size, quality, background, output_format, request_snapshot_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
  const insertResult = database.prepare("INSERT INTO results (project_id, task_id, media, status, generation_status, prompt) VALUES (?, ?, ?, 'PENDING', 'GENERATING', ?)")
  const insertInput = database.prepare('INSERT INTO generation_task_inputs (task_id, position, role, instruction, asset_id, file_id, storage_key, original_name, content_type, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
  const taskIds = database.transaction(() => {
    insertBatch.run(batchId, projectId, providerId, modelId, batchCount, keyHash)
    const ids: number[] = []
    for (let batchIndex = 1; batchIndex <= batchCount; batchIndex += 1) {
      const result = insertTask.run(projectId, workflowId, batchId)
      const taskId = Number(result.lastInsertRowid); ids.push(taskId)
      insertSpec.run(taskId, providerId, modelId, provider.base_url, provider.name, provider.type, model.model, workflowName, workflowVersion, media, batchIndex, batchCount, prompt, negativePrompt, size, quality, background, outputFormat, snapshot)
      insertResult.run(projectId, taskId, media, prompt)
      references.forEach((reference) => insertInput.run(taskId, reference.position, reference.role, reference.instruction, reference.assetId, reference.row.file_id, reference.row.storage_key, reference.row.original_name, reference.row.content_type, reference.row.size_bytes, reference.row.sha256))
      addEvent(database, taskId, 'SUBMITTED', null, 'QUEUED', '排队中', 0, `已提交至 ${provider.name} · ${model.model}`)
    }
    return ids
  })()
  return taskIds.map((taskId) => serializeTask(getTaskRow(taskId, projectId)))
}

export function claimNextGenerationTask(workerId: string, leaseMs: number) {
  const database = getDatabase(); const now = Date.now(); const leaseToken = randomUUID()
  return database.transaction(() => {
    const row = database.prepare(`SELECT id, status FROM generation_tasks WHERE (status = 'QUEUED' OR (status = 'RETRY_WAITING' AND next_attempt_at <= ?) OR (status = 'RUNNING' AND lease_expires_at < ?)) ORDER BY created_at ASC, id ASC LIMIT 1`).get(now, now) as {id: number; status: string} | undefined
    if (!row) return null
    const fromStatus = row.status
    database.prepare("UPDATE generation_tasks SET status = 'RUNNING', progress = CASE WHEN progress < 5 THEN 5 ELSE progress END, stage = '任务已领取', attempt_count = attempt_count + 1, lease_token = ?, lease_expires_at = ?, started_at = COALESCE(started_at, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')), last_heartbeat_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(leaseToken, now + leaseMs, row.id)
    addEvent(database, row.id, 'CLAIMED', fromStatus, 'RUNNING', '任务已领取', 5, `Worker ${workerId} 已领取任务`, workerId)
    return {taskId: row.id, leaseToken}
  })()
}

export function getTaskExecution(taskId: number) {
  const database = getDatabase()
  const task = database.prepare(`${taskQuery()} WHERE generation_tasks.id = ?`).get(taskId) as TaskRow | undefined
  if (!task) return null
  const spec = database.prepare('SELECT provider_id, model_id, provider_base_url, provider_name, provider_type, model, workflow_name, prompt, negative_prompt, size, quality, background, output_format FROM generation_task_specs WHERE task_id = ?').get(taskId) as {provider_id: number; model_id: number; provider_base_url: string; provider_name: string; provider_type: string; model: string; workflow_name: string; prompt: string; negative_prompt: string; size: string; quality: string; background: string; output_format: string} | undefined
  if (!spec) return null
  const provider = database.prepare('SELECT credential_value, auth, enabled FROM api_providers WHERE id = ?').get(spec.provider_id) as {credential_value: string; auth: string; enabled: number} | undefined
  const inputs = database.prepare('SELECT position, role, instruction, asset_id, file_id, storage_key, original_name, content_type, size_bytes, sha256 FROM generation_task_inputs WHERE task_id = ? ORDER BY position ASC').all(taskId) as InputRow[]
  return {task, spec, provider, inputs}
}

export function updateTaskProgress(taskId: number, workerId: string, leaseToken: string, stage: string, progress: number, leaseMs = 120000) {
  const database = getDatabase()
  const result = database.prepare("UPDATE generation_tasks SET stage = ?, progress = ?, last_heartbeat_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), lease_expires_at = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ? AND status = 'RUNNING' AND lease_token = ?").run(stage, progress, Date.now() + leaseMs, taskId, leaseToken)
  if (result.changes === 0) throw createError({statusCode: 409, statusMessage: '任务租约已失效，已停止写入进度。'})
  addEvent(database, taskId, 'PROGRESS', 'RUNNING', 'RUNNING', stage, progress, stage, workerId)
}

export function completeTask(taskId: number, workerId: string, resultId: number, upstreamRequestId: string | null, durationMs: number) {
  const database = getDatabase(); database.transaction(() => {
    const current = database.prepare('SELECT status, batch_id FROM generation_tasks WHERE id = ?').get(taskId) as {status: string; batch_id: string | null} | undefined
    if (!current || current.status === 'CANCELLED') return
    database.prepare("UPDATE generation_tasks SET status = 'COMPLETED', progress = 100, stage = '已完成', completed_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), lease_token = NULL, lease_expires_at = NULL, upstream_request_id = ?, duration_ms = ? WHERE id = ?").run(upstreamRequestId, durationMs, taskId)
    refreshGenerationBatch(database, current.batch_id)
    addEvent(database, taskId, 'COMPLETED', 'RUNNING', 'COMPLETED', '已完成', 100, `已保存生成结果 ${resultId}`, workerId)
  })()
}

export function failTask(taskId: number, workerId: string, code: string, message: string, retryable: boolean, leaseToken: string) {
  const database = getDatabase(); database.transaction(() => {
    const current = database.prepare('SELECT status, attempt_count, max_attempts, batch_id, lease_token FROM generation_tasks WHERE id = ?').get(taskId) as {status: string; attempt_count: number; max_attempts: number; batch_id: string | null; lease_token: string | null} | undefined
    if (!current || current.status === 'CANCELLED') return
    if (current.lease_token !== leaseToken) return
    if (current.status === 'CANCEL_REQUESTED') {
      database.prepare("UPDATE generation_tasks SET status = 'CANCELLED', stage = '已取消', cancelled_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), lease_token = NULL, lease_expires_at = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ? AND lease_token = ?").run(taskId, leaseToken)
      database.prepare("UPDATE results SET generation_status = 'CANCELLED' WHERE task_id = ?").run(taskId)
      refreshGenerationBatch(database, current.batch_id)
      addEvent(database, taskId, 'CANCELLED', 'CANCEL_REQUESTED', 'CANCELLED', '已取消', 0, '任务已取消', workerId)
      return
    }
    const retry = retryable && current.attempt_count < current.max_attempts
    const next = retry ? Date.now() + Math.min(300000, 5000 * (2 ** Math.max(0, current.attempt_count - 1))) : null
    database.prepare("UPDATE generation_tasks SET status = ?, stage = ?, progress = CASE WHEN ? = 'FAILED' THEN progress ELSE 10 END, next_attempt_at = ?, failed_at = CASE WHEN ? = 'FAILED' THEN strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') ELSE failed_at END, error_code = ?, error_message = ?, lease_token = NULL, lease_expires_at = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ? AND lease_token = ?").run(retry ? 'RETRY_WAITING' : 'FAILED', retry ? '等待重试' : '生成失败', retry ? 'RETRY_WAITING' : 'FAILED', next, retry ? 'RETRY_WAITING' : 'FAILED', code, message.slice(0, 1000), taskId, leaseToken)
    database.prepare("UPDATE results SET generation_status = ?, error_code = ?, error_message = ? WHERE task_id = ?").run(retry ? 'GENERATING' : 'FAILED', code, message.slice(0, 1000), taskId)
    refreshGenerationBatch(database, current.batch_id)
    addEvent(database, taskId, retry ? 'RETRY_SCHEDULED' : 'FAILED', current.status, retry ? 'RETRY_WAITING' : 'FAILED', retry ? '等待重试' : '生成失败', retry ? 10 : 0, message, workerId, code)
  })()
}

export function requestCancelGenerationTask(taskId: number, projectId: string) {
  const database = getDatabase(); const current = getTaskRow(taskId, projectId)
  if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(current.status)) return serializeTask(current)
  database.transaction(() => {
    const next = current.status === 'QUEUED' || current.status === 'RETRY_WAITING' ? 'CANCELLED' : 'CANCEL_REQUESTED'
    database.prepare("UPDATE generation_tasks SET status = ?, stage = ?, cancelled_at = CASE WHEN ? = 'CANCELLED' THEN strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') ELSE cancelled_at END, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(next, next === 'CANCELLED' ? '已取消' : '等待取消', next, taskId)
    database.prepare("UPDATE results SET generation_status = CASE WHEN ? = 'CANCELLED' THEN 'CANCELLED' ELSE generation_status END WHERE task_id = ?").run(next, taskId)
    if (next === 'CANCELLED') refreshGenerationBatch(database, current.batch_id)
    addEvent(database, taskId, next === 'CANCELLED' ? 'CANCELLED' : 'CANCEL_REQUESTED', current.status, next, next === 'CANCELLED' ? '已取消' : '等待取消', current.progress, '收到取消请求')
  })()
  return serializeTask(getTaskRow(taskId, projectId))
}

export function getTaskInputs(taskId: number) { return getDatabase().prepare('SELECT position, role, instruction, storage_key, original_name, content_type FROM generation_task_inputs WHERE task_id = ? ORDER BY position ASC').all(taskId) as Array<{position: number; role: string; instruction: string; storage_key: string; original_name: string; content_type: string}> }

export function listTaskEvents(taskId: number, projectId: string) {
  getTaskRow(taskId, projectId)
  return getDatabase().prepare('SELECT event_type AS type, from_status AS fromStatus, to_status AS toStatus, stage, progress, message, error_code AS errorCode, worker_id AS workerId, created_at AS createdAt FROM generation_task_events WHERE task_id = ? ORDER BY created_at ASC, id ASC').all(taskId).map((event) => ({...event as Record<string, unknown>, createdAt: iso(String((event as {createdAt: string}).createdAt))}))
}

export {serializeTask, getTaskRow}
