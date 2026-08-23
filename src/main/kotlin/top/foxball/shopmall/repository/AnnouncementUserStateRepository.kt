package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.AnnouncementUserState

interface AnnouncementUserStateRepository : JpaRepository<AnnouncementUserState, Long> {
    @Modifying
    @Query("delete from AnnouncementUserState s where s.announcementId in :announcementIds")
    fun deleteAllByAnnouncementIdIn(@Param("announcementIds") announcementIds: Collection<Long>): Int

    @Modifying
    @Query("delete from AnnouncementUserState s where s.userId in :userIds")
    fun deleteAllByUserIdIn(@Param("userIds") userIds: Collection<Long>): Int

    fun findByAnnouncementIdAndUserId(announcementId: Long, userId: Long): AnnouncementUserState?

    fun findAllByUserIdAndAnnouncementIdIn(
        userId: Long,
        announcementIds: Collection<Long>,
    ): List<AnnouncementUserState>
}
