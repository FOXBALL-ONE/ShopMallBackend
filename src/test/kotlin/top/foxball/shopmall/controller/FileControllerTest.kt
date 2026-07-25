package top.foxball.shopmall.controller

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import top.foxball.shopmall.handler.GlobalExceptionHandler
import top.foxball.shopmall.service.DownloadableFile
import top.foxball.shopmall.service.FileMetadataResponse
import top.foxball.shopmall.service.FileService
import top.foxball.shopmall.shared.ResponseBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

class FileControllerTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var fileService: FileService
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        fileService = mock(FileService::class.java)
        mockMvc = MockMvcBuilders.standaloneSetup(FileController(fileService, ResponseBuilder()))
            .setControllerAdvice(GlobalExceptionHandler())
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `lists current user files with pagination metadata`() {
        authenticate(42)
        val pageable = PageRequest.of(1, 10)
        `when`(fileService.list(42, pageable)).thenReturn(
            PageImpl(listOf(metadata()), pageable, 25),
        )

        mockMvc.perform(get("/api/files/mine?page=1&size=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.size").value(10))
            .andExpect(jsonPath("$.data.totalElements").value(25))
            .andExpect(jsonPath("$.data.files[0].scope").value("user:42"))

        verify(fileService).list(42, pageable)
    }

    @Test
    fun `downloads with scope nonce signature and attachment headers`() {
        val fileId = UUID.randomUUID()
        val path = tempDir.resolve("report.txt")
        Files.writeString(path, "payload")
        `when`(
            fileService.openSignedDownload(
                fileId,
                "public",
                2_000_000_000,
                "nonce-1",
                "signature-1",
                null,
                false,
            ),
        ).thenReturn(DownloadableFile(path, "report.txt", MediaType.TEXT_PLAIN_VALUE, 7))

        mockMvc.perform(
            get("/api/files/$fileId/download")
                .queryParam("scope", "public")
                .queryParam("expires", "2000000000")
                .queryParam("nonce", "nonce-1")
                .queryParam("signature", "signature-1"),
        )
            .andExpect(status().isOk)
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
    }

    @Test
    fun `old userId download parameters are rejected`() {
        mockMvc.perform(
            get("/api/files/${UUID.randomUUID()}/download")
                .queryParam("userId", "42")
                .queryParam("expires", "2000000000")
                .queryParam("signature", "legacy"),
        ).andExpect(status().isBadRequest)
    }

    private fun authenticate(userId: Long) {
        SecurityContextHolder.getContext().authentication = TestingAuthenticationToken(userId, null)
    }

    private fun metadata() = FileMetadataResponse(
        id = UUID.randomUUID(),
        fileName = "report.txt",
        contentType = MediaType.TEXT_PLAIN_VALUE,
        sizeBytes = 7,
        sha256 = "hash",
        createdAt = LocalDateTime.parse("2026-07-25T08:00:00"),
        signedDownloadUrl = "https://files.test/download",
        downloadExpiresAt = LocalDateTime.parse("2026-07-25T08:05:00"),
        scope = "user:42",
        storage = "local",
    )
}
