package top.foxball.shopmall.handler

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import top.foxball.shopmall.service.payMent.PaymentProviderError
import top.foxball.shopmall.service.payMent.PaymentProviderException


/** 全局异常处理：将各类异常转换为统一 [Response] 响应。 */
@Order(2)
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(this.javaClass)
    private val builder = ResponseBuilder()

    @ExceptionHandler(BusinessException::class)
    fun onBusinessException(ex: BusinessException): ResponseEntity<Response> {
        return builder.status(ex.status)
            .message(ex.message)
            .build()
    }

    @ExceptionHandler(OrderProcessingException::class)
    fun onOrderProcessingException(ex: OrderProcessingException): ResponseEntity<Response> {
        return builder.status(ex.status)
            .retryAfter(1)
            .message(ex.message)
            .build()
    }

    @ExceptionHandler(OrderWindowLimitException::class)
    fun onOrderWindowLimitException(ex: OrderWindowLimitException): ResponseEntity<Response> {
        return builder.status(ex.status)
            .retryAfter(ex.retryAfterSeconds)
            .message(ex.message)
            .build()
    }
    @ExceptionHandler(SupportTicketRateLimitException::class)
    fun onSupportTicketRateLimitException(ex: SupportTicketRateLimitException): ResponseEntity<Response> {
        return builder.status(ex.status)
            .retryAfter(ex.retryAfterSeconds)
            .message(ex.message)
            .build()
    }

    @ExceptionHandler(PaymentProviderException::class)
    fun onPaymentProviderException(ex: PaymentProviderException): ResponseEntity<Response> = when (ex.error) {
        PaymentProviderError.INVALID_REQUEST, PaymentProviderError.SIGNATURE_VERIFICATION ->
            builder.badRequest().message(ex.message).build()
        PaymentProviderError.PAYMENT_NOT_FOUND -> builder.notFound().message(ex.message).build()
        PaymentProviderError.CONFLICT, PaymentProviderError.UNSUPPORTED_OPERATION ->
            builder.status(org.springframework.http.HttpStatus.CONFLICT).message(ex.message).build()
        PaymentProviderError.RATE_LIMITED -> builder.tooManyRequests().retryAfter(1).message(ex.message).build()
        PaymentProviderError.AUTHENTICATION,
        PaymentProviderError.TEMPORARILY_UNAVAILABLE ->
            builder.serviceUnavailable().retryAfter(1).message(ex.message).build()
        PaymentProviderError.UNKNOWN -> if (ex.retryable) {
            builder.serviceUnavailable().retryAfter(1).message(ex.message).build()
        } else {
            builder.exception().message(ex.message).build()
        }
    }

    @ExceptionHandler(AccessDeniedException::class)
    fun onAccessDeniedException(ex: AccessDeniedException): ResponseEntity<Response> {
        return builder.forbidden()
            .message(ex.message ?: "禁止访问")
            .build()
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun onHttpRequestMethodNotSupportedException(ex: HttpRequestMethodNotSupportedException): ResponseEntity<Response> {
        return builder.badRequest()
            .message("Method \"${ex.method}\" is not supported on this endpoint.")
            .build()
    }

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun onNoResourceOrHandlerFoundException(): ResponseEntity<Response> {
        return builder.notFound().build()
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun onMissingServletRequestParameterException(ex: MissingServletRequestParameterException): ResponseEntity<Response> {
        return builder.badRequest()
            .message("Required parameter \"${ex.parameterName}\" is not provided!")
            .build()
    }

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun onMissingRequestHeaderException(ex: MissingRequestHeaderException): ResponseEntity<Response> {
        return builder.badRequest()
            .message("Required request header \"${ex.headerName}\" is not provided!")
            .build()
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onMethodArgumentTypeMismatchException(ex: MethodArgumentTypeMismatchException): ResponseEntity<Response> {
        return builder.badRequest()
            .message("Parameter \"${ex.parameter.parameterName}\" type mismatch. Expected ${ex.requiredType}.")
            .build()
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun onMethodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<Response> {
        val detail = ex.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return builder.badRequest()
            .message("参数校验失败：$detail")
            .build()
    }

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun onHandlerMethodValidationException(ex: HandlerMethodValidationException): ResponseEntity<Response> {
        val detail = ex.parameterValidationResults.joinToString("; ") { result ->
            val parameterName = result.methodParameter.parameterName ?: "parameter"
            val messages = result.resolvableErrors.joinToString(", ") { error ->
                error.defaultMessage ?: "invalid value"
            }
            "$parameterName: $messages"
        }
        return builder.badRequest()
            .message(if (detail.isBlank()) "参数校验失败" else "参数校验失败: $detail")
            .build()
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun onConstraintViolationException(ex: ConstraintViolationException): ResponseEntity<Response> {
        val detail = ex.constraintViolations.joinToString("; ") { violation ->
            "${violation.propertyPath}: ${violation.message}"
        }
        return builder.badRequest()
            .message("参数校验失败: $detail")
            .build()
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun onHttpMessageNotReadable(ex: HttpMessageNotReadableException): ResponseEntity<Response> {
        return builder.badRequest()
            .message("请求体格式错误或必填字段缺失")
            .build()
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun onMaxUploadSizeExceededException(): ResponseEntity<Response> {
        return builder.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE)
            .message("Uploaded file exceeds the configured size limit.")
            .build()
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun onIllegalArgumentException(ex: IllegalArgumentException?): ResponseEntity<Response> {
        log.warn("Illegal argument access happened: ", ex)
        return builder.badRequest()
            .message(ex?.message ?: "Invalid argument.")
            .build()
    }
    
    @ExceptionHandler(TransientDataAccessException::class)
    fun onTransientDataAccessException(ex: TransientDataAccessException): ResponseEntity<Response> {
        log.warn("Transient data access error: {}", ex.message)
        return builder.serviceUnavailable()
            .retryAfter(1)
            .message("系统繁忙，请稍后重试")
            .build()
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun onOptimisticLockingFailureException(ex: ObjectOptimisticLockingFailureException): ResponseEntity<Response> {
        log.warn("Optimistic locking conflict: {}", ex.message)
        return builder.status(org.springframework.http.HttpStatus.CONFLICT)
            .message("数据已被其他操作更新，请刷新后重试")
            .build()
    }

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun onDataIntegrityViolationException(ex: DataIntegrityViolationException): ResponseEntity<Response> {
        val detail = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        val message = when {
            "uk_shipment_item_active" in detail -> "商品已分配给其他有效运单"
            "uk_shipment_carrier_tracking" in detail -> "承运商追踪号已绑定其他运单"
            "uk_logistics_idempotency" in detail -> "幂等键冲突，请重试查询原结果"
            "uk_order_idempotency" in detail -> "下单幂等键冲突，请重试查询原订单"
            "fk_support_ticket_message_attachment_file" in detail -> "工单消息使用中的附件不能删除"
            else -> null
        }
        if (message != null) {
            return builder.status(org.springframework.http.HttpStatus.CONFLICT).message(message).build()
        }
        log.error("Unhandled data integrity violation", ex)
        return builder.exception().build()
    }

    @ExceptionHandler(DataAccessException::class)
    fun onDataAccessException(ex: DataAccessException): ResponseEntity<Response> {
        log.error("Non-transient data access error", ex)
        return builder.exception().build()
    }

    @ExceptionHandler(Exception::class)
    fun onException(req: HttpServletRequest, ex: Exception?): ResponseEntity<Response> {
        log.error("Got an exception while process request: {}", req.requestURI, ex)
        return builder.exception().build()
    }
}
