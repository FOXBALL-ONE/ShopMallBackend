package top.foxball.shopmall.handler

import org.springframework.http.HttpStatus

/** 业务异常基类，携带 HTTP 状态码；子类对应各类业务错误（未找到、未授权、参数错误等）。 */
open class BusinessException(
    val status: HttpStatus,
    override val message: String
) : RuntimeException(message) {
    val code: Int = status.value()
}

class UserNotFoundException(
    message: String = "用户不存在"
) : BusinessException(HttpStatus.NOT_FOUND, message)

class UserAlreadyExistsException(
    message: String = "用户已存在"
) : BusinessException(HttpStatus.CONFLICT, message)

class UsernameOrPasswordErrorException(
    message: String = "用户名或密码错误"
) : BusinessException(HttpStatus.UNAUTHORIZED, message)

class UserDisabledException(
    message: String = "用户被禁用"
) : BusinessException(HttpStatus.FORBIDDEN, message)

class UnauthorizedException(
    message: String = "未授权"
) : BusinessException(HttpStatus.UNAUTHORIZED, message)

class ForbiddenException(
    message: String = "禁止访问"
) : BusinessException(HttpStatus.FORBIDDEN, message)

class ResourceNotFoundException(
    message: String = "资源不存在"
) : BusinessException(HttpStatus.NOT_FOUND, message)

class ParamErrorException(
    message: String = "参数错误"
) : BusinessException(HttpStatus.BAD_REQUEST, message)

class VerificationCodeInvalidException(
    message: String = "验证码错误或已过期"
) : BusinessException(HttpStatus.BAD_REQUEST, message)

class EmailSendFailedException(
    message: String = "验证码邮件发送失败，请稍后重试"
) : BusinessException(HttpStatus.SERVICE_UNAVAILABLE, message)

class VerificationCodeRateLimitException(
    message: String = "验证码发送过于频繁，请稍后再试"
) : BusinessException(HttpStatus.TOO_MANY_REQUESTS, message)

class TokenInvalidException(
    message: String = "Token 无效"
) : BusinessException(HttpStatus.UNAUTHORIZED, message)

class TokenExpiredException(
    message: String = "Token 过期"
) : BusinessException(HttpStatus.UNAUTHORIZED, message)

class TokenForbiddenException(
    message: String = "Token 校验失败"
) : BusinessException(HttpStatus.FORBIDDEN, message)

class InsufficientStockException(
    message: String = "库存不足"
) : BusinessException(HttpStatus.CONFLICT, message)

class OrderNotFoundException(
    message: String = "订单不存在"
) : BusinessException(HttpStatus.NOT_FOUND, message)

class OrderStatusException(
    message: String = "订单状态不允许此操作"
) : BusinessException(HttpStatus.CONFLICT, message)

class OrderProcessingException(
    message: String = "上一次下单请求仍在处理中，请稍后重试",
) : BusinessException(HttpStatus.CONFLICT, message)

class PaymentFailureException(
    message: String = "支付失败"
) : BusinessException(HttpStatus.BAD_REQUEST, message)

class EmailNotVerifiedException(
    message: String = "邮箱未验证，暂不能下单"
) : BusinessException(HttpStatus.FORBIDDEN, message)

class ShipmentNotFoundException(
    message: String = "运单不存在",
) : BusinessException(HttpStatus.NOT_FOUND, message)

class ShipmentStatusException(
    message: String = "运单状态不允许此操作",
) : BusinessException(HttpStatus.CONFLICT, message)

class ShipmentAllocationConflictException(
    message: String = "商品已分配给其他有效运单",
) : BusinessException(HttpStatus.CONFLICT, message)

class TrackingNumberConflictException(
    message: String = "承运商追踪号已绑定其他运单",
) : BusinessException(HttpStatus.CONFLICT, message)

class IdempotencyConflictException(
    message: String = "幂等键已用于不同请求",
) : BusinessException(HttpStatus.CONFLICT, message)

class OrderWindowLimitException(
    val retryAfterSeconds: Long,
    message: String = "下单过于频繁，请稍后再试",
) : BusinessException(HttpStatus.TOO_MANY_REQUESTS, message)

class IdempotencyKeyInvalidException(
    message: String = "幂等键无效或不属于当前用户",
) : BusinessException(HttpStatus.FORBIDDEN, message)

class CarrierException(
    message: String = "承运商服务异常",
) : BusinessException(HttpStatus.BAD_GATEWAY, message)

class CarrierSignatureException(
    message: String = "承运商回调验签失败",
) : BusinessException(HttpStatus.UNAUTHORIZED, message)

class WebhookPayloadTooLargeException(
    message: String = "Webhook 请求体过大",
) : BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, message)

class SupportTicketStatusException(
    message: String = "工单状态不允许此操作",
) : BusinessException(HttpStatus.CONFLICT, message)
class SupportTicketRateLimitException(
    val retryAfterSeconds: Long,
    message: String = "工单操作过于频繁，请稍后再试",
) : BusinessException(HttpStatus.TOO_MANY_REQUESTS, message)

class SupportTicketRequestInProgressException(
    message: String = "相同工单请求仍在处理中，请稍后重试",
) : BusinessException(HttpStatus.CONFLICT, message)

class SupportTicketAttachmentLimitException(
    message: String = "工单附件超过允许的限制",
) : BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, message)

class SupportTicketUnsafeAttachmentException(
    message: String = "工单附件类型不受支持或未通过安全检查",
) : BusinessException(HttpStatus.BAD_REQUEST, message)
