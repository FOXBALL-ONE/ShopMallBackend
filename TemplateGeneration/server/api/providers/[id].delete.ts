import {requireAuthenticatedUser} from '../../utils/auth'
import {deleteProvider} from '../../utils/providers'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const id = deleteProvider(event)
  return {deleted: true, id}
})
