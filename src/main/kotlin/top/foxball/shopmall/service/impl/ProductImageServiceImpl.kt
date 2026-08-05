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
        adminAccessService.requireAdmin(adminId)
        files.forEach { file ->
            val contentType = file.contentType?.lowercase(Locale.ROOT)
            if (contentType !in SUPPORTED_CONTENT_TYPES) {
                throw ParamErrorException("商品图片仅支持 JPEG、PNG、WebP 和 GIF")
            }
        }
        return fileService.upload(adminId, files).map { details ->
            val file = details.file
            val url = UriComponentsBuilder.fromUriString(properties.baseUrl.trimEnd('/'))
                .pathSegment("api", "product-images", file.id.toString())
                .queryParam("signature", signature(file.id))
                .build()
                .toUriString()
            ProductImageDetails(file, url)
        }
    }

    override fun resolve(fileId: UUID, signatureValue: String): String {
        val expected = signature(fileId)
        if (!MessageDigest.isEqual(
                expected.toByteArray(StandardCharsets.US_ASCII),
                signatureValue.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw ResourceNotFoundException()
        }
        val file = storedFileRepository.findById(fileId).orElseThrow { ResourceNotFoundException() }
        return fileService.createDownloadLinks(file.ownerId, listOf(fileId), "public").single().signedDownloadUrl
    }

    private fun signature(fileId: UUID): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.signingSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("product-image:$fileId".toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private companion object {
        val SUPPORTED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
