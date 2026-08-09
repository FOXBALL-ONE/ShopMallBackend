package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.shopmall.logging.HistoryContentPage
import top.foxball.shopmall.logging.LiveLogQuery
import top.foxball.shopmall.logging.LiveLogService
import top.foxball.shopmall.logging.LogHistoryService
import top.foxball.shopmall.logging.LogLevel
import top.foxball.shopmall.logging.LoggingProperties
import top.foxball.shopmall.logging.LoggingSettingsService
import top.foxball.shopmall.logging.LoggingSettingsUpdateResult
import top.foxball.shopmall.logging.RuntimeLoggingManager
import top.foxball.shopmall.logging.UpdateLoggingSettingsCommand
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDate
import java.time.LocalDateTime

/** @folder 管理端/日志中心 */
@Validated
@RestController
@RequestMapping("/admin/api/logs")
class AdminLogController(
    private val settingsService: LoggingSettingsService,
    private val runtimeLoggingManager: RuntimeLoggingManager,
    private val properties: LoggingProperties,
    private val liveLogService: LiveLogService,
    private val historyService: LogHistoryService,
    private val builder: ResponseBuilder,
) {
    /** @api 获取日志运行设置及本机输出状态 */
    @GetMapping("/settings")
    fun getSettings(
        @AuthenticationPrincipal adminId: Long,
    ): ResponseEntity<Response> {
        data class LoggerOverrideData(
            @param:JsonProperty("logger_name") val loggerName: String,
            val level: String,
        )

        data class Response(
            @param:JsonProperty("root_level") val rootLevel: String,
            @param:JsonProperty("logger_overrides") val loggerOverrides: List<LoggerOverrideData>,
            @param:JsonProperty("output_template") val outputTemplate: String,
            val version: Long,
            val source: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            @param:JsonProperty("updated_by") val updatedBy: Long?,
            @param:JsonProperty("effective_version") val effectiveVersion: Long,
            @param:JsonProperty("runtime_status") val runtimeStatus: String,
            @param:JsonProperty("instance_id") val instanceId: String,
            @param:JsonProperty("storage_path") val storagePath: String,
            @param:JsonProperty("time_zone") val timeZone: String,
            @param:JsonProperty("max_file_size_bytes") val maxFileSizeBytes: Long,
            @param:JsonProperty("retention_days") val retentionDays: Int,
            @param:JsonProperty("active_file") val activeFile: String?,
            @param:JsonProperty("active_file_size_bytes") val activeFileSizeBytes: Long,
            @param:JsonProperty("last_file_error") val lastFileError: String?,
        )

        val settings = settingsService.getSettings(adminId)
        val runtime = runtimeLoggingManager.snapshot()
        val rs = Response(
            rootLevel = settings.rootLevel.name,
            loggerOverrides = settings.loggerLevels.map { (name, level) -> LoggerOverrideData(name, level.name) },
            outputTemplate = settings.outputTemplate,
            version = settings.version,
            source = settings.source.name,
            updatedAt = settings.updatedAt,
            updatedBy = settings.updatedBy,
            effectiveVersion = runtime.effectiveVersion,
            runtimeStatus = runtime.status.name,
            instanceId = properties.instanceId,
            storagePath = properties.rootPath.toString(),
            timeZone = properties.timeZone,
            maxFileSizeBytes = properties.maxFileSize.toBytes(),
            retentionDays = properties.retentionDays,
            activeFile = runtime.activeFile.relativePath,
            activeFileSizeBytes = runtime.activeFile.sizeBytes,
            lastFileError = runtime.activeFile.lastError ?: runtime.lastError,
        )
        return builder.ok().data(rs).build()
    }

    /** @api 校验并渲染输出模板预览，不修改当前设置 */
    @PostMapping("/settings/preview")
    fun previewTemplate(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("output_template") @Size(min = 1, max = 1024) outputTemplate: String,
    ): ResponseEntity<Response> {
        data class Response(
            val rendered: String,
            @param:JsonProperty("encoded_size_bytes") val encodedSizeBytes: Int,
        )

        val preview = settingsService.preview(adminId, outputTemplate)
        val rs = Response(preview.rendered, preview.encodedSizeBytes)
        return builder.ok().data(rs).build()
    }

    /** @api 以版本比较完整替换根等级、命名覆盖和输出模板 */
    @PutMapping("/settings")
    fun updateSettings(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("root_level") rootLevel: String,
        @RequestParam("logger_override", required = false) loggerOverrides: List<String>?,
        @RequestParam("output_template") @Size(min = 1, max = 1024) outputTemplate: String,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class ConflictResponse(
            @param:JsonProperty("actual_version") val actualVersion: Long,
        )

        data class LoggerOverrideData(
            @param:JsonProperty("logger_name") val loggerName: String,
            val level: String,
        )

        data class Response(
            @param:JsonProperty("root_level") val rootLevel: String,
            @param:JsonProperty("logger_overrides") val loggerOverrides: List<LoggerOverrideData>,
            @param:JsonProperty("output_template") val outputTemplate: String,
            val version: Long,
            val source: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            @param:JsonProperty("updated_by") val updatedBy: Long?,
            @param:JsonProperty("effective_version") val effectiveVersion: Long,
            @param:JsonProperty("runtime_status") val runtimeStatus: String,
            @param:JsonProperty("instance_id") val instanceId: String,
            @param:JsonProperty("storage_path") val storagePath: String,
            @param:JsonProperty("time_zone") val timeZone: String,
            @param:JsonProperty("max_file_size_bytes") val maxFileSizeBytes: Long,
            @param:JsonProperty("retention_days") val retentionDays: Int,
            @param:JsonProperty("active_file") val activeFile: String?,
            @param:JsonProperty("active_file_size_bytes") val activeFileSizeBytes: Long,
            @param:JsonProperty("last_file_error") val lastFileError: String?,
        )

        val command = UpdateLoggingSettingsCommand(
            rootLevel = rootLevel,
            loggerOverrides = loggerOverrides ?: emptyList(),
            outputTemplate = outputTemplate,
            expectedVersion = expectedVersion,
        )
        val result = settingsService.updateSettings(adminId, command)
        if (result is LoggingSettingsUpdateResult.Conflict) {
            val rs = ConflictResponse(result.actualVersion)
            return builder.status(HttpStatus.CONFLICT)
                .message("日志设置已被其他管理员更新，请重新加载后再保存")
                .data(rs)
                .build()
        }
        val settings = (result as LoggingSettingsUpdateResult.Updated).settings
        runtimeLoggingManager.apply(settings)
        val runtime = runtimeLoggingManager.snapshot()
        org.slf4j.LoggerFactory.getLogger("top.foxball.shopmall.logging.audit").info(
            "Logging settings updated by administrator {}: root_level={}, logger_overrides={}, version={}",
            adminId,
            settings.rootLevel.name,
            settings.loggerLevels.keys.sorted().joinToString(","),
            settings.version,
        )
        val rs = Response(
            rootLevel = settings.rootLevel.name,
            loggerOverrides = settings.loggerLevels.map { (name, level) -> LoggerOverrideData(name, level.name) },
            outputTemplate = settings.outputTemplate,
            version = settings.version,
            source = settings.source.name,
            updatedAt = settings.updatedAt,
            updatedBy = settings.updatedBy,
            effectiveVersion = runtime.effectiveVersion,
            runtimeStatus = runtime.status.name,
            instanceId = properties.instanceId,
            storagePath = properties.rootPath.toString(),
            timeZone = properties.timeZone,
            maxFileSizeBytes = properties.maxFileSize.toBytes(),
            retentionDays = properties.retentionDays,
            activeFile = runtime.activeFile.relativePath,
            activeFileSizeBytes = runtime.activeFile.sizeBytes,
            lastFileError = runtime.activeFile.lastError ?: runtime.lastError,
        )
        return builder.ok().data(rs).build()
    }

    /** @api 使用带游标长轮询读取本节点实时日志 */
    @GetMapping("/live")
    suspend fun getLiveLogs(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("boot_id", required = false) @Size(max = 64) bootId: String?,
        @RequestParam("after_sequence", required = false) @Min(0) afterSequence: Long?,
        @RequestParam("minimum_level", defaultValue = "TRACE") minimumLevel: String,
        @RequestParam("logger_prefix", required = false) @Size(max = 200) loggerPrefix: String?,
        @RequestParam("query", required = false) @Size(max = 128) query: String?,
        @RequestParam("limit", defaultValue = "200") @Min(1) @Max(500) limit: Int,
        @RequestParam("wait_seconds", defaultValue = "20") @Min(0) @Max(20) waitSeconds: Int,
    ): ResponseEntity<Response> {
        data class EventData(
            val sequence: Long,
            val timestamp: LocalDateTime,
            val level: String,
            val logger: String,
            val thread: String,
            @param:JsonProperty("request_id") val requestId: String?,
            val rendered: String,
            @param:JsonProperty("template_version") val templateVersion: Long,
        )

        data class Response(
            @param:JsonProperty("instance_id") val instanceId: String,
            @param:JsonProperty("boot_id") val bootId: String,
            val reset: Boolean,
            val gap: Boolean,
            @param:JsonProperty("dropped_count") val droppedCount: Long,
            @param:JsonProperty("earliest_sequence") val earliestSequence: Long,
            @param:JsonProperty("next_sequence") val nextSequence: Long,
            val events: List<EventData>,
        )

        val batch = liveLogService.poll(
            adminId,
            LiveLogQuery(
                bootId = bootId,
                afterSequence = afterSequence,
                minimumLevel = LogLevel.parse(minimumLevel),
                loggerPrefix = loggerPrefix?.takeIf { it.isNotBlank() },
                query = query?.takeIf { it.isNotBlank() },
                limit = limit,
                waitSeconds = waitSeconds,
            ),
        )
        val rs = Response(
            instanceId = properties.instanceId,
            bootId = batch.bootId,
            reset = batch.reset,
            gap = batch.gap,
            droppedCount = batch.droppedCount,
            earliestSequence = batch.earliestSequence,
            nextSequence = batch.nextSequence,
            events = batch.events.map { event ->
                EventData(
                    sequence = event.sequence,
                    timestamp = event.timestamp,
                    level = event.level.name,
                    logger = event.logger,
                    thread = event.thread,
                    requestId = event.requestId,
                    rendered = event.rendered,
                    templateVersion = event.templateVersion,
                )
            },
        )
        return builder.ok().header("Cache-Control", requireNotNull(CacheControl.noStore().headerValue)).data(rs).build()
    }

    /** @api 列出可读取日志日期 */
    @GetMapping("/history/dates")
    suspend fun getHistoryDates(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("from_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) fromDate: LocalDate?,
        @RequestParam("to_date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) toDate: LocalDate?,
    ): ResponseEntity<Response> {
        data class DateData(
            val date: LocalDate,
            @param:JsonProperty("file_count") val fileCount: Int,
            @param:JsonProperty("size_bytes") val sizeBytes: Long,
        )

        data class Response(val dates: List<DateData>)

        val dates = historyService.listDates(adminId, fromDate, toDate)
        val rs = Response(dates.map { date -> DateData(date.date, date.fileCount, date.sizeBytes) })
        return builder.ok().header("Cache-Control", requireNotNull(CacheControl.noStore().headerValue)).data(rs).build()
    }

    /** @api 分页列出某日的受控日志文件 */
    @GetMapping("/history/files")
    suspend fun getHistoryFiles(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam("cursor", defaultValue = "0") @Min(0) cursor: Int,
        @RequestParam("limit", defaultValue = "100") @Min(1) @Max(500) limit: Int,
    ): ResponseEntity<Response> {
        data class FileData(
            val date: LocalDate,
            @param:JsonProperty("file_time") val fileTime: LocalDateTime,
            @param:JsonProperty("rotation_index") val rotationIndex: Int,
            val filename: String,
            @param:JsonProperty("size_bytes") val sizeBytes: Long,
            @param:JsonProperty("modified_at") val modifiedAt: LocalDateTime,
            val active: Boolean,
        )

        data class Response(
            val files: List<FileData>,
            @param:JsonProperty("next_cursor") val nextCursor: Int?,
        )

        val page = historyService.listFiles(adminId, date, cursor, limit)
        val rs = Response(
            files = page.files.map { file ->
                FileData(
                    date = file.date,
                    fileTime = file.fileTime,
                    rotationIndex = file.rotationIndex,
                    filename = file.filename,
                    sizeBytes = file.sizeBytes,
                    modifiedAt = file.modifiedAt,
                    active = file.active,
                )
            },
            nextCursor = page.nextCursor,
        )
        return builder.ok().header("Cache-Control", requireNotNull(CacheControl.noStore().headerValue)).data(rs).build()
    }

    /** @api 按 UTF-8 字节游标读取一个受控日志文件 */
    @GetMapping("/history/content")
    suspend fun getHistoryContent(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam("file_time") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) fileTime: LocalDateTime,
        @RequestParam("rotation_index") @Min(0) rotationIndex: Int,
        @RequestParam("after_offset", required = false) @Min(0) afterOffset: Long?,
        @RequestParam("tail", defaultValue = "true") tail: Boolean,
        @RequestParam("query", required = false) @Size(max = 128) query: String?,
        @RequestParam("limit", defaultValue = "200") @Min(1) @Max(500) limit: Int,
    ): ResponseEntity<Response> {
        data class LineData(
            val offset: Long,
            @param:JsonProperty("next_offset") val nextOffset: Long,
            val text: String,
        )

        data class Response(
            val filename: String,
            @param:JsonProperty("file_size_bytes") val fileSizeBytes: Long,
            val active: Boolean,
            val lines: List<LineData>,
            @param:JsonProperty("next_offset") val nextOffset: Long,
            val eof: Boolean,
        )

        val page: HistoryContentPage = historyService.readContent(
            adminId = adminId,
            date = date,
            fileTime = fileTime,
            rotationIndex = rotationIndex,
            afterOffset = afterOffset,
            tail = tail,
            query = query,
            limit = limit,
        )
        val rs = Response(
            filename = page.filename,
            fileSizeBytes = page.fileSizeBytes,
            active = page.active,
            lines = page.lines.map { line -> LineData(line.offset, line.nextOffset, line.text) },
            nextOffset = page.nextOffset,
            eof = page.eof,
        )
        return builder.ok().header("Cache-Control", requireNotNull(CacheControl.noStore().headerValue)).data(rs).build()
    }
}
