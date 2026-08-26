import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'

export const RESULT_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const
export type ResultStatus = typeof RESULT_STATUSES[number]

type ResultRow = {
  id: number
  project_id: string
  task_id: number | null
  media: string
  status: ResultStatus
  prompt: string
  uri: string | null
  created_at: string
  workflow_name: string
  workflow_version: string
}

export function getResultProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) {
    throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  }
  return projectId
}

export function getResultId(event: H3Event) {
  const resultId = Number(getRouterParam(event, 'resultId'))
  if (!Number.isSafeInteger(resultId) || resultId <= 0) {
    throw createError({statusCode: 400, statusMessage: '结果 ID 必须是正整数。'})
  }
  return resultId
}

function serializeResult(row: ResultRow) {
  const version = Number(row.workflow_version.match(/V(\d+)/i)?.[1] ?? 0)
  return {
    id: row.id,
    projectId: row.project_id,
    taskId: row.task_id,
    workflow: row.workflow_name || '未命名工作流',
    version,
    workflowVersion: row.workflow_version,
    media: row.media === 'VIDEO' ? 'VIDEO' : 'IMAGE',
    status: row.status,
    prompt: row.prompt,
    uri: row.uri,
    createdAt: row.created_at.replace(' ', 'T'),
  }
}

function resultQuery() {
  return `
    SELECT results.id, results.project_id, results.task_id, results.media, results.status,
      results.prompt, results.uri, results.created_at,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name,
      COALESCE(generation_task_specs.workflow_version, '') AS workflow_version
    FROM results
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = results.task_id
  `
}

export function ensureCompletedTaskResults(projectId: string) {
  const database = getDatabase()
  const tasks = database.prepare(`
    SELECT generation_tasks.id, generation_tasks.project_id,
      COALESCE(generation_task_specs.media, 'IMAGE') AS media,
      COALESCE(generation_task_specs.prompt, '') AS prompt
    FROM generation_tasks
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id
    WHERE generation_tasks.project_id = ? AND generation_tasks.status = 'COMPLETED'
      AND NOT EXISTS (SELECT 1 FROM results WHERE results.task_id = generation_tasks.id)
  `).all(projectId) as Array<{id: number; project_id: string; media: string; prompt: string}>
  if (!tasks.length) return
  const insert = database.prepare("INSERT INTO results (project_id, task_id, media, status, prompt) VALUES (?, ?, ?, 'PENDING', ?)")
  database.transaction(() => {
    tasks.forEach((task) => insert.run(task.project_id, task.id, task.media === 'VIDEO' ? 'VIDEO' : 'IMAGE', task.prompt))
  })()
}

export function createResultForCompletedTask(taskId: number, projectId: string, media: string, prompt: string) {
  const database = getDatabase()
  const existing = database.prepare('SELECT id FROM results WHERE task_id = ? LIMIT 1').get(taskId) as {id: number} | undefined
  if (!existing) {
    database.prepare("INSERT INTO results (project_id, task_id, media, status, prompt) VALUES (?, ?, ?, 'PENDING', ?)").run(projectId, taskId, media === 'VIDEO' ? 'VIDEO' : 'IMAGE', prompt)
  }
}

export function listResults(projectId: string) {
  ensureCompletedTaskResults(projectId)
  const rows = getDatabase().prepare(`${resultQuery()} WHERE results.project_id = ? ORDER BY results.created_at DESC, results.id DESC LIMIT 200`).all(projectId) as ResultRow[]
  return rows.map(serializeResult)
}

export function updateResultReview(resultId: number, projectId: string, value: unknown) {
  if (typeof value !== 'string' || !RESULT_STATUSES.includes(value as ResultStatus)) {
    throw createError({statusCode: 400, statusMessage: '审核状态必须是 PENDING、APPROVED 或 REJECTED。'})
  }
  const database = getDatabase()
  const result = database.prepare("UPDATE results SET status = ? WHERE id = ? AND project_id = ?").run(value, resultId, projectId)
  if (result.changes === 0) throw createError({statusCode: 404, statusMessage: '结果不存在或不属于当前项目。'})
  const row = database.prepare(`${resultQuery()} WHERE results.id = ? AND results.project_id = ?`).get(resultId, projectId) as ResultRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '结果不存在或不属于当前项目。'})
  return serializeResult(row)
}
