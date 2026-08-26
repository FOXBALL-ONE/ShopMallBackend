import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {fetchProviderModels, getProviderId, refreshProviderModels} from '../../utils/providers'

type ModelPreviewInput = {
  type?: unknown
  baseUrl?: unknown
  auth?: unknown
  credentialValue?: unknown
}

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ModelPreviewInput>(event)
  const hasDraftConnection = Boolean(body && Object.keys(body).length)
  const providerId = getProviderId(event)
  if (hasDraftConnection) {
    return {models: await fetchProviderModels(providerId, body)}
  }
  return {provider: await refreshProviderModels(providerId)}
})
