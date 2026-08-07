package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.ratelimit.RateLimitSettingsUpdateResult
import top.foxball.shopmall.ratelimit.RateLimitSettingsService
import top.foxball.shopmall.ratelimit.UpdateRateLimitSettingsCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** @folder 管理端/限速设置 */
@Validated
@RestController
@RequestMapping("/admin/api/rate-limit-settings")
class AdminRateLimitController(
    private val settingsService: RateLimitSettingsService,
    private val builder: ResponseBuilder,
) {
    /** @api 获取当前全局 API 限速设置 */
    @GetMapping
    fun getSettings(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val enabled: Boolean,
            @param:JsonProperty("window_seconds") val windowSeconds: Long,
            @param:JsonProperty("authenticated_requests_per_minute") val authenticatedRequestsPerMinute: Int,
            @param:JsonProperty("anonymous_requests_per_minute") val anonymousRequestsPerMinute: Int,
            @param:JsonProperty("excluded_paths") val excludedPaths: List<String>,
            val version: Long,
            val source: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            @param:JsonProperty("updated_by") val updatedBy: Long?,
        )

        val settings = settingsService.getSettings(adminId)
        val rs = Response(
            enabled = settings.enabled,
            windowSeconds = settings.windowSeconds,
            authenticatedRequestsPerMinute = settings.authenticatedRequestsPerMinute,
            anonymousRequestsPerMinute = settings.anonymousRequestsPerMinute,
            excludedPaths = settings.excludedPaths,
            version = settings.version,
            source = settings.source.name,
            updatedAt = settings.updatedAt,
            updatedBy = settings.updatedBy,
        )
        return builder.ok().data(rs).build()
    }

    /** @api 完整替换全局 API 限速开关、额度和免限速路径 */
    @PutMapping
    fun updateSettings(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("authenticated_requests_per_minute") @Min(1) @Max(1000)
        authenticatedRequestsPerMinute: Int,
        @RequestParam("anonymous_requests_per_minute") @Min(1) @Max(1000)
        anonymousRequestsPerMinute: Int,
        @RequestParam("excluded_path", required = false) excludedPaths: List<String>?,
        @RequestParam("enabled") enabled: Boolean,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class ConflictResponse(
            @param:JsonProperty("actual_version") val actualVersion: Long,
        )

        data class Response(
            val enabled: Boolean,
            @param:JsonProperty("window_seconds") val windowSeconds: Long,
            @param:JsonProperty("authenticated_requests_per_minute") val authenticatedRequestsPerMinute: Int,
            @param:JsonProperty("anonymous_requests_per_minute") val anonymousRequestsPerMinute: Int,
            @param:JsonProperty("excluded_paths") val excludedPaths: List<String>,
            val version: Long,
            val source: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            @param:JsonProperty("updated_by") val updatedBy: Long?,
        )

        val command = UpdateRateLimitSettingsCommand(
            enabled = enabled,
            authenticatedRequestsPerMinute = authenticatedRequestsPerMinute,
            anonymousRequestsPerMinute = anonymousRequestsPerMinute,
            excludedPaths = excludedPaths ?: emptyList(),
            expectedVersion = expectedVersion,
        )
        val result = settingsService.updateSettings(adminId, command)
        if (result is RateLimitSettingsUpdateResult.Conflict) {
            val rs = ConflictResponse(actualVersion = result.actualVersion)
            return builder.status(HttpStatus.CONFLICT)
                .message("限速设置已被其他管理员更新，请重新加载后再保存")
                .data(rs)
                .build()
        }

        val settings = (result as RateLimitSettingsUpdateResult.Updated).settings
        val rs = Response(
            enabled = settings.enabled,
            windowSeconds = settings.windowSeconds,
            authenticatedRequestsPerMinute = settings.authenticatedRequestsPerMinute,
            anonymousRequestsPerMinute = settings.anonymousRequestsPerMinute,
            excludedPaths = settings.excludedPaths,
            version = settings.version,
            source = settings.source.name,
            updatedAt = settings.updatedAt,
            updatedBy = settings.updatedBy,
        )
        return builder.ok().data(rs).build()
    }

    /** @api 仅更新全局 API 限速开关；同时支持 PUT 与 PATCH，额度和路径保持不变 */
    @RequestMapping(path = ["/enabled"], method = [RequestMethod.PUT, RequestMethod.PATCH])
    fun updateEnabled(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("enabled") enabled: Boolean,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class ConflictResponse(
            @param:JsonProperty("actual_version") val actualVersion: Long,
        )

        data class Response(
            val enabled: Boolean,
            @param:JsonProperty("window_seconds") val windowSeconds: Long,
            @param:JsonProperty("authenticated_requests_per_minute") val authenticatedRequestsPerMinute: Int,
            @param:JsonProperty("anonymous_requests_per_minute") val anonymousRequestsPerMinute: Int,
            @param:JsonProperty("excluded_paths") val excludedPaths: List<String>,
            val version: Long,
            val source: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            @param:JsonProperty("updated_by") val updatedBy: Long?,
        )

        val result = settingsService.updateEnabled(adminId, enabled, expectedVersion)
        if (result is RateLimitSettingsUpdateResult.Conflict) {
            val rs = ConflictResponse(actualVersion = result.actualVersion)
            return builder.status(HttpStatus.CONFLICT)
                .message("限速设置已被其他管理员更新，请重新加载后再保存")
                .data(rs)
                .build()
        }

        val settings = (result as RateLimitSettingsUpdateResult.Updated).settings
        val rs = Response(
            enabled = settings.enabled,
            windowSeconds = settings.windowSeconds,
            authenticatedRequestsPerMinute = settings.authenticatedRequestsPerMinute,
            anonymousRequestsPerMinute = settings.anonymousRequestsPerMinute,
            excludedPaths = settings.excludedPaths,
            version = settings.version,
            source = settings.source.name,
            updatedAt = settings.updatedAt,
            updatedBy = settings.updatedBy,
        )
        return builder.ok().data(rs).build()
    }
}
