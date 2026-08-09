package top.foxball.shopmall.logging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedLogQueueTest {
    @Test
    fun `interrupt during coalescing returns the already removed event and releases its bytes`() {
        val queue = BoundedLogQueue<String>(maximumEvents = 4, maximumBytes = 1_024)
        assertEquals(LogQueueOfferResult.ACCEPTED, queue.offer("first", 128))
        val result = AtomicReference<List<String>>()
        val completed = CountDownLatch(1)
        val consumer = Thread {
            try {
                result.set(queue.takeBatch(maximumEvents = 4, idleWaitMillis = 1_000, coalesceMillis = 10_000))
            } finally {
                completed.countDown()
            }
        }

        consumer.start()
        val removalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (queue.queuedEvents() != 0 && System.nanoTime() < removalDeadline) Thread.yield()
        assertEquals(0, queue.queuedEvents())
        consumer.interrupt()

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("first"), result.get())
        assertEquals(0L, queue.queuedBytes())
        assertFalse(consumer.isAlive)
    }
}
