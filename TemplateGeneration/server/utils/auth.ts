import {createHash, randomBytes} from 'node:crypto'
import {createError, deleteCookie, getCookie, setCookie, type H3Event} from 'h3'
import {getDatabase} from './database'

export const SESSION_COOKIE = 'template_generation_session'
const SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000

export type AuthenticatedUser = {
  id: number
  username: string
}

function hashToken(token: string) {
  return createHash('sha256').update(token).digest('hex')
}

export function createSession(event: H3Event, userId: number) {
  const token = randomBytes(32).toString('hex')
  const expiresAt = Date.now() + SESSION_TTL_MS
  const database = getDatabase()

  database.prepare('DELETE FROM user_sessions WHERE expires_at <= ?').run(Date.now())
  database.prepare('INSERT INTO user_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)').run(hashToken(token), userId, expiresAt)
  setCookie(event, SESSION_COOKIE, token, {
    httpOnly: true,
    sameSite: 'lax',
    secure: process.env.NODE_ENV === 'production',
    maxAge: SESSION_TTL_MS / 1000,
    path: '/',
  })
}

export function clearAuthSession(event: H3Event) {
  const token = getCookie(event, SESSION_COOKIE)
  if (token) {
    getDatabase().prepare('DELETE FROM user_sessions WHERE token_hash = ?').run(hashToken(token))
  }
  deleteCookie(event, SESSION_COOKIE, {path: '/'})
}

export function getAuthenticatedUser(event: H3Event): AuthenticatedUser | null {
  const token = getCookie(event, SESSION_COOKIE)
  if (!token) return null

  const database = getDatabase()
  const row = database.prepare(`
    SELECT users.id, users.username, user_sessions.expires_at
    FROM user_sessions
    INNER JOIN users ON users.id = user_sessions.user_id
    WHERE user_sessions.token_hash = ?
  `).get(hashToken(token)) as {id: number; username: string; expires_at: number} | undefined

  if (!row) return null
  if (row.expires_at <= Date.now()) {
    database.prepare('DELETE FROM user_sessions WHERE token_hash = ?').run(hashToken(token))
    return null
  }

  return {id: row.id, username: row.username}
}

export function requireAuthenticatedUser(event: H3Event) {
  const user = getAuthenticatedUser(event)
  if (!user) throw createError({statusCode: 401, statusMessage: '登录已失效，请重新登录。'})
  return user
}
