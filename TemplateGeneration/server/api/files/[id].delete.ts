import {createError, getRouterParam} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {deleteFileRecord} from '../../utils/file-records'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const fileId = Number(getRouterParam(event, 'id'))
  if (!Number.isSafeInteger(fileId) || fileId <= 0) throw createError({statusCode: 400, statusMessage: '文件 ID 必须是正整数。'})
  await deleteFileRecord(fileId)
  return {deleted: true, id: fileId}
})
