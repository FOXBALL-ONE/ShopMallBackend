# 服装实体类重构说明：统一的 `Product` JOINED 继承体系

> 背景：仿 [cupshe.com](https://www.cupshe.com/) 的跨境泳装/沙滩装小电商。本文档记录把目录从「两个字段重复的并行实体」重构为「统一持久化基类 + 多个子类」的全过程。

## 一、解决的核心问题

重构前的目录结构：

```
BikiniSuit    (bikini_suits)     ← 自己一套 name/price/images/tags/status/reviews…
OnePieceSuit  (one_piece_suits)  ← 又一套完全重复的 name/price/images/tags/status…
```

两个实体**完全独立、字段大量重复**，没有公共基类。而且评价 `CustomerReview` 当时外键到 `bikini_suits`，等于写死了「只能给比基尼打分」。

### 设计目标

引入一个**真正持久化的公共基类**（不是 `@MappedSuperclass`，那种不落表、不能做外键目标）。动机是：

- **后续订单管理 / 库存管理**需要一个统一的 `product_id` 外键来指向任意品类；
- **评价多态化**：`CustomerReview` 指向抽象的 `Product`，而非某个具体类型。

> 关键推论：既然评价要多态指向 `Product`，而评价当前外键到 `bikini_suits`，**泳装必须一起并入新体系**。这与「忽略现有、重新设计」完全一致。

第一批品类：并入现有 `BikiniSuit` / `OnePieceSuit`，新增 `Dress`（连衣裙）、`CoverUp`（罩衫）。

SKU 模型保持现状：每条 `Product` 行 = 一个颜色×尺码变体；`Product(style)→Sku(variant)` 的进一步拆分留作后续。

---

## 二、新结构：`@Inheritance(strategy = JOINED)`

```
Product (abstract, products 表)        ← 公共字段全在这里
│   name / color / price / warehouseVolume / salesVolume
│   status / createdAt / updatedAt
│   images / highlight / designAndExtras / careInstructions  (懒加载集合)
│   tags (多对多) / customerReviews (一对多) / score
│   @DiscriminatorColumn(name = "product_type", length = 31)   ← 鉴别列，Hibernate 自动填
│
├─ BikiniSuit    bikini_suits     仅 topSize / bottomSize
├─ OnePieceSuit  one_piece_suits  size + 版型/结构属性
├─ Dress   (新)  dresses          size/length/silhouette/neckline/sleeveType/fabric
└─ CoverUp (新)  cover_ups        style/sheerLevel/fabric/size(默认 ONE_SIZE)
```

物理存储：

- `products` 表存所有公共列 + `product_type` 鉴别列；
- 每个子类表只存 `product_id`（= `products.id`，既是主键又是外键）加各自特有列；
- 读取时 Hibernate 用 `product_type` 知道该外连哪张子类表、还原成具体子类型。

由此带来的能力：

- 订单 / 库存外键统一指向 `products(id)` —— 任意品类都能挂；
- `productRepository.findAll()` 多态返回四种具体子类型；
- 评价 `CustomerReview.product` 现在能指向任意 `Product`。

---

## 三、按层落地

### 实体层

- **`Product`**（新增，承重基类）：抽象 `@Entity`，`@Inheritance(JOINED)` + `@DiscriminatorColumn`。每个构造参数都给了默认值，让 `Product()` 合法、子类能简洁地 `: Product()` 调用。公共字段、媒体集合、`tags`、`customerReviews`、`score` 全在这里。嵌套 `enum class Status { ACTIVE, INACTIVE, DELETED }`。
- **`BikiniSuit` / `OnePieceSuit`**：从「独立实体」瘦身成「只存特有字段的子类」，加 `@DiscriminatorValue` + `@PrimaryKeyJoinColumn(name = "product_id")`。各自的 `Status` 枚举删除，统一引用 `Product.Status`。
- **`Dress` / `CoverUp`**（新增）：`@DiscriminatorValue("DRESS")` / `@DiscriminatorValue("COVER_UP")`。
- **`SizeRecommendation`**（由 `BikiniSuitSizeRecommendation` 重命名）：字段放宽为可空（`braSizes/bust/waist/hip/underbust/torso` 均可空），让连衣裙只填 `bust/waist/hip` 也能用。

> Kotlin 技巧：`allOpen`/`kotlin-jpa` 已就绪。子类用 `@PrimaryKeyJoinColumn(name = "product_id")` 让共享主键列命名为 `product_id`。`product_type` 鉴别列由 Hibernate 维护，不进构造参数。注意 `Product` 不能是 `sealed`——`allOpen` 会强制 `@Entity` 子类为 `open`，与 sealed 的 final/sealed-subclass 规则冲突。

### Repository 层

- 新增 **`ProductRepository`**（多态查询）：`findAllByStatusOrderByCreatedAtDesc`、`findByIdAndStatus`、`existsByTags_Id`。
- 泳装两个 repo 的 `existsByTags_Id` 上移到 `Product`（标签现在是公共关系）。
- `CustomerReviewRepository` 查询从按 `bikiniSuit` 改为按 `product`：`findAllByProduct_IdAndStatusOrderByCreatedAtDesc`。

### Service 层

- 把「复制公共字段」的样板抽到 **`ProductChangeSupport`**（`internal`）：`applyBaseChangesFrom(source)` / `applyTags(tagRepository, tagIds)` / `hydrateBase()` / `replaceWith(values)`。
- 四个子类服务各自只剩「复制特有字段」，不再重复公共样板。
- **`CustomerReviewServiceImpl`** 重写：在审核 / 删除（及客户修改一条原本已通过的评价）后**重算商品 `score`**——取该商品 `APPROVED` 评价求平均写回 `product.score`，无评价置 `null`。此前 `score` 字段完全没接线，现已补上。
- **`TagServiceImpl`**：标签占用校验合并为单次 `productRepository.existsByTags_Id(id)`。

### Controller 层

- 新增 **`ProductResponses.kt`**：把原本各控制器里的 `private Response` 提升为顶层密封接口 `ProductResponse`，用 `@JsonTypeInfo(property = "productType")` + `@JsonSubTypes` 列四个子类型；共享 `fun Product.toResponse(): ProductResponse`。新老控制器共用。
- 新增 **`ProductController`**：公开 `GET /api/products`（混合子类型、ACTIVE）与 `GET /api/products/{id}`。
- **评价 URL 迁移**：从 `/api/bikini-suits/{id}/reviews` 迁到 `/api/products/{productId}/reviews`（URL 变更不可避免）；响应字段 `bikiniSuitId` → `productId`。
- 新增 `DressController` / `CoverUpController`：管理端 CRUD + 公开 GET 两级路由。
- `BikiniSuitController` / `OnePieceSuitController` 复用提升后的响应类型与共享 `toResponse()`。

### 安全配置

`SecurityConfig` 的匿名 GET 放行段新增：

```
/api/products/**
/api/dresses/**
/api/cover-ups/**
```

（写入接口仍要求 JWT。）

---

## 四、验证

`./gradlew clean test` 全绿，包含：

- 新增 **`DressValidationTest`**、**`CoverUpValidationTest`**；
- **`ProductInheritanceIntegrationTest`** —— 这条最关键：实际落库四个子类，确认：
  - `productRepository.findAll()` 能还原四种具体类型；
  - `products.product_type` 鉴别列被正确填充（BIKINI / DRESS 各计数为 1）。
  - 这是方案里标注的最大运行时风险点，现在被实测排除。
- 更新了 `CatalogServiceImplTest`、`CustomerReviewValidationTest`、`OnePieceSuitValidationTest` 等以适配新结构。

### 运行时风险与校验说明

- **Hibernate 版本**：Spring Boot 4.1.0 带 Hibernate 7.x，`JOINED` + `@DiscriminatorColumn` 在写入时会正确填充鉴别列（已由集成测试确认）。
- **多态读 + 懒代理**：`productRepository.findAll*` 返回具体子类实例，`when(product){ is BikiniSuit -> ... }` 能正确匹配；但**懒加载的 `Product` 代理**（如 `someReview.product`）上 `proxy is BikiniSuit` 为 `false`。评价只需 `product.id`（代理上有），不受影响；别处需要具体子类时用 `org.hibernate.Hibernate.unproxy(product)` 解包。
- **JOINED 性能**：每次多态读外连接多张子类表；小店铺可忽略，勿预先优化。

---

## 五、重启前的操作步骤（重要）

dev 的 PostgreSQL 库里还是旧结构。`ddl-auto: update` **不会**迁移旧表，只会残留废弃的 `bikini_suit_*` 表和旧的 `customer_reviews.bikini_suit_id` 列。

**重启前必须手工 drop 掉这些表**：

- `products` / `bikini_suits` / `one_piece_suits` / `customer_reviews`
- 相关集合表（`*_highlights` / `*_images` / `*_design_extras` / `*_care_instructions`）
- 关联表（标签多对多表）

然后重启，由 Hibernate 重建 `products`(+`products_*`)、四个子类表、`customer_reviews.product_id`。

> ⚠️ 现有开发种子数据会丢失——需要保留就先导出，重启后用管理端接口重新录入。

### 重启后手工冒烟

1. `./gradlew bootRun`，观察建出 `products` / 四子类表 / `products_*` / `products_tags` / `customer_reviews(product_id)`；
2. `SELECT id, product_type FROM products` —— 鉴别列每行都有值；
3. 各类型管理端 CRUD 公共 + 特有字段均能落库；
4. 对一件 `Dress` 发评价 → 审核置 `APPROVED` → `GET /api/products/{id}` 的 `score` 已重算；
5. 各类型各上一条 ACTIVE，`GET /api/products` 返回混合数组、每项带正确 `productType` 与子类字段；
6. 给某商品打标签后 `DELETE /api/admin/tags/{id}` → 应报「包含不存在的标签」（标签占用校验生效）；
7. 匿名访问 `GET /api/products`、`/api/dresses`、`/api/cover-ups`、`/api/products/{id}/reviews` 成功；POST/PUT/DELETE 需 JWT。

---

## 六、文件清单

**新建**

- `entity/jdbc/Product.kt`
- `entity/jdbc/Dress.kt`
- `entity/jdbc/CoverUp.kt`
- `entity/jdbc/SizeRecommendation.kt`（Phase 0 重命名）
- `repository/ProductRepository.kt`（+ `Dress` / `CoverUp` Repository）
- `service/ProductService.kt`（+ `Dress` / `CoverUp` Service 及 Impl）
- `service/impl/ProductChangeSupport.kt`
- `controller/ProductController.kt`（+ `Dress` / `CoverUp` Controller）
- `controller/ProductResponses.kt`
- `test/.../entity/jdbc/DressValidationTest.kt`
- `test/.../entity/jdbc/CoverUpValidationTest.kt`
- `test/.../repository/ProductInheritanceIntegrationTest.kt`

**改写 / 修改**

- `entity/jdbc/BikiniSuit.kt`、`entity/jdbc/OnePieceSuit.kt`、`entity/jdbc/CustomerReview.kt`
- `repository/CustomerReviewRepository.kt`（+ 泳装两个 repo）
- `service/CustomerReviewService.kt`（+ Impl）
- `service/impl/BikiniSuitServiceImpl.kt`、`OnePieceSuitServiceImpl.kt`、`TagServiceImpl.kt`
- `controller/BikiniSuitController.kt`、`OnePieceSuitController.kt`、`CustomerReviewController.kt`
- `config/SecurityConfig.kt`
- 相关测试（`CatalogServiceImplTest`、`CustomerReviewValidationTest`、`OnePieceSuitValidationTest` 等）

---

## 七、遗留小事项

- 测试文件 `BikiniSuitSizeRecommendationTest.kt` 仍叫旧名（内容已适配新 VO，功能正常）。为减少改动量未改名，需要的话可顺手 `git mv` 成 `SizeRecommendationTest.kt`。
- `Product(style)→Sku(variant)` 拆分明确留作后续，本次不做。
