package top.foxball.shopmall.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/** 密码重置邮件及一次性链接配置。 */
@ConfigurationProperties(prefix = "shopmall.mail.password-reset")
data class PasswordResetProperties(
    val ttlSeconds: Long = 300L,
    val sendIntervalSeconds: Long = 60L,
    val storefrontBaseUrl: URI = URI.create("http://localhost:8088"),
    val from: String = "",
    val subjectPrefix: String = "PELISSA",
) {
    init {
        require(ttlSeconds == 300L) { "Password reset links must expire after exactly 300 seconds" }
        require(sendIntervalSeconds > 0) { "Password reset send interval must be positive" }
        require(storefrontBaseUrl.isAbsolute && storefrontBaseUrl.host != null) {
            "Password reset storefront base URL must be an absolute URL with a host"
        }
        require(storefrontBaseUrl.rawQuery == null && storefrontBaseUrl.rawFragment == null) {
            "Password reset storefront base URL must not contain a query or fragment"
        }
        require(
            storefrontBaseUrl.scheme == "https" ||
                (storefrontBaseUrl.scheme == "http" && storefrontBaseUrl.host == "localhost"),
        ) {
            "Password reset storefront base URL must use HTTPS, except for localhost development"
        }
    }
}
