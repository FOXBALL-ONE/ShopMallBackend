import {requireAuthenticatedUser} from '../../utils/auth'
import {getProviderId, refreshProviderModels} from '../../utils/providers'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {provider: await refreshProviderModels(getProviderId(event))}
})
