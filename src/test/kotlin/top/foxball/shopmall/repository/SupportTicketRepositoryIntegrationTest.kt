package top.foxball.shopmall.repository

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.OrderEntity
import top.foxball.shopmall.entity.jdbc.OrderShippingAddress
import top.foxball.shopmall.entity.jdbc.StoredFile
import top.foxball.shopmall.entity.jdbc.SupportServiceType
import top.foxball.shopmall.entity.jdbc.SupportTicket
import top.foxball.shopmall.entity.jdbc.SupportTicketMessage
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageAttachment
import top.foxball.shopmall.entity.jdbc.SupportTicketMessageSender
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SupportTicketRepositoryIntegrationTest {
    @Autowired
    private lateinit var supportTicketRepository: SupportTicketRepository

    @Autowired
    private lateinit var supportTicketMessageRepository: SupportTicketMessageRepository

    @Autowired
    private lateinit var supportTicketMessageAttachmentRepository: SupportTicketMessageAttachmentRepository

    @Autowired
    private lateinit var orderRepository: OrderRepository

    @Autowired
    private lateinit var entityManager: jakarta.persistence.EntityManager

    @Test
    fun `admin query includes tickets without orders and filters linked orders`() {
        val order = orderRepository.save(
            OrderEntity(
                orderNo = "ORD-SUPPORT-1",
                customerId = 7,
                shippingAddress = OrderShippingAddress(
                    name = "Test Customer",
                    phone = "+14155550123",
                    country = "US",
                    city = "Seattle",
                    address1 = "1 Main Street",
                ),
            ),
        )
        supportTicketRepository.save(
            SupportTicket(
                customerId = 7,
                serviceType = SupportServiceType.PRE_SALES,
                subject = "Question",
                content = "Can I change the color?",
            ),
        )
        supportTicketRepository.save(
            SupportTicket(
                customerId = 7,
                serviceType = SupportServiceType.AFTER_SALES,
                order = order,
                subject = "Order issue",
                content = "The package is delayed.",
            ),
        )
        supportTicketRepository.flush()

        val all = supportTicketRepository.findAllForAdmin(
            status = null,
            serviceType = null,
            priority = null,
            customerId = null,
            orderNo = null,
            pageable = PageRequest.of(0, 25),
        )
        val linked = supportTicketRepository.findAllForAdmin(
            status = null,
            serviceType = null,
            priority = null,
            customerId = null,
            orderNo = "ORD-SUPPORT-1",
            pageable = PageRequest.of(0, 25),
        )

        assertEquals(2, all.totalElements)
        assertEquals(2, all.content.size)
        assertEquals(1, linked.totalElements)
        assertEquals(SupportServiceType.AFTER_SALES, linked.content.single().serviceType)
    }

    @Test
    fun `ticket lists are ordered by latest activity rather than creation time`() {
        val first = supportTicketRepository.saveAndFlush(
            SupportTicket(
                customerId = 7,
                subject = "First ticket",
                content = "Created first but updated last.",
            ),
        )
        val second = supportTicketRepository.saveAndFlush(
            SupportTicket(
                customerId = 7,
                subject = "Second ticket",
                content = "Created second.",
            ),
        )
        entityManager.createNativeQuery("update support_tickets set updated_at = :updatedAt where id = :id")
            .setParameter("updatedAt", Timestamp.from(Instant.parse("2026-08-04T12:00:00Z")))
            .setParameter("id", requireNotNull(first.id))
            .executeUpdate()
        entityManager.createNativeQuery("update support_tickets set updated_at = :updatedAt where id = :id")
            .setParameter("updatedAt", Timestamp.from(Instant.parse("2026-08-04T11:00:00Z")))
            .setParameter("id", requireNotNull(second.id))
            .executeUpdate()
        entityManager.clear()

        val customerPage = supportTicketRepository.findAllForCustomer(
            customerId = 7,
            status = null,
            serviceType = null,
            priority = null,
            pageable = PageRequest.of(0, 25),
        )
        val adminPage = supportTicketRepository.findAllForAdmin(
            status = null,
            serviceType = null,
            priority = null,
            customerId = null,
            orderNo = null,
            pageable = PageRequest.of(0, 25),
        )

        assertEquals(first.id, customerPage.content.first().id)
        assertEquals(first.id, adminPage.content.first().id)
    }

    @Test
    fun `ticket queries eagerly load linked order for response mapping`() {
        val order = orderRepository.save(
            OrderEntity(
                orderNo = "ORD-SUPPORT-GRAPH",
                customerId = 7,
                shippingAddress = OrderShippingAddress(
                    name = "Test Customer",
                    phone = "+14155550123",
                    country = "US",
                    city = "Seattle",
                    address1 = "1 Main Street",
                ),
            ),
        )
        val ticket = supportTicketRepository.saveAndFlush(
            SupportTicket(
                customerId = 7,
                serviceType = SupportServiceType.AFTER_SALES,
                order = order,
                subject = "Linked order",
                content = "Verify eager loading.",
            ),
        )
        val ticketId = requireNotNull(ticket.id)
        val persistenceUnitUtil = entityManager.entityManagerFactory.persistenceUnitUtil
        entityManager.clear()

        val detail = requireNotNull(supportTicketRepository.findByIdAndCustomerId(ticketId, 7))
        assertTrue(persistenceUnitUtil.isLoaded(detail, "order"))
        entityManager.clear()

        val customerListItem = supportTicketRepository.findAllForCustomer(
            customerId = 7,
            status = null,
            serviceType = null,
            priority = null,
            pageable = PageRequest.of(0, 25),
        ).content.single()
        assertTrue(persistenceUnitUtil.isLoaded(customerListItem, "order"))
        entityManager.clear()

        val adminListItem = supportTicketRepository.findAllForAdmin(
            status = null,
            serviceType = null,
            priority = null,
            customerId = null,
            orderNo = null,
            pageable = PageRequest.of(0, 25),
        ).content.single()
        assertTrue(persistenceUnitUtil.isLoaded(adminListItem, "order"))
    }

    @Test
    fun `message query returns newest page first and persists file associations`() {
        val ticket = supportTicketRepository.save(
            SupportTicket(
                customerId = 7,
                serviceType = SupportServiceType.PRE_SALES,
                subject = "Question",
                content = "Can I change the color?",
            ),
        )
        val file = storedFile(ownerId = 7, name = "reference.png", sizeBytes = 20)
        val firstMessage = SupportTicketMessage(
            ticket = ticket,
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = "Here is a reference image.",
        )
        firstMessage.attachments += SupportTicketMessageAttachment(message = firstMessage, file = file)
        entityManager.persist(file)
        entityManager.persist(firstMessage)
        entityManager.flush()

        val latestMessage = SupportTicketMessage(
            ticket = ticket,
            senderId = 99,
            senderType = SupportTicketMessageSender.ADMIN,
            content = "We received the image.",
        )
        entityManager.persist(latestMessage)
        entityManager.flush()
        entityManager.clear()

        val messages = supportTicketMessageRepository
            .findAllByTicket_IdOrderByCreatedAtDescIdDesc(requireNotNull(ticket.id), PageRequest.of(0, 25))

        assertEquals(2, messages.totalElements)
        assertEquals(latestMessage.id, messages.content.first().id)
        val persistedFirst = messages.content.single { it.id == firstMessage.id }
        assertEquals(1, persistedFirst.attachments.size)
        assertEquals("reference.png", persistedFirst.attachments.single().file?.originalFilename)
    }

    @Test
    fun `attachment aggregate queries enforce ticket and customer quotas`() {
        val ticket = supportTicketRepository.save(
            SupportTicket(
                customerId = 7,
                subject = "Quota test",
                content = "Count all attachments.",
            ),
        )
        persistMessageWithAttachment(ticket, 7, SupportTicketMessageSender.CUSTOMER, "customer-1.txt", 10)
        persistMessageWithAttachment(ticket, 7, SupportTicketMessageSender.CUSTOMER, "customer-2.txt", 20)
        persistMessageWithAttachment(ticket, 99, SupportTicketMessageSender.ADMIN, "admin.txt", 30)
        entityManager.flush()
        entityManager.clear()
        val ticketId = requireNotNull(ticket.id)

        assertEquals(3, supportTicketMessageAttachmentRepository.countForTicket(ticketId))
        assertEquals(60, supportTicketMessageAttachmentRepository.totalBytesForTicket(ticketId))
        assertEquals(
            2,
            supportTicketMessageAttachmentRepository.countForSender(7, SupportTicketMessageSender.CUSTOMER),
        )
        assertEquals(
            30,
            supportTicketMessageAttachmentRepository.totalBytesForSender(7, SupportTicketMessageSender.CUSTOMER),
        )
        assertEquals(
            1,
            supportTicketMessageAttachmentRepository.countForSender(99, SupportTicketMessageSender.ADMIN),
        )
    }

    @Test
    fun `stale ticket update is rejected by optimistic locking`() {
        val saved = supportTicketRepository.saveAndFlush(
            SupportTicket(
                customerId = 7,
                serviceType = SupportServiceType.PRE_SALES,
                subject = "Concurrent update",
                content = "Verify ticket versioning.",
            ),
        )
        val ticketId = requireNotNull(saved.id)
        entityManager.detach(saved)
        val first = supportTicketRepository.findById(ticketId).orElseThrow()
        entityManager.detach(first)
        val stale = supportTicketRepository.findById(ticketId).orElseThrow()
        entityManager.detach(stale)

        first.subject = "Updated first"
        supportTicketRepository.saveAndFlush(first)
        entityManager.clear()
        stale.subject = "Stale update"

        assertFailsWith<ObjectOptimisticLockingFailureException> {
            supportTicketRepository.saveAndFlush(stale)
        }
    }

    private fun persistMessageWithAttachment(
        ticket: SupportTicket,
        senderId: Long,
        senderType: SupportTicketMessageSender,
        fileName: String,
        sizeBytes: Long,
    ) {
        val file = storedFile(senderId, fileName, sizeBytes)
        val message = SupportTicketMessage(
            ticket = ticket,
            senderId = senderId,
            senderType = senderType,
        )
        message.attachments += SupportTicketMessageAttachment(message = message, file = file)
        entityManager.persist(file)
        entityManager.persist(message)
    }

    private fun storedFile(ownerId: Long, name: String, sizeBytes: Long) = StoredFile(
        ownerId = ownerId,
        originalFilename = name,
        storedFilename = "$name-${UUID.randomUUID()}",
        relativePath = "2026/08/04/${UUID.randomUUID()}-$name",
        contentType = "text/plain",
        sizeBytes = sizeBytes,
        sha256 = "b".repeat(64),
    )
}
