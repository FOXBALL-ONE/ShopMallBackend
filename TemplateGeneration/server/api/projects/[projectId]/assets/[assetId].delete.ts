import {requireAuthenticatedUser} from '../../../../utils/auth'
import {deleteAsset, getAssetId, getProjectId} from '../../../../utils/assets'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  return {deleted: true, asset: await deleteAsset(getAssetId(event), getProjectId(event))}
})
