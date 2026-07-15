package top.foxball.shopmall

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/** Spring Boot 应用入口；组件扫描根为 `top.foxball.shopmall`。 */
@SpringBootApplication
class ShopMallApplication

/** 启动嵌入式 Web 服务。 */
fun main(args: Array<String>) {
    runApplication<ShopMallApplication>(*args)
}
