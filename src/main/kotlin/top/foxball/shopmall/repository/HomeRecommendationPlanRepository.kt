package top.foxball.shopmall.repository

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import java.time.LocalDateTime

interface HomeRecommendationPlanRepository :
    JpaRepository<HomeRecommendationPlan, Long>,
    JpaSpecificationExecutor<HomeRecommendationPlan> {

    @Query(
        "select p from HomeRecommendationPlan p where p.channel = :channel and p.status = :status " +
            "and p.effectiveFrom <= :now and (p.effectiveUntil is null or p.effectiveUntil > :now) " +
            "order by p.effectiveFrom desc, p.publishedAt desc, p.id desc",
    )
    fun findCurrent(
        @Param("channel") channel: HomeRecommendationPlan.Channel,
        @Param("status") status: HomeRecommendationPlan.Status,
        @Param("now") now: LocalDateTime,
        pageable: Pageable,
    ): List<HomeRecommendationPlan>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from HomeRecommendationPlan p where p.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): HomeRecommendationPlan?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select p from HomeRecommendationPlan p where p.channel = :channel and p.status in :statuses " +
            "order by p.effectiveFrom asc, p.id asc",
    )
    fun findActiveForUpdate(
        @Param("channel") channel: HomeRecommendationPlan.Channel,
        @Param("statuses") statuses: Collection<HomeRecommendationPlan.Status>,
    ): List<HomeRecommendationPlan>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select p from HomeRecommendationPlan p where p.status in :statuses and p.effectiveUntil is not null " +
            "and p.effectiveUntil <= :now order by p.effectiveUntil asc, p.id asc",
    )
    fun findDueForExpiration(
        @Param("statuses") statuses: Collection<HomeRecommendationPlan.Status>,
        @Param("now") now: LocalDateTime,
    ): List<HomeRecommendationPlan>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select p from HomeRecommendationPlan p where p.status = :status and p.effectiveFrom <= :now " +
            "and (p.effectiveUntil is null or p.effectiveUntil > :now) " +
            "order by p.channel asc, p.effectiveFrom desc, p.id desc",
    )
    fun findDueForPublication(
        @Param("status") status: HomeRecommendationPlan.Status,
        @Param("now") now: LocalDateTime,
    ): List<HomeRecommendationPlan>
}
