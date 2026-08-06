package top.foxball.shopmall.service

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import top.foxball.shopmall.entity.jdbc.BikiniSuit
import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.Dress
import top.foxball.shopmall.entity.jdbc.OnePieceSuit
import top.foxball.shopmall.entity.jdbc.Product
import top.foxball.shopmall.entity.jdbc.ReviewStatus
import top.foxball.shopmall.entity.jdbc.Tag
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.BikiniSuitRepository
import top.foxball.shopmall.repository.CustomerReviewRepository
import top.foxball.shopmall.repository.DressRepository
import top.foxball.shopmall.repository.OnePieceSuitRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.impl.BikiniSuitServiceImpl
import top.foxball.shopmall.service.impl.CustomerReviewServiceImpl
import top.foxball.shopmall.service.impl.DressServiceImpl
import top.foxball.shopmall.service.impl.OnePieceSuitServiceImpl
import top.foxball.shopmall.service.impl.TagServiceImpl
import java.math.BigDecimal

class CatalogServiceImplTest {
    @Test
    fun `creating a bikini suit assigns only the requested persisted tags`() {
        val bikiniSuitRepository = mock(BikiniSuitRepository::class.java)
        val tagRepository = mock(TagRepository::class.java)
        val service = BikiniSuitServiceImpl(bikiniSuitRepository, tagRepository)
        val tag = Tag(id = 7, name = "High Waist")
        val bikiniSuit = BikiniSuit().apply {
            name = "Ocean Set"
            color = "Blue"
            price = BigDecimal("89.00")
            salesVolume = 27
        }

        `when`(tagRepository.findAllById(listOf(7))).thenReturn(listOf(tag))
        `when`(bikiniSuitRepository.save(any(BikiniSuit::class.java))).thenAnswer { it.getArgument(0) }

        val saved = service.create(bikiniSuit, listOf(7))

        assertEquals(setOf(tag), saved.tags)
        assertEquals(0, saved.salesVolume)
    }

    @Test
    fun `catalog metadata update preserves live stock sales and status`() {
        val dressRepository = mock(DressRepository::class.java)
        val tagRepository = mock(TagRepository::class.java)
        val service = DressServiceImpl(dressRepository, tagRepository)
        val target = Dress(size = Dress.Size.M).apply {
            id = 12L
            name = "Before"
            warehouseVolume = 8
            salesVolume = 14
            status = Product.Status.INACTIVE
        }
        val source = Dress(size = Dress.Size.L).apply {
            name = "After"
            warehouseVolume = 999
            salesVolume = 999
            status = Product.Status.ACTIVE
        }
        `when`(dressRepository.findById(12L)).thenReturn(Optional.of(target))
        `when`(tagRepository.findAllById(emptyList<Long>())).thenReturn(emptyList())

        val updated = service.update(12L, source, emptyList())

        assertEquals("After", updated?.name)
        assertEquals(8, updated?.warehouseVolume)
        assertEquals(14, updated?.salesVolume)
        assertEquals(Product.Status.INACTIVE, updated?.status)
    }

    @Test
    fun `deleted catalog product must be restored before metadata update`() {
        val dressRepository = mock(DressRepository::class.java)
        val tagRepository = mock(TagRepository::class.java)
        val service = DressServiceImpl(dressRepository, tagRepository)
        val target = Dress(size = Dress.Size.M).apply {
            id = 12L
            status = Product.Status.DELETED
        }
        `when`(dressRepository.findById(12L)).thenReturn(Optional.of(target))

        assertFailsWith<ParamErrorException> {
            service.update(12L, Dress(size = Dress.Size.L), emptyList())
        }
    }

    @Test
    fun `inactive tag cannot be newly assigned to a product`() {
        val bikiniSuitRepository = mock(BikiniSuitRepository::class.java)
        val tagRepository = mock(TagRepository::class.java)
        val service = BikiniSuitServiceImpl(bikiniSuitRepository, tagRepository)
        val tag = Tag(id = 7, name = "Archived", active = false)
        val bikiniSuit = BikiniSuit().apply {
            name = "Ocean Set"
            color = "Blue"
            price = BigDecimal("89.00")
        }
        `when`(tagRepository.findAllById(listOf(7))).thenReturn(listOf(tag))

        assertFailsWith<ParamErrorException> { service.create(bikiniSuit, listOf(7)) }
    }

    @Test
    fun `editing a review sends it back to moderation`() {
        val reviewRepository = mock(CustomerReviewRepository::class.java)
        val productRepository = mock(ProductRepository::class.java)
        val service = CustomerReviewServiceImpl(reviewRepository, productRepository)
        val review = CustomerReview(
            customerId = 42,
            rating = 4,
            content = "Original review",
            status = ReviewStatus.APPROVED,
        )

        `when`(reviewRepository.findById(9)).thenReturn(Optional.of(review))

        val updated = service.updateByCustomer(
            9,
            42,
            CustomerReview(rating = 5, content = "Updated review"),
        )

        assertEquals(ReviewStatus.PENDING, updated?.status)
        assertEquals(5, updated?.rating)
        assertEquals("Updated review", updated?.content)
    }

    @Test
    fun `creating a one piece suit assigns requested persisted tags`() {
        val onePieceSuitRepository = mock(OnePieceSuitRepository::class.java)
        val tagRepository = mock(TagRepository::class.java)
        val service = OnePieceSuitServiceImpl(onePieceSuitRepository, tagRepository)
        val tag = Tag(id = 8, name = "Tummy Control")
        val onePieceSuit = OnePieceSuit(size = OnePieceSuit.Size.M).apply {
            name = "Sculpting One Piece"
            color = "Black"
            price = BigDecimal("99.00")
        }

        `when`(tagRepository.findAllById(listOf(8))).thenReturn(listOf(tag))
        `when`(onePieceSuitRepository.save(any(OnePieceSuit::class.java))).thenAnswer { it.getArgument(0) }

        val saved = service.create(onePieceSuit, listOf(8))

        assertEquals(setOf(tag), saved.tags)
    }

    @Test
    fun `deleting a tag still assigned to a product is rejected`() {
        val tagRepository = mock(TagRepository::class.java)
        val productRepository = mock(ProductRepository::class.java)
        val service = TagServiceImpl(tagRepository, productRepository)
        val tag = Tag(id = 3, name = "Sculpting")

        `when`(tagRepository.findById(3)).thenReturn(Optional.of(tag))
        `when`(productRepository.existsByTags_Id(3)).thenReturn(true)

        assertFailsWith<ParamErrorException> { service.delete(3) }
    }
}
