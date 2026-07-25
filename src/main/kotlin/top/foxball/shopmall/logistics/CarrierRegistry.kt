package top.foxball.shopmall.logistics

import org.springframework.stereotype.Component
import top.foxball.shopmall.config.LogisticsProperties
import top.foxball.shopmall.entity.jdbc.CarrierCode

@Component
class CarrierRegistry(
    carriers: List<Carrier>,
    properties: LogisticsProperties,
) {
    private val byCode: Map<CarrierCode, Carrier> = carriers
        .groupBy { it.code }
        .mapValues { (code, beans) ->
            require(beans.size == 1) { "Carrier $code must have exactly one adapter; found ${beans.size}" }
            beans.single()
        }

    init {
        properties.carriers.filterValues { it.enabled }.keys.forEach { configuredName ->
            val code = CarrierCode.fromPath(configuredName)
                ?: error("Unknown enabled carrier configuration: $configuredName")
            require(byCode.containsKey(code)) { "Enabled carrier $configuredName has no adapter bean" }
        }
    }

    fun find(code: CarrierCode): Carrier? = byCode[code]

    fun require(code: CarrierCode): Carrier = byCode[code]
        ?: error("Carrier adapter is not registered: $code")
}
