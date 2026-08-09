package top.foxball.shopmall.service.impl

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import top.foxball.shopmall.config.MailProperties
import top.foxball.shopmall.handler.EmailSendFailedException
import top.foxball.shopmall.handler.VerificationCodeInvalidException
import top.foxball.shopmall.handler.VerificationCodeRateLimitException
import top.foxball.shopmall.service.MailService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration

/**
 * [MailService] 的实现：验证码记录、校验失败计数、发送间隔锁、每日计数与 IP 计数均经
 * [StringRedisTemplate] 以可读 key 存储（见 [top.foxball.shopmall.config.RedisTemplateConfig] 关于可读 key 的说明）。
 *
 * - 验证码记录：`mail:verification:code:{email}` → Redis Hash{code,ua,uid}，TTL = [MailProperties.ttlSeconds]
 * - 校验失败计数：`mail:verification:attempts:{email}`，首次失败起 TTL = [MailProperties.ttlSeconds]；
 *   签发新验证码或校验通过时清零，达 [MailProperties.maxAttempts] 后拒绝继续校验
 * - 发送间隔锁：`mail:verification:lock:{email}`，TTL = [MailProperties.sendIntervalSeconds]
 * - 每日计数：`mail:verification:daily:{email}`，首次自增起 24 小时失效
 * - IP 计数：`mail:verification:ip:{ip}`，首次自增起 1 小时失效；达 [MailProperties.ipHourlyLimit] 后拒绝
 *
 * 验证码载荷以 Redis Hash 存放（HGETALL 可直读），不引入额外的序列化依赖；
 * 验证码本身使用 [SecureRandom] 生成、常量时间比对（[MessageDigest.isEqual]），
 * 与 [top.foxball.shopmall.service.FileLinkSigner] 的签名比对方式一致。
 */
@Service
class MailServiceImpl(
    private val mailSender: JavaMailSender,
    private val redis: StringRedisTemplate,
    private val properties: MailProperties,
    @Value("\${spring.mail.username:}") private val mailUsername: String,
) : MailService {

    private val codes = redis.opsForHash<String, String>()
    private val verificationEmailTemplate = ClassPathResource("templates/mail/verification-code.html")
        .inputStream
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }

    override fun sendCode(email: String, userAgent: String, userId: Long?, ip: String) {
        val target = normalizeEmail(email)

        // 单 IP 频率限制：限制同一来源 IP 跨邮箱的发送总量，防止遍历邮箱轰炸
        val ipCountKey = ipKey(ip)
        val sentFromIp = redis.opsForValue().get(ipCountKey)?.toLongOrNull() ?: 0L
        if (sentFromIp >= properties.ipHourlyLimit) {
            throw VerificationCodeRateLimitException("请求过于频繁，请稍后再试")
        }
        // 发送间隔锁：同一邮箱短时间内只允许一次，避免邮件轰炸
        if (redis.hasKey(lockKey(target))) {
            throw VerificationCodeRateLimitException("验证码发送过于频繁，请稍后再试")
        }
        // 每日上限：超过则拒绝，防止针对单一邮箱的持续骚扰
        val dailyKey = dailyKey(target)
        val sentToday = redis.opsForValue().get(dailyKey)?.toLongOrNull() ?: 0L
        if (sentToday >= properties.dailyLimit) {
            throw VerificationCodeRateLimitException("今日验证码发送次数已达上限")
        }

        // 先存后发：即使投递失败也只是留下一条过期记录，不影响用户立即重试
        val code = generateCode()
        val fields = hashMapOf(
            FIELD_CODE to code,
            FIELD_UA to userAgent,
            FIELD_UID to (userId?.toString() ?: ""),
        )
        codes.putAll(codeKey(target), fields)
        redis.expire(codeKey(target), Duration.ofSeconds(properties.ttlSeconds))
        // 新验证码重置失败计数：每个验证码都获得完整的校验预算
        redis.delete(attemptsKey(target))

        sendMail(target, code)

        // 投递成功后再记账：设置间隔锁、累计当日次数与 IP 计数（均为首次自增时设置 TTL）
        redis.opsForValue().set(lockKey(target), "1", Duration.ofSeconds(properties.sendIntervalSeconds))
        incrementWithTtl(dailyKey, Duration.ofDays(1))
        incrementWithTtl(ipCountKey, Duration.ofHours(1))
    }

    override fun verifyCode(email: String, code: String, userAgent: String, userId: Long?) {
        val target = normalizeEmail(email)
        val attemptsKey = attemptsKey(target)

        // 频率限制：同一邮箱错误次数达上限即拒绝，避免对 6 位验证码暴力枚举
        val failed = redis.opsForValue().get(attemptsKey)?.toIntOrNull() ?: 0
        if (failed >= properties.maxAttempts) {
            throw VerificationCodeRateLimitException("验证码错误次数过多，请重新获取验证码")
        }

        val fields = codes.entries(codeKey(target))
        if (fields.isEmpty()) throw VerificationCodeInvalidException()
        val storedCode = fields[FIELD_CODE] ?: throw VerificationCodeInvalidException()
        val storedUa = fields[FIELD_UA] ?: throw VerificationCodeInvalidException()
        val storedUid = fields[FIELD_UID]?.takeIf { it.isNotEmpty() }?.toLongOrNull()

        // 验证码常量时间比对；UA 与 userId 必须与签发时一致，杜绝跨客户端/跨场景重放
        val supplied = code.trim().toByteArray(StandardCharsets.US_ASCII)
        val matched = MessageDigest.isEqual(storedCode.toByteArray(StandardCharsets.US_ASCII), supplied) &&
            storedUa == userAgent &&
            storedUid == userId

        if (!matched) {
            // 记一次失败尝试（首次失败设置 TTL，与验证码一致）
            incrementWithTtl(attemptsKey, Duration.ofSeconds(properties.ttlSeconds))
            throw VerificationCodeInvalidException()
        }

        // 一次性：校验通过后清除验证码与失败计数
        redis.delete(codeKey(target))
        redis.delete(attemptsKey)
    }

    /** 邮箱归一化为小写并去空白，确保 send/verify 两端命中同一 Redis key。 */
    private fun normalizeEmail(email: String): String = email.trim().lowercase()

    private fun codeKey(email: String): String = "$KEY_PREFIX_CODE$email"
    private fun attemptsKey(email: String): String = "$KEY_PREFIX_ATTEMPTS$email"
    private fun lockKey(email: String): String = "$KEY_PREFIX_LOCK$email"
    private fun dailyKey(email: String): String = "$KEY_PREFIX_DAILY$email"
    private fun ipKey(ip: String): String = "$KEY_PREFIX_IP${ip.trim().lowercase()}"

    /** 自增计数器，仅在首次自增（值由 0→1）时设置 TTL，后续累加沿用同一窗口。 */
    private fun incrementWithTtl(key: String, ttl: Duration) {
        val updated = redis.opsForValue().increment(key) ?: 1L
        if (updated == 1L) redis.expire(key, ttl)
    }

    /** 按 [MailProperties.codeLength] 生成纯数字验证码，不足位前补零。 */
    private fun generateCode(): String {
        val bound = (0 until properties.codeLength).fold(1) { acc, _ -> acc * 10 }
        return secureRandom.nextInt(bound).toString().padStart(properties.codeLength, '0')
    }

    private fun sendMail(to: String, code: String) {
        try {
            val minutes = (properties.ttlSeconds / 60).coerceAtLeast(1)
            val html = verificationEmailTemplate
                .replace("{{verification_code}}", code)
                .replace("{{expiry_minutes}}", minutes.toString())
            val message = mailSender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setTo(to)
                setFrom(resolveFrom())
                setSubject("${properties.subjectPrefix} | Your verification code")
                setText(
                    "Your PELISSA verification code is $code. It expires in $minutes minutes. " +
                        "If you did not request this, you can safely ignore this email.",
                    html,
                )
            }
            mailSender.send(message)
        } catch (ex: Exception) {
            throw EmailSendFailedException()
        }
    }

    /** 发件人优先取配置；留空回退到 spring.mail.username。 */
    private fun resolveFrom(): String = properties.from.ifBlank { mailUsername }

    private companion object {
        const val KEY_PREFIX_CODE = "mail:verification:code:"
        const val KEY_PREFIX_ATTEMPTS = "mail:verification:attempts:"
        const val KEY_PREFIX_LOCK = "mail:verification:lock:"
        const val KEY_PREFIX_DAILY = "mail:verification:daily:"
        const val KEY_PREFIX_IP = "mail:verification:ip:"
        const val FIELD_CODE = "code"
        const val FIELD_UA = "ua"
        const val FIELD_UID = "uid"
        val secureRandom = SecureRandom()
    }
}
