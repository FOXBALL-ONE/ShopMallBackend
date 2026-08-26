import {createError, readMultipartFormData} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {createAsset, getProjectId, type AssetType} from '../../../../utils/assets'
import {getDatabase} from '../../../../utils/database'
import {writeStoredFile, removeStoredFile} from '../../../../utils/storage'
import {serializeAsset} from '../../../../utils/assets'

function field(parts: Awaited<ReturnType<typeof readMultipartFormData>>, name: string) {
  const value = parts?.find((part) => part.name === name)?.data?.toString('utf8').trim()
  return value ?? ''
}

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const projectId = getProjectId(event)
  const parts = await readMultipartFormData(event)
  const filePart = parts?.find((part) => part.name === 'file' && part.data?.length)
  if (!filePart?.data || !filePart.filename || !filePart.type) throw createError({statusCode: 400, statusMessage: '请通过 file 字段上传素材文件。'})
  const type = field(parts, 'type') as AssetType
  const stored = await writeStoredFile(filePart.data, filePart.filename, filePart.type)
  const fileResult = getDatabase().prepare("INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, updated_at) VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(stored.storageKey, stored.originalName, stored.contentType, stored.sizeBytes, stored.sha256)
  const fileId = Number(fileResult.lastInsertRowid)
  try {
    const scope = field(parts, 'scope') === 'GLOBAL' ? 'GLOBAL' : 'PROJECT'
    const asset = createAsset({projectId, scope, fileId, type, name: field(parts, 'name') || stored.originalName.replace(/\.[^.]+$/, ''), code: field(parts, 'code') || `ASSET-${fileId}`, description: field(parts, 'description'), tags: field(parts, 'tags').split(',').map((tag) => tag.trim()).filter(Boolean), authorizationStatus: type === 'MODEL' ? (field(parts, 'authorized') === 'true' ? '已确认授权' : '授权待确认') : null})
    return {asset: serializeAsset(asset)}
  } catch (error) {
    getDatabase().prepare('DELETE FROM stored_files WHERE id = ?').run(fileId)
    await removeStoredFile(stored.storageKey)
    throw error
  }
})
