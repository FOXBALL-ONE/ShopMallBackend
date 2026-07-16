package top.foxball.shopmall.repository

import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CoverUp
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 验证 JOINED 继承体系的多态持久化与鉴别列写入：四种品类各自落库后，
 * 经基类仓库聚合读取应还原为对应的具体子类型，且 products.product_type 鉴别列被正确填充。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProductInheritanceIntegrationTest {
    @Autowired
    private lateinit var productRepository: ProductRepository

    @Autowired
    private lateinit var bikiniSuitRepository: BikiniSuitRepository

    @Autowired
    private lateinit var onePieceSuitRepository: OnePieceSuitRepository

    @Autowired
    private lateinit var dressRepository: DressRepository

    @Autowired
    private lateinit var coverUpRepository: CoverUpRepository

    @Autowired
    private lateinit var entityManager: EntityManager

    @Test
    fun `persists each subtype and reads them back polymorphically`() {
        bikiniSuitRepository.save(BikiniSuit().apply {
            name = "Bikini"; color = "Blue"; price = BigDecimal("10.00"); topSize = BikiniSuit.Size.S
        })
        onePieceSuitRepository.save(OnePieceSuit().apply {
            name = "One Piece"; color = "Black"; price = BigDecimal("20.00"); size = OnePieceSuit.Size.M
        })
        dressRepository.save(Dress().apply {
            name = "Dress"; color = "Sand"; price = BigDecimal("30.00"); size = Dress.Size.M
        })
        coverUpRepository.save(CoverUp().apply {
            name = "Cover Up"; color = "Ivory"; price = BigDecimal("40.00")
        })
        entityManager.flush()
        entityManager.clear()

        val all = productRepository.findAll()

        assertEquals(4, all.size)
        val concreteTypes = all.map { it::class }.toSet()
        assertTrue(BikiniSuit::class in concreteTypes, "缺少比基尼子类型")
        assertTrue(OnePieceSuit::class in concreteTypes, "缺少一件式泳衣子类型")
        assertTrue(Dress::class in concreteTypes, "缺少连衣裙子类型")
        assertTrue(CoverUp::class in concreteTypes, "缺少罩衫子类型")
    }

    @Test
    fun `populates the product_type discriminator for each subtype`() {
        bikiniSuitRepository.save(BikiniSuit().apply {
            name = "Bikini"; color = "Blue"; price = BigDecimal("10.00")
        })
        dressRepository.save(Dress().apply {
            name = "Dress"; color = "Sand"; price = BigDecimal("30.00"); size = Dress.Size.M
        })
        entityManager.flush()

        @Suppress("UNCHECKED_CAST")
        val discriminatorByProductType: Map<String, Int> = entityManager
            .createNativeQuery("SELECT product_type, COUNT(*) FROM products GROUP BY product_type")
            .resultList
            .associate { row ->
                val r = row as Array<Any?>
                (r[0] as String) to (r[1] as Number).toInt()
            }

        assertEquals(1, discriminatorByProductType["BIKINI"])
        assertEquals(1, discriminatorByProductType["DRESS"])
        assertNotNull(discriminatorByProductType["BIKINI"]) { "鉴别列 product_type 未被填充" }
    }
}
