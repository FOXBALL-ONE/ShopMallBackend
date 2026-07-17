package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.OnePieceSuit

/**
 * 一件式泳衣商品服务，区分前台上架数据与后台完整数据。
 *
 * 写操作通过 [tagIds] 绑定商品标签；删除为软删除，以保留评价和未来的订单关联。
 */
interface OnePieceSuitService {
    /** 按创建时间倒序返回所有已上架一件式泳衣。 */
    fun listPublished(): List<OnePieceSuit>

    /** 查询已上架的一件式泳衣；不存在或未上架时返回 `null`。 */
    fun getPublished(id: Long): OnePieceSuit?

    /** 返回后台可管理的全部一件式泳衣，包括已下架的。 */
    fun listForAdmin(): List<OnePieceSuit>

    /** 查询后台可见的指定一件式泳衣；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): OnePieceSuit?

    /** 创建一件式泳衣并绑定指定的标签。 */
    fun create(source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit

    /** 更新一件式泳衣的字段与标签；目标不存在时返回 `null`。 */
    fun update(id: Long, source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit?

    /** 将商品标记为已删除，保留未来订单和审计所需的数据。 */
    fun delete(id: Long): Boolean
}
