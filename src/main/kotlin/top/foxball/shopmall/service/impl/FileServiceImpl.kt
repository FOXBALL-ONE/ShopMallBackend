package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.DownloadableFile
import top.foxball.shopmall.service.FileLinkSigner
import top.foxball.shopmall.service.FileMetadataResponse
import top.foxball.shopmall.service.FileService
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.Locale
import java.util.UUID

/**
 * 基于本地文件系统的文件服务实现。
 *
 * 上传先写入受控存储目录再持久化元数据；删除先移动到暂存目录，数据库删除失败时恢复内容。
 */
@Service
class FileServiceImpl(
    private val fileRepository: StoredFileRepository,
    private val properties: FileProperties,
    private val linkSigner: FileLinkSigner,
) : FileService {
    private val storageRoot: Path = Paths.get(properties.storagePath).toAbsolutePath().normalize()

    init {
        Files.createDirectories(storageRoot)
    }

    override fun upload(ownerId: Long, files: List<MultipartFile>): List<FileMetadataResponse> {
        validateUploadBatch(files)
        val stored = mutableListOf<StoredUpload>()
        try {
            files.forEach { stored += storeUpload(ownerId, it) }
            val saved = fileRepository.saveAllAndFlush(stored.map { it.metadata })
            return saved.map { toResponse(it) }
        } catch (ex: Exception) {
            stored.forEach { deletePathQuietly(it.path) }
            throw ex
        }
    }

    override fun list(ownerId: Long, pageable: Pageable): Page<FileMetadataResponse> =
        fileRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageable).map { toResponse(it) }

    override fun createDownloadLinks(
        ownerId: Long,
        fileIds: List<UUID>,
        scope: String?,
    ): List<FileMetadataResponse> {
        validateFileIds(fileIds)
        val filesById = findOwnedFiles(ownerId, fileIds).associateBy { it.id }
        val resolvedScope = resolveIssuableScope(ownerId, scope)
        return fileIds.map { toResponse(filesById.getValue(it), resolvedScope) }
    }

    override fun openSignedDownload(
        fileId: UUID,
        scope: String,
        expiresAtEpochSeconds: Long,
        nonce: String,
        signature: String,
        authenticatedUserId: Long?,
        authenticatedAdmin: Boolean,
    ): DownloadableFile {
        if (!linkSigner.isValid(fileId, scope, expiresAtEpochSeconds, nonce, signature)) {
            throw fileNotFound()
        }
        val stored = fileRepository.findById(fileId).orElseThrow(::fileNotFound)
        if (!scopeAllows(stored, scope, authenticatedUserId, authenticatedAdmin)) throw fileNotFound()
        if (stored.storage != LOCAL_STORAGE) throw fileNotFound()
        val path = resolveStoredPath(stored.relativePath)
        if (!Files.isRegularFile(path)) throw fileNotFound()
        return DownloadableFile(
            path = path,
            originalFilename = stored.originalFilename,
            contentType = stored.contentType,
            sizeBytes = stored.sizeBytes,
        )
    }

    override fun delete(ownerId: Long, fileId: UUID) {
        val stored = fileRepository.findByIdAndOwnerId(fileId, ownerId)
            ?: throw ResourceNotFoundException("File does not exist or is no longer available.")
        deleteStoredFiles(listOf(stored))
    }

    override fun deleteBatch(ownerId: Long, fileIds: List<UUID>) {
        validateFileIds(fileIds)
        deleteStoredFiles(findOwnedFiles(ownerId, fileIds))
    }

    private fun validateUploadBatch(files: List<MultipartFile>) {
        if (files.isEmpty()) throw ParamErrorException("At least one file is required.")
        if (files.size > properties.maxBatchSize) {
            throw ParamErrorException("A batch may contain at most ${properties.maxBatchSize} files.")
        }
        files.forEach { file ->
            if (file.size > properties.maxFileSizeBytes) {
                throw ParamErrorException("Each file must not exceed ${properties.maxFileSizeBytes} bytes.")
            }
            safeOriginalFilename(file)
        }
    }

    private fun validateFileIds(fileIds: List<UUID>) {
        if (fileIds.isEmpty()) throw ParamErrorException("At least one file id is required.")
        if (fileIds.size > properties.maxBatchSize) {
            throw ParamErrorException("A batch may contain at most ${properties.maxBatchSize} file ids.")
        }
        if (fileIds.distinct().size != fileIds.size) {
            throw ParamErrorException("File ids must not contain duplicates.")
        }
    }

    private fun findOwnedFiles(ownerId: Long, fileIds: List<UUID>): List<StoredFile> {
        val files = fileRepository.findAllByIdInAndOwnerId(fileIds, ownerId)
        if (files.size != fileIds.size) {
            throw ResourceNotFoundException("One or more files do not exist or are not available.")
        }
        return files
    }

    private fun storeUpload(ownerId: Long, multipartFile: MultipartFile): StoredUpload {
        val originalFilename = safeOriginalFilename(multipartFile)
        val identifier = UUID.randomUUID()
        val datePath = LocalDate.now(ZoneOffset.UTC).toString().replace('-', '/')
        val storedFilename = "$identifier${extensionOf(originalFilename)}"
        val relativePath = "$datePath/$storedFilename"
        val target = resolveStoredPath(relativePath)
        Files.createDirectories(target.parent)

        val sha256 = try {
            writeAndHash(multipartFile, target)
        } catch (ex: Exception) {
            deletePathQuietly(target)
            throw ex
        }
        return StoredUpload(
            metadata = StoredFile(
                id = identifier,
                ownerId = ownerId,
                originalFilename = originalFilename,
                storedFilename = storedFilename,
                relativePath = relativePath,
                contentType = multipartFile.contentType?.take(255),
                sizeBytes = multipartFile.size,
                sha256 = sha256,
            ),
            path = target,
        )
    }

    private fun writeAndHash(file: MultipartFile, target: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream.use { input ->
            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    private fun toResponse(stored: StoredFile, requestedScope: String? = null): FileMetadataResponse {
        val scope = requestedScope ?: "user:${stored.ownerId}"
        val signedLink = linkSigner.sign(stored.id, scope, ttlFor(scope))
        val url = UriComponentsBuilder.fromUriString(properties.baseUrl.trimEnd('/'))
            .pathSegment("api", "files", stored.id.toString(), "download")
            .queryParam("scope", signedLink.scope)
            .queryParam("expires", signedLink.expiresAt.epochSecond)
            .queryParam("nonce", signedLink.nonce)
            .queryParam("signature", signedLink.signature)
            .build()
            .toUriString()
        return FileMetadataResponse(
            id = stored.id,
            fileName = stored.originalFilename,
            contentType = stored.contentType,
            sizeBytes = stored.sizeBytes,
            sha256 = stored.sha256,
            createdAt = stored.createdAt,
            signedDownloadUrl = url,
            downloadExpiresAt = LocalDateTime.ofInstant(signedLink.expiresAt, ZoneOffset.UTC),
            scope = signedLink.scope,
            storage = stored.storage,
        )
    }

    private fun resolveIssuableScope(ownerId: Long, requestedScope: String?): String {
        val scope = requestedScope?.trim()?.takeIf(String::isNotEmpty) ?: "user:$ownerId"
        if (scope == "public" || scope == "user:$ownerId") return scope
        throw ParamErrorException("Only public or the current user's file scope may be issued.")
    }

    private fun ttlFor(scope: String): Long = when {
        scope == "public" -> properties.signing.publicTtlSeconds
        scope.startsWith("user:") -> properties.signing.userTtlSeconds
        scope == "role:admin" -> properties.signing.adminTtlSeconds
        scope.startsWith("order:") -> properties.signing.orderTtlSeconds
        else -> properties.downloadTokenTtlSeconds
    }

    private fun scopeAllows(
        stored: StoredFile,
        scope: String,
        authenticatedUserId: Long?,
        authenticatedAdmin: Boolean,
    ): Boolean = when {
        scope == "public" -> true
        scope == "user:${stored.ownerId}" -> true
        scope == "role:admin" -> authenticatedUserId != null && authenticatedAdmin
        else -> false
    }

    private fun fileNotFound() = ResourceNotFoundException("File does not exist or is no longer available.")

    private fun deleteStoredFiles(files: List<StoredFile>) {
        // 先移动而非直接删除：元数据事务/flush 失败时可将内容恢复到原路径。
        val staged = mutableListOf<StagedDeletion>()
        try {
            files.forEach { stored -> stageForDeletion(stored)?.let(staged::add) }
        } catch (ex: Exception) {
            restoreStagedFiles(staged)
            throw IllegalStateException("Unable to stage file content for deletion.", ex)
        }
        try {
            fileRepository.deleteAll(files)
            fileRepository.flush()
        } catch (ex: Exception) {
            restoreStagedFiles(staged)
            throw ex
        }
        staged.forEach { deletePathQuietly(it.stagedPath) }
    }

    private fun stageForDeletion(stored: StoredFile): StagedDeletion? {
        val originalPath = resolveStoredPath(stored.relativePath)
        if (!Files.exists(originalPath)) return null
        val trashPath = storageRoot.resolve(DELETION_STAGING_DIRECTORY)
            .resolve("${stored.id}-${UUID.randomUUID()}")
        Files.createDirectories(trashPath.parent)
        moveFile(originalPath, trashPath)
        return StagedDeletion(originalPath, trashPath)
    }

    private fun restoreStagedFiles(staged: List<StagedDeletion>) {
        staged.asReversed().forEach { deletion ->
            runCatching {
                if (Files.exists(deletion.stagedPath) && !Files.exists(deletion.originalPath)) {
                    moveFile(deletion.stagedPath, deletion.originalPath)
                }
            }
        }
    }

    private fun moveFile(source: Path, target: Path) {
        // 同一存储根目录内优先原子移动；不支持时退回普通移动以兼容文件系统。
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun resolveStoredPath(relativePath: String): Path {
        val resolved = storageRoot.resolve(relativePath).normalize()
        if (!resolved.startsWith(storageRoot)) {
            throw ResourceNotFoundException("File does not exist or is no longer available.")
        }
        return resolved
    }

    private fun safeOriginalFilename(file: MultipartFile): String {
        val filename = file.originalFilename
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw ParamErrorException("Each upload must include a file name.")
        if (filename.length > MAX_ORIGINAL_FILENAME_LENGTH) {
            throw ParamErrorException("File name must not exceed $MAX_ORIGINAL_FILENAME_LENGTH characters.")
        }
        if (filename.any { it.code < MIN_PRINTABLE_CHARACTER || it.code == DELETE_CHARACTER }) {
            throw ParamErrorException("File name contains unsupported control characters.")
        }
        return filename
    }

    private fun extensionOf(filename: String): String {
        val extension = filename.substringAfterLast('.', missingDelimiterValue = "")
        return if (extension.matches(EXTENSION_PATTERN)) ".${extension.lowercase(Locale.ROOT)}" else ""
    }

    private fun deletePathQuietly(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    private data class StoredUpload(
        val metadata: StoredFile,
        val path: Path,
    )

    private data class StagedDeletion(
        val originalPath: Path,
        val stagedPath: Path,
    )

    private companion object {
        const val MAX_ORIGINAL_FILENAME_LENGTH = 255
        const val MIN_PRINTABLE_CHARACTER = 32
        const val DELETE_CHARACTER = 127
        const val DELETION_STAGING_DIRECTORY = ".deleting"
        const val LOCAL_STORAGE = "local"
        val EXTENSION_PATTERN = Regex("[A-Za-z0-9]{1,10}")
    }
}
