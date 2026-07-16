package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.OnePieceSuit

interface OnePieceSuitService {
    fun listPublished(): List<OnePieceSuit>

    fun getPublished(id: Long): OnePieceSuit?

    fun listForAdmin(): List<OnePieceSuit>

    fun getForAdmin(id: Long): OnePieceSuit?

    fun create(source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit

    fun update(id: Long, source: OnePieceSuit, tagIds: Collection<Long>): OnePieceSuit?

    /** 将商品标记为已删除，保留未来订单和审计所需的数据。 */
    fun delete(id: Long): Boolean
}
