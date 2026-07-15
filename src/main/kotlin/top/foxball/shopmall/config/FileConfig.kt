package top.foxball.shopmall.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import top.foxball.shopmall.service.FileLinkSigner

/** 装配文件模块配置，并创建用于签发与校验下载链接的 HMAC 签名器。 */
@Configuration
@EnableConfigurationProperties(FileProperties::class)
class FileConfig {
    /** 签名器不依赖 Spring 状态，密钥仅从受配置管理的属性中读取。 */
    @Bean
    fun fileLinkSigner(properties: FileProperties): FileLinkSigner =
        FileLinkSigner(properties.signingSecret)
}
