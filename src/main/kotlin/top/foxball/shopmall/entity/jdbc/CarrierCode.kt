package top.foxball.shopmall.entity.jdbc

enum class CarrierCode(val pathValue: String) {
    MANUAL("manual"),
    FOUR_PX("4px"),
    YUN_EXPRESS("yunexpress"),
    TRACK17("17track"),
    ;

    companion object {
        fun fromPath(value: String): CarrierCode? =
            entries.firstOrNull {
                it.pathValue.equals(value, ignoreCase = true) || it.name.equals(value, ignoreCase = true)
            }
    }
}
