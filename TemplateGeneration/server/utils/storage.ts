import {createHash, randomUUID} from 'node:crypto'
import {mkdir, readFile, stat, unlink, writeFile} from 'node:fs/promises'
import {basename, dirname, extname, isAbsolute, join, normalize, resolve} from 'node:path'
import {createError} from 'h3'

export const MAX_FILE_SIZE = 25 * 1024 * 1024
export const ALLOWED_CONTENT_TYPES = new Set([
  'image/jpeg',
  'image/png',
  'image/webp',
  'image/gif',
  'video/mp4',
  'video/webm',
  'application/pdf',
])

function getBasePath() {
  const configured = process.env.TEMPLATE_STORAGE_BASE_PATH?.trim()
  const basePath = configured ? (isAbsolute(configured) ? configured : resolve(process.cwd(), configured)) : resolve(process.cwd(), 'storage')
  return resolve(basePath)
}

export function getStorageBasePath() {
  return getBasePath()
}

function normalizeOriginalName(name: string) {
  const normalized = basename(name).replace(/[\u0000-\u001f<>:"/\\|?*]+/g, '_').trim()
  return normalized.slice(0, 255) || 'unnamed-file'
}

function extensionFor(contentType: string, originalName: string) {
  const extension = extname(originalName).toLowerCase().replace(/[^a-z0-9.]/g, '')
  if (extension.length <= 10) return extension
  const knownExtensions: Record<string, string> = {'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp', 'image/gif': '.gif', 'video/mp4': '.mp4', 'video/webm': '.webm', 'application/pdf': '.pdf'}
  return knownExtensions[contentType] ?? ''
}

export function validateUpload(data: Buffer, originalName: string, contentType: string) {
  if (!data.length) throw createError({statusCode: 400, statusMessage: '上传文件不能为空。'})
  if (data.length > MAX_FILE_SIZE) throw createError({statusCode: 413, statusMessage: '单个文件不能超过 25 MB。'})
  if (!ALLOWED_CONTENT_TYPES.has(contentType)) {
    throw createError({statusCode: 415, statusMessage: '只支持 JPG、PNG、WebP、GIF、MP4、WebM 或 PDF 文件。'})
  }
  if (contentType.startsWith('image/') && !hasExpectedImageSignature(data, contentType)) {
    throw createError({statusCode: 415, statusMessage: '文件内容与声明的图片格式不匹配。'})
  }
  return {data, originalName: normalizeOriginalName(originalName), contentType}
}

function hasExpectedImageSignature(data: Buffer, contentType: string) {
  if (contentType === 'image/jpeg') return data.subarray(0, 3).equals(Buffer.from([0xff, 0xd8, 0xff]))
  if (contentType === 'image/png') return data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
  if (contentType === 'image/gif') return data.subarray(0, 6).toString('ascii') === 'GIF87a' || data.subarray(0, 6).toString('ascii') === 'GIF89a'
  if (contentType === 'image/webp') return data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP'
  return true
}

export async function writeStoredFile(data: Buffer, originalName: string, contentType: string) {
  const upload = validateUpload(data, originalName, contentType)
  const storageKey = `${new Date().toISOString().slice(0, 10)}/${randomUUID()}${extensionFor(upload.contentType, upload.originalName)}`
  const absolutePath = getAbsoluteStoragePath(storageKey)
  await mkdir(dirname(absolutePath), {recursive: true})
  await writeFile(absolutePath, upload.data, {flag: 'wx'})
  return {
    storageKey,
    absolutePath,
    originalName: upload.originalName,
    contentType: upload.contentType,
    sizeBytes: upload.data.length,
    sha256: createHash('sha256').update(upload.data).digest('hex'),
  }
}

export function getAbsoluteStoragePath(storageKey: string) {
  const basePath = getBasePath()
  const normalizedKey = normalize(storageKey).replace(/^([.][.][\\/])+/, '')
  const absolutePath = resolve(basePath, normalizedKey)
  if (absolutePath !== basePath && !absolutePath.startsWith(`${basePath}${'\\'}`) && !absolutePath.startsWith(`${basePath}/`)) {
    throw createError({statusCode: 400, statusMessage: '非法的存储路径。'})
  }
  return absolutePath
}

export async function removeStoredFile(storageKey: string) {
  try {
    await unlink(getAbsoluteStoragePath(storageKey))
  } catch (error: unknown) {
    const code = (error as NodeJS.ErrnoException).code
    if (code !== 'ENOENT') throw error
  }
}

export async function readStoredFile(storageKey: string) {
  return readFile(getAbsoluteStoragePath(storageKey))
}

export async function storedFileExists(storageKey: string) {
  try {
    await stat(getAbsoluteStoragePath(storageKey))
    return true
  } catch (error: unknown) {
    if ((error as NodeJS.ErrnoException).code === 'ENOENT') return false
    throw error
  }
}
