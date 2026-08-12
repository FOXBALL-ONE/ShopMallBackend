package top.foxball.shopmall.service

import org.springframework.stereotype.Service
import top.foxball.shopmall.entity.jdbc.ProductVariantAttribute
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Service
class OptionSignatureService {
    fun generate(size: String?, color: String, attributes: Collection<ProductVariantAttribute>): String {
        val canonical = buildString {
            appendPart("size", normalize(size))
            appendPart("color", normalize(color))
            attributes.sortedBy { it.code.trim().lowercase() }.forEach {
                appendPart(it.code.trim().lowercase(), normalize(it.value))
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun StringBuilder.appendPart(code: String, value: String) {
        append(code.length).append(':').append(code).append(value.length).append(':').append(value)
    }

    private fun normalize(value: String?): String = value?.trim()?.uppercase().orEmpty()
}
