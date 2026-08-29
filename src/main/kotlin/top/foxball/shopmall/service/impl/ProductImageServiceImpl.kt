package top.foxball.shopmall.service.impl

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.service.ProductImageDetails
import top.foxball.shopmall.service.ProductImageService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Service
@Transactional(readOnly = true)
class ProductImageServiceImpl(
    private val fileService: FileService,
    private val storedFileRepository: StoredFileRepository,
    private val adminAccessService: AdminAccessService,
    private val properties: FileProperties,
) : ProductImageService {
    @Transactional
    override fun upload(adminId: Long, files: List<MultipartFile>): List<ProductImageDetails> {
        return upload(adminId, files, SUPPORTED_IMAGE_CONTENT_TYPES, "product-image")
    }

    @Transactional
    override fun uploadVideos(adminId: Long, files: List<MultipartFile>): List<ProductImageDetails> {
        if (files.size != 1) throw ParamErrorException("商品展示视频每次只能上传 1 个文件")
        return upload(adminId, files, SUPPORTED_VIDEO_CONTENT_TYPES, "product-video")
    }

    private fun upload(
        adminId: Long,
        files: List<MultipartFile>,
        supportedContentTypes: Set<String>,
        signatureScope: String,
    ): List<ProductImageDetails> {
        adminAccessService.requireAdmin(adminId)
        files.forEach { file ->
            val contentType = file.contentType?.lowercase(Locale.ROOT)
            if (contentType !in supportedContentTypes) {
                throw ParamErrorException(
                    if (signatureScope == "product-image") "商品图片仅支持 JPEG、PNG、WebP 和 GIF"
                    else "商品展示视频仅支持 MP4、WebM 和 Ogg",
                )
            }
        }
        return fileService.upload(adminId, files).map { details ->
            val file = details.file
            val resourcePath = if (signatureScope == "product-image") {
                listOf("api", "product-images", file.id.toString())
            } else {
                listOf("api", "product-videos", file.id.toString())
            }
            val url = UriComponentsBuilder.fromUriString(properties.baseUrl.trimEnd('/'))
                .pathSegment(*resourcePath.toTypedArray())
                .queryParam("signature", signature(file.id, signatureScope))
                .build()
                .toUriString()
            ProductImageDetails(file, url)
        }
    }

    override fun resolve(fileId: UUID, signatureValue: String): String {
        return resolve(fileId, signatureValue, "product-image")
    }

    override fun resolveVideo(fileId: UUID, signatureValue: String): String {
        return resolve(fileId, signatureValue, "product-video")
    }

    private fun resolve(fileId: UUID, signatureValue: String, signatureScope: String): String {
        val expected = signature(fileId, signatureScope)
        if (!MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                signatureValue.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw ResourceNotFoundException()
        }
        val file = storedFileRepository.findById(fileId).orElseThrow { ResourceNotFoundException() }
        val supportedContentTypes = if (signatureScope == "product-image") {
            SUPPORTED_IMAGE_CONTENT_TYPES
        } else {
            SUPPORTED_VIDEO_CONTENT_TYPES
        }
        if (file.contentType?.lowercase(Locale.ROOT) !in supportedContentTypes) {
            throw ResourceNotFoundException()
        }
        return fileService.createDownloadLinks(file.ownerId, listOf(fileId), "public").single().signedDownloadUrl
    }

    private fun signature(fileId: UUID, scope: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.signingSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$scope:$fileId".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        val SUPPORTED_IMAGE_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
        val SUPPORTED_VIDEO_CONTENT_TYPES = setOf("video/mp4", "video/webm", "video/ogg")
    }
}
