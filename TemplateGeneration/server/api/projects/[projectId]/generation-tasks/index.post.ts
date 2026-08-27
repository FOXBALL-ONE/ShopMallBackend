import {getHeader, readBody, setResponseStatus} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {createGenerationTasks, getGenerationTaskProjectId, type GenerationTaskInput} from '../../../../utils/generation-tasks'
import {getGenerationWorker} from '../../../../utils/generation-worker'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<GenerationTaskInput>(event)
  const tasks = createGenerationTasks(getGenerationTaskProjectId(event), body ?? {}, getHeader(event, 'idempotency-key') ?? '')
  // Ensure a long-running Node deployment starts processing immediately after a browser submission.
  // The Nitro plugin also starts this singleton during application boot; start() is idempotent.
  if (process.env.TEMPLATE_GENERATION_WORKER_ENABLED?.trim().toLowerCase() !== 'false') getGenerationWorker().start()
  setResponseStatus(event, 202)
  return {tasks}
})
