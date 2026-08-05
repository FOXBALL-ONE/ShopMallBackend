package top.foxball.shopmall.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageAttachment

interface SupportTicketMessageAttachmentRepository : JpaRepository<SupportTicketMessageAttachment, Long> {
    @Query(
        "select a from SupportTicketMessageAttachment a " +
            "join fetch a.file where a.message.id in :messageIds " +
            "order by a.createdAt asc, a.id asc",
    )
    fun findAllWithFileByMessageIds(
        @Param("messageIds") messageIds: Collection<Long>,
    ): List<SupportTicketMessageAttachment>
    @Query(
        "select count(a) from SupportTicketMessageAttachment a " +
            "where a.message.ticket.id = :ticketId",
    )
    fun countForTicket(@Param("ticketId") ticketId: Long): Long

    @Query(
        "select coalesce(sum(a.file.sizeBytes), 0) from SupportTicketMessageAttachment a " +
            "where a.message.ticket.id = :ticketId",
    )
    fun totalBytesForTicket(@Param("ticketId") ticketId: Long): Long

    @Query(
        "select count(a) from SupportTicketMessageAttachment a " +
            "where a.message.senderId = :senderId and a.message.senderType = :senderType",
    )
    fun countForSender(
        @Param("senderId") senderId: Long,
        @Param("senderType") senderType: top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender,
    ): Long

    @Query(
        "select coalesce(sum(a.file.sizeBytes), 0) from SupportTicketMessageAttachment a " +
            "where a.message.senderId = :senderId and a.message.senderType = :senderType",
    )
    fun totalBytesForSender(
        @Param("senderId") senderId: Long,
        @Param("senderType") senderType: top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender,
    ): Long
}

