import {getQuery} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getDatabase} from '../../utils/database'
import {serializeFile, type FileRow} from '../../utils/file-records'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  const query = getQuery(event)
  const limit = Math.min(Math.max(Number(query.limit) || 50, 1), 200)
  const rows = getDatabase().prepare('SELECT id, storage_key, original_name, content_type, size_bytes, sha256, created_at, updated_at FROM stored_files ORDER BY id DESC LIMIT ?').all(limit) as FileRow[]
  return {files: rows.map(serializeFile)}
})
