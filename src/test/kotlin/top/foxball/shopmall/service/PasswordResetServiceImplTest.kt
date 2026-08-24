package top.foxball.shopmall.service

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.crypto.password.PasswordEncoder
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.config.PasswordResetProperties
import top.foxball.shopmall.entity.jdbc.User
import top.foxball.shopmall.handler.PasswordResetTokenInvalidException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.impl.PasswordResetServiceImpl
import java.net.URI
import java.time.Duration
import java.util.Optional
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PasswordResetServiceImplTest {
    private val userRepository = mock(UserRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val loginTokenAuthentication = mock(LoginTokenAuthentication::class.java)
    private val mailSender = mock(JavaMailSender::class.java)
    private val redis = mock(StringRedisTemplate::class.java)
    @Suppress("UNCHECKED_CAST")
    private val values = mock(ValueOperations::class.java) as ValueOperations<String, String>
    private val service = PasswordResetServiceImpl(
        userRepository = userRepository,
        passwordEncoder = passwordEncoder,
        loginTokenAuthentication = loginTokenAuthentication,
        mailSender = mailSender,
        redis = redis,
        properties = PasswordResetProperties(
            storefrontBaseUrl = URI.create("https://shop.pelissa.example"),
            from = "no-reply\u0040pelissa.test",
        ),
        mailUsername = "smtp\u0040pelissa.test",
    )

    init {
        `when`(redis.opsForValue()).thenReturn(values)
    }

    @Test
    fun `unknown email returns without issuing a token or sending mail`() {
        `when`(userRepository.findByEmail("unknown\u0040pelissa.test")).thenReturn(null)

        service.requestReset("  unknown\u0040pelissa.test  ")

        verify(mailSender, never()).send(any(MimeMessage::class.java))
        verify(redis, never()).execute<Long>(any(), any<List<String>>(), any(), any(), any())
    }

    @Test
    fun `known email issues account-bound token and sends reset mail`() {
        val user = User(id = 7, email = "customer\u0040pelissa.test", username = "customer")
        val message = MimeMessage(Session.getInstance(Properties()))
        `when`(userRepository.findByEmail("customer\u0040pelissa.test")).thenReturn(user)
        doReturn(true).`when`(values).setIfAbsent(
            "auth:password-reset:lock:7",
            "1",
            Duration.ofSeconds(60),
        )
        `when`(redis.execute<Long>(any(), any<List<String>>(), any(), any(), any())).thenReturn(1L)
        `when`(mailSender.createMimeMessage()).thenReturn(message)

        service.requestReset("customer\u0040pelissa.test")

        verify(userRepository).findByEmail("customer\u0040pelissa.test")
        verify(values).setIfAbsent(
            "auth:password-reset:lock:7",
            "1",
            Duration.ofSeconds(60),
        )
        verify(mailSender).send(message)
        assertEquals("customer\u0040pelissa.test", message.allRecipients.single().toString())
        assertEquals("PELISSA | Reset your password", message.subject)
    }

    @Test
    fun `valid token updates its bound account and revokes sessions`() {
        val user = User(7, "customer\u0040pelissa.test", "customer", "old-hash")
        `when`(values.get(any())).thenReturn("7")
        `when`(redis.execute<Long>(any(), any<List<String>>(), any())).thenReturn(7L)
        `when`(userRepository.findById(7)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.encode("new-password-123")).thenReturn("new-password-hash")

        service.resetPassword("one-time-reset-token", "new-password-123")

        assertEquals("new-password-hash", user.password)
        verify(userRepository).save(user)
        verify(loginTokenAuthentication).revokeAll(7)
    }

    @Test
    fun `missing or expired token cannot update a password`() {
        `when`(values.get(any())).thenReturn(null)

        assertFailsWith<PasswordResetTokenInvalidException> {
            service.resetPassword("expired-token", "new-password-123")
        }

        verify(userRepository, never()).save(any(User::class.java))
        verify(loginTokenAuthentication, never()).revokeAll(any(Long::class.java))
    }

    @Test
    fun `disabled account cannot consume a valid reset token`() {
        val user = User(7, "customer\u0040pelissa.test", "customer", "old-hash", enabled = false)
        `when`(values.get(any())).thenReturn("7")
        `when`(userRepository.findById(7)).thenReturn(Optional.of(user))

        assertFailsWith<PasswordResetTokenInvalidException> {
            service.resetPassword("one-time-reset-token", "new-password-123")
        }

        verify(redis, never()).execute<Long>(any(), any<List<String>>(), any())
        verify(userRepository, never()).save(any(User::class.java))
        verify(loginTokenAuthentication, never()).revokeAll(any(Long::class.java))
    }
}
