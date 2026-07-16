package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Product

interface CoverUpRepository : JpaRepository<CoverUp, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<CoverUp>

    fun findByIdAndStatus(id: Long, status: Product.Status): CoverUp?
}
