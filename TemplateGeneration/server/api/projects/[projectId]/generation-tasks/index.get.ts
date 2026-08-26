import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getGenerationTaskProjectId, listGenerationTasks} from '../../../../utils/generation-tasks'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {tasks: listGenerationTasks(getGenerationTaskProjectId(event))}
})
