# 快递物流模块设计：承运商抽象 · 拆包履约 · 自动签收

> 背景：本模块负责 ShopMall 的「分配商品 → 创建面单 → 发出 → 在途 → 签收」全链路，依赖 `docs/order-system-design.md` 中的订单状态机。面向跨境独立站，承运商以海外/跨境专线为主。
>
> **实施前提**：当前仓库尚未落地 `OrderEntity`、`OrderRepository`、outbox、订单消费者和订单调度器，因此本文描述的是目标契约，不能按“复用现有订单实现”直接编码。实施顺序必须是：先落地订单核心契约与通用 outbox，再落地物流模块；也可在同一迭代中完成，但必须通过本文 §九的集成测试后一起上线。
>
> 技术栈：Spring Boot 4.1.0 + Kotlin 2.3.21 + Java 25 + PostgreSQL + Redis（Lettuce）。不引入 Kafka 或具体承运商 SDK；沿用 `ResponseBuilder`、`@AuthenticationPrincipal userId: Long`、`AdminAccessService`，异步投递使用 DB outbox + Redis Stream。物流强一致部分以 PostgreSQL 事务、订单行锁、唯一/部分唯一索引为准，Redis 不作为履约正确性的唯一依据。

---

## 一、要解决的核心问题

物流链路需要解决以下问题：

1. **整单 SHIPPED 后无运单可追踪**：`OrderEntity.markShipped` 只写 `shippedAt`，无 `carrier`/`trackingNo`，客户与管理端都无法查在途轨迹。
2. **不支持拆包发货**：跨境备货常分仓/分包裹发出（头程空运 + 尾程派送、现货 + 预售合单），整单 `markShipped` 是「一单一包裹」语义，无法表达多包裹。
3. **DELIVERED 全靠人工**：无承运商 webhook / 轮询，包裹签收后订单长期卡在 `SHIPPED`。
4. **部分履约容易误判整单完成**：只检查“已有运单是否签收”不足以证明“所有订单行项均已签收”，必须同时校验行项覆盖。
5. **取消后必须可重新发货**：作废面单需要保留审计记录，同时释放其商品分配，允许创建替代运单。

### 设计目标

- **承运商可插拔**：统一 `Carrier` 抽象 + 适配器，首期可零外部承运商接入（MANUAL 闭环），后续接 4PX / YunExpress / 17Track 不改核心履约流程。
- **拆包发货**：一个 `Order` 可有多张 `Shipment`（运单），每张独立承运商/单号/轨迹。
- **聚合状态机联动**：首个包裹真正发出时整单进入 `SHIPPED`；仅当全部订单行项都有有效运单分配，且这些运单全部签收时，整单才进入 `DELIVERED`。
- **自动签收 + 兜底**：承运商 webhook 优先推进签收；`@Scheduled` 定时器兜底「已签收未回写」与「超期未签收」两种异常。
- **轨迹全量留痕**：每条物流轨迹独立行存储，前端可渲染时间线、管理端可审计。
- **幂等与最终一致**：承运商 webhook、轮询和管理端重试都是 at-least-once；数据库唯一约束和条件 UPDATE 是权威幂等机制，outbox 负责跨进程投递。
- **跨境时间正确**：承运商时间统一转换为 `Instant` 并落 PostgreSQL `TIMESTAMPTZ`，API 返回带时区的 ISO-8601，不用无时区 `LocalDateTime` 表示跨国事件。

### 关键决策（与本期范围）

| 维度 | 选择 | 理由 |
|---|---|---|
| 承运商接入 | 统一抽象 + 适配器 | 不绑 SDK，首期可只启用 MANUAL，后续接 4PX/YunExpress/17Track 增加适配器与配置 |
| 运单归属 | 一对多（`Order` 1 : N `Shipment`） | 跨境分仓/分包裹发货刚需；表结构预留，首期可只发一包裹 |
| 运单生命周期 | `LABEL_PENDING → LABEL_CREATED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED` | 区分“请求创建面单”“已有面单”和“真正发出”，避免创建运单即视为在途 |
| 发出触发 | 管理端 dispatch 或承运商首条在途事件 | 首个真正发出的包裹才推进整单 `PAID → SHIPPED` |
| 签收触发 | webhook 优先 + 定时轮询 + MANUAL 管理端签收 | 兼顾真实承运商与首期 MANUAL 闭环 |
| 轨迹存储 | 独立 `ShipmentTrack` 明细表 | 可查历史、可审计、前端时间线渲染 |
| 整单状态联动 | 首 Shipment 发出 → SHIPPED；全部订单行项已分配且有效 Shipment 全签收 → DELIVERED | 防止只发部分商品就误判整单签收 |
| 并发串行化 | 所有履约写操作先锁订单行 | 串行化同订单的创建、发出、取消、签收和聚合，消除并发签收漏推进 |
| 取消重发 | 历史明细标记 `RELEASED` + PostgreSQL 部分唯一索引 | 保留审计，同时允许同一订单行项重新分配 |
| schema 管理 | Flyway 必选，生产 `ddl-auto=validate` | 正确性依赖部分唯一索引和约束，不能依赖 `ddl-auto=update` 猜测迁移 |

> **状态语义**：创建运单或面单不等于发货。`SHIPPED` 以管理端确认 dispatch 或承运商首条在途事件为准；`DELIVERED` 以“所有订单行项都有当前有效分配，且所有有效运单均为 `DELIVERED`”为准。未分配行项、已释放行项或待替换的取消运单都会阻止整单签收。

---

## 二、整体架构

```
管理端 POST /api/admin/orders/{orderNo}/shipments
  │  ① Idempotency-Key 校验；锁定 Order 行（PESSIMISTIC_WRITE）
  │  ② 校验订单为 PAID/SHIPPED，行项属于该订单且当前未被有效运单占用
  │  ③ remoteLabel=false：trackingNo 必填，创建 LABEL_CREATED
  │     remoteLabel=true：trackingNo 可空，创建 LABEL_PENDING，并写 SHIPMENT_LABEL_REQUESTED outbox
  │  ④ 写 ShipmentItem(ALLOCATION=ALLOCATED)，部分唯一索引防并发重复分配
  ▼
[面单消费者]
  │  以 shipmentNo 作为承运商幂等业务引用调用 createLabel
  │  LABEL_PENDING → LABEL_CREATED，回填最终 trackingNo/carrierLabelUrl；失败抛出并由 outbox 重试
  ▼
[发出]
  │  管理端 POST .../{shipmentNo}/dispatch，或承运商首条 IN_TRANSIT 轨迹
  │  锁 Order；Shipment LABEL_CREATED → IN_TRANSIT；首包裹推进 Order PAID → SHIPPED
  ▼
[webhook / 定时轮询 / MANUAL 签收]
  │  原始 ByteArray 验签并解析 TrackingEvent(trackingNo, eventId, normalizedStatus, occurredAt)
  │  INSERT ShipmentTrack ... ON CONFLICT DO NOTHING；只有新事件继续推进
  │  lastTrack 摘要仅在事件更新时才覆盖，乱序旧事件不能回退摘要
  │  状态按 LABEL_CREATED → IN_TRANSIT → OUT_FOR_DELIVERY → DELIVERED 单向推进
  ▼
[订单级聚合 reconcileOrderDelivery(orderNo)]
  │  锁 Order；查询订单行项覆盖 + 有效运单状态（不复用“运单刚转签收”作为入口条件）
  │  全部行项有 ALLOCATED 分配且所有有效运单 DELIVERED → Order SHIPPED → DELIVERED
  │  每张运单首次签收写 SHIPMENT_DELIVERED；整单首次签收另写订单 DELIVERED
```

### 无错保证链

| 风险 | 防护机制 |
|---|---|
| 重复分配同一 OrderItem | `shipment_items` 部分唯一索引仅允许一个 `ALLOCATED` 行；取消时改为 `RELEASED` 后可重新分配 |
| 部分商品误判整单签收 | 聚合同时检查订单行项覆盖和有效运单状态，不只检查已有 Shipment |
| 并发创建/取消/签收 | 所有同订单履约写操作先锁 Order 行，再执行条件 UPDATE |
| 并发签收漏推进 | `reconcileOrderDelivery` 是独立、可重复调用的订单级函数；定时器可直接补偿已签收运单对应的滞后订单 |
| CANCELLED 被回调复活 | `markDelivered` 只允许 `IN_TRANSIT/OUT_FOR_DELIVERY → DELIVERED`，永不接受 CANCELLED |
| 重复 webhook / 轮询 | `uk_track_shipment_event(shipment_id, carrier_event_id)` + `INSERT ... ON CONFLICT DO NOTHING`，数据库是权威幂等点 |
| webhook 事务失败 | 不在数据库提交前写不可回滚的 Redis“已处理”标记；数据库失败后承运商重投仍可处理 |
| 轨迹乱序 | 明细全量保存；摘要 UPDATE 带 `lastTrackAt` 条件，Shipment 业务状态只前进不回退 |
| 管理端请求重试 | `Idempotency-Key` + 请求哈希；相同 key/相同请求返回原结果，不同请求返回 409 |
| 唯一约束冲突 | 已分配/单号重复等业务冲突返回 409；仅锁超时、死锁、连接异常返回 503 + Retry-After |

---

## 三、数据模型

### 3.1 `Shipment` — `entity/jdbc/Shipment.kt`

> 运单聚合根。一张运单 = 一个承运商一个追踪号下的一批商品。一个 `Order` 可有多张（拆包）。
>
> 运单用 `orderId` 引用订单并由 Flyway 建真实外键。API 先按 `orderNo` 定位并锁定订单，再按 `orderId` 操作运单；不能只存无外键的业务字符串，否则会产生孤儿运单。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long?` | `@Id @GeneratedValue(IDENTITY)` |
| `shipmentNo` | `String` | 业务单号，`@Column(unique, length=32)`，索引 `idx_shipment_no`；生成规则见 §5.8（含随机段不可枚举） |
| `orderId` | `Long` | `orders(id)` 外键，`nullable=false, updatable=false`；索引 `idx_shipment_order_status(order_id, status)` |
| `carrierCode` | `CarrierCode` | `@Enumerated(STRING) length=24`；见 §4.1 |
| `trackingNo` | `String?` | 承运商最终追踪号；`remoteLabel=false` 时创建必填，远程面单成功后回填 |
| `trackingNoNormalized` | `String?` | 去空格并按承运商规则规范化，仅内部查询；部分唯一索引 `uk_shipment_carrier_tracking` 覆盖 `(carrier_code, tracking_no_normalized) WHERE tracking_no_normalized IS NOT NULL` |
| `status` | `ShipmentStatus` | `@Enumerated(STRING) length=24`，见 §3.4 |
| `shippingAddress` | `OrderShippingAddress` | `@Embedded` 收货地址快照（**深拷贝自 `OrderEntity.shippingAddress`**，运单创建时冻结，对齐订单快照语义） |
| `shippedAt` | `Instant?` | 运单真正发出时间，由 dispatch 或首条在途事件写入，不在创建运单时写 |
| `deliveredAt` | `Instant?` | 运单签收时间，取承运商签收事件的 `occurredAt`；MANUAL 取管理员命令时间 |
| `carrierLabelUrl` | `String?` | 面单 URL，`length=512`；仅管理端响应可见，客户端不返回 |
| `trackingUrl` | `String?` | 面向客户的承运商查询链接，`length=512`；不得与面单 URL 混用 |
| `lastTrackStatus` | `String?` | 承运商最新轨迹状态码，`length=64`，与 ShipmentTrack.statusCode 一致 |
| `lastTrackAt` | `Instant?` | 最新轨迹事件时间；仅当新事件排序更晚时更新 |
| `lastTrackEventId` | `String?` | `length=128`；相同时间戳的稳定排序键，防摘要在并发/乱序事件之间抖动 |
| `lastTrackLocation` | `String?` | 最新轨迹所在地，`length=200` |
| `nextTrackPollAt` | `Instant?` | 下次轮询时间；仅 `capabilities.polling=true` 的适配器按退避策略更新 |
| `pollLeaseOwner` | `String?` | 当前轮询领取者实例 id；仅调度内部使用 |
| `pollLeaseUntil` | `Instant?` | 轮询租约过期时间，支持实例故障后接管 |
| `consecutiveTrackFailures` | `Int` | 连续轮询失败次数，成功后清零；达到阈值只告警/延长退避，不永久停止追踪 |
| `lastTrackError` | `String?` | 最近轮询错误摘要，`length=500`，不存密钥或完整响应 |
| `createdBy` | `Long` | 创建运单的管理员 userId（审计），`@Min(1)` |
| `cancelReason` | `String?` | `length=200`，运单取消原因（如面单作废） |
| `note` | `String?` | 管理端备注，`length=200`；对应创建请求中的 `note`，不得静默丢弃 |
| `createdAt / updatedAt` | `Instant?` | `@CreationTimestamp` / `@UpdateTimestamp`，数据库列使用 `TIMESTAMPTZ` |
| `version` | `Long` | `@Version`，保护非批量更新路径；核心状态推进仍使用条件 UPDATE |

> **`carrierCode` 用 `@Enumerated(STRING)` 存 `CarrierCode` 枚举**，而非裸字符串：新增承运商只加枚举值 + 适配器，DB 可读且防拼写错误。枚举值集见 §4.1。

### 3.2 `ShipmentItem` — `entity/jdbc/ShipmentItem.kt`

运单明细：一张运单包含哪些订单行项。本期不拆行项数量，但允许面单取消后将原分配释放，再创建替代运单。

- `id: Long?` IDENTITY
- `shipment: Shipment?` — `@ManyToOne(LAZY, optional=false) @JoinColumn(name="shipment_id", nullable=false, updatable=false)` + `@get:JsonIgnore`
- `orderItemId: Long` — `order_items(id)` 外键，`nullable=false, updatable=false`
- `orderItemSnapshot: String` — 发货时的商品名/颜色/尺码 JSON 快照（深拷贝自 `OrderItem.productSnapshot`），`length=2000`，历史可读
- `quantity: Int` — 必须等于对应 `OrderItem.quantity`；本期整行分配，不允许部分数量拆分
- `allocationStatus: AllocationStatus` — `ALLOCATED` / `RELEASED`
- `releasedAt: Instant?`、`releaseReason: String?` — 取消运单时在同一订单锁和事务内写入
- `@CreationTimestamp createdAt`（商品快照与数量不可修改，只有分配状态可从 ALLOCATED 单向转为 RELEASED）

> **有效分配唯一约束**：Flyway 创建 PostgreSQL 部分唯一索引：
>
> ```sql
> CREATE UNIQUE INDEX uk_shipment_item_active
> ON shipment_items(order_item_id)
> WHERE allocation_status = 'ALLOCATED';
> ```
>
> 这样同一行项同时只能属于一个有效运单；取消时保留历史行并改为 `RELEASED`，随后可重新分配。约束冲突是稳定的业务冲突，映射为 409，而不是可重试 503。

### 3.3 `ShipmentTrack` — `entity/jdbc/ShipmentTrack.kt`

物流轨迹明细，全量留痕。承运商每次回传一条轨迹写一行。

```kotlin
@Entity
@Table(
    name = "shipment_tracks",
    uniqueConstraints = [UniqueConstraint(name = "uk_track_shipment_event", columnNames = ["shipment_id", "carrier_event_id"])],
    indexes = [Index(name = "idx_track_shipment_ts", columnList = "shipment_id, occurred_at, carrier_event_id")],
)
class ShipmentTrack(
    @Id @GeneratedValue(IDENTITY) var id: Long? = null,
    @ManyToOne(LAZY, optional=false) @JoinColumn(name="shipment_id", nullable=false, updatable=false) @get:JsonIgnore var shipment: Shipment? = null,
    @Column(name="carrier_event_id", nullable=false, length=128) var carrierEventId: String = "",
    @Column(nullable=false, length=64) var statusCode: String = "",
    @Enumerated(STRING) @Column(name="normalized_status", nullable=false, length=32) var normalizedStatus: NormalizedTrackingStatus = NormalizedTrackingStatus.UNKNOWN,
    @Enumerated(STRING) @Column(nullable=false, length=16) var source: TrackSource = TrackSource.WEBHOOK,
    @Column(length=200) var location: String? = null,
    @Column(length=500) var description: String? = null,
    @Column(name="occurred_at", nullable=false) var occurredAt: Instant? = null,
    @Column(columnDefinition="text") var raw: String? = null,
    @CreationTimestamp @Column(name="received_at", nullable=false, updatable=false) var receivedAt: Instant? = null,
)
```

设计要点：
- **数据库幂等**：使用 `INSERT ... ON CONFLICT (shipment_id, carrier_event_id) DO NOTHING RETURNING id`。只有返回新 id 时才更新摘要和业务状态；Redis 不承担“已处理”真相源。
- **事件 id 兜底**：承运商没有稳定事件 id 时，适配器按 `carrier + trackingNoNormalized + statusCode + occurredAt + location` 生成稳定 SHA-256 摘要，不能使用每次变化的接收时间。
- **时间与排序**：`occurredAt` 转为 UTC `Instant`；时间线按 `(occurredAt, carrierEventId)` 升序。摘要只接受排序更晚的事件，旧事件照常留痕但不能覆盖最新摘要。
- **状态翻译**：适配器将原始状态映射为 `IN_TRANSIT` / `OUT_FOR_DELIVERY` / `DELIVERED` / `EXCEPTION` / `UNKNOWN`。业务状态只消费前三种，异常和未知状态仅展示、告警。
- **原始载荷**：`raw` 仅供受控排障，按配置保留并脱敏；不得返回客户端，也不得记录 webhook 密钥或完整请求头。

### 3.4 `ShipmentStatus` 枚举 — `entity/jdbc/ShipmentStatus.kt`

运单状态机（与订单状态机联动但独立）：

```
LABEL_PENDING ──► LABEL_CREATED ──► IN_TRANSIT ──► OUT_FOR_DELIVERY ──► DELIVERED
      │                 │
      └──► CANCEL_PENDING ◄─────┘
                  │
                  └──► CANCELLED
```

- `LABEL_PENDING`：本地运单已创建，等待异步承运商面单结果；不是“面单已创建”。
- `LABEL_CREATED`：面单已创建（适配器 `createLabel` 成功）或管理端手动填单号后待揽收。
- `CANCEL_PENDING`：远程面单正在取消，行项仍保持 ALLOCATED，避免取消未确认时重复发货。
- `IN_TRANSIT`：承运商报「已揽收/在途」。
- `OUT_FOR_DELIVERY`：派送中（尾程）。
- `DELIVERED`：签收（终态之一）。
- `CANCELLED`：面单请求/面单已确认作废，并已释放其 `ShipmentItem` 分配（终态之一）。

> 多词值用 `OUT_FOR_DELIVERY` 风格（对齐订单 `PENDING_PAYMENT` 约定）。`DELIVERED`/`CANCELLED` 为终态。
>
> **与订单状态机的联动**（核心）：
> - 整单 `PAID → SHIPPED`：首个 Shipment 真正进入 `IN_TRANSIT` 时推进，创建面单不推进订单。
> - 整单 `SHIPPED → DELIVERED`：所有 `OrderItem` 均存在 `ALLOCATED` 分配，且这些分配所属 Shipment 全部为 `DELIVERED`。
> - `CANCELLED` 绝不允许被 webhook 推进为其他状态；取消事务必须同步释放其 ALLOCATED 行项，所以整单在替代运单签收前不会完成。
> - 承运商直接回传 `OUT_FOR_DELIVERY` 或 `DELIVERED` 时，服务先在同一订单锁内补做 `LABEL_CREATED → IN_TRANSIT` 和订单 `PAID → SHIPPED`，再单向推进到目标状态。

---

## 四、承运商抽象与适配器

### 4.1 `CarrierCode` 枚举 — `entity/jdbc/CarrierCode.kt`

```kotlin
enum class CarrierCode(val pathValue: String) {
    MANUAL("manual"),
    FOUR_PX("4px"),
    YUN_EXPRESS("yunexpress"),
    TRACK17("17track"),
    ;
    companion object {
        fun fromPath(value: String): CarrierCode? =
            entries.firstOrNull { it.pathValue.equals(value, ignoreCase = true) }
    }
}
```

> `MANUAL` 是首期默认承运商：创建时填单号得到 `LABEL_CREATED`，随后由管理端 dispatch 和 delivered 命令完成闭环。它不参与 webhook 或轮询，不能用“定时器空转”代替签收入口。

### 4.2 `Carrier` 抽象 — `logistics/Carrier.kt`

```kotlin
package top.foxball.shopmall.logistics

/** 承运商适配器抽象。每个承运商实现一个 Bean，由 [CarrierRegistry] 按 [CarrierCode] 分发。 */
interface Carrier {
    val code: CarrierCode
    val capabilities: CarrierCapabilities

    /**
     * 创建面单。request.idempotencyReference 固定为 shipmentNo；承运商支持幂等键时必须透传，
     * 不支持时重试前先按 merchantReference 查询既有面单，避免超时后重复创建。
     */
    fun createLabel(request: LabelRequest): LabelResponse

    /** 取消尚未发出的远程面单；结果必须能区分“已取消/不存在”和“暂时失败”。 */
    fun cancelLabel(request: CancelLabelRequest): CancelLabelResult

    /** 拉取当前轨迹。仅 capabilities.polling=true 时调用。 */
    fun queryTracking(trackingNo: String): List<TrackingEvent>

    /** 对原始字节验签并解析；不得先转 String 再计算签名。 */
    fun parseWebhook(payload: ByteArray, headers: Map<String, List<String>>): List<TrackingEvent>

    /** 承运商单号规范化，用于唯一索引与 webhook 反查。 */
    fun normalizeTrackingNo(trackingNo: String): String

    /** 面向客户的公开查询页；与包含 PII 的面单 URL 分离。 */
    fun trackingUrl(trackingNo: String): String?
}

data class CarrierCapabilities(
    val remoteLabel: Boolean,
    val webhook: Boolean,
    val polling: Boolean,
)

data class LabelRequest(
    val shipmentNo: String,
    val idempotencyReference: String = shipmentNo,
    val requestedTrackingNo: String?,
    val shippingAddress: OrderShippingAddress,
    val items: List<ShipmentItemSnapshot>,
)

data class LabelResponse(
    val labelUrl: String?,
    val trackingNo: String,
)

data class CancelLabelRequest(
    val shipmentNo: String,
    val trackingNo: String?,
    val idempotencyReference: String = "cancel:$shipmentNo",
)

enum class CancelLabelResult { CANCELLED_OR_NOT_FOUND, RETRYABLE_FAILURE }

/** 统一轨迹事件，承运商适配器把各自 schema 翻译成此形态。 */
data class TrackingEvent(
    val trackingNo: String,
    val carrierEventId: String,
    val statusCode: String,
    val normalizedStatus: NormalizedTrackingStatus,
    val location: String?,
    val description: String?,
    val occurredAt: Instant,
    val raw: String?,
)
```

### 4.3 `CarrierRegistry` — `logistics/CarrierRegistry.kt`

```kotlin
@Component
class CarrierRegistry(carriers: List<Carrier>) {
    private val byCode: Map<CarrierCode, Carrier> = carriers
        .groupBy { it.code }
        .mapValues { (code, beans) ->
            require(beans.size == 1) { "承运商 $code 必须且只能注册一个适配器，实际 ${beans.size}" }
            beans.single()
        }

    fun require(code: CarrierCode): Carrier = byCode[code]
        ?: error("未注册的承运商适配器: $code（请确认对应 Carrier Bean 已装配）")
}
```

> 应用启动时还要校验所有 `enabled=true` 的承运商均有且只有一个适配器，并校验所需密钥非空；配置错误必须启动失败，不能等到首个 webhook 才暴露。

### 4.4 `ManualCarrier` 首期实现 — `logistics/ManualCarrier.kt`

```kotlin
@Component
class ManualCarrier : Carrier {
    override val code = CarrierCode.MANUAL
    override val capabilities = CarrierCapabilities(
        remoteLabel = false,
        webhook = false,
        polling = false,
    )

    override fun createLabel(request: LabelRequest): LabelResponse =
        throw UnsupportedOperationException("MANUAL 不创建远程面单")

    override fun cancelLabel(request: CancelLabelRequest): CancelLabelResult =
        throw UnsupportedOperationException("MANUAL 不取消远程面单")

    override fun queryTracking(trackingNo: String): List<TrackingEvent> =
        throw UnsupportedOperationException("MANUAL 不支持轮询")

    override fun parseWebhook(payload: ByteArray, headers: Map<String, List<String>>) =
        throw UnsupportedOperationException("MANUAL 承运商不接收 webhook")

    override fun normalizeTrackingNo(trackingNo: String) = trackingNo.trim().uppercase()
    override fun trackingUrl(trackingNo: String): String? = null
}
```

---

## 五、并发控制与流程实现

### 5.1 事务边界与订单级串行化

所有会改变履约事实的入口都遵循同一顺序：`createShipment`、`dispatchShipment`、`cancelShipment`、`handleTrackingEvent`、`markManualDelivered`、`reconcileOrderDelivery`。

1. 按 `orderNo` 或 `orderId` 执行 `SELECT ... FOR UPDATE` 锁定订单行。
2. 在锁内重新读取订单状态、运单状态和行项分配，不使用锁前加载的实体判断。
3. 执行条件 UPDATE、分配变更和 outbox INSERT。
4. 事务提交后才调用外部承运商；外部结果通过新的短事务条件回填。

订单行锁把同一订单的创建、取消、发出和签收串行化，避免两个包裹同时签收时彼此看不到未提交状态、最终无人推进整单。不同订单仍可并行处理。

管理端写接口都要求 `Idempotency-Key`。`logistics_idempotency` 以 `(actor_id, operation, idempotency_key)` 唯一，保存请求哈希和结果引用：相同 key + 相同请求返回原结果；相同 key + 不同请求返回 409。

### 5.2 创建运单与异步面单

```kotlin
@Transactional
fun createShipment(orderNo: String, request: CreateShipmentRequest, adminId: Long, key: String): ShipmentResponse {
    idempotencyService.replayOrReserve(adminId, "CREATE_SHIPMENT", key, hash(request))?.let { return it }
    val order = orderRepository.lockByOrderNo(orderNo) ?: throw OrderNotFoundException()
    require(order.status == PAID || order.status == SHIPPED) { "当前订单不可创建运单" }

    val lines = orderItemRepository.findAllByIdForOrder(request.items.map { it.orderItemId }, order.id!!)
    validateDistinctAndWholeLineQuantity(request.items, lines)
    validateNoActiveAllocation(lines.mapNotNull { it.id })

    val carrier = carrierRegistry.require(request.carrierCode)
    validateTrackingInput(carrier.capabilities, request.trackingNo)
    val initialStatus = if (carrier.capabilities.remoteLabel) LABEL_PENDING else LABEL_CREATED
    val normalized = request.trackingNo?.let(carrier::normalizeTrackingNo)

    val shipment = shipmentRepository.save(
        Shipment(
            shipmentNo = shipmentNoGenerator.next(),
            orderId = order.id!!,
            carrierCode = request.carrierCode,
            trackingNo = request.trackingNo,
            trackingNoNormalized = normalized,
            status = initialStatus,
            shippingAddress = deepCopy(order.shippingAddress),
            createdBy = adminId,
            note = request.note,
        )
    )
    shipmentItemRepository.saveAll(lines.map { it.toAllocatedShipmentItem(shipment) })

    if (initialStatus == LABEL_PENDING) {
        eventPublisher.publishInTx("SHIPMENT", shipment.id!!, "SHIPMENT_LABEL_REQUESTED", labelPayload(shipment))
    }
    val response = shipmentQueryRepository.getResponse(shipment.id!!) // 从数据库投影重读
    idempotencyService.complete(adminId, "CREATE_SHIPMENT", key, response)
    return response
}
```

创建运单**不**写 `shippedAt`，也不推进订单 `SHIPPED`。远程面单消费者按以下协议处理：

- 事务外读取不可变 `LabelRequest` 快照，并以 `shipmentNo` 作为承运商 merchant reference / idempotency key 调 `createLabel`。
- 调用成功后开启短事务，条件更新 `LABEL_PENDING → LABEL_CREATED`，回填最终 `trackingNo`、规范化单号、`carrierLabelUrl` 和 `trackingUrl`。
- 若此时运单已进入 `CANCEL_PENDING`，仍记录承运商返回的单号，然后立即走取消面单流程，不恢复为 `LABEL_CREATED`。
- 外部调用失败必须抛出，让 outbox 保持可重试；不能先写 Redis “processed” 再吞掉失败。处理成功后才 ACK outbox。
- `(carrier_code, tracking_no_normalized)` 冲突返回运维错误并进入 `NEEDS_REPLAY`，不得静默绑定到已有运单。

### 5.3 发出、取消与 MANUAL 签收

**发出**：管理端 dispatch 或首条标准化 `IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED` 事件调用 `ensureDispatchedLocked`。它只允许 `LABEL_CREATED → IN_TRANSIT`，写 `shippedAt`，并在适配器支持 polling 时登记 `nextTrackPollAt`；随后尝试订单 `PAID → SHIPPED`。订单已经 `SHIPPED` 时视为追加包裹，其他状态为冲突。首次发出写 `SHIPMENT_DISPATCHED` outbox。

**取消**：只允许 `LABEL_PENDING` / `LABEL_CREATED`，已发出运单不能取消，只能进入售后异常流程。

- MANUAL：在同一订单事务内直接 `→ CANCELLED`，并把所属 `ShipmentItem.ALLOCATED → RELEASED`。
- 远程面单：先 `→ CANCEL_PENDING` 并写 `SHIPMENT_CANCEL_REQUESTED` outbox；适配器确认面单已取消或不存在后，再锁订单并 `→ CANCELLED`、释放行项。取消失败保持 `CANCEL_PENDING` 并重试/告警，不能提前释放后创建可能重复履约的替代运单。
- `CANCELLED` 是不可逆终态。任何后到轨迹都保留审计并告警，但不能改变运单或订单状态。

**MANUAL 签收**：`POST .../delivered` 仅允许 MANUAL 或具备特权的人工纠错流程。命令先确保 dispatch，再插入 `source=MANUAL` 的合成轨迹；`carrierEventId` 使用 `manual:{shipmentId}:{sha256(idempotencyKey).take(32)}`，随后复用正常签收和聚合逻辑。

### 5.4 Webhook、轮询与轨迹推进

控制器最多读取 `max-webhook-body-bytes` 原始字节，适配器在 `ByteArray` 上验签，并按 `(occurredAt, carrierEventId)` 排序事件。未知运单记录脱敏审计并返回 2xx，避免承运商持续重投其他平台单号。

```kotlin
@Transactional
fun handleTrackingEvent(carrierCode: CarrierCode, event: TrackingEvent, source: TrackSource) {
    val carrier = carrierRegistry.require(carrierCode)
    val normalizedNo = carrier.normalizeTrackingNo(event.trackingNo)
    val lookup = shipmentRepository.findIdentityByCarrierAndTrackingNo(carrierCode, normalizedNo)
        ?: return auditUnknownTracking(carrierCode, normalizedNo, event.carrierEventId)

    val order = orderRepository.lockById(lookup.orderId) ?: return auditOrphanShipment(lookup.id)
    val shipment = shipmentRepository.findByIdForUpdate(lookup.id) ?: return

    val inserted = shipmentTrackRepository.insertOnConflictDoNothing(shipment.id!!, event, source)
    if (inserted) {
        shipmentRepository.updateLastTrackIfNewer(shipment.id!!, event)
    }

    when (event.normalizedStatus) {
        IN_TRANSIT -> ensureDispatchedLocked(order, shipment, event.occurredAt)
        OUT_FOR_DELIVERY -> {
            ensureDispatchedLocked(order, shipment, event.occurredAt)
            shipmentRepository.markOutForDelivery(shipment.id!!)
        }
        DELIVERED -> {
            ensureDispatchedLocked(order, shipment, event.occurredAt)
            val changed = shipmentRepository.markDelivered(
                shipment.id!!,
                allowed = setOf(ShipmentStatus.IN_TRANSIT, ShipmentStatus.OUT_FOR_DELIVERY),
                deliveredAt = event.occurredAt,
            )
            if (changed == 1) {
                eventPublisher.publishInTx("SHIPMENT", shipment.id!!, "SHIPMENT_DELIVERED", shipmentPayload(shipment.id!!))
            }
            // 即使运单此前已 DELIVERED，也要执行聚合，以修复历史事务在整单推进前失败的情况。
            reconcileOrderDeliveryLocked(order)
        }
        EXCEPTION, UNKNOWN -> Unit
    }
}
```

关键约束：

- `insertOnConflictDoNothing` 与后续状态更新在同一数据库事务内；事务回滚后轨迹唯一键也回滚，重投不会被 Redis 标记吞掉。
- `markDelivered` 只允许 `IN_TRANSIT/OUT_FOR_DELIVERY → DELIVERED`，不得使用 `status != DELIVERED`。
- `updateLastTrackIfNewer` 条件为 `lastTrackAt IS NULL OR lastTrackAt < :occurredAt OR (lastTrackAt = :occurredAt AND lastTrackEventId < :eventId)`。
- 乱序旧事件仍写明细，但不能回退摘要或业务状态。`DELIVERED`/`CANCELLED` 后业务状态不再变化。

### 5.5 订单级签收聚合

`reconcileOrderDelivery(orderNo/orderId)` 是独立公开的应用服务方法，不依赖“某张运单刚从非签收转为签收”。入口自行锁订单；已持锁的 webhook/管理端流程调用 `reconcileOrderDeliveryLocked`。

```kotlin
private fun reconcileOrderDeliveryLocked(order: OrderEntity) {
    // ensureDispatchedLocked 可能刚通过批量 UPDATE 推进订单，必须 fresh 查询，不能读旧实体字段。
    if (orderRepository.findStatusById(order.id!!) != OrderStatus.SHIPPED) return
    val summary = fulfillmentQueryRepository.summarize(order.id!!)

    val fullyAllocated = summary.orderItemCount > 0 &&
        summary.orderItemCount == summary.allocatedDistinctOrderItemCount
    val allAllocatedShipmentsDelivered = summary.nonDeliveredAllocatedShipmentCount == 0
    if (!fullyAllocated || !allAllocatedShipmentsDelivered) return

    val deliveredAt = summary.maxAllocatedShipmentDeliveredAt ?: return
    if (orderRepository.markDelivered(order.id!!, SHIPPED, DELIVERED, deliveredAt) == 1) {
        eventPublisher.publishInTx("ORDER", order.id!!, "DELIVERED", orderPayload(order.id!!))
    }
}
```

`fulfillmentQueryRepository.summarize` 必须从 `order_items` 左连接当前 `ALLOCATED` 的 `shipment_items` 与 `shipments` 计算，不能只查询 Shipment 列表。`CANCEL_PENDING` 仍保留 ALLOCATED 分配且不是 DELIVERED，因此会阻止整单完成；`RELEASED` 分配不计入覆盖。

### 5.6 Repository、outbox 与持久化上下文

Repository 至少提供以下原子操作：

- `OrderRepository.lockById/lockByOrderNo`：`PESSIMISTIC_WRITE`。
- `ShipmentRepository.markLabelCreated`：仅 `LABEL_PENDING → LABEL_CREATED`；取消竞态下不覆盖 `CANCEL_PENDING`。
- `markInTransit`：仅 `LABEL_CREATED → IN_TRANSIT`。
- `markOutForDelivery`：仅 `IN_TRANSIT → OUT_FOR_DELIVERY`。
- `markDelivered`：仅 `IN_TRANSIT/OUT_FOR_DELIVERY → DELIVERED`。
- `markCancelPending`：仅 `LABEL_PENDING/LABEL_CREATED → CANCEL_PENDING`。
- `markCancelled`：仅 `CANCEL_PENDING → CANCELLED`；MANUAL 可用独立条件 UPDATE 从 `LABEL_CREATED → CANCELLED`。
- `ShipmentTrackRepository.insertOnConflictDoNothing`：返回是否新插入。
- `ShipmentItemRepository.releaseAllocatedByShipmentId`：只把 `ALLOCATED → RELEASED`。

通用 outbox 不能继续假设 `aggregateId` 永远是 orderId。订单模块落地时将契约定义为：

```kotlin
publishInTx(
    aggregateType: String, // ORDER / SHIPMENT
    aggregateId: Long,
    eventType: String,
    payload: String,
)
```

表使用 `domain_outbox`（或迁移既有 `order_outbox`）并增加 `aggregate_type`。`SHIPMENT_DELIVERED` 的 aggregateId 始终是 `shipment.id: Long`，每张运单首次签收都发布；整单首次完成另发 `aggregateType=ORDER, eventType=DELIVERED, aggregateId=order.id`。

使用 `@Modifying(clearAutomatically=true)` 后不得再从旧实体生成 payload 或响应。事件 payload 使用 id 重新查询投影，接口响应同样从数据库重读，避免返回旧状态、空时间或触发已分离实体的懒加载。

### 5.7 定时轮询与聚合补偿

轮询不是“发出 30 天后才第一次查询”。`nextTrackPollAt` 控制正常活跃轮询，例如发出后 15 分钟开始，成功后按 30 分钟至 6 小时逐步退避；`stale-delivery-days` 只用于超期告警。

多实例不使用固定 TTL 的全局 Redis 选主锁。调度器通过 PostgreSQL `FOR UPDATE SKIP LOCKED` 批量领取到期运单，并写 `pollLeaseOwner/pollLeaseUntil`；外部调用在领取事务提交后执行。租约过期可被其他实例接管，避免长扫描超过 Redis TTL 后重复选主。

单个轮询成功后调用 `handleTrackingEvent(..., source=POLL)`，更新 `nextTrackPollAt` 并清零连续失败次数。失败只增加 `consecutiveTrackFailures`、记录脱敏错误并退避；达到阈值后告警，但不能永久停止尚未签收的运单。

另设 `OrderDeliveryReconciliationScheduler` 扫描 `status=SHIPPED` 的候选订单，直接调用 `reconcileOrderDelivery(orderId)`。它不能通过已签收 Shipment 再调用 `markDelivered`，否则会因运单已经 DELIVERED 而提前返回，无法修复订单滞后。

物流模块自行提供 `LogisticsSchedulingConfig` 并显式 `@EnableScheduling`，不依赖订单模块是否已经加注解。

### 5.8 业务编号与时间

- **运单号**：`S` + UTC 日期时间 + PostgreSQL sequence + 6 位 CSPRNG 随机段，总长不超过 32；`shipment_no` 唯一约束兜底，冲突时有限重试。编号生成不依赖 Redis 可用性。
- **时间**：持久化统一使用 `Instant` / PostgreSQL `TIMESTAMPTZ`。适配器负责解析承运商时区或 offset；缺少时区的载荷必须使用该承运商明确配置的默认时区并记录解析来源，不能默认使用应用服务器时区。

---

## 六、API 与安全

### 6.1 端点（`controller/ShipmentController.kt`）

遵循订单模块 `OrderController` 风格：`@RestController`、类级无 `@RequestMapping`、构造注入 service + `AdminAccessService` + `ResponseBuilder`、方法内 `data class Response(...)` 局部封装、`@AuthenticationPrincipal`、`@Valid @RequestBody`、`@field:` 校验。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/admin/orders/{orderNo}/shipments` | admin + Idempotency-Key | 创建运单并分配整行商品；MANUAL 填 trackingNo，远程面单可不填 |
| GET | `/api/admin/orders/{orderNo}/shipments` | admin | 管理端列运单 + 轨迹 |
| GET | `/api/orders/{orderNo}/shipments` | authenticated + owner | 客户查自己的运单与轨迹（非 owner → 404，关闭存在性预言机） |
| GET | `/api/orders/{orderNo}/shipments/{shipmentNo}` | authenticated + owner | 单运单详情 + 轨迹时间线 |
| GET | `/api/logistics/track/{carrier}/{trackingNo}` | authenticated | 按承运商 + 追踪号查轨迹（限本订单 owner 或 admin） |
| POST | `/api/admin/shipments/{shipmentNo}/dispatch` | admin + Idempotency-Key | 确认发出；`LABEL_CREATED → IN_TRANSIT`，首包裹推进订单 SHIPPED |
| POST | `/api/admin/shipments/{shipmentNo}/cancel` | admin + Idempotency-Key | 作废未发出运单；远程面单先进入 CANCEL_PENDING |
| POST | `/api/admin/shipments/{shipmentNo}/delivered` | admin + Idempotency-Key | MANUAL 签收或受审计的人工纠错，复用正常签收聚合 |
| POST | `/api/logistics/webhook/{carrier}` | permitAll + carrier signature | 承运商回调，原始 ByteArray 验签，受请求体大小和网关限流保护 |

> **owner 校验**：客户端点先 `orderRepository.findByOrderNoAndCustomerId(orderNo, userId)`，找不到统一 404。按 carrier + trackingNo 查询时先由复合唯一键定位 Shipment，再校验其 orderId 的 owner/admin，不能只因用户已登录就返回轨迹。

### 6.2 请求体 schema

```kotlin
data class CreateShipmentRequest(
    @field:NotNull val carrierCode: CarrierCode,
    @field:Size(max = 64) val trackingNo: String?,
    @field:NotNull @field:Size(min = 1, max = 50) @field:Valid val items: List<ShipmentItemRequest>,
    @field:Size(max = 200) val note: String? = null,
)

data class ShipmentItemRequest(
    @field:Min(1) val orderItemId: Long,
    @field:Min(1) val quantity: Int,
)

data class DispatchShipmentRequest(
    @field:Size(max = 200) val note: String? = null,
)

data class CancelShipmentRequest(
    @field:NotBlank @field:Size(max = 200) val reason: String,
)

data class ManualDeliveredRequest(
    val occurredAt: Instant? = null, // null 时取服务端 Clock.instant()
    @field:NotBlank @field:Size(max = 200) val reason: String,
)
```

服务层执行跨字段校验：`remoteLabel=false`（包括 MANUAL 和仅追踪型适配器）时 `trackingNo` 必填；`remoteLabel=true` 时允许为空。`items.orderItemId` 必须互不重复，`quantity` 必须等于订单行数量。所有命令的 `Idempotency-Key` 长度限制为 1..128，并与请求哈希绑定。

### 6.3 承运商验签

每个适配器 `parseWebhook(payload: ByteArray, headers: Map<String, List<String>>)` 自行验签：

- **4PX**：HMAC-SHA256 over 原始体 + `X-4PX-Signature` 头，密钥从配置注入。
- **YunExpress**：类似 HMAC，头名与密钥单独配置。
- **17Track**：按供应商正式 webhook 认证协议实现；不能把普通查询 API Key 自动等同于 webhook 签名。
- **MANUAL**：路由层直接返回 404，不调用适配器。

安全约束：

- HMAC 直接覆盖收到的原始 `ByteArray`，使用常量时间比较；绝不先解码再重新编码。
- 控制器使用有上限的流读取，超过 `max-webhook-body-bytes` 返回 413；反向代理同步设置 body limit 和按 IP/承运商限流。
- 承运商支持时间戳/nonce 时校验允许的时钟偏差和重放窗口；数据库事件唯一键仍是最终幂等兜底。
- 各承运商密钥独立。`enabled=true` 时密钥为空必须启动失败；未知/禁用 carrier 与 MANUAL 统一返回 404，不进入默认适配器。
- 验签失败返回 401，响应不包含签名计算细节；日志只记录 carrier、请求 id 和脱敏摘要。

### 6.4 SecurityConfig 调整 — `config/SecurityConfig.kt`

```kotlin
it.requestMatchers(HttpMethod.POST, "/api/logistics/webhook/**").permitAll()
it.requestMatchers(HttpMethod.GET, "/api/orders/*/shipments/**").authenticated()
```

> 管理端命令继续由现有 `/api/admin/**` `hasRole("ADMIN")` 保护，并在 service 层调用 `AdminAccessService` 做业务审计。Webhook 不与 `/api/admin/**` 路径重叠，但必须在 `anyRequest().authenticated()` 前声明。

### 6.5 配置追加 — `src/main/resources/application.yaml`

```yaml
shopmall:
  logistics:
    webhook-max-body-bytes: "${LOGISTICS_WEBHOOK_MAX_BODY_BYTES:1048576}"
    poll-initial-delay-seconds: "${LOGISTICS_POLL_INITIAL_DELAY:900}"
    poll-max-delay-seconds: "${LOGISTICS_POLL_MAX_DELAY:21600}"
    poll-lease-seconds: "${LOGISTICS_POLL_LEASE_SECONDS:120}"
    track-max-consecutive-failures: "${LOGISTICS_TRACK_MAX_CONSECUTIVE_FAILURES:5}"
    stale-delivery-days: "${LOGISTICS_STALE_DELIVERY_DAYS:30}"  # 仅告警阈值，不是首次轮询时间
    raw-track-retention-days: "${LOGISTICS_RAW_TRACK_RETENTION_DAYS:30}"
    carriers:
      4px:
        enabled: "${CARRIER_4PX_ENABLED:false}"
        webhook-secret: "${CARRIER_4PX_WEBHOOK_SECRET:}"
        api-key: "${CARRIER_4PX_API_KEY:}"
      yunexpress:
        enabled: "${CARRIER_YUN_ENABLED:false}"
        webhook-secret: "${CARRIER_YUN_WEBHOOK_SECRET:}"
        api-key: "${CARRIER_YUN_API_KEY:}"
      track17:
        enabled: "${CARRIER_17TRACK_ENABLED:false}"
        webhook-secret: "${CARRIER_17TRACK_WEBHOOK_SECRET:}"
        api-key: "${CARRIER_17TRACK_API_KEY:}"
```

> `.env.example` 追加对应占位。未启用的适配器允许密钥为空；启用后必须完整配置并通过启动校验。

### 6.6 响应模型 — `controller/ShipmentResponses.kt`

沿用 `OrderResponses.kt` 的 DTO 风格，但通过 `ShipmentQueryRepository` 投影构建 `CustomerShipmentResponse` / `AdminShipmentResponse`，不从批量 UPDATE 后的旧实体直接映射。

```kotlin
data class CustomerShipmentResponse(
    val shipmentNo: String,
    val orderNo: String,
    val carrier: String,
    val trackingNo: String?,
    val trackingUrl: String?,
    val status: String,
    val shippedAt: Instant?,
    val deliveredAt: Instant?,
    val lastTrackStatus: String?,
    val lastTrackLocation: String?,
    val lastTrackAt: Instant?,
    val items: List<ShipmentItemResponse>,
    val tracks: List<ShipmentTrackResponse>,
)

data class AdminShipmentResponse(
    val shipment: CustomerShipmentResponse,
    val carrierLabelUrl: String?,
    val createdBy: Long,
    val note: String?,
    val cancelReason: String?,
    val consecutiveTrackFailures: Int,
    val lastTrackError: String?,
)
```

> 客户不需要打印商家出库面单，因此 `carrierLabelUrl` 只在管理端响应出现；客户端只得到公开 `trackingUrl`。`raw`、`createdBy`、内部错误、轮询租约等字段不进入客户模型。管理端查看 raw 轨迹应使用单独受审计的诊断端点，而不是默认列表接口。

### 6.7 异常 — `handler/BusinessException.kt`

新增子类（沿用现有模式）：

| 异常 | HttpStatus | 默认消息 |
|---|---|---|
| `ShipmentNotFoundException` | 404 NOT_FOUND | 运单不存在 |
| `ShipmentStatusException` | 409 CONFLICT | 运单状态不允许此操作 |
| `ShipmentAllocationConflictException` | 409 CONFLICT | 商品已分配给其他有效运单 |
| `TrackingNumberConflictException` | 409 CONFLICT | 承运商追踪号已绑定其他运单 |
| `IdempotencyConflictException` | 409 CONFLICT | 幂等键已用于不同请求 |
| `CarrierException` | 502 BAD_GATEWAY | 承运商服务异常 |
| `CarrierSignatureException` | 401 UNAUTHORIZED | 承运商回调验签失败 |
| `WebhookPayloadTooLargeException` | 413 PAYLOAD_TOO_LARGE | Webhook 请求体过大 |

> 不得把所有 `DataAccessException` 都当作 503。服务层按约束名翻译 `uk_shipment_item_active`、`uk_shipment_carrier_tracking` 和幂等唯一键为 409；`CannotAcquireLockException`、死锁、连接临时故障等才映射为 503 + `Retry-After`。未知数据完整性错误保持 500 并告警，避免把永久错误伪装成可重试故障。

---

## 七、与订单模块的联动契约

| 订单模块要素 | 物流模块联动 |
|---|---|
| `OrderRepository.lockById/lockByOrderNo` | 所有履约写入口先取得订单 `PESSIMISTIC_WRITE` 锁；订单模块必须新增此契约 |
| `OrderEntity.markShipped(PAID→SHIPPED, Instant)` | 首个 Shipment 真正进入 IN_TRANSIT 时调用；创建运单/面单不调用 |
| `OrderEntity.markDelivered(SHIPPED→DELIVERED, Instant)` | 行项覆盖完整且全部有效运单签收时由 `reconcileOrderDelivery` 调用 |
| `OrderItemRepository` | 批量按 orderId 查询行项，并参与覆盖汇总；不得逐行 N+1 查询 |
| `OrderEntity.shippingAddress`(@Embedded 快照) | `Shipment.shippingAddress` 深拷贝自它，二次冻结 |
| 通用 `domain_outbox` | 新增 `aggregateType`，支持 ORDER/SHIPMENT；物流事件的 aggregateId 使用 Long 实体 id |
| outbox relay + Stream | 复用投递基础设施，但增加物流事件 handler；外部调用成功后才 ACK，失败保留重试 |
| `GlobalExceptionHandler` | 约束名明确的业务冲突 → 409；锁/连接等瞬态异常 → 503 |
| `OrderLineRequest` 整行 quantity 约定 | `ShipmentItemRequest.quantity` 必须等于 `OrderItem.quantity`（本期不拆行项） |

> **订单状态枚举不变，但基础契约需要扩展**：`OrderStatus` 仍使用 `PAID → SHIPPED → DELIVERED`；carrier/trackingNo 只落 Shipment。订单模块必须补订单行锁、通用 outbox 和 `Instant` 时间契约。当前仓库尚无订单实现，因此这些是物流上线前置项，不得在代码清单中写成“既有可直接复用”。

---

## 八、待新增 / 修改文件清单

**新增**：

- `entity/jdbc/Shipment.kt`、`ShipmentItem.kt`、`ShipmentTrack.kt`
- `entity/jdbc/ShipmentStatus.kt`、`AllocationStatus.kt`、`CarrierCode.kt`、`NormalizedTrackingStatus.kt`、`TrackSource.kt`
- `entity/jdbc/LogisticsIdempotency.kt`
- `repository/ShipmentRepository.kt`、`ShipmentItemRepository.kt`、`ShipmentTrackRepository.kt`、`LogisticsIdempotencyRepository.kt`
- `repository/FulfillmentQueryRepository.kt`、`ShipmentQueryRepository.kt`（复杂覆盖汇总和响应投影）
- `service/ShipmentService.kt`（接口）、`service/impl/ShipmentServiceImpl.kt`（`@Service @Transactional(readOnly=true)` 类级 + 写方法 `@Transactional`）
- `service/impl/ShipmentTrackingScheduler.kt`、`OrderDeliveryReconciliationScheduler.kt`
- `service/impl/ShipmentLabelEventHandler.kt`、`ShipmentCancellationEventHandler.kt`
- `logistics/Carrier.kt`、`CarrierRegistry.kt`、`ManualCarrier.kt`（首期闭环实现）
- `shared/ShipmentNoGenerator.kt`、`LogisticsIdempotencyService.kt`
- `controller/ShipmentController.kt`、`LogisticsWebhookController.kt`、`ShipmentResponses.kt`
- `config/LogisticsProperties.kt`、`LogisticsSchedulingConfig.kt`
- `db/migration/V*_create_logistics.sql`、`V*_generalize_outbox.sql`
- 本文 §6.7 列出的物流异常子类

**修改**：

- `config/SecurityConfig.kt` — 放行 `/api/logistics/webhook/**`；客户 `/api/orders/*/shipments/**` 走 authenticated
- `src/main/resources/application.yaml` — `shopmall.logistics.*` + 承运商密钥占位
- `.env.example` — 承运商 enabled/密钥/轮询配置占位
- `OrderRepository` — 新增订单行锁查询；状态时间参数统一为 `Instant`
- `OutboxEvent` / publisher / relay / consumer — 通用化为 `domain_outbox` + `aggregateType`
- `GlobalExceptionHandler` — 区分业务约束冲突与瞬态数据库异常
- `build.gradle.kts` — 增加 Flyway 和 Testcontainers 依赖

`build.gradle.kts` 至少需要：

```kotlin
implementation("org.flywaydb:flyway-core")
runtimeOnly("org.flywaydb:flyway-database-postgresql")
testImplementation("org.testcontainers:junit-jupiter")
testImplementation("org.testcontainers:postgresql")
```

生产配置必须使用 `spring.jpa.hibernate.ddl-auto=validate`，由 Flyway 创建表、外键、`uk_shipment_item_active` 部分唯一索引和复合单号唯一索引。开发环境也运行同一迁移；H2 仅用于不依赖 PostgreSQL 语义的纯单元测试。

---

## 九、验证方案（端到端）

1. **迁移与启动**：空库运行 Flyway 后启动应用，确认 Hibernate `validate` 通过；再从上一版本 schema 升级，确认数据和约束均正确。测试应用入口必须显式启用 scheduling 配置。
2. **服务单元测试**：使用 mock repository/carrier 验证状态分支、请求哈希、owner/admin 校验、MANUAL/remoteLabel 跨字段校验和异常映射。
3. **Testcontainers-PostgreSQL 集成测试（必加）**：
   - 创建运单只产生 `LABEL_CREATED` 或 `LABEL_PENDING`，订单仍为 PAID；dispatch 才推进 SHIPPED。
   - 只分配并签收部分订单行项，整单必须保持 SHIPPED；补齐剩余行项并全部签收后才 DELIVERED。
   - 两个包裹并发签收，订单行锁保证最终整单 DELIVERED；即使模拟“运单已签收但订单更新失败”，reconciliation scheduler 也能补齐。
   - 取消 MANUAL 运单后 ALLOCATED → RELEASED，可为同一 OrderItem 创建替代运单；远程 CANCEL_PENDING 在确认前不能重新分配。
   - CANCELLED 收到 delivered webhook 时只留审计，不得复活或推进订单。
   - `uk_shipment_item_active`、`uk_shipment_carrier_tracking`、`uk_track_shipment_event` 在真实 PG 下生效，业务约束冲突映射 409。
   - 相同 webhook 事件重复、并发和事务回滚后重投：轨迹最多一行，回滚不会留下 Redis“已处理”标记，重投可成功。
   - 乱序轨迹全部留痕，但 `lastTrackAt/status/location` 不被旧事件覆盖，DELIVERED 状态不回退。
   - `@Modifying(clearAutomatically=true)` 后响应和 outbox payload 从投影重读，返回最新状态和时间。
   - outbox 中 SHIPMENT 事件使用 shipmentId Long；每张运单各发一次 `SHIPMENT_DELIVERED`，订单只发一次 `DELIVERED`。
4. **幂等与并发压测**：100 线程用相同/不同 Idempotency-Key 创建同一行项，断言同 key 返回同结果、不同 key 只有一个有效分配，其余 409；不同订单可并行。
5. **调度测试**：多实例并发领取轮询任务时 `SKIP LOCKED + lease` 不重复领取；实例中断后租约过期可接管；连续失败只退避告警，不永久停追踪。
6. **安全测试**：使用含非 ASCII 字节的原始 body 验证 HMAC；超限 body 返回 413；错误签名返回 401；客户响应不含 labelUrl/raw/createdBy；trackingNo 查询强制 owner/admin。
7. **端到端冒烟**：MANUAL 创建 → dispatch → 客户查看在途 → 管理端 delivered → 客户查看轨迹和整单 DELIVERED。远程 mock carrier 另跑 LABEL_PENDING → LABEL_CREATED → webhook/轮询 → DELIVERED 全链路。

---

## 十、范围边界与后续迭代

> 本期为 MVP，明确以下范围与后续项：

- **行项拆分**：本期 `ShipmentItem.quantity` 必须等于 `OrderItem.quantity`（整行发货），不支持同一行项数量拆到多张运单。后续若需部分数量履约，应把 `uk_shipment_item_active` 改为分配账本 + `allocated_quantity` 原子汇总约束，保证有效分配数量之和不超过订单数量，并相应调整覆盖查询。
- **承运商自动面单**：数据模型和 outbox 契约本期即支持，但首个上线版本可只启用 MANUAL。启用 4PX/YunExpress 前必须完成真实签名、幂等创建/取消、时区解析和沙箱端到端测试。
- **通知**：本期物流状态变更不发通知。后续复用既有 `MailService`，在整单 `SHIPPED`/`DELIVERED` 节点发交易邮件；投递走订单 outbox（新增 `NOTIFY` 事件类型，与订单模块 §十通知迭代一致），保证「状态变更」与「通知待发」原子。
- **物流异常申诉**：包裹破损/丢件/错派本期无流程，靠客服人工。后续加 `LogisticsClaim` 聚合（申诉单号、责任方、赔付金额、退货物流），与订单退款路径联动（复用 `lock:pi:{piId}` 串行 Stripe refund）。
- **多承运商分段**：跨境头程 + 尾程分不同承运商（如 4PX 头程 + 本地 USPS 尾程），本期一个 Shipment 一个承运商。后续支持 `Shipment` 拆 `ShipmentSegment`（每段独立承运商/单号/轨迹），聚合签收以末段为准。
- **退货物流**：本期 Shipment 以 `orderId` 外键归属正向订单，不能把 orderId/orderNo 直接改指 RMA。后续新增 `ReturnShipment`，或通过明确的 ownerType/ownerId 迁移实现多归属，且收货地址改为退货仓库快照。
- **轨迹推送前端实时**：本期客户主动 `GET` 查轨迹。后续接 SSE/WebSocket 在 `ShipmentTrack` 落库时推前端实时更新（投递走 outbox + Stream，复用既有消费者）。
- **GDPR / PII**：Shipment 地址快照与 raw 轨迹可能包含姓名、电话和地址。上线前确定保留周期、诊断权限和脱敏任务；保留单号、状态和时间戳用于对账，但不无限期保留 raw payload。
- **多实例**：本期即使用 PostgreSQL lease + `SKIP LOCKED` 领取轮询任务，不再依赖固定 TTL 的 Redis 全局选主锁。

---

## 附录：与订单设计文档的衔接

1. **单号归属**：carrier/trackingNo 由 Shipment（一对多）承载，`OrderEntity` 不持运单号。
2. **发货触发**：`createShipment` 不再推进 SHIPPED；dispatch 或首条在途轨迹才调用 `markShipped`。
3. **签收触发**：不能用“所有已创建 Shipment 都签收”作为条件，必须同时校验所有 OrderItem 已被有效分配。
4. **并发契约**：订单模块需新增 `lockById/lockByOrderNo`，同订单履约写操作在订单行锁内串行化。
5. **outbox 契约**：`aggregateId: Long` 保留，但增加 `aggregateType`；Shipment 事件传 shipmentId，订单事件传 orderId，禁止传 orderNo 字符串。
6. **时间契约**：物流和订单履约时间统一使用 `Instant` / `TIMESTAMPTZ`。若订单文档仍写 `LocalDateTime`，实施前必须同步修订。
7. **实施状态**：当前源码尚未实现订单/outbox，本附录列出的是需要同时兑现的前置契约，不代表这些类已经存在。
