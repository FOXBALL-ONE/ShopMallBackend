import {getQuery} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getProjectId, listAssets} from '../../../../utils/assets'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  const projectId = getProjectId(event)
  const query = getQuery(event)
  return {assets: listAssets(projectId, typeof query.type === 'string' ? query.type : undefined, typeof query.q === 'string' ? query.q : undefined)}
})
