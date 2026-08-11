package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.Announcement
import java.time.LocalDateTime

interface AnnouncementRepository : JpaRepository<Announcement, Long>, JpaSpecificationExecutor<Announcement> {
    @Query(
        "select a from Announcement a where a.channel = :channel and a.status in :statuses " +
            "and a.effectiveFrom <= :now and (a.effectiveUntil is null or a.effectiveUntil > :now) " +
            "order by a.priority desc, a.effectiveFrom desc, a.publishedAt desc, a.id desc",
    )
    fun findCurrent(
        @Param("channel") channel: Announcement.Channel,
        @Param("statuses") statuses: Collection<Announcement.Status>,
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<Announcement>

    @Query(
        "select a from Announcement a where a.channel = :channel and a.status in :statuses " +
            "and a.effectiveFrom <= :now and (a.effectiveUntil is null or a.effectiveUntil > :now) " +
            "and not exists (select s.id from AnnouncementUserState s " +
            "where s.announcementId = a.id and s.userId = :userId) " +
            "order by a.priority desc, a.effectiveFrom desc, a.publishedAt desc, a.id desc",
    )
    fun findCurrentUnreadForUser(
        @Param("channel") channel: Announcement.Channel,
        @Param("statuses") statuses: Collection<Announcement.Status>,
        @Param("now") now: LocalDateTime,
        @Param("userId") userId: Long,
        pageable: Pageable,
    ): List<Announcement>

    @Query(
        "select a from Announcement a where a.channel = :channel and a.autoShowEnabled = true " +
            "and a.status in :statuses and a.effectiveFrom <= :now " +
            "and (a.effectiveUntil is null or a.effectiveUntil > :now) " +
            "order by a.priority desc, " +
            "case when a.type in :preferredTypes then 1 else 0 end desc, " +
            "a.effectiveFrom desc, a.publishedAt desc, a.id desc",
    )
    fun findAutoShowCandidates(
        @Param("channel") channel: Announcement.Channel,
        @Param("statuses") statuses: Collection<Announcement.Status>,
        @Param("preferredTypes") preferredTypes: Collection<Announcement.Type>,
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<Announcement>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select a from Announcement a where a.status in :activeStatuses " +
            "and a.effectiveUntil is not null and a.effectiveUntil <= :now order by a.id asc",
    )
    fun findDueForExpiration(
        @Param("now") now: LocalDateTime,
        @Param("activeStatuses") activeStatuses: Collection<Announcement.Status>,
    ): List<Announcement>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select a from Announcement a where a.status = :scheduled and a.effectiveFrom <= :now " +
            "and (a.effectiveUntil is null or a.effectiveUntil > :now) order by a.id asc",
    )
    fun findDueForPublication(
        @Param("now") now: LocalDateTime,
        @Param("scheduled") scheduled: Announcement.Status,
    ): List<Announcement>
}
