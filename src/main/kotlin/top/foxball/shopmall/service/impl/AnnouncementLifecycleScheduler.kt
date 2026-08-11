package top.foxball.shopmall.service.impl

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import top.foxball.shopmall.service.AnnouncementService

/** 定期推进已到生效或失效时间的公告状态，查询入口也会同步执行以降低延迟。 */
@Component
class AnnouncementLifecycleScheduler(
    private val announcementService: AnnouncementService,
) {
    @Scheduled(fixedDelayString = "\${shopmall.announcement.lifecycle-delay-ms:60000}")
    fun synchronizeLifecycle() {
        announcementService.synchronizeLifecycle()
    }
}
