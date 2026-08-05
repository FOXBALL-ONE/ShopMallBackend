package top.foxball.shopmall.service

import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.entity.jdbc.StoredFile
import java.util.UUID

data class ProductImageDetails(
    val file: StoredFile,
    val stableUrl: String,
)

interface ProductImageService {
    fun upload(adminId: Long, files: List<MultipartFile>): List<ProductImageDetails>

    fun resolve(fileId: UUID, signatureValue: String): String
}
