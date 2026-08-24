package top.foxball.shopmall.service

import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import top.foxball.shopmall.config.OrderProperties
import top.foxball.shopmall.service.impl.OrderEventConsumer
import top.foxball.shopmall.service.impl.OutboxMessageHandler
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderEventConsumerTest {
    @Test
    fun `new consumer group starts before existing stream records`() {
        val redis = mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val operations = mock(StreamOperations::class.java) as StreamOperations<String, String, String>
        val handler = mock(OutboxMessageHandler::class.java)
        val bootstrapId = RecordId.of("2-0")
        `when`(redis.opsForStream<String, String>()).thenReturn(operations)
        `when`(operations.add("order:events", mapOf("bootstrap" to "true"))).thenReturn(bootstrapId)
        val consumer = OrderEventConsumer(redis, handler, OrderProperties())

        consumer.poll()

        val offset = ArgumentCaptor.forClass(ReadOffset::class.java)
        verify(operations).createGroup(
            org.mockito.ArgumentMatchers.eq("order:events"),
            offset.capture(),
            org.mockito.ArgumentMatchers.eq("order-consumer-group"),
        )
        assertEquals("0-0", offset.value.offset)
        verify(operations).delete("order:events", bootstrapId)
    }
}
