package top.foxball.shopmall.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.data.redis.connection.RedisClusterConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisConnectionUtils
import org.springframework.stereotype.Component
import java.util.Properties

@Component("redisHealthIndicator")
class DetailedRedisHealthIndicator(
    private val redisConnectionFactory: RedisConnectionFactory,
    @Value($$"${spring.data.redis.database:0}")
    private val database: Int,
) : AbstractHealthIndicator("Redis health check failed") {
    override fun doHealthCheck(builder: Health.Builder) {
        val connection = RedisConnectionUtils.getConnection(redisConnectionFactory)
        try {
            if (connection is RedisClusterConnection) {
                val cluster = connection.clusterGetClusterInfo()
                builder.withDetails(
                    linkedMapOf(
                        "mode" to "cluster",
                        "role" to "cluster",
                        "cluster_size" to cluster.clusterSize,
                        "known_nodes" to cluster.knownNodes,
                        "slots_up" to cluster.slotsOk,
                        "slots_fail" to cluster.slotsFail,
                    ).filterValues { it != null },
                )
                if (cluster.state.equals("fail", ignoreCase = true)) builder.down() else builder.up()
                return
            }

            val commands = connection.serverCommands()
            val info = commands.info()
            val maxClients = info.longValue("maxclients") ?: runCatching {
                commands.getConfig("maxclients").longValue("maxclients")
            }.getOrNull()
            val keyspace = info.getProperty("db$database")
                ?.split(',')
                ?.mapNotNull { field ->
                    val separator = field.indexOf('=')
                    if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
                }
                ?.toMap()
                .orEmpty()

            builder.withDetails(
                linkedMapOf(
                    "version" to info.getProperty("redis_version"),
                    "mode" to info.getProperty("redis_mode"),
                    "role" to info.getProperty("role"),
                    "database" to database,
                    "key_count" to commands.dbSize(),
                    "expiring_key_count" to keyspace["expires"]?.toLongOrNull(),
                    "average_ttl_ms" to keyspace["avg_ttl"]?.toLongOrNull(),
                    "uptime_seconds" to info.longValue("uptime_in_seconds"),
                    "used_memory_bytes" to info.longValue("used_memory"),
                    "peak_memory_bytes" to info.longValue("used_memory_peak"),
                    "max_memory_bytes" to info.longValue("maxmemory"),
                    "connected_clients" to info.longValue("connected_clients"),
                    "max_clients" to maxClients,
                    "blocked_clients" to info.longValue("blocked_clients"),
                    "total_commands_processed" to info.longValue("total_commands_processed"),
                    "operations_per_second" to info.longValue("instantaneous_ops_per_sec"),
                    "keyspace_hits" to info.longValue("keyspace_hits"),
                    "keyspace_misses" to info.longValue("keyspace_misses"),
                    "evicted_keys" to info.longValue("evicted_keys"),
                ).filterValues { it != null },
            ).up()
        } finally {
            RedisConnectionUtils.releaseConnection(connection, redisConnectionFactory)
        }
    }

    private fun Properties.longValue(name: String): Long? = getProperty(name)?.toLongOrNull()
}
