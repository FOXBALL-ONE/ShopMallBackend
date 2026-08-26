import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getWorkflowProjectId, listWorkflows} from '../../../../utils/workflows'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {workflows: listWorkflows(getWorkflowProjectId(event))}
})
