import {createError, getRouterParam} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getFileRow, serializeFile} from '../../utils/file-records'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  const fileId = Number(getRouterParam(event, 'id'))
  if (!Number.isSafeInteger(fileId) || fileId <= 0) throw createError({statusCode: 400, statusMessage: '文件 ID 必须是正整数。'})
  return {file: serializeFile(getFileRow(fileId))}
})
