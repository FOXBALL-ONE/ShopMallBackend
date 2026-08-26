import {requireAuthenticatedUser} from '../../utils/auth'
import {listUsers} from '../../utils/users'

export default defineEventHandler((event) => {
  const currentUser = requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {users: listUsers(currentUser.id)}
})
