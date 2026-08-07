package top.foxball.shopmall.service.impl

import java.time.LocalDateTime
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.authentication.LoginTokenAuthentication
import top.foxball.shopmall.entity.jdbc.Status
import top.foxball.shopmall.handler.UserDisabledException
import top.foxball.shopmall.handler.UsernameOrPasswordErrorException
import top.foxball.shopmall.repository.UserRepository
import top.foxball.shopmall.service.AuthService
import top.foxball.shopmall.service.MailService

/** 实现登录凭据校验、最近登录信息记录和密码变更后的会话撤销。 */
@Service
class AuthServiceImpl(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val loginTokenAuthentication: LoginTokenAuthentication,
    private val mailService: MailService,
) : AuthService {

    override fun login(
        identifier: String,
        password: String,
        userAgent: String,
        clientIp: String,
    ): LoginTokenAuthentication.LoginResult {
        // 含 @ 视为邮箱登录，否则按用户名登录
        val isEmail = identifier.contains('@')
        // 标识不存在与密码错误统一回相同消息，避免账号枚举
        val user = (if (isEmail) userRepository.findByEmail(identifier)
                    else userRepository.findByUsername(identifier))
            ?: throw UsernameOrPasswordErrorException()
        // 邮箱登录要求邮箱已验证；未验证同样回统一消息，避免泄露"邮箱已注册但未验证"
        if (isEmail && !user.emailVerified) throw UsernameOrPasswordErrorException()
        if (!user.enabled || user.status != Status.ACTIVE) throw UserDisabledException()
        if (!passwordEncoder.matches(password, user.password)) {
            throw UsernameOrPasswordErrorException()
        }
        // 登录成功：先落库最近登录时间/IP，再签发令牌，避免签发后落库失败产生孤立令牌
        user.lastLoginAt = LocalDateTime.now()
        user.lastLoginIp = clientIp
        userRepository.save(user)
        return loginTokenAuthentication.login(user, userAgent)
    }

    @Transactional
    override fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        verificationCode: String,
        userAgent: String,
    ) {
        val user = userRepository.findById(userId).orElse(null)
            ?: throw UsernameOrPasswordErrorException()
        // 先校验验证码：未通过即拒绝，避免后续密码比对成为"密码是否正确"的探测口
        mailService.verifyCode(user.email, verificationCode, userAgent, userId)
        if (!passwordEncoder.matches(currentPassword, user.password)) {
            throw UsernameOrPasswordErrorException()
        }
        if (!user.enabled || user.status != Status.ACTIVE) throw UserDisabledException()

        user.password = requireNotNull(passwordEncoder.encode(newPassword)) { "密码编码失败" }
        userRepository.save(user)
        loginTokenAuthentication.revokeAll(userId)
    }
}
