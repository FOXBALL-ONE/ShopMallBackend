import {createError} from 'h3'
import {getDatabase} from './database'
import {removeStoredFile, storedFileExists} from './storage'

export type FileRow = {
  id: number
  storage_key: string
  original_name: string
  content_type: string
  size_bytes: number
  sha256: string
  created_at: string
  updated_at: string
}

export function serializeFile(row: FileRow) {
  return {
    id: row.id,
    originalName: row.original_name,
    contentType: row.content_type,
    sizeBytes: row.size_bytes,
    sha256: row.sha256,
    createdAt: row.created_at.replace(' ', 'T'),
    updatedAt: row.updated_at.replace(' ', 'T'),
    downloadUrl: `/api/files/${row.id}`,
  }
}

export function getFileRow(fileId: number) {
  const row = getDatabase().prepare('SELECT id, storage_key, original_name, content_type, size_bytes, sha256, created_at, updated_at FROM stored_files WHERE id = ?').get(fileId) as FileRow | undefined
  if (!row) throw createError({statusCode: 404, statusMessage: '文件不存在。'})
  return row
}

export async function deleteFileRecord(fileId: number) {
  const row = getFileRow(fileId)
  const references = getDatabase().prepare('SELECT COUNT(*) AS count FROM asset_library WHERE file_id = ?').get(fileId) as {count: number}
  if (references.count > 0) throw createError({statusCode: 409, statusMessage: '文件仍被素材引用，请先删除对应素材。'})
  await removeStoredFile(row.storage_key)
  getDatabase().prepare('DELETE FROM stored_files WHERE id = ?').run(fileId)
  return serializeFile(row)
}

export async function verifyStoredFile(row: FileRow) {
  if (!await storedFileExists(row.storage_key)) {
    throw createError({statusCode: 410, statusMessage: '文件内容已丢失，请重新上传。'})
  }
}

export async function purgeOrphanedFiles() {
  const rows = getDatabase().prepare('SELECT id, storage_key, original_name, content_type, size_bytes, sha256, created_at, updated_at FROM stored_files WHERE id NOT IN (SELECT file_id FROM asset_library)').all() as FileRow[]
  for (const row of rows) {
    await removeStoredFile(row.storage_key)
    getDatabase().prepare('DELETE FROM stored_files WHERE id = ?').run(row.id)
  }
  return rows.length
}
