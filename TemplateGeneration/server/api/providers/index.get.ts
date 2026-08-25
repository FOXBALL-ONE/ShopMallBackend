import {requireAuthenticatedUser} from '../../utils/auth'
import {listProviders} from '../../utils/providers'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {providers: listProviders()}
})
