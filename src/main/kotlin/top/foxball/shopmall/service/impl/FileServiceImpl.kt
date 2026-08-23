package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.DownloadableFile
import top.foxball.shopmall.service.FileDetails
import top.foxball.shopmall.service.FileLinkSigner
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.service.SUPPORT_TICKET_DOWNLOAD_SCOPE
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
    private val productRepository: ProductRepository,
    private val properties: FileProperties,
    private val linkSigner: FileLinkSigner,
) : FileService {
    private val storageRoot: Path = Paths.get(properties.storagePath).toAbsolutePath().normalize()

    init {
        Files.createDirectories(storageRoot)
    }

    override fun upload(ownerId: Long, files: List<MultipartFile>): List<FileDetails> {
        validateUploadBatch(files)
        val stored = mutableListOf<StoredUpload>()
        try {
            files.forEach { stored += storeUpload(ownerId, it) }
            val saved = fileRepository.saveAllAndFlush(stored.map { it.metadata })
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                val storedPaths = stored.map { it.path }
                TransactionSynchronizationManager.registerSynchronization(
                    object : TransactionSynchronization {
                        override fun afterCompletion(status: Int) {
                            if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                                storedPaths.forEach(::deletePathQuietly)
                            }
                        }
                    },
                )
            }
            return saved.map { storedFile -> fileDetails(storedFile, "user:${storedFile.ownerId}") }
        } catch (ex: Exception) {
            stored.forEach { deletePathQuietly(it.path) }
            throw ex
        }
    }

    override fun list(ownerId: Long, pageable: Pageable): Page<FileDetails> =
        fileRepository.findAllByOwnerIdOrderByCreatedAtDesc(ownerId, pageable).map { storedFile ->
            fileDetails(storedFile, "user:${storedFile.ownerId}")
        }

    override fun createDownloadLinks(
        ownerId: Long,
        fileIds: List<UUID>,
        scope: String?,
    ): List<FileDetails> {
        validateFileIds(fileIds)
        val filesById = findOwnedFiles(ownerId, fileIds).associateBy { it.id }
        val resolvedScope = resolveIssuableScope(ownerId, scope)
        return fileIds.map { fileId ->
            val storedFile = filesById.getValue(fileId)
            fileDetails(storedFile, resolvedScope)
        }
    }

    override fun createSupportTicketDownloadLinks(files: Collection<StoredFile>): List<FileDetails> =
        files.map { storedFile -> fileDetails(storedFile, SUPPORT_TICKET_DOWNLOAD_SCOPE) }

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
        val stored = fileRepository.findByIdAndOwnerId(fileId, ownerId) ?: run {
            val existing = fileRepository.findById(fileId).orElse(null)
            if (existing != null && existing.ownerId != ownerId) {
                throw ForbiddenException("只能删除自己的文件")
            }
            throw ResourceNotFoundException("File does not exist or is no longer available.")
        }
        deleteStoredFiles(listOf(stored))
    }

    override fun deleteBatch(ownerId: Long, fileIds: List<UUID>) {
        validateFileIds(fileIds)
        deleteStoredFiles(findOwnedFiles(ownerId, fileIds))
    }

    override fun deleteAllByOwnerId(ownerId: Long) {
        deleteAllByOwnerIds(listOf(ownerId))
    }

    override fun deleteAllByOwnerIds(ownerIds: Collection<Long>) {
        val distinctOwnerIds = ownerIds.distinct()
        if (distinctOwnerIds.isEmpty()) return

        val files = fileRepository.findAllByOwnerIdInOrderByCreatedAtAsc(distinctOwnerIds)
        if (files.isEmpty()) return

        val referencedProductImageIds = productRepository.findAllImageUrls()
            .mapNotNull { PRODUCT_IMAGE_FILE_ID_PATTERN.find(it)?.groupValues?.get(1) }
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .toSet()
        val deletableFiles = files.filterNot { it.id in referencedProductImageIds }
        if (deletableFiles.isNotEmpty()) deleteStoredFiles(deletableFiles)
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
            if (fileRepository.findAllById(fileIds).any { it.ownerId != ownerId }) {
                throw ForbiddenException("只能访问自己的文件")
            }
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

    private fun resolveIssuableScope(ownerId: Long, requestedScope: String?): String {
        val scope = requestedScope?.trim()?.takeIf(String::isNotEmpty) ?: "user:$ownerId"
        if (scope == "public" || scope == "user:$ownerId") return scope
        throw ParamErrorException("Only public or the current user's file scope may be issued.")
    }

    private fun ttlFor(scope: String): Long = when {
        scope == "public" -> properties.signing.publicTtlSeconds
        scope.startsWith("user:") || scope == SUPPORT_TICKET_DOWNLOAD_SCOPE ->
            properties.signing.resolvedUserTtl(properties.downloadTokenTtlSeconds)
        // order / role:* 暂未提供签发入口（createDownloadLinks 仅签发 public/user），
        // 此处保留 TTL 分级供 secure-download 验签扩展点对齐设计文档 §5.4。
        scope == "role:admin" -> properties.signing.adminTtlSeconds
        scope.startsWith("order:") -> properties.signing.orderTtlSeconds
        else -> properties.signing.resolvedUserTtl(properties.downloadTokenTtlSeconds)
    }

    private fun fileDetails(storedFile: StoredFile, scope: String): FileDetails {
        val signedLink = linkSigner.sign(storedFile.id, scope, ttlFor(scope))
        val url = UriComponentsBuilder.fromUriString(properties.baseUrl.trimEnd('/'))
            .pathSegment("api", "files", storedFile.id.toString(), "download")
            .queryParam("scope", signedLink.scope)
            .queryParam("expires", signedLink.expiresAt.epochSecond)
            .queryParam("nonce", signedLink.nonce)
            .queryParam("signature", signedLink.signature)
            .build()
            .toUriString()
        return FileDetails(
            file = storedFile,
            signedDownloadUrl = url,
            downloadExpiresAt = LocalDateTime.ofInstant(signedLink.expiresAt, ZoneOffset.UTC),
            scope = signedLink.scope,
        )
    }

    /**
     * 校验 scope 是否允许下载当前文件。
     *
     * download 端点为 permitAll，user: scope 仅靠 HMAC 签名绑定（签名把 scope 字符串纳入摘要，
     * 篡改即失效），不叠加 JWT 二次校验——因此整个下载鉴权强依赖 [FileLinkSigner] 密钥保密。
     * 密钥一旦泄漏，任何文件的 user: 链接可被批量伪造；需配套密钥轮换机制。
     *
     * order:* 暂无签发入口，落到 else 视为不匹配；待补齐「文件归属订单 + 购买者」校验后开放。
     */
    private fun scopeAllows(
        stored: StoredFile,
        scope: String,
        authenticatedUserId: Long?,
        authenticatedAdmin: Boolean,
    ): Boolean = when {
        scope == "public" -> true
        scope == SUPPORT_TICKET_DOWNLOAD_SCOPE -> true
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
        val PRODUCT_IMAGE_FILE_ID_PATTERN = Regex(
            "(?:^|/)api/product-images/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:[?#]|$)",
        )
    }
}
