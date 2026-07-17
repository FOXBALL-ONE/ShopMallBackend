package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.CustomerReview
import top.foxball.shopmall.entity.jdbc.ReviewStatus

/** 商品评价服务，负责前台展示、客户编辑、后台审核及商品评分同步。 */
interface CustomerReviewService {
    /** 按创建时间倒序返回商品已审核通过的评价。 */
    fun listPublishedByProduct(productId: Long): List<CustomerReview>

    /** 查询已审核通过的评价；不存在或不可见时返回 `null`。 */
    fun getPublished(id: Long): CustomerReview?

    /** 按创建时间倒序返回后台可管理的全部评价。 */
    fun listForAdmin(): List<CustomerReview>

    /** 查询后台可见的指定评价；不存在时返回 `null`。 */
    fun getForAdmin(id: Long): CustomerReview?

    /** 为已上架商品创建待审核评价；商品不可用时返回 `null`。 */
    fun create(customerId: Long, productId: Long, source: CustomerReview): CustomerReview?

    /** 客户更新自己的评价，并将其重新置为待审核状态。 */
    fun updateByCustomer(id: Long, customerId: Long, source: CustomerReview): CustomerReview?

    /** 审核评价并按需更新购买认证和商家回复，同时重算商品评分。 */
    fun moderate(
        id: Long,
        status: ReviewStatus,
        verifiedPurchase: Boolean?,
        merchantReply: String?,
    ): CustomerReview?

    /** 删除评价；非管理员只能删除自己的评价，目标不存在时返回 `null`。 */
    fun delete(id: Long, customerId: Long, isAdmin: Boolean): Boolean?
}
