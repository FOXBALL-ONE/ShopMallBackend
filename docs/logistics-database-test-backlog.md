# 物流模块数据库测试待办

记录日期：2026-07-25

本轮仅完成不依赖真实 PostgreSQL 的编译、单元测试和 H2 测试。以下场景依赖 PostgreSQL 的锁、部分唯一索引、事务隔离或多实例行为，按要求暂缓，不使用 H2 结果替代。

## 待验证项

- [ ] 多实例同时领取到期任务时，`FOR UPDATE SKIP LOCKED` 保证同一运单只被一个实例领取。
- [ ] 工作实例领取任务后崩溃，`poll_lease_until` 到期后其他实例能够接管。
- [ ] 两个包裹并发签收时，订单行锁和聚合逻辑最终只把整单推进一次到 `DELIVERED`。
- [ ] 首条承运商轨迹直接为 `DELIVERED` 时，运单发货、签收和订单聚合状态均能持久化。
- [ ] webhook 轨迹写入与状态推进在同一事务中回滚；同一事件重投后能够成功处理且不产生重复轨迹。
- [ ] 100 线程并发创建同一订单行项分配时，相同幂等键返回同一结果，不同幂等键仅一个有效分配成功。
- [ ] PostgreSQL 唯一索引 `uk_shipment_item_active` 能阻止重复有效分配，并映射为业务冲突。
- [ ] PostgreSQL 唯一索引 `uk_shipment_carrier_tracking` 能阻止承运商追踪号重复绑定，并映射为业务冲突。
- [ ] PostgreSQL 唯一索引 `uk_track_shipment_event` 能阻止同一运单事件重复写入。

## 执行条件

- Docker 或可隔离的 PostgreSQL 测试实例可用。
- Flyway 从空库迁移成功，Hibernate `ddl-auto=validate` 启动通过。
- 测试可并发执行且不会连接开发、预发布或生产数据库。

## 本轮状态

Gradle 全量测试共 63 项：60 项通过，3 项 PostgreSQL/Testcontainers 测试因数据库运行条件不可用而跳过，失败 0 项。
