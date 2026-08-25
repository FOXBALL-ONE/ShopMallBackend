import {createError, readBody} from 'h3'
import {getDatabase} from '../../utils/database'
import {createSession, requireAuthenticatedUser} from '../../utils/auth'
import {hashPassword, PASSWORD_MIN_LENGTH, verifyPassword} from '../../utils/password'

export default defineEventHandler(async (event) => {
  const user = requireAuthenticatedUser(event)
  const body = await readBody<{current_password?: unknown; new_password?: unknown}>(event)
  const currentPassword = typeof body?.current_password === 'string' ? body.current_password : ''
  const newPassword = typeof body?.new_password === 'string' ? body.new_password : ''

  if (!currentPassword || !newPassword) {
    throw createError({statusCode: 400, statusMessage: '请输入当前密码和新密码。'})
  }
  if (newPassword.length < PASSWORD_MIN_LENGTH) {
    throw createError({statusCode: 400, statusMessage: `新密码长度必须至少为 ${PASSWORD_MIN_LENGTH} 个字符。`})
  }
  if (newPassword === currentPassword) {
    throw createError({statusCode: 400, statusMessage: '新密码不能与当前密码相同。'})
  }

  const database = getDatabase()
  const row = database.prepare('SELECT password_hash, password_salt FROM users WHERE id = ?').get(user.id) as {
    password_hash: string
    password_salt: string
  } | undefined
  if (!row || !verifyPassword(currentPassword, row.password_hash, row.password_salt)) {
    throw createError({statusCode: 400, statusMessage: '当前密码不正确。'})
  }

  const {hash, salt} = hashPassword(newPassword)
  const update = database.prepare("UPDATE users SET password_hash = ?, password_salt = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%f', 'now', 'localtime') WHERE id = ?")
  const deleteSessions = database.prepare('DELETE FROM user_sessions WHERE user_id = ?')
  database.transaction(() => {
    update.run(hash, salt, user.id)
    deleteSessions.run(user.id)
  })()
  createSession(event, user.id)

  return {updated: true, user}
})
