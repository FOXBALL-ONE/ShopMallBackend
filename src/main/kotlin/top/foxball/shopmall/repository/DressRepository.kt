package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.Product

interface DressRepository : JpaRepository<Dress, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<Dress>

    fun findByIdAndStatus(id: Long, status: Product.Status): Dress?
}
