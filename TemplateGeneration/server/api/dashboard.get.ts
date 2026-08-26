import {requireAuthenticatedUser} from '../utils/auth'
import {getDashboardData} from '../utils/dashboard'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return getDashboardData()
})
