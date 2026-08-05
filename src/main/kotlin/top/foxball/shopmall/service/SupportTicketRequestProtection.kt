package top.foxball.shopmall.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import top.foxball.shopmall.config.SupportTicketProperties
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import top.foxball.shopmall.handler.IdempotencyConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.handler.SupportTicketAttachmentLimitException
import top.foxball.shopmall.handler.SupportTicketRateLimitException
import top.foxball.shopmall.handler.SupportTicketRequestInProgressException
import top.foxball.shopmall.handler.SupportTicketUnsafeAttachmentException
import top.foxball.shopmall.repository.SupportTicketMessageAttachmentRepository
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat

/** 工单请求的限流、幂等、附件配额和基础文件安全检查。 */
@Component
class SupportTicketRequestProtection(
    private val redis: StringRedisTemplate,
    private val properties: SupportTicketProperties,
    private val attachmentRepository: SupportTicketMessageAttachmentRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface Acquisition {
        data class Acquired(val token: Token) : Acquisition
        data class Completed(val resultId: Long) : Acquisition
        data object Pending : Acquisition
    }

    class Token internal constructor(
        internal val redisKey: String,
        internal val pendingValue: String,
        internal val completedPrefix: String,
    )

    /** 校验客户端幂等键，避免空白键和不可控 Redis key。 */
    fun normalizeIdempotencyKey(value: String): String {
        val normalized = value.trim()
        if (normalized.length !in IDEMPOTENCY_KEY_MIN_LENGTH..IDEMPOTENCY_KEY_MAX_LENGTH ||
            !IDEMPOTENCY_KEY_PATTERN.matches(normalized)
        ) {
            throw ParamErrorException(
                "Idempotency-Key 必须为 $IDEMPOTENCY_KEY_MIN_LENGTH-$IDEMPOTENCY_KEY_MAX_LENGTH 位字母、数字或 ._:-",
            )
        }
        return normalized
    }

    /** 对结构化字段与附件内容生成请求指纹，同时执行附件类型和基础恶意内容检查。 */
    fun fingerprint(fields: List<String?>, files: List<MultipartFile> = emptyList()): String {
        if (files.size > properties.maxFilesPerMessage) {
            throw SupportTicketAttachmentLimitException(
                "单条工单消息最多上传 ${properties.maxFilesPerMessage} 个附件",
            )
        }
        var totalBytes = 0L
        val requestDigest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field -> updateLengthPrefixed(requestDigest, field?.toByteArray(StandardCharsets.UTF_8)) }

        files.forEach { file ->
            if (file.isEmpty || file.size <= 0) {
                throw SupportTicketUnsafeAttachmentException("工单附件不能为空文件")
            }
            totalBytes = try {
                Math.addExact(totalBytes, file.size)
            } catch (_: ArithmeticException) {
                throw SupportTicketAttachmentLimitException("单条工单消息附件总大小超出限制")
            }
            if (totalBytes > properties.maxAttachmentBytesPerMessage) {
                throw SupportTicketAttachmentLimitException(
                    "单条工单消息附件总大小不能超过 ${properties.maxAttachmentBytesPerMessage} 字节",
                )
            }

            val fileName = file.originalFilename
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw SupportTicketUnsafeAttachmentException("工单附件缺少文件名")
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val contentType = file.contentType?.substringBefore(';')?.trim()?.lowercase()
            val expectedType = SUPPORTED_FILE_TYPES[extension]
                ?: throw SupportTicketUnsafeAttachmentException("不支持 .$extension 类型的工单附件")
            if (contentType != expectedType) {
                throw SupportTicketUnsafeAttachmentException("工单附件扩展名与 Content-Type 不匹配")
            }

            updateLengthPrefixed(requestDigest, fileName.toByteArray(StandardCharsets.UTF_8))
            updateLengthPrefixed(requestDigest, contentType.toByteArray(StandardCharsets.UTF_8))
            updateLengthPrefixed(requestDigest, file.size.toString().toByteArray(StandardCharsets.US_ASCII))

            val prefix = ByteArrayOutputStream(FILE_SIGNATURE_PREFIX_BYTES)
            var malwareCarry = ""
            var containsNullByte = false
            file.inputStream.use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    requestDigest.update(buffer, 0, read)
                    if (prefix.size() < FILE_SIGNATURE_PREFIX_BYTES) {
                        prefix.write(buffer, 0, minOf(read, FILE_SIGNATURE_PREFIX_BYTES - prefix.size()))
                    }
                    if (extension == "txt" && !containsNullByte) {
                        containsNullByte = buffer.copyOfRange(0, read).any { it == 0.toByte() }
                    }
                    val searchable = malwareCarry + String(buffer, 0, read, StandardCharsets.ISO_8859_1)
                    if (searchable.contains(EICAR_SIGNATURE)) {
                        throw SupportTicketUnsafeAttachmentException("工单附件未通过恶意内容检查")
                    }
                    malwareCarry = searchable.takeLast(EICAR_SIGNATURE.length - 1)
                }
            }
            validateFileSignature(extension, prefix.toByteArray(), containsNullByte)
        }
        return HexFormat.of().formatHex(requestDigest.digest())
    }

    fun acquire(
        operation: String,
        actorId: Long,
        clientKey: String,
        fingerprint: String,
    ): Acquisition {
        val redisKey = "support-ticket:idempotency:$operation:$actorId:${sha256(clientKey)}"
        val pendingValue = "$PENDING_PREFIX$fingerprint"
        val completedPrefix = "$COMPLETED_PREFIX$fingerprint:"
        val result = redis.execute(
            acquireScript,
            listOf(redisKey),
            pendingValue,
            completedPrefix,
            properties.idempotencyTtlSeconds.toString(),
        ) ?: throw SupportTicketRequestInProgressException("无法确认工单请求状态，请稍后重试")
        return when {
            result == ACQUIRED -> Acquisition.Acquired(Token(redisKey, pendingValue, completedPrefix))
            result == PENDING -> Acquisition.Pending
            result == CONFLICT -> throw IdempotencyConflictException()
            result.startsWith(COMPLETED_RESULT_PREFIX) -> {
                val resultId = result.removePrefix(COMPLETED_RESULT_PREFIX).toLongOrNull()
                    ?: throw IdempotencyConflictException("工单幂等记录无效，请更换 Idempotency-Key")
                Acquisition.Completed(resultId)
            }
            else -> Acquisition.Pending
        }
    }

    /** 成功结果只在数据库事务提交后写入 Redis；事务回滚时释放占用。 */
    fun completeAfterCommit(token: Token, resultId: Long) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            completeNow(token, resultId)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    runCatching { completeNow(token, resultId) }
                        .onFailure { log.error("Failed to complete support ticket idempotency record", it) }
                }

                override fun afterCompletion(status: Int) {
                    if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                        runCatching { release(token) }
                            .onFailure { log.error("Failed to release rolled back support ticket idempotency record", it) }
                    }
                }
            },
        )
    }

    /** 在业务校验或持久化失败时释放尚未完成的幂等占用。 */
    fun release(token: Token) {
        redis.execute(releaseScript, listOf(token.redisKey), token.pendingValue)
    }

    fun requireCreateRateAllowed(customerId: Long) {
        requireRateAllowed(
            key = "support-ticket:rate:create:$customerId",
            limit = properties.createRateLimit,
            windowSeconds = properties.createRateWindowSeconds,
            message = "创建工单过于频繁，请稍后再试",
        )
    }

    fun requireMessageRateAllowed(senderId: Long, senderType: SupportTicketMessageSender, ticketId: Long) {
        requireRateAllowed(
            key = "support-ticket:rate:message:${senderType.name.lowercase()}:$senderId:$ticketId",
            limit = properties.messageRateLimit,
            windowSeconds = properties.messageRateWindowSeconds,
            message = "发送工单消息过于频繁，请稍后再试",
        )
    }

    /** 检查单工单配额；客户消息还会检查该客户跨工单的总配额。 */
    fun requireAttachmentQuota(
        senderId: Long,
        senderType: SupportTicketMessageSender,
        ticketId: Long,
        files: List<MultipartFile>,
    ) {
        if (files.isEmpty()) return
        val addedCount = files.size.toLong()
        val addedBytes = try {
            files.fold(0L) { total, file -> Math.addExact(total, file.size) }
        } catch (_: ArithmeticException) {
            throw SupportTicketAttachmentLimitException("工单附件总大小超出允许范围")
        }
        val ticketCount = attachmentRepository.countForTicket(ticketId)
        val ticketBytes = attachmentRepository.totalBytesForTicket(ticketId)
        if (exceedsLimit(ticketCount, addedCount, properties.maxAttachmentsPerTicket)) {
            throw SupportTicketAttachmentLimitException(
                "单个工单最多保存 ${properties.maxAttachmentsPerTicket} 个附件",
            )
        }
        if (exceedsLimit(ticketBytes, addedBytes, properties.maxAttachmentBytesPerTicket)) {
            throw SupportTicketAttachmentLimitException(
                "单个工单附件总大小不能超过 ${properties.maxAttachmentBytesPerTicket} 字节",
            )
        }
        if (senderType == SupportTicketMessageSender.CUSTOMER) {
            val customerCount = attachmentRepository.countForSender(senderId, senderType)
            val customerBytes = attachmentRepository.totalBytesForSender(senderId, senderType)
            if (exceedsLimit(customerCount, addedCount, properties.maxAttachmentsPerCustomer)) {
                throw SupportTicketAttachmentLimitException(
                    "单个客户最多保存 ${properties.maxAttachmentsPerCustomer} 个工单附件",
                )
            }
            if (exceedsLimit(customerBytes, addedBytes, properties.maxAttachmentBytesPerCustomer)) {
                throw SupportTicketAttachmentLimitException(
                    "单个客户工单附件总大小不能超过 ${properties.maxAttachmentBytesPerCustomer} 字节",
                )
            }
        }
    }

    private fun exceedsLimit(current: Long, added: Long, limit: Long): Boolean =
        added > limit || current > limit - added

    private fun completeNow(token: Token, resultId: Long) {
        redis.execute(
            completeScript,
            listOf(token.redisKey),
            token.pendingValue,
            "${token.completedPrefix}$resultId",
            properties.idempotencyTtlSeconds.toString(),
        )
    }

    private fun requireRateAllowed(key: String, limit: Int, windowSeconds: Long, message: String) {
        val retryAfter = redis.execute(
            rateLimitScript,
            listOf(key),
            limit.toString(),
            windowSeconds.toString(),
        ) ?: 0L
        if (retryAfter > 0) {
            throw SupportTicketRateLimitException(retryAfter.coerceAtLeast(1), message)
        }
    }

    private fun validateFileSignature(extension: String, prefix: ByteArray, containsNullByte: Boolean) {
        val valid = when (extension) {
            "png" -> prefix.startsWith(PNG_SIGNATURE)
            "jpg", "jpeg" -> prefix.startsWith(JPEG_SIGNATURE)
            "webp" -> prefix.size >= 12 &&
                prefix.copyOfRange(0, 4).contentEquals(RIFF_SIGNATURE) &&
                prefix.copyOfRange(8, 12).contentEquals(WEBP_SIGNATURE)
            "pdf" -> prefix.indexOf(PDF_SIGNATURE) in 0..PDF_HEADER_MAX_OFFSET
            "txt" -> !containsNullByte
            else -> false
        }
        if (!valid) {
            throw SupportTicketUnsafeAttachmentException("工单附件内容与声明的文件类型不匹配")
        }
    }

    private fun updateLengthPrefixed(digest: MessageDigest, bytes: ByteArray?) {
        if (bytes == null) {
            digest.update(NULL_FIELD_MARKER)
            return
        }
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(FIELD_SEPARATOR)
        digest.update(bytes)
        digest.update(FIELD_SEPARATOR)
    }

    private fun sha256(value: String): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && copyOfRange(0, prefix.size).contentEquals(prefix)

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        for (index in 0..size - needle.size) {
            if (copyOfRange(index, index + needle.size).contentEquals(needle)) return index
        }
        return -1
    }

    private companion object {
        const val IDEMPOTENCY_KEY_MIN_LENGTH = 8
        const val IDEMPOTENCY_KEY_MAX_LENGTH = 128
        val IDEMPOTENCY_KEY_PATTERN = Regex("[A-Za-z0-9._:-]+")
        const val FILE_SIGNATURE_PREFIX_BYTES = 1_024
        const val PDF_HEADER_MAX_OFFSET = 1_019
        const val PENDING_PREFIX = "P:"
        const val COMPLETED_PREFIX = "D:"
        const val COMPLETED_RESULT_PREFIX = "D:"
        const val ACQUIRED = "A"
        const val PENDING = "P"
        const val CONFLICT = "C"
        val FIELD_SEPARATOR = byteArrayOf(0)
        val NULL_FIELD_MARKER = byteArrayOf(0x7f)
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        val RIFF_SIGNATURE = "RIFF".toByteArray(StandardCharsets.US_ASCII)
        val WEBP_SIGNATURE = "WEBP".toByteArray(StandardCharsets.US_ASCII)
        val PDF_SIGNATURE = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
        const val EICAR_SIGNATURE = "X5O!P%@AP[4\\PZX54(P^)7CC)7}${'$'}EICAR-STANDARD-ANTIVIRUS-TEST-FILE!${'$'}H+H*"
        val SUPPORTED_FILE_TYPES = mapOf(
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "webp" to "image/webp",
            "pdf" to "application/pdf",
            "txt" to "text/plain",
        )

        val acquireScript = DefaultRedisScript(
            """
                local current = redis.call('GET', KEYS[1])
                if not current then
                    local inserted = redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[3], 'NX')
                    if inserted then return '$ACQUIRED' end
                    current = redis.call('GET', KEYS[1])
                end
                if current == ARGV[1] then return '$PENDING' end
                if string.sub(current, 1, string.len(ARGV[2])) == ARGV[2] then
                    return '$COMPLETED_RESULT_PREFIX' .. string.sub(current, string.len(ARGV[2]) + 1)
                end
                return '$CONFLICT'
            """.trimIndent(),
            String::class.java,
        )

        val completeScript = DefaultRedisScript(
            """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                    return 1
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )

        val releaseScript = DefaultRedisScript(
            """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    return redis.call('DEL', KEYS[1])
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )

        val rateLimitScript = DefaultRedisScript(
            """
                local count = redis.call('INCR', KEYS[1])
                if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
                if count > tonumber(ARGV[1]) then
                    local ttl = redis.call('TTL', KEYS[1])
                    if ttl < 1 then
                        redis.call('EXPIRE', KEYS[1], ARGV[2])
                        return tonumber(ARGV[2])
                    end
                    return ttl
                end
                return 0
            """.trimIndent(),
            Long::class.java,
        )
    }
}
