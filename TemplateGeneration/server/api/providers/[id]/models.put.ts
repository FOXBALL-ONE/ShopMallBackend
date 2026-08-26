import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../utils/auth'
import {getProviderId, saveProviderModelCatalog} from '../../../utils/providers'

type ModelCatalogInput = {
  models?: unknown
  model_id?: unknown
}

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ModelCatalogInput>(event)
  return {provider: saveProviderModelCatalog(getProviderId(event), body?.models, body?.model_id)}
})
