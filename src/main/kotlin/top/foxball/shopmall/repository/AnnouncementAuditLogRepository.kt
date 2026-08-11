package top.foxball.shopmall.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.AnnouncementAuditLog

interface AnnouncementAuditLogRepository : JpaRepository<AnnouncementAuditLog, Long> {
    fun findAllByAnnouncementIdOrderByCreatedAtDescIdDesc(announcementId: Long, pageable: Pageable): Page<AnnouncementAuditLog>
}
