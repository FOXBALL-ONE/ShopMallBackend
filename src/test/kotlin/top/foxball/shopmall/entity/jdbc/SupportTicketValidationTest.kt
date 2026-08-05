package top.foxball.shopmall.entity.jdbc

import jakarta.validation.Validation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportTicketValidationTest {
    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Test
    fun `new ticket defaults to low priority and open status`() {
        val ticket = SupportTicket(
            customerId = 7,
            serviceType = SupportServiceType.PRE_SALES,
            subject = "Size advice",
            content = "Which size should I choose?",
        )

        assertEquals(SupportTicketPriority.LOW, ticket.priority)
        assertEquals(SupportTicketStatus.OPEN, ticket.status)
        assertTrue(validator.validate(ticket).isEmpty())
    }

    @Test
    fun `after sales ticket requires an order`() {
        val ticket = SupportTicket(
            customerId = 7,
            serviceType = SupportServiceType.AFTER_SALES,
            subject = "Damaged item",
            content = "The item arrived damaged.",
        )

        val invalidProperties = validator.validate(ticket).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("hasRequiredOrder"), invalidProperties)
    }

    @Test
    fun `after sales ticket accepts a linked order`() {
        val ticket = SupportTicket(
            customerId = 7,
            serviceType = SupportServiceType.AFTER_SALES,
            order = OrderEntity(id = 10, orderNo = "ORD-10", customerId = 7),
            subject = "Damaged item",
            content = "The item arrived damaged.",
        )

        assertTrue(validator.validate(ticket).isEmpty())
    }

    @Test
    fun `ticket rejects an order owned by another customer`() {
        val ticket = SupportTicket(
            customerId = 7,
            serviceType = SupportServiceType.AFTER_SALES,
            order = OrderEntity(id = 10, orderNo = "ORD-10", customerId = 8),
            subject = "Damaged item",
            content = "The item arrived damaged.",
        )

        val invalidProperties = validator.validate(ticket).map { it.propertyPath.toString() }.toSet()
        assertEquals(setOf("ownsLinkedOrder"), invalidProperties)
    }

    @Test
    fun `message rejects blank content without attachments`() {
        val message = SupportTicketMessage(
            ticket = SupportTicket(id = 1),
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = "   ",
        )

        val invalidProperties = validator.validate(message).map { it.propertyPath.toString() }.toSet()

        assertEquals(setOf("hasContentOrAttachment"), invalidProperties)
    }

    @Test
    fun `message accepts text without attachments`() {
        val message = SupportTicketMessage(
            ticket = SupportTicket(id = 1),
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
            content = "Please help.",
        )

        assertTrue(validator.validate(message).isEmpty())
    }

    @Test
    fun `message accepts an attachment without text`() {
        val message = SupportTicketMessage(
            ticket = SupportTicket(id = 1),
            senderId = 7,
            senderType = SupportTicketMessageSender.CUSTOMER,
        )
        message.attachments += SupportTicketMessageAttachment(
            message = message,
            file = StoredFile(
                ownerId = 7,
                originalFilename = "evidence.txt",
                storedFilename = "evidence.txt",
                relativePath = "support/evidence.txt",
                contentType = "text/plain",
                sizeBytes = 8,
                sha256 = "a".repeat(64),
            ),
        )

        assertTrue(validator.validate(message).isEmpty())
    }
}

