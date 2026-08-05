package top.foxball.shopmall.service.impl

import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.FileDetails
import top.foxball.shopmall.service.FileService
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductImageServiceImplTest {
    private lateinit var fileService: FileService
    private lateinit var repository: StoredFileRepository
    private lateinit var adminAccessService: AdminAccessService
    private lateinit var service: ProductImageServiceImpl

    @BeforeTest
    fun setUp() {
        fileService = mock(FileService::class.java)
        repository = mock(StoredFileRepository::class.java)
        adminAccessService = mock(AdminAccessService::class.java)
        service = ProductImageServiceImpl(
            fileService = fileService,
            storedFileRepository = repository,
            adminAccessService = adminAccessService,
            properties = FileProperties(
                baseUrl = "https://files.example.test/",
                signingSecret = "test-product-image-signing-secret-long-enough",
            ),
        )
    }

    @Test
    fun `stable image URL resolves to a newly issued public download link`() {
        val fileId = UUID.randomUUID()
        val upload = MockMultipartFile("files", "product.webp", "image/webp", "image".toByteArray())
        val stored = StoredFile(
            id = fileId,
            ownerId = 99L,
            originalFilename = "product.webp",
            contentType = "image/webp",
            sizeBytes = 5,
            sha256 = "abc",
        )
        val uploadedDetails = FileDetails(
            file = stored,
            signedDownloadUrl = "https://files.example.test/temporary-upload-link",
            downloadExpiresAt = LocalDateTime.parse("2026-08-05T12:05:00"),
            scope = "user:99",
        )
        val publicDetails = uploadedDetails.copy(
            signedDownloadUrl = "https://files.example.test/api/files/$fileId/download?scope=public",
            scope = "public",
        )
        `when`(fileService.upload(99L, listOf(upload))).thenReturn(listOf(uploadedDetails))
        `when`(repository.findById(fileId)).thenReturn(Optional.of(stored))
        `when`(fileService.createDownloadLinks(99L, listOf(fileId), "public")).thenReturn(listOf(publicDetails))

        val stableUrl = service.upload(99L, listOf(upload)).single().stableUrl
        val uri = UriComponentsBuilder.fromUriString(stableUrl).build()
        val signature = requireNotNull(uri.queryParams.getFirst("signature"))

        assertTrue(stableUrl.startsWith("https://files.example.test/api/product-images/$fileId"))
        assertEquals(publicDetails.signedDownloadUrl, service.resolve(fileId, signature))
        verify(adminAccessService).requireAdmin(99L)
        verify(fileService).createDownloadLinks(99L, listOf(fileId), "public")
    }

    @Test
    fun `rejects unsupported uploads and forged stable signatures`() {
        val textFile = MockMultipartFile("files", "notes.txt", "text/plain", "text".toByteArray())
        assertFailsWith<ParamErrorException> { service.upload(99L, listOf(textFile)) }
        verify(fileService, never()).upload(99L, listOf(textFile))

        val fileId = UUID.randomUUID()
        assertFailsWith<ResourceNotFoundException> { service.resolve(fileId, "forged") }
        verify(repository, never()).findById(fileId)
    }
}
