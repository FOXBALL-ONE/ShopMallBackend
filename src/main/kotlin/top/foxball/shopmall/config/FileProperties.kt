package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 文件上传/下载配置（shopmall.file.*）。 */
@ConfigurationProperties(prefix = "shopmall.file")
data class FileProperties(
    val storagePath: String = "./storage",
    val baseUrl: String = "http://localhost:8080",
    /** 下载链接 HMAC 密钥；生产环境必须通过环境变量覆盖开发默认值。 */
    val signingSecret: String = "dev-file-signing-secret-do-not-use-in-prod",
    /** 下载链接有效期（秒）；当 signing.user-ttl-seconds 未配置时作为 user scope 的回退 TTL。 */
    val downloadTokenTtlSeconds: Long = 300L,
    /** 单次批量接口可接受的最大文件数量。 */
    val maxBatchSize: Int = 20,
    /** 单文件字节上限，与 Spring Multipart 限制共同生效。 */
    val maxFileSizeBytes: Long = 104_857_600L,
    val signing: SigningProperties = SigningProperties(),
) {
    init {
        require(signingSecret.length >= MIN_SIGNING_SECRET_LENGTH) {
            "File signing secret must be at least $MIN_SIGNING_SECRET_LENGTH characters."
        }
        require(maxBatchSize > 0) { "File batch size must be positive." }
        require(maxFileSizeBytes > 0) { "File size limit must be positive." }
        signing.validate(downloadTokenTtlSeconds)
    }

    /**
     * 按授权范围分级的签名 TTL。`userTtlSeconds` 留默认哨兵 [USER_TTL_UNSET] 时
     * 回退到 [FileProperties.downloadTokenTtlSeconds]，兑现设计文档的兼容别名约定。
     */
    data class SigningProperties(
        val publicTtlSeconds: Long = 60,
        val userTtlSeconds: Long = USER_TTL_UNSET,
        val adminTtlSeconds: Long = 180,
        val orderTtlSeconds: Long = 300,
    ) {
        fun validate(downloadTokenTtlSeconds: Long) {
            require(publicTtlSeconds > 0) { "Public file link TTL must be positive." }
            require(adminTtlSeconds > 0) { "Admin file link TTL must be positive." }
            require(orderTtlSeconds > 0) { "Order file link TTL must be positive." }
            require(downloadTokenTtlSeconds > 0) { "File download link TTL must be positive." }
        }

        /** 实际生效的 user 链接 TTL；未显式配置时回退到 downloadTokenTtlSeconds。 */
        fun resolvedUserTtl(downloadTokenTtlSeconds: Long): Long =
            if (userTtlSeconds > 0) userTtlSeconds else downloadTokenTtlSeconds
    }

    private companion object {
        const val MIN_SIGNING_SECRET_LENGTH = 32
        const val USER_TTL_UNSET = -1L
    }
}
