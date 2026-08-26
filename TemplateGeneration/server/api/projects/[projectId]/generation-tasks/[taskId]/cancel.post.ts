import {requireAuthenticatedUser} from '../../../../../utils/auth'
import {getGenerationTaskId, getGenerationTaskProjectId, requestCancelGenerationTask} from '../../../../../utils/generation-tasks'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {task: requestCancelGenerationTask(getGenerationTaskId(event), getGenerationTaskProjectId(event))}
})
