import {getDatabase} from '../../utils/database'
import {storedFileExists} from '../../utils/storage'
import {getFileRow} from '../../utils/file-records'

export default defineEventHandler(async () => {
  const database = getDatabase()
  const tables = database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").all() as Array<{name: string}>
  const fileCount = database.prepare('SELECT COUNT(*) AS count FROM stored_files').get() as {count: number}
  const assetCount = database.prepare('SELECT COUNT(*) AS count FROM asset_library').get() as {count: number}
  const firstFile = database.prepare('SELECT id FROM stored_files ORDER BY id ASC LIMIT 1').get() as {id: number} | undefined
  const storageWritable = firstFile ? await storedFileExists(getFileRow(firstFile.id).storage_key) : true
  return {status: 'ok', driver: 'sqlite', storageConfigured: Boolean(process.env.TEMPLATE_STORAGE_BASE_PATH?.trim()), storageWritable, fileCount: fileCount.count, assetCount: assetCount.count, tables: tables.map(({name}) => name)}
})
