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
}

type TaskRow = {
  id: number
  project_id: string
  workflow_id: number | null
  status: string
  progress: number
  created_at: string
  updated_at: string
  provider_id: number | null
  model_id: number | null
  provider_name: string
  provider_type: string
  model: string
  workflow_name: string
  workflow_version: string
  media: string
  batch_index: number
  batch_count: number
  prompt: string
}

const TASK_STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED'] as const
const MEDIA_TYPES = ['IMAGE', 'VIDEO'] as const

export function getGenerationTaskProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) {
    throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  }
  return projectId
}

export function getGenerationTaskId(event: H3Event) {
  const taskId = Number(getRouterParam(event, 'taskId'))
  if (!Number.isSafeInteger(taskId) || taskId <= 0) {
    throw createError({statusCode: 400, statusMessage: '生成任务 ID 必须是正整数。'})
  }
  return taskId
}

function readPositiveInteger(value: unknown, field: string) {
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw createError({statusCode: 400, statusMessage: `${field}必须是正整数。`})
  }
  return parsed
}

function readOptionalString(value: unknown, field: string, maxLength: number) {
  if (value === undefined || value === null) return ''
  if (typeof value !== 'string') throw createError({statusCode: 400, statusMessage: `${field}必须是文本。`})
  const result = value.trim()
  if (result.length > maxLength) throw createError({statusCode: 400, statusMessage: `${field}长度不能超过 ${maxLength} 个字符。`})
  return result
}

function readMedia(value: unknown) {
  if (typeof value !== 'string' || !MEDIA_TYPES.includes(value as typeof MEDIA_TYPES[number])) {
    throw createError({statusCode: 400, statusMessage: '生成类型必须是 IMAGE 或 VIDEO。'})
  }
  return value as typeof MEDIA_TYPES[number]
}

function serializeTask(row: TaskRow) {
  return {
    id: row.id,
    projectId: row.project_id,
    workflowId: row.workflow_id,
    workflowName: row.workflow_name,
    workflowVersion: row.workflow_version,
    media: row.media,
    type: row.media === 'VIDEO' ? '视频' : '图片',
    status: row.status,
    statusLabel: row.status === 'RUNNING' ? '生成中' : row.status === 'COMPLETED' ? '已完成' : row.status === 'CANCELLED' ? '已取消' : '排队中',
    progress: row.progress,
    provider: {
      id: row.provider_id,
      name: row.provider_name,
      type: row.provider_type,
      modelId: row.model_id,
      model: row.model,
    },
    batchIndex: row.batch_index,
    batchCount: row.batch_count,
    prompt: row.prompt,
    createdAt: row.created_at.replace(' ', 'T'),
    updatedAt: row.updated_at.replace(' ', 'T'),
  }
}

function getTaskRow(taskId: number, projectId: string) {
  const row = getDatabase().prepare(`
    SELECT generation_tasks.id, generation_tasks.project_id, generation_tasks.workflow_id,
      generation_tasks.status, generation_tasks.progress, generation_tasks.created_at, generation_tasks.updated_at,
      generation_task_specs.provider_id, generation_task_specs.model_id, COALESCE(generation_task_specs.provider_name, '') AS provider_name,
      COALESCE(generation_task_specs.provider_type, '') AS provider_type, COALESCE(generation_task_specs.model, '') AS model,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name, COALESCE(generation_task_specs.workflow_version, '') AS workflow_version,
      COALESCE(generation_task_specs.media, 'IMAGE') AS media, COALESCE(generation_task_specs.batch_index, 1) AS batch_index,
      COALESCE(generation_task_specs.batch_count, 1) AS batch_count, COALESCE(generation_task_specs.prompt, '') AS prompt
    FROM generation_tasks
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id
    WHERE generation_tasks.id = ? AND generation_tasks.project_id = ?
  `).get(taskId, projectId) as TaskRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '生成任务不存在。'})
  return row
}

export function getGenerationTask(taskId: number, projectId: string) {
  return serializeTask(getTaskRow(taskId, projectId))
}

export function listGenerationTasks(projectId: string) {
  const rows = getDatabase().prepare(`
    SELECT generation_tasks.id, generation_tasks.project_id, generation_tasks.workflow_id,
      generation_tasks.status, generation_tasks.progress, generation_tasks.created_at, generation_tasks.updated_at,
      generation_task_specs.provider_id, generation_task_specs.model_id, COALESCE(generation_task_specs.provider_name, '') AS provider_name,
      COALESCE(generation_task_specs.provider_type, '') AS provider_type, COALESCE(generation_task_specs.model, '') AS model,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name, COALESCE(generation_task_specs.workflow_version, '') AS workflow_version,
      COALESCE(generation_task_specs.media, 'IMAGE') AS media, COALESCE(generation_task_specs.batch_index, 1) AS batch_index,
      COALESCE(generation_task_specs.batch_count, 1) AS batch_count, COALESCE(generation_task_specs.prompt, '') AS prompt
    FROM generation_tasks
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id
    WHERE generation_tasks.project_id = ?
    ORDER BY generation_tasks.created_at DESC, generation_tasks.id DESC
    LIMIT 200
  `).all(projectId) as TaskRow[]
  return rows.map(serializeTask)
}

export function createGenerationTasks(projectId: string, body: GenerationTaskInput) {
  const database = getDatabase()
  const project = database.prepare('SELECT id FROM projects WHERE id = ?').get(projectId) as {id: string} | undefined
  if (!project) throw createError({statusCode: 404, statusMessage: '项目不存在。'})

  const providerId = readPositiveInteger(body.provider_id, '模型提供商 ID')
  const modelId = readPositiveInteger(body.model_id, '模型 ID')
  const provider = database.prepare('SELECT id, name, type, enabled FROM api_providers WHERE id = ?').get(providerId) as {id: number; name: string; type: string; enabled: number} | undefined
  if (!provider) throw createError({statusCode: 404, statusMessage: '模型提供商不存在。'})
  if (!provider.enabled) throw createError({statusCode: 400, statusMessage: '只能使用已启用的模型提供商。'})
  const model = database.prepare('SELECT id, model FROM api_provider_models WHERE id = ? AND provider_id = ?').get(modelId, providerId) as {id: number; model: string} | undefined
  if (!model) throw createError({statusCode: 400, statusMessage: '模型 ID 不属于所选模型提供商。'})

  const batchCount = Number(body.batch_count)
  if (!Number.isInteger(batchCount) || batchCount < 1 || batchCount > 12) {
    throw createError({statusCode: 400, statusMessage: '批量数量必须是 1-12 的整数。'})
  }

  let workflowId: number | null = null
  let workflowName = readOptionalString(body.workflow_name, '工作流名称', 120)
  let workflowVersion = readOptionalString(body.workflow_version, '工作流版本', 80)
  let media: typeof MEDIA_TYPES[number] = 'IMAGE'
  let prompt = readOptionalString(body.prompt, '提示词', 5000)
  if (body.workflow_id !== undefined && body.workflow_id !== null && body.workflow_id !== '') {
    workflowId = readPositiveInteger(body.workflow_id, '工作流 ID')
    const workflow = database.prepare('SELECT id, name, version, definition_json FROM workflows WHERE id = ? AND project_id = ?').get(workflowId, projectId) as {id: number; name: string; version: number; definition_json: string} | undefined
    if (!workflow) throw createError({statusCode: 404, statusMessage: '工作流不存在或不属于当前项目。'})
    workflowName = workflow.name
    workflowVersion = `IMAGE · V${workflow.version}`
    media = 'IMAGE'
    try {
      const definition = JSON.parse(workflow.definition_json) as {creativePrompt?: unknown}
      prompt = typeof definition.creativePrompt === 'string' ? definition.creativePrompt.slice(0, 5000) : ''
    } catch {
      throw createError({statusCode: 500, statusMessage: '工作流定义数据损坏。'})
    }
  } else {
    media = readMedia(body.media)
  }
  if (!workflowName) throw createError({statusCode: 400, statusMessage: '工作流名称不能为空。'})
  if (!workflowVersion) throw createError({statusCode: 400, statusMessage: '工作流版本不能为空。'})

  const insertTask = database.prepare("INSERT INTO generation_tasks (project_id, workflow_id, status, progress, updated_at) VALUES (?, ?, 'QUEUED', 0, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))")
  const insertSpec = database.prepare('INSERT INTO generation_task_specs (task_id, provider_id, model_id, provider_name, provider_type, model, workflow_name, workflow_version, media, batch_index, batch_count, prompt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
  const taskIds = database.transaction(() => {
    const ids: number[] = []
    for (let batchIndex = 1; batchIndex <= batchCount; batchIndex += 1) {
      const result = insertTask.run(projectId, workflowId)
      const taskId = Number(result.lastInsertRowid)
      insertSpec.run(taskId, provider.id, model.id, provider.name, provider.type, model.model, workflowName, workflowVersion, media, batchIndex, batchCount, prompt)
      ids.push(taskId)
    }
    return ids
  })()
  return taskIds.map((taskId) => serializeTask(getTaskRow(taskId, projectId)))
}

export function updateGenerationTask(taskId: number, projectId: string, body: {status?: unknown; progress?: unknown}) {
  const current = getTaskRow(taskId, projectId)
  if (typeof body.status !== 'string' || !TASK_STATUSES.includes(body.status as typeof TASK_STATUSES[number])) {
    throw createError({statusCode: 400, statusMessage: '任务状态必须是 QUEUED、RUNNING、COMPLETED 或 CANCELLED。'})
  }
  const progress = body.progress === undefined ? current.progress : Number(body.progress)
  if (!Number.isInteger(progress) || progress < 0 || progress > 100) {
    throw createError({statusCode: 400, statusMessage: '任务进度必须是 0-100 的整数。'})
  }
  if (current.status === 'COMPLETED' && body.status !== 'COMPLETED') {
    throw createError({statusCode: 409, statusMessage: '已完成任务不能再次修改状态。'})
  }
  if (current.status === 'CANCELLED' && body.status !== 'CANCELLED') {
    throw createError({statusCode: 409, statusMessage: '已取消任务不能再次修改状态。'})
  }
  getDatabase().prepare("UPDATE generation_tasks SET status = ?, progress = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ? AND project_id = ?").run(body.status, progress, taskId, projectId)
  return serializeTask(getTaskRow(taskId, projectId))
}
