import {getDatabase} from '../../utils/database'

export default defineEventHandler(() => {
  const database = getDatabase()
  const tables = database.prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").all() as Array<{name: string}>

  return {
    status: 'ok',
    driver: 'sqlite',
    tables: tables.map(({name}) => name),
  }
})
