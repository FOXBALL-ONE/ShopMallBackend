# 物流与订单模块审查：未修复问题清单

> 来源：两轮逐行审查（物流模块 + 订单模块，含对抗式验证）。已修复项见各模块设计文档与本仓提交记录；本清单仅列出**尚未修复**的问题，供后续迭代处理。
>
> 更新日期：2026-07-26

---

## 一、跨模块 / 基础设施类

### 1. outbox `NEEDS_REPLAY` 状态事件永久丢失（原物流缺陷5，跨模块）

**文件**：`src/main/kotlin/top/foxball/shopmall/service/impl/OutboxMessageHandler.kt`（`recordFailure`，约 50 行）、`src/main/kotlin/top/foxball/shopmall/repository/OutboxEventRepository.kt`（`lockRelayBatch`，14-19 行）、`src/main/kotlin/top/foxball/shopmall/service/impl/OutboxRelayScheduler.kt`

**问题**：`OutboxMessageHandler.recordFailure` 在消费重试耗尽时把 outbox 事件置 `NEEDS_REPLAY` 且 `nextAttemptAt=null`。但 `OutboxRelayScheduler.lockRelayBatch` 的查询只扫描 `status='PENDING'` 或 `status='SENT'`，**不扫 `NEEDS_REPLAY`**。结果：承运商临时故障 / PI 创建失败重试耗尽后，该 outbox 事件静默丢失，运单永久卡 LABEL_PENDING、订单 PI_CREATE 永不闭环。

**影响范围**：跨订单与物流两个模块（影响所有 `aggregateType`），非物流独有。

**修复方向**（二选一或组合）：
- 让 `lockRelayBatch` 扫描 `NEEDS_REPLAY` 状态（或新增独立重放调度器扫 `NEEDS_REPLAY`）；
- 或让 `recordFailure` 耗尽时保持 `PENDING` + 延后 `nextAttemptAt` 而非转 `NEEDS_REPLAY`，由 relay 继续 at-least-once 重投（配合告警）。
- 保留 `NEEDS_REPLAY` 作为人工重放入口（`replay-outbox.sh`，见 `order-system-design.md` §5.5），但需补 relay 扫描或明确人工介入流程。

**为何未修**：属订单/outbox 模块而非物流模块本身，影响所有 aggregateType，超出本轮物流修复范围；改动 outbox 状态机需整体评估，避免误伤。

---

## 二、Stripe 相关（本轮按要求跳过）

### 2. `PaymentIntentCoordinator.withLock` 锁失败静默跳过退款，无对账兜底（原订单缺陷D）

**文件**：`src/main/kotlin/top/foxball/shopmall/shared/PaymentIntentCoordinator.kt`（`withLock`，45-58 行）、`src/main/kotlin/top/foxball/shopmall/service/impl/OrderPaymentServiceImpl.kt`（`cancelOrRefund` action 的 catch，96-98 行；`scheduleConflictRefund` catch，157-159 行）

**问题**：`withLock` 用 `setIfAbsent(lock:pi:{piId})`，未取到锁时仅 `log.warn` 后 `return`，跳过 Stripe cancel/refund 操作，无重试、无 outbox 兜底、无告警。锁的串行化本身是设计意图（防双退款，data-integrity 维度验证者判 not-a-bug），但**真实缺陷在于锁持有方 Stripe 调用失败时**（瞬态 API 错误，被 afterCommit 的 `catch(Exception)` 吞掉只记日志），另一路径在该 30s 锁窗口内被静默跳过 → 退款永久丢失，订单已 CANCELLED 但客户钱未退。

**对抗验证分歧**：payment/edge-cases 维度判 medium 真缺陷；data-integrity 维度判 not-a-bug（设计接受的串行）。裁决：真实缺陷但严重度偏低——需「锁持有方 Stripe 失败 + 并发路径在锁窗口内到达」双重失败交叠，触发条件窄。

**修复方向**：
- afterCommit 的 Stripe 退款失败不应仅 `catch + log`，应落入持久化重试队列（outbox 或独立退款对账表），后续调度器重试；
- 或新增「CANCELLED 订单 + Stripe PI 仍 succeeded」的对账调度器，周期扫描补偿退款；
- 与问题1（outbox NEEDS_REPLAY）共用持久化重试基础设施。

**为何未修**：用户要求本轮跳过 Stripe 相关问题。

---

## 三、未来风险（非现行 bug，真实承运商接入后暴露）

### 3. `ShipmentTrackingScheduler.isTrackingNoInvalid` 关键词过宽，可能误判临时故障为不可恢复

**文件**：`src/main/kotlin/top/foxball/shopmall/service/impl/ShipmentTrackingScheduler.kt`（`isTrackingNoInvalid`，72-77 行；`INVALID_TRACKING_NO_KEYWORDS`，79-81 行）

**问题**：判定单号无效的关键词列表含 `"invalid"`，过于宽泛。承运商返回 `"invalid request format"`（请求格式错误，临时故障）也会被判定为不可恢复并 `disable` 永久停止轮询，把临时故障误判为永久失败，运单不再被追踪。

**现状**：当前仓库只有 `ManualCarrier`（不进轮询），`CarrierException` 现有抛出点的 message 都不含这些关键词，**当前不会误触发**。仅在未来接入 4PX / YunExpress / 17Track 真实适配器时暴露。

**修复方向**：收紧关键词匹配，改为更精确的正则，如 `"tracking.*not found"` / `"invalid tracking"` / `"unknown tracking number"`，而非裸 `"invalid"`。

**为何未修**：非现行 bug（无真实承运商适配器），属代码质量/未来风险，不阻塞本轮。

---

## 四、已修复项摘要（备查，非待办）

> 以下已在本次两轮审查中修复并通过编译 + 全量测试，仅作记录。

**物流模块（10 项，9 项已修，1 项即问题1跨模块未修）**：
- 缺陷1 MANUAL 签收拒已 DELIVERED 重入 ✅
- 缺陷2 并发相同 Idempotency-Key 返回原结果（约束冲突重读）✅
- 缺陷3 remoteLabel 运单 LABEL_PENDING 竞态（根因修复：禁预填 trackingNo + 防御兜底）✅
- 缺陷4 CANCEL_PENDING 回填后补发取消事件 ✅
- 缺陷6 `trackMaxConsecutiveFailures` 死配置告警 ✅
- 缺陷7 单号无效 disable 停止轮询（即问题3 的关键词过宽风险）✅（功能已修，关键词待收紧）
- 缺陷8 签收补偿调度游标分页 → 后改为有界批次（见问题4）✅
- 缺陷9 MANUAL 签收 occurredAt 下界 ✅
- 缺陷10 幂等 hash items 顺序敏感 ✅

**订单模块（3 项非 Stripe 已修，Stripe 即问题2 未修）**：
- 缺陷A 幂等回写失败 → DB 幂等表兜底（`order_idempotency` 表 + `replayOrderNo`/`recordOrderNo` + GlobalExceptionHandler 翻译 uk_order_idempotency → 409）✅
- 缺陷B 无界 while 饿死单线程调度器 → 有界批次 50 ✅
- 缺陷C 超时调度器只扫首页 100 条 → 有界批次 50 ✅

### 4. 缺陷8/缺陷B 的修复演化说明（已知权衡）

`OrderDeliveryReconciliationScheduler.reconcile` 经历两次修复：第一次（物流轮）从首页 `PageRequest.of(0,100)` 改为无界 `while(true)` 游标循环以修复漏扫 → 引入「饿死单线程调度器」回归（缺陷B）→ 第二次（订单轮）改为有界批次 50 + fixedDelay 自然推进。

当前最终形态：每轮 `findIdsByStatusAfter(SHIPPED, 0L, PageRequest.of(0,50))`，不跨轮游标。已 DELIVERED 的订单下一轮不再返回（status 已变），不漏扫；未推进的下一轮仍在前 50 内。`reconcileOrderDelivery` 内部 `markDelivered` 条件 UPDATE 幂等。

**遗留权衡**：若 SHIPPED 订单持续 >50/60s 增长（极端高并发发货），单轮 50 条追赶不及会积压，但靠多轮 fixedDelay 最终清空，不漏扫不损坏数据。`OrderTimeoutScheduler`（缺陷C）同理。如需更高吞吐，可配置 `spring.task.scheduling.pool.size` 提升调度线程池，或为 reconcile/timeout 拆分独立调度器。
