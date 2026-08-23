package top.foxball.shopmall.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage

interface SupportTicketMessageRepository : JpaRepository<SupportTicketMessage, Long> {
    @Query("select m.id from SupportTicketMessage m where m.ticket.id in :ticketIds order by m.id")
    fun findIdsByTicketIdIn(@Param("ticketIds") ticketIds: Collection<Long>): List<Long>

    @Query("select m.id from SupportTicketMessage m where m.senderId in :senderIds order by m.id")
    fun findIdsBySenderIdIn(@Param("senderIds") senderIds: Collection<Long>): List<Long>

    @Modifying(flushAutomatically = true)
    @Query("delete from SupportTicketMessage m where m.id in :messageIds")
    fun deleteAllByIdIn(@Param("messageIds") messageIds: Collection<Long>): Int

    fun findAllByTicket_IdOrderByCreatedAtDescIdDesc(
        ticketId: Long,
        pageable: Pageable,
    ): Page<SupportTicketMessage>
    fun findByIdAndTicket_Id(id: Long, ticketId: Long): SupportTicketMessage?
}
