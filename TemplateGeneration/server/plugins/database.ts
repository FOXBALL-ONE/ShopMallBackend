import {closeDatabase, getDatabase} from '../utils/database'

export default defineNitroPlugin((nitroApp) => {
  getDatabase()
  nitroApp.hooks.hook('close', closeDatabase)
})
