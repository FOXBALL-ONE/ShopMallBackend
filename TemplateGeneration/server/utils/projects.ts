import {createError, getRouterParam, type H3Event} from 'h3'
import {randomUUID} from 'node:crypto'
import {getDatabase} from './database'

type ProjectRow = {
  id: string
  name: string
  season: string
  status: 'ACTIVE' | 'ARCHIVED' | string
  created_at: string
  updated_at: string
  assets?: number
  workflows?: number
  tasks?: number
}

export type ProjectInput = {
  id?: unknown
  name?: unknown
  season?: unknown
  status?: unknown
}

function readText(value: unknown, field: string, maxLength: number, required = true) {
  if (value === undefined || value === null) {
    if (required) throw createError({statusCode: 400, statusMessage: `${field}不能为空。`})
    return ''
  }
  if (typeof value !== 'string') throw createError({statusCode: 400, statusMessage: `${field}必须是文本。`})
  const text = value.trim()
  if (required && !text) throw createError({statusCode: 400, statusMessage: `${field}不能为空。`})
  if (text.length > maxLength) throw createError({statusCode: 400, statusMessage: `${field}长度不能超过 ${maxLength} 个字符。`})
  return text
}

function serializeProject(row: ProjectRow) {
  return {
    id: row.id,
    code: row.id,
    name: row.name,
    season: row.season,
    status: row.status === 'ARCHIVED' ? 'ARCHIVED' : 'ACTIVE',
    assets: Number(row.assets ?? 0),
    workflows: Number(row.workflows ?? 0),
    tasks: Number(row.tasks ?? 0),
    createdAt: row.created_at.replace(' ', 'T'),
    updatedAt: row.updated_at.replace(' ', 'T'),
  }
}

export function getProjectRouteId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) {
    throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  }
  return projectId
}

export function getProject(projectId: string) {
  const row = getDatabase().prepare(`
    SELECT projects.id, projects.name, projects.season, projects.created_at, projects.updated_at,
      (SELECT COUNT(*) FROM asset_library WHERE asset_library.project_id = projects.id OR asset_library.scope = 'GLOBAL' OR asset_library.project_id = '__global__') AS assets,
      (SELECT COUNT(*) FROM workflows WHERE workflows.project_id = projects.id) AS workflows,
      (SELECT COUNT(*) FROM generation_tasks WHERE generation_tasks.project_id = projects.id) AS tasks,
      projects.status
    FROM projects WHERE projects.id = ?
  `).get(projectId) as ProjectRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '项目不存在。'})
  return serializeProject(row)
}

export function listProjects() {
  const rows = getDatabase().prepare(`
    SELECT projects.id, projects.name, projects.season, projects.created_at, projects.updated_at,
      (SELECT COUNT(*) FROM asset_library WHERE asset_library.project_id = projects.id OR asset_library.scope = 'GLOBAL' OR asset_library.project_id = '__global__') AS assets,
      (SELECT COUNT(*) FROM workflows WHERE workflows.project_id = projects.id) AS workflows,
      (SELECT COUNT(*) FROM generation_tasks WHERE generation_tasks.project_id = projects.id) AS tasks,
      projects.status
    FROM projects ORDER BY CASE WHEN projects.status = 'ACTIVE' THEN 0 ELSE 1 END, projects.updated_at DESC, projects.id ASC
  `).all() as ProjectRow[]
  return rows.map(serializeProject)
}

export function createProject(body: ProjectInput) {
  const name = readText(body.name, '项目名称', 120)
  const season = readText(body.season, '系列/季节', 80, false) || '未设置季节'
  const suppliedId = body.id === undefined || body.id === null || body.id === '' ? '' : readText(body.id, '项目 ID', 120)
  const projectId = suppliedId || `prj_${name.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '').slice(0, 70) || 'project'}_${randomUUID().slice(0, 8)}`
  if (!/^[a-zA-Z0-9_-]+$/.test(projectId)) throw createError({statusCode: 400, statusMessage: '项目 ID 只能包含字母、数字、下划线和连字符。'})

  try {
    getDatabase().prepare("INSERT INTO projects (id, name, season, updated_at) VALUES (?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(projectId, name, season)
  } catch (error: unknown) {
    if ((error as {code?: string}).code === 'SQLITE_CONSTRAINT_PRIMARYKEY' || (error as {code?: string}).code === 'SQLITE_CONSTRAINT_UNIQUE') {
      throw createError({statusCode: 409, statusMessage: '项目 ID 已存在，请更换后重试。'})
    }
    throw error
  }
  return getProject(projectId)
}

export function updateProject(projectId: string, body: ProjectInput) {
  const current = getProject(projectId)
  const name = body.name === undefined ? current.name : readText(body.name, '项目名称', 120)
  const season = body.season === undefined ? current.season : (readText(body.season, '系列/季节', 80, false) || '未设置季节')
  const status = body.status === undefined ? current.status : body.status
  if (status !== 'ACTIVE' && status !== 'ARCHIVED') throw createError({statusCode: 400, statusMessage: '工程状态只能是 ACTIVE 或 ARCHIVED。'})
  getDatabase().prepare("UPDATE projects SET name = ?, season = ?, status = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(name, season, status, projectId)
  return getProject(projectId)
}

export function deleteProject(projectId: string) {
  const project = getProject(projectId)
  // Keep the endpoint backwards-compatible while preserving the complete
  // project workspace (workflows, tasks and results). Lifecycle removal is
  // represented as an archived state and can be reversed with PATCH.
  getDatabase().prepare("UPDATE projects SET status = 'ARCHIVED', updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(projectId)
  return {...project, status: 'ARCHIVED'}
}
