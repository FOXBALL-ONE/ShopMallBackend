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
import org.springframework.web.util.UriComponentsBuilder
import top.foxball.shopmall.config.FileProperties
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.repository.StoredFileRepository
import top.foxball.shopmall.service.FileLinkSigner
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** 使用临时存储目录和模拟仓储验证文件服务的磁盘与元数据一致性。 */
class FileServiceImplTest {
    @TempDir
    lateinit var storageRoot: Path

    private lateinit var repository: StoredFileRepository
    private lateinit var signer: FileLinkSigner
    private lateinit var service: FileServiceImpl

    @BeforeTest
    fun setUp() {
        repository = Mockito.mock(StoredFileRepository::class.java)
        signer = FileLinkSigner(
            secret = "test-file-signing-secret-must-be-long-enough",
            clock = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC),
        )
        service = FileServiceImpl(
            fileRepository = repository,
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
        assertEquals("quarterly report.txt", response.fileName)
        assertTrue(diskPath.startsWith(storageRoot))
        assertEquals("payload", diskPath.readText())
        assertEquals("239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5", stored.sha256)

        val query = UriComponentsBuilder.fromUriString(response.signedDownloadUrl).build().queryParams
        assertTrue(
            signer.isValid(
                stored.id,
                query.getFirst("userId")!!.toLong(),
                query.getFirst("expires")!!.toLong(),
                query.getFirst("signature")!!,
            ),
        )
        assertFalse(
            signer.isValid(
                stored.id,
                43,
                query.getFirst("expires")!!.toLong(),
                query.getFirst("signature")!!,
            ),
        )
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
}
