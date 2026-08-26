import {createError, getQuery, getRouterParam, setHeader} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getFileRow, serializeFile, verifyStoredFile} from '../../utils/file-records'
import {readStoredFile} from '../../utils/storage'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const fileId = Number(getRouterParam(event, 'id'))
  if (!Number.isSafeInteger(fileId) || fileId <= 0) throw createError({statusCode: 400, statusMessage: '文件 ID 必须是正整数。'})
  const row = getFileRow(fileId)
  await verifyStoredFile(row)
  setHeader(event, 'content-type', row.content_type)
  setHeader(event, 'content-length', row.size_bytes)
  const download = getQuery(event).download === '1'
  setHeader(event, 'content-disposition', `${download ? 'attachment' : 'inline'}; filename*=UTF-8''${encodeURIComponent(row.original_name)}`)
  setHeader(event, 'cache-control', 'private, no-store')
  return readStoredFile(row.storage_key)
})
