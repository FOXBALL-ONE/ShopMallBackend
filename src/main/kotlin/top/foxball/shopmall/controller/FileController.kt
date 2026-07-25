package top.foxball.shopmall.controller

import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.service.FileMetadataResponse
import top.foxball.shopmall.service.DownloadableFile
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.shared.ResponseBuilder
import java.nio.charset.StandardCharsets
import java.util.UUID
import top.foxball.shopmall.shared.Response as ApiResponse

/** 批量获取链接或删除文件时使用的文件标识集合。 */
data class FileIdBatchRequest(
    @field:NotEmpty
    val ids: List<UUID> = emptyList(),
    val scope: String? = null,
)

data class FilePageResponse(
    val files: List<FileMetadataResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

private fun Page<FileMetadataResponse>.toResponse() = FilePageResponse(
    files = content,
    page = number,
    size = size,
    totalElements = totalElements,
    totalPages = totalPages,
)

/**
 * 文件 HTTP 接口。
 *
 * 上传、列表和删除通过 JWT 确认所属用户；下载端点通过用户绑定的短期签名链接授权。
 */
@RestController
@RequestMapping("/api/files")
class FileController(
    private val fileService: FileService,
    private val builder: ResponseBuilder,
) {
    /** 接收一个或多个名为 `files` 的 multipart 部件，单个端点同时支持批量上传。 */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @AuthenticationPrincipal userId: Long,
        @RequestPart("files") files: List<MultipartFile>,
    ): ResponseEntity<ApiResponse> {
        data class Response(val files: List<top.foxball.shopmall.service.FileMetadataResponse>)
        return builder.status(org.springframework.http.HttpStatus.CREATED)
            .data(Response(fileService.upload(userId, files)))
            .build()
    }

    /** 返回当前用户的文件元数据，并为每个文件生成新的下载链接。 */
    @GetMapping("/mine")
    fun listMine(
        @AuthenticationPrincipal userId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<ApiResponse> {
        if (page < 0 || size !in 1..MAX_PAGE_SIZE) {
            throw ParamErrorException("Page must be non-negative and size must be between 1 and $MAX_PAGE_SIZE.")
        }
        return builder.ok()
            .data(fileService.list(userId, PageRequest.of(page, size)).toResponse())
            .build()
    }

    /** 批量刷新当前用户指定文件的短期下载链接。 */
    @PostMapping("/batch/links")
    fun createBatchDownloadLinks(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: FileIdBatchRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val files: List<top.foxball.shopmall.service.FileMetadataResponse>)
        return builder.ok()
            .data(Response(fileService.createDownloadLinks(userId, request.ids, request.scope)))
            .build()
    }

    /** 校验 URL 签名后以附件形式输出文件，避免浏览器按不可信 MIME 类型内联执行。 */
    @GetMapping("/{fileId}/download")
    fun download(
        @PathVariable fileId: UUID,
        @RequestParam scope: String,
        @RequestParam expires: Long,
        @RequestParam nonce: String,
        @RequestParam signature: String,
    ): ResponseEntity<Resource> {
        if (scope != "public" && !scope.startsWith("user:")) throw ResourceNotFoundException()
        val downloadable = fileService.openSignedDownload(fileId, scope, expires, nonce, signature)
        return downloadResponse(downloadable)
    }

    /** JWT 与签名双重校验的下载入口；当前支持 role:admin，order scope 保留扩展点。 */
    @GetMapping("/{fileId}/secure-download")
    fun secureDownload(
        @AuthenticationPrincipal userId: Long,
        authentication: Authentication,
        @PathVariable fileId: UUID,
        @RequestParam scope: String,
        @RequestParam expires: Long,
        @RequestParam nonce: String,
        @RequestParam signature: String,
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
        return downloadResponse(downloadable)
    }

    private fun downloadResponse(downloadable: DownloadableFile): ResponseEntity<Resource> {
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

    /** 删除当前用户的一份文件及其元数据。 */
    @DeleteMapping("/{fileId}")
    fun delete(
        @AuthenticationPrincipal userId: Long,
        @PathVariable fileId: UUID,
    ): ResponseEntity<ApiResponse> {
        data class Response(val id: UUID, val deleted: Boolean)
        fileService.delete(userId, fileId)
        return builder.ok().data(Response(fileId, true)).build()
    }

    /** 删除当前用户的多份文件；服务层会在元数据删除失败时恢复已暂存的内容。 */
    @DeleteMapping("/batch")
    fun deleteBatch(
        @AuthenticationPrincipal userId: Long,
        @Valid @RequestBody request: FileIdBatchRequest,
    ): ResponseEntity<ApiResponse> {
        data class Response(val ids: List<UUID>, val deleted: Boolean)
        fileService.deleteBatch(userId, request.ids)
        return builder.ok().data(Response(request.ids, true)).build()
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
