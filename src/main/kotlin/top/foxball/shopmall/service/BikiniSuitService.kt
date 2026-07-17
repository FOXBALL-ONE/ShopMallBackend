package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.BikiniSuit

/**
 * 比基尼（上下分体式泳衣）商品服务，区分前台上架数据与后台完整数据。
 *
 * 写操作通过 [tagIds] 绑定商品标签；删除为软删除，以保留评价和未来的订单关联。
 */
interface BikiniSuitService {
    /** 按创建时间倒序返回所有已上架比基尼。 */
    fun listPublished(): List<BikiniSuit>

    /** 查询一件已上架比基尼；不存在或未上架时返回 `null`。 */
    fun getPublished(id: Long): BikiniSuit?

    /** 返回后台可管理的全部比基尼，包括已下架的。 */
    fun listForAdmin(): List<BikiniSuit>

    /** 查询后台可见的指定比基尼；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): BikiniSuit?

    /** 创建一件比基尼并绑定指定的标签。 */
    fun create(source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit

    /** 更新比基尼的字段与标签；目标不存在时返回 `null`。 */
    fun update(id: Long, source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
