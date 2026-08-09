package top.foxball.shopmall.logging

import ch.qos.logback.classic.spi.IThrowableProxy
import java.util.Collections
import java.util.IdentityHashMap

/** Renders a throwable proxy without ever constructing an unbounded intermediate stack trace. */
internal object BoundedThrowableRenderer {
    private const val MAX_CAUSE_DEPTH = 32
    private const val TRUNCATED_MARKER = "...<throwable truncated>"

    fun render(proxy: IThrowableProxy?, maximumCharacters: Int): String {
        if (proxy == null || maximumCharacters <= 0) return ""
        val output = LimitedText(maximumCharacters)
        val visited = Collections.newSetFromMap(IdentityHashMap<IThrowableProxy, Boolean>())
        appendProxy(proxy, "", output, visited, 0)
        return output.finish()
    }

    private fun appendProxy(
        proxy: IThrowableProxy,
        prefix: String,
        output: LimitedText,
        visited: MutableSet<IThrowableProxy>,
        depth: Int,
    ) {
        if (!output.canContinue()) return
        if (depth >= MAX_CAUSE_DEPTH || proxy.isCyclic || !visited.add(proxy)) {
            output.append(prefix).append("[cyclic or deeply nested throwable]\n")
            return
        }

        output.append(prefix).append(proxy.className)
        proxy.message?.takeIf { it.isNotBlank() }?.let { output.append(": ").append(it) }
        output.append('\n')
        for (frame in proxy.stackTraceElementProxyArray.orEmpty()) {
            if (!output.canContinue()) return
            output.append("\tat ").append(frame.steAsString).append('\n')
        }
        if (proxy.commonFrames > 0) {
            output.append("\t... ").append(proxy.commonFrames.toString()).append(" common frames omitted\n")
        }
        for (suppressed in proxy.suppressed.orEmpty()) {
            appendProxy(suppressed, "Suppressed: ", output, visited, depth + 1)
            if (!output.canContinue()) return
        }
        proxy.cause?.let { cause -> appendProxy(cause, "Caused by: ", output, visited, depth + 1) }
    }

    private class LimitedText(
        private val maximumCharacters: Int,
    ) {
        private val value = StringBuilder(maximumCharacters.coerceAtMost(16_384))
        private var truncated = false

        fun append(text: CharSequence): LimitedText {
            if (truncated || text.isEmpty()) return this
            val remaining = maximumCharacters - value.length
            if (remaining <= 0) {
                truncated = true
                return this
            }
            if (text.length <= remaining) {
                value.append(text)
            } else {
                value.append(text, 0, remaining)
                truncated = true
            }
            return this
        }

        fun append(character: Char): LimitedText {
            if (truncated) return this
            if (value.length < maximumCharacters) {
                value.append(character)
            } else {
                truncated = true
            }
            return this
        }

        fun canContinue(): Boolean = !truncated && value.length < maximumCharacters

        fun finish(): String {
            if (!truncated) return value.toString().trimEnd('\n')
            val marker = TRUNCATED_MARKER.take(maximumCharacters)
            value.setLength((maximumCharacters - marker.length).coerceAtLeast(0).coerceAtMost(value.length))
            value.append(marker)
            return value.toString()
        }
    }
}
