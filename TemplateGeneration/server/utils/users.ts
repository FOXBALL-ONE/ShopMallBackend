import {getDatabase} from './database'

export type UserRow = {
  id: number
  username: string
  created_at: string
  updated_at: string
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
