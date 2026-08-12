# 商品数据结构全量重构方案

> 状态：设计方案
> 目标实体：`top.foxball.shopmall.entity.jdbc.Product`
> 编写日期：2026-08-12
> 默认业务货币：USD
> 实施任务：`docs/product-model-unification-implementation-tasks.md`

## 1. 重构前提

本次属于不兼容的数据结构彻底重构，采用以下前提：

1. 不迁移数据库旧数据。
2. 不保留旧商品、购物车、订单、评价或测试数据。
3. 不编写旧结构到新结构的数据回填脚本。
4. 不提供新旧商品模型双写、兼容读取或旧接口适配。
5. 部署新版本前直接删除并重新创建数据库结构。
6. 后端、`AdminPanelUI` 和 `frontend` 在同一重构周期内全部切换。
7. 支付和退款流程的业务规则保持不变，但测试数据需要重新创建。

本文中的“迁移”仅表示代码和数据模型从旧设计切换到新设计，不表示保留或转换旧数据库数据。

## 2. 重构目标

1. 使用一个 `Product` 表示所有商品款式。
2. 使用 `ProductVariant` 表示可销售 SKU。
3. 删除 `BikiniSuit`、`OnePieceSuit`、`Dress`、`CoverUp` 等 JPA 子类。
4. 删除 `JOINED` 继承和 Hibernate 鉴别器。
5. 通过明确的 `productType` 字段区分商品类型。
6. 所有类型共享的公共参数直接写入 `Product` 或 `ProductVariant`。
7. 品类私有参数以 KV 形式写入对应 `attributes`。
8. 新增商品类型和私有参数时不再创建新的商品实体、子表、Repository 或 Controller。
9. 商品分类、商品类型和 SKU 规格分别建模，不能互相代替。
10. 库存、价格、购物车和订单始终以 SKU 为最小交易单位。

## 3. 总体模型

~~~mermaid
erDiagram
    PRODUCT_TYPE ||--o{ ATTRIBUTE_DEFINITION : defines
    PRODUCT_TYPE ||--o{ PRODUCT : types
    PRODUCT_CATEGORY ||--o{ PRODUCT : classifies
    PRODUCT ||--|{ PRODUCT_VARIANT : contains
    PRODUCT ||--o{ PRODUCT_ATTRIBUTE : has
    PRODUCT_VARIANT ||--o{ PRODUCT_VARIANT_ATTRIBUTE : has
    PRODUCT ||--o{ PRODUCT_IMAGE : displays
    PRODUCT ||--o{ PRODUCT_MATERIAL : contains
    PRODUCT ||--o{ PRODUCT_TAG : tagged
    PRODUCT ||--o{ CUSTOMER_REVIEW : reviewed
    SHOPPING_CART ||--o{ CART_ITEM : contains
    PRODUCT_VARIANT ||--o{ CART_ITEM : selected
    ORDER ||--o{ ORDER_ITEM : contains
~~~

模型职责：

- `ProductType`：定义商品类型和允许使用的私有参数。
- `ProductCategory`：定义前台导航和运营分类树。
- `Product`：商品款式（SPU）及公共展示数据。
- `ProductVariant`：可销售 SKU、USD 价格、规格、库存和销量。
- `ProductAttribute`：商品级私有 KV。
- `ProductVariantAttribute`：SKU 级私有 KV。

## 4. 商品类型设计

### 4.1 不再使用 JPA 继承

目标 `Product` 必须移除：

~~~kotlin
@Inheritance(strategy = InheritanceType.JOINED)
@DiscriminatorColumn(...)
~~~

`Product` 不再是基类，也不存在任何商品子类。

### 4.2 ProductType

为了支持后续增加类型和对应参数，建议增加 `ProductType` 实体：

| 字段 | 说明 |
| --- | --- |
| `id` | 数据库主键 |
| `code` | 稳定类型代码，例如 BIKINI、DRESS |
| `name` | 管理端和前台显示名称 |
| `description` | 类型说明 |
| `active` | 是否允许创建新商品 |
| `displayOrder` | 展示顺序 |
| `createdAt` / `updatedAt` | LocalDateTime |
| `version` | 乐观锁版本 |

`Product.productType` 使用 `@ManyToOne` 关联 `ProductType`。接口返回 `product_type` 时返回稳定 code，不返回数据库 ID。

使用实体而不是 Kotlin enum 的原因：

- 新类型不要求重新定义 `Product` 子类。
- 可以启用或停用类型。
- 可以为类型动态配置属性定义。
- 类型显示名称不与代码耦合。

类型 code 创建后不可修改，只允许停用。

### 4.3 ProductType 与 ProductCategory

- ProductType 表示数据规则，例如 DRESS。
- ProductCategory 表示导航位置，例如 Women / Dresses。
- 一个分类可以包含多个类型，一个类型也可以出现在不同分类中。
- Product 当前保留一个主分类；未来需要多分类时再改为关联表。

## 5. 公共参数与私有参数

### 5.1 公共参数判定

满足以下任一条件的字段属于公共参数：

1. 所有商品都必须使用。
2. 核心交易流程直接依赖。
3. 需要跨商品类型排序、统计或建立固定索引。
4. 具有稳定数据库约束，不能接受自由 KV。

核心交易字段不得写入 attributes，包括：

- 商品和 SKU 标识；
- 商品类型和分类；
- SKU；
- USD 价格；
- 库存和销量；
- 商品与 SKU 状态；
- 创建、更新时间和版本。

### 5.2 私有参数判定

只适用于特定商品类型，且不属于核心交易字段的参数写入 attributes，例如：

- 连衣裙长度和廓形；
- 一体式泳衣覆盖程度和腹部塑形；
- 罩衫款式和透视程度；
- 比基尼上下装尺码。

### 5.3 属性作用域

私有参数必须区分作用域：

- PRODUCT：同一 Product 下所有 SKU 共享。
- VARIANT：参与 SKU 规格组合，不同 SKU 可以不同。

比基尼 `top_size` 和 `bottom_size` 必须是 VARIANT 属性，不能写入 Product.attributes。

## 6. 目标实体

### 6.1 Product

Product 只保存真正通用的商品款式字段：

| 字段 | 说明 |
| --- | --- |
| `id` | SPU 主键 |
| `productType` | ProductType 关联 |
| `category` | ProductCategory 主分类 |
| `name` | 商品名称 |
| `status` | ACTIVE / INACTIVE |
| `deletedAt` | 软删除时间 |
| `materials` | 结构化材质及百分比 |
| `highlights` | 商品卖点 |
| `images` | 图片、替代文本和主图标识 |
| `fitSense` | 版型与穿着感受 |
| `description` | 完整描述 |
| `designAndExtras` | 设计细节和附加配件 |
| `careInstructions` | 洗护说明 |
| `attributes` | PRODUCT 作用域私有 KV |
| `tags` | 商品标签 |
| `customerReviews` | 客户评价 |
| `score` | 评价派生平均分 |
| `createdAt` / `updatedAt` | LocalDateTime |
| `version` | 乐观锁 |

当前实验 Product 中以下字段不是真正公共字段，应移动到属性定义，而不是继续固定在 Product：

- `cupStyle`
- `cupThickness`
- `shoulderStrapDesign`
- `supportStructure`

如果后续业务确认某字段需要跨全部商品类型固定筛选，再通过独立设计评审提升为 Product 字段。

当前实验 Product 还需要补回：

- highlights；
- designAndExtras；
- tags；
- customerReviews；
- score。

### 6.2 ProductVariant

| 字段 | 说明 |
| --- | --- |
| `id` | SKU 主键 |
| `product` | 所属 Product |
| `sku` | 全局唯一、创建后不可修改 |
| `size` | 通用单尺码，可为空 |
| `color` | 标准颜色名称或代码 |
| `price` | 当前 USD 价格 |
| `warehouseVolume` | 当前库存 |
| `salesVolume` | 累计销量 |
| `status` | ACTIVE / INACTIVE |
| `displayOrder` | 变体展示顺序 |
| `attributes` | VARIANT 作用域私有 KV |
| `optionSignature` | 规范化规格签名 |
| `createdAt` / `updatedAt` | LocalDateTime |
| `version` | 乐观锁 |

唯一约束：

- `sku` 全局唯一；
- `(product_id, option_signature)` 唯一。

当前实验实体中的 `(product_id, size_value, color_value)` 约束需要删除，因为它无法区分比基尼的上下装尺码组合。

`optionSignature` 根据以下内容生成：

1. 规范化 size；
2. 规范化 color；
3. 按 attribute code 排序的 VARIANT 属性；
4. 使用稳定编码生成签名。

### 6.3 ProductAttribute 和 ProductVariantAttribute

实际属性值使用 KV：

| 字段 | 说明 |
| --- | --- |
| `code` | 稳定 snake_case 键 |
| `value` | 规范化字符串值 |
| `sortOrder` | 展示顺序 |

约束：

- ProductAttribute 的 `(product_id, code)` 唯一；
- ProductVariantAttribute 的 `(variant_id, code)` 唯一；
- code 必须在当前 ProductType 的属性定义中存在；
- scope 必须匹配；
- 空值不写入属性行。

显示名称不应在每个商品属性中重复保存，应来自 AttributeDefinition。

### 6.4 AttributeDefinition

| 字段 | 说明 |
| --- | --- |
| `id` | 主键 |
| `productType` | 所属 ProductType |
| `code` | 属性代码 |
| `name` | 显示名称 |
| `scope` | PRODUCT / VARIANT |
| `valueType` | STRING / BOOLEAN / INTEGER / DECIMAL / ENUM |
| `required` | 是否必填 |
| `filterable` | 是否允许作为筛选条件 |
| `allowedValues` | ENUM 允许值 |
| `maxLength` | STRING 最大长度 |
| `displayOrder` | 展示顺序 |
| `active` | 是否继续允许写入 |

属性值规范：

- ENUM 使用大写下划线；
- BOOLEAN 仅允许 true / false；
- INTEGER 和 DECIMAL 使用无本地化分隔符文本；
- STRING 写入前 trim；
- 停用定义仅禁止新写入，已存在值仍可读取。

### 6.5 ProductCategory

继续使用当前实验 `ProductCategory`：

- code；
- name；
- description；
- parent；
- displayOrder；
- status；
- LocalDateTime 时间戳；
- version。

## 7. 初始商品类型定义

### 7.1 BIKINI

| code | scope | type | 规则 |
| --- | --- | --- | --- |
| `top_size` | VARIANT | ENUM | S、M、L、XL、XXL、XXXL、XXXXL |
| `bottom_size` | VARIANT | ENUM | 同 top_size |
| `cup_style` | PRODUCT | ENUM | 使用设计确认后的值域 |
| `cup_thickness` | PRODUCT | ENUM | 使用设计确认后的值域 |
| `shoulder_strap_design` | PRODUCT | ENUM | 使用设计确认后的值域 |
| `support_structure` | PRODUCT | ENUM | 使用设计确认后的值域 |

业务校验：top_size 和 bottom_size 至少存在一个。

### 7.2 ONE_PIECE

通用 `size` 写入 ProductVariant.size。

| code | scope | type |
| --- | --- | --- |
| `support_level` | PRODUCT | ENUM |
| `coverage` | PRODUCT | ENUM |
| `torso_fit` | PRODUCT | ENUM |
| `neckline` | PRODUCT | ENUM |
| `back_style` | PRODUCT | ENUM |
| `tummy_control` | PRODUCT | BOOLEAN |
| `removable_padding` | PRODUCT | BOOLEAN |
| `cup_style` | PRODUCT | ENUM |
| `cup_thickness` | PRODUCT | ENUM |
| `shoulder_strap_design` | PRODUCT | ENUM |
| `support_structure` | PRODUCT | ENUM |

### 7.3 DRESS

通用 `size` 写入 ProductVariant.size。

| code | scope | type |
| --- | --- | --- |
| `length` | PRODUCT | ENUM |
| `silhouette` | PRODUCT | ENUM |
| `neckline` | PRODUCT | ENUM |
| `sleeve_type` | PRODUCT | ENUM |
| `fabric_description` | PRODUCT | STRING |

### 7.4 COVER_UP

通用 `size` 写入 ProductVariant.size。

| code | scope | type |
| --- | --- | --- |
| `cover_up_style` | PRODUCT | ENUM |
| `sheer_level` | PRODUCT | ENUM |
| `fabric_description` | PRODUCT | STRING |

`SizeRecommendation` 不写入 Product 或 attributes。它属于“商品类型 + 尺码代码”的只读尺码指南。

## 8. 全新数据库结构

本次不演进旧结构，直接清空并重新创建。

目标商品相关表：

- product_types；
- product_attribute_definitions；
- product_categories；
- products；
- product_variants；
- products_attributes；
- product_variant_attributes；
- products_materials；
- products_images；
- products_highlights；
- products_design_extras；
- products_care_instructions；
- products_tags；
- customer_reviews；
- shopping_carts；
- shopping_cart_items；
- orders；
- order_items。

不再创建：

- bikini_suits；
- one_piece_suits；
- dresses；
- cover_ups。

本项目不编写数据库迁移脚本。开发和测试环境通过新的 JPA 映射创建空结构；正式环境也必须使用经审批的空库初始化流程，不能对旧数据库执行原地升级。

## 9. 关联模型调整

### 9.1 ShoppingCart

`CartItem` 从关联 Product 改为关联 ProductVariant。

唯一约束：

~~~text
(cart_id, variant_id)
~~~

购物车展示时从 Variant 获取价格、颜色、尺码和库存，从 Product 获取名称、图片和状态。

### 9.2 OrderItem

新订单行保存：

- productId；
- variantId；
- sku；
- productSnapshot；
- unitPrice；
- quantity；
- lineTotal。

productSnapshot 至少包含：

- productType；
- 商品名称；
- SKU；
- 颜色和尺码；
- Product attributes；
- Variant attributes；
- 下单时图片；
- 货币 USD。

订单创建后不回查当前商品覆盖历史快照。

### 9.3 Tag 和 CustomerReview

- Tag 继续关联 Product。
- CustomerReview 继续关联 Product。
- score 由已审核评价派生。
- 删除 Product 时必须明确处理购物车项，历史 OrderItem 不建立可级联删除的商品外键。

## 10. Repository 与事务

### 10.1 Repository

只保留：

- ProductRepository；
- ProductVariantRepository；
- ProductTypeRepository；
- ProductCategoryRepository；
- AttributeDefinitionRepository。

删除四个品类 Repository。

ProductRepository 负责：

- productType、category、status 和关键字查询；
- 商品资料和集合加载；
- 软删除及恢复。

ProductVariantRepository 负责：

- SKU 查询；
- 价格、库存和销量；
- 低库存查询；
- 库存条件更新；
- optionSignature 唯一性检查。

### 10.2 商品写事务

创建 Product 时，在一个事务中完成：

1. 校验 ProductType 已启用。
2. 校验 PRODUCT 和 VARIANT 属性定义。
3. 保存 Product。
4. 保存至少一个 ProductVariant。
5. 保存 attributes、图片、材质、标签和洗护说明。
6. 验证每个 SKU 和 optionSignature 唯一。

任一步失败整体回滚。

### 10.3 库存事务

库存仍使用数据库条件更新：

~~~sql
UPDATE product_variants
SET warehouse_volume = warehouse_volume - :quantity
WHERE id = :variant_id
  AND warehouse_volume >= :quantity
  AND status = 'ACTIVE'
~~~

影响行数必须为 1，否则订单事务回滚。乐观锁用于普通编辑，不能替代库存条件更新。

### 10.4 下单事务

1. 按 variantId 排序锁定 SKU。
2. 校验 Product 未删除且 Product、Variant 均 ACTIVE。
3. 从 Variant 获取 USD 价格。
4. 构造不可变订单快照。
5. 按 Variant 条件扣减库存。
6. 创建订单、订单行和事件。
7. 任一步失败整体回滚。

订单取消按 variantId 回补库存。支付、退款和订单状态事务规则保持现有设计。

## 11. Service 重构

目标服务：

### ProductTypeService

- 管理商品类型；
- 管理属性定义；
- 校验属性 code、scope、类型和值域；
- 返回管理端动态表单定义。

### ProductService

- Product 列表和详情；
- 创建、编辑、上下架、软删除和恢复；
- 管理 Product attributes、图片、材质和标签；
- 不直接调整库存。

### ProductVariantService

- 创建、编辑和停用 SKU；
- 管理 Variant attributes；
- 调整库存；
- 计算 optionSignature；
- 提供订单所需的原子库存接口。

删除：

- BikiniSuitService；
- OnePieceSuitService；
- DressService；
- CoverUpService；
- 对应实现类；
- ProductChangeSupport 中针对旧字段的复制逻辑。

## 12. Controller 与 API

删除四套客户端和管理端品类 Controller，直接提供统一接口：

- 商品类型及属性定义查询；
- 商品分类查询；
- 客户端商品列表和详情；
- 管理端 Product CRUD；
- 管理端 Variant CRUD；
- 管理端 SKU 库存调整。

控制器继续遵循项目规范：

- 参数直接声明 `@PathVariable`、`@RequestParam`、`@RequestPart`；
- wire name 使用 snake_case；
- 不创建请求包装 DTO；
- 响应 data class 定义在端点方法内部；
- 端点内显式构造响应。

建议详情响应：

~~~json
{
  "id": 1001,
  "product_type": "ONE_PIECE",
  "name": "Example",
  "status": "ACTIVE",
  "attributes": {
    "support_level": "HIGH",
    "coverage": "FULL",
    "tummy_control": "true"
  },
  "variants": [
    {
      "id": 2001,
      "sku": "OP-BLACK-M",
      "size": "M",
      "color": "BLACK",
      "price": "79.00",
      "currency": "USD",
      "warehouse_volume": 15,
      "attributes": {}
    }
  ]
}
~~~

不保留旧 `/api/bikini-suits`、`/api/dresses` 等接口兼容层。

## 13. 前端重构

### 13.1 AdminPanelUI

管理端商品编辑流程：

1. 选择 ProductType。
2. 根据 AttributeDefinition 动态生成 PRODUCT 属性控件。
3. 编辑公共字段、图片、材质和标签。
4. 创建多个 Variant。
5. 根据 VARIANT 属性定义生成规格控件。
6. 设置 SKU、USD 价格和库存。

控件规则：

- ENUM 使用菜单或分段选择；
- BOOLEAN 使用开关；
- INTEGER / DECIMAL 使用数字输入；
- 颜色使用色板；
- 图片支持主图选择和替代文本；
- 不在页面中硬编码 DRESS、BIKINI 等字段分支。

### 13.2 frontend

客户商品详情：

- 根据 Product 公共字段展示商品内容；
- 根据 AttributeDefinition 展示私有属性；
- 根据 Variants 生成规格选择器；
- 选择完整 SKU 后才能加入购物车；
- 价格和库存随选中 Variant 更新；
- 购物车和下单只提交 variant_id。

## 14. 代码删除与替换

删除：

- `entity.jdbc.Product`；
- `entity.jdbc.BikiniSuit`；
- `entity.jdbc.OnePieceSuit`；
- `entity.jdbc.Dress`；
- `entity.jdbc.CoverUp`；
- 四个品类 Repository；
- 四个品类 Service 及实现；
- 四套客户端品类 Controller；
- 四套管理端品类 Controller；
- `AdminProductService.ProductType.entityClass`；
- 所有 `when (product)`、`is Dress` 等运行时类型分支；
- 基于 `root.type()` 的查询；
- 商品级 price、color、warehouseVolume、salesVolume。

启用或新增：

- `entity.jdbc.Product`；
- `entity.jdbc.ProductVariant`；
- `entity.jdbc.ProductCategory`；
- ProductType；
- AttributeDefinition；
- ProductVariant attributes；
- 统一 Product / Variant Repository；
- 统一 Service 和 Controller。

`ShopMallApplication` 的实体扫描切换到新的商品实体。不能同时扫描两个映射 products 表的 Product。

## 15. 实施顺序

### 阶段 1：完成领域模型

- 调整 entity.jdbc.Product。
- 增加 ProductType 和 AttributeDefinition。
- 将 cup 等品类字段移入属性定义。
- 增加 Variant attributes 和 optionSignature。
- 补回标签、评价、卖点和设计细节。

### 阶段 2：完成持久化

- 新增统一 Repository。
- 重写 CartItem、OrderItem、Tag、CustomerReview 关联。
- 添加 JPA 映射和约束测试。
- 在空数据库验证 Hibernate 建表。

### 阶段 3：完成业务服务

- 实现类型和属性校验。
- 实现 Product 与 Variant CRUD。
- 重写库存、购物车和订单逻辑。
- 保持支付、退款和订单状态行为。

### 阶段 4：完成 API

- 实现统一客户端和管理端 Controller。
- 删除旧品类 Controller。
- 更新接口测试和 API 文档。

### 阶段 5：完成前端

- AdminPanelUI 实现动态属性和 SKU 编辑。
- frontend 实现动态规格选择。
- 删除旧类型硬编码页面和接口调用。

### 阶段 6：清理与重建

- 删除全部旧实体、服务和测试。
- 删除旧数据库并创建空数据库。
- 初始化 ProductType、AttributeDefinition 和 ProductCategory。
- 重新创建测试和演示商品。
- 运行全量测试与端到端验收。

## 16. 初始化数据

空数据库启动后需要幂等初始化：

- BIKINI；
- ONE_PIECE；
- DRESS；
- COVER_UP；
- 每种类型的 AttributeDefinition；
- 基础 ProductCategory；
- 开发环境模拟商品。

类型与属性定义属于系统元数据，不属于旧业务数据。初始化器必须幂等，并使用事务一次性写入一组完整定义。

生产环境是否自动初始化由显式配置控制，不能依赖开发 MockDataInitializer。

## 17. 测试清单

1. ProductType code 唯一和停用规则。
2. AttributeDefinition 类型、作用域和值域校验。
3. Product 与 Variant attributes 校验。
4. optionSignature 稳定性和去重。
5. Product/Variant/Category JPA 映射。
6. Product 创建事务整体回滚。
7. SKU 唯一和至少一个 Variant 规则。
8. 购物车按 variant_id 去重。
9. USD 价格精度和金额计算。
10. 并发库存扣减不产生负库存。
11. 订单取消正确回补 Variant 库存。
12. 订单快照不受后续商品编辑影响。
13. 商品软删除、恢复和永久删除。
14. 支付、退款和订单状态回归。
15. AdminPanelUI 动态商品编辑流程。
16. frontend 选规格、购物车和下单流程。
17. 空数据库完整应用上下文启动。

## 18. 发布与回退原则

本次没有旧数据需要保护，因此不做数据库层面的回滚设计。

发布：

1. 停止旧版本应用。
2. 删除旧数据库或使用全新的空数据库。
3. 部署后端新版本。
4. 初始化系统类型、属性定义和分类。
5. 部署匹配版本的 AdminPanelUI 和 frontend。
6. 创建新商品并完成冒烟测试。

代码回退时必须同时使用与旧代码匹配的空数据库结构。不能让旧代码连接新结构，也不能让新代码连接旧结构。

## 19. 完成标准

- 正式业务只使用 entity.jdbc.Product 和 ProductVariant。
- products 不再使用 JPA 继承或品类子表。
- 商品类型与属性定义可以独立增加。
- 公共参数进入 Product/Variant。
- 私有参数进入正确作用域的 attributes。
- 购物车、订单、价格和库存全部以 Variant 为单位。
- AdminPanelUI 不再硬编码品类字段表单。
- frontend 不再调用旧品类接口。
- 空数据库可以完成初始化并通过全量测试。
- 旧商品实体、服务、Controller 和数据库表已全部删除。
