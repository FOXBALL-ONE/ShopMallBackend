import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getResultId, getResultProjectId, updateResultReview} from '../../../../utils/results'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<{status?: unknown}>(event)
  return {result: updateResultReview(getResultId(event), getResultProjectId(event), body?.status)}
})
