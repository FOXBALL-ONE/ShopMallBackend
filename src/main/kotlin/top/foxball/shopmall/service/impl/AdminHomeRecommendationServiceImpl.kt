package top.foxball.shopmall.service.impl

import jakarta.persistence.criteria.Predicate
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.shopmall.entity.jdbc.HomeRecommendationGroup
import top.foxball.shopmall.entity.jdbc.HomeRecommendationItem
import top.foxball.shopmall.entity.jdbc.HomeRecommendationPlan
import top.foxball.shopmall.entity.jdbc.HomeRecommendationSection
import top.foxball.shopmall.handler.HomeRecommendationScheduleConflictException
import top.foxball.shopmall.handler.HomeRecommendationVersionConflictException
import top.foxball.shopmall.handler.ParamErrorException
import top.foxball.shopmall.repository.HomeRecommendationPlanRepository
import top.foxball.shopmall.repository.ProductCategoryRepository
import top.foxball.shopmall.repository.ProductRepository
import top.foxball.shopmall.repository.ProductTypeRepository
import top.foxball.shopmall.repository.TagRepository
import top.foxball.shopmall.service.AdminAccessService
import top.foxball.shopmall.service.AdminHomeRecommendationService
import top.foxball.shopmall.service.HomeRecommendationCache
import top.foxball.shopmall.service.HomeRecommendationService
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId

@Service
class AdminHomeRecommendationServiceImpl(
    private val planRepository: HomeRecommendationPlanRepository,
    private val productRepository: ProductRepository,
    private val productCategoryRepository: ProductCategoryRepository,
    private val productTypeRepository: ProductTypeRepository,
    private val tagRepository: TagRepository,
    private val adminAccessService: AdminAccessService,
    private val homeRecommendationService: HomeRecommendationService,
    private val homeRecommendationCache: HomeRecommendationCache,
    private val clock: Clock,
    @Value("\${shopmall.home-recommendation.time-zone:Asia/Shanghai}") recommendationTimeZone: String,
) : AdminHomeRecommendationService {
    private val recommendationZoneId = ZoneId.of(recommendationTimeZone)

    @Transactional
    override fun list(
        adminId: Long,
        query: AdminHomeRecommendationService.Query,
    ): Page<HomeRecommendationPlan> {
        adminAccessService.requireAdmin(adminId)
        synchronizeLifecycle()
        val keyword = query.keyword?.trim()?.takeIf(String::isNotEmpty)
        val specification = Specification<HomeRecommendationPlan> { root, _, criteriaBuilder ->
            val predicates = mutableListOf<Predicate>()
            keyword?.let { value ->
                val escaped = value.lowercase()
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
                predicates += criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("name")),
                    "%$escaped%",
                    '\\',
                )
            }
            query.status?.let { status ->
                predicates += criteriaBuilder.equal(root.get<HomeRecommendationPlan.Status>("status"), status)
            }
            predicates += criteriaBuilder.equal(
                root.get<HomeRecommendationPlan.Channel>("channel"),
                HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            )
            criteriaBuilder.and(*predicates.toTypedArray())
        }
        val direction = if (query.ascending) Sort.Direction.ASC else Sort.Direction.DESC
        var sort = Sort.by(direction, query.sortBy.property)
        if (query.sortBy.property != "id") sort = sort.and(Sort.by(Sort.Direction.DESC, "id"))
        val result = planRepository.findAll(
            specification,
            PageRequest.of(query.page.coerceAtLeast(0), query.size.coerceIn(1, 100), sort),
        )
        result.content.forEach(::initializePlan)
        return result
    }

    @Transactional
    override fun get(adminId: Long, planId: Long): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        synchronizeLifecycle()
        return planRepository.findById(planId).orElse(null)?.also(::initializePlan)
    }

    @Transactional
    override fun create(
        adminId: Long,
        command: AdminHomeRecommendationService.CreateCommand,
    ): HomeRecommendationPlan {
        adminAccessService.requireAdmin(adminId)
        validateCommand(command)
        val plan = HomeRecommendationPlan(
            name = command.name.trim(),
            status = HomeRecommendationPlan.Status.DRAFT,
            channel = HomeRecommendationPlan.Channel.CUSTOMER_WEB,
            effectiveFrom = command.effectiveFrom,
            effectiveUntil = command.effectiveUntil,
            fallbackEnabled = command.fallbackEnabled,
            deduplicateAcrossSections = command.deduplicateAcrossSections,
            createdBy = adminId,
            updatedBy = adminId,
        )
        plan.replaceSections(buildSections(command.sections))
        return planRepository.saveAndFlush(plan).also(::initializePlan)
    }

    @Transactional
    override fun update(
        adminId: Long,
        planId: Long,
        command: AdminHomeRecommendationService.UpdateCommand,
    ): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        val plan = planRepository.findByIdForUpdate(planId) ?: return null
        requireExpectedVersion(plan, command.expectedVersion)
        if (plan.status !in setOf(HomeRecommendationPlan.Status.DRAFT, HomeRecommendationPlan.Status.OFFLINE)) {
            throw ParamErrorException("只有草稿或已下线方案可以编辑；已排期、已发布或已归档方案请先复制")
        }
        validateCommand(
            AdminHomeRecommendationService.CreateCommand(
                name = command.name,
                effectiveFrom = command.effectiveFrom,
                effectiveUntil = command.effectiveUntil,
                fallbackEnabled = command.fallbackEnabled,
                deduplicateAcrossSections = command.deduplicateAcrossSections,
                sections = command.sections,
            ),
        )
        plan.name = command.name.trim()
        plan.effectiveFrom = command.effectiveFrom
        plan.effectiveUntil = command.effectiveUntil
        plan.fallbackEnabled = command.fallbackEnabled
        plan.deduplicateAcrossSections = command.deduplicateAcrossSections
        plan.updatedBy = adminId
        plan.replaceSections(buildSections(command.sections))
        return planRepository.saveAndFlush(plan).also(::initializePlan)
    }

    @Transactional
    override fun copy(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        val source = planRepository.findByIdForUpdate(planId) ?: return null
        requireExpectedVersion(source, expectedVersion)
        initializePlan(source)
        val clone = HomeRecommendationPlan(
            name = "${source.name}（副本）".take(120),
            status = HomeRecommendationPlan.Status.DRAFT,
            channel = source.channel,
            effectiveFrom = now(),
            effectiveUntil = null,
            fallbackEnabled = source.fallbackEnabled,
            deduplicateAcrossSections = source.deduplicateAcrossSections,
            createdBy = adminId,
            updatedBy = adminId,
        )
        clone.replaceSections(
            source.sections.sortedWith(compareBy(HomeRecommendationSection::sortOrder, HomeRecommendationSection::id))
                .map { section ->
                    HomeRecommendationSection(
                        code = section.code,
                        eyebrow = section.eyebrow,
                        title = section.title,
                        subtitle = section.subtitle,
                        displayStyle = section.displayStyle,
                        desktopColumns = section.desktopColumns,
                        mobileColumns = section.mobileColumns,
                        linkLabel = section.linkLabel,
                        linkUrl = section.linkUrl,
                        itemLimit = section.itemLimit,
                        hideWhenEmpty = section.hideWhenEmpty,
                        sortOrder = section.sortOrder,
                    ).also { copiedSection ->
                        copiedSection.replaceGroups(
                            section.groups.sortedWith(compareBy(HomeRecommendationGroup::sortOrder, HomeRecommendationGroup::id))
                                .map { group ->
                                    HomeRecommendationGroup(
                                        code = group.code,
                                        title = group.title,
                                        selectionMode = group.selectionMode,
                                        strategy = group.strategy,
                                        itemLimit = group.itemLimit,
                                        categoryId = group.categoryId,
                                        productType = group.productType,
                                        tagId = group.tagId,
                                        lookbackDays = group.lookbackDays,
                                        minimumStock = group.minimumStock,
                                        fallbackStrategy = group.fallbackStrategy,
                                        sortOrder = group.sortOrder,
                                    ).also { copiedGroup ->
                                        copiedGroup.replaceItems(
                                            group.items.sortedWith(
                                                compareByDescending<HomeRecommendationItem> { it.pinned }
                                                    .thenBy(HomeRecommendationItem::sortOrder)
                                                    .thenBy(HomeRecommendationItem::id),
                                            ).map { item ->
                                                HomeRecommendationItem(
                                                    productId = item.productId,
                                                    pinned = item.pinned,
                                                    customBadge = item.customBadge,
                                                    sortOrder = item.sortOrder,
                                                )
                                            },
                                        )
                                    }
                                },
                        )
                    }
                },
        )
        return planRepository.saveAndFlush(clone).also(::initializePlan)
    }

    @Transactional
    override fun publish(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        val plan = planRepository.findByIdForUpdate(planId) ?: return null
        requireExpectedVersion(plan, expectedVersion)
        if (plan.status !in setOf(HomeRecommendationPlan.Status.DRAFT, HomeRecommendationPlan.Status.OFFLINE)) {
            throw ParamErrorException("只有草稿或已下线方案可以发布")
        }
        initializePlan(plan)
        validatePlan(plan)
        val currentTime = now()
        if (plan.effectiveUntil != null && plan.effectiveUntil!! <= currentTime) {
            throw ParamErrorException("方案失效时间已到，不能发布")
        }
        val activePlans = planRepository.findActiveForUpdate(
            plan.channel,
            listOf(HomeRecommendationPlan.Status.PUBLISHED, HomeRecommendationPlan.Status.SCHEDULED),
        ).filter { it.id != plan.id }
        val overlappingPlan = activePlans.firstOrNull { candidate ->
            intervalsOverlap(plan, candidate) &&
                !(plan.effectiveFrom <= currentTime && candidate.status == HomeRecommendationPlan.Status.PUBLISHED)
        }
        if (overlappingPlan != null) {
            throw HomeRecommendationScheduleConflictException(
                "方案生效区间与${if (overlappingPlan.status == HomeRecommendationPlan.Status.SCHEDULED) "已排期" else "已发布"}方案 #${overlappingPlan.id} 重叠",
            )
        }
        if (plan.effectiveFrom <= currentTime) {
            activePlans.filter { it.status == HomeRecommendationPlan.Status.PUBLISHED }.forEach { activePlan ->
                activePlan.status = HomeRecommendationPlan.Status.OFFLINE
                activePlan.updatedBy = adminId
            }
            plan.status = HomeRecommendationPlan.Status.PUBLISHED
            plan.publishedAt = currentTime
        } else {
            plan.status = HomeRecommendationPlan.Status.SCHEDULED
            plan.publishedAt = null
        }
        plan.archivedAt = null
        plan.updatedBy = adminId
        return planRepository.saveAndFlush(plan).also {
            homeRecommendationCache.invalidateAfterCommit()
            initializePlan(it)
        }
    }

    @Transactional
    override fun offline(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        val plan = planRepository.findByIdForUpdate(planId) ?: return null
        requireExpectedVersion(plan, expectedVersion)
        if (plan.status !in setOf(HomeRecommendationPlan.Status.SCHEDULED, HomeRecommendationPlan.Status.PUBLISHED)) {
            throw ParamErrorException("只有已排期或已发布方案可以下线")
        }
        plan.status = HomeRecommendationPlan.Status.OFFLINE
        plan.updatedBy = adminId
        return planRepository.saveAndFlush(plan).also {
            homeRecommendationCache.invalidateAfterCommit()
            initializePlan(it)
        }
    }

    @Transactional
    override fun archive(adminId: Long, planId: Long, expectedVersion: Long): HomeRecommendationPlan? {
        adminAccessService.requireAdmin(adminId)
        val plan = planRepository.findByIdForUpdate(planId) ?: return null
        requireExpectedVersion(plan, expectedVersion)
        if (plan.status == HomeRecommendationPlan.Status.ARCHIVED) {
            throw ParamErrorException("方案已经归档")
        }
        if (plan.status in setOf(HomeRecommendationPlan.Status.SCHEDULED, HomeRecommendationPlan.Status.PUBLISHED)) {
            throw ParamErrorException("已排期或已发布方案需先下线再归档")
        }
        plan.status = HomeRecommendationPlan.Status.ARCHIVED
        plan.archivedAt = now()
        plan.updatedBy = adminId
        return planRepository.saveAndFlush(plan).also(::initializePlan)
    }

    @Transactional(readOnly = true)
    override fun preview(
        adminId: Long,
        planId: Long,
        productLimitPerGroup: Int?,
    ): HomeRecommendationService.ResolvedPlan? {
        adminAccessService.requireAdmin(adminId)
        val plan = planRepository.findById(planId).orElse(null) ?: return null
        initializePlan(plan)
        return homeRecommendationService.resolve(plan, productLimitPerGroup = productLimitPerGroup)
    }

    @Transactional
    override fun synchronizeLifecycle(): Int {
        val currentTime = now()
        val expired = planRepository.findDueForExpiration(
            listOf(HomeRecommendationPlan.Status.SCHEDULED, HomeRecommendationPlan.Status.PUBLISHED),
            currentTime,
        )
        expired.forEach { plan ->
            plan.status = HomeRecommendationPlan.Status.EXPIRED
            plan.updatedBy = SYSTEM_OPERATOR_ID
        }
        if (expired.isNotEmpty()) planRepository.flush()
        val due = planRepository.findDueForPublication(HomeRecommendationPlan.Status.SCHEDULED, currentTime)
        due.groupBy(HomeRecommendationPlan::channel).forEach { (channel, channelPlans) ->
            val winner = channelPlans.maxWithOrNull(
                compareBy<HomeRecommendationPlan> { it.effectiveFrom }.thenBy { it.id },
            ) ?: return@forEach
            planRepository.findActiveForUpdate(
                channel,
                listOf(HomeRecommendationPlan.Status.PUBLISHED),
            ).filter { activePlan -> activePlan.id != winner.id }.forEach { activePlan ->
                activePlan.status = HomeRecommendationPlan.Status.OFFLINE
                activePlan.updatedBy = SYSTEM_OPERATOR_ID
            }
            channelPlans.filter { it.id != winner.id }.forEach { supersededPlan ->
                supersededPlan.status = HomeRecommendationPlan.Status.OFFLINE
                supersededPlan.updatedBy = SYSTEM_OPERATOR_ID
            }
            winner.status = HomeRecommendationPlan.Status.PUBLISHED
            winner.publishedAt = currentTime
            winner.updatedBy = SYSTEM_OPERATOR_ID
        }
        if (expired.isNotEmpty() || due.isNotEmpty()) {
            planRepository.flush()
            homeRecommendationCache.invalidateAfterCommit()
        }
        return expired.size + due.size
    }

    private fun buildSections(commands: List<AdminHomeRecommendationService.SectionCommand>): List<HomeRecommendationSection> =
        commands.map { sectionCommand ->
            HomeRecommendationSection(
                code = sectionCommand.code.trim().lowercase(),
                eyebrow = sectionCommand.eyebrow?.trim()?.takeIf(String::isNotEmpty),
                title = sectionCommand.title.trim(),
                subtitle = sectionCommand.subtitle?.trim()?.takeIf(String::isNotEmpty),
                displayStyle = sectionCommand.displayStyle,
                desktopColumns = sectionCommand.desktopColumns,
                mobileColumns = sectionCommand.mobileColumns,
                linkLabel = sectionCommand.linkLabel?.trim()?.takeIf(String::isNotEmpty),
                linkUrl = normalizedLinkUrl(sectionCommand.linkUrl),
                itemLimit = sectionCommand.itemLimit,
                hideWhenEmpty = sectionCommand.hideWhenEmpty,
                sortOrder = sectionCommand.sortOrder,
            ).also { section ->
                section.replaceGroups(
                    sectionCommand.groups.map { groupCommand ->
                        HomeRecommendationGroup(
                            code = groupCommand.code.trim().lowercase(),
                            title = groupCommand.title?.trim()?.takeIf(String::isNotEmpty),
                            selectionMode = groupCommand.selectionMode,
                            strategy = groupCommand.strategy,
                            itemLimit = groupCommand.itemLimit,
                            categoryId = groupCommand.categoryId,
                            productType = groupCommand.productType?.trim()?.uppercase()?.takeIf(String::isNotEmpty),
                            tagId = groupCommand.tagId,
                            lookbackDays = groupCommand.lookbackDays,
                            minimumStock = groupCommand.minimumStock,
                            fallbackStrategy = groupCommand.fallbackStrategy,
                            sortOrder = groupCommand.sortOrder,
                        ).also { group ->
                            group.replaceItems(
                                groupCommand.items.map { itemCommand ->
                                    HomeRecommendationItem(
                                        productId = itemCommand.productId,
                                        pinned = itemCommand.pinned,
                                        customBadge = itemCommand.customBadge?.trim()?.takeIf(String::isNotEmpty),
                                        sortOrder = itemCommand.sortOrder,
                                    )
                                },
                            )
                        }
                    },
                )
            }
        }

    private fun validateCommand(command: AdminHomeRecommendationService.CreateCommand) {
        if (command.name.trim().isEmpty()) throw ParamErrorException("方案名称不能为空")
        if (command.name.trim().length > 120) throw ParamErrorException("方案名称不能超过 120 个字符")
        if (command.effectiveUntil != null && command.effectiveUntil <= command.effectiveFrom) {
            throw ParamErrorException("失效时间必须晚于生效时间")
        }
        if (command.sections.isEmpty() || command.sections.size > 10) {
            throw ParamErrorException("方案必须包含 1 到 10 个推荐楼层")
        }
        val sectionCodes = mutableSetOf<String>()
        val referencedProductIds = mutableSetOf<Long>()
        val referencedCategoryIds = mutableSetOf<Long>()
        val referencedProductTypes = mutableSetOf<String>()
        val referencedTagIds = mutableSetOf<Long>()
        command.sections.forEachIndexed { sectionIndex, section ->
            val location = "第 ${sectionIndex + 1} 个楼层"
            val code = section.code.trim().lowercase()
            if (!CODE_PATTERN.matches(code)) throw ParamErrorException("$location 的 code 格式无效")
            if (!sectionCodes.add(code)) throw ParamErrorException("楼层 code 不能重复：$code")
            if (code == HomeRecommendationSection.HERO_CAROUSEL_CODE) {
                if (sectionIndex != 0 || section.sortOrder != 0) {
                    throw ParamErrorException("首页顶部轮播必须位于第一个楼层且排序值为 0")
                }
                if (section.displayStyle != HomeRecommendationSection.DisplayStyle.CAROUSEL) {
                    throw ParamErrorException("首页顶部轮播的展示形态必须为 CAROUSEL")
                }
                if (section.itemLimit !in 1..HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS) {
                    throw ParamErrorException(
                        "首页顶部轮播的商品数必须为 1 到 ${HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS}",
                    )
                }
            }
            if (section.title.trim().isEmpty() || section.title.trim().length > 120) {
                throw ParamErrorException("$location 的标题不能为空且不能超过 120 个字符")
            }
            if ((section.eyebrow?.trim()?.length ?: 0) > 80 || (section.subtitle?.trim()?.length ?: 0) > 255) {
                throw ParamErrorException("$location 的辅助文案过长")
            }
            if (section.desktopColumns !in 2..6 || section.mobileColumns !in 1..2) {
                throw ParamErrorException("$location 的桌面列数必须为 2 到 6，移动列数必须为 1 到 2")
            }
            if (section.itemLimit !in 1..24 || section.sortOrder < 0) {
                throw ParamErrorException("$location 的商品数必须为 1 到 24 且排序值不能小于 0")
            }
            if ((section.linkLabel?.trim()?.length ?: 0) > 40) throw ParamErrorException("$location 的链接文字不能超过 40 个字符")
            normalizedLinkUrl(section.linkUrl)
            if (section.groups.isEmpty() || section.groups.size > 8) {
                throw ParamErrorException("$location 必须包含 1 到 8 个商品组")
            }
            if (section.displayStyle == HomeRecommendationSection.DisplayStyle.TABS && section.groups.size < 2) {
                throw ParamErrorException("$location 使用 TABS 时至少需要两个商品组")
            }
            if (section.displayStyle != HomeRecommendationSection.DisplayStyle.TABS && section.groups.size != 1) {
                throw ParamErrorException("$location 使用 GRID/CAROUSEL 时只能配置一个商品组")
            }
            val groupCodes = mutableSetOf<String>()
            section.groups.forEachIndexed { groupIndex, group ->
                val groupLocation = "${location}的第 ${groupIndex + 1} 个商品组"
                val groupCode = group.code.trim().lowercase()
                if (!CODE_PATTERN.matches(groupCode)) throw ParamErrorException("$groupLocation 的 code 格式无效")
                if (!groupCodes.add(groupCode)) throw ParamErrorException("同一楼层的商品组 code 不能重复：$groupCode")
                if ((group.title?.trim()?.length ?: 0) > 80) throw ParamErrorException("$groupLocation 的标题不能超过 80 个字符")
                if (section.displayStyle == HomeRecommendationSection.DisplayStyle.TABS && group.title.isNullOrBlank()) {
                    throw ParamErrorException("TABS 楼层的每个商品组都必须填写页签标题")
                }
                if (group.itemLimit !in 1..24 || group.minimumStock < 1 || group.sortOrder < 0) {
                    throw ParamErrorException("$groupLocation 的商品数、最低库存或排序值无效")
                }
                if (code == HomeRecommendationSection.HERO_CAROUSEL_CODE) {
                    if (group.selectionMode != HomeRecommendationGroup.SelectionMode.MANUAL) {
                        throw ParamErrorException("首页顶部轮播必须使用人工选品")
                    }
                    if (group.strategy != HomeRecommendationGroup.Strategy.EDITOR_PICKS) {
                        throw ParamErrorException("首页顶部轮播的推荐策略必须为 EDITOR_PICKS")
                    }
                    if (group.fallbackStrategy != HomeRecommendationGroup.FallbackStrategy.NONE) {
                        throw ParamErrorException("首页顶部轮播不能配置自动兜底商品")
                    }
                    if (group.itemLimit !in 1..HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS ||
                        group.items.size > HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS
                    ) {
                        throw ParamErrorException(
                            "首页顶部轮播最多只能配置 ${HomeRecommendationSection.HERO_CAROUSEL_MAX_ITEMS} 个商品",
                        )
                    }
                }
                if (group.categoryId != null && group.categoryId < 1) throw ParamErrorException("$groupLocation 的分类 ID 无效")
                if (group.tagId != null && group.tagId < 1) throw ParamErrorException("$groupLocation 的标签 ID 无效")
                if ((group.productType?.trim()?.length ?: 0) > 64) throw ParamErrorException("$groupLocation 的商品类型过长")
                group.categoryId?.let(referencedCategoryIds::add)
                group.productType?.trim()?.uppercase()?.takeIf(String::isNotEmpty)?.let(referencedProductTypes::add)
                group.tagId?.let(referencedTagIds::add)
                if (group.strategy == HomeRecommendationGroup.Strategy.NEW_ARRIVALS && group.lookbackDays !in 1..365) {
                    throw ParamErrorException("$groupLocation 的新品回溯天数必须为 1 到 365")
                }
                if (group.selectionMode == HomeRecommendationGroup.SelectionMode.MANUAL && group.items.isEmpty()) {
                    throw ParamErrorException("$groupLocation 使用人工选品时至少需要一个商品")
                }
                if (group.selectionMode == HomeRecommendationGroup.SelectionMode.AUTO && group.items.isNotEmpty()) {
                    throw ParamErrorException("$groupLocation 使用自动选品时不能配置人工商品")
                }
                val productIds = mutableSetOf<Long>()
                group.items.forEachIndexed { itemIndex, item ->
                    if (item.productId < 1) throw ParamErrorException("$groupLocation 的第 ${itemIndex + 1} 个商品 ID 无效")
                    if (!productIds.add(item.productId)) throw ParamErrorException("$groupLocation 不能重复选择商品 #${item.productId}")
                    referencedProductIds += item.productId
                    if (item.sortOrder < 0) throw ParamErrorException("$groupLocation 的人工商品排序值不能小于 0")
                    if ((item.customBadge?.trim()?.length ?: 0) > 30) throw ParamErrorException("$groupLocation 的商品角标不能超过 30 个字符")
                }
            }
        }
        if (referencedProductIds.isNotEmpty()) {
            val existingIds = productRepository.findAllById(referencedProductIds).mapNotNull { it.id }.toSet()
            val missingIds = referencedProductIds - existingIds
            if (missingIds.isNotEmpty()) throw ParamErrorException("商品不存在：${missingIds.sorted().joinToString()}")
        }
        if (referencedCategoryIds.isNotEmpty()) {
            val existingIds = productCategoryRepository.findAllById(referencedCategoryIds).mapNotNull { it.id }.toSet()
            val missingIds = referencedCategoryIds - existingIds
            if (missingIds.isNotEmpty()) throw ParamErrorException("商品分类不存在：${missingIds.sorted().joinToString()}")
        }
        referencedProductTypes.sorted().firstOrNull { !productTypeRepository.existsByCode(it) }?.let { code ->
            throw ParamErrorException("商品类型不存在：$code")
        }
        if (referencedTagIds.isNotEmpty()) {
            val existingIds = tagRepository.findAllById(referencedTagIds).mapNotNull { it.id }.toSet()
            val missingIds = referencedTagIds - existingIds
            if (missingIds.isNotEmpty()) throw ParamErrorException("商品标签不存在：${missingIds.sorted().joinToString()}")
        }
    }

    private fun validatePlan(plan: HomeRecommendationPlan) {
        validateCommand(
            AdminHomeRecommendationService.CreateCommand(
                name = plan.name,
                effectiveFrom = plan.effectiveFrom,
                effectiveUntil = plan.effectiveUntil,
                fallbackEnabled = plan.fallbackEnabled,
                deduplicateAcrossSections = plan.deduplicateAcrossSections,
                sections = plan.sections.map { section ->
                    AdminHomeRecommendationService.SectionCommand(
                        code = section.code,
                        eyebrow = section.eyebrow,
                        title = section.title,
                        subtitle = section.subtitle,
                        displayStyle = section.displayStyle,
                        desktopColumns = section.desktopColumns,
                        mobileColumns = section.mobileColumns,
                        linkLabel = section.linkLabel,
                        linkUrl = section.linkUrl,
                        itemLimit = section.itemLimit,
                        hideWhenEmpty = section.hideWhenEmpty,
                        sortOrder = section.sortOrder,
                        groups = section.groups.map { group ->
                            AdminHomeRecommendationService.GroupCommand(
                                code = group.code,
                                title = group.title,
                                selectionMode = group.selectionMode,
                                strategy = group.strategy,
                                itemLimit = group.itemLimit,
                                categoryId = group.categoryId,
                                productType = group.productType,
                                tagId = group.tagId,
                                lookbackDays = group.lookbackDays,
                                minimumStock = group.minimumStock,
                                fallbackStrategy = group.fallbackStrategy,
                                sortOrder = group.sortOrder,
                                items = group.items.map { item ->
                                    AdminHomeRecommendationService.ItemCommand(
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
            ),
        )
        val resolved = homeRecommendationService.resolve(plan, useDefaultFallback = false)
        if (!plan.fallbackEnabled && resolved.sections.none { section -> section.groups.any { it.products.isNotEmpty() } }) {
            throw ParamErrorException("所有商品组解析后都为空，且方案未开启系统兜底")
        }
        val resolvedSections = resolved.sections.associateBy { it.code }
        if (plan.sections.any { it.code == HomeRecommendationSection.HERO_CAROUSEL_CODE }) {
            val heroProductCount = resolvedSections[HomeRecommendationSection.HERO_CAROUSEL_CODE]
                ?.groups
                ?.sumOf { it.products.size }
                ?: 0
            if (heroProductCount == 0) {
                throw ParamErrorException("首页顶部轮播解析后没有可展示商品")
            }
        }
        plan.sections.filter { it.displayStyle == HomeRecommendationSection.DisplayStyle.TABS }.forEach { section ->
            val effectiveGroupCount = resolvedSections[section.code]?.groups?.count { it.products.isNotEmpty() } ?: 0
            if (effectiveGroupCount < 2) {
                throw ParamErrorException("TABS 楼层 ${section.code} 少于两个有效商品组")
            }
        }
    }

    private fun normalizedLinkUrl(linkUrl: String?): String? {
        val normalized = linkUrl?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.length > 512 || containsUnsafeUrlCharacters(normalized)) {
            throw ParamErrorException("推荐楼层链接包含不安全字符或长度超过 512")
        }
        val uri = runCatching { URI(normalized) }.getOrNull() ?: throw ParamErrorException("推荐楼层链接格式无效")
        if (!uri.isAbsolute) {
            val rawPath = uri.rawPath ?: throw ParamErrorException("站内链接必须以单个 / 开头")
            if (uri.authority != null || !rawPath.startsWith("/") || rawPath.startsWith("//")) {
                throw ParamErrorException("站内链接必须以单个 / 开头")
            }
            ensureDecodedPathIsSafe(rawPath)
            return normalized
        }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw ParamErrorException("站外链接只允许使用 HTTPS")
        }
        uri.rawPath?.let(::ensureDecodedPathIsSafe)
        return normalized
    }

    private fun ensureDecodedPathIsSafe(rawPath: String) {
        var decoded = rawPath
        repeat(3) {
            if (containsUnsafeUrlCharacters(decoded) || decoded.startsWith("//")) {
                throw ParamErrorException("推荐楼层链接包含不安全路径")
            }
            val next = runCatching { URLDecoder.decode(decoded, StandardCharsets.UTF_8) }
                .getOrElse { throw ParamErrorException("推荐楼层链接格式无效") }
            if (next == decoded) return
            decoded = next
        }
        if (containsUnsafeUrlCharacters(decoded) || decoded.startsWith("//")) {
            throw ParamErrorException("推荐楼层链接包含不安全路径")
        }
    }

    private fun containsUnsafeUrlCharacters(value: String): Boolean = value.any {
        it == '\\' || it.code in 0..31 || it.code == 127
    }

    private fun intervalsOverlap(left: HomeRecommendationPlan, right: HomeRecommendationPlan): Boolean {
        val leftEndsAfterRightStarts = left.effectiveUntil == null || left.effectiveUntil!! > right.effectiveFrom
        val rightEndsAfterLeftStarts = right.effectiveUntil == null || right.effectiveUntil!! > left.effectiveFrom
        return leftEndsAfterRightStarts && rightEndsAfterLeftStarts
    }

    private fun initializePlan(plan: HomeRecommendationPlan) {
        plan.sections.forEach { section -> section.groups.forEach { group -> group.items.size } }
    }

    private fun requireExpectedVersion(plan: HomeRecommendationPlan, expectedVersion: Long) {
        if (plan.version != expectedVersion) throw HomeRecommendationVersionConflictException(plan.version)
    }

    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), recommendationZoneId)

    companion object {
        private const val SYSTEM_OPERATOR_ID = 0L
        private val CODE_PATTERN = Regex("^[a-z][a-z0-9_]*$")
    }
}
