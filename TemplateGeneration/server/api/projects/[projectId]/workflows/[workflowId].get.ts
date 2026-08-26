import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getWorkflowProjectId, getWorkflow, getWorkflowId} from '../../../../utils/workflows'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {workflow: getWorkflow(getWorkflowId(event), getWorkflowProjectId(event))}
})
