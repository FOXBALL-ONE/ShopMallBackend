package top.foxball.shopmall.service

import top.foxball.shopmall.authentication.LoginTokenAuthentication

/**
 * 认证编排：校验凭据并签发会话令牌。
 *
 * 凭据比对 / 用户态校验在此完成；令牌签发与白名单管理委托 [LoginTokenAuthentication]。
 */
interface AuthService {

    /**
     * 按用户名或邮箱 + 密码登录，返回登录结果（含令牌与用户信息）。
     *
     * [identifier] 含 `@` 视为邮箱登录（要求该邮箱已通过验证），否则按用户名登录。
     * [clientIp] 用于记录最近登录 IP，应取反代/CDN 透传的真实客户端地址。
     */
    fun login(identifier: String, password: String, userAgent: String, clientIp: String): LoginTokenAuthentication.LoginResult

    /**
     * 校验当前密码并更新为新密码；成功后撤销该用户的全部登录会话。
     *
     * 除当前密码外，还需通过 [verificationCode] 完成邮箱验证：验证码须绑定到当前 [userId] 与发起请求的
     * [userAgent]（由 [MailService] 校验），作为修改密码的额外因子。验证码先于密码校验，
     * 以免密码比对成为"密码是否正确"的探测口。
     */
    fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        verificationCode: String,
        userAgent: String,
    )
}
