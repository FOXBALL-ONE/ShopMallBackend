package top.foxball.shopmall.logging

import java.nio.charset.StandardCharsets
import java.time.format.DateTimeFormatter

/** Compiles and renders the deliberately small, single-line logging template language. */
class RuntimeLogFormatter private constructor(
    val template: String,
    private val segments: List<Segment>,
) {
    fun render(record: LogRecord, maxRecordBytes: Int): RenderedLogRecord {
        require(maxRecordBytes > 1) { "maxRecordBytes must be greater than one" }
        val maxLineBytes = maxRecordBytes - 1
        val output = CountingUtf8Prefix(maxLineBytes)
        segments.forEach { segment ->
            when (segment) {
                is Segment.Literal -> output.appendRaw(segment.value)
                is Segment.Placeholder -> when (segment.field) {
                    TemplateField.TIMESTAMP -> output.appendRaw(
                        record.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    )
                    TemplateField.LEVEL -> output.appendRaw(record.level.name)
                    TemplateField.THREAD -> output.appendEscaped(record.thread)
                    TemplateField.LOGGER -> output.appendEscaped(record.logger)
                    TemplateField.MESSAGE -> output.appendEscaped(record.message)
                    TemplateField.EXCEPTION -> if (record.exception.isNotBlank()) {
                        output.appendRaw(" exception=")
                        output.appendEscaped(record.exception)
                    }
                    TemplateField.REQUEST_ID -> output.appendEscaped(record.requestId ?: "-")
                }
            }
        }
        val renderedPrefix = output.prefix()
        if (output.totalBytes <= maxLineBytes.toLong()) {
            return RenderedLogRecord(
                text = renderedPrefix,
                bytes = renderedPrefix.toByteArray(StandardCharsets.UTF_8).withLineFeed(),
                truncated = false,
            )
        }

        var retainedByteLimit = (maxLineBytes - TRUNCATION_SUFFIX_RESERVE).coerceAtLeast(0)
        var retained = utf8Prefix(renderedPrefix, retainedByteLimit)
        repeat(4) {
            val retainedBytes = utf8Length(retained)
            val suffix = "...<truncated ${output.totalBytes - retainedBytes} bytes>"
            retainedByteLimit = (maxLineBytes - utf8Length(suffix)).coerceAtLeast(0)
            retained = utf8Prefix(renderedPrefix, retainedByteLimit)
        }
        val retainedBytes = utf8Length(retained)
        val fullSuffix = "...<truncated ${output.totalBytes - retainedBytes} bytes>"
        val suffix = utf8Prefix(fullSuffix, maxLineBytes - retainedBytes)
        val truncated = retained + suffix
        val truncatedBytes = truncated.toByteArray(StandardCharsets.UTF_8)
        return RenderedLogRecord(
            text = truncated,
            bytes = truncatedBytes.withLineFeed(),
            truncated = true,
        )
    }

    private fun ByteArray.withLineFeed(): ByteArray = copyOf(size + 1).also { bytes ->
        bytes[bytes.lastIndex] = '\n'.code.toByte()
    }

    sealed interface Segment {
        data class Literal(val value: String) : Segment

        data class Placeholder(val field: TemplateField) : Segment
    }

    enum class TemplateField(val wireName: String) {
        TIMESTAMP("timestamp"),
        LEVEL("level"),
        THREAD("thread"),
        LOGGER("logger"),
        MESSAGE("message"),
        EXCEPTION("exception"),
        REQUEST_ID("request_id"),
        ;

        companion object {
            fun fromWireName(raw: String): TemplateField? = entries.firstOrNull { it.wireName == raw }
        }
    }

    companion object {
        private const val MAX_TEMPLATE_LENGTH = 1_024
        private const val TRUNCATION_SUFFIX_RESERVE = 64
        private val REQUIRED_FIELDS = setOf(
            TemplateField.TIMESTAMP,
            TemplateField.LEVEL,
            TemplateField.LOGGER,
            TemplateField.MESSAGE,
            TemplateField.EXCEPTION,
        )

        fun compile(template: String): RuntimeLogFormatter {
            val templateBytes = template.toByteArray(StandardCharsets.UTF_8).size
            require(templateBytes in 1..MAX_TEMPLATE_LENGTH) {
                "output_template must contain between 1 and $MAX_TEMPLATE_LENGTH UTF-8 bytes"
            }
            require(template.none { it == '\u0000' || it == '\r' || it == '\n' || it == '\u001b' || it.isISOControl() }) {
                "output_template must not contain control characters"
            }

            val segments = mutableListOf<Segment>()
            val literal = StringBuilder()
            val usedFields = mutableSetOf<TemplateField>()
            var index = 0
            fun flushLiteral() {
                if (literal.isNotEmpty()) {
                    segments += Segment.Literal(literal.toString())
                    literal.setLength(0)
                }
            }

            while (index < template.length) {
                when {
                    template.startsWith("{{", index) -> {
                        literal.append('{')
                        index += 2
                    }

                    template.startsWith("}}", index) -> {
                        literal.append('}')
                        index += 2
                    }

                    template[index] == '{' -> {
                        flushLiteral()
                        val end = template.indexOf('}', index + 1)
                        require(end >= 0) { "output_template contains an unclosed placeholder" }
                        val name = template.substring(index + 1, end)
                        val field = TemplateField.fromWireName(name)
                            ?: throw IllegalArgumentException("Unsupported output_template placeholder: {$name}")
                        require(usedFields.add(field)) {
                            "output_template must not repeat placeholder: {$name}"
                        }
                        segments += Segment.Placeholder(field)
                        index = end + 1
                    }

                    template[index] == '}' -> throw IllegalArgumentException(
                        "output_template contains an unmatched closing brace; use }} for a literal brace",
                    )

                    else -> {
                        literal.append(template[index])
                        index++
                    }
                }
            }
            flushLiteral()
            val missing = REQUIRED_FIELDS - usedFields
            require(missing.isEmpty()) {
                "output_template is missing required placeholders: ${missing.joinToString { "{${it.wireName}}" }}"
            }
            return RuntimeLogFormatter(template, segments.toList())
        }

        fun escape(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\r' -> append("\\r")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    else -> if (character.isISOControl()) {
                        append("\\u")
                        append(character.code.toString(16).uppercase().padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }

        private fun utf8Prefix(value: String, byteLimit: Int): String {
            if (byteLimit <= 0) return ""
            var charIndex = 0
            var byteCount = 0
            while (charIndex < value.length) {
                val character = value[charIndex]
                val charCount = if (
                    Character.isHighSurrogate(character) &&
                    charIndex + 1 < value.length &&
                    Character.isLowSurrogate(value[charIndex + 1])
                ) {
                    2
                } else {
                    1
                }
                val bytes = utf8CharacterBytes(value, charIndex, charCount)
                if (byteCount + bytes > byteLimit) break
                byteCount += bytes
                charIndex += charCount
            }
            return value.substring(0, charIndex)
        }

        private fun utf8Length(value: String): Int {
            var index = 0
            var bytes = 0
            while (index < value.length) {
                val character = value[index]
                val charCount = if (
                    Character.isHighSurrogate(character) &&
                    index + 1 < value.length &&
                    Character.isLowSurrogate(value[index + 1])
                ) {
                    2
                } else {
                    1
                }
                bytes += utf8CharacterBytes(value, index, charCount)
                index += charCount
            }
            return bytes
        }

        /** Matches the JDK UTF-8 encoder, including its one-byte replacement for malformed surrogates. */
        private fun utf8CharacterBytes(value: String, index: Int, charCount: Int): Int {
            if (charCount == 2) return 4
            val character = value[index]
            return when {
                Character.isSurrogate(character) -> 1
                character.code <= 0x7f -> 1
                character.code <= 0x7ff -> 2
                else -> 3
            }
        }
    }

    /** Counts the complete rendered size while retaining only one configured record-size prefix. */
    private class CountingUtf8Prefix(
        private val maximumBytes: Int,
    ) {
        private val value = StringBuilder(maximumBytes.coerceAtMost(16_384))
        private var retainedBytes = 0
        var totalBytes = 0L
            private set

        fun appendRaw(text: String) {
            var index = 0
            while (index < text.length) {
                val character = text[index]
                val charCount = if (
                    Character.isHighSurrogate(character) &&
                    index + 1 < text.length &&
                    Character.isLowSurrogate(text[index + 1])
                ) {
                    2
                } else {
                    1
                }
                val bytes = utf8CharacterBytes(text, index, charCount)
                totalBytes += bytes
                if (retainedBytes + bytes <= maximumBytes) {
                    value.append(text, index, index + charCount)
                    retainedBytes += bytes
                }
                index += charCount
            }
        }

        fun appendEscaped(text: String) {
            var index = 0
            while (index < text.length) {
                val character = text[index]
                when (character) {
                    '\\' -> appendAscii('\\', '\\')
                    '\r' -> appendAscii('\\', 'r')
                    '\n' -> appendAscii('\\', 'n')
                    '\t' -> appendAscii('\\', 't')
                    else -> if (character.isISOControl()) {
                        appendUnicodeEscape(character)
                    } else {
                        val charCount = if (
                            Character.isHighSurrogate(character) &&
                            index + 1 < text.length &&
                            Character.isLowSurrogate(text[index + 1])
                        ) {
                            2
                        } else {
                            1
                        }
                        val bytes = utf8CharacterBytes(text, index, charCount)
                        totalBytes += bytes
                        if (retainedBytes + bytes <= maximumBytes) {
                            value.append(text, index, index + charCount)
                            retainedBytes += bytes
                        }
                        index += charCount - 1
                    }
                }
                index++
            }
        }

        fun prefix(): String = value.toString()

        private fun appendAscii(vararg characters: Char) {
            characters.forEach { character ->
                totalBytes++
                if (retainedBytes < maximumBytes) {
                    value.append(character)
                    retainedBytes++
                }
            }
        }

        private fun appendUnicodeEscape(character: Char) {
            appendAscii('\\', 'u')
            repeat(4) { shift ->
                val bits = (character.code shr ((3 - shift) * 4)) and 0xf
                appendAscii(HEX_DIGITS[bits])
            }
        }

        private companion object {
            const val HEX_DIGITS = "0123456789ABCDEF"

            fun utf8CharacterBytes(value: String, index: Int, charCount: Int): Int {
                if (charCount == 2) return 4
                val character = value[index]
                return when {
                    Character.isSurrogate(character) -> 1
                    character.code <= 0x7f -> 1
                    character.code <= 0x7ff -> 2
                    else -> 3
                }
            }
        }
    }
}
