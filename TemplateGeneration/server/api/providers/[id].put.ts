import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getProvider, getProviderId, updateProvider, validateProviderInput, type ProviderInput} from '../../utils/providers'
import {getDatabase} from '../../utils/database'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const providerId = getProviderId(event)
  const current = getProvider(providerId)
  const row = getDatabase().prepare('SELECT credential_value, enabled FROM api_providers WHERE id = ?').get(providerId) as {credential_value: string; enabled: number} | undefined
  const body = await readBody<ProviderInput>(event)
  const input = validateProviderInput(body ?? {}, row?.credential_value ?? '')
  return {provider: updateProvider(providerId, input, row ? Boolean(row.enabled) : current.enabled)}
})
