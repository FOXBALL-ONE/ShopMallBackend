package top.foxball.shopmall.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage

interface SupportTicketMessageRepository : JpaRepository<SupportTicketMessage, Long> {
    fun findAllByTicket_IdOrderByCreatedAtDescIdDesc(
        ticketId: Long,
        pageable: Pageable,
    ): Page<SupportTicketMessage>
    fun findByIdAndTicket_Id(id: Long, ticketId: Long): SupportTicketMessage?
}

