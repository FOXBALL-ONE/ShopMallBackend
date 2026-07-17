package top.foxball.shopmall.service

/**
 * 邮箱验证码服务：生成 6 位随机数字验证码，与请求 User-Agent、用户 ID 一并存入 Redis，
 * 并通过邮件发送。注册场景 [userId] 传 `null`，已登录场景（如修改密码）传当前用户 ID。
 *
 * 验证时验证码、User-Agent、用户 ID（含 `null`）三者必须同时匹配，任一不符即视为无效，
 * 以避免验证码跨场景或跨客户端重放。有效期与发送频率由
 * [top.foxball.shopmall.config.MailProperties] 控制。
 */
interface MailService {
    /**
     * 生成并向 [email] 发送验证码；按 [userAgent] 与 [userId] 绑定后存入 Redis。
     *
     * 受发送频率限制：同一邮箱的发送间隔与每日上限、同一 [ip] 的每小时上限任一超限即抛
     * [top.foxball.shopmall.handler.VerificationCodeRateLimitException]；
     * 邮件投递失败抛 [top.foxball.shopmall.handler.EmailSendFailedException]。
     */
    fun sendCode(email: String, userAgent: String, userId: Long?, ip: String)

    /**
     * 校验 [email] 的验证码：验证码、[userAgent]、[userId] 必须与签发时完全一致。
     * 通过后立即删除该验证码（一次性使用）。任一不匹配或已过期抛
     * [top.foxball.shopmall.handler.VerificationCodeInvalidException]；
     * 校验失败次数达上限抛 [top.foxball.shopmall.handler.VerificationCodeRateLimitException]。
     */
    fun verifyCode(email: String, code: String, userAgent: String, userId: Long?)
}
