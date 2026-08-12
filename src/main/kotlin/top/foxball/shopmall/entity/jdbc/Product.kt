package top.foxball.shopmall.entity.jdbc

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.validator.constraints.URL
import java.math.BigDecimal
import java.time.LocalDateTime
import jakarta.validation.constraints.Size as ValidationSize

/**
 * 商品款式（SPU）。
 *
 * 商品类型由 [productType] 定义，类型私有字段以 [attributes] 保存；所有可售规格、USD 价格和库存均由
 * [ProductVariant] 持有。本实体不使用 JPA 继承。
 */
@Entity
@DynamicUpdate
@Table(
    name = "products",
    indexes = [
        Index(name = "idx_products_type_status", columnList = "product_type_id, status"),
        Index(name = "idx_products_category_status", columnList = "category_id, status"),
        Index(name = "idx_products_deleted_at", columnList = "deleted_at"),
        Index(name = "idx_products_created_at", columnList = "created_at"),
    ],
)
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    /** 数据规则和私有属性定义来源。创建后不能直接改为另一种类型。 */
    @field:NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false, updatable = false)
    var productType: ProductType? = null,

    /** 前台导航使用的主分类。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    var category: ProductCategory? = null,

    @field:NotBlank
    @field:ValidationSize(max = 200)
    @Column(nullable = false, length = 200)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.INACTIVE,

    /** 商品卖点，按顺序展示。 */
    @field:ValidationSize(max = 10)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_highlights",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_highlights_product_id", columnList = "product_id")],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "highlight", nullable = false, length = 255)
    var highlights: MutableList<String> = mutableListOf(),

    /** 面料组成及百分比；填写时总和必须为 100%。 */
    @field:ValidationSize(max = 10)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_materials",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_materials_product_id", columnList = "product_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_products_materials_product_name",
                columnNames = ["product_id", "material_name"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    var materials: MutableList<@Valid MaterialComponent> = mutableListOf(),

    /** 商品类型私有属性；显示名和值域由 ProductAttributeDefinition 提供。 */
    @field:ValidationSize(max = 30)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_attributes",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_attributes_product_id", columnList = "product_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_products_attributes_product_code",
                columnNames = ["product_id", "attribute_code"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    var attributes: MutableList<@Valid ProductAttribute> = mutableListOf(),

    /** 商品图片及无障碍说明；有图时必须且只能有一张主图。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_images",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_images_product_id", columnList = "product_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_products_images_product_url",
                columnNames = ["product_id", "image_url"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    var images: MutableList<@Valid ProductImage> = mutableListOf(),

    @field:ValidationSize(max = 255)
    @Column(name = "fit_sense", length = 255)
    var fitSense: String? = null,

    @field:ValidationSize(max = 4_000)
    @Column(length = 4_000)
    var description: String? = null,

    /** 设计细节和附加配件，按顺序展示。 */
    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_design_extras",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_design_extras_product_id", columnList = "product_id")],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "detail", nullable = false, length = 255)
    var designAndExtras: MutableList<String> = mutableListOf(),

    @field:ValidationSize(max = 12)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "products_care_instructions",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        indexes = [Index(name = "idx_products_care_instructions_product_id", columnList = "product_id")],
    )
    @OrderColumn(name = "sort_order")
    var careInstructions: MutableList<@Valid CareInstruction> = mutableListOf(),

    @ManyToMany(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @JoinTable(
        name = "products_tags",
        joinColumns = [JoinColumn(name = "product_id", nullable = false)],
        inverseJoinColumns = [JoinColumn(name = "tag_id", nullable = false)],
        indexes = [Index(name = "idx_products_tags_tag_id", columnList = "tag_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_products_tags_product_tag",
                columnNames = ["product_id", "tag_id"],
            ),
        ],
    )
    var tags: MutableSet<Tag> = linkedSetOf(),

    @field:DecimalMin("0.0")
    @field:DecimalMax("5.0")
    @Column
    var score: Float? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,
) {
    @get:JsonIgnore
    @OneToMany(
        mappedBy = "product",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @BatchSize(size = 100)
    var variants: MutableList<@Valid ProductVariant> = mutableListOf()
        protected set

    @get:JsonIgnore
    @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
    var customerReviews: MutableList<CustomerReview> = mutableListOf()

    fun addVariant(variant: ProductVariant) {
        variant.product?.takeIf { it !== this }?.removeVariant(variant)
        if (variant !in variants) variants += variant
        variant.product = this
    }

    fun removeVariant(variant: ProductVariant) {
        if (variants.remove(variant)) variant.product = null
    }

    @get:JsonIgnore
    @get:Transient
    @get:AssertTrue(message = "面料占比合计必须为 100%")
    val materialPercentageValid: Boolean
        get() = materials.isEmpty() ||
            materials.fold(BigDecimal.ZERO) { total, item -> total + item.percentage }
                .compareTo(BigDecimal("100.00")) == 0

    @get:JsonIgnore
    @get:Transient
    @get:AssertTrue(message = "有商品图片时必须且只能指定一张主图")
    val primaryImageConfigurationValid: Boolean
        get() = images.isEmpty() || images.count(ProductImage::primary) == 1

    @get:JsonIgnore
    @get:Transient
    @get:AssertTrue(message = "商品至少需要一个 SKU")
    val variantsPresent: Boolean
        get() = variants.isNotEmpty()

    enum class Status {
        ACTIVE,
        INACTIVE,
    }
}

/** 单个可销售 SKU；库存、销量和 USD 价格的最小业务单位。 */
@Entity
@DynamicUpdate
@Table(
    name = "product_variants",
    indexes = [
        Index(name = "idx_product_variants_product_status", columnList = "product_id, status"),
        Index(name = "idx_product_variants_status_stock", columnList = "status, warehouse_volume"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_product_variants_sku", columnNames = ["sku"]),
        UniqueConstraint(
            name = "uk_product_variants_product_options",
            columnNames = ["product_id", "option_signature"],
        ),
    ],
)
class ProductVariant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    var product: Product? = null,

    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$")
    @Column(nullable = false, updatable = false, length = 64)
    var sku: String = "",

    @field:ValidationSize(max = 30)
    @Column(name = "size_value", length = 30)
    var size: String? = null,

    @field:NotBlank
    @field:ValidationSize(max = 50)
    @Column(name = "color_value", nullable = false, length = 50)
    var color: String = "",

    @field:DecimalMin("0.00", inclusive = false)
    @field:Digits(integer = 8, fraction = 2)
    @Column(nullable = false, precision = 10, scale = 2)
    var price: BigDecimal = BigDecimal.ZERO,

    @field:Min(0)
    @Column(name = "warehouse_volume", nullable = false)
    var warehouseVolume: Int = 0,

    @field:Min(0)
    @Column(name = "sales_volume", nullable = false)
    var salesVolume: Long = 0,

    @field:Min(0)
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: Status = Status.INACTIVE,

    /** SKU 私有规格，例如比基尼 top_size 与 bottom_size。 */
    @field:ValidationSize(max = 20)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "product_variant_attributes",
        joinColumns = [JoinColumn(name = "variant_id", nullable = false)],
        indexes = [Index(name = "idx_variant_attributes_variant_id", columnList = "variant_id")],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_variant_attributes_variant_code",
                columnNames = ["variant_id", "attribute_code"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    var attributes: MutableList<@Valid ProductVariantAttribute> = mutableListOf(),

    /** 由规范化 size、color 和 Variant 属性生成的稳定规格签名。 */
    @field:NotBlank
    @field:ValidationSize(max = 512)
    @Column(name = "option_signature", nullable = false, length = 512)
    var optionSignature: String = "",

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,
) {
    enum class Status {
        ACTIVE,
        INACTIVE,
    }
}

@Embeddable
data class ProductAttribute(
    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9_]*$")
    @Column(name = "attribute_code", nullable = false, length = 64)
    var code: String = "",

    @field:NotBlank
    @field:ValidationSize(max = 1_000)
    @Column(name = "attribute_value", nullable = false, length = 1_000)
    var value: String = "",
)

@Embeddable
data class ProductVariantAttribute(
    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9_]*$")
    @Column(name = "attribute_code", nullable = false, length = 64)
    var code: String = "",

    @field:NotBlank
    @field:ValidationSize(max = 1_000)
    @Column(name = "attribute_value", nullable = false, length = 1_000)
    var value: String = "",
)

@Embeddable
data class MaterialComponent(
    @field:NotBlank
    @field:ValidationSize(max = 100)
    @Column(name = "material_name", nullable = false, length = 100)
    var name: String = "",

    @field:DecimalMin("0.00", inclusive = false)
    @field:DecimalMax("100.00")
    @field:Digits(integer = 3, fraction = 2)
    @Column(nullable = false, precision = 5, scale = 2)
    var percentage: BigDecimal = BigDecimal.ZERO,
)

@Embeddable
data class ProductImage(
    @field:NotBlank
    @field:ValidationSize(max = 512)
    @field:URL
    @Column(name = "image_url", nullable = false, length = 512)
    var url: String = "",

    @field:ValidationSize(max = 255)
    @Column(name = "alt_text", length = 255)
    var altText: String? = null,

    @Column(name = "is_primary", nullable = false)
    var primary: Boolean = false,
)

@Embeddable
data class CareInstruction(
    @field:NotBlank
    @field:ValidationSize(max = 255)
    @Column(name = "instruction", nullable = false, length = 255)
    var text: String = "",
)
