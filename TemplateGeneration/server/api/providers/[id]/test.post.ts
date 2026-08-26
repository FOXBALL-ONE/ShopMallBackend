import {readBody, createError} from 'h3'
import {requireAuthenticatedUser} from '../../../utils/auth'
import {getProviderId, testProviderModel} from '../../../utils/providers'

type ModelTestInput = {
  model_id?: unknown
  model?: unknown
  type?: unknown
  base_url?: unknown
  baseUrl?: unknown
  auth?: unknown
  credential_value?: unknown
  credentialValue?: unknown
}

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ModelTestInput>(event)
  const rawModelId = body?.model_id
  const modelId = rawModelId === undefined || rawModelId === null || rawModelId === '' || Number(rawModelId) === 0 ? null : Number(rawModelId)
  if (modelId !== null && (!Number.isSafeInteger(modelId) || modelId <= 0)) {
    throw createError({statusCode: 400, statusMessage: '模型 ID 必须是正整数或留空并提供模型名称。'})
  }
  return await testProviderModel(getProviderId(event), modelId, body?.model, {
    type: body?.type,
    baseUrl: body?.baseUrl ?? body?.base_url,
    auth: body?.auth,
    credentialValue: body?.credentialValue ?? body?.credential_value,
  })
})
