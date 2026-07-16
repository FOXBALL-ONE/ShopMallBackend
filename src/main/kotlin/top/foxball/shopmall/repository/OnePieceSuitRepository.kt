package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product

interface OnePieceSuitRepository : JpaRepository<OnePieceSuit, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: Product.Status): List<OnePieceSuit>

    fun findByIdAndStatus(id: Long, status: Product.Status): OnePieceSuit?
}
