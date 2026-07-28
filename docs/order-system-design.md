# 订单系统设计：高并发 · 无错 · 幂等

> 背景：仿 [cupshe.com](https://www.cupshe.com/) 的跨境泳装/沙滩装独立站。本文档设计一套支持高并发、保证不超卖、不重复下单、状态机闭环的订单系统。
>
> 技术栈：Spring Boot 4.1.0 + Kotlin 2.3.21 + Java 25 + PostgreSQL + Redis（Lettuce）+ Stripe 33.1.1。
> 约束：不引入 Redisson、Kafka；沿用项目 lean 风格与既有模式（`ResponseBuilder` 封装、`@AuthenticationPrincipal userId: Long` 取当前用户、`StringRedisTemplate`、`shared/StripeConfig` 的 `StripeClient` bean）。
>
> **实施状态**：当前仓库尚未落地订单实体、仓库、outbox 和消费者。本文是订单与物流共同依赖的目标契约；实现时必须与 `docs/logistics-system-design.md` 同步，不能把文档中的类当作已经存在。

---

## 一、要解决的核心问题

订单系统上线前，现有代码有两个致命缺口：

1. **库存无并发保护**：`Product.warehouseVolume: Int`（`entity/jdbc/Product.kt:81-84`）是可售库存，但全代码库**没有任何 `@Version` / `@Lock` / `@Modifying`**——库存是裸字段直接赋值，高并发下必然超卖。
2. **无幂等 / 无队列 / 无分布式锁**：重复下单、重复支付回调、状态错乱无从防护。

### 设计目标

- **零超卖**：DB 层原子条件扣减兜底，即便 Redis 层有偏差也不超卖。
- **零重复**：Redis SETNX + Lua 幂等键，覆盖前端防抖与网络重试。
- **状态机闭环**：乐观条件 UPDATE 保证单次推进；**区分「真幂等」与「冲突态」两种返回 0**。
- **最终一致**：DB outbox 表兜底事件投递 + Redis Stream 消费者 + 超时定时器三重保证。
- **支付闭环**：Stripe PaymentIntent + webhook 推进状态；取消订单同步作废 PI。

### 关键决策

| 维度 | 选择 | 理由 |
|---|---|---|
| 防超卖 | 原子条件扣减 SQL | `UPDATE ... WHERE warehouse_volume >= :qty AND status = ACTIVE`，受影响行数=0 即超卖回滚；PostgreSQL 行锁天然串行化，无外部依赖 |
| 异步化 | Redis Stream 轻量队列 + DB outbox 补偿 | 复用既有 Redis，无 Kafka 依赖；outbox 保证 DB 与事件一致 |
| 幂等 | Redis SETNX + Lua | 符合 `LoginToken` 既有 Redis 模式，无需 Redisson |
| 支付 | Stripe PaymentIntent + webhook | `StripeClient` bean 已就绪；用 `constructEvent` 验签 |

---

## 二、整体架构

```
客户端
  │  POST /api/orders  (Header: Idempotency-Key)
  │  Body: { items:[{productId,quantity}], addressId, clientMessage? }
  ▼
OrderController ──► OrderService.placeOrder(userId, request)
  │   ① emailVerified 门控：未验证邮箱 → 403 ForbiddenException
  │   ② Redis SETNX(Lua) 幂等键: order:idem:{userId}:{key} → 命中即回放（含 TOCTOU 处理）
  │   ③ @Transactional(读写) 内：
  │      a. 服务端重算价格：按 productId 查 Product.price → unitPrice/lineTotal/itemsSubtotal
  │         （请求体不得含 unitPrice/lineTotal/totalAmount）
  │      b. 运费/税计算（本期基线：shippingFee=0, taxAmount=0，字段已预留）
  │      c. decrementStock(id, qty) ← @Modifying 原子条件 UPDATE（含 status=ACTIVE）
  │         受影响行数 = 0 → throw InsufficientStockException → 事务回滚
  │      d. 创建 OrderEntity + OrderItems（快照单价 / 收货地址 / 商品快照）
  │      e. outbox 表同事务写入事件（CREATED + PI_CREATE）
  │      f. 在本事务内 set expiresAt = now + timeout
  │   ④ 事务提交后：
  │      - outbox relay 异步 XADD 事件到 Redis Stream（best-effort，失败由 outbox 兜底重试）
  │      - PI 创建由消费者消费 PI_CREATE 事件完成（失败不阻断下单，见 §6.2）
  │   ⑤ 返回 { orderNo, status=PENDING_PAYMENT, clientSecret? }
  ▼
[Outbox Relay] OutboxRelayScheduler（@Scheduled）：扫 outbox 未投递/超 SLA 未确认行 → XADD
[Stream 消费者] OrderEventConsumer：消费事件驱动后续（§5）
[@Scheduled] OrderTimeoutScheduler：扫 expiresAt < now → 取消 + 回补 + 作废 PI（§5.3，需 @EnableScheduling）
[Stripe Webhook] /webhook → 原始字节验签 → 区分幂等/冲突推进状态（§6.3）
```

### 无错保证链

| 风险 | 防护机制 |
|---|---|
| 超卖 | DB 单条原子条件 UPDATE 兜底（`warehouse_volume >= :qty AND status = ACTIVE` 不满足则 0 行 → 回滚） |
| 价格篡改 | 请求体只收 productId+quantity，服务端用 `Product.price` 重算 |
| 重复下单 | Redis 幂等键 SETNX（Lua 原子）+ 订单号回放 |
| 重复回调 | webhook `id` 去重 + 订单状态机幂等 |
| 库存最终一致 | outbox 保证事件不丢 + Stream 消费者 + 超时定时器三重 |
| 并发状态推进 | 乐观条件 UPDATE；返回 0 时区分「已是目标态」与「已进冲突态」 |
| 重复回补库存 | restock 调用一律门控在自己的 `markCancelled` 返回 1 |
| 锁等待超时/死锁 | 瞬态数据库异常 → 503 + Retry-After；已知业务约束冲突单独映射 409 |

---

## 三、数据模型

### 3.1 `OrderEntity` — `entity/jdbc/OrderEntity.kt`

> 类名用 `OrderEntity`，避免与 SQL 保留字 `ORDER` 及 JPA `@OrderBy` 混淆；`@Table(name = "orders")`。
>
> 引用 User 用裸 `customerId: Long`，与 `CustomerReview.customerId` 的去归一化哲学一致，避免级联与跨实体加载。

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long?` | `@Id @GeneratedValue(IDENTITY)` |
| `orderNo` | `String` | 业务单号，`@Column(unique, length=32)`，索引 `idx_orders_no`；生成见 §5.4，含随机段不可枚举 |
| `customerId` | `Long` | `@Min(1)`，索引 `idx_orders_customer_status(customer_id, status)` |
| `status` | `OrderStatus` | `@Enumerated(STRING) length=24`，索引 `idx_orders_status` |
| `itemsSubtotal` | `BigDecimal` | 商品小计 = Σ lineTotal，`@Digits(10,2) precision=12 scale=2` |
| `shippingFee` | `BigDecimal` | 运费，本期基线固定 0，`@Digits(10,2) precision=12 scale=2` |
| `taxAmount` | `BigDecimal` | 税/VAT，本期基线固定 0，`@Digits(10,2) precision=12 scale=2` |
| `discountAmount` | `BigDecimal` | 折扣，本期基线 0，`@Digits(10,2) precision=12 scale=2` |
| `totalAmount` | `BigDecimal` | = itemsSubtotal + shippingFee + taxAmount − discountAmount，`@Digits(10,2) precision=12 scale=2`（金额可审计、可退款） |
| `currency` | `String` | ISO 4217 **大写**（`USD`，DB 存储），调用 Stripe 时转小写，`length=3` |
| `paymentIntentId` | `String?` | Stripe PI ID，`length=64`，唯一索引 `uk_orders_payment_intent` |
| `shippingAddress` | `OrderShippingAddress` | `@Embedded` 收货地址快照，**字段上必须 `@field:Valid`**（否则单值 `@Embedded` 内部校验不级联） |
| `clientMessage` | `String?` | 客户留言，`length=500` |
| `expiresAt` | `Instant?` | 支付截止时间，**在下单事务内写入**，PostgreSQL `TIMESTAMPTZ`；索引 `idx_orders_status_timeout(status, expires_at)` |
| `paidAt / cancelledAt / shippedAt / deliveredAt` | `Instant?` | 状态时间戳；通过带时间戳的条件 UPDATE 一并写入。履约时间与物流模块统一使用 UTC `Instant` |
| `cancelReason` | `String?` | `length=200` |
| `createdAt / updatedAt` | `Instant?` | `@CreationTimestamp` / `@UpdateTimestamp`，数据库使用 `TIMESTAMPTZ` |

### 3.2 `OrderItem` — `entity/jdbc/OrderItem.kt`

- `id: Long?` IDENTITY
- `order: OrderEntity?` — `@ManyToOne(LAZY, optional=false) @JoinColumn(name="order_id", nullable=false, updatable=false)` + `@get:JsonIgnore`
- `productId: Long` — 裸 ID（去归一化，避免加载多态 Product），`@Min(1)`
- `productSnapshot: String` — 下单时的商品名/颜色/尺码等 JSON 快照，`length=2000`，历史可读
- `unitPrice: BigDecimal` — **快照单价，来源为服务端读取的 `Product.price`**（绝不取自请求体）
- `quantity: Int` — `@Min(1)`
- `lineTotal: BigDecimal` — `quantity * unitPrice` 快照，服务端计算
- `@CreationTimestamp createdAt`（无 `updatedAt`，订单项不可变）

### 3.3 `OrderShippingAddress` — `entity/jdbc/OrderShippingAddress.kt`

`@Embeddable`，字段对齐 `User.DeliveryAddressItem`（`User.kt:231-307`）但去除 `id/isDefault/sortOrder`，纯值快照。**校验注解逐字段照搬**：

| Kotlin 属性 | 校验 | DB 列（`@Column`） |
|---|---|---|
| `name`（收件人） | `@field:NotBlank @field:Size(max=100)` | `recipient_name, nullable=false, length=100` |
| `phone`（E.164） | `@field:NotBlank @field:Pattern(regexp="^\\+[1-9]\\d{7,14}$")` | `phone, nullable=false, length=16` |
| `country`（ISO alpha-2） | `@field:NotBlank @field:Pattern(regexp="^[A-Z]{2}$")` | `country_code, nullable=false, length=2` |
| `stateOrProvince?` | `@field:Size(max=100)` | `state_or_province, length=100` |
| `city` | `@field:NotBlank @field:Size(max=100)` | `city, nullable=false, length=100` |
| `district?` | `@field:Size(max=100)` | `district, length=100` |
| `postalCode?` | `@field:Size(max=20)` | `postal_code, length=20` |
| `address1` | `@field:NotBlank @field:Size(max=255)` | `address_line1, nullable=false, length=255` |
| `address2?` | `@field:Size(max=255)` | `address_line2, length=255` |
| `company?` | `@field:Size(max=100)` | `company, length=100` |
| `deliveryInstructions?` | `@field:Size(max=500)` | `delivery_instructions, length=500` |

> **快照语义**：`OrderServiceImpl.placeOrder` 把选中的 `User.DeliveryAddressItem` 字段**深拷贝**进新的 `OrderShippingAddress` 实例并持久化，**创建后永不回读用户的可变地址簿**。

### 3.4 `OrderStatus` 枚举

状态机：

```
PENDING_PAYMENT ──► PAID ──► SHIPPED ──► DELIVERED ──► COMPLETED
      │
      └──► CANCELLED          （超时 / 客户主动取消未支付单；同步作废 PI）

PAID ──► CANCELLED            （退款，仅管理端；同步 Stripe refund + 回补库存 + 冲销销量）
```

多词值用 `PENDING_PAYMENT` 风格（对齐 `ONE_PIECE` 约定）。终态：`COMPLETED` / `CANCELLED`。

### 3.5 `OutboxEvent` — `entity/jdbc/OutboxEvent.kt`（订单/物流通用）

```kotlin
@Entity
@Table(name = "domain_outbox", indexes = [
    Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
])
class OutboxEvent(
    @Id @GeneratedValue(IDENTITY) var id: Long? = null,
    @Column(name="aggregate_type", nullable=false, length=32) var aggregateType: String = "", // ORDER / SHIPMENT
    @Column(name="aggregate_id", nullable=false) var aggregateId: Long = 0,
    @Column(name="event_type", nullable=false, length=64) var eventType: String = "",
    @Column(nullable=false, columnDefinition="text") var payload: String = "",
    @Enumerated(STRING) @Column(nullable=false, length=16) var status: Status = Status.PENDING,
    @Column(nullable=false) var attempts: Int = 0,
    @Column(name="next_attempt_at") var nextAttemptAt: Instant? = null,
    @Column(name="acknowledged_at") var acknowledgedAt: Instant? = null,
    @CreationTimestamp @Column(name="created_at", nullable=false, updatable=false) var createdAt: Instant? = null,
) {
    // PENDING: 待投递（relay 扫此态）
    // SENT: 已 XADD 到 stream，待消费者 ACK 闭环
    // ACKNOWLEDGED: 消费者已处理并回写（终态，可 retention 清理）
    // NEEDS_REPLAY: 消费失败达上限进死信，待人工/脚本重放
    // DEAD: relay 投递失败达上限（XADD 本身失败）
    enum class Status { PENDING, SENT, ACKNOWLEDGED, NEEDS_REPLAY, DEAD }
}
```

> outbox 是订单和物流共享的基础设施。publisher 签名固定为 `publishInTx(aggregateType, aggregateId: Long, eventType, payload)`；订单事件传 orderId，物流事件传 shipmentId，禁止把 orderNo 字符串塞进 aggregateId。表结构和部分唯一索引均由 Flyway 管理，生产使用 `ddl-auto=validate`。

---

## 四、并发控制实现细节

### 4.1 防超卖：原子条件扣减（核心）

`repository/ProductRepository.kt` 新增（本库首次引入 `@Modifying` / `@Query`）：

```kotlin
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
    UPDATE Product p
    SET p.warehouseVolume = p.warehouseVolume - :qty
    WHERE p.id = :id
      AND p.warehouseVolume >= :qty
      AND p.status = :active
""")
fun decrementStock(
    @Param("id") id: Long,
    @Param("qty") qty: Int,
    @Param("active") active: Product.Status = Product.Status.ACTIVE,
): Int

@Modifying
@Query("""
    UPDATE Product p
    SET p.warehouseVolume = p.warehouseVolume + :qty
    WHERE p.id = :id
""")
fun restock(@Param("id") id: Long, @Param("qty") qty: Int): Int

@Modifying
@Query("""
    UPDATE Product p SET p.salesVolume = p.salesVolume + :qty
    WHERE p.id = :id
""")
fun incrementSales(@Param("id") id: Long, @Param("qty") qty: Int): Int

/** 退款 PAID→CANCELLED 时调用，幂等由 markCancelled 返回 1 保证。 */
@Modifying
@Query("""
    UPDATE Product p SET p.salesVolume = p.salesVolume - :qty
    WHERE p.id = :id AND p.salesVolume >= :qty
""")
fun decrementSales(@Param("id") id: Long, @Param("qty") qty: Int): Int
```

设计要点：

- **`decrementStock` 只扣 `warehouseVolume`，不动 `salesVolume`**。库存预占与销量是两个语义：预占在扣库存时，销量在 PAID 后（`incrementSales` 的唯一调用点是 webhook `markPaid` 返回 1，§6.3）。`restock` 只回补 `warehouseVolume`，退款路径另调 `decrementSales` 冲销销量。
- **`restock` 不带 `status` 条件是有意的**：回补只在取消/退款路径触发，此时商品可能已下架/软删（`status=INACTIVE`/`DELETED`）。对 DELETED 商品回补 `warehouseVolume` 是无害 no-op——该商品永不再售，库存值无意义；若加 `status` 条件反而会漏回补（取消一个后被软删的商品回补丢失）。回补的正确性靠 `markCancelled` 返回 1 门控，不靠商品状态。
- **WHERE 含 `status = ACTIVE`**：下架/软删商品不可下单扣库存。`Product.Status` 是嵌套枚举（`Product.kt:206`，JVM 内部名 `Product$Status`），**用命名参数 `:active` 绑定而非 JPQL 字面量**——参数绑定让 Hibernate 自动处理 `@Enumerated(STRING)` 序列化，避免 JPQL 字面量嵌入枚举的跨版本行为不确定；全库零 JPQL 字面量先例，参数绑定与 Spring Data JPA 惯用法 + 现有 `Product.Status` 类型引用风格一致。
- 受影响行数 `0` ⇒ 库存不足或商品非 ACTIVE ⇒ 抛 `InsufficientStockException`（库存不足）或 `ProductNotFoundException`（下架）⇒ 事务回滚（订单与订单项一并回滚，库存不动）。
- PostgreSQL 对该 UPDATE 自动加行锁，**天然串行化同一 SKU 的并发扣减**，无需悲观 `SELECT FOR UPDATE`。
- `OrderServiceImpl.placeOrder` 内（`@Transactional` 读写）按下单行项逐条调用 `decrementStock`；任一返回 0 立即抛异常回滚整个事务。多行项时按 `productId` 升序扣减以降低死锁概率。**同样的升序约定适用于所有 restock/decrementSales 调用点**（取消消费、超时、客户取消/退款）。
- **`clearAutomatically` 与 persist 序列**：下单事务典型顺序是「读 Product 取 price/snapshot → persist(OrderEntity) → persist(OrderItem) → decrementStock」。PostgreSQL IDENTITY 策略下 `persist()` 立即 INSERT 取主键，故 persist 后行已落库；`decrementStock` 的 `clearAutomatically=true` 清空 L1 只是丢弃托管快照，**不影响已落库的行**。但清空后实体变 detach，**同事务内不得再改字段并期望脏检查 flush**——因此 `decrementStock` 必须放在「所有 OrderEntity/OrderItem 字段已设好并 persist 之后、事务返回前」的最后一步，扣减后不再操作实体。`price`/`productSnapshot` 等读取必须在 `decrementStock` 之前完成。`restock`/`incrementSales`/`decrementSales` 同理放在各自事务末尾。

### 4.2 幂等：Redis SETNX + Lua

`shared/OrderIdempotencyService.kt`（`@Component`，注入 `StringRedisTemplate`）：

- key 前缀 `order:idem:{customerId}:{clientKey}`（`clientKey` 取自 `Idempotency-Key` 请求头，未提供时回退为请求体哈希）。
- Lua 脚本语义：
  - `SETNX key "PENDING" EX 600` → 返回 1 = 首次（占位，继续下单）；返回 0 = 已存在。
  - 命中已存在时读 value：`PENDING` = 上一笔仍在进行（见 TOCTOU 处理）；`{orderNo}` = 已完成（回放返回该订单）。
- 下单成功后 `SET key {orderNo}` 记录结果；失败（系统错误可重试）则 `DEL key` 放行重试；失败（业务不可重试如库存不足）则 `SET key {orderNo}:REJECTED:{msg}`，后续相同 key 直接返回该拒绝结果（避免业务错误被 DEL 放行后重复扣减探测）。

**TOCTOU 窗口处理**：在「SETNX 占位成功 → 事务未提交」窗口内，第二个相同 key 请求命中占位但查不到订单。处理方式：
- 占位 value 为 `PENDING`，命中 `PENDING` 的第二请求**返回 409/425「上一次请求处理中，请勿重复提交」**（带 `Retry-After`），而非回放查询。前端收到后等待或轮询订单状态。
- TTL 覆盖占位窗口（600s），占位超时自动释放；若下单进程崩溃遗留 `PENDING`，超时后可被新请求覆盖。

### 4.3 状态机推进：乐观条件 UPDATE（区分幂等与冲突）

`repository/OrderRepository.kt`：

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM OrderEntity o WHERE o.id = :id")
fun lockById(@Param("id") id: Long): OrderEntity?

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM OrderEntity o WHERE o.orderNo = :orderNo")
fun lockByOrderNo(@Param("orderNo") orderNo: String): OrderEntity?

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE OrderEntity o SET o.status = :new WHERE o.id = :id AND o.status = :old")
fun transitionStatus(@Param("id") id: Long, @Param("old") old: OrderStatus, @Param("new") newStatus: OrderStatus): Int

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE OrderEntity o SET o.status = :new, o.paidAt = :ts WHERE o.id = :id AND o.status = :old")
fun markPaid(@Param("id") id: Long, @Param("old") old: OrderStatus, @Param("new") newStatus: OrderStatus, @Param("ts") ts: Instant): Int

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE OrderEntity o SET o.status = :new, o.cancelledAt = :ts, o.cancelReason = :reason WHERE o.id = :id AND o.status = :old")
fun markCancelled(@Param("id") id: Long, @Param("old") old: OrderStatus, @Param("new") newStatus: OrderStatus, @Param("ts") ts: Instant, @Param("reason") reason: String): Int

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE OrderEntity o SET o.status = :new, o.shippedAt = :ts WHERE o.id = :id AND o.status = :old")
fun markShipped(@Param("id") id: Long, @Param("old") old: OrderStatus, @Param("new") newStatus: OrderStatus, @Param("ts") ts: Instant): Int

@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("UPDATE OrderEntity o SET o.status = :new, o.deliveredAt = :ts WHERE o.id = :id AND o.status = :old")
fun markDelivered(@Param("id") id: Long, @Param("old") old: OrderStatus, @Param("new") newStatus: OrderStatus, @Param("ts") ts: Instant): Int

/** 冲突态判定「查当前状态」必须用此 fresh 查询绕开 L1，不得读已 load 的 OrderEntity.status 字段。 */
@Query("SELECT o.status FROM OrderEntity o WHERE o.id = :id")
fun findStatusById(@Param("id") id: Long): OrderStatus?
```

> `lockById/lockByOrderNo` 是物流模块的并发边界：创建、发出、取消、签收和订单级聚合都先锁订单，再访问 Shipment。所有履约路径必须保持相同锁顺序，不能先锁 Shipment 再锁 Order。

**返回 0 必须区分两种语义**：

| 场景 | `markPaid(old=PENDING_PAYMENT, new=PAID)` 返回 0 的原因 | 正确处理 |
|---|---|---|
| 真·幂等 | 订单**已是 PAID**（重复 webhook） | 静默忽略，返回成功 |
| 冲突态（退款补偿） | 订单**已是 CANCELLED**（超时定时器先取消，webhook 后到） | **不能静默忽略**：Stripe 已收款但订单已取消 → 触发退款补偿 + 告警 |
| 冲突态（已履约） | 订单**已是 SHIPPED/DELIVERED/COMPLETED**（状态已超越 PAID，PI 不该再 succeeded） | **告警但不退款**：订单已履约/客户已收货，退款会损害商户；记审计告警人工裁定。与 CANCELLED 冲突态区分，**不**走退款补偿 |

> webhook 推进 PAID 失败（返回 0）时，**用 `findStatusById` 重查当前状态**（不得读 L1 实体的 status 字段）：`PAID` → 真幂等忽略；`CANCELLED` → 退款补偿路径（§6.3）；`SHIPPED`/`DELIVERED`/`COMPLETED` → 记审计告警，**不退款**（订单已履约）。同理取消路径返回 0 时若发现已 `PAID`，不应 restock。

---

## 五、异步、对账与超时

### 5.1 事件生产：DB outbox + Stream

`shared/OrderEventPublisher.kt`：
- 通用签名改为 `publishInTx(aggregateType, aggregateId: Long, eventType, payload)`，在当前事务向 `domain_outbox` INSERT `PENDING` 行。订单调用固定传 `aggregateType=ORDER, aggregateId=orderId`；物流传 `SHIPMENT, shipmentId`。
- publisher 先保存 outbox 获得 id，再构造最终 payload 或 envelope，确保消息中携带真实 `outboxId`，不能在主键生成前序列化一个未知 id。
- 事件类型全集：`CREATED` / `PAID` / `CANCELLED` / `SHIPPED` / `DELIVERED` / `TIMEOUT` / `PI_CREATE`。

### 5.2 Stream 消费

`service/impl/OrderEventConsumer.kt`（`@Component`，`@PostConstruct` 起消费循环 + `@PreDestroy` 优雅停止）：

- **消费组初始化**：`@PostConstruct` 先 `XGROUP CREATE order:events order-consumer-group $ MKSTREAM`（忽略 BUSYGROUP 已存在错误）。**初始化顺序约束**：`RedisCleanupConfig` 的 `flushDb` 在 `ApplicationReadyEvent` 执行，而 `@PostConstruct` 在 bean 初始化阶段——两者无严格先后保证。为避免「消费者先建组 → flushDb 清掉 → XREADGROUP 抛 NOGROUP」，消费者首次 `XREADGROUP` 捕获 `NOGROUP` 异常时重建消费组（幂等重试），不依赖启动顺序。
- `XREADGROUP` group `order-consumer-group`，消费者名 `consumer-${random}`，**`block` 设短超时（如 2000ms）而非无限阻塞**，以便 `@PreDestroy` 的 `volatile running=false` 标志能在下个 block 周期被检测到优雅退出。
- 未确认消息：`@Scheduled` 定期 `XAUTOCLAIM`（带 `minIdleTime`，如 60s）把卡住的 pending 消息转给活跃消费者重投。

**消费幂等与 ACK 闭环**：outbox relay 是 at-least-once 投递（relay 崩溃重启会重复 XADD），消费者必须幂等吸收重复事件，且成功后回写 outbox 形成「投递→消费」闭环：

1. 事件 payload 携带 `outboxId`。消费者不能在业务处理前写不可回滚的 Redis “processed” 标记，否则业务事务失败后重投会被吞掉。领域 handler 必须依赖数据库条件 UPDATE、唯一约束和外部 API idempotency key 自身幂等。
2. 处理成功后**回写 outbox**：`UPDATE domain_outbox SET status='ACKNOWLEDGED', acknowledged_at=now WHERE id=:outboxId AND status='SENT'`，随后 XACK。重复消息看到 DB 已 ACK 可直接 XACK。
3. 处理失败：`attempts++`，达上限（如 5）→ `XADD` 到 `order:events:dead` **并 `XACK` 原消息**（避免 PEL 死循环），**同时回写 outbox `status='NEEDS_REPLAY'`** 留 DB 痕迹并独立告警。

**处理逻辑**：
- `CREATED` → **仅当 `expiresAt IS NULL` 时条件补登**（`UPDATE ... WHERE expires_at IS NULL`），避免重复 CREATED 漂移超时窗口。实际上 `expiresAt` 已在下单事务内写，此分支退化为兜底。
- `PAID` → **不调用 `incrementSales`**（PAID 事件是 at-least-once 投递，无条件 `incrementSales` 在 XAUTOCLAIM 重投时会双倍累加）。`salesVolume` 累加的唯一调用点是 webhook `markPaid` 返回 1（§6.3）。消费者收到 PAID 事件仅做 ACK 闭环，不再有任何 Product 写操作。
- `CANCELLED` → `restock` 回补库存，**且 restock 调用必须门控在自己的 `markCancelled` 返回 1**（见下）。退款 PAID→CANCELLED 的销量冲销不在消费者做，在管理端退款端点调 `decrementSales`（§7.1）。
- `PI_CREATE` → 调 Stripe 创建 PaymentIntent 并落库（§6.2）。落库用 `UPDATE ... AND payment_intent_id IS NULL` 乐观条件，吸收重复 PI_CREATE（只一个 PI 落库，孤儿 PI 是 cosmetic）。
- 死信 stream 同样 `MAXLEN ~ 10000`，并提供回放工具（`replay-outbox.sh`，见 §5.5）。

**restock 状态机门控**：取消回补不能按「当前 status == CANCELLED」判定，否则 XPENDING/XCLAIM 重投会重复 restock。消费者/定时器都调用自己的 `markCancelled(PENDING_PAYMENT → CANCELLED)`，仅返回 1 才 restock；返回 0 跳过。数据库条件 UPDATE 是权威幂等点，不能依赖预先写入的 Redis processed key。

### 5.3 超时取消

`service/impl/OrderTimeoutScheduler.kt`（`@Component`，`@Scheduled(fixedDelay = 60_000)`）：

- 扫描 `status = PENDING_PAYMENT AND expiresAt < now` 的单（`expiresAt` 在下单事务内已写，不依赖事件登记）。
- `markCancelled(PENDING_PAYMENT → CANCELLED)`；**仅当返回 1**（我抢到推进权）才执行后续：
  1. `restock` 回补库存（按行项）。
  2. 若 `paymentIntentId != null`：**cancel/refund 串行化**——先取 Redis 锁 `lock:pi:{piId} NX EX 30` 串行化同一 PI 的 cancel/refund（webhook 退款补偿也会争这把锁）；`stripeClient.paymentIntents().retrieve(piId)` 判 PI 状态：若已 `succeeded`（客户在取消窗口完成支付）则**走 refund**，而非 cancel（succeeded 的 PI 调 cancel 会报 API 错误被吞）；否则调 `cancel`。Stripe 异常捕获记审计，不阻断本地取消（本地状态已正确 CANCELLED）。
     - cancel：`paymentIntents().cancel(piId, PaymentIntentCancelParams.builder().build())`（类名是 `PaymentIntentCancelParams`，无独立 `CancelParams`）。
     - refund：`refunds().create(RefundCreateParams.builder().setPaymentIntent(piId).build(), RequestOptions.builder().setIdempotencyKey("$orderNo:timeout-refund").build())`（idempotencyKey 走 `RequestOptions`，不在 `RefundCreateParams.Builder` 上）。
  3. outbox 写 `CANCELLED` 事件。
- **多实例限流**：`@Scheduled` 本地定时器多实例都会扫，靠 `markCancelled` 返回 1 兜底不重复推进，但会产生无效 UPDATE。建议用 Redis `SET lock:timeout-scan NX EX 55` 选主，持有锁的实例才扫；或接受冗余扫描（单实例阶段可接受）。

> **依赖 `@EnableScheduling`**：Spring Boot 4.1 默认不开启调度。本模块新增 `OrderSchedulingConfig` 显式启用，不依赖应用入口或物流模块的配置。

### 5.4 业务编号与时间

- **订单号**：`yyMMddHHmmss` + Redis `INCR`（key `order:seq:{yyyyMMdd}`，TTL 25h）补零 6 位 + **8 位 CSPRNG 随机段**，总长 ≤ 26（`@Column length=32` 容纳）。随机段使 orderNo **不可枚举**。
- **时间**：订单、支付和物流状态时间统一使用 `Instant` / PostgreSQL `TIMESTAMPTZ`，API 返回带 offset 的 ISO-8601。`expiresAt = clock.instant() + payment-timeout`，不依赖服务器默认时区。

### 5.5 Outbox Relay

`service/impl/OutboxRelayScheduler.kt`（`@Component`，`@Scheduled(fixedDelay = 5_000)`，同样依赖 `@EnableScheduling`）：

- **扫 PENDING 投递**：`SELECT ... FROM domain_outbox WHERE status='PENDING' ... FOR UPDATE SKIP LOCKED`。Stream envelope 必须携带 aggregateType、aggregateId、eventType、outboxId；消费者按 aggregateType/eventType 分发。
- **超 SLA 重扫 SENT**：relay 还扫超 SLA 未 ACKNOWLEDGED 的 SENT 行并重投。重复消息由领域条件 UPDATE、唯一约束和外部 API idempotency key 吸收。
- **NEEDS_REPLAY 与 DEAD 双告警**：`NEEDS_REPLAY`（消费失败达上限）与 `DEAD`（投递失败达上限）是两条独立告警路径，运维分别盯。提供 `replay-outbox.sh`：按 id 重置 `status='PENDING', attempts=0, nextAttemptAt=now`，relay 自动重新投递（消费者幂等键保证不重复执行）。
- **retention 清理**：定时删除 `domain_outbox` 中超过保留期的 ACKNOWLEDGED 行；DEAD/NEEDS_REPLAY 不自动删除。

> relay XADD 成功但置 SENT 的 UPDATE 跨 Redis+PG 非原子，崩溃重启会重复 XADD。因此每个 handler 都必须业务幂等；例如 PI_CREATE 使用 `payment_intent_id IS NULL` 条件更新和 Stripe idempotency key。

---

## 六、支付集成（Stripe PaymentIntent）

### 6.1 StripeClient 与配置

- `shared/StripeConfig` 已暴露 `com.stripe.StripeClient` bean（用 `StripeProperties.secretKey` 构造），直接注入。
- `StripeProperties` 需扩展 `webhookSecret`：

```kotlin
@ConfigurationProperties(prefix = "stripe")
data class StripeProperties(
    val secretKey: String,
    val webhookSecret: String = "",
)
```

### 6.2 PaymentIntent 创建与失败补偿

- 下单事务提交后，relay 消费 `PI_CREATE` outbox 事件调用 `stripeClient.paymentIntents().create(...)`：
  - **金额单位**：Stripe 用最小货币单位（cents）。`amount = totalAmount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()`。
  - **currency**：转小写（`USD` → `usd`）。
  - `metadata`：`mapOf("orderNo" to orderNo)`，webhook 回调时据 `paymentIntentId` 反查订单。
- 落库 `paymentIntentId` 用**独立乐观 UPDATE**：`UPDATE orders SET payment_intent_id = :pi WHERE id = :id AND payment_intent_id IS NULL AND status = PENDING_PAYMENT`，防覆盖。
- **失败补偿**：PI 创建走 outbox 事件异步重试（relay 指数退避）；下单响应立即返回 `orderNo` + `status=PENDING_PAYMENT` + `clientSecret?`（可能为空）；新增 `GET /api/orders/{orderNo}/payment` 端点让前端按需轮询获取 `clientSecret`（覆盖 PI 创建滞后/重试场景）。

### 6.3 Webhook 验签与状态推进

`controller/StripeWebhookController.kt` `POST /webhook`（`permitAll`）。

**原始字节验签**：Stripe 签名是 HMAC-SHA256 over **原始请求体字节**。`@RequestBody` 会消费 InputStream 且按 charset 解码，含非 ASCII 字节（跨境姓名/地址常见）时重编码后 HMAC 恒失败。必须用 `HttpServletRequest`：

```kotlin
@PostMapping("/webhook")
fun webhook(req: HttpServletRequest): ResponseEntity<ApiResponse> {
    val payload = req.inputStream.readBytes().toString(StandardCharsets.UTF_8)  // 原始字节
    val sigHeader = req.getHeader("Stripe-Signature")
    val event = try {
        stripeClient.constructEvent(payload, sigHeader, stripeProperties.webhookSecret)  // v1 Event API
    } catch (e: SignatureVerificationException) {
        return builder.badRequest().message("invalid signature").build()
    }
    // 幂等：webhook event id 去重
    if (!idempotencyService.tryAcquireWebhook(event.id)) return builder.ok().build()
    return orderService.handleWebhookEvent(event)
}
```

> **API 说明**（反编译 `stripe-java-33.1.1.jar` 核实）：`StripeClient` 提供 `constructEvent(payload, sigHeader, secret)` 返回 v1 `com.stripe.model.Event`，从 `event.dataObjectDeserializer.deserializeObject()` 取 `PaymentIntent`。`StripeClient.webhooks()` 在 33.1.1 里**不存在**（只有 `webhookEndpoints()` 管 endpoint 配置）。

**事件处理**（事务边界：`handleWebhookEvent` 的 succeeded 分支必须在**单个 `@Transactional` 方法**内完成 `markPaid` + 按行项 `incrementSales` + 写 `PAID` outbox 行。三者同事务原子：若 `markPaid` 返回 1 后、`incrementSales` 前崩溃 → 整个事务回滚（状态回 PENDING_PAYMENT），下次 webhook 重投（Stripe 自动重试）重新 `markPaid` 返回 1 再 `incrementSales`。outbox 的 `PAID` 行也在该事务内，消费者仅做 ACK 闭环——这样 `salesVolume` 累加既受 `markPaid` 返回值门控（幂等），又受事务原子性保护（不丢），还受 outbox 兜底。webhook 控制器本身不带 `@Transactional`，事务由 service 层包）：
- `payment_intent.succeeded` → 调用 `markPaid(PENDING_PAYMENT → PAID, paidAt)`：
  - **返回 1**（我推进成功）→ **在同一事务内按行项调 `incrementSales(id, qty)`**（销量累加的唯一调用点，天然受 `markPaid` 返回 1 门控，PAID 事件重投不会双计，因为重投时 `markPaid` 返回 0）。事务内 outbox 写 `PAID` 事件（消费者仅做 ACK 闭环，不再碰 `salesVolume`）。
  - **返回 0** → 用 `findStatusById` 重查当前状态（不得读 L1 实体的 status）：
    - 若 `PAID` → 真·幂等，静默忽略。
    - 若 `CANCELLED` → **冲突态**（Stripe 已收款但订单已取消）：**取 Redis 锁 `lock:pi:{piId} NX EX 30`**（与超时取消的 refund 串行），调 `stripeClient.refunds().create(RefundCreateParams.builder().setPaymentIntent(piId).build(), RequestOptions.builder().setIdempotencyKey("$orderNo:conflict-refund").build())`（idempotencyKey 走 RequestOptions，防重复退款），告警。**此分支不调 `incrementSales`**（订单最终 CANCELLED，从未产生有效销量）。
- `payment_intent.payment_failed` → 记录原因，保留 `PENDING_PAYMENT` 等超时或客户重试（同一 PI 可多次 confirm，不创建新 PI；`clientSecret` 是 PI 稳定属性，GET /payment 返回它供前端重试 confirm）。

---

## 七、API 与安全

### 7.1 端点（`controller/OrderController.kt`）

遵循 `DressController` 风格：`@RestController`、类级无 `@RequestMapping`、构造注入 service + `AdminAccessService` + `ResponseBuilder`、方法内 `data class Response(...)` 局部封装、`@AuthenticationPrincipal userId: Long`、`@Valid @RequestBody`、`@field:` 校验。

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| POST | `/api/orders` | authenticated + **emailVerified** | 下单，需 `Idempotency-Key` 头 |
| GET | `/api/orders` | authenticated | 我的订单列表（按 `customerId`，分页） |
| GET | `/api/orders/{orderNo}` | authenticated | 订单详情；**非 owner 统一返回 404**（非 403），关闭存在性预言机 |
| GET | `/api/orders/{orderNo}/payment` | authenticated | 按需获取 `clientSecret`（PI 创建滞后/重试） |
| POST | `/api/orders/{orderNo}/cancel` | authenticated | 客户主动取消未支付单（状态机推进 + 回补 + 作废 PI） |
| POST | `/webhook` | permitAll | Stripe 回调（原始字节验签，§6.3） |
| GET | `/api/admin/orders` | admin | 管理端列表（分页 + 多条件过滤） |
| POST | `/api/admin/orders/{orderNo}/refund` | admin | 退款 PAID → CANCELLED + Stripe refund + 回补库存 + 冲销销量 |

> **退款路径实现**：管理端 refund 端点流程——①`markCancelled(PAID → CANCELLED, cancelledAt, reason)`，**仅返回 1 才继续**（幂等门控）；返回 0 时用 `findStatusById` 重查（不读 L1）：`CANCELLED` → 幂等返回成功（已退款）；`SHIPPED`/`DELIVERED`/`COMPLETED` → 抛 `OrderStatusException`（409，本期已发货不可退款）；②按行项 `productId` 升序 `restock`（回补 `warehouseVolume`）+ `decrementSales`（冲销 `salesVolume`，`WHERE salesVolume >= qty` 防负）；③取 Redis 锁 `lock:pi:{piId} NX EX 30`（与超时取消/webhook 冲突态串行）；④`stripeClient.refunds().create(RefundCreateParams.builder().setPaymentIntent(piId).build(), RequestOptions.builder().setIdempotencyKey("$orderNo:refund").build())`。①②必须在单个 `@Transactional` 内（库存/销量回补与状态变更原子），③④的 Stripe 调用在事务提交后（afterCommit）执行，Stripe 异常记审计不阻断本地 CANCELLED。**注意**：本期退款仅限 `PAID` 态（状态机无 SHIPPED/DELIVERED → CANCELLED 迁移，已发货退款需先走退货/RMA 流程）。冲突态退款子场景（webhook 推进 PAID 但订单已 CANCELLED，salesVolume 从未累加）只需 Stripe refund + `restock`（若超时路径已 restock 则跳过），**不调 `decrementSales`**——与已 PAID 后退款语义区分。
>
> **emailVerified 门控**：`User.kt:152` 明确「未验证前限制下单等敏感操作」。`placeOrder` 先 `userRepository.findById(userId)` 校验 `emailVerified`，否则抛 `ForbiddenException(403)`。username 登录会绕过 `AuthServiceImpl` 的 email-login 验证门，故订单层必须自验。
>
> **履约端点归属**：订单控制器不再提供手工 `ship` / `deliver`。创建运单、dispatch、取消面单和签收均由物流模块 `/api/admin/.../shipments` 端点负责；订单模块只暴露并发安全的 `markShipped` / `markDelivered` 仓库契约。

### 7.2 请求体 schema

`controller/OrderController.kt` 内定义 `PlaceOrderRequest`：

```kotlin
data class PlaceOrderRequest(
    @field:NotNull
    @field:Size(min = 1, max = 10)
    @field:Valid
    val items: List<OrderLineRequest>,

    @field:NotNull
    val addressId: UUID,          // 引用 User.DeliveryAddressItem.id；服务端据此查地址簿并快照

    @field:Size(max = 500)
    val clientMessage: String? = null,
)

data class OrderLineRequest(
    @field:NotNull @field:Min(1) val productId: Long,
    @field:Min(1) @field:Max(99) val quantity: Int,   // 单行上限 99；无跨订单限购（见 §十）
)
```

> **关键约束**：请求体**只接受 `productId` + `quantity`**，**绝不接受** `unitPrice` / `lineTotal` / `totalAmount`。服务端在事务内按 `productId` 查 `Product.price` 重算 `unitPrice`、`lineTotal`、`itemsSubtotal`、`totalAmount`。地址用 `addressId` 引用用户地址簿：**复用现成的 `UserService.getDeliveryAddress(userId, addressId)`**（`UserService.kt:29`，已带 userId ownership scope，返回 `DeliveryAddressItem?`），不存在或非本人地址返回 404，再深拷贝快照（§3.3）。不另造查询。

### 7.3 SecurityConfig 调整 — `config/SecurityConfig.kt`

```kotlin
it.requestMatchers(HttpMethod.POST, "/webhook").permitAll()
```
其余订单端点走默认 `anyRequest().authenticated()`。**CORS `allowedHeaders` 追加 `Idempotency-Key`**（否则浏览器下单被预检拦截）。

### 7.4 配置追加 — `src/main/resources/application.yaml`

`shopmall` 命名空间下新增：

```yaml
shopmall:
  order:
    payment-timeout-minutes: "${ORDER_PAYMENT_TIMEOUT_MINUTES:15}"
    idempotency-ttl-seconds: "${ORDER_IDEMPOTENCY_TTL:600}"
    stream-max-len: "${ORDER_STREAM_MAX_LEN:10000}"
    outbox-max-attempts: "${ORDER_OUTBOX_MAX_ATTEMPTS:5}"
    outbox-ack-sla-seconds: "${ORDER_OUTBOX_ACK_SLA:300}"     # SENT 超 SLA 未 ACK 则 relay 重投
    outbox-retention-days: "${ORDER_OUTBOX_RETENTION_DAYS:7}" # ACKNOWLEDGED 行保留期，过期清理
    max-quantity-per-line: "${ORDER_SINGLE_SKU_LIMIT:99}"     # 单行数量上限（非跨订单限购）
stripe:
  webhook-secret: "${STRIPE_WEBHOOK_SECRET:}"
```

> `redis.clear-on-startup` 生产必须置 `false`：`RedisCleanupConfig` 默认 `true` 会 `flushDb()` 清空**全部** key（含 stream、消费组、幂等键）。outbox 在 DB 不受影响，但 stream/消费组重建依赖消费者 `@PostConstruct` 的 `XGROUP CREATE MKSTREAM`。

### 7.5 异常 — `handler/BusinessException.kt`

新增子类（沿用现有模式）：

| 异常 | HttpStatus | 默认消息 |
|---|---|---|
| `InsufficientStockException` | 409 CONFLICT | 库存不足 |
| `OrderNotFoundException` | 404 NOT_FOUND | 订单不存在 |
| `OrderStatusException` | 409 CONFLICT | 订单状态不允许此操作 |
| `PaymentFailureException` | 400 BAD_REQUEST | 支付失败 |
| `EmailNotVerifiedException` | 403 FORBIDDEN | 邮箱未验证，暂不能下单 |

`GlobalExceptionHandler` 需要区分瞬态数据库故障和业务约束冲突：

```kotlin
@ExceptionHandler(CannotAcquireLockException::class, QueryTimeoutException::class)
fun onTransientDataAccess(ex: DataAccessException): ResponseEntity<Response> =
    builder.serviceUnavailable().retryAfter(1).message("系统繁忙，请稍后重试").build()
```

> 唯一约束、外键和 CHECK 失败不是一概可重试。已知业务约束按 constraint name 翻译为 409；未知完整性错误保持 500 并告警。锁等待、死锁、连接瞬断等才返回 503。

### 7.6 响应模型 — `controller/OrderResponses.kt`

沿用 `ProductResponses.kt` 模式：`data class OrderResponse / OrderItemResponse` + `fun OrderEntity.toResponse()`、`fun OrderItem.toResponse()` 扩展。**`OrderResponse.clientSecret: String?`（可空）**（PI 异步创建，下单响应可能为空，前端收到 null 时按指数退避轮询 `GET /api/orders/{orderNo}/payment`，上限 N 次）。敏感字段裁剪（未支付前不回传 `clientSecret` 之外的密钥；`paymentIntentId` 可回传）。

---

## 八、待新增 / 修改文件清单

**新增**（均在既有包结构内）：

- `entity/jdbc/OrderEntity.kt`、`OrderItem.kt`、`OrderShippingAddress.kt`、`OrderStatus.kt`、通用 `OutboxEvent.kt`
- `repository/OrderRepository.kt`、`OrderItemRepository.kt`、`OutboxEventRepository.kt`
- `service/OrderService.kt`（接口）、`service/impl/OrderServiceImpl.kt`（`@Service @Transactional(readOnly=true)` 类级 + 写方法 `@Transactional`）
- `service/impl/OrderEventConsumer.kt`、`OrderTimeoutScheduler.kt`、`OutboxRelayScheduler.kt`
- `shared/OrderIdempotencyService.kt`、`OrderEventPublisher.kt`、`OrderNoGenerator.kt`
- `controller/OrderController.kt`、`StripeWebhookController.kt`、`OrderResponses.kt`
- `handler/BusinessException.kt` 内追加 5 个订单异常子类
- `entity/redis/OrderEvent.kt`（可选，死信审计）
- `db/migration/V*_create_order_core.sql`、`V*_create_domain_outbox.sql`

**修改**：

- `repository/ProductRepository.kt` — 加 `decrementStock` / `restock` / `incrementSales` / `decrementSales`（`@Modifying @Query`）
- `config/SecurityConfig.kt` — 放行 `/webhook`，CORS 加 `Idempotency-Key`
- `shared/StripeClient.kt`（`StripeConfig` / `StripeProperties`）— **`StripeProperties` 加 `webhookSecret` 字段**
- `src/main/resources/application.yaml` — `shopmall.order.*` + `stripe.webhook-secret`；**`redis.clear-on-startup` 生产置 false**
- `src/main/resources/application-test.yaml` — 测试用短超时
- `OrderSchedulingConfig.kt` — 显式 `@EnableScheduling`，不依赖其他模块
- `handler/GlobalExceptionHandler.kt` — 瞬态数据库异常 → 503，业务约束冲突 → 409
- `build.gradle.kts` — 增加 Flyway、Testcontainers PostgreSQL/JUnit 依赖

**复用既有**（不重写）：

- `shared/ResponseBuilder` / `Response`、`shared/StripeConfig` 的 `StripeClient` bean
- `service/AdminAccessService`、`StringRedisTemplate`、`@AuthenticationPrincipal userId: Long`
- `UserRepository.findWithDeliveryAddressById`（下单时一次性加载用户+地址簿，避免 N+1）
- `UserService.getDeliveryAddress(userId, addressId)`（下单取地址，已带 ownership scope）
- `CustomerReviewController` 的 404 合并模式（订单详情非 owner 也返回 404）

> 无需引入 Redisson、Kafka。订单和物流依赖真实外键、CHECK、部分唯一索引和 `SKIP LOCKED`，必须用 Flyway 管理 schema；生产 `ddl-auto=validate`。当前源码尚无订单实现，因此文件清单是待落地项而非“复用既有”。

---

## 九、验证方案（端到端）

1. **迁移与启动**：空库和升级库分别运行 Flyway，确认 `orders` / `order_items` / `domain_outbox` 建表及 Hibernate `validate` 通过。
2. **单元测试**（H2 + `application-test.yaml`，沿用 `CatalogServiceImplTest` 风格）：
   - 正常下单：库存 N→N−qty，`OrderEntity`/`OrderItem` 落库，`expiresAt` 已写，`salesVolume` **不变**（待 PAID）。
   - 超卖：库存=1、qty=2 → `decrementStock` 返回 0 → `InsufficientStockException`，事务回滚、库存不变、订单不落库。
   - 下架商品：`status=INACTIVE` → `decrementStock` 返回 0 → 404/409。
   - **价格篡改**：请求体带 `unitPrice` 字段 → 校验拒绝或忽略，服务端用 `Product.price` 重算。
   - **emailVerified**：未验证用户下单 → 403。
   - 幂等：相同 `Idempotency-Key` 二次下单 → 返回首次 `orderNo`，不重复扣库存；命中 `PENDING` 占位 → 409 + Retry-After。
   - 状态机：`PENDING_PAYMENT→PAID` 返回 1；**冲突态**（已 CANCELLED 再推进 PAID）返回 0 且 `findStatusById` 重查为 CANCELLED → 触发退款补偿，**且用 L1 实体 status 字段重查会误判**（断言必须用 `findStatusById` fresh 查询，模拟先 load OrderEntity 再并发取消的场景验证 L1 不陈旧）。
   - **已履约冲突态**：订单已 `SHIPPED` 后 webhook `payment_intent.succeeded` 到达 → `markPaid` 返回 0 → `findStatusById` = SHIPPED → 记审计告警，**不触发退款补偿**（与 CANCELLED 冲突态区分）；退款端点对 `SHIPPED`/`DELIVERED` 单 `markCancelled` 返回 0 且 `findStatusById` 重查非 CANCELLED → 抛 `OrderStatusException`（409）。
   - restock 门控：重复 CANCELLED 事件 → 仅首次 `markCancelled` 返回 1 者 restock，`warehouseVolume` 不超过原始。
   - **PAID 重投幂等**：模拟 PAID outbox 事件 XAUTOCLAIM 重投两次 → `salesVolume` 只 +qty 一次（因 `incrementSales` 在 webhook `markPaid` 返回 1 调用，不在消费者；消费者重投仅 ACK 闭环）。
   - **退款回退**：PAID 单（`warehouseVolume` 已扣、`salesVolume` 已加）经管理端 refund → `markCancelled(PAID→CANCELLED)` 返回 1 后 `restock` + `decrementSales`，断言 `warehouseVolume`/`salesVolume` 回到下单前；refund 端点对 `SHIPPED`/`DELIVERED` 单返回 409（本期不支持已发货退款）。
3. **Testcontainers-PostgreSQL 集成测试**（必加）：`@Testcontainers` + 真实 PostgreSQL 容器，针对 `decrementStock`/`restock`/`incrementSales`/`decrementSales` 跨具体子类（`BikiniSuit`、`Dress`）断言：受影响行数、`warehouse_volume`/`sales_volume` 值、**多态 UPDATE 目标**（确认只更新根表 `products`）、JPQL 枚举参数绑定在真 PG 下的行为。H2 MODE=PostgreSQL 无法保证这些语义一致，H2 测试通过≠生产正确。
4. **并发压测**：100 线程同抢库存=10 的 SKU，断言**成功单数 ≤ 10、无负库存、订单数 ≤ 10**。压测必须在真实 PostgreSQL（Testcontainers）上跑。
5. **Stream / 超时 / outbox**：模拟 Redis 不可用、relay 重复 XADD、handler 事务失败后重投和消费者失败达上限。断言不使用处理前 Redis processed 标记，领域条件 UPDATE/唯一约束吸收重复，失败重投不会被吞掉；`domain_outbox` 正确进入 ACKNOWLEDGED/NEEDS_REPLAY。
6. **Stripe**：`./gradlew test` 覆盖 webhook 原始字节验签（含非 ASCII 负载）与状态推进；本地用 Stripe CLI `stripe listen --forward-to localhost:8080/webhook` 转发真实事件；验证金额 cents 转换。
7. **手工冒烟**：带 `Idempotency-Key` 调 `POST /api/orders` → `clientSecret`（或轮询 `/payment`）→ 前端确认支付 → webhook 推进 `PAID` → `GET /api/orders/{orderNo}` 状态正确；超时不付 → 自动取消 + PI 作废 + 库存回补。

---

## 十、范围边界与后续迭代

> 本期为 MVP，明确以下范围与后续项（避免实现者对未覆盖功能产生歧义）：

- **购物车**：本期**无购物车**，前端直选商品调 `POST /api/orders` 提交（`items` 可多行，聚合在请求内）。后续若需持久化购物车，单立 `Cart` 聚合。
- **运费 / 税**：本期基线 `shippingFee=0`、`taxAmount=0`、`discountAmount=0`（字段已预留，§3.1）。`totalAmount = itemsSubtotal`。后续引入运费模板（按国家/重量）与 VAT 时，在 `OrderService` 计算 `shippingFee`/`taxAmount` 后写入，`totalAmount` 公式不变。
- **限购**：本期单行 `quantity` 上限 99（`OrderLineRequest` `@field:Max(99)`，配置名 `max-quantity-per-line`，实为**单行数量上限**，非跨订单限购）。**无单用户/单 SKU 跨订单限购**。秒杀限流为后续项（需分布式限流，可能引入 Redis 计数器）。
- **退款**：`PAID → CANCELLED` 仅管理端，流程见 §7.1 退款实现（`markCancelled` 返回 1 后 `restock` + `decrementSales` + Stripe refund）。`incrementSales` 的唯一调用点是 webhook `markPaid` 返回 1（§6.3），**退款/冲突态都不调 `incrementSales`**。退款实际到账由 Stripe 异步，webhook `charge.refunded` / `charge.refund.failed` 可后续接入对账。**已发货单（SHIPPED/DELIVERED）本期不支持退款**，状态机无对应迁移，需先走退货/RMA 流程（后续迭代）。
- **物流追踪**：物流模块与订单模块同批落地。承运商/运单号只存 `Shipment`（一对多），`OrderEntity` 不持单号。创建面单不推进订单；首个 Shipment dispatch/在途时调用 `markShipped`。整单签收必须同时满足“全部 OrderItem 有有效分配”和“有效 Shipment 全部 DELIVERED”，由 `reconcileOrderDelivery` 调用 `markDelivered`。所有履约写操作先通过 `lockById/lockByOrderNo` 锁订单。
- **通知**：本期订单状态变更**不发通知**。后续复用既有 `MailService`（与邮箱验证码同源 `JavaMailSender`）在 `PAID`/`SHIPPED`/超时取消等节点发交易邮件；投递走 outbox（新增 `NOTIFY` 事件类型）保证「状态变更」与「通知待发」原子，不引入独立消息中间件。跨境短信成本高，暂不纳入。
- **优惠券 / 折扣**：`discountAmount` 字段已预留（§3.1），本期恒 0，`totalAmount = itemsSubtotal`。后续引入优惠券/促销时新增 `Coupon` 聚合（码核销、有效期、适用商品/品类），在 `OrderService` 事务内算出 `discountAmount` 后写入，`totalAmount = itemsSubtotal + shippingFee + taxAmount − discountAmount` 公式不变；核销走原子条件 UPDATE（`WHERE used_count < max_count`）防超发，复用本期防超卖同款模式。
- **多币种**：`currency` 单值（§3.1，ISO 4217 大写存储），本期单一币种（如 `USD`），Stripe 按此币种建 PI（§6.2）。后续多币种结算需按用户区域或请求显式选择币种，cents 转换须按各币种小数位处理（JPY/KRW 等零小数位币种不可 `movePointRight(2)`）；金额列 `precision/scale` 已固定，汇率换算在服务端完成、币种快照随订单落库。
- **售后 / 退货（RMA）**：本期退款仅 `PAID → CANCELLED` 且仅未发货单（§7.1）；`SHIPPED`/`DELIVERED`/`COMPLETED` 不可退款（状态机无迁移）。后续已发货退款需先走退货：新增 `ReturnRequest`/`RMA` 聚合（退货物流、质检、金额分摊），退货入库后 `restock` + `decrementSales` + Stripe refund，复用本期退款端点的底层原子操作与 `lock:pi:{piId}` 串行。
- **评价联动**：`CustomerReview` 已存在且含 `verifiedPurchase` 字段（`entity/jdbc/CustomerReview.kt:77`，「是否由已完成订单确认购买」），但本期**无订单关联**、该位无法置真。后续在订单 `COMPLETED` 后解锁评价，以 `customerId + productId` 对 `OrderItem` 核验购买真实性并置 `verifiedPurchase=true`；复用既有评价服务与审核状态机，不另起聚合。
- **审计日志**：本期管理端 `ship`/`refund` 等操作仅留下订单字段变更与 Stripe 侧记录，无独立操作轨迹。后续引入操作审计表（操作人、时间、字段前后值）覆盖所有管理端状态推进与退款，满足跨境合规可追溯；写入走 outbox 与业务变更原子。
- **风控 / 反欺诈**：本期依赖 Stripe 默认风控（Radar）。后续可在下单/支付节点加规则（用户/设备/IP 频次、地址黑白名单），命中走人工审核或拦截；与 §4.2 幂等键体系协同做单用户/单设备限频，命中记录入审计表。
- **GDPR / PII 留存**：`OrderShippingAddress` 快照含收件人姓名、电话、地址（§3.3）。本期遵循项目 GDPR 约定，不做订单 PII 自动清理。后续需平衡「被遗忘权」与财务/税务法定留存：对到期订单的地址/电话字段做**脱敏**（不可逆截断/哈希）而非物理删除，保留金额/状态/时间戳用于对账与税务核查。
- **schema 迁移**：本期即使用 Flyway；生产 `ddl-auto=validate`。不能把正确性依赖的唯一索引/CHECK 推迟到后续。
- **多实例**：outbox relay、超时扫描和物流轮询使用 PostgreSQL `FOR UPDATE SKIP LOCKED` / lease 分片；不依赖固定 TTL 的 Redis 全局选主锁。
