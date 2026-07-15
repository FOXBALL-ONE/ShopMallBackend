package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.BikiniSuit

interface BikiniSuitRepository : JpaRepository<BikiniSuit, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: BikiniSuit.Status): List<BikiniSuit>

    fun findByIdAndStatus(id: Long, status: BikiniSuit.Status): BikiniSuit?

    fun existsByTags_Id(tagId: Long): Boolean
}
