import {requireAuthenticatedUser} from '../../utils/auth'
import {getProject, getProjectRouteId} from '../../utils/projects'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {project: getProject(getProjectRouteId(event))}
})
