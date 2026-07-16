package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.CoverUp

interface CoverUpService {
    fun listPublished(): List<CoverUp>

    fun getPublished(id: Long): CoverUp?

    fun listForAdmin(): List<CoverUp>

    fun getForAdmin(id: Long): CoverUp?

    fun create(source: CoverUp, tagIds: Collection<Long>): CoverUp

    fun update(id: Long, source: CoverUp, tagIds: Collection<Long>): CoverUp?

    /** 下架商品而非物理删除，以保留评价和未来的订单关联。 */
    fun delete(id: Long): Boolean
}
