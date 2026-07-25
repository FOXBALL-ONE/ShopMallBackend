# 文件模块数据库测试待办

记录日期：2026-07-25

本轮完成 service/controller、H2 上下文和单元测试。以下项目依赖真实 PostgreSQL，按要求暂缓：

- [ ] 在已有 `file_metadata` 表的数据库上执行 `V2__extend_file_metadata.sql`，确认 `storage` 列回填为 `local` 且无数据丢失。
- [ ] 从空库执行全部 Flyway 迁移，确认 `file_metadata` 表、唯一约束和复合索引均正确创建。
- [ ] 使用 `EXPLAIN` 验证 owner 分页查询命中 `idx_file_metadata_owner_created`。
- [ ] 验证大数据量下 `owner_id + created_at DESC` 分页顺序稳定且不会全表加载。

执行条件：使用隔离的 PostgreSQL 测试实例，不连接开发、预发布或生产数据库。
