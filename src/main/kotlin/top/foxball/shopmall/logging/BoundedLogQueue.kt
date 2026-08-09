package top.foxball.shopmall.logging

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A non-blocking producer queue bounded by both event count and estimated retained bytes. */
internal class BoundedLogQueue<T>(
    maximumEvents: Int,
    private val maximumBytes: Long,
) {
    private val queue = ArrayBlockingQueue<Entry<T>>(maximumEvents)
    private val retainedBytes = AtomicLong(0)
    private val accepting = AtomicBoolean(true)

    fun offer(value: T, estimatedBytes: Long): LogQueueOfferResult {
        if (!accepting.get()) return LogQueueOfferResult.CLOSED
        val normalizedBytes = estimatedBytes.coerceAtLeast(1)
        while (true) {
            val current = retainedBytes.get()
            if (normalizedBytes > maximumBytes - current) return LogQueueOfferResult.BYTE_CAPACITY
            if (retainedBytes.compareAndSet(current, current + normalizedBytes)) break
        }
        if (!accepting.get() || !queue.offer(Entry(value, normalizedBytes))) {
            retainedBytes.addAndGet(-normalizedBytes)
            return if (accepting.get()) LogQueueOfferResult.EVENT_CAPACITY else LogQueueOfferResult.CLOSED
        }
        return LogQueueOfferResult.ACCEPTED
    }

    @Throws(InterruptedException::class)
    fun takeBatch(
        maximumEvents: Int,
        idleWaitMillis: Long,
        coalesceMillis: Long = 0,
    ): List<T> {
        require(maximumEvents > 0) { "maximumEvents must be positive" }
        val first = if (idleWaitMillis <= 0) {
            queue.poll()
        } else {
            queue.poll(idleWaitMillis, TimeUnit.MILLISECONDS)
        } ?: return emptyList()

        val entries = ArrayList<Entry<T>>(maximumEvents)
        entries += first
        var interruptedWhileCoalescing = false
        if (maximumEvents > 1) {
            if (coalesceMillis > 0 && accepting.get()) {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(coalesceMillis)
                while (entries.size < maximumEvents) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    val next = try {
                        queue.poll(remaining, TimeUnit.NANOSECONDS)
                    } catch (_: InterruptedException) {
                        // Once an entry has been removed from the queue it must still be returned to
                        // the consumer. Re-interrupt after accounting so shutdown remains prompt.
                        interruptedWhileCoalescing = true
                        break
                    } ?: break
                    entries += next
                }
            } else {
                queue.drainTo(entries, maximumEvents - entries.size)
            }
        }
        retainedBytes.addAndGet(-entries.sumOf { it.estimatedBytes })
        val values = entries.mapTo(ArrayList(entries.size)) { it.value }
        if (interruptedWhileCoalescing) Thread.currentThread().interrupt()
        return values
    }

    fun closeForOffers() {
        accepting.set(false)
    }

    fun isAccepting(): Boolean = accepting.get()

    fun isEmpty(): Boolean = queue.isEmpty()

    /** Releases queued values after the bounded shutdown deadline has expired. */
    fun discardAll(): LogQueueDiscardResult {
        val entries = ArrayList<Entry<T>>(queue.size)
        queue.drainTo(entries)
        val discardedBytes = entries.sumOf { it.estimatedBytes }
        if (discardedBytes > 0) retainedBytes.addAndGet(-discardedBytes)
        return LogQueueDiscardResult(entries.size, discardedBytes)
    }

    internal fun queuedEvents(): Int = queue.size

    internal fun queuedBytes(): Long = retainedBytes.get()

    private data class Entry<T>(
        val value: T,
        val estimatedBytes: Long,
    )
}

internal data class LogQueueDiscardResult(
    val events: Int,
    val bytes: Long,
)

internal enum class LogQueueOfferResult {
    ACCEPTED,
    CLOSED,
    EVENT_CAPACITY,
    BYTE_CAPACITY,
}
