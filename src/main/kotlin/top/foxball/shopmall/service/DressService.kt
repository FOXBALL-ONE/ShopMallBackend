package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Dress

interface DressService {
    fun listPublished(): List<Dress>

    fun getPublished(id: Long): Dress?

    fun listForAdmin(): List<Dress>

    fun getForAdmin(id: Long): Dress?

    fun create(source: Dress, tagIds: Collection<Long>): Dress

    fun update(id: Long, source: Dress, tagIds: Collection<Long>): Dress?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
