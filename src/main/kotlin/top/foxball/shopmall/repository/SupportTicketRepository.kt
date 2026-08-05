package top.foxball.shopmall.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketPriority
import top.foxball.shopmall.entity.jdbc.SupportTicketStatus

interface SupportTicketRepository : JpaRepository<SupportTicket, Long> {
    @EntityGraph(attributePaths = ["order"])
    fun findByIdAndCustomerId(id: Long, customerId: Long): SupportTicket?

    @Query(
        "select t from SupportTicket t where t.customerId = :customerId and " +
            "(:status is null or t.status = :status) and " +
            "(:serviceType is null or t.serviceType = :serviceType) and " +
            "(:priority is null or t.priority = :priority) " +
            "order by t.updatedAt desc, t.id desc",
    )
    @EntityGraph(attributePaths = ["order"])
    fun findAllForCustomer(
        @Param("customerId") customerId: Long,
        @Param("status") status: SupportTicketStatus?,
        @Param("serviceType") serviceType: SupportServiceType?,
        @Param("priority") priority: SupportTicketPriority?,
        pageable: Pageable,
    ): Page<SupportTicket>

    @Query(
        "select t from SupportTicket t left join t.order o where " +
            "(:status is null or t.status = :status) and " +
            "(:serviceType is null or t.serviceType = :serviceType) and " +
            "(:priority is null or t.priority = :priority) and " +
            "(:customerId is null or t.customerId = :customerId) and " +
            "(:orderNo is null or o.orderNo = :orderNo) " +
            "order by t.updatedAt desc, t.id desc",
    )
    @EntityGraph(attributePaths = ["order"])
    fun findAllForAdmin(
        @Param("status") status: SupportTicketStatus?,
        @Param("serviceType") serviceType: SupportServiceType?,
        @Param("priority") priority: SupportTicketPriority?,
        @Param("customerId") customerId: Long?,
        @Param("orderNo") orderNo: String?,
        pageable: Pageable,
    ): Page<SupportTicket>
}


