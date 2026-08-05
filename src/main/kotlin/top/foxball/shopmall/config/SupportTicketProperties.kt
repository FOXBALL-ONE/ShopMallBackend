package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 工单创建、消息发送及附件安全限制（shopmall.support-ticket.*）。 */
@ConfigurationProperties(prefix = "shopmall.support-ticket")
data class SupportTicketProperties(
    /** 单个客户在创建限流窗口内最多创建的工单数。 */
    val createRateLimit: Int = 5,
    /** 工单创建限流窗口，单位秒。 */
    val createRateWindowSeconds: Long = 600,
    /** 单个发送者在单个工单的消息限流窗口内最多发送的消息数。 */
    val messageRateLimit: Int = 30,
    /** 工单消息限流窗口，单位秒。 */
    val messageRateWindowSeconds: Long = 60,
    /** 工单幂等记录在 Redis 中的保存时间，单位秒。 */
    val idempotencyTtlSeconds: Long = 900,
    /** 单条工单消息允许的最大附件数量。 */
    val maxFilesPerMessage: Int = 10,
    /** 单条工单消息附件总字节上限。 */
    val maxAttachmentBytesPerMessage: Long = 52_428_800,
    /** 在 Multipart 解析前按 Content-Length 拦截的工单消息请求大小上限。 */
    val maxMessageRequestBytes: Long = 62_914_560,
    /** 单个工单允许保存的最大附件数量。 */
    val maxAttachmentsPerTicket: Long = 100,
    /** 单个工单允许保存的附件总字节上限。 */
    val maxAttachmentBytesPerTicket: Long = 536_870_912,
    /** 单个客户允许保存的工单附件总数量。 */
    val maxAttachmentsPerCustomer: Long = 500,
    /** 单个客户允许保存的工单附件总字节上限。 */
    val maxAttachmentBytesPerCustomer: Long = 2_147_483_648,
) {
    init {
        require(createRateLimit > 0) { "Support ticket create rate limit must be positive." }
        require(createRateWindowSeconds > 0) { "Support ticket create rate window must be positive." }
        require(messageRateLimit > 0) { "Support ticket message rate limit must be positive." }
        require(messageRateWindowSeconds > 0) { "Support ticket message rate window must be positive." }
        require(idempotencyTtlSeconds > 0) { "Support ticket idempotency TTL must be positive." }
        require(maxFilesPerMessage > 0) { "Support ticket message file limit must be positive." }
        require(maxAttachmentBytesPerMessage > 0) { "Support ticket message byte limit must be positive." }
        require(maxMessageRequestBytes >= maxAttachmentBytesPerMessage) {
            "Support ticket request byte limit must cover the attachment byte limit."
        }
        require(maxAttachmentsPerTicket > 0) { "Support ticket attachment count limit must be positive." }
        require(maxAttachmentBytesPerTicket >= maxAttachmentBytesPerMessage) {
            "Support ticket byte quota must cover one message."
        }
        require(maxAttachmentsPerCustomer >= maxAttachmentsPerTicket) {
            "Customer attachment count quota must cover one ticket."
        }
        require(maxAttachmentBytesPerCustomer >= maxAttachmentBytesPerTicket) {
            "Customer attachment byte quota must cover one ticket."
        }
    }
}
