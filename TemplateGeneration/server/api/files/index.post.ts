import {readMultipartFormData, createError} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getDatabase} from '../../utils/database'
import {writeStoredFile, removeStoredFile} from '../../utils/storage'
import {serializeFile} from '../../utils/file-records'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const parts = await readMultipartFormData(event)
  const filePart = parts?.find((part) => part.name === 'file' && part.data?.length)
  if (!filePart?.data || !filePart.filename || !filePart.type) {
    throw createError({statusCode: 400, statusMessage: '请通过 file 字段上传文件。'})
  }

  const stored = await writeStoredFile(filePart.data, filePart.filename, filePart.type)
  try {
    const result = getDatabase().prepare("INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, updated_at) VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(stored.storageKey, stored.originalName, stored.contentType, stored.sizeBytes, stored.sha256)
    const row = getDatabase().prepare('SELECT id, storage_key, original_name, content_type, size_bytes, sha256, created_at, updated_at FROM stored_files WHERE id = ?').get(result.lastInsertRowid)
    return {file: serializeFile(row as Parameters<typeof serializeFile>[0])}
  } catch (error) {
    await removeStoredFile(stored.storageKey)
    throw error
  }
})
