import {getAuthenticatedUser} from '../../utils/auth'

export default defineEventHandler((event) => {
  const user = getAuthenticatedUser(event)
  return user
    ? {authenticated: true, user}
    : {authenticated: false, user: null}
})
