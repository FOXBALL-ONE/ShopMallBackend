import {requireAuthenticatedUser} from '../../utils/auth'
import {listProjects} from '../../utils/projects'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {projects: listProjects()}
})

