import {getGenerationWorker} from '../utils/generation-worker'

export default defineNitroPlugin((nitroApp) => {
  if (process.env.TEMPLATE_GENERATION_WORKER_ENABLED?.trim().toLowerCase() === 'false') return
  const worker = getGenerationWorker()
  worker.start()
  nitroApp.hooks.hook('close', async () => { await worker.stop() })
})
