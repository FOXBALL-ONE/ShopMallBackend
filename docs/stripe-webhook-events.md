# Stripe Webhook 事件说明

> 适用范围：ShopMall Stripe Checkout 一次性支付、退款及后续扩展场景
> 更新时间：2026-08-13

## 1. 概览

Stripe Webhook 事件按资源类型命名，例如 `checkout.session.*`、`payment_intent.*`、`refund.*` 和 `invoice.*`。事件集合会随 Stripe 产品与 API 演进增加，因此不应把本文当作固定的全量枚举；实际可订阅事件应以 Stripe Dashboard 的 Webhook Endpoint 配置页面和 Stripe 官方事件类型参考为准。

ShopMall 当前使用 Stripe Checkout 处理一次性订单支付。支付结果以经过签名验证的 Webhook 为准，不能以前端回跳页或浏览器展示结果作为订单已支付的依据。

## 2. ShopMall 当前订阅事件

当前后端在 `OrderPaymentServiceImpl` 中支持以下事件：

| 事件 | Stripe 含义 | ShopMall 处理 |
|---|---|---|
| `checkout.session.completed` | Checkout 会话完成 | 仅当 `payment_status=paid` 时绑定 PaymentIntent、将订单推进为 `PAID` 并写入 `ORDER/PAID` Outbox 事件 |
| `checkout.session.async_payment_succeeded` | 异步支付方式最终收款成功 | 绑定 PaymentIntent、将订单推进为 `PAID` 并写入 `ORDER/PAID` Outbox 事件 |
| `checkout.session.async_payment_failed` | 异步支付方式最终失败 | 当前记录告警，不推进订单为已支付 |
| `checkout.session.expired` | 未完成的 Checkout 会话过期 | 取消待支付订单、释放库存，并按需要创建退款补偿任务 |
| `refund.created` | 退款对象已创建 | 关联订单退款记录，等待终态通知或主动查询 |
| `refund.updated` | 退款状态更新 | 根据退款终态完成退款、部分退款或恢复付款状态 |
| `refund.failed` | 退款失败 | 恢复退款中的订单状态 |

支付成功后，`ORDER/PAID` Outbox 消费者会使用订单的 PaymentIntent 查询 `latest_charge.receipt_url`，再异步发送含 Stripe 收据链接的订单支付完成邮件。

## 3. Checkout 事件

| 事件 | 何时发生 | 建议用途 |
|---|---|---|
| `checkout.session.completed` | 客户完成 Checkout 流程 | 一次性支付中检查 `payment_status`；`paid` 才能履约或发送付款成功通知 |
| `checkout.session.async_payment_succeeded` | 延迟确认的支付方式最终成功 | 将待支付订单转为已支付 |
| `checkout.session.async_payment_failed` | 延迟确认的支付方式最终失败 | 保持或关闭待支付订单，并通知客户重新支付 |
| `checkout.session.expired` | Checkout Session 到期且未完成 | 取消本地待支付订单、释放预留库存 |

注意事项：

- `checkout.session.completed` 表示会话完成，不必然表示资金已经到账；一次性支付必须结合 `payment_status=paid` 判断。
- 采用异步支付方式时，不能只订阅 `checkout.session.completed`，还需要订阅 `checkout.session.async_payment_succeeded` 和 `checkout.session.async_payment_failed`。
- Checkout 订阅模式也会发送 `checkout.session.completed`，但订阅续费成功应主要根据账单事件处理。

## 4. PaymentIntent 事件

PaymentIntent 是支付状态机。ShopMall 当前通过 Checkout Session 接入，通常以 Checkout 事件作为订单状态入口；以下事件在改用自定义支付界面、Payment Element 或需要补充支付审计时有用。

| 事件 | 含义 | 典型用途 |
|---|---|---|
| `payment_intent.created` | PaymentIntent 已创建 | 审计或初始化本地支付记录 |
| `payment_intent.processing` | 支付处理中 | 展示处理中状态，等待最终结果 |
| `payment_intent.requires_action` | 需要客户完成额外验证 | 提示前端继续 3DS 或其他认证流程 |
| `payment_intent.succeeded` | PaymentIntent 支付成功 | 非 Checkout 集成的支付成功确认 |
| `payment_intent.payment_failed` | 支付失败 | 记录失败原因并提示客户重试 |
| `payment_intent.canceled` | PaymentIntent 已取消 | 同步本地支付取消状态 |
| `payment_intent.amount_capturable_updated` | 已授权且可捕获金额变化 | 手动捕获支付模式 |

不要在 Checkout 和 `payment_intent.succeeded` 两条路径上对同一个订单重复履约。若将来订阅 PaymentIntent 事件，必须通过订单状态条件更新和 Stripe `event.id` 去重保证副作用最多执行一次。

## 5. 退款、Charge 与争议事件

| 事件 | 含义 | 建议用途 |
|---|---|---|
| `refund.created` | 退款请求已创建 | 写入退款 ID 与处理中状态 |
| `refund.updated` | 退款状态变更 | 根据 `status` 同步退款成功、失败或取消 |
| `refund.failed` | 退款失败 | 恢复可售后状态并通知运营 |
| `charge.refunded` | Charge 已发生退款 | 兼容 Charge 视角的退款审计；新实现优先使用 `refund.*` |
| `charge.dispute.created` | 客户发起拒付或争议 | 冻结高风险履约动作、通知运营并保留证据 |
| `charge.dispute.updated` | 争议状态或证据变更 | 同步运营处理进度 |
| `charge.dispute.closed` | 争议关闭 | 根据胜诉、败诉或撤销结果更新财务与订单状态 |

退款与拒付是独立业务过程。付款成功邮件不应因为后续退款或争议而重发；相应的退款或争议通知应使用独立事件和模板。

## 6. 订阅与账单事件

以下事件仅在引入 Stripe Billing、订阅或 Invoicing 后订阅：

| 事件 | 建议用途 |
|---|---|
| `customer.subscription.created` | 初始化本地订阅权益 |
| `customer.subscription.updated` | 同步套餐、状态、取消计划和试用期变化 |
| `customer.subscription.deleted` | 关闭订阅权益 |
| `customer.subscription.trial_will_end` | 试用到期提醒 |
| `invoice.created` | 账单生成后的审计或补充账单信息 |
| `invoice.finalized` | 账单最终确认 |
| `invoice.paid` | 首次订阅或续费实际到账后开通、续期权益 |
| `invoice.payment_failed` | 续费失败、催款或限制权益 |
| `invoice.payment_action_required` | 续费需要客户完成额外认证 |
| `invoice.voided` | 账单作废后同步本地状态 |

对于订阅续费，`invoice.paid` 比 `checkout.session.completed` 更适合作为持续开通权益的支付确认事件。

## 7. 客户、支付方式与 SetupIntent 事件

| 事件 | 适用场景 |
|---|---|
| `customer.created`、`customer.updated`、`customer.deleted` | 将 Stripe Customer 与本地客户档案同步 |
| `payment_method.attached`、`payment_method.detached` | 保存或移除可复用支付方式 |
| `setup_intent.succeeded` | 客户成功保存支付方式，可用于后续扣款 |
| `setup_intent.setup_failed` | 保存支付方式失败，需要客户重试 |

ShopMall 当前的一次性 Checkout 订单不要求订阅这些事件；只有需要保存支付方式或进行后续主动扣款时才需要接入。

## 8. Connect、打款与其他扩展事件

若 ShopMall 后续成为平台型业务或市场撮合业务，可按需增加：

| 事件 | 适用场景 |
|---|---|
| `account.updated` | Connect 账户 KYC、收款能力或资料变更 |
| `capability.updated` | Connect 账户能力启用或受限 |
| `person.updated` | Connect 账户人员验证资料变更 |
| `transfer.created`、`transfer.paid`、`transfer.failed` | 平台向关联账户分账 |
| `payout.paid`、`payout.failed` | Stripe 向商户银行账户打款结果 |
| `review.opened`、`review.closed` | Radar 人工风控审核 |

这些事件不适用于普通单商户的一次性订单支付，除非实际启用对应 Stripe 产品。

## 9. 统一处理原则

1. 使用原始请求体和 `Stripe-Signature` 验证 Webhook 签名；未通过验证的请求不可进入业务处理。
2. 以 `event.id` 幂等去重；Stripe 会重试投递，事件也可能乱序到达。
3. Webhook 请求内只执行必要的本地事务，尽快返回 2xx；邮件、第三方查询和耗时任务通过 Outbox 异步处理。
4. 订单状态更新使用条件更新，例如只允许 `PENDING_PAYMENT -> PAID`，以防重复事件导致重复库存、副作用或通知。
5. 记录事件类型、事件 ID、关联订单与处理结果；不要记录 Stripe Secret、Webhook Secret、完整收据链接或客户敏感信息。
6. 对未知事件安全忽略并保留可观测日志；只为实际启用的 Stripe 产品订阅需要的事件，避免不必要的处理和告警。
7. 使用 Stripe Checkout 时保持动态支付方式配置，不传 `payment_method_types`；支付方式由 Stripe Dashboard 或 Payment Method Configuration 管理。

## 10. 配置建议

建议在 Stripe Dashboard 为 ShopMall 的 Webhook Endpoint 至少勾选：

```text
checkout.session.completed
checkout.session.async_payment_succeeded
checkout.session.async_payment_failed
checkout.session.expired
refund.created
refund.updated
refund.failed
```

当新增订阅、保存支付方式、Connect、拒付处理或主动扣款能力时，再按本文对应章节增量订阅事件，并为每个事件补充幂等、乱序、失败重试和状态机回归测试。
