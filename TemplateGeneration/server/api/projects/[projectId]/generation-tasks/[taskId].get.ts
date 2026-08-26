import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getGenerationTask, getGenerationTaskId, getGenerationTaskProjectId} from '../../../../utils/generation-tasks'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {task: getGenerationTask(getGenerationTaskId(event), getGenerationTaskProjectId(event))}
})
