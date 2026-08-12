package top.foxball.shopmall.service.impl

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.service.AdminHomeRecommendationService

/** 定期推进首页推荐方案的自动发布和过期状态。 */
@Component
class HomeRecommendationLifecycleScheduler(
    private val adminHomeRecommendationService: AdminHomeRecommendationService,
) {
    @Scheduled(fixedDelayString = "\${shopmall.home-recommendation.lifecycle-delay-ms:60000}")
    fun synchronizeLifecycle() {
        adminHomeRecommendationService.synchronizeLifecycle()
    }
}
