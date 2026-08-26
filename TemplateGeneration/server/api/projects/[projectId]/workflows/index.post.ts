import {readBody} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getWorkflowProjectId, createWorkflow, type WorkflowInput} from '../../../../utils/workflows'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  setHeader(event, 'cache-control', 'private, no-store')
  const body = await readBody<WorkflowInput>(event)
  return {workflow: createWorkflow(getWorkflowProjectId(event), body ?? {})}
})
