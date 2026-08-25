import Database from 'better-sqlite3'
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
    `)
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
