package top.foxball.shopmall.service

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

/** 前端可展示的文件元数据，以及当前响应时新签发的下载链接。 */
data class FileMetadataResponse(
    val id: UUID,
    val fileName: String,
    val contentType: String?,
    val sizeBytes: Long,
    val sha256: String,
    val createdAt: LocalDateTime?,
    val signedDownloadUrl: String,
    val downloadExpiresAt: LocalDateTime,
    val scope: String,
    val storage: String,
)

/** 已完成签名和归属校验、可由控制器输出的本地文件内容描述。 */
data class DownloadableFile(
    val path: Path,
    val originalFilename: String,
    val contentType: String?,
    val sizeBytes: Long,
)

/**
 * 文件领域服务。
 *
 * 所有管理操作均按 [ownerId] 隔离；仅 [openSignedDownload] 接受无 JWT 的签名下载请求。
 */
interface FileService {
    /** 保存一份或多份上传文件，并返回带短期链接的元数据。 */
    fun upload(ownerId: Long, files: List<MultipartFile>): List<FileMetadataResponse>

    /** 列出当前用户的文件，并为每项重新签发链接。 */
    fun list(ownerId: Long, pageable: Pageable): Page<FileMetadataResponse>

    /** 为指定文件批量签发新的下载链接。 */
    fun createDownloadLinks(
        ownerId: Long,
        fileIds: List<UUID>,
        scope: String? = null,
    ): List<FileMetadataResponse>

    /** 校验文件、scope、到期时间与 HMAC 签名后打开本地文件。 */
    fun openSignedDownload(
        fileId: UUID,
        scope: String,
        expiresAtEpochSeconds: Long,
        nonce: String,
        signature: String,
        authenticatedUserId: Long? = null,
        authenticatedAdmin: Boolean = false,
    ): DownloadableFile

    /** 删除一份属于当前用户的文件。 */
    fun delete(ownerId: Long, fileId: UUID)

    /** 删除多份属于当前用户的文件。 */
    fun deleteBatch(ownerId: Long, fileIds: List<UUID>)
}
