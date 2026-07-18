package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 文件上传/下载配置（shopmall.file.*）。 */
@ConfigurationProperties(prefix = "shopmall.file")
data class FileProperties(
    val storagePath: String = "./storage",
    val baseUrl: String = "http://localhost:8080",
    /** 下载链接 HMAC 密钥；生产环境必须通过环境变量覆盖开发默认值。 */
    val signingSecret: String = "dev-file-signing-secret-do-not-use-in-prod",
    /** 下载链接有效期，单位为秒。 */
    val downloadTokenTtlSeconds: Long = 300L,
    /** 单次批量接口可接受的最大文件数量。 */
    val maxBatchSize: Int = 20,
    /** 单文件字节上限，与 Spring Multipart 限制共同生效。 */
    val maxFileSizeBytes: Long = 104_857_600L,
) {
    init {
        require(signingSecret.length >= MIN_SIGNING_SECRET_LENGTH) {
            "File signing secret must be at least $MIN_SIGNING_SECRET_LENGTH characters."
        }
        require(downloadTokenTtlSeconds > 0) { "File download link TTL must be positive." }
        require(maxBatchSize > 0) { "File batch size must be positive." }
        require(maxFileSizeBytes > 0) { "File size limit must be positive." }
    }

    private companion object {
        const val MIN_SIGNING_SECRET_LENGTH = 32
    }
}
