import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../utils/auth'
import {getProjectRouteId, updateProject, type ProjectInput} from '../../utils/projects'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<ProjectInput>(event)
  return {project: updateProject(getProjectRouteId(event), body ?? {})}
})
