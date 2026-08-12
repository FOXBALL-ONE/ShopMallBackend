package top.foxball.shopmall.service

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/** 首页公共推荐的进程内短期响应缓存；缓存内容为不可变快照，不持有 JPA 实体。 */
@Component
class HomeRecommendationCache {
    private data class RequestKey(
        val channel: HomeRecommendationPlan.Channel,
        val sectionLimit: Int,
        val productLimitPerGroup: Int?,
    )

    private data class Entry(
        val cacheKey: String,
        val value: HomeRecommendationService.ResolvedPlan,
    )

    private val currentResponses = ConcurrentHashMap<RequestKey, Entry>()

    fun getCurrent(
        channel: HomeRecommendationPlan.Channel,
        sectionLimit: Int,
        productLimitPerGroup: Int?,
        currentTime: LocalDateTime,
    ): HomeRecommendationService.ResolvedPlan? {
        val requestKey = RequestKey(channel, sectionLimit, productLimitPerGroup)
        val entry = currentResponses[requestKey] ?: return null
        if (entry.value.expiresAt <= currentTime) {
            currentResponses.remove(requestKey, entry)
            return null
        }
        return entry.value
    }

    fun putCurrent(
        channel: HomeRecommendationPlan.Channel,
        sectionLimit: Int,
        productLimitPerGroup: Int?,
        value: HomeRecommendationService.ResolvedPlan,
    ) {
        val planIdentity = value.planId?.let { "${it}:${value.planVersion}" } ?: "default:0"
        val limitIdentity = productLimitPerGroup?.toString() ?: "default"
        val cacheKey = "home:recommendation:response:${channel.name}:$planIdentity:$sectionLimit:$limitIdentity"
        currentResponses[RequestKey(channel, sectionLimit, productLimitPerGroup)] = Entry(cacheKey, value)
    }

    fun invalidateAll() {
        currentResponses.clear()
    }

    fun invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        invalidateAll()
                    }
                },
            )
        } else {
            invalidateAll()
        }
    }
}
