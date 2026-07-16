package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.Product

interface BikiniSuitRepository : JpaRepository<BikiniSuit, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<BikiniSuit>

    fun findByIdAndStatus(id: Long, status: Product.Status): BikiniSuit?
}
