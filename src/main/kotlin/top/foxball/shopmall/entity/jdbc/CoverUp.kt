package top.foxball.shopmall.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.PrimaryKeyJoinColumn
import jakarta.persistence.Table
import jakarta.validation.constraints.Size as ValidationSize

/**
 * 可销售的罩衫 SKU（海滩罩衫、防晒衫、外搭裙等）。
 *
 * 公共属性继承自 [Product]，本类仅描述罩衫特有的款式、透视度与尺码。
 * 罩衫通常宽松，常以均码销售；合体裁剪的罩衫可使用标准尺码。
 */
@Entity
@Table(name = "cover_ups")
@DiscriminatorValue("COVER_UP")
@PrimaryKeyJoinColumn(name = "product_id")
class CoverUp(
    /** 罩衫款式。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "cover_up_style", length = 16)
    var style: CoverUpStyle? = null,

    /** 透视程度。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "sheer_level", length = 16)
    var sheerLevel: SheerLevel? = null,

    /** 面料说明（自由文本）。 */
    @field:ValidationSize(max = 100)
    @Column(length = 100)
    var fabric: String? = null,

    /** 罩衫尺码；宽松款默认均码，合体款可使用标准尺码。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    var size: Size = Size.ONE_SIZE,
) : Product() {
    /** 常见的罩衫款式。 */
    enum class CoverUpStyle {
        KIMONO,
        TUNIC,
        ROBE,
        PONCHO,
        WRAP,
        DUSTER,
    }

    /** 罩衫面料的透视程度。 */
    enum class SheerLevel {
        SHEER,
        SEMI_SHEER,
        OPAQUE,
    }

    /** 罩衫尺码；含均码与标准尺码。 */
    enum class Size {
        ONE_SIZE,
        XS,
        S,
        M,
        L,
        XL,
        XXL,
    }
}
