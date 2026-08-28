import {requireAuthenticatedUser} from '../../../../utils/auth'
import {deleteWorkflow, getWorkflowId, getWorkflowProjectId} from '../../../../utils/workflows'

export default defineEventHandler((event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  return {deleted: true, workflow: deleteWorkflow(getWorkflowId(event), getWorkflowProjectId(event))}
})
