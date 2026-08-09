package top.foxball.shopmall.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.UnsynchronizedAppenderBase
import org.slf4j.helpers.MessageFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Captures Logback events without blocking application threads. Formatting, live publication, and
 * file I/O are isolated behind separate bounded queues so slow disks can only cause log loss.
 */
class ShopMallLogAppender(
    private val writer: LogFileSink,
    private val liveBuffer: LiveLogBuffer,
    private val properties: LoggingProperties,
    private val metrics: LoggingMetrics,
    initialFormatter: RuntimeLogFormatter,
    initialTemplateVersion: Long,
) : UnsynchronizedAppenderBase<ILoggingEvent>() {
    private val inputQueue = BoundedLogQueue<QueuedLogEvent>(
        properties.asyncQueueEvents,
        properties.asyncQueueBytes,
    )
    private val fileQueue = BoundedLogQueue<FileLogRecord>(
        properties.fileQueueEvents,
        properties.fileQueueBytes,
    )
    private val maximumRecordCharacters = properties.maxRecordSize.toBytes().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val conservativeUnknownArgumentBytes = properties.maxRecordSize.toBytes()
        .coerceAtMost(properties.asyncQueueBytes)
        .coerceAtLeast(ARGUMENT_REFERENCE_BYTES)
    private val workerId = WORKER_IDS.incrementAndGet()
    private val formatterWorker = Thread(::runFormatterWorker, "shopmall-log-format-$workerId").apply { isDaemon = true }
    private val fileWorker = Thread(::runFileWorker, "shopmall-log-file-$workerId").apply { isDaemon = true }

    @Volatile
    private var formatterState = FormatterState(initialFormatter, initialTemplateVersion)

    fun updateFormatter(formatter: RuntimeLogFormatter, version: Long) {
        formatterState = FormatterState(formatter, version)
    }

    override fun start() {
        if (isStarted) return
        super.start()
        try {
            fileWorker.start()
            formatterWorker.start()
        } catch (exception: RuntimeException) {
            inputQueue.closeForOffers()
            fileQueue.closeForOffers()
            super.stop()
            runCatching { writer.close() }
            addError("Unable to start ShopMall asynchronous logging workers", exception)
            throw exception
        }
    }

    /** The application thread performs only bounded capture and a non-blocking queue offer. */
    override fun append(event: ILoggingEvent) {
        try {
            val state = formatterState
            val mdc = event.mdcPropertyMap.orEmpty()
            val messagePattern = truncateCapturedText(event.message.orEmpty(), maximumRecordCharacters)
            val logger = truncateCapturedText(event.loggerName.orEmpty(), MAX_LOGGER_CHARACTERS)
            val thread = truncateCapturedText(event.threadName.orEmpty(), MAX_THREAD_CHARACTERS)
            val requestId = mdc[REQUEST_ID_MDC_KEY]?.let { value ->
                truncateCapturedText(value, MAX_REQUEST_ID_CHARACTERS)
            }
            val arguments = event.argumentArray
            val retainedArgumentBytes = estimateRetainedArgumentBytes(arguments)
            if (retainedArgumentBytes >= properties.asyncQueueBytes) {
                metrics.asyncDropped(AsyncLogStage.INPUT, LogQueueOfferResult.BYTE_CAPACITY)
                return
            }
            val throwable = event.throwableProxy
            val queued = QueuedLogEvent(
                timestampMillis = event.timeStamp,
                level = LogLevel.fromLogback(event.level),
                thread = thread,
                logger = logger,
                requestId = requestId,
                messagePattern = messagePattern,
                arguments = arguments,
                throwable = throwable,
                formatterState = state,
                excludeFromLiveTail = mdc[LIVE_TAIL_EXCLUDED_MDC_KEY] == "true",
            )
            val estimatedBytes = LoggingProperties.ASYNC_EVENT_OVERHEAD_BYTES +
                (messagePattern.length + logger.length + thread.length + requestId.orEmpty().length).toLong() * 2L +
                retainedArgumentBytes +
                if (throwable == null) 0L else maximumRecordCharacters.toLong() * 2L
            val result = inputQueue.offer(queued, estimatedBytes)
            if (result != LogQueueOfferResult.ACCEPTED) metrics.asyncDropped(AsyncLogStage.INPUT, result)
        } catch (exception: Exception) {
            metrics.asyncProcessingFailed(AsyncLogStage.INPUT)
            addError("ShopMall logging event capture failed", exception)
        }
    }

    override fun stop() {
        if (!isStarted) return
        super.stop()
        inputQueue.closeForOffers()
        formatterWorker.interrupt()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.shutdownTimeoutMillis)
        joinUntil(formatterWorker, deadline)

        fileQueue.closeForOffers()
        fileWorker.interrupt()
        joinUntil(fileWorker, deadline)
        val abandonedInput = inputQueue.discardAll()
        val abandonedFile = fileQueue.discardAll()
        metrics.asyncDropped(AsyncLogStage.INPUT, LogQueueOfferResult.CLOSED, abandonedInput.events)
        metrics.asyncDropped(AsyncLogStage.FILE, LogQueueOfferResult.CLOSED, abandonedFile.events)
        if (formatterWorker.isAlive || fileWorker.isAlive || abandonedInput.events > 0 || abandonedFile.events > 0) {
            addWarn(
                "ShopMall asynchronous logging shutdown abandoned " +
                    "${abandonedInput.events} input and ${abandonedFile.events} file events",
            )
        }
    }

    private fun runFormatterWorker() {
        try {
            while (inputQueue.isAccepting() || !inputQueue.isEmpty()) {
                val batch = try {
                    inputQueue.takeBatch(FORMAT_BATCH_EVENTS, WORKER_IDLE_WAIT_MILLIS)
                } catch (_: InterruptedException) {
                    continue
                }
                batch.forEach { queued ->
                    try {
                        val occurredAt = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(queued.timestampMillis),
                            properties.zoneId,
                        )
                        val formattedMessage = if (queued.arguments.isNullOrEmpty()) {
                            queued.messagePattern
                        } else {
                            MessageFormatter.arrayFormat(queued.messagePattern, queued.arguments).message.orEmpty()
                        }
                        val record = LogRecord(
                            timestamp = occurredAt,
                            level = queued.level,
                            thread = queued.thread,
                            logger = queued.logger,
                            requestId = queued.requestId,
                            message = truncateCapturedText(formattedMessage, maximumRecordCharacters),
                            exception = BoundedThrowableRenderer.render(queued.throwable, maximumRecordCharacters),
                        )
                        val rendered = queued.formatterState.formatter.render(record, maximumRecordCharacters)
                        if (!queued.excludeFromLiveTail) {
                            try {
                                liveBuffer.append(
                                    timestamp = record.timestamp,
                                    level = record.level,
                                    logger = record.logger,
                                    thread = record.thread,
                                    requestId = record.requestId,
                                    rendered = rendered,
                                    templateVersion = queued.formatterState.version,
                                )
                            } catch (exception: Exception) {
                                metrics.asyncProcessingFailed(AsyncLogStage.INPUT)
                                addError("ShopMall live log publication failed", exception)
                            }
                        }
                        val fileResult = fileQueue.offer(
                            FileLogRecord(
                                bytes = rendered.bytes,
                                truncated = rendered.truncated,
                                occurredAt = occurredAt,
                                templateKey = queued.formatterState.formatter.template,
                                level = record.level,
                            ),
                            rendered.bytes.size.toLong() + LoggingProperties.FILE_EVENT_OVERHEAD_BYTES,
                        )
                        if (fileResult != LogQueueOfferResult.ACCEPTED) {
                            metrics.asyncDropped(AsyncLogStage.FILE, fileResult)
                        }
                    } catch (exception: Exception) {
                        metrics.asyncProcessingFailed(AsyncLogStage.INPUT)
                        addError("ShopMall asynchronous log formatting failed", exception)
                    }
                }
            }
        } catch (failure: Throwable) {
            inputQueue.closeForOffers()
            metrics.asyncProcessingFailed(AsyncLogStage.INPUT)
            addError("ShopMall log formatter worker stopped unexpectedly", failure)
        } finally {
            fileQueue.closeForOffers()
            fileWorker.interrupt()
        }
    }

    private fun runFileWorker() {
        try {
            try {
                writer.initialize()
            } catch (exception: Exception) {
                metrics.asyncProcessingFailed(AsyncLogStage.FILE)
                addError("ShopMall asynchronous log file initialization failed", exception)
            }
            while (fileQueue.isAccepting() || !fileQueue.isEmpty()) {
                val batch = try {
                    fileQueue.takeBatch(
                        maximumEvents = properties.fileBatchEvents,
                        idleWaitMillis = WORKER_IDLE_WAIT_MILLIS,
                        coalesceMillis = if (fileQueue.isAccepting()) properties.fileFlushIntervalMillis else 0,
                    )
                } catch (_: InterruptedException) {
                    continue
                }
                if (batch.isEmpty()) continue
                val failed = try {
                    val result = writer.writeBatch(batch)
                    if (!result.success) metrics.asyncProcessingFailed(AsyncLogStage.FILE)
                    !result.success
                } catch (exception: Exception) {
                    metrics.asyncProcessingFailed(AsyncLogStage.FILE)
                    addError("ShopMall asynchronous file logging failed", exception)
                    true
                }
                if (failed && (fileQueue.isAccepting() || !fileQueue.isEmpty())) {
                    try {
                        Thread.sleep(properties.fileFailureBackoffMillis)
                    } catch (_: InterruptedException) {
                        // Shutdown interrupts the backoff so the bounded drain can continue.
                    }
                }
            }
        } catch (failure: Throwable) {
            fileQueue.closeForOffers()
            metrics.asyncProcessingFailed(AsyncLogStage.FILE)
            addError("ShopMall log file worker stopped unexpectedly", failure)
        } finally {
            runCatching { writer.close() }
                .onFailure { failure -> addError("Unable to close ShopMall log file sink", failure) }
        }
    }

    private fun joinUntil(worker: Thread, deadlineNanos: Long) {
        if (!worker.isAlive) return
        val remainingNanos = deadlineNanos - System.nanoTime()
        if (remainingNanos <= 0) return
        try {
            TimeUnit.NANOSECONDS.timedJoin(worker, remainingNanos)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun truncateCapturedText(value: String, maximumCharacters: Int): String {
        if (value.length <= maximumCharacters) return value
        if (maximumCharacters <= CAPTURE_TRUNCATION_SUFFIX.length) return value.take(maximumCharacters)
        return value.take(maximumCharacters - CAPTURE_TRUNCATION_SUFFIX.length) + CAPTURE_TRUNCATION_SUFFIX
    }

    /**
     * Estimates retained arguments with constant bounded work and without invoking application
     * methods such as custom Collection.size or toString implementations.
     */
    private fun estimateRetainedArgumentBytes(arguments: Array<out Any?>?): Long {
        if (arguments.isNullOrEmpty()) return 0
        if (arguments.size > MAX_CAPTURED_ARGUMENTS) return properties.asyncQueueBytes

        var estimatedBytes = estimateArrayBytes(arguments.size, ARGUMENT_REFERENCE_BYTES)
        arguments.forEach { value ->
            val additionalBytes = when (value) {
                null -> 0
                is String -> estimateArrayBytes(value.length, LoggingProperties.ESTIMATED_CHARACTER_BYTES)
                is ByteArray -> estimateArrayBytes(value.size, Byte.SIZE_BYTES.toLong())
                is BooleanArray -> estimateArrayBytes(value.size, Byte.SIZE_BYTES.toLong())
                is ShortArray -> estimateArrayBytes(value.size, Short.SIZE_BYTES.toLong())
                is CharArray -> estimateArrayBytes(value.size, Char.SIZE_BYTES.toLong())
                is IntArray -> estimateArrayBytes(value.size, Int.SIZE_BYTES.toLong())
                is FloatArray -> estimateArrayBytes(value.size, Float.SIZE_BYTES.toLong())
                is LongArray -> estimateArrayBytes(value.size, Long.SIZE_BYTES.toLong())
                is DoubleArray -> estimateArrayBytes(value.size, Double.SIZE_BYTES.toLong())
                is Array<*> -> addRetainedBytes(
                    conservativeUnknownArgumentBytes,
                    estimateArrayBytes(value.size, ARGUMENT_REFERENCE_BYTES),
                )
                is Byte,
                is Boolean,
                is Short,
                is Char,
                is Int,
                is Float,
                is Long,
                is Double,
                is Enum<*>,
                -> SMALL_ARGUMENT_BYTES
                is Collection<*>, is Map<*, *> -> conservativeUnknownArgumentBytes
                else -> conservativeUnknownArgumentBytes
            }
            estimatedBytes = addRetainedBytes(estimatedBytes, additionalBytes)
            if (estimatedBytes >= properties.asyncQueueBytes) return properties.asyncQueueBytes
        }
        return estimatedBytes
    }

    private fun estimateArrayBytes(elements: Int, bytesPerElement: Long): Long {
        val count = elements.toLong()
        return if (count > properties.asyncQueueBytes / bytesPerElement) {
            properties.asyncQueueBytes
        } else {
            count * bytesPerElement
        }
    }

    private fun addRetainedBytes(current: Long, additional: Long): Long {
        val remaining = properties.asyncQueueBytes - current
        return if (additional >= remaining) properties.asyncQueueBytes else current + additional
    }

    private data class QueuedLogEvent(
        val timestampMillis: Long,
        val level: LogLevel,
        val thread: String,
        val logger: String,
        val requestId: String?,
        val messagePattern: String,
        val arguments: Array<out Any?>?,
        val throwable: IThrowableProxy?,
        val formatterState: FormatterState,
        val excludeFromLiveTail: Boolean,
    )

    private data class FormatterState(
        val formatter: RuntimeLogFormatter,
        val version: Long,
    )

    companion object {
        const val LIVE_TAIL_EXCLUDED_MDC_KEY = "shopmall_live_tail_excluded"
        private const val REQUEST_ID_MDC_KEY = "request_id"
        private const val FORMAT_BATCH_EVENTS = 128
        private const val WORKER_IDLE_WAIT_MILLIS = 50L
        private const val ARGUMENT_REFERENCE_BYTES = 16L
        private const val SMALL_ARGUMENT_BYTES = 32L
        private const val MAX_CAPTURED_ARGUMENTS = 64
        private const val MAX_LOGGER_CHARACTERS = LoggingProperties.MAX_LOGGER_CHARACTERS
        private const val MAX_THREAD_CHARACTERS = LoggingProperties.MAX_THREAD_CHARACTERS
        private const val MAX_REQUEST_ID_CHARACTERS = LoggingProperties.MAX_REQUEST_ID_CHARACTERS
        private const val CAPTURE_TRUNCATION_SUFFIX = "...<capture truncated>"
        private val WORKER_IDS = AtomicLong(0)
    }
}
