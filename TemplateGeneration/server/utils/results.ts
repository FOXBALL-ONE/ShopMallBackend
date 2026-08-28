import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'
import {getFileRow, serializeFile, type FileRow} from './file-records'

export const RESULT_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const
export type ResultStatus = typeof RESULT_STATUSES[number]

type ResultRow = {
  id: number; project_id: string; task_id: number | null; media: string; status: ResultStatus; generation_status: string; prompt: string; uri: string | null
  file_id: number | null; content_type: string | null; size_bytes: number | null; sha256: string | null; error_code: string | null; error_message: string | null
  generated_at: string | null; created_at: string; workflow_name: string; workflow_version: string; task_status: string | null; task_progress: number | null; task_stage: string | null
  provider_id: number | null; provider_name: string | null; model_id: number | null; model: string | null
}

export function getResultProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  return projectId
}

export function getResultId(event: H3Event) {
  const resultId = Number(getRouterParam(event, 'resultId'))
  if (!Number.isSafeInteger(resultId) || resultId <= 0) throw createError({statusCode: 400, statusMessage: '结果 ID 必须是正整数。'})
  return resultId
}

function iso(value: string | null | undefined) { return value ? value.replace(' ', 'T') : null }

function resultQuery() {
  return `
    SELECT results.id, results.project_id, results.task_id, results.media, results.status, results.generation_status,
      results.prompt, results.uri, results.file_id, results.content_type, results.size_bytes, results.sha256,
      results.error_code, results.error_message, results.generated_at, results.created_at,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name,
      COALESCE(generation_task_specs.workflow_version, '') AS workflow_version,
      generation_tasks.status AS task_status, generation_tasks.progress AS task_progress, generation_tasks.stage AS task_stage,
      generation_task_specs.provider_id, generation_task_specs.provider_name, generation_task_specs.model_id, generation_task_specs.model
    FROM results
    LEFT JOIN generation_tasks ON generation_tasks.id = results.task_id
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = results.task_id
  `
}

function serializeResult(row: ResultRow) {
  const file = row.file_id ? (() => {
    try { return serializeFile(getFileRow(row.file_id)) } catch { return null }
  })() : null
  const version = Number(row.workflow_version.match(/V(\d+)/i)?.[1] ?? 0)
  return {
    id: row.id, projectId: row.project_id, taskId: row.task_id, workflow: row.workflow_name || '未命名工作流', version,
    workflowVersion: row.workflow_version, media: row.media === 'VIDEO' ? 'VIDEO' : 'IMAGE', status: row.status,
    reviewStatus: row.status, generationStatus: row.generation_status, prompt: row.prompt, uri: row.uri, file,
    contentType: row.content_type, sizeBytes: row.size_bytes, sha256: row.sha256, errorCode: row.error_code, errorMessage: row.error_message,
    progress: row.task_progress ?? (row.generation_status === 'READY' ? 100 : 0), stage: row.task_stage ?? (row.generation_status === 'READY' ? '已完成' : '生成中'),
    provider: {id: row.provider_id, name: row.provider_name, modelId: row.model_id, model: row.model},
    createdAt: iso(row.created_at), generatedAt: iso(row.generated_at),
  }
}

export function createResultForTask(projectId: string, taskId: number, prompt: string) {
  const database = getDatabase()
  database.prepare("INSERT OR IGNORE INTO results (project_id, task_id, media, status, generation_status, prompt) VALUES (?, ?, 'IMAGE', 'PENDING', 'GENERATING', ?)").run(projectId, taskId, prompt)
}

export function completeResultForTask(taskId: number, fileId: number, uri: string, contentType: string, sizeBytes: number, sha256: string, generatedAt: string, upstreamRequestId: string | null) {
  const database = getDatabase()
  const result = database.prepare("UPDATE results SET generation_status = 'READY', file_id = ?, uri = ?, content_type = ?, size_bytes = ?, sha256 = ?, generated_at = ?, upstream_request_id = ?, error_code = NULL, error_message = NULL WHERE task_id = ?").run(fileId, uri, contentType, sizeBytes, sha256, generatedAt, upstreamRequestId, taskId)
  if (result.changes === 0) throw createError({statusCode: 500, statusMessage: '生成结果记录不存在。'})
  const row = database.prepare('SELECT id FROM results WHERE task_id = ?').get(taskId) as {id: number} | undefined
  if (!row) throw createError({statusCode: 500, statusMessage: '生成结果记录读取失败。'})
  return row.id
}

export function failResultForTask(taskId: number, code: string, message: string, terminal: boolean) {
  if (terminal) getDatabase().prepare("UPDATE results SET generation_status = 'FAILED', error_code = ?, error_message = ? WHERE task_id = ?").run(code, message.slice(0, 1000), taskId)
}

export function ensureCompletedTaskResults(projectId: string) {
  const database = getDatabase()
  const tasks = database.prepare(`SELECT generation_tasks.id, generation_tasks.project_id, COALESCE(generation_task_specs.prompt, '') AS prompt FROM generation_tasks LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id WHERE generation_tasks.project_id = ? AND generation_tasks.status = 'COMPLETED' AND NOT EXISTS (SELECT 1 FROM results WHERE results.task_id = generation_tasks.id)`).all(projectId) as Array<{id: number; project_id: string; prompt: string}>
  const insert = database.prepare("INSERT INTO results (project_id, task_id, media, status, generation_status, prompt) VALUES (?, ?, 'IMAGE', 'PENDING', 'READY', ?)")
  database.transaction(() => tasks.forEach((task) => insert.run(task.project_id, task.id, task.prompt)))()
}

export function listResults(projectId: string) {
  ensureCompletedTaskResults(projectId)
  const rows = getDatabase().prepare(`${resultQuery()} WHERE results.project_id = ? ORDER BY results.created_at DESC, results.id DESC LIMIT 200`).all(projectId) as ResultRow[]
  return rows.map(serializeResult)
}

export function getDownloadableResultFiles(projectId: string, resultIds: number[]) {
  if (!resultIds.length || resultIds.length > 200) throw createError({statusCode: 400, statusMessage: '请选择 1-200 个生成结果。'})
  if (resultIds.some((id) => !Number.isSafeInteger(id) || id <= 0)) throw createError({statusCode: 400, statusMessage: '结果 ID 必须是正整数。'})
  const uniqueIds = [...new Set(resultIds)]
  const placeholders = uniqueIds.map(() => '?').join(', ')
  const rows = getDatabase().prepare(`
    SELECT results.id AS result_id, results.task_id, stored_files.id, stored_files.storage_key,
      stored_files.original_name, stored_files.content_type, stored_files.size_bytes, stored_files.sha256,
      stored_files.created_at, stored_files.updated_at
    FROM results
    INNER JOIN stored_files ON stored_files.id = results.file_id
    WHERE results.project_id = ? AND results.generation_status = 'READY' AND results.id IN (${placeholders})
  `).all(projectId, ...uniqueIds) as Array<FileRow & {result_id: number; task_id: number | null}>
  const rowsByResultId = new Map(rows.map((row) => [row.result_id, row]))
  const orderedRows = uniqueIds.map((id) => rowsByResultId.get(id)).filter((row): row is FileRow & {result_id: number; task_id: number | null} => Boolean(row))
  if (orderedRows.length !== uniqueIds.length) throw createError({statusCode: 400, statusMessage: '所选结果包含未生成完成、文件缺失或不属于当前项目的记录。'})
  return orderedRows
}

export function updateResultReview(resultId: number, projectId: string, value: unknown) {
  if (typeof value !== 'string' || !RESULT_STATUSES.includes(value as ResultStatus)) throw createError({statusCode: 400, statusMessage: '审核状态必须是 PENDING、APPROVED 或 REJECTED。'})
  const database = getDatabase()
  const result = database.prepare("UPDATE results SET status = ? WHERE id = ? AND project_id = ? AND generation_status = 'READY'").run(value, resultId, projectId)
  if (result.changes === 0) throw createError({statusCode: 404, statusMessage: '结果不存在、尚未生成完成或不属于当前项目。'})
  const row = database.prepare(`${resultQuery()} WHERE results.id = ? AND results.project_id = ?`).get(resultId, projectId) as ResultRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '结果不存在或不属于当前项目。'})
  return serializeResult(row)
}
