import {requireAuthenticatedUser} from '../../../../utils/auth'
import {deleteGenerationTask, getGenerationTaskId, getGenerationTaskProjectId} from '../../../../utils/generation-tasks'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {deleted: true, task: await deleteGenerationTask(getGenerationTaskId(event), getGenerationTaskProjectId(event))}
})
