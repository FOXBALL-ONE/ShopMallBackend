package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Product

/** 跨品类的多态商品读取接口，供前台聚合展示所有上架商品。 */
interface ProductService {
    /** 按前台展示顺序返回所有已上架商品。 */
    fun listPublished(): List<Product>

    /** 查询一件已上架商品；不存在或未上架时返回 `null`。 */
    fun getPublished(id: Long): Product?
}
