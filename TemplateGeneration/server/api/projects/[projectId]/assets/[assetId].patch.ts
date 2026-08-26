import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getAssetId, getProjectId, updateAssetScope} from '../../../../utils/assets'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const body = await readBody<{scope?: unknown}>(event)
  return {asset: updateAssetScope(getAssetId(event), getProjectId(event), body?.scope)}
})

