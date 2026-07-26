# Controller 编写规范与样板

> 本文档基于 `application-api/.../api/controller` 下全部 31 个 Controller 源码归纳。
> 范围：HTTP 入口层（Controller）的编写约定。Service / Aspect / 注解本身的实现不在本文档范围内。

---

## 0. ShopMall 项目落地规则

`ShopMall` 使用 Jackson 和 Spring Security，落地本文规范时按以下规则适配：

1. 鉴权用户从 `@AuthenticationPrincipal` 取得，不额外接收 `user_id`。
2. HTTP 输入必须展开为显式的 `@PathVariable`、`@RequestHeader`、`@RequestParam` 或 `@RequestPart`；参数名使用 snake_case。禁止新增 `XxxRequest`、批量请求包装类以及直接接收 JPA 实体的 `@RequestBody`。
3. 每个接口在方法体内声明自己的 `data class Response` 和所需的 `XxxData`，并在 Controller 内显式映射。即使字段重复，也不创建外置 `XxxResponse`、`toResponse()` 或 Controller 私有响应转换函数。
4. JSON 字段重命名使用 `@param:JsonProperty("snake_case")`。Controller 返回类型仍统一为 `ResponseEntity<shared.Response>`，所有响应由注入的 `ResponseBuilder` 生成。
5. Service 接口不得 import `controller` 包，不得接收 HTTP 请求 DTO，不得返回名称或职责属于 HTTP 的 `XxxResponse`。复杂查询可返回由实体和值对象组成的领域视图，如 `OrderView`、`ShipmentDetails`。
6. ServiceImpl 不负责组装 HTTP 响应，不保留 `adminResponse()`、`customerResponse()`、`toResponse()` 等转换辅助方法。需要的数据在各返回路径直接构建领域视图，HTTP 字段选择只在 Controller 完成。
7. 列表响应统一为 `{ list: [...] }`；分页列表为 `{ list: [...], pagination: ... }`。不得直接把实体、`Page` 或裸集合交给 `builder.data()`。
8. 每个 Controller 必须有 `@folder`，每个接口必须有 `@api`，并为所有路径、查询、Header 和 multipart 参数补充 `@param`。

`ShipmentController`、`ShipmentService` 与 `ShipmentServiceImpl` 是本项目的基准实现。

---

## 1. 总览

- **技术栈**：Kotlin + Spring Boot（`@RestController`），FastJSON2 序列化，AOP 切面做鉴权 / 限流 / 分页校验。
- **职责**：Controller **只做**「参数校验 → 调 Service → 组装响应数据类 → 交 `ResponseBuilder` 输出」。业务逻辑写在 Service；Controller 内允许出现轻量的映射 / 拼装逻辑，但不应有跨多个 Service 的事务编排。
- **统一返回类型**：所有接口返回 `ResponseEntity<Response>`，其中 `Response` 是 `shared` 模块的 `data class Response(status, message, data)`。**禁止**直接返回裸对象或 `String`。

---

## 2. 包与目录结构

按业务域分子包，文件名 `XxxController.kt`，每个文件一个 `class`：

```
controller/
├── AppController.kt              # 顶层放跨域 / 杂项接口
├── TunnelController.kt
├── auth/
│   ├── AuthController.kt
│   ├── oauth/                    # 用斜杠表达层级
│   └── thirdparty/
├── domain/{,icp/}
├── frp/{client/,server/}         # 区分调用方：frps 还是 frpc
├── game/
├── node/
├── site/
└── user/{,thirdparty/}
```

每个 Controller 类的 KDoc **必须**带 `@folder <中文分类>` 注解，用于文档生成，路径用 `/` 表达层级：

```kotlin
/**
 * @folder 鉴权/OAuth
 */
@RestController
class OAuthController(...)
```

---

## 3. 类级样板

```kotlin
package cn.locyan.locyanfrp.api.controller.<domain>

import ...   // 只导入用到的；可用 .* 通配（如 web.bind.annotation.*）

/**
 * @folder <分类>
 */
@RestController
class XxxController(
    private val appCfg: AppConfig,          // 配置统一通过 AppConfig 注入
    private val xxxService: XxxService,     // 业务 Service
    private val builder: ResponseBuilder    // 响应构建器，几乎所有 Controller 都要
) {
    // ...
}
```

要点：

1. **构造器注入**：用主构造的 `private val` 参数，**不写 `@Autowired`**。
   - 例外：`AuthController` 里对 `V2UserClient`（Feign）使用 `@Autowired private lateinit var`，因为它需要按字段注入；这是历史遗留，新代码避免。
2. `ResponseBuilder` 必须作为构造参数注入（`@Component`），**不要** `new ResponseBuilder()`。
   - 反例：`DomainIcpController` 写成 `private val builder: ResponseBuilder = ResponseBuilder()`，与全局约定不一致，不要照抄。
3. 配置不直接 `@Value`，统一从 `AppConfig`（`@ConfigurationProperties`）取（如 `appCfg.regex.tunnel.name`、`appCfg.alipay.url.gateway`）。

---

## 4. 注解体系（核心）

鉴权 / 限流 / 校验全部通过自定义注解 + AOP 实现，注解是 Java 文件（`api/annotation/*.java`），`@Target(METHOD)`。

| 注解 | 作用 | 切面读取的输入 |
|------|------|----------------|
| `@Authentication` | 普通用户登录态校验 | 请求参数 `user_id`（Long）+ Header `Authorization: Bearer <token>` + `User-Agent` |
| `@OAuth("Node.Read")` | OAuth 权限节点校验（token 以 `OA_` 开头） | 同上；`value` 是权限节点字符串 |
| `@NodeAuthentication` | frps / frpc 节点调用鉴权 | 参数 `node_id` + Header `X-Node-API-Key` |
| `@Captcha` | 人机验证（前置校验图形/行为验证码） | — |
| `@SpeedLimit(limit=N, timeWindow=S, targetParam="x")` | 基于 Redis 的计数限流（滑动窗口） | `targetParam` 指定的请求参数值作为限流维度；不填则按 URI 限流 |
| `@CheckPageable` | 校验分页参数大小（默认参数名 `size`） | `size` 参数 |

### 4.1 鉴权机制要点（务必理解）

- **`@Authentication` 要求客户端在 query 里传 `user_id`**。切面 `AuthenticationAspect` 从 `request.getParameter("user_id")` 取值并校验 token。因此方法签名里**必须有** `@RequestParam("user_id") userId: Long`，否则切面虽能放行，但语义不完整。
- token 前缀决定走哪条鉴权：`WE_` 走普通登录态，`OA_` 走 OAuth（要求方法同时标了 `@OAuth`）。
- 同一方法可同时标 `@OAuth` + `@Authentication`：用 OAuth token 时校验权限节点，用普通 token 时走登录态。
- **`@NodeAuthentication` 不读 `user_id`**，它读 `node_id` + `X-Node-API-Key`，用于 frp 服务端 / 客户端回连。

### 4.2 注解组合顺序

项目内顺序不统一，但**推荐**如下（从外到内：限流 → 人机 → 权限 → 登录态 → 参数校验）：

```kotlin
@SpeedLimit(...)   // 可选
@Captcha           // 可选
@OAuth("X.Read")   // 可选
@Authentication    // 或 @NodeAuthentication
@CheckPageable     // 有分页时
@GetMapping("/...")
```

> 现存代码顺序较乱（有的 `@Authentication` 在最上），新代码按上表写即可，不强求改旧代码。

### 4.3 哪些接口不加 `@Authentication`

明确**不鉴权**的公开接口（如 `register`、`login`、`/auth/webauthn/options`、`/site/notice`、`/software/*`、`OAuthController` 的 token 兑换类接口）。这些接口里若需要当前用户，靠业务参数（如 `frp_token`）自行解析。

---

## 5. 方法签名样板

```kotlin
/**
 * @api <中文接口名>
 * @param <paramKey> <中文说明>
 * ...
 */
@Authentication
@GetMapping("/tunnel")
fun getTunnel(
    @RequestParam("user_id") userId: Long,
    @RequestParam("tunnel_id") tunnelId: Long,
): ResponseEntity<Response> {
    // ...
}
```

要点：

1. **方法 KDoc 必须有 `@api`**（接口名）和对每个路径 / query 参数的 `@param` 说明，用于文档生成。内部辅助参数（如 `userAgent`）可不写 `@param`。
2. 返回类型恒为 `ResponseEntity<Response>`（`shared.Response`）。`HelloController` 那种 `val data` 属性 + `@get:GetMapping` 是特例，新接口用 `fun`。
3. **HTTP 动词语义**（项目约定，非严格 REST）：

   | 动作 | 注解 | 典型场景 |
   |------|------|----------|
   | 查询（单个 / 列表） | `@GetMapping` | `GET /tunnel`、`GET /tunnels` |
   | 创建 | `@PutMapping` | `PUT /tunnel`、`PUT /app` |
   | 全量更新 / 幂等写 | `@PostMapping` | `POST /sign`、`POST /donation`、`POST /auth/login` |
   | 部分更新 | `@PatchMapping` | `PATCH /tunnel` |
   | 删除 / 撤销 | `@DeleteMapping` | `DELETE /tunnel` |

   > 注意：**创建用 PUT、动作用 POST** 是本项目的既有约定（非 REST 教科书语义），沿用即可。
4. **路径**：单数 `/tunnel`、复数 `/tunnels` 表列表；批量操作用 `/tunnel/batch`、`/tunnels/status` 等子路径；子资源用 `/donation/comment`、`/verification/real-person/payment`。

---

## 6. 请求参数约定

- **命名一律 snake_case**：`user_id`、`tunnel_id`、`local_ip`、`remote_port`、`verify_code`、`frp_token`。Kotlin 形参用 camelCase，靠 `@RequestParam("snake_case")` 映射。
- **分页参数固定**：`page`（默认 `1`）+ `size`（默认 `25`），并标 `@CheckPageable`。
- **可选参数**：`required = false`，类型可空（`String?` / `Int?` / `List<Long>?`）。可给默认值：`defaultValue = "-1"`。
- **列表参数**：`List<Long>` / `List<String>`，Spring 自动按逗号拆分（如 `tunnel_ids=1,2,3`）。
- **Header 参数**用 `@RequestHeader`：登录类接口要 `@RequestHeader("User-Agent") userAgent: String`；可选 header 写 `required = false`（见 `UserController.updatePassword`）。
- **枚举参数**直接用枚举类型：`@RequestParam("type") type: Tunnel.Type`，Spring 按枚举名解析；复杂场景传 String 再 `valueOf(...)`（见 `VerificationController` 的 `idType`）。

---

## 7. 响应构建约定（ResponseBuilder）

`ResponseBuilder` 提供 fluent API，**所有响应必须经它产出**：

```kotlin
return builder.ok()
    .data(rs)            // 可选；无数据体时省略
    .build()
```

### 7.1 状态码入口 → 语义映射（重要，按此选）

| 入口 | HTTP | 何时使用 |
|------|------|----------|
| `builder.ok()` | 200 | 成功 |
| `builder.badRequest()` | 400 | 参数缺失 / 格式错 / 业务前置条件不满足（如「已支付」「已读」） |
| `builder.unauthorized()` | 401 | 未登录 / token 无效 / 密码错（部分场景用 `forbidden`，见下） |
| `builder.forbidden()` | 403 | **最常用**：无权限、归属校验失败、业务规则禁止（如「已签到」「邮箱已注册」） |
| `builder.notFound()` | 404 | 资源不存在 |
| `builder.tooManyRequests()` | 429 | 手动触发的限流（一般由 `@SpeedLimit` 切面自动抛） |
| `builder.serviceUnavailable()` | 503 | 依赖不可用（如隧道未在线） |
| `builder.exception()` | 500 | 上游 API 失败、不可恢复异常 |
| `builder.teapot()` | 418 | 占位 / 未实现（见 `MinecraftController.getStatus`） |
| `builder.status(HttpStatus.X)` | 自定义 | 需要非标准状态码时（如 login 用 `HttpStatus.ACCEPTED(202)` 表示「需要 TOTP」） |

### 7.2 链式调用风格

- 链式调用换行缩进 4 空格，`.build()` 单独成行：

  ```kotlin
  return builder.ok()
      .data(rs)
      .build()
  ```

- 无数据体时一行：`return builder.ok().build()`。
- 附带消息：`.message("...")`，消息语言**混用**（见 §11）；无消息时用入口的默认 message。

### 7.3 早返回（early return）

校验失败立即 `return builder.xxx().build()`，**不要**嵌套 if-else。这是全项目最一致的风格：

```kotlin
val tunnel = tunnelService.find(tunnelId) ?: return builder.notFound().build()
if (tunnel.userId != userId) return builder.forbidden().build()
```

---

## 8. 响应数据类约定

### 8.1 局部 `data class Response` 模式（最常见）

**每个接口在方法体内定义自己的 `data class Response(...)`**，这个名字会**遮蔽**（shadow）`shared.Response`，但因为它只作为 `builder.data()` 的载荷（`Any?`），不会被当返回类型用，所以安全：

```kotlin
data class Response(
    val name: String,
    @param:JSONField(name = "create_time")
    val createTime: LocalDateTime,
)
val rs = Response(name = ..., createTime = ...)
return builder.ok().data(rs).build()
```

列表接口的数据项类通常命名 `XxxData`（如 `TunnelData`、`AppData`、`NodeStat`）。

### 8.2 `@JSONField` 序列化命名

- Kotlin data class 属性用 **`@param:JSONField(name = "snake_case")`**（注解打到构造器参数上，FastJSON2 能读到）：

  ```kotlin
  @param:JSONField(name = "user_id")
  val userId: Long,
  ```

- 顶层 / 被 FastJSON2 反射序列化的复杂结构可用 `@field:JSONField(name = ...)`（见 `TunnelController` 的 `TrafficResponse` 系列）。展开嵌套用 `@field:JSONField(unwrapped = true)`。
- **务必用 `com.alibaba.fastjson2.annotation.JSONField`**（v2）。
  - ⚠️ 反例：`DomainController` 与 `FrpServerTunnelController` 误用了 v1 的 `com.alibaba.fastjson.annotation.JSONField`，新代码不要照抄。

### 8.3 DTO 与实体分离

**不要把 JPA / 数据源实体直接塞进响应**（会泄漏字段、产生循环引用）。统一新建 DTO data class 做映射。

- 反例（不要学）：`PrizeController.getPrizes` 直接 `Response(list = prizeService.findAll())` 把 `Prize` 实体丢出去；`SoftwareController` 直接返回 `SoftwareAssets` 实体。
- 正例：`TunnelController` 定义 `TunnelData` + `NodeData` 做映射；`NodeController` 定义 `NodeData` + `AdditionalData`。

> 顶层共享 DTO（被多个方法复用，如 `TunnelData`、`NodeData`、`NotificationData`）放在 class 顶层；只被一个方法用的 DTO 放方法内部。

---

## 9. 分页约定

```kotlin
@CheckPageable
@Authentication
@GetMapping("/tunnels")
fun getTunnels(
    @RequestParam("user_id") userId: Long,
    @RequestParam("page", defaultValue = "1") page: Int,
    @RequestParam("size", defaultValue = "25") pageSize: Int,   // 形参叫 pageSize
): ResponseEntity<Response> {
    val page = PageRequest.of(page - 1, pageSize)              // 0-based，故 -1

    val pagedData = tunnelService.findByUserId(userId, page)
    // ... 映射成 List<TunnelData>

    data class Response(
        val list: List<TunnelData>,
        val pagination: Pagination,           // shared.data.Pagination(count: Int)
    )
    val rs = Response(data, pagination = Pagination(pagedData.totalPages))
    return builder.ok().data(rs).build()
}
```

要点：

- `Pagination` 只有一个字段 `count`，**约定填「总页数」`pagedData.totalPages`**（不是总条数，名字有误导，沿用即可）。
- 响应结构固定为 `{ list: [...], pagination: { count } }`。
- `PageRequest.of(page - 1, pageSize)`：用户传 1-based，Spring Data 要 0-based。
- 即使方法有 `page`/`size` 但**没有** `@CheckPageable`，转换逻辑也一样写（`@CheckPageable` 只做大小上限校验）。

---

## 10. 鉴权与归属校验模式

### 10.1 单资源「取 → 校验归属 → 操作」

```kotlin
val tunnel = tunnelService.find(tunnelId) ?: return builder.notFound().build()
if (tunnel.userId != userId) return builder.forbidden().build()
// ... 操作
```

几乎所有「按 id 操作某资源」的接口都是这个三段式。**归属校验失败一律 `forbidden`，不用 `notFound`**（少数地方用 `notFound` 防枚举，如 `UserController.updateEmail`，可酌情）。

### 10.2 批量操作「先全部校验，再统一执行」

```kotlin
val tunnels = arrayListOf<Tunnel>()
tunnelIds.forEach {
    val tunnel = tunnelService.find(it) ?: return builder.notFound()
        .message("Tunnel $it not found.").build()
    if (tunnel.userId != userId) return builder.forbidden()
        .message("Tunnel $it is not owned by you.").build()
    tunnels.add(tunnel)
}
tunnels.forEach { tunnelService.delete(it) }   // 全部通过后再执行
return builder.ok().build()
```

> 见 `TunnelController.deleteTunnels` / `updateTunnels`、`DomainController.removeDomains`、`DomainIcpController.removeDomainsIcp`。

### 10.3 批量「部分成功」模式

需要返回成功 / 失败清单时，逐项 try 并收集（见 `TunnelController.downTunnel(batch)`）：返回 `{ succeeded: [...], failed: [...] }`。

### 10.4 Service 返回结果枚举 → when 映射状态码

复杂业务让 Service 返回 `enum class XxxResult`，Controller 用 `when` 映射成 HTTP 状态：

```kotlin
when (tunnelService.verifyType(type, node)) {
    TunnelService.VerifyTypeResult.NODE_SETTING_NOT_ALLOWED_UDP ->
        return builder.forbidden().message("UDP is not allowed on this node.").build()
    TunnelService.VerifyTypeResult.SUCCESS -> { /* 继续 */ }
}
```

> 见 `TunnelController.createTunnel`、`DomainVerificationController.doVerification`、`SiteNotificationController.markSystemNotificationRead`。

---

## 11. 命名与文案约定

- **类 / 函数 / 变量**：camelCase；类名 `XxxController`；查询方法 `get*` / `find*`，创建 `create*`，更新 `update*`，删除 `delete*` / `remove*`。
- **常量**：`UPPER_SNAKE_CASE`。
- **包名**：全小写。
- **提示文案语言不统一**：英文与中文混用（`"Tunnel not found."` vs `"您已完成实名认证"`）。
  - 现状：用户直接可见的业务提示偏中文（实名、签到、留言、抽奖），系统 / 技术性提示偏英文。
  - 新代码：**与同文件 / 同业务域已有文案保持一致**；同一接口的几条消息不要中英混搭。
- 局部辅助函数（如 `OAuthController.genToken()`、`TunnelController` 流量接口里的 `parseDuration`）用 `fun` 定义在方法内即可。

---

## 12. 完整样板（拷贝即用）

```kotlin
package cn.locyan.locyanfrp.api.controller.<domain>

import cn.locyan.locyanfrp.api.annotation.Authentication
import cn.locyan.locyanfrp.api.annotation.CheckPageable
import cn.locyan.locyanfrp.api.annotation.OAuth
import cn.locyan.locyanfrp.api.config.AppConfig
import cn.locyan.locyanfrp.service.XxxService
import cn.locyan.locyanfrp.shared.Response
import cn.locyan.locyanfrp.shared.ResponseBuilder
import cn.locyan.locyanfrp.shared.data.Pagination
import com.alibaba.fastjson2.annotation.JSONField
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * @folder <分类>
 */
@RestController
class XxxController(
    private val appCfg: AppConfig,
    private val xxxService: XxxService,
    private val builder: ResponseBuilder
) {
    /**
     * @api 获取 XX 列表
     * @param userId 用户 ID
     * @param page 分页页码
     * @param pageSize 分页每页数量
     */
    @OAuth("Xxx.Read")
    @CheckPageable
    @Authentication
    @GetMapping("/xxxs")
    fun getList(
        @RequestParam("user_id") userId: Long,
        @RequestParam("page", defaultValue = "1") page: Int,
        @RequestParam("size", defaultValue = "25") pageSize: Int,
    ): ResponseEntity<Response> {
        val page = PageRequest.of(page - 1, pageSize)

        data class ItemData(
            val id: Long,
            val name: String,
            @param:JSONField(name = "create_time")
            val createTime: java.time.LocalDateTime,
        )

        val pagedData = xxxService.findAll(userId, page)
        val list = pagedData.map {
            ItemData(id = it.id, name = it.name, createTime = it.createTime)
        }

        data class Response(
            val list: List<ItemData>,
            val pagination: Pagination,
        )

        val rs = Response(list, pagination = Pagination(pagedData.totalPages))
        return builder.ok().data(rs).build()
    }

    /**
     * @api 更新 XX
     * @param userId 用户 ID
     * @param xxxId XX ID
     * @param name 名称
     */
    @OAuth("Xxx.Write.Update")
    @Authentication
    @PatchMapping("/xxx")
    fun update(
        @RequestParam("user_id") userId: Long,
        @RequestParam("xxx_id") xxxId: Long,
        @RequestParam("name", required = false) name: String?,
    ): ResponseEntity<Response> {
        val item = xxxService.find(xxxId) ?: return builder.notFound().build()
        if (item.userId != userId) return builder.forbidden().build()

        name?.let { item.name = it }
        xxxService.update(item)

        return builder.ok().build()
    }
}
```

---
