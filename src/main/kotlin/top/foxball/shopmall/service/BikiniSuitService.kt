package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.BikiniSuit

interface BikiniSuitService {
    fun listPublished(): List<BikiniSuit>

    fun getPublished(id: Long): BikiniSuit?

    fun listForAdmin(): List<BikiniSuit>

    fun getForAdmin(id: Long): BikiniSuit?

    fun create(source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit

    fun update(id: Long, source: BikiniSuit, tagIds: Collection<Long>): BikiniSuit?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
