package top.foxball.shopmall.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

/** 应用启动时按配置清空 Redis（默认关闭），仅用于显式的本地/测试数据重置。 */
@Component
class RedisCleanupConfig(
    private val redisTemplate: RedisTemplate<Any, Any>,
    @Value($$"${redis.clear-on-startup:false}")
    private val clearOnStartup: Boolean
) {

    @EventListener(ApplicationReadyEvent::class)
    fun clearRedisOnStartup() {
        if (!clearOnStartup) {
            println("⚠ 跳过清空Redis（配置未启用）")
            return
        }

        try {
            redisTemplate.connectionFactory?.connection?.serverCommands()?.flushDb()
            println("✓ Redis已清空")
        } catch (e: Exception) {
            System.err.println("✗ 清空Redis失败: ${e.message}")
            e.printStackTrace()
        }
    }
}
