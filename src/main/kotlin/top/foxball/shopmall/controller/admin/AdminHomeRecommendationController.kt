package top.foxball.shopmall.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.entity.jdbc.ProductVariant
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.service.AdminHomeRecommendationService
import top.foxball.shopmall.shared.Response
import top.foxball.shopmall.shared.ResponseBuilder
import java.time.LocalDateTime

/** 管理员首页推荐方案编排、预览和发布接口。 */
@Validated
@RestController
@RequestMapping("/admin/api/home-recommendations/plans")
class AdminHomeRecommendationController(
    private val adminHomeRecommendationService: AdminHomeRecommendationService,
    private val objectMapper: ObjectMapper,
    private val builder: ResponseBuilder,
) {
    @GetMapping
    fun listPlans(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("page", defaultValue = "0") @Min(0) page: Int,
        @RequestParam("size", defaultValue = "25") @Min(1) @Max(100) size: Int,
        @RequestParam("keyword", required = false) @Size(max = 120) keyword: String?,
        @RequestParam("status", required = false) status: HomeRecommendationPlan.Status?,
        @RequestParam("sort_by", defaultValue = "UPDATED_AT") sortBy: AdminHomeRecommendationService.SortBy,
        @RequestParam("sort_direction", defaultValue = "DESC") sortDirection: String,
    ): ResponseEntity<Response> {
        data class PlanData(
            val id: Long,
            val version: Long,
            val name: String,
            val status: String,
            val channel: String,
            @param:JsonProperty("section_count") val sectionCount: Int,
            @param:JsonProperty("effective_from") val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until") val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(
            val items: List<PlanData>,
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_elements") val totalElements: Long,
            @param:JsonProperty("total_pages") val totalPages: Int,
        )

        val direction = sortDirection.uppercase()
        if (direction !in setOf("ASC", "DESC")) {
            return builder.badRequest().message("sort_direction 只能为 ASC 或 DESC").build()
        }
        val result = adminHomeRecommendationService.list(
            adminId,
            AdminHomeRecommendationService.Query(
                page = page,
                size = size,
                keyword = keyword,
                status = status,
                sortBy = sortBy,
                ascending = direction == "ASC",
            ),
        )
        val rs = Response(
            items = result.content.map { plan ->
                PlanData(
                    id = requireNotNull(plan.id),
                    version = plan.version,
                    name = plan.name,
                    status = plan.status.name,
                    channel = plan.channel.name,
                    sectionCount = plan.sections.size,
                    effectiveFrom = plan.effectiveFrom,
                    effectiveUntil = plan.effectiveUntil,
                    publishedAt = plan.publishedAt,
                    createdAt = plan.createdAt,
                    updatedAt = plan.updatedAt,
                )
            },
            page = result.number,
            size = result.size,
            totalElements = result.totalElements,
            totalPages = result.totalPages,
        )
        return builder.ok().data(rs).build()
    }

    @GetMapping("/{plan_id}")
    fun getPlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
    ): ResponseEntity<Response> {
        data class ItemData(
            val id: Long,
            @param:JsonProperty("product_id") val productId: Long,
            val pinned: Boolean,
            @param:JsonProperty("custom_badge") val customBadge: String?,
            @param:JsonProperty("sort_order") val sortOrder: Int,
        )
        data class GroupData(
            val id: Long,
            val code: String,
            val title: String?,
            @param:JsonProperty("selection_mode") val selectionMode: String,
            val strategy: String,
            @param:JsonProperty("item_limit") val itemLimit: Int,
            @param:JsonProperty("category_id") val categoryId: Long?,
            @param:JsonProperty("product_type") val productType: String?,
            @param:JsonProperty("tag_id") val tagId: Long?,
            @param:JsonProperty("lookback_days") val lookbackDays: Int?,
            @param:JsonProperty("minimum_stock") val minimumStock: Int,
            @param:JsonProperty("fallback_strategy") val fallbackStrategy: String,
            @param:JsonProperty("sort_order") val sortOrder: Int,
            val items: List<ItemData>,
        )
        data class SectionData(
            val id: Long,
            val code: String,
            val eyebrow: String?,
            val title: String,
            val subtitle: String?,
            @param:JsonProperty("display_style") val displayStyle: String,
            @param:JsonProperty("desktop_columns") val desktopColumns: Int,
            @param:JsonProperty("mobile_columns") val mobileColumns: Int,
            @param:JsonProperty("link_label") val linkLabel: String?,
            @param:JsonProperty("link_url") val linkUrl: String?,
            @param:JsonProperty("item_limit") val itemLimit: Int,
            @param:JsonProperty("hide_when_empty") val hideWhenEmpty: Boolean,
            @param:JsonProperty("sort_order") val sortOrder: Int,
            val groups: List<GroupData>,
        )
        data class Response(
            val id: Long,
            val version: Long,
            val name: String,
            val status: String,
            val channel: String,
            @param:JsonProperty("effective_from") val effectiveFrom: LocalDateTime,
            @param:JsonProperty("effective_until") val effectiveUntil: LocalDateTime?,
            @param:JsonProperty("fallback_enabled") val fallbackEnabled: Boolean,
            @param:JsonProperty("deduplicate_across_sections") val deduplicateAcrossSections: Boolean,
            @param:JsonProperty("created_by") val createdBy: Long,
            @param:JsonProperty("updated_by") val updatedBy: Long,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
            @param:JsonProperty("archived_at") val archivedAt: LocalDateTime?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            val sections: List<SectionData>,
        )

        val plan = adminHomeRecommendationService.get(adminId, planId)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        val rs = Response(
            id = requireNotNull(plan.id),
            version = plan.version,
            name = plan.name,
            status = plan.status.name,
            channel = plan.channel.name,
            effectiveFrom = plan.effectiveFrom,
            effectiveUntil = plan.effectiveUntil,
            fallbackEnabled = plan.fallbackEnabled,
            deduplicateAcrossSections = plan.deduplicateAcrossSections,
            createdBy = plan.createdBy,
            updatedBy = plan.updatedBy,
            publishedAt = plan.publishedAt,
            archivedAt = plan.archivedAt,
            createdAt = plan.createdAt,
            updatedAt = plan.updatedAt,
            sections = plan.sections.sortedWith(compareBy(HomeRecommendationSection::sortOrder, HomeRecommendationSection::id))
                .map { section ->
                    SectionData(
                        id = requireNotNull(section.id),
                        code = section.code,
                        eyebrow = section.eyebrow,
                        title = section.title,
                        subtitle = section.subtitle,
                        displayStyle = section.displayStyle.name,
                        desktopColumns = section.desktopColumns,
                        mobileColumns = section.mobileColumns,
                        linkLabel = section.linkLabel,
                        linkUrl = section.linkUrl,
                        itemLimit = section.itemLimit,
                        hideWhenEmpty = section.hideWhenEmpty,
                        sortOrder = section.sortOrder,
                        groups = section.groups.sortedWith(compareBy(HomeRecommendationGroup::sortOrder, HomeRecommendationGroup::id))
                            .map { group ->
                                GroupData(
                                    id = requireNotNull(group.id),
                                    code = group.code,
                                    title = group.title,
                                    selectionMode = group.selectionMode.name,
                                    strategy = group.strategy.name,
                                    itemLimit = group.itemLimit,
                                    categoryId = group.categoryId,
                                    productType = group.productType,
                                    tagId = group.tagId,
                                    lookbackDays = group.lookbackDays,
                                    minimumStock = group.minimumStock,
                                    fallbackStrategy = group.fallbackStrategy.name,
                                    sortOrder = group.sortOrder,
                                    items = group.items.sortedWith(
                                        compareByDescending<HomeRecommendationItem> { it.pinned }
                                            .thenBy(HomeRecommendationItem::sortOrder)
                                            .thenBy(HomeRecommendationItem::id),
                                    ).map { item ->
                                        ItemData(
                                            id = requireNotNull(item.id),
                                            productId = item.productId,
                                            pinned = item.pinned,
                                            customBadge = item.customBadge,
                                            sortOrder = item.sortOrder,
                                        )
                                    },
                                )
                            },
                    )
                },
        )
        return builder.ok().data(rs).build()
    }

    @PostMapping
    fun createPlan(
        @AuthenticationPrincipal adminId: Long,
        @RequestParam("name") @NotBlank @Size(max = 120) name: String,
        @RequestParam("effective_from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFrom: LocalDateTime,
        @RequestParam("effective_until", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveUntil: LocalDateTime?,
        @RequestParam("fallback_enabled", defaultValue = "true") fallbackEnabled: Boolean,
        @RequestParam("deduplicate_across_sections", defaultValue = "true") deduplicateAcrossSections: Boolean,
        @RequestParam("sections") @NotBlank @Size(max = 100_000) sections: String,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val version: Long, val status: String)

        val sectionsNode = runCatching { objectMapper.readTree(sections) }
            .getOrElse { throw ParamErrorException("sections 必须是有效的 JSON 数组") }
        if (!sectionsNode.isArray) throw ParamErrorException("sections 必须是 JSON 数组")
        val sectionCommands = sectionsNode.mapIndexed { sectionIndex, sectionNode ->
            if (!sectionNode.isObject) throw ParamErrorException("第 ${sectionIndex + 1} 个楼层必须是 JSON 对象")
            val groupsNode = sectionNode.get("groups")
            if (groupsNode == null || !groupsNode.isArray) {
                throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的 groups 必须是 JSON 数组")
            }
            AdminHomeRecommendationService.SectionCommand(
                code = sectionNode.get("code")?.asString() ?: "",
                eyebrow = sectionNode.get("eyebrow")?.takeUnless { it.isNull }?.asString(),
                title = sectionNode.get("title")?.asString() ?: "",
                subtitle = sectionNode.get("subtitle")?.takeUnless { it.isNull }?.asString(),
                displayStyle = runCatching {
                    HomeRecommendationSection.DisplayStyle.valueOf(sectionNode.get("display_style")?.asString()?.uppercase() ?: "")
                }.getOrElse { throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的 display_style 无效") },
                desktopColumns = sectionNode.get("desktop_columns")?.asString()?.toIntOrNull() ?: 4,
                mobileColumns = sectionNode.get("mobile_columns")?.asString()?.toIntOrNull() ?: 2,
                linkLabel = sectionNode.get("link_label")?.takeUnless { it.isNull }?.asString(),
                linkUrl = sectionNode.get("link_url")?.takeUnless { it.isNull }?.asString(),
                itemLimit = sectionNode.get("item_limit")?.asString()?.toIntOrNull() ?: 8,
                hideWhenEmpty = sectionNode.get("hide_when_empty")?.asString()?.toBooleanStrictOrNull() ?: true,
                sortOrder = sectionNode.get("sort_order")?.asString()?.toIntOrNull() ?: sectionIndex,
                groups = groupsNode.mapIndexed { groupIndex, groupNode ->
                    if (!groupNode.isObject) {
                        throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的第 ${groupIndex + 1} 个商品组必须是 JSON 对象")
                    }
                    val itemsNode = groupNode.get("items")
                    if (itemsNode != null && !itemsNode.isNull && !itemsNode.isArray) {
                        throw ParamErrorException("商品组 items 必须是 JSON 数组")
                    }
                    AdminHomeRecommendationService.GroupCommand(
                        code = groupNode.get("code")?.asString() ?: "",
                        title = groupNode.get("title")?.takeUnless { it.isNull }?.asString(),
                        selectionMode = runCatching {
                            HomeRecommendationGroup.SelectionMode.valueOf(groupNode.get("selection_mode")?.asString()?.uppercase() ?: "")
                        }.getOrElse { throw ParamErrorException("商品组 selection_mode 无效") },
                        strategy = runCatching {
                            HomeRecommendationGroup.Strategy.valueOf(groupNode.get("strategy")?.asString()?.uppercase() ?: "")
                        }.getOrElse { throw ParamErrorException("商品组 strategy 无效") },
                        itemLimit = groupNode.get("item_limit")?.asString()?.toIntOrNull() ?: 8,
                        categoryId = groupNode.get("category_id")?.takeUnless { it.isNull }?.asString()?.toLongOrNull(),
                        productType = groupNode.get("product_type")?.takeUnless { it.isNull }?.asString(),
                        tagId = groupNode.get("tag_id")?.takeUnless { it.isNull }?.asString()?.toLongOrNull(),
                        lookbackDays = groupNode.get("lookback_days")?.takeUnless { it.isNull }?.asString()?.toIntOrNull(),
                        minimumStock = groupNode.get("minimum_stock")?.asString()?.toIntOrNull() ?: 1,
                        fallbackStrategy = runCatching {
                            HomeRecommendationGroup.FallbackStrategy.valueOf(
                                groupNode.get("fallback_strategy")?.asString()?.uppercase() ?: "LATEST",
                            )
                        }.getOrElse { throw ParamErrorException("商品组 fallback_strategy 无效") },
                        sortOrder = groupNode.get("sort_order")?.asString()?.toIntOrNull() ?: groupIndex,
                        items = if (itemsNode == null || itemsNode.isNull) emptyList() else itemsNode.mapIndexed { itemIndex, itemNode ->
                            if (!itemNode.isObject) throw ParamErrorException("人工商品项必须是 JSON 对象")
                            AdminHomeRecommendationService.ItemCommand(
                                productId = itemNode.get("product_id")?.asString()?.toLongOrNull() ?: 0,
                                pinned = itemNode.get("pinned")?.asString()?.toBooleanStrictOrNull() ?: false,
                                customBadge = itemNode.get("custom_badge")?.takeUnless { it.isNull }?.asString(),
                                sortOrder = itemNode.get("sort_order")?.asString()?.toIntOrNull() ?: itemIndex,
                            )
                        },
                    )
                },
            )
        }
        val plan = adminHomeRecommendationService.create(
            adminId,
            AdminHomeRecommendationService.CreateCommand(
                name = name,
                effectiveFrom = effectiveFrom,
                effectiveUntil = effectiveUntil,
                fallbackEnabled = fallbackEnabled,
                deduplicateAcrossSections = deduplicateAcrossSections,
                sections = sectionCommands,
            ),
        )
        return builder.created().data(Response(requireNotNull(plan.id), plan.version, plan.status.name)).build()
    }

    @PutMapping("/{plan_id}")
    fun updatePlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("name") @NotBlank @Size(max = 120) name: String,
        @RequestParam("effective_from")
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveFrom: LocalDateTime,
        @RequestParam("effective_until", required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        effectiveUntil: LocalDateTime?,
        @RequestParam("fallback_enabled", defaultValue = "true") fallbackEnabled: Boolean,
        @RequestParam("deduplicate_across_sections", defaultValue = "true") deduplicateAcrossSections: Boolean,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
        @RequestParam("sections") @NotBlank @Size(max = 100_000) sections: String,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val version: Long, val status: String)

        val sectionsNode = runCatching { objectMapper.readTree(sections) }
            .getOrElse { throw ParamErrorException("sections 必须是有效的 JSON 数组") }
        if (!sectionsNode.isArray) throw ParamErrorException("sections 必须是 JSON 数组")
        val sectionCommands = sectionsNode.mapIndexed { sectionIndex, sectionNode ->
            if (!sectionNode.isObject) throw ParamErrorException("第 ${sectionIndex + 1} 个楼层必须是 JSON 对象")
            val groupsNode = sectionNode.get("groups")
            if (groupsNode == null || !groupsNode.isArray) {
                throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的 groups 必须是 JSON 数组")
            }
            AdminHomeRecommendationService.SectionCommand(
                code = sectionNode.get("code")?.asString() ?: "",
                eyebrow = sectionNode.get("eyebrow")?.takeUnless { it.isNull }?.asString(),
                title = sectionNode.get("title")?.asString() ?: "",
                subtitle = sectionNode.get("subtitle")?.takeUnless { it.isNull }?.asString(),
                displayStyle = runCatching {
                    HomeRecommendationSection.DisplayStyle.valueOf(sectionNode.get("display_style")?.asString()?.uppercase() ?: "")
                }.getOrElse { throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的 display_style 无效") },
                desktopColumns = sectionNode.get("desktop_columns")?.asString()?.toIntOrNull() ?: 4,
                mobileColumns = sectionNode.get("mobile_columns")?.asString()?.toIntOrNull() ?: 2,
                linkLabel = sectionNode.get("link_label")?.takeUnless { it.isNull }?.asString(),
                linkUrl = sectionNode.get("link_url")?.takeUnless { it.isNull }?.asString(),
                itemLimit = sectionNode.get("item_limit")?.asString()?.toIntOrNull() ?: 8,
                hideWhenEmpty = sectionNode.get("hide_when_empty")?.asString()?.toBooleanStrictOrNull() ?: true,
                sortOrder = sectionNode.get("sort_order")?.asString()?.toIntOrNull() ?: sectionIndex,
                groups = groupsNode.mapIndexed { groupIndex, groupNode ->
                    if (!groupNode.isObject) {
                        throw ParamErrorException("第 ${sectionIndex + 1} 个楼层的第 ${groupIndex + 1} 个商品组必须是 JSON 对象")
                    }
                    val itemsNode = groupNode.get("items")
                    if (itemsNode != null && !itemsNode.isNull && !itemsNode.isArray) {
                        throw ParamErrorException("商品组 items 必须是 JSON 数组")
                    }
                    AdminHomeRecommendationService.GroupCommand(
                        code = groupNode.get("code")?.asString() ?: "",
                        title = groupNode.get("title")?.takeUnless { it.isNull }?.asString(),
                        selectionMode = runCatching {
                            HomeRecommendationGroup.SelectionMode.valueOf(groupNode.get("selection_mode")?.asString()?.uppercase() ?: "")
                        }.getOrElse { throw ParamErrorException("商品组 selection_mode 无效") },
                        strategy = runCatching {
                            HomeRecommendationGroup.Strategy.valueOf(groupNode.get("strategy")?.asString()?.uppercase() ?: "")
                        }.getOrElse { throw ParamErrorException("商品组 strategy 无效") },
                        itemLimit = groupNode.get("item_limit")?.asString()?.toIntOrNull() ?: 8,
                        categoryId = groupNode.get("category_id")?.takeUnless { it.isNull }?.asString()?.toLongOrNull(),
                        productType = groupNode.get("product_type")?.takeUnless { it.isNull }?.asString(),
                        tagId = groupNode.get("tag_id")?.takeUnless { it.isNull }?.asString()?.toLongOrNull(),
                        lookbackDays = groupNode.get("lookback_days")?.takeUnless { it.isNull }?.asString()?.toIntOrNull(),
                        minimumStock = groupNode.get("minimum_stock")?.asString()?.toIntOrNull() ?: 1,
                        fallbackStrategy = runCatching {
                            HomeRecommendationGroup.FallbackStrategy.valueOf(
                                groupNode.get("fallback_strategy")?.asString()?.uppercase() ?: "LATEST",
                            )
                        }.getOrElse { throw ParamErrorException("商品组 fallback_strategy 无效") },
                        sortOrder = groupNode.get("sort_order")?.asString()?.toIntOrNull() ?: groupIndex,
                        items = if (itemsNode == null || itemsNode.isNull) emptyList() else itemsNode.mapIndexed { itemIndex, itemNode ->
                            if (!itemNode.isObject) throw ParamErrorException("人工商品项必须是 JSON 对象")
                            AdminHomeRecommendationService.ItemCommand(
                                productId = itemNode.get("product_id")?.asString()?.toLongOrNull() ?: 0,
                                pinned = itemNode.get("pinned")?.asString()?.toBooleanStrictOrNull() ?: false,
                                customBadge = itemNode.get("custom_badge")?.takeUnless { it.isNull }?.asString(),
                                sortOrder = itemNode.get("sort_order")?.asString()?.toIntOrNull() ?: itemIndex,
                            )
                        },
                    )
                },
            )
        }
        val plan = adminHomeRecommendationService.update(
            adminId,
            planId,
            AdminHomeRecommendationService.UpdateCommand(
                name = name,
                effectiveFrom = effectiveFrom,
                effectiveUntil = effectiveUntil,
                fallbackEnabled = fallbackEnabled,
                deduplicateAcrossSections = deduplicateAcrossSections,
                sections = sectionCommands,
                expectedVersion = expectedVersion,
            ),
        ) ?: return builder.notFound().message("首页推荐方案不存在").build()
        return builder.ok().data(Response(requireNotNull(plan.id), plan.version, plan.status.name)).build()
    }

    @PostMapping("/{plan_id}/copy")
    fun copyPlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val version: Long, val name: String, val status: String)
        val plan = adminHomeRecommendationService.copy(adminId, planId, expectedVersion)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        return builder.created().data(Response(requireNotNull(plan.id), plan.version, plan.name, plan.status.name)).build()
    }

    @PostMapping("/{plan_id}/publish")
    fun publishPlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val status: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val plan = adminHomeRecommendationService.publish(adminId, planId, expectedVersion)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        return builder.ok().data(Response(requireNotNull(plan.id), plan.version, plan.status.name, plan.publishedAt)).build()
    }

    @PostMapping("/{plan_id}/offline")
    fun offlinePlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val version: Long, val status: String)
        val plan = adminHomeRecommendationService.offline(adminId, planId, expectedVersion)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        return builder.ok().data(Response(requireNotNull(plan.id), plan.version, plan.status.name)).build()
    }

    @PostMapping("/{plan_id}/archive")
    fun archivePlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("expected_version") @Min(0) expectedVersion: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val version: Long,
            val status: String,
            @param:JsonProperty("archived_at") val archivedAt: LocalDateTime?,
        )
        val plan = adminHomeRecommendationService.archive(adminId, planId, expectedVersion)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        return builder.ok().data(Response(requireNotNull(plan.id), plan.version, plan.status.name, plan.archivedAt)).build()
    }

    @GetMapping("/{plan_id}/preview")
    fun previewPlan(
        @AuthenticationPrincipal adminId: Long,
        @PathVariable("plan_id") @Min(1) planId: Long,
        @RequestParam("product_limit_per_group", required = false) @Min(1) @Max(24) productLimitPerGroup: Int?,
    ): ResponseEntity<Response> {
        data class ProductData(
            val id: Long,
            val name: String,
            @param:JsonProperty("image_url") val imageUrl: String?,
            val price: String?,
            val badge: String?,
            val position: Int,
        )
        data class GroupData(val code: String, val title: String?, val strategy: String, val products: List<ProductData>)
        data class SectionData(
            val code: String,
            val eyebrow: String?,
            val title: String,
            val subtitle: String?,
            @param:JsonProperty("display_style") val displayStyle: String,
            val groups: List<GroupData>,
        )
        data class Response(
            @param:JsonProperty("plan_id") val planId: Long?,
            @param:JsonProperty("request_id") val requestId: String,
            @param:JsonProperty("generated_at") val generatedAt: LocalDateTime,
            val sections: List<SectionData>,
        )

        val preview = adminHomeRecommendationService.preview(adminId, planId, productLimitPerGroup)
            ?: return builder.notFound().message("首页推荐方案不存在").build()
        val rs = Response(
            planId = preview.planId,
            requestId = preview.requestId,
            generatedAt = preview.generatedAt,
            sections = preview.sections.map { section ->
                SectionData(
                    code = section.code,
                    eyebrow = section.eyebrow,
                    title = section.title,
                    subtitle = section.subtitle,
                    displayStyle = section.displayStyle.name,
                    groups = section.groups.map { group ->
                        GroupData(
                            code = group.code,
                            title = group.title,
                            strategy = group.strategy.name,
                            products = group.products.map { item ->
                                ProductData(
                                    id = requireNotNull(item.product.id),
                                    name = item.product.name,
                                    imageUrl = item.product.images.firstOrNull()?.url,
                                    price = item.product.variants
                                        .filter {
                                            it.status == ProductVariant.Status.ACTIVE &&
                                                it.warehouseVolume >= group.minimumStock && it.price.signum() > 0
                                        }
                                        .minOfOrNull { it.price }
                                        ?.toPlainString(),
                                    badge = item.badge,
                                    position = item.context.position,
                                )
                            },
                        )
                    },
                )
            },
        )
        return builder.ok().header("Cache-Control", "no-store").data(rs).build()
    }
}
