package top.foxball.shopmall.service

import top.foxball.shopmall.entity.jdbc.Product

/** 跨品类的多态商品读取接口，供前台聚合展示所有上架商品。 */
interface ProductService {
    fun listPublished(): List<Product>

    fun getPublished(id: Long): Product?
}
