package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.Tag

interface TagRepository : JpaRepository<Tag, Long> {
    fun findAllByActiveTrueOrderBySortOrderAscNameAsc(): List<Tag>

    fun findByIdAndActiveTrue(id: Long): Tag?

    fun existsByNameAndIdNot(name: String, id: Long): Boolean

    fun existsByName(name: String): Boolean
}
