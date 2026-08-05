package top.foxball.shopmall.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.service.ProductImageService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.net.URI
import java.time.LocalDateTime
import java.util.UUID

/**
 * @folder 商品/图片
 */
@Validated
@RestController
class ProductImageController(
    private val productImageService: ProductImageService,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 上传管理端商品图片
     * @param files 商品图片列表
     */
    @PostMapping("/admin/api/product-images", consumes = ["multipart/form-data"])
    fun upload(
        @AuthenticationPrincipal adminId: Long,
        @RequestPart("files") @Size(min = 1, max = 20) files: List<MultipartFile>,
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
            @param:JsonProperty("stable_url")
            val stableUrl: String,
            val storage: String,
        )

        data class Response(val list: List<FileData>)

        val list = productImageService.upload(adminId, files).map { details ->
            val file = details.file
            FileData(
                id = file.id,
                fileName = file.originalFilename,
                contentType = file.contentType,
                sizeBytes = file.sizeBytes,
                sha256 = file.sha256,
                createdAt = file.createdAt,
                stableUrl = details.stableUrl,
                storage = file.storage,
            )
        }
        val rs = Response(list)
        return builder.status(HttpStatus.CREATED).data(rs).build()
    }

    /**
     * @api 打开稳定商品图片地址
     * @param fileId 文件 ID
     * @param signature 永久用途签名
     */
    @GetMapping("/api/product-images/{file_id}")
    fun open(
        @PathVariable("file_id") fileId: UUID,
        @RequestParam("signature") signature: String,
    ): ResponseEntity<Void> {
        val location = productImageService.resolve(fileId, signature)
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(location))
            .cacheControl(CacheControl.noStore())
            .build()
    }
}
