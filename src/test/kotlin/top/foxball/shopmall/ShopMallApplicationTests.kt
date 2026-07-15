package top.foxball.shopmall

import org.junit.jupiter.api.Test
import org.springframework.test.context.ActiveProfiles
import org.springframework.boot.test.context.SpringBootTest

/** 使用隔离测试配置启动完整 Spring 上下文，验证 Bean 装配与数据库映射。 */
@SpringBootTest
@ActiveProfiles("test")
class ShopMallApplicationTests {
    
    @Test
    fun contextLoads() {
    }
    
}
