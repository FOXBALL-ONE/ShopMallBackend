import {createError, getRouterParam, type H3Event} from 'h3'
import {getDatabase} from './database'

export type WorkflowInput = {
  name?: unknown
  garment_asset_id?: unknown
  model_asset_id?: unknown
  creative_prompt?: unknown
  negative_prompt?: unknown
  aspect_ratio?: unknown
  camera?: unknown
  lighting?: unknown
  output_count?: unknown
  high_definition?: unknown
  face_consistency?: unknown
  reference_images?: unknown
}

type WorkflowReference = {assetId: number; role: string; instruction: string}

type WorkflowDefinition = {
  garmentAssetId: number
  modelAssetId: number
  creativePrompt: string
  negativePrompt: string
  aspectRatio: string
  camera: string
  lighting: string
  outputCount: number
  highDefinition: boolean
  faceConsistency: boolean
  referenceImages: WorkflowReference[]
}

const REFERENCE_CONTENT_TYPES = ['image/png', 'image/jpeg', 'image/webp'] as const

type WorkflowRow = {
  id: number
  project_id: string
  name: string
  version: number
  definition_json: string
  created_at: string
  updated_at: string
}

export function getWorkflowProjectId(event: H3Event) {
  const projectId = getRouterParam(event, 'projectId')?.trim()
  if (!projectId || projectId.length > 120 || !/^[a-zA-Z0-9_-]+$/.test(projectId)) {
    throw createError({statusCode: 400, statusMessage: '项目 ID 格式不正确。'})
  }
  return projectId
}

export function getWorkflowId(event: H3Event) {
  const workflowId = Number(getRouterParam(event, 'workflowId'))
  if (!Number.isSafeInteger(workflowId) || workflowId <= 0) throw createError({statusCode: 400, statusMessage: '工作流 ID 必须是正整数。'})
  return workflowId
}

function requiredString(value: unknown, field: string, maxLength: number) {
  if (typeof value !== 'string' || !value.trim()) throw createError({statusCode: 400, statusMessage: `${field}不能为空。`})
  const result = value.trim()
  if (result.length > maxLength) throw createError({statusCode: 400, statusMessage: `${field}长度不能超过 ${maxLength} 个字符。`})
  return result
}

function requiredAssetId(value: unknown, field: string) {
  const result = Number(value)
  if (!Number.isSafeInteger(result) || result <= 0) throw createError({statusCode: 400, statusMessage: `${field}必须是正整数素材 ID。`})
  return result
}

function normalizeReferenceImages(value: unknown) {
  if (value === undefined || value === null) return [] as WorkflowReference[]
  if (!Array.isArray(value)) throw createError({statusCode: 400, statusMessage: '参考图必须是对象数组。'})
  if (value.length > 8) throw createError({statusCode: 400, statusMessage: '单个工作流最多支持 8 张参考图。'})
  const references: WorkflowReference[] = []
  value.forEach((item) => {
    if (!item || typeof item !== 'object') throw createError({statusCode: 400, statusMessage: '参考图必须是对象数组。'})
    const record = item as Record<string, unknown>
    const assetId = requiredAssetId(record.asset_id, '参考图素材')
    if (references.some((reference) => reference.assetId === assetId)) throw createError({statusCode: 400, statusMessage: '工作流不能重复选择同一张参考图。'})
    const role = typeof record.role === 'string' && record.role.trim() ? record.role.trim() : 'reference'
    if (role.length > 40) throw createError({statusCode: 400, statusMessage: '参考图角色长度不能超过 40 个字符。'})
    const instruction = typeof record.instruction === 'string' ? record.instruction.trim() : ''
    if (instruction.length > 500) throw createError({statusCode: 400, statusMessage: '参考图文字要求长度不能超过 500 个字符。'})
    references.push({assetId, role, instruction})
  })
  return references
}

function validateDefinition(body: WorkflowInput): WorkflowDefinition {
  const outputCount = Number(body.output_count)
  if (!Number.isInteger(outputCount) || outputCount < 1 || outputCount > 12) throw createError({statusCode: 400, statusMessage: '生成数量必须是 1-12 的整数。'})
  const definition = {
    garmentAssetId: requiredAssetId(body.garment_asset_id, '服装素材'),
    modelAssetId: requiredAssetId(body.model_asset_id, '模特素材'),
    creativePrompt: requiredString(body.creative_prompt, '画面描述', 5000),
    negativePrompt: typeof body.negative_prompt === 'string' ? body.negative_prompt.trim().slice(0, 3000) : '',
    aspectRatio: requiredString(body.aspect_ratio, '画幅比例', 30),
    camera: requiredString(body.camera, '镜头', 80),
    lighting: requiredString(body.lighting, '光线', 80),
    outputCount,
    highDefinition: body.high_definition !== false,
    faceConsistency: body.face_consistency !== false,
    referenceImages: normalizeReferenceImages(body.reference_images),
  }
  return definition
}

function parseDefinition(value: string) {
  try {
    const parsed = JSON.parse(value) as Partial<WorkflowDefinition>
    return {
      garmentAssetId: Number(parsed.garmentAssetId) || 0,
      modelAssetId: Number(parsed.modelAssetId) || 0,
      creativePrompt: typeof parsed.creativePrompt === 'string' ? parsed.creativePrompt : '',
      negativePrompt: typeof parsed.negativePrompt === 'string' ? parsed.negativePrompt : '',
      aspectRatio: typeof parsed.aspectRatio === 'string' ? parsed.aspectRatio : '',
      camera: typeof parsed.camera === 'string' ? parsed.camera : '',
      lighting: typeof parsed.lighting === 'string' ? parsed.lighting : '',
      outputCount: Number(parsed.outputCount) || 1,
      highDefinition: parsed.highDefinition !== false,
      faceConsistency: parsed.faceConsistency !== false,
      referenceImages: Array.isArray(parsed.referenceImages)
        ? parsed.referenceImages.flatMap((item) => {
            if (!item || typeof item !== 'object') return []
            const record = item as Record<string, unknown>
            const assetId = Number(record.assetId)
            if (!Number.isSafeInteger(assetId) || assetId <= 0) return []
            const role = typeof record.role === 'string' && record.role.trim() ? record.role.trim().slice(0, 40) : 'reference'
            const instruction = typeof record.instruction === 'string' ? record.instruction.trim().slice(0, 500) : ''
            return [{assetId, role, instruction}]
          }).slice(0, 8)
        : [],
    }
  } catch {
    throw createError({statusCode: 500, statusMessage: '工作流定义数据损坏。'})
  }
}

function serializeWorkflow(row: WorkflowRow) {
  return {
    id: row.id,
    projectId: row.project_id,
    name: row.name,
    version: row.version,
    versionLabel: `IMAGE · V${row.version}`,
    savedAt: row.updated_at.replace(' ', 'T'),
    createdAt: row.created_at.replace(' ', 'T'),
    definition: parseDefinition(row.definition_json),
  }
}

function getWorkflowRow(workflowId: number, projectId: string) {
  const row = getDatabase().prepare('SELECT id, project_id, name, version, definition_json, created_at, updated_at FROM workflows WHERE id = ? AND project_id = ?').get(workflowId, projectId) as WorkflowRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '工作流不存在。'})
  return row
}

export function listWorkflows(projectId: string) {
  const rows = getDatabase().prepare('SELECT id, project_id, name, version, definition_json, created_at, updated_at FROM workflows WHERE project_id = ? ORDER BY version DESC, id DESC').all(projectId) as WorkflowRow[]
  return rows.map(serializeWorkflow)
}

export function getWorkflow(workflowId: number, projectId: string) {
  return serializeWorkflow(getWorkflowRow(workflowId, projectId))
}

export function createWorkflow(projectId: string, body: WorkflowInput) {
  const name = requiredString(body.name, '工作流名称', 120)
  const definition = validateDefinition(body)
  const database = getDatabase()
  const project = database.prepare('SELECT id FROM projects WHERE id = ?').get(projectId) as {id: string} | undefined
  if (!project) throw createError({statusCode: 404, statusMessage: '项目不存在。'})

  const garment = database.prepare('SELECT id, project_id, type FROM asset_library WHERE id = ?').get(definition.garmentAssetId) as {id: number; project_id: string; type: string} | undefined
  if (!garment || (garment.project_id !== projectId && garment.project_id !== '__global__')) throw createError({statusCode: 400, statusMessage: '服装素材不存在或不属于当前项目。'})
  if (garment.type !== 'GARMENT') throw createError({statusCode: 400, statusMessage: '指定素材不是服装素材。'})

  const model = database.prepare('SELECT id, project_id, type, authorization_status FROM asset_library WHERE id = ?').get(definition.modelAssetId) as {id: number; project_id: string; type: string; authorization_status: string | null} | undefined
  if (!model || (model.project_id !== projectId && model.project_id !== '__global__')) throw createError({statusCode: 400, statusMessage: '模特素材不存在或不属于当前项目。'})
  if (model.type !== 'MODEL') throw createError({statusCode: 400, statusMessage: '指定素材不是模特素材。'})
  if (model.authorization_status !== '已确认授权') throw createError({statusCode: 400, statusMessage: '只能使用已确认授权的模特素材。'})

  definition.referenceImages.forEach((reference) => {
    const asset = database.prepare(`SELECT asset_library.id, asset_library.project_id, stored_files.content_type
      FROM asset_library INNER JOIN stored_files ON stored_files.id = asset_library.file_id
      WHERE asset_library.id = ?`).get(reference.assetId) as {id: number; project_id: string; content_type: string} | undefined
    if (!asset || (asset.project_id !== projectId && asset.project_id !== '__global__')) throw createError({statusCode: 400, statusMessage: '参考素材不存在或不属于当前项目。'})
    if (!REFERENCE_CONTENT_TYPES.includes(asset.content_type as typeof REFERENCE_CONTENT_TYPES[number])) throw createError({statusCode: 415, statusMessage: '图生图参考素材必须是 PNG、JPEG 或 WebP 图片。'})
  })

  const save = database.transaction(() => {
    const nextVersion = database.prepare('SELECT COALESCE(MAX(version), 0) + 1 AS version FROM workflows WHERE project_id = ?').get(projectId) as {version: number}
    return database.prepare("INSERT INTO workflows (project_id, name, version, definition_json, updated_at) VALUES (?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(projectId, name, nextVersion.version, JSON.stringify(definition))
  })
  const result = save.immediate()
  return getWorkflow(Number(result.lastInsertRowid), projectId)
}
