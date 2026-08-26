import {requireAuthenticatedUser} from '../../utils/auth'
import {deleteProject, getProjectRouteId} from '../../utils/projects'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {deleted: true, project: deleteProject(getProjectRouteId(event))}
})
