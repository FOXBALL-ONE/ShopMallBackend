# 首页商品推荐模块设计方案

## 1. 背景

ShopMall 客户端首页目前在 `frontend/app/pages/index.vue` 中直接调用通用商品列表接口，加载全部已发布商品后，在浏览器中筛选“新品”、按 `created_at` 排序并截取 4 个商品。该方案可以完成基础陈列，但存在以下问题：

- 首页展示内容不能由运营人员独立配置，调整推荐商品必须修改标签、商品数据或前端代码。
- 首页会遍历通用商品列表的全部分页；商品量增长后，首屏请求数、传输量和前端计算量都会增加。
- 只能表达单一“新品”逻辑，无法同时支持热卖、编辑精选、主题推荐和运营置顶。
- 缺少方案草稿、定时生效、预览、回滚、展示去重和失效商品自动补位。
- 缺少推荐曝光、点击和后续转化的上下文，运营无法评价推荐位效果。

本方案新增一个**运营可配置、规则可计算、整页原子发布**的首页商品推荐模块。首期不建设机器学习推荐系统，而采用“运营编排 + 确定性规则 + 自动兜底”的方式，先解决首页推荐内容可管理、可发布、可观测的问题。

## 2. 参考页面分析

参考页面：<https://www.cupshe.com/>，分析日期为 2026-08-12。

Cupshe 首页值得借鉴的不是某一套视觉样式，而是其模块化陈列思路：

1. 首页先用主题 Banner 和场景入口建立浏览动机，再进入商品推荐。
2. 使用 “WHAT'S HOT RIGHT NOW” 聚合当前重点商品，并用 “New Arrivals / Best Sellers” 等标签降低选择成本。
3. 推荐区不是孤立商品列表，还会与 “STYLE YOUR NEXT GETAWAY” 等场景化内容组合，引导用户按搭配和使用场景浏览。
4. 商品推荐、品牌内容、社区内容之间有明确楼层节奏，而不是把所有商品堆在一个列表中。
5. “新品”“畅销”“主题精选”分别代表时间、交易热度和人工策划三类信号，适合转换成 ShopMall 的推荐策略。

因此，ShopMall 首期建议将当前首页的单一 “New & Noteworthy” 区域升级为可配置推荐楼层，默认提供两个页签：

- **New Arrivals**：按上架/创建时间选择新品。
- **Best Sellers**：按 SKU 累计销量选择热卖商品。

后续再增加编辑精选、主题合集、猜你喜欢和搭配购，不在第一版同时引入复杂个性化算法。

## 3. 目标与非目标

### 3.1 目标

- 管理员可在 `AdminPanelUI/` 创建、编辑、排序、预览和发布首页推荐方案。
- 一个方案可包含多个推荐楼层；一个楼层可为单商品组或多页签商品组。
- 支持人工选品、自动新品、自动热卖和“人工置顶 + 自动补齐”。
- 发布时对整套推荐方案做原子切换，避免首页展示半新半旧的配置。
- 自动排除下架、软删除、无有效 SKU 或无库存商品，并按配置补位。
- 客户首页只请求一个专用聚合接口，不再为了推荐区加载全部商品。
- 返回数据直接满足现有 `ProductCard` 展示需要，金额统一为 USD，时间使用 ISO-8601 `LocalDateTime` 字符串。
- 为曝光、点击、加购和购买归因预留稳定的推荐上下文。

### 3.2 非目标

首期不包含：

- 基于深度学习、协同过滤或向量检索的实时个性化推荐。
- 广告竞价、付费推广和商家自助投放。
- 完整首页 CMS；Banner、公告、分类入口和品牌内容仍由各自模块管理。
- App、小程序等渠道；首期渠道固定为 `CUSTOMER_WEB`。
- 数据库迁移脚本。本项目约定数据库迁移不在默认范围，本方案只定义逻辑数据模型。

## 4. 总体设计

### 4.1 核心思路

采用四层模型：

```text
推荐方案 Plan
  └── 推荐楼层 Section
        └── 商品组 Group（单组或页签）
              └── 人工商品项 Item
```

- **Plan**：一次完整的首页推荐配置，是发布、排期和回滚的最小单位。
- **Section**：首页中的一个推荐楼层，例如 “What's Hot Right Now”。
- **Group**：楼层中的商品集合；当楼层为页签样式时，一个 Group 对应一个页签。
- **Item**：人工选择的商品及顺序，只用于 `MANUAL` 或 `HYBRID` 选品模式。

公开接口读取当前有效 Plan，服务端解析每个 Group 的候选商品、过滤不可售商品、补齐数量、跨楼层去重，最后一次性返回首页推荐结构。

### 4.2 为什么以整套 Plan 发布

如果每个楼层单独发布，运营同时调整多个楼层时，用户可能看到旧的新品区和新的热卖区组合。以 Plan 为发布单位可获得：

- 多楼层原子上线。
- 发布前完整预览和校验。
- 定时切换整套首页推荐内容。
- 复制历史方案后快速回滚。
- 缓存 Key 可绑定 `plan_id + version`，失效逻辑简单。

### 4.3 首期推荐页面结构

建议首页顺序调整为：

```text
Hero
购物权益条
分类入口
What's Hot Right Now（推荐模块：New Arrivals / Best Sellers）
主题内容区
可选 Editor's Picks（推荐模块：人工精选商品横滑）
品牌内容 / Newsletter / Footer
```

当前 `frontend/app/pages/index.vue` 的 “New & Noteworthy” 区域可直接由第一条已发布推荐楼层替代；当后台没有有效方案或接口异常时，前端不展示空白楼层，由后端或前端使用默认新品兜底。

## 5. 产品功能设计

## 5.1 管理后台

在 `AdminPanelUI/` 新增“首页推荐”菜单和页面：

```text
AdminPanelUI/app/pages/home-recommendations/index.vue
```

### 5.1.1 方案列表

列表字段：

- 方案名称。
- 状态：草稿、待生效、已发布、已下线、已过期、已归档。
- 生效时间、失效时间。
- 楼层数量、人工商品数量。
- 版本号。
- 创建人、更新人、更新时间。
- 操作：编辑、复制、预览、发布/排期、下线、归档。

筛选项：

- 关键词。
- 状态。
- 生效时间区间。
- 是否包含自动策略。

### 5.1.2 方案编辑器

编辑器采用三栏布局：

1. 左侧：楼层列表，支持拖拽排序、复制和删除。
2. 中间：当前楼层/商品组配置表单。
3. 右侧：桌面端与移动端即时预览。

方案级字段：

| 字段 | 说明 |
|---|---|
| `name` | 管理端方案名称，不展示给客户 |
| `effective_from` | 生效时间，ISO-8601 `LocalDateTime` |
| `effective_until` | 可选失效时间 |
| `fallback_enabled` | 方案解析失败时是否使用系统默认推荐 |
| `deduplicate_across_sections` | 是否跨楼层去重，默认开启 |

楼层字段：

| 字段 | 说明 |
|---|---|
| `code` | 稳定业务编码，例如 `whats_hot`，同一方案内唯一 |
| `eyebrow` | 小标题，例如 `TRENDING NOW` |
| `title` | 主标题 |
| `subtitle` | 可选说明 |
| `display_style` | `GRID`、`CAROUSEL`、`TABS` |
| `desktop_columns` | 桌面端列数，建议 4 |
| `mobile_columns` | 移动端列数，建议 2；Carousel 可忽略 |
| `link_label` | 可选“查看全部”文案 |
| `link_url` | 站内路径或 HTTPS URL |
| `item_limit` | 楼层总展示上限，建议 4～12 |
| `hide_when_empty` | 无商品时是否隐藏，默认开启 |
| `sort_order` | 楼层顺序 |

商品组字段：

| 字段 | 说明 |
|---|---|
| `code` | 稳定编码，例如 `new_arrivals` |
| `title` | 页签标题；非 TABS 楼层可不在前台单独显示 |
| `selection_mode` | `MANUAL`、`AUTO`、`HYBRID` |
| `strategy` | 自动策略，见第 7 节 |
| `item_limit` | 本组商品数量 |
| `category_id` | 可选分类限制 |
| `product_type` | 可选商品类型限制 |
| `tag_id` | 可选标签限制 |
| `lookback_days` | 新品统计窗口，默认 30 天 |
| `minimum_stock` | SKU 总可售库存下限，默认 1 |
| `fallback_strategy` | 候选不足时的补位策略 |
| `sort_order` | 页签/商品组顺序 |

人工选品区域支持：

- 按商品名、商品类型、分类和状态搜索。
- 展示主图、名称、最低价、总库存、总销量和状态。
- 拖拽排序。
- 标记为置顶商品。
- 显示校验警告：已下架、已删除、无可售 SKU、无库存、重复选品。
- 发布前自动剔除无效商品，但必须给出明确校验结果，不可静默发布一个完全为空的商品组。

### 5.1.3 发布与排期

发布规则：

- `effective_from <= 当前时间` 时立即发布。
- `effective_from > 当前时间` 时状态为 `SCHEDULED`。
- 同一渠道的方案生效时间段不得重叠。
- 发布动作在事务中完成，验证通过后一次性切换当前 Plan。
- 已发布方案不能直接原位编辑。点击编辑时复制为新草稿，避免线上配置随编辑过程变化。
- 下线当前方案后，若存在下一条已到期的排期方案，由调度任务立即激活；否则使用系统默认兜底。

### 5.1.4 预览

预览必须调用服务端解析逻辑，而不是只显示表单数据，以便提前发现：

- 商品下架或库存不足后实际会显示什么。
- 自动策略当前选中的商品。
- 去重后是否数量不足。
- 桌面端和移动端折行/横滑效果。
- “查看全部”链接是否有效。

预览响应不进入公共缓存，并显示“预览数据生成时间”。

## 5.2 客户前台

### 5.2.1 展示形态

首期提供三种形态：

1. `GRID`：固定网格，适合 4～8 个商品。
2. `CAROUSEL`：横向滑动，适合 6～12 个商品。
3. `TABS`：一个楼层中切换多个商品组，适合 Cupshe 风格的 “New Arrivals / Best Sellers”。

移动端行为：

- `GRID` 默认两列。
- `CAROUSEL` 每屏展示约 1.4～2.2 张商品卡，提示可横滑。
- `TABS` 页签吸附在楼层标题下方，切换时不重新请求接口。
- 图片沿用 `ProductCard` 的懒加载机制，首个推荐楼层前 4 张图片可 eager 加载，其余懒加载。

### 5.2.2 空态与降级

- 单个商品无效：跳过，并按该组 `fallback_strategy` 补位。
- 一个商品组为空：TABS 楼层隐藏该页签；单组楼层按 `hide_when_empty` 决定隐藏。
- 全部楼层为空：接口返回系统默认新品楼层。
- 推荐接口超时或 5xx：前端保留页面其他内容，不阻塞首页；可使用 `GET /api/products?page=1&size=4` 的轻量兜底，但不再请求全部分页。
- 推荐模块失败不应影响分类、公告、购物车和账号功能。

### 5.2.3 商品卡片

继续复用现有 `ProductCard`，推荐接口返回：

- 商品 ID、名称、分类、商品类型。
- 主图和备用图。
- 最低可售 SKU 价格，货币固定 USD。
- 可售 SKU、颜色、尺码、库存和销量。
- 标签、评分和推荐徽标。
- `recommendation_context`：请求、方案、楼层、商品组、位置等归因信息。

推荐模块不绕过现有商品详情、购物车和订单校验。即使首页缓存中短暂显示了刚售罄的商品，加入购物车和下单仍必须以后端实时库存结果为准。

## 6. 逻辑数据模型

以下为逻辑模型，不包含迁移脚本。

### 6.1 `HomeRecommendationPlan`

| 字段 | 类型 | 约束/说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `version` | `Long` | `@Version`，管理端并发编辑检测 |
| `name` | `String(120)` | 管理端名称 |
| `status` | Enum | `DRAFT/SCHEDULED/PUBLISHED/OFFLINE/EXPIRED/ARCHIVED` |
| `channel` | Enum | 首期固定 `CUSTOMER_WEB` |
| `effectiveFrom` | `LocalDateTime` | 必填 |
| `effectiveUntil` | `LocalDateTime?` | 必须晚于 `effectiveFrom` |
| `fallbackEnabled` | `Boolean` | 默认 `true` |
| `deduplicateAcrossSections` | `Boolean` | 默认 `true` |
| `createdBy` | `Long` | 管理员 ID |
| `updatedBy` | `Long` | 管理员 ID |
| `publishedAt` | `LocalDateTime?` | 实际发布时间 |
| `archivedAt` | `LocalDateTime?` | 归档时间 |
| `createdAt` | `LocalDateTime` | 自动生成 |
| `updatedAt` | `LocalDateTime` | 自动生成 |

建议索引：

- `(channel, status, effective_from, effective_until)`。
- `(status, updated_at)`。

### 6.2 `HomeRecommendationSection`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `planId` | `Long` | 所属方案 |
| `code` | `String(64)` | 方案内唯一，snake_case |
| `eyebrow` | `String(80)?` | 前台小标题 |
| `title` | `String(120)` | 前台主标题 |
| `subtitle` | `String(255)?` | 前台说明 |
| `displayStyle` | Enum | `GRID/CAROUSEL/TABS` |
| `desktopColumns` | `Int` | 2～6 |
| `mobileColumns` | `Int` | 1～2 |
| `linkLabel` | `String(40)?` | 查看全部文案 |
| `linkUrl` | `String(512)?` | 站内路径或 HTTPS |
| `itemLimit` | `Int` | 1～24 |
| `hideWhenEmpty` | `Boolean` | 默认 `true` |
| `sortOrder` | `Int` | 从小到大 |

约束：`(plan_id, code)` 唯一。

### 6.3 `HomeRecommendationGroup`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `sectionId` | `Long` | 所属楼层 |
| `code` | `String(64)` | 楼层内唯一 |
| `title` | `String(80)?` | 页签名 |
| `selectionMode` | Enum | `MANUAL/AUTO/HYBRID` |
| `strategy` | Enum | `NEW_ARRIVALS/BEST_SELLERS/HIGH_RATED/EDITOR_PICKS` |
| `itemLimit` | `Int` | 1～24 |
| `categoryId` | `Long?` | 分类过滤 |
| `productType` | `String(64)?` | 商品类型过滤 |
| `tagId` | `Long?` | 标签过滤 |
| `lookbackDays` | `Int?` | 1～365 |
| `minimumStock` | `Int` | 默认 1 |
| `fallbackStrategy` | Enum | `NONE/LATEST/BEST_SELLERS` |
| `sortOrder` | `Int` | 页签顺序 |

约束：`(section_id, code)` 唯一。

首期策略参数使用明确字段，不建议直接放任意 JSON，以便后端验证、后台生成表单和后续查询。只有当策略参数种类显著增加时，再评估引入受控的 JSON 配置。

### 6.4 `HomeRecommendationItem`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键 |
| `groupId` | `Long` | 所属商品组 |
| `productId` | `Long` | 商品 SPU ID |
| `pinned` | `Boolean` | HYBRID 中是否固定优先展示 |
| `customBadge` | `String(30)?` | 可选运营徽标，例如 `EDITOR'S PICK` |
| `sortOrder` | `Int` | 人工顺序 |
| `createdAt` | `LocalDateTime` | 创建时间 |

约束：`(group_id, product_id)` 唯一。

删除商品时不需要级联删除历史方案内容；解析阶段将不存在或已软删除商品视为无效项，以保留历史配置可审计性。

### 6.5 发布审计

建议增加 `HomeRecommendationAuditLog`，记录：

- 方案 ID、操作管理员、操作类型。
- 发布、排期、下线、复制、归档。
- 操作前后摘要和原因。
- 操作时间。

首期至少记录生命周期操作；普通字段编辑可依赖应用日志和 `version`，第二阶段再扩展完整字段快照。

## 7. 推荐策略

## 7.1 统一可售过滤

无论人工还是自动策略，商品在输出前必须满足：

1. `Product.status == ACTIVE`。
2. `deleted_at == null`。
3. 至少一个 `ProductVariant.status == ACTIVE`。
4. 至少一个有效 SKU 的 `warehouse_volume >= minimum_stock`。
5. SKU 价格大于 0，货币按项目约定输出 USD。
6. 商品至少有一张可展示图片；无主图时使用第一张图，无任何图片时首期排除。

人工商品不因失效而导致接口报错，应被过滤并进入补位流程。

## 7.2 `NEW_ARRIVALS`

首期排序：

```text
created_at DESC, updated_at DESC, product_id DESC
```

默认过滤最近 30 天；若窗口内数量不足：

- `fallback_strategy = LATEST`：放宽时间窗口，继续按创建时间补齐。
- `fallback_strategy = NONE`：返回实际数量。

现有标签中的 `new/new arrival` 可作为额外加权或运营筛选条件，但不应继续成为唯一“新品”来源，避免人工漏打标签导致新品消失。

## 7.3 `BEST_SELLERS`

首期可使用现有 SKU 累计销量：

```text
product_sales = SUM(active_variant.sales_volume)
排序：product_sales DESC, product.score DESC NULLS LAST, created_at DESC, product_id DESC
```

注意：当前字段代表累计销量，不代表最近 7/30 天趋势。第二阶段接入事件或订单聚合后，改为时间窗口销量，例如：

```text
sales_30d DESC, conversion_rate_30d DESC, score DESC, product_id DESC
```

在有可靠时间窗口数据前，不建议使用复杂加权公式，以免结果难以解释和验证。

## 7.4 `HIGH_RATED`

排序：

```text
score DESC, product_sales DESC, created_at DESC
```

只有当 `Product.score` 由已通过审核的客户评论聚合并定期更新后才开放该策略。当前若评分主要来自模拟数据，不应在生产管理端展示该选项。

## 7.5 `MANUAL`

- 严格按 `sort_order` 输出。
- 失效商品跳过。
- 数量不足时是否补位由 `fallback_strategy` 决定。
- 适合新品首发、主题活动、库存清理和编辑精选。

## 7.6 `HYBRID`

推荐用于长期运营：

1. 先输出有效的人工商品，`pinned = true` 优先。
2. 按人工 `sort_order` 保持相对顺序。
3. 使用自动 `strategy` 补齐到 `item_limit`。
4. 自动候选排除已出现的人工商品和全局已展示商品。

例如：运营固定 2 个主推款，其余 6 个位置由热卖策略自动更新。

## 7.7 去重规则

默认顺序：

1. 按 Section `sort_order`。
2. 按 Group `sort_order`。
3. 先解析高优先位置，后续位置排除已经展示的商品。

当开启跨楼层去重时：

- 同一个商品在同一首页响应中只展示一次。
- TABS 内是否去重建议默认关闭，因为用户明确切换“新品/热卖”时，同一商品可能同时具有两种属性；可提供 `deduplicate_within_section` 后续配置。
- 人工置顶项优先于后续自动候选。

## 8. 服务端接口设计

所有字段采用 snake_case。Controller 按项目约定：输入直接声明为 `@PathVariable`、`@RequestParam`、`@RequestHeader` 或 `@RequestPart`；端点内部声明局部 `data class Response` 和数据项；直接构造 domain command 与响应，不引入请求包装 DTO 或响应 mapper。

## 8.1 客户端接口

### 获取当前首页推荐

```http
GET /api/home/recommendations
```

可选参数：

- `section_limit`：最多返回多少个楼层，默认 10，最大 20。
- `product_limit_per_group`：客户端临时限制，不能超过后台配置和 24。

示例响应数据：

```json
{
  "plan_id": 12,
  "plan_version": 4,
  "request_id": "rec_01J...",
  "generated_at": "2026-08-12T19:25:14",
  "expires_at": "2026-08-12T19:26:14",
  "sections": [
    {
      "id": 31,
      "code": "whats_hot",
      "eyebrow": "TRENDING NOW",
      "title": "What's hot right now",
      "subtitle": null,
      "display_style": "TABS",
      "desktop_columns": 4,
      "mobile_columns": 2,
      "link_label": "Shop all",
      "link_url": "/collections/shop",
      "groups": [
        {
          "code": "new_arrivals",
          "title": "New Arrivals",
          "products": [
            {
              "id": 101,
              "product_type": "BIKINI",
              "category_id": 7,
              "name": "Example Product",
              "images": [
                {
                  "url": "https://example.test/product.jpg",
                  "alt_text": "Example Product",
                  "is_primary": true,
                  "sort_order": 0
                }
              ],
              "tags": ["new"],
              "score": 4.8,
              "badge": "NEW",
              "variants": [
                {
                  "id": 1001,
                  "sku": "SKU-001",
                  "size": "M",
                  "color": "Black",
                  "price": "39.99",
                  "currency": "USD",
                  "warehouse_volume": 21,
                  "sales_volume": 35,
                  "display_order": 0,
                  "attributes": []
                }
              ],
              "recommendation_context": {
                "request_id": "rec_01J...",
                "plan_id": 12,
                "section_code": "whats_hot",
                "group_code": "new_arrivals",
                "strategy": "NEW_ARRIVALS",
                "position": 1
              }
            }
          ]
        }
      ]
    }
  ]
}
```

接口语义：

- 匿名与登录用户首期返回相同方案，便于 CDN/Redis 缓存。
- 无有效运营方案时仍返回系统默认楼层，而不是 404。
- 如果所有兜底也无商品，返回 `sections: []` 和成功状态。
- `generated_at`、`expires_at` 使用 `LocalDateTime` 的 ISO-8601 文本。

### 推荐事件上报

第二阶段新增：

```http
POST /api/home/recommendations/events
```

直接参数：

- `event_type`：`SECTION_IMPRESSION/PRODUCT_IMPRESSION/PRODUCT_CLICK`。
- `request_id`。
- `section_code`。
- `group_code`。
- `product_id`，楼层曝光时可为空。
- `position`，商品事件必填。
- `occurred_at`，ISO-8601 `LocalDateTime`。

服务端不信任客户端上报的 plan、strategy 等任意文本，应通过 `request_id` 或签名上下文校验；接口需要限流和去重。

加购和购买归因不额外依赖前端自由文本，可在购物车项或短期 Redis 归因上下文中记录经过校验的 `request_id + product_id`。

## 8.2 管理端接口

建议前缀：

```text
/admin/api/home-recommendations
```

### 方案

```http
GET    /admin/api/home-recommendations/plans
GET    /admin/api/home-recommendations/plans/{plan_id}
POST   /admin/api/home-recommendations/plans
PUT    /admin/api/home-recommendations/plans/{plan_id}
POST   /admin/api/home-recommendations/plans/{plan_id}/copy
POST   /admin/api/home-recommendations/plans/{plan_id}/publish
POST   /admin/api/home-recommendations/plans/{plan_id}/offline
POST   /admin/api/home-recommendations/plans/{plan_id}/archive
GET    /admin/api/home-recommendations/plans/{plan_id}/preview
```

方案创建/编辑直接接收：

- `name`
- `expected_version`（编辑时）
- `effective_from`
- `effective_until`
- `fallback_enabled`
- `deduplicate_across_sections`

### 楼层

```http
POST   /admin/api/home-recommendations/plans/{plan_id}/sections
PUT    /admin/api/home-recommendations/sections/{section_id}
DELETE /admin/api/home-recommendations/sections/{section_id}
POST   /admin/api/home-recommendations/sections/{section_id}/copy
PUT    /admin/api/home-recommendations/plans/{plan_id}/section-order
```

`section-order` 可使用重复的 `section_id` 请求参数表达有序 ID 列表，不引入请求包装 DTO。

### 商品组与人工商品

```http
POST   /admin/api/home-recommendations/sections/{section_id}/groups
PUT    /admin/api/home-recommendations/groups/{group_id}
DELETE /admin/api/home-recommendations/groups/{group_id}
PUT    /admin/api/home-recommendations/groups/{group_id}/items
```

首期人工项仅需有序商品 ID 时，`items` 接口可使用重复的 `product_id` 参数；若同时支持 `pinned` 和 `custom_badge`，可按现有管理商品接口风格使用 `@RequestPart` 传 JSON 字符串并在端点内解析、直接构造 command，仍不创建 Controller 请求 DTO。

## 9. 后端模块划分

建议新增：

```text
src/main/kotlin/top/foxball/shopmall/
├── controller/HomeRecommendationController.kt
├── controller/admin/AdminHomeRecommendationController.kt
├── entity/jdbc/HomeRecommendationPlan.kt
├── entity/jdbc/HomeRecommendationSection.kt
├── entity/jdbc/HomeRecommendationGroup.kt
├── entity/jdbc/HomeRecommendationItem.kt
├── repository/HomeRecommendationPlanRepository.kt
├── repository/HomeRecommendationSectionRepository.kt
├── repository/HomeRecommendationGroupRepository.kt
├── repository/HomeRecommendationItemRepository.kt
├── service/HomeRecommendationService.kt
├── service/AdminHomeRecommendationService.kt
└── service/impl/...
```

### 9.1 服务职责

`HomeRecommendationService`：

- 查找当前有效方案。
- 解析各组策略。
- 批量加载商品、SKU 和主图。
- 做可售过滤、补位、去重。
- 生成稳定的 `recommendation_context`。
- 读写公共缓存。

`AdminHomeRecommendationService`：

- 草稿 CRUD。
- 排序、复制、校验、预览。
- 生命周期和排期冲突控制。
- 发布、下线、归档和审计。
- 发布后缓存失效。

策略解析首期可使用一个服务内的 `when(strategy)`，不要为四个简单策略过早建立复杂插件框架。只有当策略超过约 6 种、参数和数据源明显分化时，再抽象 `RecommendationCandidateProvider`。

### 9.2 查询优化

不要按楼层、商品组和商品逐级触发懒加载。建议：

1. 一次加载当前 Plan 及有序 Section、Group、Item ID。
2. 汇总人工商品 ID。
3. 自动策略通过限定数量的仓储查询直接选候选 ID，不加载全表后在 Kotlin 内排序。
4. 合并所有候选 ID 后，批量加载商品详情、图片和有效 SKU。
5. 内存中完成最终顺序、补位和去重。

可在 `ProductRepository`/专用查询仓储中增加只读查询：

- 按创建时间查询可售商品 ID。
- 按 SKU 销量聚合查询可售商品 ID。
- 按评分查询商品 ID。
- 按一组商品 ID 批量加载推荐卡片所需数据。

公开首页接口不能复用当前“遍历全部 `/api/products` 分页”的前端做法。

## 10. 缓存与一致性

### 10.1 缓存分层

建议两层缓存：

1. **方案结构缓存**
   - Key：`home:recommendation:plan:CUSTOMER_WEB:{plan_id}:{version}`。
   - 内容：Section、Group、人工 Item 和策略配置。
   - TTL：5～30 分钟；发布/下线时主动删除。

2. **已解析响应缓存**
   - Key：`home:recommendation:response:CUSTOMER_WEB:{plan_id}:{version}`。
   - 内容：公共匿名首页推荐响应。
   - TTL：30～60 秒。
   - 用短 TTL 控制价格、库存、商品状态变化造成的陈旧窗口。

### 10.2 缓存失效

以下事件使已解析响应失效：

- 推荐方案发布、下线、过期或切换。
- 推荐方案内容变化并重新发布。
- 商品上下架、删除/恢复。
- SKU 状态变化。
- 可选：库存从有货变为 0 或从 0 恢复有货。

商品价格和普通库存变化可以依靠 30～60 秒 TTL，不必每次库存扣减都广播全量失效。交易路径始终实时校验库存，因此短暂陈旧只影响陈列，不影响交易正确性。

### 10.3 调度

复用项目现有 Spring 调度方式，每分钟执行：

- 激活 `effective_from <= now` 的 `SCHEDULED` 方案。
- 将 `effective_until <= now` 的活动方案标记为 `EXPIRED`。
- 在事务中处理同渠道方案切换并清理缓存。

时间统一使用应用约定的 `LocalDateTime`，不向 JDBC 直接传 `Instant`。

## 11. 前端改造

## 11.1 客户前端 `frontend/`

建议新增：

```text
frontend/app/
├── composables/useHomeRecommendationApi.ts
├── components/home/HomeRecommendationSection.vue
├── components/home/HomeRecommendationTabs.vue
├── components/home/HomeRecommendationCarousel.vue
└── types/home-recommendation.ts
```

首页改造：

- 删除首页为推荐区调用 `catalogApi.listProducts()` 并加载全部分页的逻辑。
- 使用 `useAsyncData('home-recommendations', ...)` 请求专用接口。
- 分类入口仍通过 `useCatalogCategories()` 获取；分类主图以后可再改为独立分类配置，避免依赖第一件商品图片。
- 使用 `v-for` 渲染后台已发布 Section。
- 推荐楼层组件只负责展示，不在组件中重新计算新品/热卖。
- 继续复用 `ProductCard`，在点击和曝光时传递 `recommendation_context`。

SSR 注意事项：

- 首期为公共推荐，可安全共享 SSR/服务端缓存。
- 第三阶段引入个性化后，不能让带用户身份的响应进入公共 Nuxt 缓存；公共 Plan 和用户重排结果必须分开。

### 11.2 管理前端 `AdminPanelUI/`

建议新增：

```text
AdminPanelUI/app/
├── pages/home-recommendations/index.vue
├── components/HomeRecommendationPlanEditor.vue
├── components/HomeRecommendationSectionEditor.vue
├── components/HomeRecommendationProductPicker.vue
├── composables/useHomeRecommendationApi.ts
└── types/home-recommendation.ts
```

在 `AdminPanelUI/app/layouts/default.vue` 增加“首页推荐”菜单。该功能属于运营管理，必须放在 `AdminPanelUI/`，不能放到客户侧 `frontend/`。

## 12. 埋点与效果评估

## 12.1 事件

建议记录：

- `section_impression`：楼层至少 50% 可见并持续 1 秒。
- `product_impression`：商品卡片至少 50% 可见并持续 1 秒；同一 `request_id + product_id + group` 去重。
- `product_click`：点击商品卡片。
- `add_to_cart`：由购物车成功响应侧关联推荐上下文。
- `purchase`：订单支付成功后按商品关联最近有效推荐上下文。

## 12.2 指标

后台第二阶段可展示：

- 楼层曝光 UV/PV。
- 商品曝光量。
- 点击率 `CTR = clicks / product_impressions`。
- 加购率 `add_to_cart / product_clicks`。
- 点击购买转化率。
- 推荐归因 GMV，货币统一 USD。
- 各策略在相同位置的对比。

建议点击归因窗口 7 天、曝光归因窗口 24 小时；点击优先于曝光。具体窗口应作为业务配置，不写死在前端。

## 13. 权限、安全与校验

- 所有 `/admin/api/home-recommendations/**` 接口由现有 `/admin/api/**` ADMIN 规则保护。
- 管理接口以 `@AuthenticationPrincipal adminId: Long` 记录操作人。
- 发布/下线使用 `expected_version` 做乐观锁冲突提示。
- `link_url` 只允许单个 `/` 开头的站内路径或 HTTPS URL，禁止 `javascript:`、双斜杠和编码绕过；可复用公告模块的安全规则，但只有在多处调用时才抽取共享的独立 URL 校验抽象。
- 标题、说明、徽标均按纯文本输出，不允许直接注入 HTML。
- 商品 ID、分类 ID、标签 ID 必须存在；发布时再次校验，不只依赖编辑时校验。
- 预览接口仅管理员可访问，响应设置 `Cache-Control: no-store`。
- 事件上报接口需要请求频率限制、字段长度限制和幂等去重，禁止客户端直接上传金额或收益数据。

## 14. 错误处理

### 管理端

- `400`：参数、时间范围、链接、布局或商品组配置非法。
- `404`：方案、楼层、商品组或商品不存在。
- `409`：版本冲突、排期冲突、发布状态冲突。
- 发布校验返回结构化问题列表，至少包含 `level`、`section_code`、`group_code`、`product_id` 和 `message`。

阻断发布的错误：

- 方案没有楼层。
- 楼层没有商品组。
- TABS 楼层少于两个有效商品组。
- 生效时间冲突。
- 所有商品组解析后都为空且未开启兜底。

可警告但允许发布：

- 某个人工商品已下架。
- 实际商品数少于配置数量但仍大于 0。
- 多个组之间存在重复商品。

### 客户端

- 无 Plan 或无商品不是异常，返回成功空数组或默认方案。
- 单条脏配置不能导致整个首页 500；记录错误后跳过该楼层。
- 数据库/Redis 短暂失败时优先返回最近成功的短期缓存；无缓存时返回空楼层并记录指标。

## 15. 测试设计

### 15.1 后端单元测试

- 新品排序、时间窗口和兜底。
- 热卖销量聚合及并列排序稳定性。
- MANUAL/HYBRID 顺序与补位。
- 商品状态、SKU 状态、库存和图片过滤。
- 同楼层/跨楼层去重。
- TABS 空组隐藏。
- 默认方案生成。
- 方案时间冲突、状态流转和版本冲突。
- 安全 URL 校验。

### 15.2 Repository 集成测试

- 自动策略查询只返回可售商品。
- 分类、类型、标签过滤。
- 销量聚合正确。
- 生效时间边界：等于 `effective_from` 时生效，等于 `effective_until` 时失效。
- 发布事务保证同渠道只有一个当前有效方案。

### 15.3 Controller/API 测试

- snake_case 入参与响应。
- `LocalDateTime` ISO-8601 序列化。
- ADMIN 权限和匿名公共读取。
- 400/404/409 错误。
- Controller 局部响应 `data class` 与 `ResponseBuilder` 输出格式。
- 预览 `no-store`。

### 15.4 前端测试

- GRID/CAROUSEL/TABS 渲染。
- 页签切换不重复请求。
- 加载骨架、空态和接口失败降级。
- 移动端两列与横滑。
- 商品点击携带正确推荐上下文。
- 不可见商品不误报曝光。

### 15.5 验收场景

1. 管理员创建含 New Arrivals 和 Best Sellers 的 TABS 楼层并立即发布，客户首页刷新后可见。
2. 排期方案在指定 `effective_from` 自动切换，旧方案不再展示。
3. 人工置顶商品下架后自动跳过，并由策略商品补齐。
4. 商品售罄后最多在缓存 TTL 内消失，加购接口仍实时拒绝无库存 SKU。
5. 复制历史方案并发布可完成回滚。
6. 推荐接口失败不影响首页 Hero、分类、公告和导航。
7. 首页不再为一个推荐区加载全部商品分页。

## 16. 可观测性

建议指标：

- `home_recommendation_request_total{result}`。
- `home_recommendation_resolve_duration_ms`。
- `home_recommendation_cache_hit_total{layer}`。
- `home_recommendation_empty_group_total{strategy}`。
- `home_recommendation_filtered_product_total{reason}`。
- `home_recommendation_plan_switch_total{result}`。
- `home_recommendation_event_rejected_total{reason}`。

日志必须携带：

- HTTP `request_id`。
- `recommendation_request_id`。
- `plan_id`、`plan_version`。
- `section_code`、`group_code`。
- 解析失败原因和被过滤商品数量。

正常请求不要逐商品打印 INFO 日志，避免首页流量造成日志放大；商品级过滤详情使用 DEBUG 或聚合指标。

## 17. 分阶段交付

### Phase 1：可运营的首页推荐

- Plan/Section/Group/Item 逻辑模型。
- 管理端草稿、编辑、排序、预览、立即发布、下线和复制。
- GRID、CAROUSEL、TABS。
- MANUAL、NEW_ARRIVALS、BEST_SELLERS、HYBRID。
- 可售过滤、补位、去重和默认新品兜底。
- 公共聚合接口和 30～60 秒缓存。
- 首页替换当前全量商品拉取逻辑。

### Phase 2：运营数据闭环

- 定时排期和自动过期的完整管理体验。
- 曝光、点击、加购、购买归因。
- 最近 7/30 天销量与转化率聚合。
- 推荐效果报表。
- 发布审计和结构化校验问题。
- A/B 试验：同一位置按稳定访客分桶选择不同 Plan 或 Group。

### Phase 3：个性化与搭配

- 最近浏览、购物车、购买品类偏好。
- 个性化重排，但保留运营置顶和安全过滤。
- “猜你喜欢”。
- “Shop the Look” 搭配组：主视觉 + 多商品组合。
- 多渠道、多地区和多语言方案。

## 18. 针对当前 ShopMall 的落地建议

第一版不要直接建设复杂推荐算法，建议按以下最小方案落地：

1. 新增一个默认 Plan：`homepage_default`。
2. 新增一个 TABS Section：`whats_hot`。
3. Group A：`new_arrivals`，`AUTO + NEW_ARRIVALS`，8 件，最近 30 天，不足时用 `LATEST` 补齐。
4. Group B：`best_sellers`，`AUTO + BEST_SELLERS`，8 件，不足时用 `LATEST` 补齐。
5. 允许运营在每组固定最多 2 件商品，形成 `HYBRID`。
6. 桌面端 4 列，移动端 2 列；首屏先显示 4 件，可继续横滑或查看全部。
7. 后端新增 `GET /api/home/recommendations`，首页不再调用 `listProducts()` 遍历全部分页。
8. 管理后台新增“首页推荐”，但不把功能混入现有“商品管理”表单，避免商品基础信息与首页运营编排互相耦合。
9. 首期以累计 `sales_volume` 实现热卖，文档和 UI 明确标为“累计热卖”；有 30 天聚合数据后再改为“近期热卖”。
10. 先完成发布、过滤、补位和缓存，再做埋点与个性化。

该方案既能复现 Cupshe 首页“新品 + 热卖 + 场景化楼层”的核心运营能力，又与 ShopMall 当前 Kotlin/Spring Boot、Nuxt 客户端、Nuxt 管理后台和统一商品模型保持一致，并给后续数据驱动推荐留下清晰演进路径。
