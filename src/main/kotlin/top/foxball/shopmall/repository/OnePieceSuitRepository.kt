package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.OnePieceSuit

interface OnePieceSuitRepository : JpaRepository<OnePieceSuit, Long> {
    fun findAllByStatusOrderByCreatedAtDesc(status: OnePieceSuit.Status): List<OnePieceSuit>

    fun findByIdAndStatus(id: Long, status: OnePieceSuit.Status): OnePieceSuit?

    fun existsByTags_Id(tagId: Long): Boolean
}
