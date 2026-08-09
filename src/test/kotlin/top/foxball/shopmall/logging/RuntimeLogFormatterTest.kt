package top.foxball.shopmall.logging

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.LocalDateTime

class RuntimeLogFormatterTest {
    private val record = LogRecord(
        timestamp = LocalDateTime.parse("2026-08-08T19:25:14.238"),
        level = LogLevel.INFO,
        thread = "worker\nthread",
        logger = "top.foxball.shopmall.Example",
        requestId = "request-1",
        message = "one\ntwo\\three",
        exception = "",
    )

    @Test
    fun `formatter escapes records and appends one newline`() {
        val rendered = RuntimeLogFormatter.compile(LoggingProperties.DEFAULT_OUTPUT_TEMPLATE).render(record, 1_024)

        assertContains(rendered.text, "one\\ntwo\\\\three")
        assertContains(rendered.text, "worker\\nthread")
        assertFalse(rendered.text.contains('\n'))
        assertTrue(rendered.bytes.contentEquals("${rendered.text}\n".toByteArray()))
    }

    @Test
    fun `formatter rejects unsupported and incomplete templates`() {
        assertFailsWith<IllegalArgumentException> {
            RuntimeLogFormatter.compile("{timestamp} {level} {logger} {message}")
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeLogFormatter.compile("{timestamp} {level} {logger} {message}{exception} {unknown}")
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeLogFormatter.compile("{timestamp} {level} {logger} {message}{message}{exception}")
        }
    }

    @Test
    fun `formatter keeps UTF-8 record under limit`() {
        val rendered = RuntimeLogFormatter.compile(LoggingProperties.DEFAULT_OUTPUT_TEMPLATE).render(
            record.copy(message = "中".repeat(500)),
            128,
        )

        assertTrue(rendered.truncated)
        assertTrue(rendered.bytes.size <= 128)
        assertContains(rendered.text, "<truncated")
        assertEquals('\n'.code.toByte(), rendered.bytes.last())
    }

    @Test
    fun `formatter keeps tiny and malformed-unicode records within the byte limit`() {
        val formatter = RuntimeLogFormatter.compile(LoggingProperties.DEFAULT_OUTPUT_TEMPLATE)
        val malformed = "\uD800"

        (2..64).forEach { limit ->
            val rendered = formatter.render(record.copy(message = malformed.repeat(100)), limit)
            assertTrue(rendered.bytes.size <= limit, "limit=$limit actual=${rendered.bytes.size}")
            assertEquals('\n'.code.toByte(), rendered.bytes.last())
        }
    }
}
