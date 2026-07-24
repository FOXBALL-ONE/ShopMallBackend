package top.foxball.shopmall.handler

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder


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
    
    @ExceptionHandler(DataAccessException::class)
    fun onDataAccessException(ex: DataAccessException): ResponseEntity<Response> {
        // 高并发扣减/状态机推进触发锁等待超时、死锁等并发异常时，映射为可重试的 503 而非被 catch-all 吞成 500
        log.warn("Data access error (likely lock contention): {}", ex.message)
        return builder.serviceUnavailable()
            .retryAfter(1)
            .message("系统繁忙，请稍后重试")
            .build()
    }

    @ExceptionHandler(Exception::class)
    fun onException(req: HttpServletRequest, ex: Exception?): ResponseEntity<Response> {
        log.error("Got an exception while process request: {}", req.requestURI, ex)
        return builder.exception().build()
    }
}
