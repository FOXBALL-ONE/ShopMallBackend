import {readBody, createError} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getProviderId, testProviderModel} from '../../utils/providers'

type ModelTestInput = {
  model_id?: unknown
}

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ModelTestInput>(event)
  const modelId = Number(body?.model_id)
  if (!Number.isSafeInteger(modelId) || modelId <= 0) {
    throw createError({statusCode: 400, statusMessage: '模型 ID 必须是正整数。'})
  }
  return await testProviderModel(getProviderId(event), modelId)
})
