package top.foxball.shopmall.entity.jdbc

import org.junit.jupiter.api.Test
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** 验证统一商品模型可以独立完成 Hibernate 元数据构建和建表。 */
@SpringBootTest(
    classes = [ExperimentalProductMappingTest.TestApplication::class],
    properties = ["spring.data.jpa.repositories.enabled=false"],
)
@ActiveProfiles("test")
class ExperimentalProductMappingTest {
    @Test
    fun contextLoads() {
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(
        basePackages = [
            "top.foxball.shopmall.entity.jdbc",
        ],
    )
    class TestApplication
}
