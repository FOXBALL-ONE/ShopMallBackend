package top.foxball.shopmall.service

/** 通过邮箱签发一次性重置链接，并使用该链接更新对应用户密码。 */
interface PasswordResetService {
    /** 邮箱存在时发送重置邮件；邮箱不存在时同样正常返回，避免泄露账号是否存在。 */
    fun requestReset(email: String)

    /** 消费一次性令牌并重置其绑定用户的密码。 */
    fun resetPassword(token: String, newPassword: String)
}
