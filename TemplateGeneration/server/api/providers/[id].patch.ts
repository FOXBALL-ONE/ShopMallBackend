import {readBody} from 'h3'
import {createError} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getDatabase} from '../../utils/database'
import {getProvider, getProviderId, updateProvider, validateProviderInput, type ProviderInput} from '../../utils/providers'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const providerId = getProviderId(event)
  const current = getProvider(providerId)
  const body = await readBody<ProviderInput>(event)
  if (typeof body?.enabled === 'boolean' && Object.keys(body).length === 1) {
    const row = getDatabase().prepare('SELECT credential_value FROM api_providers WHERE id = ?').get(providerId) as {credential_value: string} | undefined
    const input = validateProviderInput({
      name: current.name,
      type: current.type,
      baseUrl: current.baseUrl,
      auth: current.auth,
      model: current.model,
      models: current.models,
    }, row?.credential_value ?? '')
    return {provider: updateProvider(providerId, input, body.enabled)}
  }
  throw createError({statusCode: 400, statusMessage: '只支持更新提供商启停状态。'})
})
