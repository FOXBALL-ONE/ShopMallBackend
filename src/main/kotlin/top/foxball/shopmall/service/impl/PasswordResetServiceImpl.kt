package top.foxball.shopmall.service.impl

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.config.PasswordResetProperties
import top.foxball.shopmall.handler.EmailSendFailedException
import top.foxball.shopmall.handler.PasswordResetTokenInvalidException
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.PasswordResetService
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

@Service
class PasswordResetServiceImpl(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val mailSender: JavaMailSender,
    private val redis: StringRedisTemplate,
    private val properties: PasswordResetProperties,
    @Value("\${spring.mail.username:}") private val mailUsername: String,
) : PasswordResetService {

    private val emailTemplate = ClassPathResource("templates/mail/password-reset.html")
        .inputStream
        .bufferedReader(StandardCharsets.UTF_8)
        .use { it.readText() }

    override fun requestReset(email: String) {
        val user = userRepository.findByEmail(email.trim().lowercase()) ?: return
        val userId = user.id ?: return
        val lockKey = "$KEY_PREFIX_LOCK$userId"
        val acquired = redis.opsForValue().setIfAbsent(
            lockKey,
            "1",
            Duration.ofSeconds(properties.sendIntervalSeconds),
        ) == true
        if (!acquired) return

        val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val tokenDigest = digest(token)
        val tokenKey = "$KEY_PREFIX_TOKEN$tokenDigest"
        val userKey = "$KEY_PREFIX_USER$userId"
        val ttlMillis = Duration.ofSeconds(properties.ttlSeconds).toMillis().toString()
        val issueKeys = mutableListOf(userKey, tokenKey)
        redis.opsForValue().get(userKey)?.let { previousDigest ->
            issueKeys += "$KEY_PREFIX_TOKEN$previousDigest"
        }

        try {
            redis.execute(issueScript, issueKeys, tokenDigest, userId.toString(), ttlMillis)
            sendMail(user.email, token)
        } catch (ex: Exception) {
            redis.execute(cleanupScript, listOf(userKey, tokenKey), tokenDigest)
            redis.delete(lockKey)
            if (ex is EmailSendFailedException) throw ex
            log.error("Failed to issue password reset link for user {}", userId, ex)
            throw EmailSendFailedException("密码重置邮件发送失败，请稍后重试")
        }
    }

    @Transactional
    override fun resetPassword(token: String, newPassword: String) {
        val normalizedToken = token.trim()
        if (normalizedToken.isEmpty()) throw PasswordResetTokenInvalidException()

        val tokenDigest = digest(normalizedToken)
        val tokenKey = "$KEY_PREFIX_TOKEN$tokenDigest"
        val userIdValue = redis.opsForValue().get(tokenKey) ?: throw PasswordResetTokenInvalidException()
        val userId = userIdValue.toLongOrNull() ?: throw PasswordResetTokenInvalidException()
        val user = userRepository.findById(userId).orElse(null)
            ?: throw PasswordResetTokenInvalidException()
        if (!user.enabled || user.status != Status.ACTIVE) {
            throw PasswordResetTokenInvalidException()
        }
        val userKey = "$KEY_PREFIX_USER$userId"
        val consumedUserId = redis.execute(consumeScript, listOf(tokenKey, userKey), tokenDigest)
            ?: throw PasswordResetTokenInvalidException()
        if (consumedUserId != userId) throw PasswordResetTokenInvalidException()

        user.password = requireNotNull(passwordEncoder.encode(newPassword)) { "密码编码失败" }
        userRepository.save(user)
        loginTokenAuthentication.revokeAll(userId)
    }

    private fun sendMail(to: String, token: String) {
        try {
            val resetUrl = properties.storefrontBaseUrl.toString().trimEnd('/') + "/reset-password#token=$token"
            val html = emailTemplate.replace("{{reset_url}}", resetUrl)
            val message = mailSender.createMimeMessage()
            MimeMessageHelper(message, true, "UTF-8").apply {
                setTo(to)
                setFrom(properties.from.ifBlank { mailUsername })
                setSubject("${properties.subjectPrefix} | Reset your password")
                setText(
                    "Use this link to reset your PELISSA password: $resetUrl " +
                        "The link expires in 5 minutes and can be used only once. " +
                        "If you did not request this, you can safely ignore this email.",
                    html,
                )
            }
            mailSender.send(message)
        } catch (ex: Exception) {
            throw EmailSendFailedException("密码重置邮件发送失败，请稍后重试")
        }
    }

    private fun digest(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(StandardCharsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN_BYTES = 32
        const val KEY_PREFIX_TOKEN = "auth:password-reset:token:"
        const val KEY_PREFIX_USER = "auth:password-reset:user:"
        const val KEY_PREFIX_LOCK = "auth:password-reset:lock:"
        val secureRandom = SecureRandom()
        val log = LoggerFactory.getLogger(PasswordResetServiceImpl::class.java)

        val issueScript = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                if KEYS[3] then
                    redis.call('DEL', KEYS[3])
                end
                redis.call('PSETEX', KEYS[2], ARGV[3], ARGV[2])
                redis.call('PSETEX', KEYS[1], ARGV[3], ARGV[1])
                return 1
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        val cleanupScript = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                if redis.call('GET', KEYS[1]) == ARGV[1] then
                    redis.call('DEL', KEYS[1])
                    redis.call('DEL', KEYS[2])
                    return 1
                end
                return 0
                """.trimIndent(),
            )
            resultType = Long::class.java
        }

        val consumeScript = DefaultRedisScript<Long>().apply {
            setScriptText(
                """
                local user_id = redis.call('GET', KEYS[1])
                if not user_id or redis.call('GET', KEYS[2]) ~= ARGV[1] then
                    return nil
                end
                redis.call('DEL', KEYS[1])
                redis.call('DEL', KEYS[2])
                return tonumber(user_id)
                """.trimIndent(),
            )
            resultType = Long::class.java
        }
    }
}
