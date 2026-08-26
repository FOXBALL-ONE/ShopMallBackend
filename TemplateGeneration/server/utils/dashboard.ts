import {getDatabase} from './database'
import {ensureCompletedTaskResults} from './results'

type ProjectRow = {
  id: string
  name: string
  season: string
  assets: number
  tasks: number
}

type TaskRow = {
  id: number
  project_name: string
  workflow_name: string
  media: string
  progress: number
  status: string
}

export function getDashboardData() {
  const database = getDatabase()
  const projectIds = database.prepare('SELECT id FROM projects').all() as Array<{id: string}>
  projectIds.forEach((project) => ensureCompletedTaskResults(project.id))
  const firstProject = database.prepare('SELECT id FROM projects ORDER BY updated_at DESC, id ASC LIMIT 1').get() as {id: string} | undefined
  const projectRows = database.prepare(`
    SELECT projects.id, projects.name, projects.season,
      COUNT(DISTINCT asset_library.id) AS assets,
      COUNT(DISTINCT generation_tasks.id) AS tasks
    FROM projects
    LEFT JOIN asset_library ON asset_library.project_id = projects.id
    LEFT JOIN generation_tasks ON generation_tasks.project_id = projects.id
    GROUP BY projects.id
    ORDER BY projects.updated_at DESC, projects.id ASC
  `).all() as ProjectRow[]
  const projects = projectRows.map((project, index) => ({
    code: project.id,
    name: project.name,
    season: project.season,
    assets: project.assets,
    tasks: project.tasks,
    tone: index % 2 === 0 ? 'light' : 'dark',
  }))

  const tasks = (database.prepare(`
    SELECT generation_tasks.id,
      generation_tasks.project_id,
      projects.name AS project_name,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name,
      COALESCE(generation_task_specs.media, 'IMAGE') AS media,
      generation_tasks.progress,
      generation_tasks.status
    FROM generation_tasks
    INNER JOIN projects ON projects.id = generation_tasks.project_id
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = generation_tasks.id
    ORDER BY generation_tasks.updated_at DESC, generation_tasks.id DESC
    LIMIT 20
  `).all() as TaskRow[]).map((task) => ({
    id: task.id,
    title: task.workflow_name || (task.media === 'VIDEO' ? '展示视频' : '展示图片'),
    project: task.project_name,
    type: task.media === 'VIDEO' ? '展示视频' : '展示图片',
    progress: task.progress,
    status: task.status === 'RUNNING' ? '运行中' : task.status === 'COMPLETED' ? '已完成' : task.status === 'CANCELLED' ? '已取消' : '排队中',
  }))

  const stats = database.prepare(`
    SELECT
      (SELECT COUNT(*) FROM projects) AS active_projects,
      (SELECT COUNT(*) FROM asset_library) AS asset_count,
      (SELECT COUNT(*) FROM generation_tasks WHERE status = 'RUNNING') AS running_tasks,
      (SELECT COUNT(*) FROM results WHERE status = 'PENDING') AS pending_reviews
  `).get() as {active_projects: number; asset_count: number; running_tasks: number; pending_reviews: number}

  const pendingReview = database.prepare(`
    SELECT results.id, results.media, results.prompt,
      COALESCE(generation_task_specs.workflow_name, '') AS workflow_name,
      COALESCE(generation_task_specs.workflow_version, '') AS workflow_version
    FROM results
    LEFT JOIN generation_task_specs ON generation_task_specs.task_id = results.task_id
    WHERE results.project_id = ? AND results.status = 'PENDING'
    ORDER BY results.created_at ASC, results.id ASC
    LIMIT 1
  `).get(firstProject?.id ?? '') as {id: number; media: string; prompt: string; workflow_name: string; workflow_version: string} | undefined

  return {
    stats: {
      activeProjects: stats.active_projects,
      assets: stats.asset_count,
      runningTasks: stats.running_tasks,
      pendingReviews: stats.pending_reviews,
    },
    projects,
    tasks,
    pendingReview: pendingReview ? {
      id: pendingReview.id,
      workflow: pendingReview.workflow_name || '待审核生成结果',
      version: pendingReview.workflow_version,
      media: pendingReview.media === 'VIDEO' ? 'VIDEO' : 'IMAGE',
      prompt: pendingReview.prompt,
    } : null,
  }
}
