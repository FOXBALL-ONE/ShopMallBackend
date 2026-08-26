import Database from 'better-sqlite3'
import {hashPassword, PASSWORD_MIN_LENGTH} from './password'
import {rmSync} from 'node:fs'
import {resolve} from 'node:path'

const databaseFileName = 'template-generation.sqlite'
const databaseInitializationEnv = 'TEMPLATE_DATABASE_INITIALIZATION_ENABLED'

let database: Database.Database | undefined

function isEnabled(value: string | undefined) {
  return ['1', 'true', 'yes', 'on'].includes(value?.trim().toLowerCase() ?? '')
}

function forceInitializeDatabase(databasePath: string) {
  // SQLite may keep recent writes in sidecar files while WAL mode is active.
  // Remove every database/ journal file so initialization can never retain old records.
  for (const filePath of [databasePath, `${databasePath}-wal`, `${databasePath}-shm`, `${databasePath}-journal`]) {
    rmSync(filePath, {force: true})
  }
  console.warn(`[TemplateGeneration] ${databaseInitializationEnv}=true，已删除现有 SQLite 数据库并执行强制初始化。`)
}

/** Opens the template workspace database once per Nitro process and creates its base schema. */
export function getDatabase() {
  if (database) return database

  const databasePath = resolve(process.cwd(), databaseFileName)
  if (isEnabled(process.env[databaseInitializationEnv])) {
    forceInitializeDatabase(databasePath)
  }
  const connection = new Database(databasePath)

  try {
    connection.pragma('journal_mode = WAL')
    connection.pragma('foreign_keys = ON')
    connection.pragma('busy_timeout = 5000')
    connection.exec(`
    CREATE TABLE IF NOT EXISTS projects (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      season TEXT NOT NULL,
      status TEXT NOT NULL DEFAULT 'ACTIVE',
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS assets (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      name TEXT NOT NULL,
      code TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS workflows (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
      name TEXT NOT NULL,
      version INTEGER NOT NULL DEFAULT 1,
      definition_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS generation_tasks (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
      workflow_id INTEGER REFERENCES workflows(id) ON DELETE SET NULL,
      batch_id TEXT,
      status TEXT NOT NULL,
      progress INTEGER NOT NULL DEFAULT 0,
      stage TEXT NOT NULL DEFAULT '排队中',
      attempt_count INTEGER NOT NULL DEFAULT 0,
      max_attempts INTEGER NOT NULL DEFAULT 2,
      next_attempt_at INTEGER,
      lease_token TEXT,
      lease_expires_at INTEGER,
      started_at TEXT,
      completed_at TEXT,
      failed_at TEXT,
      cancelled_at TEXT,
      last_heartbeat_at TEXT,
      error_code TEXT,
      error_message TEXT,
      upstream_request_id TEXT,
      duration_ms INTEGER,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS generation_batches (
      id TEXT PRIMARY KEY,
      project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
      provider_id INTEGER,
      model_id INTEGER,
      batch_count INTEGER NOT NULL,
      status TEXT NOT NULL DEFAULT 'QUEUED',
      submitted_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      completed_at TEXT,
      failed_at TEXT,
      idempotency_key_hash TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      UNIQUE(project_id, idempotency_key_hash)
    );

    CREATE INDEX IF NOT EXISTS idx_generation_batches_project_id ON generation_batches(project_id, submitted_at);

    CREATE TABLE IF NOT EXISTS generation_task_specs (
      task_id INTEGER PRIMARY KEY REFERENCES generation_tasks(id) ON DELETE CASCADE,
      provider_id INTEGER REFERENCES api_providers(id) ON DELETE SET NULL,
      provider_base_url TEXT NOT NULL DEFAULT '',
      provider_name TEXT NOT NULL,
      provider_type TEXT NOT NULL,
      model TEXT NOT NULL,
      workflow_name TEXT NOT NULL,
      workflow_version TEXT NOT NULL,
      media TEXT NOT NULL,
      batch_index INTEGER NOT NULL DEFAULT 1,
      batch_count INTEGER NOT NULL DEFAULT 1,
      prompt TEXT NOT NULL DEFAULT '',
      negative_prompt TEXT NOT NULL DEFAULT '',
      size TEXT NOT NULL DEFAULT 'auto',
      quality TEXT NOT NULL DEFAULT 'auto',
      background TEXT NOT NULL DEFAULT 'auto',
      output_format TEXT NOT NULL DEFAULT 'png',
      request_snapshot_json TEXT NOT NULL DEFAULT '{}',
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE INDEX IF NOT EXISTS idx_generation_task_specs_provider_id ON generation_task_specs(provider_id);
    CREATE INDEX IF NOT EXISTS idx_generation_task_specs_workflow_name ON generation_task_specs(workflow_name);

    CREATE TABLE IF NOT EXISTS results (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
      task_id INTEGER REFERENCES generation_tasks(id) ON DELETE SET NULL,
      media TEXT NOT NULL,
      status TEXT NOT NULL,
      generation_status TEXT NOT NULL DEFAULT 'GENERATING',
      prompt TEXT NOT NULL DEFAULT '',
      uri TEXT,
      file_id INTEGER REFERENCES stored_files(id) ON DELETE SET NULL,
      content_type TEXT,
      size_bytes INTEGER,
      sha256 TEXT,
      error_code TEXT,
      error_message TEXT,
      generated_at TEXT,
      upstream_request_id TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS generation_task_inputs (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      task_id INTEGER NOT NULL REFERENCES generation_tasks(id) ON DELETE CASCADE,
      position INTEGER NOT NULL,
      role TEXT NOT NULL,
      instruction TEXT NOT NULL DEFAULT '',
      asset_id INTEGER,
      file_id INTEGER REFERENCES stored_files(id) ON DELETE SET NULL,
      storage_key TEXT NOT NULL,
      original_name TEXT NOT NULL,
      content_type TEXT NOT NULL,
      size_bytes INTEGER NOT NULL,
      sha256 TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      UNIQUE(task_id, position)
    );

    CREATE INDEX IF NOT EXISTS idx_generation_task_inputs_task_id ON generation_task_inputs(task_id, position);

    CREATE TABLE IF NOT EXISTS generation_task_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      task_id INTEGER NOT NULL REFERENCES generation_tasks(id) ON DELETE CASCADE,
      event_type TEXT NOT NULL,
      from_status TEXT,
      to_status TEXT,
      stage TEXT,
      progress INTEGER,
      message TEXT NOT NULL DEFAULT '',
      error_code TEXT,
      metadata_json TEXT NOT NULL DEFAULT '{}',
      worker_id TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE INDEX IF NOT EXISTS idx_generation_task_events_task_id ON generation_task_events(task_id, created_at, id);

    CREATE TABLE IF NOT EXISTS api_providers (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      name TEXT NOT NULL,
      type TEXT NOT NULL,
      base_url TEXT NOT NULL,
      auth TEXT NOT NULL,
      credential_value TEXT NOT NULL DEFAULT '',
      model TEXT NOT NULL,
      enabled INTEGER NOT NULL DEFAULT 1,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS api_provider_models (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      provider_id INTEGER NOT NULL REFERENCES api_providers(id) ON DELETE CASCADE,
      model TEXT NOT NULL,
      position INTEGER NOT NULL DEFAULT 0,
      UNIQUE (provider_id, model)
    );

    CREATE INDEX IF NOT EXISTS idx_api_provider_models_provider_id ON api_provider_models(provider_id);

    CREATE TABLE IF NOT EXISTS stored_files (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      storage_key TEXT NOT NULL UNIQUE,
      original_name TEXT NOT NULL,
      content_type TEXT NOT NULL,
      size_bytes INTEGER NOT NULL,
      sha256 TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE INDEX IF NOT EXISTS idx_stored_files_sha256 ON stored_files(sha256);

    CREATE TABLE IF NOT EXISTS asset_library (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      project_id TEXT NOT NULL,
      scope TEXT NOT NULL DEFAULT 'PROJECT',
      file_id INTEGER NOT NULL REFERENCES stored_files(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      name TEXT NOT NULL,
      code TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      tags_json TEXT NOT NULL DEFAULT '[]',
      authorization_status TEXT,
      source_task_id INTEGER,
      source_result_id INTEGER,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      UNIQUE(project_id, code)
    );

    CREATE INDEX IF NOT EXISTS idx_asset_library_project_id ON asset_library(project_id);
    CREATE INDEX IF NOT EXISTS idx_asset_library_file_id ON asset_library(file_id);

    CREATE TABLE IF NOT EXISTS users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      username TEXT NOT NULL UNIQUE COLLATE NOCASE,
      password_hash TEXT NOT NULL,
      password_salt TEXT NOT NULL,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS user_sessions (
      token_hash TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE INDEX IF NOT EXISTS idx_user_sessions_user_id ON user_sessions(user_id);
    CREATE INDEX IF NOT EXISTS idx_user_sessions_expires_at ON user_sessions(expires_at);
    `)

    const projectColumns = connection.prepare('PRAGMA table_info(projects)').all() as Array<{name: string}>
    if (!projectColumns.some((column) => column.name === 'status')) {
      connection.exec("ALTER TABLE projects ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE'")
    }

    const assetColumns = connection.prepare('PRAGMA table_info(asset_library)').all() as Array<{name: string}>
    if (!assetColumns.some((column) => column.name === 'scope')) {
      connection.exec("ALTER TABLE asset_library ADD COLUMN scope TEXT NOT NULL DEFAULT 'PROJECT'")
    }
    const ensureColumns = (table: string, columns: Record<string, string>) => {
      const existing = new Set((connection.prepare(`PRAGMA table_info(${table})`).all() as Array<{name: string}>).map((column) => column.name))
      Object.entries(columns).forEach(([name, definition]) => {
        if (!existing.has(name)) connection.exec(`ALTER TABLE ${table} ADD COLUMN ${name} ${definition}`)
      })
    }
    ensureColumns('generation_tasks', {
      batch_id: 'TEXT', stage: "TEXT NOT NULL DEFAULT '排队中'", attempt_count: 'INTEGER NOT NULL DEFAULT 0',
      max_attempts: 'INTEGER NOT NULL DEFAULT 2', next_attempt_at: 'INTEGER', lease_token: 'TEXT',
      lease_expires_at: 'INTEGER', started_at: 'TEXT', completed_at: 'TEXT', failed_at: 'TEXT', cancelled_at: 'TEXT',
      last_heartbeat_at: 'TEXT', error_code: 'TEXT', error_message: 'TEXT', upstream_request_id: 'TEXT', duration_ms: 'INTEGER',
    })
    ensureColumns('generation_task_specs', {
      provider_base_url: "TEXT NOT NULL DEFAULT ''", negative_prompt: "TEXT NOT NULL DEFAULT ''",
      size: "TEXT NOT NULL DEFAULT 'auto'", quality: "TEXT NOT NULL DEFAULT 'auto'", background: "TEXT NOT NULL DEFAULT 'auto'",
      output_format: "TEXT NOT NULL DEFAULT 'png'", request_snapshot_json: "TEXT NOT NULL DEFAULT '{}'",
    })
    ensureColumns('results', {
      generation_status: "TEXT NOT NULL DEFAULT 'GENERATING'", file_id: 'INTEGER', content_type: 'TEXT', size_bytes: 'INTEGER',
      sha256: 'TEXT', error_code: 'TEXT', error_message: 'TEXT', generated_at: 'TEXT', upstream_request_id: 'TEXT',
    })
    ensureColumns('asset_library', {source_task_id: 'INTEGER', source_result_id: 'INTEGER'})
    connection.exec('CREATE INDEX IF NOT EXISTS idx_generation_tasks_project_status ON generation_tasks(project_id, status, updated_at)')
    connection.exec('CREATE INDEX IF NOT EXISTS idx_generation_tasks_batch_id ON generation_tasks(batch_id)')
    connection.exec('CREATE UNIQUE INDEX IF NOT EXISTS idx_results_task_id_unique ON results(task_id) WHERE task_id IS NOT NULL')
    connection.exec('CREATE INDEX IF NOT EXISTS idx_results_project_generation_status ON results(project_id, generation_status, created_at)')
    connection.exec('CREATE INDEX IF NOT EXISTS idx_generation_task_inputs_task_id ON generation_task_inputs(task_id, position)')
    connection.exec('CREATE INDEX IF NOT EXISTS idx_generation_task_events_task_id ON generation_task_events(task_id, created_at, id)')
    connection.exec("UPDATE asset_library SET scope = CASE WHEN project_id = '__global__' THEN 'GLOBAL' ELSE 'PROJECT' END WHERE scope IS NULL OR scope = ''")

    // Upgrade databases created before model records had their own stable IDs.
    const modelColumns = connection.prepare('PRAGMA table_info(api_provider_models)').all() as Array<{name: string}>
    if (!modelColumns.some((column) => column.name === 'id')) {
      connection.exec(`
        DROP INDEX IF EXISTS idx_api_provider_models_provider_id;
        ALTER TABLE api_provider_models RENAME TO api_provider_models_legacy;
        CREATE TABLE api_provider_models (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          provider_id INTEGER NOT NULL REFERENCES api_providers(id) ON DELETE CASCADE,
          model TEXT NOT NULL,
          position INTEGER NOT NULL DEFAULT 0,
          UNIQUE (provider_id, model)
        );
        INSERT INTO api_provider_models (provider_id, model, position)
          SELECT provider_id, model, position FROM api_provider_models_legacy ORDER BY rowid;
        DROP TABLE api_provider_models_legacy;
        CREATE INDEX idx_api_provider_models_provider_id ON api_provider_models(provider_id);
      `)
    }

    const specColumns = connection.prepare('PRAGMA table_info(generation_task_specs)').all() as Array<{name: string}>
    if (!specColumns.some((column) => column.name === 'model_id')) {
      connection.exec('ALTER TABLE generation_task_specs ADD COLUMN model_id INTEGER REFERENCES api_provider_models(id) ON DELETE SET NULL')
      connection.exec('CREATE INDEX IF NOT EXISTS idx_generation_task_specs_model_id ON generation_task_specs(model_id)')
    }

    connection.prepare("INSERT OR IGNORE INTO projects (id, name, season) VALUES ('prj_noir', 'NOIR · 春夏系列', 'SS 2026')").run()

    const providerCount = connection.prepare('SELECT COUNT(*) AS count FROM api_providers').get() as {count: number}
    if (providerCount.count === 0) {
      const insertProvider = connection.prepare("INSERT INTO api_providers (name, type, base_url, auth, credential_value, model, enabled) VALUES (?, ?, ?, ?, '', ?, ?)")
      const insertModel = connection.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, ?)')
      const seedProviders = [
        {name: 'OpenAI Production', type: 'OpenAI', baseUrl: 'https://api.openai.com/v1', auth: 'Bearer Token', model: 'gpt-4o', enabled: 1, models: ['gpt-4o', 'gpt-4o-mini', 'o3-mini']},
        {name: 'Anthropic Review', type: 'Anthropic', baseUrl: 'https://api.anthropic.com/v1', auth: 'Custom Header', model: 'claude-3-5-sonnet', enabled: 1, models: ['claude-3-5-sonnet', 'claude-3-5-haiku']},
        {name: '本地推理网关', type: '兼容网关', baseUrl: 'http://127.0.0.1:8080/v1', auth: '无需认证', model: 'local-model', enabled: 0, models: ['local-model']},
      ]
      connection.transaction(() => {
        seedProviders.forEach((provider) => {
          const result = insertProvider.run(provider.name, provider.type, provider.baseUrl, provider.auth, provider.model, provider.enabled)
          provider.models.forEach((model, position) => insertModel.run(result.lastInsertRowid, model, position))
        })
      })()
    }

    const providersWithoutModels = connection.prepare(`
      SELECT api_providers.id, api_providers.model
      FROM api_providers
      LEFT JOIN api_provider_models ON api_provider_models.provider_id = api_providers.id
      GROUP BY api_providers.id
      HAVING COUNT(api_provider_models.model) = 0
    `).all() as Array<{id: number; model: string}>
    const insertMissingModel = connection.prepare('INSERT INTO api_provider_models (provider_id, model, position) VALUES (?, ?, 0)')
    providersWithoutModels.forEach((provider) => insertMissingModel.run(provider.id, provider.model))

    connection.exec(`
      UPDATE generation_task_specs
      SET model_id = (
        SELECT api_provider_models.id
        FROM api_provider_models
        WHERE api_provider_models.provider_id = generation_task_specs.provider_id
          AND api_provider_models.model = generation_task_specs.model
        LIMIT 1
      )
      WHERE model_id IS NULL AND provider_id IS NOT NULL
    `)

    const userCount = connection.prepare('SELECT COUNT(*) AS count FROM users').get() as {count: number}
    if (userCount.count === 0) {
      const username = process.env.TEMPLATE_INITIAL_USERNAME?.trim()
      const password = process.env.TEMPLATE_INITIAL_PASSWORD

      if (!username || !password) {
        throw new Error('首次启动必须设置 TEMPLATE_INITIAL_USERNAME 和 TEMPLATE_INITIAL_PASSWORD 环境变量。')
      }
      if (username.length < 3 || username.length > 64) {
        throw new Error('TEMPLATE_INITIAL_USERNAME 长度必须为 3-64 个字符。')
      }
      if (password.length < PASSWORD_MIN_LENGTH) {
        throw new Error(`TEMPLATE_INITIAL_PASSWORD 长度必须至少为 ${PASSWORD_MIN_LENGTH} 个字符。`)
      }

      const {hash, salt} = hashPassword(password)
      connection.prepare('INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)').run(username, hash, salt)
    }
  } catch (error) {
    connection.close()
    throw error
  }

  database = connection
  return connection
}

export function closeDatabase() {
  if (!database) return

  database.close()
  database = undefined
}
