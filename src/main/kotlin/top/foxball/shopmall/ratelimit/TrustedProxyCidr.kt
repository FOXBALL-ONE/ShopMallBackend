package top.foxball.shopmall.ratelimit

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** A numeric-only CIDR range used to decide whether forwarding headers are trustworthy. */
class TrustedProxyCidr private constructor(
    private val network: ByteArray,
    private val prefixLength: Int,
) {
    fun contains(address: InetAddress): Boolean {
        val bytes = canonicalize(address).address
        if (bytes.size != network.size) return false

        val fullBytes = prefixLength / BITS_PER_BYTE
        val remainingBits = prefixLength % BITS_PER_BYTE
        for (index in 0 until fullBytes) {
            if (network[index] != bytes[index]) return false
        }
        if (remainingBits == 0) return true

        val mask = (0xff shl (BITS_PER_BYTE - remainingBits)) and 0xff
        return (network[fullBytes].toInt() and mask) == (bytes[fullBytes].toInt() and mask)
    }

    companion object {
        private const val BITS_PER_BYTE = 8

        fun parse(value: String): TrustedProxyCidr {
            val parts = value.split('/', limit = 2)
            require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                "Invalid trusted proxy CIDR: $value"
            }
            val address = parseIpLiteral(parts[0]) ?: throw IllegalArgumentException("Invalid trusted proxy CIDR: $value")
            val prefixLength = parts[1].toIntOrNull() ?: throw IllegalArgumentException("Invalid trusted proxy CIDR: $value")
            require(prefixLength in 0..address.address.size * BITS_PER_BYTE) {
                "Invalid trusted proxy CIDR prefix: $value"
            }
            return TrustedProxyCidr(address.address, prefixLength)
        }

        fun parseIpLiteral(value: String): InetAddress? {
            val candidate = value.trim()
            if (candidate.isEmpty() || candidate.contains('%')) return null
            val isIpv4 = IPV4_LITERAL.matches(candidate)
            val isIpv6 = candidate.contains(':') && IPV6_LITERAL.matches(candidate)
            if (!isIpv4 && !isIpv6) return null
            if (isIpv4 && candidate.split('.').any { it.toIntOrNull() !in 0..255 }) return null

            val parsed = runCatching { InetAddress.getByName(candidate) }.getOrNull() ?: return null
            if ((isIpv4 && parsed !is Inet4Address) || (isIpv6 && parsed !is Inet6Address)) return null
            return canonicalize(parsed)
        }

        fun canonicalize(address: InetAddress): InetAddress {
            val bytes = address.address
            val isIpv4Mapped = bytes.size == 16 &&
                bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
            return if (isIpv4Mapped) InetAddress.getByAddress(bytes.copyOfRange(12, 16)) else address
        }

        fun canonicalText(address: InetAddress): String = canonicalize(address).hostAddress.substringBefore('%').lowercase()

        private val IPV4_LITERAL = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        private val IPV6_LITERAL = Regex("[0-9A-Fa-f:.]+")
    }
}
