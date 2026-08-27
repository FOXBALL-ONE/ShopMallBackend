import {createError, readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getGenerationTaskId, getGenerationTaskProjectId, requestCancelGenerationTask} from '../../../../utils/generation-tasks'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<{status?: unknown}>(event)
  if (body?.status !== 'CANCELLED') {
    throw createError({statusCode: 405, statusMessage: '任务状态由服务端 Worker 管理，客户端只能请求取消任务。'})
  }
  return {task: requestCancelGenerationTask(getGenerationTaskId(event), getGenerationTaskProjectId(event))}
})
