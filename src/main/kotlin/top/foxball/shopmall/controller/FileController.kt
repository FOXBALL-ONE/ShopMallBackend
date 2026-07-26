package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.PageRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.UUID

/**
 * @folder 文件
 */
@Validated
@RestController
@RequestMapping("/api/files")
class FileController(
    private val fileService: FileService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 上传文件
     * @param files 文件列表
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userId: Long,
        @RequestPart("files") @Size(min = 1) files: List<MultipartFile>,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: UUID,
            @param:JsonProperty("file_name")
            val fileName: String,
            @param:JsonProperty("content_type")
            val contentType: String?,
            @param:JsonProperty("size_bytes")
            val sizeBytes: Long,
            val sha256: String,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("signed_download_url")
            val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at")
            val downloadExpiresAt: LocalDateTime,
            val scope: String,
            val storage: String,
        )

        data class Response(val list: List<FileData>)

        val list = fileService.upload(userId, files).map { details ->
            val file = details.file
            FileData(
                id = file.id,
                fileName = file.originalFilename,
                contentType = file.contentType,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                createdAt = file.createdAt,
                signedDownloadUrl = details.signedDownloadUrl,
                downloadExpiresAt = details.downloadExpiresAt,
                scope = details.scope,
                storage = file.storage,
            )
        }
        val rs = Response(list)
        return builder.status(HttpStatus.CREATED)
            .data(rs)
            .build()
    }

    /**
     * @api 获取我的文件列表
     * @param page 分页页码
     * @param pageSize 分页每页数量
     */
    @GetMapping("/mine")
    fun getMyFiles(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) pageSize: Int,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: UUID,
            @param:JsonProperty("file_name")
            val fileName: String,
            @param:JsonProperty("content_type")
            val contentType: String?,
            @param:JsonProperty("size_bytes")
            val sizeBytes: Long,
            val sha256: String,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("signed_download_url")
            val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at")
            val downloadExpiresAt: LocalDateTime,
            val scope: String,
            val storage: String,
        )

        data class Pagination(val count: Int)

        data class Response(
            val list: List<FileData>,
            val pagination: Pagination,
        )

        val pagedData = fileService.list(userId, PageRequest.of(page - 1, pageSize))
        val list = pagedData.content.map { details ->
            val file = details.file
            FileData(
                id = file.id,
                fileName = file.originalFilename,
                contentType = file.contentType,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                createdAt = file.createdAt,
                signedDownloadUrl = details.signedDownloadUrl,
                downloadExpiresAt = details.downloadExpiresAt,
                scope = details.scope,
                storage = file.storage,
            )
        }
        val rs = Response(list, Pagination(pagedData.totalPages))
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 批量刷新文件下载链接
     * @param fileIds 文件 ID 列表
     * @param scope 下载授权范围
     */
    @PostMapping("/batch/links")
    fun createBatchDownloadLinks(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("file_ids") @Size(min = 1) fileIds: List<UUID>,
        @RequestParam("scope", required = false) scope: String?,
    ): ResponseEntity<Response> {
        data class FileData(
            val id: UUID,
            @param:JsonProperty("file_name")
            val fileName: String,
            @param:JsonProperty("content_type")
            val contentType: String?,
            @param:JsonProperty("size_bytes")
            val sizeBytes: Long,
            val sha256: String,
            @param:JsonProperty("created_at")
            val createdAt: LocalDateTime?,
            @param:JsonProperty("signed_download_url")
            val signedDownloadUrl: String,
            @param:JsonProperty("download_expires_at")
            val downloadExpiresAt: LocalDateTime,
            val scope: String,
            val storage: String,
        )

        data class Response(val list: List<FileData>)

        val list = fileService.createDownloadLinks(userId, fileIds, scope).map { details ->
            val file = details.file
            FileData(
                id = file.id,
                fileName = file.originalFilename,
                contentType = file.contentType,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                createdAt = file.createdAt,
                signedDownloadUrl = details.signedDownloadUrl,
                downloadExpiresAt = details.downloadExpiresAt,
                scope = details.scope,
                storage = file.storage,
            )
        }
        val rs = Response(list)
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 下载公开或用户文件
     * @param fileId 文件 ID
     * @param scope 下载授权范围
     * @param expires 过期时间戳
     * @param nonce 随机数
     * @param signature 下载签名
     */
    @GetMapping("/{file_id}/download")
    fun download(
        @PathVariable("file_id") fileId: UUID,
        @RequestParam("scope") scope: String,
        @RequestParam("expires") expires: Long,
        @RequestParam("nonce") nonce: String,
        @RequestParam("signature") signature: String,
    ): ResponseEntity<Resource> {
        if (scope != "public" && !scope.startsWith("user:")) throw ResourceNotFoundException()
        val downloadable = fileService.openSignedDownload(fileId, scope, expires, nonce, signature)
        val contentType = downloadable.contentType
            ?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
            ?: MediaType.APPLICATION_OCTET_STREAM
        val disposition = ContentDisposition.attachment()
            .filename(downloadable.originalFilename, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .contentType(contentType)
            .contentLength(downloadable.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(downloadable.path))
    }

    /**
     * @api 安全下载受保护文件
     * @param fileId 文件 ID
     * @param scope 下载授权范围
     * @param expires 过期时间戳
     * @param nonce 随机数
     * @param signature 下载签名
     */
    @GetMapping("/{file_id}/secure-download")
    fun secureDownload(
        @AuthenticationPrincipal userId: Long,
        authentication: Authentication,
        @PathVariable("file_id") fileId: UUID,
        @RequestParam("scope") scope: String,
        @RequestParam("expires") expires: Long,
        @RequestParam("nonce") nonce: String,
        @RequestParam("signature") signature: String,
    ): ResponseEntity<Resource> {
        if (!scope.startsWith("role:") && !scope.startsWith("order:")) throw ResourceNotFoundException()
        val downloadable = fileService.openSignedDownload(
            fileId = fileId,
            scope = scope,
            expiresAtEpochSeconds = expires,
            nonce = nonce,
            signature = signature,
            authenticatedUserId = userId,
            authenticatedAdmin = authentication.authorities.any { it.authority == "ROLE_ADMIN" },
        )
        val contentType = downloadable.contentType
            ?.let { runCatching { MediaType.parseMediaType(it) }.getOrNull() }
            ?: MediaType.APPLICATION_OCTET_STREAM
        val disposition = ContentDisposition.attachment()
            .filename(downloadable.originalFilename, StandardCharsets.UTF_8)
            .build()
        return ResponseEntity.ok()
            .contentType(contentType)
            .contentLength(downloadable.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(downloadable.path))
    }

    /**
     * @api 删除文件
     * @param fileId 文件 ID
     */
    @DeleteMapping("/{file_id}")
    fun deleteFile(
        @AuthenticationPrincipal userId: Long,
        @PathVariable("file_id") fileId: UUID,
    ): ResponseEntity<Response> {
        data class Response(
            val id: UUID,
            val deleted: Boolean,
        )

        fileService.delete(userId, fileId)
        val rs = Response(fileId, true)
        return builder.ok()
            .data(rs)
            .build()
    }

    /**
     * @api 批量删除文件
     * @param fileIds 文件 ID 列表
     */
    @DeleteMapping("/batch")
    fun deleteFiles(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("file_ids") @Size(min = 1) fileIds: List<UUID>,
    ): ResponseEntity<Response> {
        data class Response(
            val ids: List<UUID>,
            val deleted: Boolean,
        )

        fileService.deleteBatch(userId, fileIds)
        val rs = Response(fileIds, true)
        return builder.ok()
            .data(rs)
            .build()
    }
}
