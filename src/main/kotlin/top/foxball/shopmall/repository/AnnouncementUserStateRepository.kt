package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState

interface AnnouncementUserStateRepository : JpaRepository<AnnouncementUserState, Long> {
    fun findByAnnouncementIdAndUserId(announcementId: Long, userId: Long): AnnouncementUserState?

    fun findAllByUserIdAndAnnouncementIdIn(
        userId: Long,
        announcementIds: Collection<Long>,
    ): List<AnnouncementUserState>
}
