package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Dress

/**
 * 连衣裙商品服务，区分前台上架数据与后台完整数据。
 *
 * 写操作通过 [tagIds] 绑定商品标签；删除为软删除，以保留评价和未来的订单关联。
 */
interface DressService {
    /** 按创建时间倒序返回所有已上架连衣裙。 */
    fun listPublished(): List<Dress>

    /** 查询一件已上架连衣裙；不存在或未上架时返回 `null`。 */
    fun getPublished(id: Long): Dress?

    /** 返回后台可管理的全部连衣裙，包括已下架的。 */
    fun listForAdmin(): List<Dress>

    /** 查询后台可见的指定连衣裙；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): Dress?

    /** 创建一件连衣裙并绑定指定的标签。 */
    fun create(source: Dress, tagIds: Collection<Long>): Dress

    /** 更新连衣裙的字段与标签；目标不存在时返回 `null`。 */
    fun update(id: Long, source: Dress, tagIds: Collection<Long>): Dress?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
