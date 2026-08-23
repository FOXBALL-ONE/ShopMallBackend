package top.foxball.shopmall.service.impl

import kotlin.io.path.readText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.springframework.mock.web.MockMultipartFile
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.handler.ForbiddenException
import top.foxball.shopmall.handler.ResourceNotFoundException
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.FileLinkSigner
import top.foxball.shopmall.service.SUPPORT_TICKET_DOWNLOAD_SCOPE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Optional

/** 使用临时存储目录和模拟仓储验证文件服务的磁盘与元数据一致性。 */
class FileServiceImplTest {
    @TempDir
    lateinit var storageRoot: Path

    private lateinit var repository: StoredFileRepository
    private lateinit var productRepository: ProductRepository
    private lateinit var signer: FileLinkSigner
    private lateinit var service: FileServiceImpl

    @BeforeTest
    fun setUp() {
        repository = Mockito.mock(StoredFileRepository::class.java)
        productRepository = Mockito.mock(ProductRepository::class.java)
        signer = FileLinkSigner(
            secret = "test-file-signing-secret-must-be-long-enough",
            clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC),
        )
        service = FileServiceImpl(
            fileRepository = repository,
            productRepository = productRepository,
            properties = FileProperties(
                storagePath = storageRoot.toString(),
                baseUrl = "https://files.example.test/",
                signingSecret = "test-file-signing-secret-must-be-long-enough",
                downloadTokenTtlSeconds = 300,
            ),
            linkSigner = signer,
        )
    }

    @Test
    fun `uploads below the configured root and returns a valid owner-bound link`() {
        var persisted: List<StoredFile> = emptyList()
        Mockito.`when`(repository.saveAllAndFlush(ArgumentMatchers.anyList<StoredFile>()))
            .thenAnswer { invocation ->
                persisted = invocation.getArgument<List<StoredFile>>(0)
                persisted
            }

        val response = service.upload(
            ownerId = 42,
            files = listOf(
                MockMultipartFile("files", "../quarterly report.txt", "text/plain", "payload".toByteArray()),
            ),
        ).single()

        val stored = persisted.single()
        val diskPath = storageRoot.resolve(stored.relativePath).normalize()
        assertEquals("quarterly report.txt", response.file.originalFilename)
        assertTrue(diskPath.startsWith(storageRoot))
        assertEquals("payload", diskPath.readText())
        assertEquals("239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5", stored.sha256)

        val query = UriComponentsBuilder.fromUriString(response.signedDownloadUrl).build().queryParams
        assertEquals("user:42", response.scope)
        assertEquals("local", response.file.storage)
        assertTrue(
            signer.isValid(
                stored.id,
                query.getFirst("scope")!!,
                query.getFirst("expires")!!.toLong(),
                query.getFirst("nonce")!!,
                query.getFirst("signature")!!,
            ),
        )
        assertFalse(
            signer.isValid(
                stored.id,
                "user:43",
                query.getFirst("expires")!!.toLong(),
                query.getFirst("nonce")!!,
                query.getFirst("signature")!!,
            ),
        )
    }

    @Test
    fun `lists owner files with repository pagination`() {
        val pageable = PageRequest.of(1, 20)
        val stored = storedFile()
        Mockito.`when`(repository.findAllByOwnerIdOrderByCreatedAtDesc(42, pageable))
            .thenReturn(PageImpl(listOf(stored), pageable, 45))

        val result = service.list(42, pageable)

        assertEquals(1, result.number)
        assertEquals(45, result.totalElements)
        assertEquals(stored.id, result.content.single().file.id)
    }

    @Test
    fun `opens signed public download and hides owner scope mismatch as not found`() {
        val stored = storedFile()
        val diskPath = storageRoot.resolve(stored.relativePath)
        Files.createDirectories(diskPath.parent)
        Files.writeString(diskPath, "payload")
        Mockito.`when`(repository.findById(stored.id)).thenReturn(Optional.of(stored))
        val publicLink = signer.sign(stored.id, "public", 60)

        val result = service.openSignedDownload(
            stored.id,
            publicLink.scope,
            publicLink.expiresAt.epochSecond,
            publicLink.nonce,
            publicLink.signature,
        )

        assertEquals(diskPath, result.path)

        val supportTicketLink = signer.sign(stored.id, SUPPORT_TICKET_DOWNLOAD_SCOPE, 300)
        val supportTicketResult = service.openSignedDownload(
            stored.id,
            supportTicketLink.scope,
            supportTicketLink.expiresAt.epochSecond,
            supportTicketLink.nonce,
            supportTicketLink.signature,
        )

        assertEquals(diskPath, supportTicketResult.path)

        val wrongOwnerLink = signer.sign(stored.id, "user:43", 300)
        assertFailsWith<top.foxball.shopmall.handler.ResourceNotFoundException> {
            service.openSignedDownload(
                stored.id,
                wrongOwnerLink.scope,
                wrongOwnerLink.expiresAt.epochSecond,
                wrongOwnerLink.nonce,
                wrongOwnerLink.signature,
            )
        }
    }

    @Test
    fun `issues public links with the configured short ttl`() {
        val stored = storedFile()
        Mockito.`when`(repository.findAllByIdInAndOwnerId(listOf(stored.id), 42))
            .thenReturn(listOf(stored))

        val response = service.createDownloadLinks(42, listOf(stored.id), "public").single()
        val query = UriComponentsBuilder.fromUriString(response.signedDownloadUrl).build().queryParams

        assertEquals("public", response.scope)
        assertEquals(Instant.parse("2026-07-15T00:01:00Z").epochSecond, query.getFirst("expires")!!.toLong())
    }

    @Test
    fun `user scope ttl falls back to download token ttl when user ttl is unset`() {
        val fallbackService = FileServiceImpl(
            fileRepository = repository,
            productRepository = productRepository,
            properties = FileProperties(
                storagePath = storageRoot.toString(),
                baseUrl = "https://files.example.test/",
                signingSecret = "test-file-signing-secret-must-be-long-enough",
                downloadTokenTtlSeconds = 999L,
            ),
            linkSigner = signer,
        )
        val stored = storedFile()
        Mockito.`when`(repository.findAllByIdInAndOwnerId(listOf(stored.id), 42))
            .thenReturn(listOf(stored))

        val response = fallbackService.createDownloadLinks(42, listOf(stored.id), null).single()
        val query = UriComponentsBuilder.fromUriString(response.signedDownloadUrl).build().queryParams

        assertEquals("user:42", response.scope)
        assertEquals(
            Instant.parse("2026-07-15T00:16:39Z").epochSecond,
            query.getFirst("expires")!!.toLong(),
        )
    }

    @Test
    fun `rejects batch link issuance for another users file`() {
        val stored = storedFile().apply { ownerId = 43 }
        Mockito.`when`(repository.findAllByIdInAndOwnerId(listOf(stored.id), 42)).thenReturn(emptyList())
        Mockito.`when`(repository.findAllById(listOf(stored.id))).thenReturn(listOf(stored))

        assertFailsWith<ForbiddenException> {
            service.createDownloadLinks(42, listOf(stored.id), null)
        }
    }

    @Test
    fun `returns not found when a batch link file does not exist`() {
        val missingId = UUID.randomUUID()
        Mockito.`when`(repository.findAllByIdInAndOwnerId(listOf(missingId), 42)).thenReturn(emptyList())
        Mockito.`when`(repository.findAllById(listOf(missingId))).thenReturn(emptyList())

        assertFailsWith<ResourceNotFoundException> {
            service.createDownloadLinks(42, listOf(missingId), null)
        }
    }

    @Test
    fun `rejects deletion of another users file`() {
        val stored = storedFile().apply { ownerId = 43 }
        Mockito.`when`(repository.findByIdAndOwnerId(stored.id, 42)).thenReturn(null)
        Mockito.`when`(repository.findById(stored.id)).thenReturn(Optional.of(stored))

        assertFailsWith<ForbiddenException> {
            service.delete(42, stored.id)
        }
    }

    @Test
    fun `support ticket links omit owner identifiers and are not limited by api batch size`() {
        val constrainedService = FileServiceImpl(
            fileRepository = repository,
            productRepository = productRepository,
            properties = FileProperties(
                storagePath = storageRoot.toString(),
                baseUrl = "https://files.example.test/",
                signingSecret = "test-file-signing-secret-must-be-long-enough",
                maxBatchSize = 1,
            ),
            linkSigner = signer,
        )
        val files = listOf(
            StoredFile(id = UUID.randomUUID(), ownerId = 42, originalFilename = "one.txt"),
            StoredFile(id = UUID.randomUUID(), ownerId = 43, originalFilename = "two.txt"),
        )

        val response = constrainedService.createSupportTicketDownloadLinks(files)

        assertEquals(listOf(SUPPORT_TICKET_DOWNLOAD_SCOPE, SUPPORT_TICKET_DOWNLOAD_SCOPE), response.map { it.scope })
        response.forEach { details ->
            val query = UriComponentsBuilder.fromUriString(details.signedDownloadUrl).build().queryParams
            assertEquals(SUPPORT_TICKET_DOWNLOAD_SCOPE, query.getFirst("scope"))
            assertFalse(details.signedDownloadUrl.contains("user:"))
        }
        Mockito.verifyNoInteractions(repository)
    }

    @Test
    fun `removes copied files when metadata persistence fails`() {
        Mockito.`when`(repository.saveAllAndFlush(ArgumentMatchers.anyList<StoredFile>()))
            .thenThrow(IllegalStateException("database unavailable"))

        assertFailsWith<IllegalStateException> {
            service.upload(
                ownerId = 42,
                files = listOf(MockMultipartFile("files", "invoice.txt", "text/plain", "payload".toByteArray())),
            )
        }

        Files.walk(storageRoot).use { paths ->
            assertFalse(paths.anyMatch { Files.isRegularFile(it) })
        }
    }

    @Test
    fun `removes uploaded content when an enclosing transaction rolls back`() {
        var persisted: List<StoredFile> = emptyList()
        Mockito.`when`(repository.saveAllAndFlush(ArgumentMatchers.anyList<StoredFile>()))
            .thenAnswer { invocation ->
                persisted = invocation.getArgument<List<StoredFile>>(0)
                persisted
            }
        TransactionSynchronizationManager.initSynchronization()
        try {
            service.upload(
                ownerId = 42,
                files = listOf(MockMultipartFile("files", "evidence.txt", "text/plain", "payload".toByteArray())),
            )
            val diskPath = storageRoot.resolve(persisted.single().relativePath)
            assertTrue(Files.isRegularFile(diskPath))

            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }

            assertFalse(Files.exists(diskPath))
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `restores file content when metadata deletion fails`() {
        val fileId = UUID.randomUUID()
        val relativePath = "2026/07/15/invoice.txt"
        val diskPath = storageRoot.resolve(relativePath)
        Files.createDirectories(diskPath.parent)
        Files.writeString(diskPath, "payload")
        val stored = StoredFile(
            id = fileId,
            ownerId = 42,
            originalFilename = "invoice.txt",
            storedFilename = "invoice.txt",
            relativePath = relativePath,
            sizeBytes = 7,
            sha256 = "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
        )
        Mockito.`when`(repository.findByIdAndOwnerId(fileId, 42)).thenReturn(stored)
        Mockito.doThrow(IllegalStateException("database unavailable")).`when`(repository).flush()

        assertFailsWith<IllegalStateException> {
            service.delete(ownerId = 42, fileId = fileId)
        }

        assertTrue(Files.isRegularFile(diskPath))
        assertEquals("payload", diskPath.readText())
    }

    @Test
    fun `deletes owner files while preserving product image files still referenced by products`() {
        val ordinaryFile = storedFile().apply { id = UUID.randomUUID() }
        val productImageFile = storedFile().apply { id = UUID.randomUUID() }
        listOf(ordinaryFile, productImageFile).forEach { stored ->
            val diskPath = storageRoot.resolve(stored.relativePath)
            Files.createDirectories(diskPath.parent)
            Files.writeString(diskPath, stored.id.toString())
        }
        Mockito.`when`(repository.findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L)))
            .thenReturn(listOf(ordinaryFile, productImageFile))
        Mockito.`when`(productRepository.findAllImageUrls())
            .thenReturn(listOf("https://files.example.test/api/product-images/${productImageFile.id}?signature=abc"))

        service.deleteAllByOwnerIds(listOf(42L))

        Mockito.verify(repository).deleteAll(listOf(ordinaryFile))
        Mockito.verify(repository).flush()
        assertFalse(Files.exists(storageRoot.resolve(ordinaryFile.relativePath)))
        assertTrue(Files.isRegularFile(storageRoot.resolve(productImageFile.relativePath)))
    }

    @Test
    fun `does not scan product image urls when the owner has no files`() {
        Mockito.`when`(repository.findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L))).thenReturn(emptyList())

        service.deleteAllByOwnerIds(listOf(42L))

        Mockito.verifyNoInteractions(productRepository)
        Mockito.verify(repository).findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L))
    }

    @Test
    fun `deletes files for multiple owners with one repository scan`() {
        val first = storedFile().apply { id = UUID.randomUUID(); ownerId = 42 }
        val second = storedFile().apply { id = UUID.randomUUID(); ownerId = 43 }
        listOf(first, second).forEach { stored ->
            val diskPath = storageRoot.resolve(stored.relativePath)
            Files.createDirectories(diskPath.parent)
            Files.writeString(diskPath, "payload")
        }
        Mockito.`when`(repository.findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L, 43L)))
            .thenReturn(listOf(first, second))
        Mockito.`when`(productRepository.findAllImageUrls()).thenReturn(emptyList())

        service.deleteAllByOwnerIds(listOf(42L, 43L, 42L))

        Mockito.verify(repository).findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L, 43L))
        Mockito.verify(repository).deleteAll(listOf(first, second))
        assertFalse(Files.exists(storageRoot.resolve(first.relativePath)))
        assertFalse(Files.exists(storageRoot.resolve(second.relativePath)))
    }

    @Test
    fun `does not treat unrelated urls as product image references`() {
        val file = storedFile().apply { id = UUID.randomUUID() }
        val diskPath = storageRoot.resolve(file.relativePath)
        Files.createDirectories(diskPath.parent)
        Files.writeString(diskPath, "payload")
        Mockito.`when`(repository.findAllByOwnerIdInOrderByCreatedAtAsc(listOf(42L))).thenReturn(listOf(file))
        Mockito.`when`(productRepository.findAllImageUrls()).thenReturn(
            listOf(
                "https://files.example.test/api/product-images/${file.id}/not-a-valid-boundary",
                "https://files.example.test/api/other/${file.id}",
            ),
        )

        service.deleteAllByOwnerIds(listOf(42L))

        Mockito.verify(repository).deleteAll(listOf(file))
        assertFalse(Files.exists(diskPath))
    }

    private fun storedFile() = StoredFile(
        id = UUID.randomUUID(),
        ownerId = 42,
        originalFilename = "invoice.txt",
        storedFilename = "invoice.txt",
        relativePath = "2026/07/15/invoice-${UUID.randomUUID()}.txt",
        sizeBytes = 7,
        sha256 = "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
    )
}
