import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {createProvider, validateProviderInput, type ProviderInput} from '../../utils/providers'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ProviderInput>(event)
  const provider = createProvider(validateProviderInput(body ?? {}))
  return {provider}
})
