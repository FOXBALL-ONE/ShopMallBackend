# ShopMall

ShopMall 是一个包含客户商城、运营管理后台和 Kotlin/Spring Boot API 的全栈电商项目。客户侧提供商品浏览、购物车、下单与公告阅读；管理侧覆盖商品、订单、用户、公告、工单、物流和运行状态管理。

## 技术栈

- 后端：Kotlin、Spring Boot 4、Spring Security、Spring Data JPA/JDBC、PostgreSQL、Redis、Stripe
- 客户端：Nuxt 4、Vue 3、Nuxt UI、Pinia
- 管理端：Nuxt 4、Vue 3、Naive UI、ECharts
- 构建与测试：Gradle、JUnit 5、Testcontainers、npm

## 项目结构

```text
.
├── src/                         # Kotlin/Spring Boot API 与测试
├── frontend/                    # 客户商城（默认 http://localhost:8088）
├── AdminPanelUI/                # 运营管理后台（Nuxt 默认端口 3000）
├── docs/                        # 业务模块设计文档
├── .env.example                 # 后端本地环境变量模板
└── build.gradle.kts             # 后端构建配置
```

`frontend/` 面向顾客，`AdminPanelUI/` 面向管理员和内部运营人员，两者共享后端 API，但不能互换使用。

## 功能概览

- 商品与分类：统一商品类型、规格属性、图片、标签、评论和搜索浏览。
- 账户与鉴权：注册、登录、邮箱验证、双 Token 刷新机制和会话管理。
- 交易：购物车、库存校验、订单创建、Stripe 支付、支付结果、售后和订单查询。
- 公告：管理员可编辑、发布、定时和审计公告；客户侧提供横幅、自动展示、历史与详情页。
- 客服与物流：工单、附件、物流单、运输状态回调与轮询。
- 运营能力：商品/订单/用户管理、运行状态、日志、全局限流和会话控制。

## 本地运行

### 前置条件

- JDK 25
- PostgreSQL 16+（或兼容版本）
- Redis 7+
- Node.js 22+ 与 npm

### 1. 配置后端

从模板创建本地环境文件并填写 PostgreSQL、Redis 和需要使用的第三方服务配置：

```powershell
Copy-Item .env.example .env
```

至少确认以下本地开发配置正确：

- `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`
- `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`
- `JWT_REFRESH_COOKIE_SECURE=false`，以便浏览器在本地 HTTP 环境回传刷新 Cookie
- 需要演示数据时设置 `PRODUCT_METADATA_INITIALIZATION_ENABLED=true` 和 `MOCK_DATA_ENABLED=true`

完整变量说明见 [.env.example](.env.example)。`.env`、本地文件存储和日志目录已被 Git 忽略，不应提交真实密钥。

启动后端：

```powershell
.\gradlew.bat bootRun
```

后端默认监听 `http://localhost:8080`，客户端 API 前缀为 `http://localhost:8080/api`，管理员 API 前缀为 `http://localhost:8080/admin/api`。

### 2. 启动客户商城

```powershell
Set-Location frontend
npm install
npm run dev
```

客户商城默认地址为 `http://localhost:8088`。使用 `NUXT_PUBLIC_API_BASE` 可覆盖 API 地址。

### 3. 启动管理后台

在另一个终端执行：

```powershell
Set-Location AdminPanelUI
npm install
npm run dev
```

管理后台默认地址为 `http://localhost:3000`。可通过 `NUXT_PUBLIC_API_BASE` 和 `NUXT_PUBLIC_ADMIN_API_BASE` 分别覆盖公共 API 与管理员 API 地址。

## 验证与构建

```powershell
# 后端测试
.\gradlew.bat test

# 客户商城生产构建与类型检查
Set-Location frontend
npm run build
npx nuxt typecheck

# 管理后台生产构建与类型检查
Set-Location ..\AdminPanelUI
npm run build
npm run typecheck
```

应用探活端点：

```powershell
Invoke-WebRequest http://localhost:8080/actuator/health
```

生产部署前请将 `DB_DDL` 调整为 `validate` 或 `none`，关闭模拟数据与开发固定令牌，并为 JWT、文件签名、限流、Stripe、物流回调等配置独立的强随机密钥。

## 设计文档

- [公告模块](docs/announcement-module-design.md)
- [首页商品推荐模块](docs/home-product-recommendation-module-design.md)
- [API 限流](docs/api-rate-limit-design.md)
- [日志系统](docs/logging-system-design.md)
- [订单创建](docs/order-creation-design.md)
- [消息队列解耦](docs/message-queue-decoupling-design.md)
- [请求 ID](docs/request-id-design.md)

## 开发约定

- 后端 API 请求字段采用 `snake_case`；业务时间使用 ISO-8601 `LocalDateTime` 文本。
- 订单与业务金额默认货币为 USD。
- 数据库迁移不在默认开发范围内；不要为常规业务修改新增迁移脚本。
- 提交信息使用 Conventional Commit 前缀，提交主题与说明使用简体中文。
