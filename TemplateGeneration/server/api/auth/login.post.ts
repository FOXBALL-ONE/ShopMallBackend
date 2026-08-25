import {createError, readBody} from 'h3'
import {getDatabase} from '../../utils/database'
import {createSession} from '../../utils/auth'
import {verifyPassword} from '../../utils/password'

export default defineEventHandler(async (event) => {
  const body = await readBody<{username?: unknown; password?: unknown}>(event)
  const username = typeof body?.username === 'string' ? body.username.trim() : ''
  const password = typeof body?.password === 'string' ? body.password : ''

  if (!username || !password) {
    throw createError({statusCode: 400, statusMessage: '请输入用户名和密码。'})
  }

  const row = getDatabase().prepare('SELECT id, username, password_hash, password_salt FROM users WHERE username = ? COLLATE NOCASE').get(username) as {
    id: number
    username: string
    password_hash: string
    password_salt: string
  } | undefined

  if (!row || !verifyPassword(password, row.password_hash, row.password_salt)) {
    throw createError({statusCode: 401, statusMessage: '用户名或密码不正确。'})
  }

  createSession(event, row.id)
  return {authenticated: true, user: {id: row.id, username: row.username}}
})
