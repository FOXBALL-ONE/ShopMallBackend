package top.foxball.shopmall.shared

import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Component
import top.foxball.shopmall.config.JwtProperties
import java.time.Duration

/**
 * 刷新令牌存储层（`StringRedisTemplate` + Lua）。
 *
 * 取代旧 `@RedisHash(LoginToken)` 的访问白名单职责：项目默认 `RedisTemplate` 把 key 也 JSON 序列化
 * （见 `config/RedisTemplateConfig.kt`），Lua 无法可靠寻址；故刷新令牌改由 `StringRedisTemplate`
 * 管理的 Redis 结构承载，轮换的 `ACTIVE→USED` 由单条 Lua 原子完成。
 *
 * Redis key 布局（详见 `docs/dual-token-auth-design.md` §3.2）：
 * - `refresh:token:<jti>` HASH：`userId`/`familyId`/`userAgent`/`status`/`replacedBy`/`rotatedAt`，TTL=refresh 剩余有效期。
 * - `refresh:idx:user:<userId>` SET：该用户全部 jti，TTL=refresh TTL。
 * - `refresh:idx:fam:<familyId>` SET：该族全部 jti，TTL=refresh TTL。
 *
 * 写入/清理不变量：
 * - [issueActive]：`HMSET` + `EXPIRE` + 两个索引 `SADD`+`EXPIRE`。
 * - [decideRotation]：跑 [refresh_rotate.lua] 原子判定；只 `HSET` 字段、不发 `EXPIRE`，USED 记录 TTL 不被重置。
 * - [revokeFamily]/[revokeAll]：`SMEMBERS` → 逐个 `DEL refresh:token:<jti>` → `DEL` 索引 SET；返回删除记录数。
 *
 * @param redis Spring Boot 自动装配的 `StringRedisTemplate`（项目仅覆写 `redisTemplate`，未覆写 `stringRedisTemplate`）。
 * @param props JWT 配置，取 `refresh.graceSeconds` 作为 Lua 的 grace 窗口。
 */
@Component
class RefreshTokenStore(
    private val redis: StringRedisTemplate,
    private val props: JwtProperties,
) {

    /**
     * 签发：`HMSET refresh:token:<jti> ... status=ACTIVE` + `EXPIRE <ttl>` + `SADD` 两个索引 + 各 `EXPIRE <ttl>`。
     *
     * @param jti 刷新令牌唯一标识（即 Redis 主键尾部）。
     * @param userId 用户 ID，落 HASH 字段 `userId` 并入用户索引 SET。
     * @param familyId 会话族 ID，落 HASH 字段 `familyId` 并入族索引 SET。
     * @param userAgent 签发时请求 UA，用于后续跨 UA 复用门控。
     * @param ttlSeconds 该 refresh 的有效期秒数；token 与两个索引 SET 共用此 TTL。
     */
    fun issueActive(jti: String, userId: Long, familyId: String, userAgent: String, ttlSeconds: Long) {
        val tokenKey = tokenKey(jti)
        val userKey = userIdxKey(userId)
        val famKey = famIdxKey(familyId)
        // 下限 1s 兜底（设计 §3.2 不变量）：EXPIRE 0 等价 DEL，会令刚签发的记录瞬间消失
        val ttl = ttlSeconds.coerceAtLeast(1L)

        redis.opsForHash<String, String>().putAll(
            tokenKey,
            mapOf(
                "userId" to userId.toString(),
                "familyId" to familyId,
                "userAgent" to userAgent,
                "status" to "ACTIVE",
            ),
        )
        redis.expire(tokenKey, Duration.ofSeconds(ttl))

        redis.opsForSet().add(userKey, jti)
        redis.expire(userKey, Duration.ofSeconds(ttl))

        redis.opsForSet().add(famKey, jti)
        redis.expire(famKey, Duration.ofSeconds(ttl))
    }

    /**
     * 跑 [refresh_rotate.lua] 原子轮换判定。
     *
     * KEYS=`[refresh:token:<jti>]`，ARGV=`[nowEpoch, graceSeconds, newJti]`。
     * 返回 `List<*>`，首元素为 verdict 字符串（`unknown`/`rotate`/`grace`/`reuse`）：
     * - `rotate` 第二元素为 pttl 字符串 → [RotationVerdict.Rotate]；
     * - `grace` 第二元素为 `replacedBy` → [RotationVerdict.Grace]。
     *
     * @param jti 待轮换的刷新令牌 jti。
     * @param newJti 预生成的新 jti，Lua 原子写入 `replacedBy`。
     * @param nowEpoch 当前 epoch 秒。
     */
    fun decideRotation(jti: String, newJti: String, nowEpoch: Long): RotationVerdict {
        val result = redis.execute(
            rotateScript,
            listOf(tokenKey(jti)),
            nowEpoch.toString(),
            props.refresh.graceSeconds.toString(),
            newJti,
        )
        if (result.isNullOrEmpty()) return RotationVerdict.Unknown

        val verdict = result[0]?.toString() ?: return RotationVerdict.Unknown
        return when (verdict) {
            "rotate" -> {
                val pttl = result.getOrNull(1)?.toString()?.takeIf { it.isNotBlank() }
                val millis = pttl?.toLongOrNull() ?: -1L
                RotationVerdict.Rotate(millis)
            }
            "grace" -> {
                val replacedBy = result.getOrNull(1)?.toString() ?: ""
                RotationVerdict.Grace(replacedBy)
            }
            "reuse" -> RotationVerdict.Reuse
            else -> RotationVerdict.Unknown
        }
    }

    /**
     * 读 HASH（grace 找尖端 / 一致性校验用）；不存在返回 null。
     *
     * @param jti 刷新令牌 jti。
     * @return HASH 全字段映射；key 不存在返回 `null`。
     */
    fun loadHash(jti: String): Map<String, String>? {
        val entries = redis.opsForHash<String, String>().entries(tokenKey(jti))
        return entries.takeIf { it.isNotEmpty() }
    }

    /** 读 HASH 的 `userId` 字段；记录不存在或字段缺失返回 null（交叉清理索引时容忍陈旧成员）。 */
    private fun storeUserId(jti: String): Long? =
        redis.opsForHash<String, String>().get(tokenKey(jti), "userId")?.toLongOrNull()

    /** 读 HASH 的 `familyId` 字段；记录不存在或字段缺失返回 null。 */
    private fun storeFamilyId(jti: String): String? =
        redis.opsForHash<String, String>().get(tokenKey(jti), "familyId")

    /**
     * 撤销单张刷新令牌（登出用）：`DEL refresh:token:<jti>` + `SREM` 两个索引 SET。
     *
     * 只删当前记录，不动同族其他记录（区别于 [revokeFamily] 的整族撤销）。登出语义见设计 §4.3。
     *
     * @param jti 待撤销的刷新令牌 jti。
     * @param userId 所属用户，用于从用户索引移除该 jti。
     * @param familyId 所属会话族，用于从族索引移除该 jti。
     * @return 是否确实删除了 token 记录（false 表示记录已不存在/已过期，幂等）。
     */
    fun revokeOne(jti: String, userId: Long, familyId: String): Boolean {
        val deleted = redis.delete(tokenKey(jti))
        redis.opsForSet().remove(userIdxKey(userId), jti)
        redis.opsForSet().remove(famIdxKey(familyId), jti)
        return deleted
    }

    /**
     * 撤销整族：`SMEMBERS refresh:idx:fam:<famId>` → 逐个 `DEL refresh:token:<jti>` +
     * `SREM` 用户索引 → `DEL` 族索引。
     *
     * 交叉清理用户索引（`refresh:idx:user:<userId>`），避免陈旧成员最长残留一个 refresh TTL。
     * 索引可能含已过期陈旧成员：`DEL` 不存在的 key 幂等，`SREM` 不存在的成员也幂等。
     *
     * @param familyId 会话族 ID。
     * @return 删除的 token 记录数。
     */
    fun revokeFamily(familyId: String): Int {
        val famKey = famIdxKey(familyId)
        val jtis = redis.opsForSet().members(famKey) ?: emptySet()
        jtis.forEach { jti ->
            // 先读 userId 再 DEL token key：DEL 之后 HASH 字段已清空，storeUserId 会返回 null，
            // 导致用户索引里的该 jti 不被交叉清理。故必须在 DEL 之前取 userId。
            val userId = storeUserId(jti)
            redis.delete(tokenKey(jti))
            userId?.let { redis.opsForSet().remove(userIdxKey(it), jti) }
        }
        redis.delete(famKey)
        return jtis.size
    }

    /**
     * 撤销某用户全部：`SMEMBERS refresh:idx:user:<userId>` → 逐个 `DEL refresh:token:<jti>` +
     * `SREM` 族索引 → `DEL` 用户索引。
     *
     * 交叉清理族索引，避免陈旧成员残留。
     *
     * @param userId 用户 ID。
     * @return 删除的 token 记录数。
     */
    fun revokeAll(userId: Long): Int {
        val userKey = userIdxKey(userId)
        val jtis = redis.opsForSet().members(userKey) ?: emptySet()
        jtis.forEach { jti ->
            // 先读 familyId 再 DEL token key：DEL 之后 HASH 字段已清空，storeFamilyId 会返回 null，
            // 导致族索引里的该 jti 不被交叉清理。故必须在 DEL 之前取 familyId。
            val famId = storeFamilyId(jti)
            redis.delete(tokenKey(jti))
            famId?.let { redis.opsForSet().remove(famIdxKey(it), jti) }
        }
        redis.delete(userKey)
        return jtis.size
    }

    /** 原子轮换判定结果（§3.2）。 */
    sealed interface RotationVerdict {
        /** 无记录：token 已过期或从未签发 → 401。 */
        data object Unknown : RotationVerdict
        /** ACTIVE 被本请求原子翻成 USED，返回剩余 TTL 毫秒 → 继续签发新令牌。 */
        data class Rotate(val remainingTtlMillis: Long) : RotationVerdict
        /** USED 在 grace 窗口内再现，合法重试：沿 [replacedBy] 续换尖端。 */
        data class Grace(val replacedBy: String) : RotationVerdict
        /** 超窗口 / 被盗 → 撤销整族 → 401。 */
        data object Reuse : RotationVerdict
    }

    private fun tokenKey(jti: String) = "${TOKEN_PREFIX}$jti"
    private fun userIdxKey(userId: Long) = "${USER_IDX_PREFIX}$userId"
    private fun famIdxKey(familyId: String) = "${FAM_IDX_PREFIX}$familyId"

    private companion object {
        private const val TOKEN_PREFIX = "refresh:token:"
        private const val USER_IDX_PREFIX = "refresh:idx:user:"
        private const val FAM_IDX_PREFIX = "refresh:idx:fam:"

        private val SCRIPT_PATH = "META-INF/scripts/refresh_rotate.lua"

        /**
         * 原子轮换 Lua 脚本：从 classpath `META-INF/scripts/refresh_rotate.lua` 加载。
         * `resultType = List` 以解析多元素返回 `{verdict[, payload]}`。
         */
        @Suppress("UNCHECKED_CAST")
        private val rotateScript: DefaultRedisScript<List<*>> by lazy {
            DefaultRedisScript<List<*>>().apply {
                setLocation(ClassPathResource(SCRIPT_PATH))
                resultType = List::class.java
            }
        }
    }
}
