import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getGenerationTaskId, getGenerationTaskProjectId, updateGenerationTask} from '../../../../utils/generation-tasks'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<{status?: unknown; progress?: unknown}>(event)
  return {task: updateGenerationTask(getGenerationTaskId(event), getGenerationTaskProjectId(event), body ?? {})}
})
