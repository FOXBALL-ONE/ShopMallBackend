import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getResultProjectId, listResults} from '../../../../utils/results'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {results: listResults(getResultProjectId(event))}
})
