import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {createUser, type UserInput} from '../../utils/users'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<UserInput>(event)
  return {user: createUser(body ?? {})}
})
