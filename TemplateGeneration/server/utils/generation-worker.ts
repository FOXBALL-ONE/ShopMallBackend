import {randomUUID} from 'node:crypto'
import {getDatabase} from './database'
import {readStoredFile, writeStoredFile, removeStoredFile} from './storage'
import {generateImage, type ImageReference} from './openai-image'
import {claimNextGenerationTask, failTask, getTaskExecution, refreshGenerationBatch, updateTaskProgress} from './generation-tasks'

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

function errorDetails(error: unknown) {
  const value = error as {statusMessage?: string; message?: string; retryable?: boolean; statusCode?: number}
  return {message: (value.statusMessage || value.message || '图像生成失败').slice(0, 1000), retryable: value.retryable === true, code: value.statusCode === 429 ? 'RATE_LIMITED' : value.statusCode === 504 ? 'UPSTREAM_TIMEOUT' : 'UPSTREAM_ERROR'}
}

function markCancelled(taskId: number, workerId: string, leaseToken: string) {
  const database = getDatabase()
  database.transaction(() => {
    const current = database.prepare('SELECT status FROM generation_tasks WHERE id = ?').get(taskId) as {status: string} | undefined
    if (!current || current.status !== 'CANCEL_REQUESTED') return
    const updated = database.prepare("UPDATE generation_tasks SET status = 'CANCELLED', stage = '已取消', cancelled_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), lease_token = NULL, lease_expires_at = NULL, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ? AND lease_token = ?").run(taskId, leaseToken)
    if (updated.changes === 0) return
    database.prepare("UPDATE results SET generation_status = 'CANCELLED' WHERE task_id = ?").run(taskId)
    const batch = database.prepare('SELECT batch_id FROM generation_tasks WHERE id = ?').get(taskId) as {batch_id: string | null} | undefined
    refreshGenerationBatch(database, batch?.batch_id ?? null)
    database.prepare("INSERT INTO generation_task_events (task_id, event_type, from_status, to_status, stage, progress, message, worker_id) SELECT id, 'CANCELLED', 'CANCEL_REQUESTED', 'CANCELLED', '已取消', progress, '任务已取消', ? FROM generation_tasks WHERE id = ?").run(workerId, taskId)
  })()
}

function persistGeneratedResult(taskId: number, workerId: string, leaseToken: string, stored: Awaited<ReturnType<typeof writeStoredFile>>, prompt: string, workflowName: string, upstreamRequestId: string | null, durationMs: number) {
  const database = getDatabase()
  return database.transaction(() => {
    const existing = database.prepare("SELECT id, file_id FROM results WHERE task_id = ? AND generation_status = 'READY'").get(taskId) as {id: number; file_id: number | null} | undefined
    if (existing) return {resultId: existing.id, reused: true}
    const fileResult = database.prepare("INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256, updated_at) VALUES (?, ?, ?, ?, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))").run(stored.storageKey, stored.originalName, stored.contentType, stored.sizeBytes, stored.sha256)
    const fileId = Number(fileResult.lastInsertRowid)
    const resultUpdate = database.prepare("UPDATE results SET generation_status = 'READY', file_id = ?, uri = ?, content_type = ?, size_bytes = ?, sha256 = ?, generated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), upstream_request_id = ?, error_code = NULL, error_message = NULL WHERE task_id = ?").run(fileId, `/api/files/${fileId}`, stored.contentType, stored.sizeBytes, stored.sha256, upstreamRequestId, taskId)
    if (resultUpdate.changes === 0) throw new Error('生成结果记录不存在。')
    const resultRow = database.prepare('SELECT id FROM results WHERE task_id = ?').get(taskId) as {id: number}
    database.prepare("INSERT OR IGNORE INTO asset_library (project_id, scope, file_id, type, name, code, description, tags_json, source_task_id, source_result_id, updated_at) SELECT generation_tasks.project_id, 'PROJECT', ?, 'REFERENCE', ?, ?, ?, '[\"generated\"]', generation_tasks.id, ?, strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') FROM generation_tasks WHERE generation_tasks.id = ?").run(fileId, `${workflowName} · 生成结果`, `GEN-${taskId}`, prompt.slice(0, 500), resultRow.id, taskId)
    const current = database.prepare('SELECT status, batch_id, lease_token FROM generation_tasks WHERE id = ?').get(taskId) as {status: string; batch_id: string | null; lease_token: string | null} | undefined
    if (!current || current.status !== 'RUNNING' || current.lease_token !== leaseToken) throw new Error('任务租约已失效或已取消。')
    database.prepare("UPDATE generation_tasks SET status = 'COMPLETED', progress = 100, stage = '已完成', completed_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'), lease_token = NULL, lease_expires_at = NULL, upstream_request_id = ?, duration_ms = ? WHERE id = ? AND lease_token = ?").run(upstreamRequestId, durationMs, taskId, leaseToken)
    refreshGenerationBatch(database, current.batch_id)
    database.prepare("INSERT INTO generation_task_events (task_id, event_type, from_status, to_status, stage, progress, message, worker_id) VALUES (?, 'COMPLETED', 'RUNNING', 'COMPLETED', '已完成', 100, ?, ?)").run(taskId, `已保存生成结果 ${resultRow.id}`, workerId)
    return {resultId: resultRow.id, reused: false}
  })()
}

async function executeTask(taskId: number, workerId: string, leaseToken: string, leaseMs: number) {
  const execution = getTaskExecution(taskId)
  if (!execution || !execution.spec || !execution.provider) throw new Error('任务指定的模型提供商配置不存在。')
  const startedAt = Date.now()
  updateTaskProgress(taskId, workerId, leaseToken, '读取参考图', 15, leaseMs)
  const references: ImageReference[] = []
  for (const input of execution.inputs) {
    references.push({storageKey: input.storage_key, originalName: input.original_name, contentType: input.content_type, data: await readStoredFile(input.storage_key), role: input.role, instruction: input.instruction})
  }
  const latest = getTaskExecution(taskId)
  if (!latest || latest.task.status === 'CANCEL_REQUESTED' || latest.task.status === 'CANCELLED') { markCancelled(taskId, workerId, leaseToken); return }
  updateTaskProgress(taskId, workerId, leaseToken, '正在调用图像生成服务', 40, leaseMs)
  const generated = await generateImage({baseUrl: execution.spec.provider_base_url, type: execution.spec.provider_type, auth: execution.provider.auth, credentialValue: execution.provider.credential_value, model: execution.spec.model}, {prompt: execution.spec.prompt, negativePrompt: execution.spec.negative_prompt, size: execution.spec.size, quality: execution.spec.quality, background: execution.spec.background, outputFormat: execution.spec.output_format, references})
  const afterUpstream = getTaskExecution(taskId)
  if (!afterUpstream || afterUpstream.task.status === 'CANCEL_REQUESTED' || afterUpstream.task.status === 'CANCELLED') { markCancelled(taskId, workerId, leaseToken); return }
  updateTaskProgress(taskId, workerId, leaseToken, '保存生成结果', 80, leaseMs)
  const extension = generated.contentType === 'image/webp' ? '.webp' : generated.contentType === 'image/jpeg' || generated.contentType === 'image/jpg' ? '.jpg' : '.png'
  let stored: Awaited<ReturnType<typeof writeStoredFile>>
  try { stored = await writeStoredFile(generated.data, `generation-${taskId}${extension}`, generated.contentType) } catch (error) { throw error }
  try {
    const result = persistGeneratedResult(taskId, workerId, leaseToken, stored, execution.spec.prompt, execution.spec.workflow_name, generated.upstreamRequestId, Date.now() - startedAt)
    return result
  } catch (error) {
    await removeStoredFile(stored.storageKey)
    throw error
  }
}

export class GenerationWorker {
  private running = false
  private readonly workers: Promise<void>[] = []
  private readonly workerId = `${process.pid}-${randomUUID().slice(0, 8)}`
  private readonly concurrency = Math.max(1, Math.min(8, Number(process.env.TEMPLATE_GENERATION_WORKER_CONCURRENCY || 2)))
  private readonly leaseMs = Math.max(30000, Number(process.env.TEMPLATE_GENERATION_LEASE_MS || 180000))

  start() {
    if (this.running) return
    this.running = true
    for (let index = 0; index < this.concurrency; index += 1) this.workers.push(this.loop(index))
  }

  async stop() {
    this.running = false
    await Promise.allSettled(this.workers)
    this.workers.length = 0
  }

  private async loop(index: number) {
    const workerId = `${this.workerId}-${index + 1}`
    while (this.running) {
      const claim = claimNextGenerationTask(workerId, this.leaseMs)
      if (!claim) { await sleep(500); continue }
      try { await executeTask(claim.taskId, workerId, claim.leaseToken, this.leaseMs) }
      catch (error) {
        const details = errorDetails(error)
        failTask(claim.taskId, workerId, details.code, details.message, details.retryable, claim.leaseToken)
      }
    }
  }
}

let worker: GenerationWorker | undefined
export function getGenerationWorker() {
  if (!worker) worker = new GenerationWorker()
  return worker
}
