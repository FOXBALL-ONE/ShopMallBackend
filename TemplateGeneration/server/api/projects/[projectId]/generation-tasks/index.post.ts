import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {createGenerationTasks, getGenerationTaskProjectId, type GenerationTaskInput} from '../../../../utils/generation-tasks'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<GenerationTaskInput>(event)
  return {tasks: createGenerationTasks(getGenerationTaskProjectId(event), body ?? {})}
})
