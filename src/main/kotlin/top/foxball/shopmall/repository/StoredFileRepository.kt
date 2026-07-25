package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import top.foxball.shopmall.entity.jdbc.StoredFile
import java.util.UUID

/** 文件元数据查询；所有按 ID 读取的方法均附带 owner 条件，供服务层执行授权。 */
interface StoredFileRepository : JpaRepository<StoredFile, UUID> {
    fun findAllByOwnerIdOrderByCreatedAtDesc(ownerId: Long, pageable: Pageable): Page<StoredFile>

    fun findAllByIdInAndOwnerId(ids: Collection<UUID>, ownerId: Long): List<StoredFile>

    fun findByIdAndOwnerId(id: UUID, ownerId: Long): StoredFile?
}
