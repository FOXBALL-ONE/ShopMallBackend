package top.foxball.shopmall.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.net.InetAddress

/** Resolves a client address without trusting forwarding headers from direct clients. */
@Component
class ClientIpResolver(
    properties: RateLimitProperties,
) {
    private val trustedProxyCidrs = properties.trustedProxyCidrs.map(TrustedProxyCidr::parse)

    fun resolve(request: HttpServletRequest): String {
        val remote = TrustedProxyCidr.parseIpLiteral(request.remoteAddr)
            ?: throw RateLimitUnavailableException("Unable to parse request remote address")
        if (!isTrustedProxy(remote)) return TrustedProxyCidr.canonicalText(remote)

        val forwardedHops = forwardedHops(request)
        if (forwardedHops != null) {
            return selectClient(forwardedHops, remote)
        }

        val xForwardedForHops = xForwardedForHops(request)
        return if (xForwardedForHops == null) {
            TrustedProxyCidr.canonicalText(remote)
        } else {
            selectClient(xForwardedForHops, remote)
        }
    }

    /** null means no Forwarded header; an empty list means a present but invalid header. */
    private fun forwardedHops(request: HttpServletRequest): List<InetAddress>? {
        val values = request.getHeaders(FORWARDED_HEADER).toList()
        if (values.isEmpty()) return null
        val hops = mutableListOf<InetAddress>()
        for (value in values) {
            val elements = splitOutsideQuotes(value, ',') ?: return emptyList()
            for (element in elements) {
                val parameters = splitOutsideQuotes(element, ';') ?: return emptyList()
                var forwardedFor: String? = null
                for (parameter in parameters) {
                    val separator = parameter.indexOf('=')
                    if (separator <= 0) return emptyList()
                    val name = parameter.substring(0, separator).trim()
                    if (!name.equals("for", ignoreCase = true)) continue
                    if (forwardedFor != null) return emptyList()
                    forwardedFor = parseForwardedFor(parameter.substring(separator + 1).trim()) ?: return emptyList()
                }
                val address = forwardedFor?.let(TrustedProxyCidr::parseIpLiteral) ?: return emptyList()
                hops += address
            }
        }
        return hops.takeIf { it.isNotEmpty() } ?: emptyList()
    }

    /** null means no X-Forwarded-For header; an empty list means a present but invalid header. */
    private fun xForwardedForHops(request: HttpServletRequest): List<InetAddress>? {
        val values = request.getHeaders(X_FORWARDED_FOR_HEADER).toList()
        if (values.isEmpty()) return null
        val hops = mutableListOf<InetAddress>()
        for (value in values) {
            val parts = value.split(',')
            if (parts.isEmpty()) return emptyList()
            for (part in parts) {
                val address = TrustedProxyCidr.parseIpLiteral(part.trim()) ?: return emptyList()
                hops += address
            }
        }
        return hops.takeIf { it.isNotEmpty() } ?: emptyList()
    }

    private fun selectClient(hops: List<InetAddress>, remote: InetAddress): String {
        if (hops.isEmpty()) return TrustedProxyCidr.canonicalText(remote)
        for (hop in (hops + remote).asReversed()) {
            if (!isTrustedProxy(hop)) return TrustedProxyCidr.canonicalText(hop)
        }
        return TrustedProxyCidr.canonicalText(remote)
    }

    private fun isTrustedProxy(address: InetAddress): Boolean = trustedProxyCidrs.any { it.contains(address) }

    private fun splitOutsideQuotes(value: String, delimiter: Char): List<String>? {
        if (value.isBlank()) return null
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (character in value) {
            when {
                character == '\\' && quoted -> return null
                character == '"' -> {
                    quoted = !quoted
                    current.append(character)
                }
                character == delimiter && !quoted -> {
                    val part = current.toString().trim()
                    if (part.isEmpty()) return null
                    parts += part
                    current.setLength(0)
                }
                else -> current.append(character)
            }
        }
        if (quoted) return null
        val last = current.toString().trim()
        if (last.isEmpty()) return null
        parts += last
        return parts
    }

    private fun parseForwardedFor(value: String): String? {
        val unquoted = when {
            value.startsWith('"') && value.endsWith('"') && value.length >= 2 -> value.substring(1, value.length - 1)
            value.contains('"') -> return null
            else -> value
        }
        if (unquoted.startsWith('[')) {
            val closing = unquoted.indexOf(']')
            if (closing <= 1) return null
            val suffix = unquoted.substring(closing + 1)
            if (suffix.isNotEmpty() && !(suffix.startsWith(':') && suffix.substring(1).all(Char::isDigit))) return null
            return unquoted.substring(1, closing)
        }
        if (unquoted.count { it == ':' } == 1) {
            val separator = unquoted.lastIndexOf(':')
            val host = unquoted.substring(0, separator)
            val port = unquoted.substring(separator + 1)
            if (TrustedProxyCidr.parseIpLiteral(host) != null && port.isNotEmpty() && port.all(Char::isDigit)) {
                return host
            }
        }
        return unquoted.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) && !it.startsWith('_') }
    }

    private companion object {
        const val FORWARDED_HEADER = "Forwarded"
        const val X_FORWARDED_FOR_HEADER = "X-Forwarded-For"
    }
}
