package top.foxball.shopmall.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.entity.jdbc.CarrierCode
import top.foxball.shopmall.entity.jdbc.TrackSource
import top.foxball.shopmall.handler.ShipmentNotFoundException
import top.foxball.shopmall.handler.WebhookPayloadTooLargeException
import top.foxball.shopmall.logistics.CarrierRegistry
import top.foxball.shopmall.service.ShipmentService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.io.ByteArrayOutputStream

/**
 * @folder 物流/Webhook
 */
@RestController
class LogisticsWebhookController(
    private val carrierRegistry: CarrierRegistry,
    private val shipmentService: ShipmentService,
    private val properties: LogisticsProperties,
    private val builder: ResponseBuilder,
) {
    /**
     * @api 接收承运商物流回调
     * @param carrier 承运商代码
     */
    @PostMapping("/api/logistics/webhook/{carrier}")
    fun webhook(
        @PathVariable("carrier") carrier: String,
        request: HttpServletRequest,
    ): ResponseEntity<Response> {
        val carrierCode = CarrierCode.fromPath(carrier)
            ?.takeIf { it != CarrierCode.MANUAL }
            ?: throw ShipmentNotFoundException()
        val adapter = carrierRegistry.find(carrierCode)
            ?.takeIf { it.capabilities.webhook }
            ?: throw ShipmentNotFoundException()
        val payload = readLimited(request)
        val headers = request.headerNames.asSequence().associateWith { name ->
            request.getHeaders(name).asSequence().toList()
        }
        adapter.parseWebhook(payload, headers)
            .sortedWith(compareBy({ it.occurredAt }, { it.carrierEventId }))
            .forEach { shipmentService.handleTrackingEvent(carrierCode, it, TrackSource.WEBHOOK) }
        return builder.ok().build()
    }

    private fun readLimited(request: HttpServletRequest): ByteArray {
        val limit = properties.webhookMaxBodyBytes
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        request.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() + read > limit) throw WebhookPayloadTooLargeException()
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }
}
