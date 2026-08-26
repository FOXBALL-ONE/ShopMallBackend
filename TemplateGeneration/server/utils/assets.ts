import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'
import {deleteFileRecord, getFileRow, serializeFile} from './file-records'

export const ASSET_TYPES = ['GARMENT', 'MODEL', 'REFERENCE'] as const
export type AssetType = typeof ASSET_TYPES[number]

type AssetRow = {
  id: number
  project_id: string
  scope?: 'GLOBAL' | 'PROJECT' | string
  file_id: number
  type: AssetType
  name: string
  code: string
  description: string
  tags_json: string
  authorization_status: string | null
  created_at: string
  updated_at: string
}

export function getProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) {
    throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  }
  return projectId
}

function assertProjectExists(projectId: string) {
  const project = getDatabase().prepare('SELECT id FROM projects WHERE id = ?').get(projectId) as {id: string} | undefined
  if (!project) throw createError({statusCode: 404, statusMessage: '项目不存在。'})
}

function parseTags(tagsJson: string) {
  try {
    const tags = JSON.parse(tagsJson)
    return Array.isArray(tags) ? tags.filter((tag): tag is string => typeof tag === 'string') : []
  } catch {
    return []
  }
}

export function serializeAsset(row: AssetRow) {
  const file = getFileRow(row.file_id)
  return {
    id: row.id,
    projectId: row.scope === 'GLOBAL' || row.project_id === '__global__' ? null : row.project_id,
    scope: row.scope === 'GLOBAL' || row.project_id === '__global__' ? 'GLOBAL' : 'PROJECT',
    fileId: row.file_id,
    type: row.type,
    name: row.name,
    code: row.code,
    description: row.description,
    tags: parseTags(row.tags_json),
    authorizationStatus: row.authorization_status,
    createdAt: row.created_at.replace(' ', 'T'),
    updatedAt: row.updated_at.replace(' ', 'T'),
    file: serializeFile(file),
  }
}

export function listAssets(projectId: string, type?: string, query?: string) {
  assertProjectExists(projectId)
  const clauses = ['(asset_library.project_id = ? OR asset_library.project_id = ?)']
  const params: Array<string> = [projectId, '__global__']
  if (type && type !== 'ALL') {
    if (!ASSET_TYPES.includes(type as AssetType)) throw createError({statusCode: 400, statusMessage: '素材类型不正确。'})
    clauses.push('asset_library.type = ?')
    params.push(type)
  }
  if (query?.trim()) {
    clauses.push('(asset_library.name LIKE ? OR asset_library.code LIKE ? OR asset_library.description LIKE ? OR asset_library.tags_json LIKE ?)')
    const normalized = `%${query.trim()}%`
    params.push(normalized, normalized, normalized, normalized)
  }
  const rows = getDatabase().prepare(`
    SELECT asset_library.id, asset_library.project_id, asset_library.scope, asset_library.file_id, asset_library.type, asset_library.name, asset_library.code, asset_library.description, asset_library.tags_json, asset_library.authorization_status, asset_library.created_at, asset_library.updated_at,
      stored_files.original_name, stored_files.content_type, stored_files.size_bytes, stored_files.sha256
    FROM asset_library INNER JOIN stored_files ON stored_files.id = asset_library.file_id
    WHERE ${clauses.join(' AND ')}
    ORDER BY asset_library.id DESC
  `).all(...params) as Array<AssetRow & {original_name: string; content_type: string; size_bytes: number; sha256: string}>
  return rows.map((row) => {
    const {original_name, content_type, size_bytes, sha256, ...assetRow} = row
    return {
      id: assetRow.id,
      projectId: assetRow.scope === 'GLOBAL' || assetRow.project_id === '__global__' ? null : assetRow.project_id,
      scope: assetRow.scope === 'GLOBAL' || assetRow.project_id === '__global__' ? 'GLOBAL' : 'PROJECT',
      fileId: assetRow.file_id,
      type: assetRow.type,
      name: assetRow.name,
      code: assetRow.code,
      description: assetRow.description,
      tags: parseTags(assetRow.tags_json),
      authorizationStatus: assetRow.authorization_status,
      createdAt: assetRow.created_at.replace(' ', 'T'),
      updatedAt: assetRow.updated_at.replace(' ', 'T'),
      file: {id: assetRow.file_id, originalName: original_name, contentType: content_type, sizeBytes: size_bytes, sha256, createdAt: assetRow.created_at.replace(' ', 'T'), updatedAt: assetRow.updated_at.replace(' ', 'T'), downloadUrl: `/api/files/${assetRow.file_id}`},
    }
  })
}

export function getAsset(assetId: number, projectId?: string) {
  if (projectId) assertProjectExists(projectId)
  const clauses = ['id = ?']
  const params: Array<string | number> = [assetId]
  if (projectId) {
    clauses.push('(project_id = ? OR project_id = ?)')
    params.push(projectId, '__global__')
  }
  const row = getDatabase().prepare(`SELECT id, project_id, scope, file_id, type, name, code, description, tags_json, authorization_status, created_at, updated_at FROM asset_library WHERE ${clauses.join(' AND ')}`).get(...params) as AssetRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '素材不存在。'})
  return row
}

export function createAsset(input: {projectId: string; scope?: 'GLOBAL' | 'PROJECT'; fileId: number; type: AssetType; name: string; code: string; description: string; tags: string[]; authorizationStatus?: string | null}) {
  assertProjectExists(input.projectId)
  if (!input.name.trim() || input.name.length > 120) throw createError({statusCode: 400, statusMessage: '素材名称不能为空且不能超过 120 个字符。'})
  if (!input.code.trim() || input.code.length > 80) throw createError({statusCode: 400, statusMessage: '素材款号不能为空且不能超过 80 个字符。'})
  if (!ASSET_TYPES.includes(input.type)) throw createError({statusCode: 400, statusMessage: '素材类型不正确。'})
  const database = getDatabase()
  try {
    const scope = input.scope === 'GLOBAL' ? 'GLOBAL' : 'PROJECT'
    const ownerProjectId = scope === 'GLOBAL' ? '__global__' : input.projectId
    const result = database.prepare("INSERT INTO asset_library (project_id, scope, file_id, type, name, code, description, tags_json, authorization_status, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(ownerProjectId, scope, input.fileId, input.type, input.name.trim(), input.code.trim(), input.description.trim(), JSON.stringify(input.tags.slice(0, 30)), input.authorizationStatus ?? null)
    return getAsset(Number(result.lastInsertRowid), input.projectId)
  } catch (error: unknown) {
    if ((error as {code?: string}).code === 'SQLITE_CONSTRAINT_UNIQUE') throw createError({statusCode: 409, statusMessage: '当前项目中已存在相同款号。'})
    throw error
  }
}

export async function deleteAsset(assetId: number, projectId: string) {
  const row = getAsset(assetId, projectId)
  const serialized = serializeAsset(row)
  const database = getDatabase()
  database.prepare('DELETE FROM asset_library WHERE id = ?').run(assetId)
  try {
    await deleteFileRecord(row.file_id)
  } catch (error: unknown) {
    database.prepare('INSERT OR IGNORE INTO asset_library (id, project_id, scope, file_id, type, name, code, description, tags_json, authorization_status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)').run(row.id, row.project_id, row.scope ?? 'PROJECT', row.file_id, row.type, row.name, row.code, row.description, row.tags_json, row.authorization_status, row.created_at, row.updated_at)
    if ((error as {statusCode?: number}).statusCode !== 404) throw error
  }
  return serialized
}

export function updateAssetScope(assetId: number, projectId: string, scope: unknown) {
  if (scope !== 'GLOBAL' && scope !== 'PROJECT') throw createError({statusCode: 400, statusMessage: '共享范围必须是 GLOBAL 或 PROJECT。'})
  const row = getAsset(assetId, projectId)
  const ownerProjectId = scope === 'GLOBAL' ? '__global__' : projectId
  try {
    getDatabase().prepare("UPDATE asset_library SET project_id = ?, scope = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?").run(ownerProjectId, scope, assetId)
  } catch (error: unknown) {
    if ((error as {code?: string}).code === 'SQLITE_CONSTRAINT_UNIQUE') throw createError({statusCode: 409, statusMessage: '目标共享范围中已存在相同款号。'})
    throw error
  }
  return serializeAsset({...row, project_id: ownerProjectId, scope: scope as string})
}

export function getAssetId(event: H3Event) {
  const assetId = Number(getRouterParam(event, 'assetId'))
  if (!Number.isSafeInteger(assetId) || assetId <= 0) throw createError({statusCode: 400, statusMessage: '素材 ID 必须是正整数。'})
  return assetId
}
