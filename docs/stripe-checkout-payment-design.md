# Stripe Checkout 托管支付方案

## 1. 目标与边界

本方案使用 Stripe Checkout 的托管支付页完成一次性订单支付：后端创建 Checkout Session，前端将浏览器整页跳转至 Stripe 返回的 `session.url`；客户完成或取消支付后，Stripe 将浏览器跳转回主站指定页面。

支付是否成功只能由 Stripe webhook 确认。主站回跳、查询参数和前端展示均不是支付凭证。

本方案替换当前 `PaymentIntent + clientSecret` 前端确认模式，不接收前端传入的金额、币种、商品价格或回跳 URL。

## 2. 当前代码与目标差异

当前订单链路通过 `PI_CREATE` 外盒事件异步创建 `PaymentIntent`，并在 `GET /api/orders/{orderNo}/payment` 中返回 `clientSecret`。这适合 Payment Element，不适合跳转到 Stripe 托管页。

目标链路使用以下 Stripe 资源：

| 资源 | 用途 | 本地保存方式 |
| --- | --- | --- |
| Checkout Session (`cs_...`) | 托管页 URL、浏览器回跳和 webhook 的关联依据 | 新增 `stripe_checkout_session_id` |
| PaymentIntent (`pi_...`) | 退款、支付冲突补偿和 Stripe 交易状态 | 保留并扩展 `payment_intent_id` |
| Stripe Event (`evt_...`) | webhook 去重 | 复用 `stripe_webhook_events.event_id` |

`PaymentInterface` 的 Stripe 实现应返回 `PaymentClientAction.Redirect(URI(session.url))`。为避免将 Checkout Session ID 塞入 `rawStatus`，建议为 `PaymentTransaction` 新增可空字段 `checkoutReference`，用于保存 `cs_...`；`providerPaymentId` 继续表示可退款的 `pi_...`。

## 3. 总体流程

```text
客户                主站后端                 Stripe                 主站前端
 | POST /checkout      |                        |                       |
 |-------------------->| 创建或读取订单快照      |                       |
 |                     | POST /v1/checkout/...  |                       |
 |                     |----------------------->|                       |
 |                     | <--- Session(id,url) --|                       |
 | <--- checkoutUrl -- |                        |                       |
 | window.location=url |                        |                       |
 |---------------------------------------------------------------> Stripe 托管页
 |                     |                        |                       |
 |                 付款或取消                   |                       |
 |                     | POST /webhook (签名)                     |
 |                     |<-----------------------|                       |
 |                     | 验签、去重、PENDING_PAYMENT -> PAID       |
 |                     |                        |                       |
 |<------------------- Stripe success_url/cancel_url ------------|
 | GET /checkout/status（轮询本地订单）                           |
 |-------------------->|                                        |
 |<--- PENDING/PAID ---|                                        |
```

回跳可能早于 webhook，也可能客户根本不返回主站。因此：

1. 回跳页仅展示“正在确认支付”并查询本地订单状态。
2. webhook 在单个事务中推进订单状态、累计销量并写入 `PAID` 外盒事件。
3. `markPaid(PENDING_PAYMENT -> PAID)` 的条件更新是最终幂等门闩。

## 4. 配置

扩展 `StripeProperties`，所有地址由服务端配置生成，禁止由浏览器提交：

```yaml
stripe:
  secret-key: "${STRIPE_SECRET_KEY}"
  webhook-secret: "${STRIPE_WEBHOOK_SECRET}"
  webhook:
    # Stripe Dashboard 中登记的独立回调地址，路径固定为 /webhook。
    endpoint-url: "${STRIPE_WEBHOOK_URL:https://api.example.com/webhook}"
  checkout:
    storefront-base-url: "${STOREFRONT_BASE_URL:https://www.example.com}"

shopmall:
  order:
    # 本地订单支付窗口；超时后通过补偿任务使 Stripe Checkout Session 失效。
    payment-timeout-minutes: "${ORDER_PAYMENT_TIMEOUT_MINUTES:30}"
```

建议的属性类型：

```kotlin
@ConfigurationProperties(prefix = "stripe")
data class StripeProperties(
    val secretKey: String,
    val webhookSecret: String,
    val checkout: CheckoutProperties,
) {
    data class CheckoutProperties(
        val storefrontBaseUrl: URI,
    )
}
```

`storefrontBaseUrl` 必须使用 HTTPS 的生产主站域名。测试环境可使用 `http://localhost`；不要使用请求头中的 `Host`、`Origin` 或前端传入的地址拼接 `successUrl`，否则会形成开放重定向风险。

## 5. 数据模型与实体调整

### 5.1 开发环境约束

本次 Checkout 改造以开发环境为目标，**不新增或修改 Flyway 迁移文件**。通过 JPA 自动建表/更新使新增的实体字段生效；开始验证前应清理本地开发数据库，避免旧的 `orders` 表缺少新列。

生产环境上线时仍必须补充经评审的显式迁移，示例如下仅用于说明最终目标，**本次不创建该文件**：

```sql
ALTER TABLE orders
    ADD COLUMN stripe_checkout_session_id VARCHAR(255);

ALTER TABLE orders
    ALTER COLUMN payment_intent_id TYPE VARCHAR(255);

CREATE UNIQUE INDEX uk_orders_stripe_checkout_session
    ON orders(stripe_checkout_session_id)
    WHERE stripe_checkout_session_id IS NOT NULL;
```

`OrderEntity` 增加：

```kotlin
@Column(name = "stripe_checkout_session_id", unique = true, length = 255)
var stripeCheckoutSessionId: String? = null

@Column(name = "payment_intent_id", unique = true, length = 255)
var paymentIntentId: String? = null
```

新增仓储方法，确保并发请求只会绑定一次 Session：

```kotlin
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query(
    "update OrderEntity o set o.stripeCheckoutSessionId = :sessionId, " +
        "o.paymentIntentId = :paymentIntentId " +
        "where o.id = :id and o.status = :status and o.stripeCheckoutSessionId is null",
)
fun attachStripeCheckoutSession(
    @Param("id") id: Long,
    @Param("sessionId") sessionId: String,
    @Param("paymentIntentId") paymentIntentId: String,
    @Param("status") status: OrderStatus = OrderStatus.PENDING_PAYMENT,
): Int
```

不要将 `cs_...` 写入名称为 `payment_intent_id` 的字段：`stripeCheckoutSessionId` 保存 `cs_...`，`paymentIntentId` 保存 `pi_...`。Stripe 在 `mode=payment` 下通常会在创建 Session 时返回 PaymentIntent；若创建响应未带 `paymentIntent`，仍应先绑定 Session，随后通过 Session 查询或 webhook 补齐 PaymentIntent，不能因空值把 Session ID 写入错误字段。

### 5.2 支付契约的 Checkout 扩展

当前 `PaymentInterface` 面向通用支付提供商，Checkout 需要在“可退款交易标识”和“用于跳转的会话标识”之间保留明确边界。实现前先扩展契约，避免把 `cs_...` 放入 `rawStatus`：

```kotlin
data class PaymentCreateRequest(
    val merchantPaymentId: String,
    val amount: PaymentAmount,
    val idempotencyKey: String,
    val description: String? = null,
    /** 支付成功后的主站回跳地址。 */
    val returnUrl: URI? = null,
    /** 客户取消或离开支付页后的主站回跳地址。 */
    val cancelUrl: URI? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class PaymentTransaction(
    /** 可查询、取消或退款的提供商侧交易标识；Stripe 对应 pi_...。 */
    val providerPaymentId: String,
    val amount: PaymentAmount,
    val status: PaymentStatus,
    val clientAction: PaymentClientAction = PaymentClientAction.None,
    /** 托管收银台或跳转会话标识；Stripe 对应 cs_...。 */
    val checkoutReference: String? = null,
    val rawStatus: String? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val expiresAt: Instant? = null,
)
```

`StripeService.createPayment` 的返回值必须满足：

| 字段 | Stripe 来源 | 用途 |
| --- | --- | --- |
| `providerPaymentId` | `Session.paymentIntent` | 退款、支付冲突补偿和 PaymentIntent 查询 |
| `checkoutReference` | `Session.id` | 保存到 `OrderEntity.stripeCheckoutSessionId`，用于 webhook 关联 |
| `clientAction` | `Session.url` | `PaymentClientAction.Redirect(URI(url))`，由前端整页跳转 |
| `status` | `Session.status` / `Session.paymentStatus` | 仅供展示；本地订单是否已支付仍由 webhook 决定 |

### 5.3 仓储方法

除 `attachStripeCheckoutSession` 外，新增按 Session ID 查询的只读方法：

```kotlin
fun findByStripeCheckoutSessionId(sessionId: String): OrderEntity?
```

保留 `findByPaymentIntentId`，它继续服务于退款和“本地订单已取消、Stripe 随后支付成功”的冲突补偿。旧的 `attachPaymentIntent` 与 `PI_CREATE` 消费链路在 Checkout 切换完成后删除，不与新绑定方法并存调用。

## 6. 后端接口

### 6.1 创建或取得 Checkout Session

新增受 JWT 保护的接口：

```text
POST /api/orders/{orderNo}/checkout
```

响应示例：

```json
{
  "status": 200,
  "data": {
    "order_no": "ORD202607270001",
    "status": "PENDING_PAYMENT",
    "checkout_url": "https://checkout.stripe.com/c/pay/cs_test_...",
    "expires_at": "2026-07-27T10:30:00Z"
  }
}
```

处理规则：

1. 按 `orderNo + customerId` 查询订单，拒绝访问他人订单。
2. 仅 `PENDING_PAYMENT` 可以创建或返回 Checkout Session；`PAID`、`CANCELLED` 等状态返回 409。
3. 若已绑定的 Session 仍为 `open`，从 Stripe 读取其 URL 后直接返回，禁止重复创建。
4. 使用稳定幂等键 `"${orderNo}:checkout-session"` 调用 Stripe。订单金额、币种和回跳 URL 均来自数据库及服务端配置；一期不显式传递会随重试时间变化的 Stripe `expires_at`，因此同一幂等键始终对应同一请求。
5. Stripe 调用在数据库事务之外执行；创建成功后通过 `attachStripeCheckoutSession` 条件更新绑定本地订单。条件更新失败时重新读取订单：若已有 Session 则返回该 Session，若订单已取消或支付成功则使新建但未绑定的开放 Session 过期并返回冲突。

`POST /api/orders` 不再发布 `PI_CREATE` 外盒事件，也不返回 `client_secret`。订单创建完成后，前端立即调用本接口取得 `checkout_url`。

### 6.1.1 事务边界与并发处理

创建 Session 不能在持有订单数据库锁的事务中调用 Stripe。推荐将 `OrderCheckoutService.openCheckout(customerId, orderNo)` 拆成三个步骤：

1. 第一个短事务：以 `orderNo + customerId` 读取订单，校验 `PENDING_PAYMENT`、未超时、金额和地址快照完整；若已绑定 Session 则返回其 ID。
2. 非事务区：未绑定时调用 `StripeService.createPayment`，幂等键固定为 `"${orderNo}:checkout-session"`。
3. 第二个短事务：执行 `attachStripeCheckoutSession`。若更新成功，返回新 URL；若更新失败，读取胜出的本地 Session 并返回它的 URL。只有订单已不再待支付且新 Session 尚未被绑定时，才调用 `sessions().expire` 回收该孤儿 Session。

同一订单的重复点击或网络重试会命中同一个 Stripe 幂等请求；数据库条件更新则负责吸收多节点并发。不得因为第一次返回超时就使用新的 Stripe 幂等键创建第二个开放 Session。

### 6.2 主站回跳后的状态查询

保留并改造现有接口：

```text
GET /api/orders/{orderNo}/payment
```

建议响应：

```json
{
  "order_no": "ORD202607270001",
  "status": "PENDING_PAYMENT",
  "checkout_session_id": "cs_test_...",
  "expires_at": "2026-07-27T10:30:00Z"
}
```

主站成功页地址固定为：

```text
https://www.example.com/orders/{orderNo}/payment/result?session_id={CHECKOUT_SESSION_ID}
```

其中 `{CHECKOUT_SESSION_ID}` 必须以字面量传给 Stripe；Stripe 完成支付后会替换为实际 Session ID。回跳页先校验登录态，再调用 `GET /api/orders/{orderNo}/payment` 轮询本地状态，最长轮询至订单过期。它不得根据 URL 中的 `session_id` 修改订单状态。

取消页固定为：

```text
https://www.example.com/orders/{orderNo}/payment/cancelled
```

取消回跳表示客户离开或取消了 Stripe 页面，不表示订单已取消。页面应提供“继续支付”或“取消订单”操作；真正取消仍调用现有 `POST /api/orders/{orderNo}/cancel`。

### 6.3 现有响应模型的替换

以下字段只服务于 Payment Element，切换到 Checkout 后必须移除或替换，避免前端继续依赖 `clientSecret`：

| 当前位置 | 当前字段/行为 | Checkout 改造 |
| --- | --- | --- |
| `OrderView` | `clientSecret` | 移除；订单详情只返回本地订单状态和 `paymentIntentId`（如确有运营需要） |
| `OrderPaymentView` | `clientSecret` | 改为 `checkoutUrl: String?` 与 `checkoutSessionId: String?` |
| `OrderController` 多个本地 `Response` | `client_secret` | 移除；仅 `POST /checkout` 返回短期有效的 `checkout_url` |
| `OrderPaymentService` | `createPaymentIntent`、`getClientSecret` | 改为 `openCheckout`、`getCheckoutStatus` 或由专用 `OrderCheckoutService` 承担 |
| `OutboxMessageHandler` | 消费 `PI_CREATE` | 删除该分支，不再在订单创建后异步生成前端凭据 |

不要在普通订单详情接口长期返回 `checkout_url`。该 URL 仅由受保护的 Checkout 创建接口返回；需要恢复支付时由该接口先向 Stripe 检查 Session 是否仍为 `open`，再返回 URL。

## 7. Stripe Checkout Session 创建

一期使用一个“订单总额”行项目，避免把商品、运费、税费和优惠的计算交给浏览器或与 Stripe 的独立价格配置重复计算。若后续要展示逐商品明细，必须仅由 `OrderItem` 快照生成行项目，并确保所有行项目、运费、税费、优惠之和严格等于 `OrderEntity.totalAmount`。

以下示例对应项目当前 `stripe-java:33.1.1`：

```kotlin
import com.stripe.StripeClient
import com.stripe.net.RequestOptions
import com.stripe.param.checkout.SessionCreateParams
import java.math.RoundingMode
import java.net.URI

private fun createCheckoutSession(order: OrderEntity): com.stripe.model.checkout.Session {
    val amountInMinorUnit = order.totalAmount
        .movePointRight(2)
        .setScale(0, RoundingMode.UNNECESSARY)
        .longValueExact()
    val successUrl = "${stripeProperties.checkout.storefrontBaseUrl}" +
        "/orders/${order.orderNo}/payment/result?session_id={CHECKOUT_SESSION_ID}"
    val cancelUrl = "${stripeProperties.checkout.storefrontBaseUrl}" +
        "/orders/${order.orderNo}/payment/cancelled"

    val params = SessionCreateParams.builder()
        .setMode(SessionCreateParams.Mode.PAYMENT)
        .setSuccessUrl(successUrl)
        .setCancelUrl(cancelUrl)
        .setClientReferenceId(order.orderNo)
        .putMetadata("orderNo", order.orderNo)
        .setPaymentIntentData(
            SessionCreateParams.PaymentIntentData.builder()
                .putMetadata("orderNo", order.orderNo)
                .build(),
        )
        .addLineItem(
            SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(
                    SessionCreateParams.LineItem.PriceData.builder()
                        .setCurrency(order.currency.lowercase())
                        .setUnitAmount(amountInMinorUnit)
                        .setProductData(
                            SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                .setName("Order ${order.orderNo}")
                                .build(),
                        )
                        .build(),
                )
                .build(),
        )
        .build()

    return stripeClient.checkout().sessions().create(
        params,
        RequestOptions.builder()
            .setIdempotencyKey("${order.orderNo}:checkout-session")
            .build(),
    )
}
```

一期不显式设置 Stripe `expires_at`，避免固定幂等键在网络重试时对应不同参数；Stripe 使用默认 Session 有效期，本地订单仍按持久化的 `OrderEntity.expiresAt` 超时，并通过可重试外盒任务使 Session 失效。创建成功后必须校验 `session.id` 与 `session.url` 非空；`session.paymentIntent` 若暂未返回则先绑定 Session，随后通过 Session 查询或 webhook 补齐。当前商城金额模型保留两位小数，因此一期仅开放 Stripe 的两位小数币种，例如 USD、EUR；若要支持零小数或三小数币种，必须按币种最小单位单独换算，不能固定 `movePointRight(2)`。

`StripeService` 应作为 Spring Bean 实现 `PaymentInterface`：

```kotlin
@Service
class StripeService(
    private val stripeClient: StripeClient,
) : PaymentInterface {
    override val provider = PaymentProviderId("stripe")
    override val capabilities = PaymentCapabilities(
        cancellation = true,
        refund = true,
        partialRefund = true,
        webhook = true,
    )

    // createPayment 返回 PaymentClientAction.Redirect(URI(session.url))。
    // query/cancel/refund 统一转换为项目的 PaymentStatus 与 PaymentRefundStatus。
}
```

`PaymentCreateRequest.returnUrl` 只能承载成功回跳地址。Checkout 还需要取消回跳地址，建议在支付契约中增加 `cancelUrl: URI?`，并由订单服务传入已校验的服务端配置值。

### 7.1 当前 Stripe 实现类的落地要求

`src/main/kotlin/top/foxball/shopmall/service/payMent/stripe/StripeService.kt` 当前仅是未完成的接口骨架。实现时应：

1. 删除无关的 `org.aspectj.weaver.ast.Var` 导入，并加上 `@Service`，使 Spring 能注册该适配器。
2. 将 `provider` 固定为 `PaymentProviderId("stripe")`，不要使用 `TODO()` getter。
3. 将 Stripe SDK 异常统一包装为 `PaymentProviderException`：参数错误不可重试；网络、限流和 5xx 错误可重试；验签失败为 `SIGNATURE_VERIFICATION` 且不可重试。
4. `queryPayment` 使用 `paymentIntents().retrieve(piId)`；`cancelPayment` 用于没有 Checkout Session 的兼容路径。Checkout 订单的取消由订单服务先使 Session 过期，再按 PaymentIntent 状态决定是否退款。
5. `refundPayment` 使用 PaymentIntent 创建 Refund；全额退款时不传金额，部分退款时将 `PaymentAmount` 按币种最小单位换算。
6. `parseWebhook` 验证签名后返回项目的 `PaymentWebhookEvent`；订单层仍应保留 Stripe 原始 `Event` 的事件类型分流，避免丢失 `checkout.session.*` 的精确信息。

## 8. Webhook 与订单状态机

Stripe Dashboard 中创建生产 webhook 端点：

```text
POST https://api.example.com/webhook
```

订阅以下事件：

| Stripe 事件 | 条件 | 本地处理 |
| --- | --- | --- |
| `checkout.session.completed` | `payment_status == paid` | 原子执行 `PENDING_PAYMENT -> PAID`、累计销量、写入 `PAID` 外盒事件 |
| `checkout.session.completed` | 非 `paid` 的延迟支付方式 | 保持 `PENDING_PAYMENT`，等待异步结果 |
| `checkout.session.async_payment_succeeded` | 无 | 与支付成功相同的原子处理 |
| `checkout.session.async_payment_failed` | 无 | 记录失败原因；订单保持待支付，后续由超时任务取消 |
| `checkout.session.expired` | 无 | 调用与超时任务相同的幂等取消逻辑并回补库存 |

独立的 `StripeWebhookController` 已读取原始请求体并使用 `StripeClient.constructEvent` 验签，这一边界应保留。需要将 `OrderPaymentService.handleWebhookEvent` 的事件对象从 `PaymentIntent` 改为 `com.stripe.model.checkout.Session`：

```kotlin
@Transactional
fun handleWebhookEvent(event: Event) {
    if (webhookEventRepository.claim(event.id, event.type) == 0) return

    val session = event.dataObjectDeserializer.getObject().orElse(null)
        as? com.stripe.model.checkout.Session
        ?: return
    val order = orderRepository.findByStripeCheckoutSessionId(session.id)
        ?: error("Checkout session ${session.id} is not attached to an order")

    when (event.type) {
        "checkout.session.completed" -> {
            if (session.paymentStatus == "paid") markPaid(order)
        }
        "checkout.session.async_payment_succeeded" -> markPaid(order)
        "checkout.session.async_payment_failed" -> recordPaymentFailure(order, session)
        "checkout.session.expired" -> cancelExpiredPendingOrder(order)
    }
}
```

`claim`、订单状态更新、销量累计和 `PAID` 外盒事件必须处于同一个事务。若本地事务失败，webhook 返回 5xx，Stripe 才会重投；不要在捕获异常后仍返回 200。对于找不到本地订单但 Session metadata 中包含 `orderNo` 的情况，应记录高优先级告警并返回可重试错误，避免 Session 创建和本地绑定之间的短暂竞态造成永久丢单。

Stripe Dashboard 中 webhook 端点的 API 版本必须固定为与当前 `stripe-java` 版本兼容的版本，并在升级 SDK 时同步验证和升级。对受支持的 `checkout.session.*` 事件，若 SDK 因 API 版本不兼容而无法反序列化，服务端必须抛出异常返回 5xx，使事件去重记录随事务回滚；禁止使用不安全反序列化后继续确认事件。

## 9. 取消、超时与退款

订单超时或客户取消时，不能直接取消 Checkout 所属的 PaymentIntent。处理顺序如下：

1. 本地以条件更新赢得 `PENDING_PAYMENT -> CANCELLED`，并且只有赢家回补库存。
2. 事务提交后，通过持久化外盒任务调用 `stripeClient.checkout().sessions().expire(sessionId)`，使仍处于 `open` 的托管页失效。
3. 若 Session 已完成或 PaymentIntent 已成功，则使用 `paymentIntentId` 创建退款；退款使用稳定幂等键，例如 `"${orderNo}:customer-cancel"`。
4. Stripe 调用失败不得只记录日志。应保留或新增可重试的支付补偿外盒事件，并对失败次数、最终失败和人工处理建立告警。

这避免客户在本地订单已取消后继续使用仍开放的 Checkout 页面完成支付；如果极端竞态下支付仍成功，webhook 的冲突分支必须退款，而不是重新将已取消订单改为已支付。

## 10. 前端跳转与回跳页

支付按钮的行为仅包含一次 API 调用和一次浏览器跳转：

```ts
const response = await api.post(`/api/orders/${orderNo}/checkout`)
window.location.assign(response.data.checkout_url)
```

成功页行为：

1. 从路由读取 `orderNo` 和可选的 `session_id`，但不把它们视为支付成功证据。
2. 调用受保护的订单支付查询接口。
3. `PAID` 时展示支付成功和订单入口；`PENDING_PAYMENT` 时每 2 秒轮询一次，直到收到 `PAID`、`CANCELLED` 或超过本地支付截止时间。
4. 轮询超时后展示“支付结果确认中”，提供手动刷新与客服联系入口，不重复创建订单或重复扣款。

取消页展示订单仍未支付，并提供返回订单详情、继续支付和主动取消订单的操作。重新支付仅在旧 Session 为 `open` 时复用其 URL；本方案不为同一待支付订单创建第二个 Session。

## 11. 安全与一致性要求

- `STRIPE_SECRET_KEY`、`STRIPE_WEBHOOK_SECRET` 仅保存在服务端环境变量，严禁返回给浏览器或写入日志。
- 托管 Checkout 不需要在前端使用 `STRIPE_PUBLIC_KEY` 或 `client_secret`；保留公钥仅在其他 Stripe 前端产品需要时使用。
- 所有 Checkout 金额、币种、订单号、过期时间、商品名称和回跳地址均由服务端生成。
- webhook 必须使用原始字节流和 `Stripe-Signature` 验签；不得先经 JSON 反序列化或网关字段改写。
- `stripe_webhook_events` 以 `evt_...` 去重；订单状态条件更新为第二层幂等保护。
- 创建 Checkout Session 必须携带 Stripe idempotency key；本地绑定使用条件更新，避免双击支付按钮产生多笔远端会话。
- Stripe Session metadata 与 `client_reference_id` 仅作为关联和诊断信息；查找本地订单时仍应匹配已持久化的 `stripe_checkout_session_id`。
- 不记录完整 webhook 请求体、支付卡信息、Stripe 签名或 Checkout URL 中的敏感查询参数。

## 12. 实施顺序

1. 开发环境启用 JPA 自动建表/更新，清理本地开发数据库；增加 `OrderEntity` 字段和仓储条件更新方法，本次不写 Flyway。
2. 将订单支付超时配置调整至不少于 30 分钟，并同步更新测试配置。
3. 扩展 `StripeProperties` 的主站地址配置，并在启动时校验协议、域名和 webhook secret。
4. 先扩展 `PaymentCreateRequest` 与 `PaymentTransaction`，再实现并注册 `StripeService`，使用 Checkout Session 创建和 `Redirect` 客户端动作。
5. 新增 `OrderCheckoutService` 及 `POST /api/orders/{orderNo}/checkout`，删除 `PI_CREATE` 外盒消费逻辑和 `clientSecret` 响应字段。
6. 改造 webhook 消费 Checkout Session 事件，保留现有签名校验、事件去重和订单原子状态机。
7. 改造取消/超时补偿：优先使 Session 过期，完成支付则退款，并将 Stripe 补偿失败接入可重试外盒。
8. 前端接入跳转、成功页轮询与取消页。

## 13. 改造文件清单

| 文件 | 修改内容 |
| --- | --- |
| `service/payMent/PaymentInterface.kt` | 增加 `cancelUrl`、`checkoutReference`，保持支付契约不暴露 Stripe SDK 类型 |
| `service/payMent/stripe/StripeClient.kt` | 扩展 Checkout 主站地址配置 |
| `service/payMent/stripe/StripeService.kt` | 完成 Checkout Session 创建、查询、退款、异常映射与适配器注册 |
| `entity/jdbc/OrderEntity.kt` | 增加 `stripeCheckoutSessionId`，扩展 PaymentIntent 标识长度 |
| `repository/OrderRepository.kt` | 增加 Session 查询和原子绑定方法，淘汰 `attachPaymentIntent` |
| `service/OrderPaymentService.kt` | 删除 `clientSecret` 语义，改为 Checkout 会话语义或拆分出 `OrderCheckoutService` |
| `service/impl/OrderPaymentServiceImpl.kt` | 从 PaymentIntent 前端确认改为 Checkout webhook 确认；保留退款冲突补偿 |
| `service/impl/OrderServiceImpl.kt` | 停止发布 `PI_CREATE`，响应模型不再持有 `clientSecret` |
| `service/impl/OutboxMessageHandler.kt` | 删除 `PI_CREATE` 分支；保留其他订单事件处理 |
| `controller/OrderController.kt` | 增加 `POST /api/orders/{orderNo}/checkout`，改造支付查询响应 |
| `controller/StripeWebhookController.kt` | 独立的 `/webhook` 入口，保留原始请求体和签名校验，事件类型改为 Checkout Session |
| `config/SecurityConfig.kt` | 仅放行 `POST /webhook`；确认 Checkout 创建与支付查询仍要求 JWT |
| `application.yaml`、`application-test.yaml` | 增加主站地址和不少于 30 分钟的支付超时配置 |

以上清单是实现范围，不要求在本次文档完善阶段修改代码。

## 14. 验收与测试

1. 使用 Stripe 测试密钥和测试卡 `4242 4242 4242 4242` 创建订单并跳转到托管页。
2. 支付完成后确认浏览器跳到配置的成功页，且成功页在 webhook 到达前显示确认中、到达后显示 `PAID`。
3. 刷新成功页、重复投递同一个 `evt_...`、重复调用 Checkout 接口，验证订单只支付一次且销量只累计一次。
4. 在 Stripe Checkout 页面取消，验证仅跳转取消页，不会自动将订单标记为取消。
5. 等待或手动使 Session 过期，验证订单取消、库存回补、Session 不可继续支付。
6. 在本地取消与 Stripe 支付成功并发时，验证本地取消获胜后自动退款。
7. 使用 Stripe CLI 转发本地 webhook：

```bash
stripe listen --forward-to localhost:8080/webhook
```

将 CLI 输出的 `whsec_...` 配置为本地 `STRIPE_WEBHOOK_SECRET`。生产环境使用 Stripe Dashboard 为 HTTPS 公网地址配置同一 webhook 端点。

## 15. 官方参考

- [Stripe Checkout 工作方式](https://docs.stripe.com/payments/checkout/how-checkout-works)
- [创建 Checkout Session API](https://docs.stripe.com/api/checkout/sessions/create)
- [自定义成功页与 `{CHECKOUT_SESSION_ID}`](https://docs.stripe.com/payments/checkout/custom-success-page)
- [Checkout 支付完成与异步支付 webhook](https://docs.stripe.com/payments/checkout/fulfillment)
- [Webhook 签名验证](https://docs.stripe.com/webhooks/signature)
- [Stripe 幂等请求](https://docs.stripe.com/api/idempotent_requests)

## 16. 已知问题与修复清单

以下问题来自对当前实现的代码审查。所有项目默认处于未修复状态；修复时应同步补充对应测试，并在通过验收条件后勾选。

### 16.1 P0：必须优先修复

- [x] **启动时强制校验 Stripe 密钥，禁止空 webhook secret**
  - 风险：`STRIPE_WEBHOOK_SECRET` 缺失时当前配置会绑定为空字符串，攻击者可能使用空密钥构造有效签名并伪造支付成功事件。
  - 涉及文件：`service/payMent/stripe/StripeClient.kt`、`application.yaml`、`application-test.yaml`。
  - 修复要求：生产配置中的 `secretKey` 和 `webhookSecret` 必须非空；建议同时校验 `sk_`、`whsec_` 前缀。配置无效时应用拒绝启动。
  - 验收条件：缺失任一密钥的启动测试失败；合法测试密钥可以正常加载 Spring 上下文。

- [x] **保证相同 Checkout 幂等键对应完全相同的请求参数**
  - 风险：当前 `expiresAt` 使用每次请求的当前时间计算，而幂等键固定为 `"${orderNo}:checkout-session"`。并发请求或 Stripe 创建成功但本地响应超时后的重试会携带不同参数，触发 Stripe 幂等冲突，导致 Session 无法恢复和绑定。
  - 涉及文件：`service/impl/OrderCheckoutServiceImpl.kt`。
  - 修复要求：Checkout 过期时间必须来自稳定、可持久化的数据，不得在每次重试时根据 `now` 重新生成；同一订单的金额、币种、回跳地址、metadata 和过期时间必须保持不变。
  - 验收条件：并发调用和模拟首次响应超时后的重试生成完全相同的 `PaymentCreateRequest`，最终只绑定一个 Session。

- [x] **Redis 锁竞争时不得确认支付补偿外盒事件**
  - 风险：当前支付协调器获取 Redis 锁失败后正常返回，外盒处理器随后仍会将事件标记为 `ACKNOWLEDGED`，造成 Session 未过期或退款未执行且任务永久丢失。
  - 涉及文件：`shared/PaymentIntentCoordinator.kt`、`service/impl/OutboxMessageHandler.kt`。
  - 修复要求：锁竞争必须返回明确的未执行结果或抛出可重试异常；只有确认 Stripe 操作已经完成或幂等地达到目标状态后才能确认外盒事件。
  - 验收条件：锁被占用时外盒事件保持可重试状态；释放锁后可再次执行并最终确认。

- [x] **Checkout webhook 反序列化失败时回滚事件去重记录**
  - 风险：当前实现先写入 webhook 去重记录，随后在 `EventDataObjectDeserializer.getObject()` 返回空时直接结束，事务会提交，真实支付事件将被永久忽略。
  - 涉及文件：`service/impl/OrderPaymentServiceImpl.kt`。
  - 修复要求：对受支持的 `checkout.session.*` 事件，反序列化失败必须抛出异常并返回 5xx，使去重记录随事务回滚；同时明确 Stripe webhook API 版本兼容策略。
  - 验收条件：模拟反序列化失败时订单状态不变、事件去重记录不存在；修复解析条件后重投同一事件可以成功处理。

### 16.2 P1：重要问题

- [x] **合并取消补偿与支付冲突补偿的退款幂等语义**
  - 风险：本地取消会发布 `PAYMENT_CANCEL_OR_REFUND`，支付成功竞态又会发布 `PAYMENT_CONFLICT_REFUND`；两个任务使用不同退款幂等键，可能对同一个 PaymentIntent 重复发起全额退款，导致其中一个任务持续失败并进入人工重放队列。
  - 涉及文件：`service/impl/OrderPaymentServiceImpl.kt`、`shared/PaymentIntentCoordinator.kt`。
  - 修复要求：同一订单同一取消结果应使用统一退款幂等键，或在退款前查询已退款金额和剩余可退款金额。
  - 验收条件：本地取消与 Stripe 支付成功并发时只创建一笔退款，两个补偿入口重复执行均能幂等成功。

- [x] **限制公开 Stripe webhook 请求体大小**
  - 风险：当前 webhook 对公开请求直接调用 `readBytes()`，普通 HTTP 请求不受 multipart 大小配置约束，可能被超大请求消耗堆内存。
  - 涉及文件：`controller/StripeWebhookController.kt`、支付相关配置类与配置文件。
  - 修复要求：在读取完整请求体前执行字节数限制；超过限制返回 `413 Payload Too Large`，且不得进入验签和业务处理。
  - 验收条件：正常 Stripe 事件可以通过；超过限制的请求稳定返回 413，且不会调用 `handleWebhookEvent`。

- [x] **修正 Stripe 异常映射的判断顺序**
  - 风险：当前先按 `InvalidRequestException`、`IdempotencyException` 分类，再判断 HTTP 404/409，导致资源不存在和幂等冲突无法分别映射为 `PAYMENT_NOT_FOUND`、`CONFLICT`。
  - 涉及文件：`service/payMent/stripe/StripeService.kt`。
  - 修复要求：优先依据明确的 HTTP 状态码分类，再按异常类型兜底，并保持网络、限流和 5xx 异常可重试。
  - 验收条件：为 400、401/403、404、409、429、5xx 和网络异常分别增加映射测试。

### 16.3 测试补充清单

- [x] 增加 Checkout Session 并发创建与原子绑定测试。
- [x] 增加 Stripe 已创建但本地响应超时后的幂等恢复测试。
- [x] 增加 webhook 重复投递、反序列化失败及 Session 尚未绑定时的重试测试。
- [x] 增加本地取消、订单超时与 Stripe 支付成功并发测试。
- [x] 增加 Redis 锁竞争时外盒事件不得确认的测试。
- [x] 增加取消补偿与冲突补偿重复执行时只退款一次的测试。
- [x] 增加 webhook 请求体大小限制与无效密钥启动测试。
