import {ZipArchive} from 'archiver'
import {extname} from 'node:path'
import {getQuery, sendStream, setHeader} from 'h3'
import {requireAuthenticatedUser} from '../../../../utils/auth'
import {getResultProjectId, getDownloadableResultFiles} from '../../../../utils/results'
import {verifyStoredFile} from '../../../../utils/file-records'
import {getAbsoluteStoragePath} from '../../../../utils/storage'

export default defineEventHandler(async (event) => {
  requireAuthenticatedUser(event)
  const rawResultIds = getQuery(event).result_id
  const values = Array.isArray(rawResultIds) ? rawResultIds : rawResultIds === undefined ? [] : [rawResultIds]
  const resultIds = values.flatMap((value) => String(value).split(',')).map((value) => Number(value.trim()))
  const files = getDownloadableResultFiles(getResultProjectId(event), resultIds)
  await Promise.all(files.map(verifyStoredFile))
  const archive = new ZipArchive({zlib: {level: 9}})
  const usedNames = new Set<string>()

  files.forEach((file) => {
    const extension = extname(file.original_name).toLowerCase() || (file.content_type === 'image/jpeg' ? '.jpg' : file.content_type === 'image/webp' ? '.webp' : '.png')
    const baseName = `task-${file.task_id ?? 'unknown'}-result-${file.result_id}`
    let name = `${baseName}${extension}`
    let suffix = 2
    while (usedNames.has(name)) name = `${baseName}-${suffix++}${extension}`
    usedNames.add(name)
    archive.file(getAbsoluteStoragePath(file.storage_key), {name})
  })

  archive.on('warning', (error: Error & {code?: string}) => {
    if ((error as NodeJS.ErrnoException).code !== 'ENOENT') archive.destroy(error)
  })
  setHeader(event, 'content-type', 'application/zip')
  setHeader(event, 'content-disposition', `attachment; filename*=UTF-8''${encodeURIComponent(`generation-results-${Date.now()}.zip`)}`)
  setHeader(event, 'cache-control', 'private, no-store')
  archive.finalize()
  return sendStream(event, archive)
})
