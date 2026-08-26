import {createError} from 'h3'
import {getDatabase} from './database'
import {hashPassword, PASSWORD_MIN_LENGTH} from './password'

export const USERNAME_MIN_LENGTH = 3
export const USERNAME_MAX_LENGTH = 64
export const PASSWORD_MAX_LENGTH = 72

export type UserRow = {
  id: number
  username: string
  created_at: string
  updated_at: string
}

export type UserInput = {
  username?: unknown
  password?: unknown
}

export function listUsers(currentUserId?: number) {
  const rows = getDatabase().prepare('SELECT id, username, created_at, updated_at FROM users ORDER BY id ASC').all() as UserRow[]
  return rows.map((row) => ({
    id: row.id,
    username: row.username,
    createdAt: row.created_at.replace(' ', 'T'),
    updatedAt: row.updated_at.replace(' ', 'T'),
    isCurrent: row.id === currentUserId,
  }))
}

export function createUser(input: UserInput) {
  const username = typeof input.username === 'string' ? input.username.trim() : ''
  const password = typeof input.password === 'string' ? input.password : ''

  if (!username) {
    throw createError({statusCode: 400, statusMessage: '请输入用户名。'})
  }
  if (username.length < USERNAME_MIN_LENGTH || username.length > USERNAME_MAX_LENGTH) {
    throw createError({statusCode: 400, statusMessage: `用户名长度必须为 ${USERNAME_MIN_LENGTH}-${USERNAME_MAX_LENGTH} 个字符。`})
  }
  if (!password) {
    throw createError({statusCode: 400, statusMessage: '请输入密码。'})
  }
  if (password.length < PASSWORD_MIN_LENGTH || password.length > PASSWORD_MAX_LENGTH) {
    throw createError({statusCode: 400, statusMessage: `密码长度必须为 ${PASSWORD_MIN_LENGTH}-${PASSWORD_MAX_LENGTH} 个字符。`})
  }

  const {hash, salt} = hashPassword(password)
  try {
    const result = getDatabase().prepare('INSERT INTO users (username, password_hash, password_salt) VALUES (?, ?, ?)').run(username, hash, salt)
    const row = getDatabase().prepare('SELECT id, username, created_at, updated_at FROM users WHERE id = ?').get(result.lastInsertRowid) as UserRow | undefined
    if (!row) throw new Error('创建用户后无法读取用户记录。')
    return {
      id: row.id,
      username: row.username,
      createdAt: row.created_at.replace(' ', 'T'),
      updatedAt: row.updated_at.replace(' ', 'T'),
      isCurrent: false,
    }
  } catch (error: unknown) {
    if ((error as {code?: string}).code === 'SQLITE_CONSTRAINT_UNIQUE') {
      throw createError({statusCode: 409, statusMessage: '用户名已存在，请更换后重试。'})
    }
    throw error
  }
}
