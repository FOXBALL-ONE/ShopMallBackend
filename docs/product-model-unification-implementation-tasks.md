# 商品模型统一重构实施任务

> 上位设计：`docs/product-model-unification-refactor-plan.md`
> 实施方式：全量重构，不保留旧数据库数据
> 默认货币：USD
> 工作流：实体、Service、Controller、管理端

## 1. 使用方式

本文将重构拆分为可执行任务。任务编号用于提交、评审和测试记录：

- `E-*`：实体与持久化模型。
- `S-*`：Repository、Service 与交易流程。
- `C-*`：客户和管理端 Controller/API。
- `A-*`：`AdminPanelUI`。
- `X-*`：跨模块切换、清理和验收。

任务状态：

- [ ] 未开始
- [~] 进行中
- [x] 已完成

除非任务明确标记为独立可发布，否则所有工作在同一重构分支完成。旧模型与新模型映射同一业务表，最终切换前不能同时加入正式 EntityScan。

## 2. 阶段总览

| 阶段 | 主要工作流 | 目标 | 检查点 |
| --- | --- | --- | --- |
| P0 | 全部 | 冻结领域和 API 契约 | 字段、属性作用域和接口评审通过 |
| P1 | 实体 | 完成新实体和隔离映射 | `compileKotlin` + entity.jdbc 隔离 JPA 测试 |
| P2 | Service | 完成仓储、校验、库存、购物车和订单 | Service 单元/集成测试通过 |
| P3 | Controller | 完成统一 API 并删除品类 API | Controller 测试通过 |
| P4 | 管理端 | 动态商品、属性和 SKU 管理 | typecheck + build + 关键流程测试 |
| P5 | 全部 | 原子切换、清理旧代码、空库启动 | 全量后端测试 + 前端构建 + 冒烟 |

依赖主链：

~~~text
E-01 -> E-03 -> E-04 -> S-02 -> S-04 -> C-03 -> A-02
                    \-> S-05 -> C-05 -> A-04
E-07 -> S-06/S-07 -> C-06/C-07
全部任务 -> X-01 实体扫描切换 -> X-02 旧代码删除 -> X-04 全量验收
~~~

## 3. P0：契约冻结

### P0-01 商品类型与属性定义

- [ ] 确认初始 ProductType：BIKINI、ONE_PIECE、DRESS、COVER_UP。
- [ ] 确认每个属性的 code、scope、valueType、required、allowedValues。
- [ ] 确认比基尼 top_size / bottom_size 属于 VARIANT。
- [ ] 确认 cup_style 等字段从 Product 固定列移动到类型属性。
- [ ] 确认 ProductVariant.size 允许为空。
- [ ] 确认 SKU 格式、长度和不可修改规则。

交付物：属性定义表或初始化常量清单。

完成标准：实体、Service、Controller 和管理端使用同一份 code 与值域，不在各层重复定义不同枚举。

### P0-02 API 契约

- [ ] 冻结 Product 列表、详情、创建和更新响应。
- [ ] 冻结 Variant CRUD、库存调整和状态接口。
- [ ] 冻结 ProductType、AttributeDefinition、ProductCategory 查询接口。
- [ ] 购物车请求只接受 `variant_id`。
- [ ] 新订单快照包含 `product_id`、`variant_id`、`sku`、`product_type` 和属性。
- [ ] 所有 date-time 使用 ISO_LOCAL_DATE_TIME。
- [ ] 金额字符串和数据库金额均按 USD、两位小数处理。

完成标准：`AdminPanelUI` 可以只依赖统一 API，不需要调用旧品类端点。

## 4. P1：实体工作流

### E-01 ProductType

- [ ] 新增 `entity/jdbc/ProductType.kt`。
- [ ] 字段：id、code、name、description、active、displayOrder、createdAt、updatedAt、version。
- [ ] code 使用大写下划线格式并设置唯一约束。
- [ ] code 创建后不可更新。
- [ ] 增加状态、排序和 code 索引。

依赖：P0-01。

完成标准：ProductType 可独立完成 JPA 建表和 Bean Validation。

### E-02 AttributeDefinition

- [ ] 新增 `ProductAttributeDefinition`。
- [ ] 新增 `AttributeScope`：PRODUCT、VARIANT。
- [ ] 新增 `AttributeValueType`：STRING、BOOLEAN、INTEGER、DECIMAL、ENUM。
- [ ] 关联 ProductType。
- [ ] 字段：code、name、scope、valueType、required、filterable、allowedValues、maxLength、displayOrder、active。
- [ ] `(product_type_id, code)` 唯一。
- [ ] allowedValues 使用结构化集合，不使用逗号拼接字符串。

依赖：E-01。

完成标准：每个属性定义可以完整驱动后端校验和管理端控件。

### E-03 Product 重构

目标文件：`../src/main/kotlin/top/foxball/shopmall/entity/jdbc/Product.kt`。

- [ ] 删除 `@Inheritance` 和 `@DiscriminatorColumn`。
- [ ] 增加 `productType: ProductType` 必填关联。
- [ ] 保留 `category: ProductCategory?`。
- [ ] 删除固定字段 cupStyle、cupThickness、shoulderStrapDesign、supportStructure。
- [ ] 增加 highlights。
- [ ] 增加 designAndExtras。
- [ ] 保留并校验 materials、images、careInstructions、fitSense、description。
- [ ] 增加 tags、customerReviews、score。
- [ ] 保留 status、deletedAt、createdAt、updatedAt、version。
- [ ] attributes 改为真正的 PRODUCT 作用域 KV，不重复保存显示名称。
- [ ] 增加“至少一个 Variant”的领域校验。
- [ ] 保留主图唯一和材质比例校验。

依赖：E-01、E-02。

完成标准：Product 不引用任何旧商品子类或品类枚举。

### E-04 ProductVariant 重构

目标文件：`../src/main/kotlin/top/foxball/shopmall/entity/jdbc/Product.kt`，后续可按职责拆成独立文件。

- [ ] size 改为可空。
- [ ] 增加 VARIANT 作用域 attributes。
- [ ] 增加 optionSignature。
- [ ] 删除 `(product_id, size_value, color_value)` 唯一约束。
- [ ] 增加 `(product_id, option_signature)` 唯一约束。
- [ ] sku 保持全局唯一且不可更新。
- [ ] price 使用 DecimalMin、Digits 和数据库 precision/scale。
- [ ] warehouseVolume 非负 Int。
- [ ] salesVolume 非负 Long。
- [ ] 保留 status、displayOrder、LocalDateTime 和 version。
- [ ] Product.addVariant/removeVariant 继续维护双向关联。

依赖：E-02、E-03。

完成标准：相同颜色但不同 top_size / bottom_size 的比基尼 SKU 可以共存。

### E-05 属性值对象

- [ ] ProductAttribute 仅保存 code、value、sortOrder。
- [ ] 新增 ProductVariantAttribute。
- [ ] code 使用 snake_case 校验。
- [ ] value 长度覆盖定义允许的最大长度。
- [ ] owner + code 唯一。
- [ ] 不在实体 setter 中手写类型解析；解析和定义校验属于 Service。

依赖：E-02。

完成标准：实体层表达 KV，Service 层负责类型和值域。

### E-06 ProductCategory 收尾

目标文件：`entity/jdbc/ProductCategory.kt`。

- [ ] 校验 code、name、parent、displayOrder、status。
- [ ] 增加同级显示顺序所需索引。
- [ ] 防止 category.parent 指向自身。
- [ ] 明确删除父分类时的约束，不级联删除 Product。
- [ ] 删除所有 Sort 遗留声明和引用。

依赖：无。

完成标准：分类只负责导航，不承担 productType 规则。

### E-07 关联实体重构

目标文件：

- `entity/jdbc/ShoppingCart.kt`
- `entity/jdbc/OrderItem.kt`
- `entity/jdbc/Tag.kt`
- `entity/jdbc/CustomerReview.kt`

任务：

- [ ] CartItem 从 Product 改为 ProductVariant。
- [ ] 唯一约束改为 `(cart_id, variant_id)`。
- [ ] OrderItem 增加 productId、variantId、sku 快照字段。
- [ ] OrderItem 不建立会阻止商品删除的强商品外键。
- [ ] Tag 改为关联 entity.jdbc.Product。
- [ ] CustomerReview 改为关联 entity.jdbc.Product。
- [ ] 所有新/修改业务时间使用 LocalDateTime。

依赖：E-03、E-04。

完成标准：购物车以 SKU 为单位，订单历史不依赖当前 Product 存活。

### E-08 实体测试

- [ ] ProductType code 和唯一性测试。
- [ ] AttributeDefinition scope/type/value配置测试。
- [ ] Product 主图、材质、至少一个 Variant 校验。
- [ ] ProductVariant SKU、价格、库存、销量校验。
- [ ] optionSignature 唯一映射测试。
- [ ] CartItem variant 唯一约束测试。
- [ ] entity.jdbc 隔离 EntityManagerFactory 启动测试。

依赖：E-01 至 E-07。

完成标准：新实体映射在空 H2/PostgreSQL 测试库中可完整建表。

## 5. P2：Service 工作流

### S-01 统一 Repository

- [ ] 新 ProductRepository 面向 entity.jdbc.Product。
- [ ] 新 ProductVariantRepository。
- [ ] 新 ProductTypeRepository。
- [ ] 新 ProductAttributeDefinitionRepository。
- [ ] 新 ProductCategoryRepository。
- [ ] Product 查询支持 productType、category、status、keyword。
- [ ] Variant 查询支持 sku、productId、status、lowStock。
- [ ] 主图批量查询避免 N+1。
- [ ] 库存和销量方法全部位于 ProductVariantRepository。

依赖：E-01 至 E-07。

完成标准：Repository 测试覆盖常用过滤、分页、锁和条件更新。

### S-02 属性校验与 optionSignature

- [ ] 新增 AttributeValidationService。
- [ ] 校验属性定义存在且 active。
- [ ] 校验 PRODUCT / VARIANT scope。
- [ ] 校验 STRING、BOOLEAN、INTEGER、DECIMAL、ENUM。
- [ ] 校验 required 属性完整。
- [ ] 拒绝重复 code。
- [ ] 规范化 value。
- [ ] 新增 OptionSignatureService。
- [ ] size、color、属性 code 排序后生成稳定签名。
- [ ] 相同输入顺序不同仍生成同一签名。

依赖：E-02、E-05。

完成标准：所有属性写操作只通过统一校验服务。

### S-03 ProductTypeService

- [ ] 查询启用类型。
- [ ] 查询类型及属性定义。
- [ ] 创建、编辑、停用 ProductType。
- [ ] 创建、编辑、停用 AttributeDefinition。
- [ ] 禁止修改已创建 type/attribute code。
- [ ] 禁止删除仍被 Product 使用的类型。
- [ ] 增加系统元数据初始化服务。

依赖：S-01、S-02。

完成标准：空数据库可幂等初始化四种类型及属性定义。

### S-04 ProductService

- [ ] 统一商品列表与详情。
- [ ] 创建 Product 及至少一个 Variant。
- [ ] 更新公共字段、PRODUCT attributes、图片、材质、标签和洗护说明。
- [ ] 按 ProductType 校验属性。
- [ ] 状态更新、软删除、恢复和永久删除。
- [ ] 商品删除时清理购物车项，不删除历史 OrderItem。
- [ ] 评价变化后重算 score。
- [ ] 所有写操作具有明确事务边界。

依赖：S-01、S-02、S-03。

完成标准：不包含 `when(product)` 或旧子类判断。

### S-05 ProductVariantService

- [ ] 新增、编辑、停用 Variant。
- [ ] 校验 sku、price、size、color 和 VARIANT attributes。
- [ ] 计算并保存 optionSignature。
- [ ] 原子增加/减少库存。
- [ ] 原子增加/减少销量。
- [ ] SKU 删除前检查购物车和订单使用规则。
- [ ] Product 至少保留一个有效 Variant。
- [ ] 低库存统计和排序按 Variant 实现。

依赖：S-01、S-02、S-04。

完成标准：库存条件更新影响行数不是 1 时明确失败并回滚。

### S-06 ShoppingCartService

- [ ] addItem 参数从 productId 改为 variantId。
- [ ] 校验 Product 和 Variant 均可销售。
- [ ] 价格、库存、颜色、尺码来自 Variant。
- [ ] 名称、主图和 ProductType 来自 Product。
- [ ] 同一 variantId 合并数量。
- [ ] 更新、删除、清空购物车保持现有数量限制。
- [ ] 消除 Product.images 和 variants 的 N+1。

依赖：E-07、S-01、S-05。

完成标准：购物车任何行都能唯一定位 SKU。

### S-07 OrderService

- [ ] 下单命令行项目使用 variantId。
- [ ] 按 variantId 排序锁定。
- [ ] 从 Variant 获取 USD 价格。
- [ ] 构造 productId、variantId、sku、productType、属性快照。
- [ ] 按 Variant 条件扣库存。
- [ ] 订单取消按 Variant 回补库存。
- [ ] 支付成功按 Variant 增加销量。
- [ ] 退款、删除、邮件和发货继续读取不可变订单快照。
- [ ] 保持现有幂等、Outbox 和订单状态事务规则。

依赖：E-07、S-05、S-06。

完成标准：并发下单库存不为负，任一 SKU 失败使整单回滚。

### S-08 周边服务

- [ ] ProductImageService 使用结构化 ProductImage。
- [ ] TagService 关联新 Product。
- [ ] CustomerReviewService 关联新 Product。
- [ ] AdminProductService 按字段 productType 过滤。
- [ ] Dashboard 统计区分 Product 数量与 Variant 低库存数量。
- [ ] MockDataInitializer 使用 ProductType + Product + Variant。
- [ ] 删除 ProductChangeSupport 的旧品类复制逻辑。

依赖：S-03 至 S-07。

完成标准：全项目 Service 不再导入旧商品子类。

### S-09 Service 测试

- [ ] 属性值域和 required 测试。
- [ ] Product 创建/更新事务回滚测试。
- [ ] Variant 唯一和 optionSignature 测试。
- [ ] 库存并发扣减和回补测试。
- [ ] ShoppingCart variant 行为测试。
- [ ] Order 快照和金额测试。
- [ ] 退款与订单状态回归。
- [ ] Tag、Review、Image 回归。

依赖：S-01 至 S-08。

完成标准：Service 测试不构造旧 BikiniSuit/Dress 等实体。

## 6. P3：Controller 工作流

### C-01 类型与分类 API

- [ ] 客户端获取启用 ProductType 与属性定义。
- [ ] 客户端获取 ProductCategory。
- [ ] 管理端 ProductType CRUD。
- [ ] 管理端 AttributeDefinition CRUD。
- [ ] 管理端 ProductCategory CRUD。
- [ ] code 使用 snake_case wire name。

依赖：S-03。

完成标准：管理端无需本地硬编码类型字段和值域。

### C-02 统一客户商品 API

- [ ] Product 列表支持 product_type、category、keyword、分页。
- [ ] Product 详情返回公共字段、attributes、images 和 variants。
- [ ] Variant 返回 sku、size、color、USD price、stock 状态和 attributes。
- [ ] 只返回 Product 与 Variant 均 ACTIVE 的可售组合。
- [ ] 删除 BikiniSuitController、OnePieceSuitController、DressController、CoverUpController。

依赖：S-04、S-05。

完成标准：不存在按 Kotlin 实体类型分支构造响应的代码。

### C-03 统一管理商品 API

- [ ] 管理端 Product 分页查询。
- [ ] Product 创建、更新、状态、软删除、恢复和永久删除。
- [ ] attributes 作为直接 @RequestPart/@RequestParam 输入，不创建请求包装 DTO。
- [ ] 每个端点内定义响应 data class。
- [ ] 显式返回 ProductType、Category、attributes 和 Variant 摘要。
- [ ] 删除四个 Admin 品类 Controller。

依赖：S-04、C-01。

完成标准：符合 `docs/CONTROLLER_CONVENTIONS.md`。

### C-04 Variant 与库存 API

- [ ] Product 下 Variant 列表。
- [ ] Variant 创建、更新、停用和删除。
- [ ] 单 SKU 库存调整。
- [ ] 批量 SKU 状态和库存操作。
- [ ] 路径和参数明确使用 variant_id。
- [ ] 低库存查询返回 Product 和 SKU 上下文。

依赖：S-05、C-03。

完成标准：不再通过 product_id 调整库存。

### C-05 购物车与订单 API

- [ ] ShoppingCartController 输入和响应增加 variant_id。
- [ ] OrderController 下单输入使用 variant_id。
- [ ] OrderController/AdminOrderController 响应返回 variant_id 和 sku。
- [ ] 发货与邮件展示兼容新 productSnapshot。
- [ ] 保持订单、支付和退款端点路径与行为，除 SKU 参数变化外不扩大改动。

依赖：S-06、S-07。

完成标准：客户提交 product_id 作为购物车 SKU 时明确拒绝。

### C-06 图片、标签和评价 API

- [ ] 图片响应增加 alt_text、is_primary、sort_order。
- [ ] Tag Controller 使用新 Product。
- [ ] Review Controller 使用新 Product。
- [ ] Product 详情聚合已审核评价分数。

依赖：S-08。

完成标准：周边 API 不导入旧 Product。

### C-07 Controller 测试

- [ ] 类型和属性定义接口测试。
- [ ] 统一客户 Product 列表/详情测试。
- [ ] 管理端 Product CRUD 测试。
- [ ] Variant CRUD 和库存测试。
- [ ] 购物车 variant_id 测试。
- [ ] 订单、支付、退款回归测试。
- [ ] Controller 响应 snake_case 契约测试。

依赖：C-01 至 C-06。

完成标准：删除旧品类 Controller 测试后，统一接口覆盖不降低。

## 7. P4：管理端工作流

### A-01 TypeScript 类型

目标文件：`AdminPanelUI/app/types/product.ts`。

- [ ] 删除 Dress/Bikini/OnePiece/CoverUp 判别联合类型。
- [ ] 新增 ProductType、AttributeDefinition、Product、ProductVariant。
- [ ] 新增 PRODUCT/VARIANT scope。
- [ ] 新增 STRING/BOOLEAN/INTEGER/DECIMAL/ENUM valueType。
- [ ] 所有金额在 API 类型中使用字符串。
- [ ] 库存操作明确使用 variantId。

依赖：P0-02、C-01 至 C-04。

完成标准：类型文件不再固定列举四种商品响应字段。

### A-02 useProductApi

目标文件：`AdminPanelUI/app/composables/useProductApi.ts`。

- [ ] 删除 CATEGORY_CONFIG 和四套 basePath。
- [ ] 删除旧响应 normalize 分支。
- [ ] 增加 ProductType/AttributeDefinition API。
- [ ] 增加统一 Product CRUD。
- [ ] 增加 Variant CRUD 和库存 API。
- [ ] 增加 Category API。
- [ ] 统一错误处理和请求中的 snake_case。

依赖：A-01、C-01 至 C-04。

完成标准：composable 不调用 /dresses、/bikini-suits、/one-piece-suits、/cover-ups。

### A-03 动态 Product 表单

目标文件：`AdminPanelUI/app/components/ProductFormDrawer.vue`。

- [ ] 选择 ProductType 后加载 PRODUCT 属性定义。
- [ ] 根据 valueType 生成输入控件。
- [ ] required 定义进入 NForm 校验。
- [ ] ENUM 使用 NSelect。
- [ ] BOOLEAN 使用 NSwitch。
- [ ] INTEGER/DECIMAL 使用 NInputNumber。
- [ ] STRING 使用 NInput/NInput textarea。
- [ ] 删除所有 `if productType === DRESS` 等硬编码区域。
- [ ] 保留图片、主图、材质、标签、洗护和描述编辑。
- [ ] 类型已有 Product 使用后限制切换，或执行完整校验后切换。

依赖：A-02。

完成标准：新增 ProductType 和属性定义后无需修改组件代码即可显示表单。

### A-04 Variant 编辑器

建议新增：`AdminPanelUI/app/components/ProductVariantEditor.vue`。

- [ ] 支持多个 Variant 行。
- [ ] 编辑 SKU、size、color、USD price、stock、status、displayOrder。
- [ ] 根据 VARIANT 属性定义动态生成控件。
- [ ] 显示 optionSignature 冲突错误。
- [ ] 支持新增、复制、停用和删除 SKU。
- [ ] SKU 已创建后禁止直接修改。
- [ ] 颜色使用色板或颜色选择控件。
- [ ] 固定表格/网格尺寸，避免动态控件导致布局跳动。

依赖：A-02、A-03、C-04。

完成标准：比基尼可配置多组 top_size/bottom_size SKU。

### A-05 商品列表页

目标文件：`AdminPanelUI/app/pages/products/index.vue`。

- [ ] 列表主行展示 SPU，不再把每个 SKU 当商品。
- [ ] 类型筛选由 ProductType API 生成。
- [ ] 显示 Variant 数、价格区间、库存合计和低库存 SKU 数。
- [ ] 展开或抽屉查看 SKU。
- [ ] 库存调整先选择 Variant。
- [ ] Product 状态与 Variant 状态分别显示。
- [ ] 删除 DELETED 枚举依赖，使用 deletedAt/删除视图。
- [ ] 批量操作明确作用于 Product 或 Variant。

依赖：A-02、A-04。

完成标准：不存在 row.color、row.price、row.warehouseVolume 作为 Product 单值的假设。

### A-06 类型、属性和分类管理

- [ ] ProductType 管理视图。
- [ ] AttributeDefinition 管理视图。
- [ ] scope、valueType、required、filterable 和 allowedValues 编辑。
- [ ] ProductCategory 树形管理。
- [ ] 被使用的 code 不允许修改或删除。
- [ ] 使用菜单/选择器而不是自由文本输入固定选项。

依赖：A-02、C-01。

完成标准：管理员可以添加新类型和参数定义而不修改前端代码。

### A-07 订单、发货与仪表盘

- [ ] 订单类型增加 variant_id、sku。
- [ ] 订单和发货页从 productSnapshot 展示 SKU 与规格。
- [ ] 商品仪表盘区分 Product 数量和低库存 Variant 数量。
- [ ] 库存快捷操作跳转到具体 Variant。

依赖：C-05、S-08。

完成标准：管理端不把 product_id 当作 SKU 标识。

### A-08 管理端验证

- [ ] `npm run typecheck`。
- [ ] `npm run build`。
- [ ] ProductType/AttributeDefinition CRUD 测试。
- [ ] Product + 多 Variant 创建测试。
- [ ] 动态字段必填和值域测试。
- [ ] SKU 库存调整测试。
- [ ] 商品软删除与恢复测试。
- [ ] 订单和发货 SKU 展示测试。
- [ ] 桌面和移动视口截图检查。

依赖：A-01 至 A-07。

完成标准：无类型错误、构建错误、文本溢出或控件重叠。

## 8. P5：原子切换与清理

### X-01 实体扫描切换

- [ ] 更新 `ShopMallApplication` 的 EntityScan。
- [ ] 正式扫描 entity.jdbc 商品实体。
- [ ] 确保旧 jdbc.Product 和新 Product 不会同时作为实体。
- [ ] Spring 完整上下文在空数据库启动。

依赖：E、S、C 全部任务。

完成标准：`ShopMallApplicationTests.contextLoads` 通过。

### X-02 删除旧后端代码

- [ ] 删除 jdbc.Product 和四个商品子类。
- [ ] 删除四个品类 Repository。
- [ ] 删除四个品类 Service 与实现。
- [ ] 删除四套客户和管理端 Controller。
- [ ] 删除旧类型测试和继承测试。
- [ ] 删除 root.type()、is Dress、when(product) 等分支。
- [ ] 全项目搜索确认无旧类引用。

依赖：X-01。

完成标准：

~~~powershell
rg -n "BikiniSuit|OnePieceSuit|class Dress|class CoverUp|root\.type" src/main src/test
~~~

结果只允许出现在明确保留的商品类型初始化数据中，不允许出现类引用。

### X-03 空数据库初始化

- [ ] 删除旧数据库结构，不执行迁移。
- [ ] 使用新 JPA 映射创建空结构。
- [ ] 幂等初始化 ProductType、AttributeDefinition、ProductCategory。
- [ ] 开发环境重新创建 MockData。
- [ ] 生产初始化受显式配置控制。

依赖：X-01、S-03。

完成标准：全新 PostgreSQL 实例可直接启动并进入可创建商品状态。

### X-04 全量验收

后端：

- [ ] `./gradlew test`。
- [ ] PostgreSQL 集成测试。
- [ ] 并发库存与订单回补测试。
- [ ] 支付和退款回归。

管理端：

- [ ] `npm run typecheck`。
- [ ] `npm run build`。
- [ ] ProductType -> Product -> Variant -> 库存完整流程。

跨模块：

- [ ] 管理端创建商品。
- [ ] 客户端选择 SKU 加入购物车。
- [ ] 创建订单并扣库存。
- [ ] 取消订单并回补库存。
- [ ] 支付与退款状态保持正确。
- [ ] 订单和发货正确显示商品快照。

依赖：全部任务。

完成标准：所有检查通过后才允许删除旧数据库并发布。

## 9. 推荐提交划分

每个提交使用简体中文主题：

1. `feat(product): 新增商品类型与属性定义实体`
2. `refactor(product): 重构统一商品与 SKU 实体`
3. `refactor(product): 调整购物车和订单商品关联`
4. `feat(product): 实现类型属性校验与统一仓储`
5. `refactor(product): 统一商品与 SKU 服务`
6. `refactor(order): 按 SKU 处理购物车和订单库存`
7. `refactor(controller): 统一商品管理和客户接口`
8. `refactor(admin): 使用动态属性和 SKU 商品表单`
9. `refactor(admin): 更新商品列表、库存和订单展示`
10. `chore(product): 删除旧品类模型并切换空数据库结构`

提交顺序可以细分，但不得把未编译的中间状态合并到主分支。

## 10. 第一轮实施边界

第一轮建议只完成 P0 + P1：

- 冻结属性定义；
- 完成 ProductType、AttributeDefinition；
- 完成 Product、ProductVariant 和 Category；
- 完成关联实体草稿；
- 通过隔离 JPA 和 Validation 测试。

第一轮不切换 EntityScan、不删除旧业务类，也不启动新 Service Bean。这样可以先稳定数据模型，再进入 Service 和 API 重写。
