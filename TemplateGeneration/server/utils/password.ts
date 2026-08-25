import {randomBytes, scryptSync, timingSafeEqual} from 'node:crypto'

export const PASSWORD_MIN_LENGTH = 8

export function hashPassword(password: string) {
  const salt = randomBytes(16).toString('hex')
  const hash = scryptSync(password, salt, 64).toString('hex')
  return {hash, salt}
}

export function verifyPassword(password: string, hash: string, salt: string) {
  const expected = Buffer.from(hash, 'hex')
  const actual = scryptSync(password, salt, expected.length)
  return expected.length === actual.length && timingSafeEqual(expected, actual)
}
