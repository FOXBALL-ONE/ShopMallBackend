package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.BatchSize
import org.hibernate.annotations.DynamicUpdate
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime
import jakarta.validation.constraints.Size as ValidationSize

/** 可配置的商品类型；新增类型不需要新增商品实体。 */
@Entity
@DynamicUpdate
@Table(
    name = "product_types",
    indexes = [Index(name = "idx_product_types_active_order", columnList = "active, display_order")],
    uniqueConstraints = [UniqueConstraint(name = "uk_product_types_code", columnNames = ["code"])],
)
class ProductType(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[A-Z][A-Z0-9_]*$")
    @Column(nullable = false, updatable = false, length = 64)
    var code: String = "",

    @field:NotBlank
    @field:ValidationSize(max = 100)
    @Column(nullable = false, length = 100)
    var name: String = "",

    @field:ValidationSize(max = 1_000)
    @Column(length = 1_000)
    var description: String? = null,

    @Column(nullable = false)
    var active: Boolean = true,

    @field:Min(0)
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

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
    @OneToMany(
        mappedBy = "productType",
        fetch = FetchType.LAZY,
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
    )
    @BatchSize(size = 100)
    var attributeDefinitions: MutableList<@Valid ProductAttributeDefinition> = mutableListOf()
}

/** 某一商品类型可使用的属性定义。 */
@Entity
@DynamicUpdate
@Table(
    name = "product_attribute_definitions",
    indexes = [
        Index(name = "idx_attribute_definitions_type_scope", columnList = "product_type_id, scope"),
        Index(name = "idx_attribute_definitions_active_order", columnList = "active, display_order"),
    ],
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_attribute_definitions_type_code",
            columnNames = ["product_type_id", "code"],
        ),
    ],
)
class ProductAttributeDefinition(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @field:NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_type_id", nullable = false, updatable = false)
    var productType: ProductType? = null,

    @field:NotBlank
    @field:ValidationSize(max = 64)
    @field:Pattern(regexp = "^[a-z][a-z0-9_]*$")
    @Column(nullable = false, updatable = false, length = 64)
    var code: String = "",

    @field:NotBlank
    @field:ValidationSize(max = 100)
    @Column(nullable = false, length = 100)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var scope: AttributeScope = AttributeScope.PRODUCT,

    @Enumerated(EnumType.STRING)
    @Column(name = "value_type", nullable = false, length = 16)
    var valueType: AttributeValueType = AttributeValueType.STRING,

    @Column(nullable = false)
    var required: Boolean = false,

    @Column(nullable = false)
    var filterable: Boolean = false,

    @field:Min(1)
    @Column(name = "max_length")
    var maxLength: Int? = null,

    @field:Min(0)
    @Column(name = "display_order", nullable = false)
    var displayOrder: Int = 0,

    @Column(nullable = false)
    var active: Boolean = true,

    @field:ValidationSize(max = 100)
    @ElementCollection(fetch = FetchType.LAZY)
    @BatchSize(size = 100)
    @CollectionTable(
        name = "product_attribute_allowed_values",
        joinColumns = [JoinColumn(name = "definition_id", nullable = false)],
        uniqueConstraints = [
            UniqueConstraint(
                name = "uk_attribute_allowed_values_definition_value",
                columnNames = ["definition_id", "allowed_value"],
            ),
        ],
    )
    @OrderColumn(name = "sort_order")
    @Column(name = "allowed_value", nullable = false, length = 100)
    var allowedValues: MutableList<String> = mutableListOf(),

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

enum class AttributeScope {
    PRODUCT,
    VARIANT,
}

enum class AttributeValueType {
    STRING,
    BOOLEAN,
    INTEGER,
    DECIMAL,
    ENUM,
}
