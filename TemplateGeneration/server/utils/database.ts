import Database from 'better-sqlite3'
import {hashPassword, PASSWORD_MIN_LENGTH} from './password'
import {resolve} from 'node:path'

const databaseFileName = 'template-generation.sqlite'

let database: Database.Database | undefined

/** Opens the template workspace database once per Nitro process and creates its base schema. */
export function getDatabase() {
  if (database) return database

  const databasePath = resolve(process.cwd(), databaseFileName)
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
      status TEXT NOT NULL,
      progress INTEGER NOT NULL DEFAULT 0,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime')),
      updated_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

    CREATE TABLE IF NOT EXISTS generation_task_specs (
      task_id INTEGER PRIMARY KEY REFERENCES generation_tasks(id) ON DELETE CASCADE,
      provider_id INTEGER REFERENCES api_providers(id) ON DELETE SET NULL,
      provider_name TEXT NOT NULL,
      provider_type TEXT NOT NULL,
      model TEXT NOT NULL,
      workflow_name TEXT NOT NULL,
      workflow_version TEXT NOT NULL,
      media TEXT NOT NULL,
      batch_index INTEGER NOT NULL DEFAULT 1,
      batch_count INTEGER NOT NULL DEFAULT 1,
      prompt TEXT NOT NULL DEFAULT '',
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
      prompt TEXT NOT NULL DEFAULT '',
      uri TEXT,
      created_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime'))
    );

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
      file_id INTEGER NOT NULL REFERENCES stored_files(id) ON DELETE CASCADE,
      type TEXT NOT NULL,
      name TEXT NOT NULL,
      code TEXT NOT NULL,
      description TEXT NOT NULL DEFAULT '',
      tags_json TEXT NOT NULL DEFAULT '[]',
      authorization_status TEXT,
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
