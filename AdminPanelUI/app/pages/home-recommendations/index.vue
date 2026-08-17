<script setup lang="ts">
import { ArrowDown, ArrowUp, ImagePlus, Plus, RefreshCw, Save, Search, Sparkles, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, FormInst, SelectOption, TagProps, UploadCustomRequestOptions } from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import {
  HOME_RECOMMENDATION_DISPLAY_STYLE_OPTIONS,
  HOME_RECOMMENDATION_FALLBACK_OPTIONS,
  HOME_RECOMMENDATION_SELECTION_MODE_OPTIONS,
  HOME_RECOMMENDATION_STATUS_OPTIONS,
  HOME_RECOMMENDATION_STRATEGY_OPTIONS,
  useHomeRecommendationApi,
} from '~/composables/useHomeRecommendationApi'
import { useProductApi } from '~/composables/useProductApi'
import type { Product, ProductCategory, ProductType, Tag } from '~/types/product'
import type {
  HomeRecommendationDisplayStyle,
  HomeRecommendationCategoryInput,
  HomeRecommendationFormInput,
  HomeRecommendationGroupInput,
  HomeRecommendationPlanDetail,
  HomeRecommendationPlanListItem,
  HomeRecommendationPreview,
  HomeRecommendationSectionInput,
  HomeRecommendationStatus,
  HomeRecommendationStrategy,
} from '~/types/home-recommendation'

definePageMeta({ layout: 'default' })

type EditableGroup = HomeRecommendationGroupInput & { productToAdd: number | null }
type EditableSection = Omit<HomeRecommendationSectionInput, 'groups'> & { groups: EditableGroup[] }
type EditableForm = Omit<HomeRecommendationFormInput, 'sections'> & { sections: EditableSection[] }

const HERO_CAROUSEL_CODE = 'hero_carousel'
const HERO_CAROUSEL_MAX_ITEMS = 8

const api = useHomeRecommendationApi()
const productApi = useProductApi()
const message = useMessage()
const runtimeConfig = useRuntimeConfig()
const homeRecommendationTimeZone = runtimeConfig.public.homeRecommendationTimeZone as string
const loading = ref(false)
const saving = ref(false)
const editorOpen = ref(false)
const previewOpen = ref(false)
const previewLoading = ref(false)
const actionKey = ref('')
const formRef = ref<FormInst | null>(null)
const editing = ref<HomeRecommendationPlanDetail | null>(null)
const preview = ref<HomeRecommendationPreview | null>(null)
const plans = ref<HomeRecommendationPlanListItem[]>([])
const productSearchLoading = ref(false)
const productOptions = ref<SelectOption[]>([])
const productCache = reactive<Record<number, Product>>({})
const catalogCategories = ref<ProductCategory[]>([])
const productTypes = ref<ProductType[]>([])
const tags = ref<Tag[]>([])
const categoryToAdd = ref<number | null>(null)
const pendingCategoryUploads = ref(0)
const filters = reactive({ keyword: '', status: null as HomeRecommendationStatus | null })
const pagination = reactive({ page: 1, pageSize: 25, pageCount: 1, total: 0 })

function localDateTimeNow() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: homeRecommendationTimeZone,
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date())
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}`
}

function defaultGroup(code = 'new_arrivals', title = 'New Arrivals', strategy: HomeRecommendationStrategy = 'NEW_ARRIVALS'): EditableGroup {
  return {
    code, title, selectionMode: 'AUTO', strategy, itemLimit: 8, categoryId: null, productType: '', tagId: null,
    lookbackDays: strategy === 'NEW_ARRIVALS' ? 30 : null, minimumStock: 1, fallbackStrategy: 'LATEST', items: [], productToAdd: null,
  }
}

function defaultSection(code = 'whats_hot'): EditableSection {
  return {
    code, eyebrow: 'WHAT’S HOT RIGHT NOW', title: 'Trending now.', subtitle: '', displayStyle: 'TABS', desktopColumns: 4,
    mobileColumns: 2, linkLabel: '查看全部', linkUrl: '/collections/shop', itemLimit: 8, hideWhenEmpty: true,
    groups: [defaultGroup(), defaultGroup('best_sellers', 'Best Sellers', 'BEST_SELLERS')],
  }
}

function defaultHeroSection(): EditableSection {
  return {
    code: HERO_CAROUSEL_CODE,
    eyebrow: 'FEATURED PRODUCTS',
    title: 'Shop the featured edit.',
    subtitle: '',
    displayStyle: 'CAROUSEL',
    desktopColumns: 4,
    mobileColumns: 2,
    linkLabel: '',
    linkUrl: '',
    itemLimit: HERO_CAROUSEL_MAX_ITEMS,
    hideWhenEmpty: true,
    groups: [{
      code: 'hero_products',
      title: '首页顶部轮播商品',
      selectionMode: 'MANUAL',
      strategy: 'EDITOR_PICKS',
      itemLimit: HERO_CAROUSEL_MAX_ITEMS,
      categoryId: null,
      productType: '',
      tagId: null,
      lookbackDays: null,
      minimumStock: 1,
      fallbackStrategy: 'NONE',
      items: [],
      productToAdd: null,
    }],
  }
}

function isHeroSection(section: EditableSection) {
  return section.code.trim().toLowerCase() === HERO_CAROUSEL_CODE
}

const form = reactive<EditableForm>({
  name: '首页默认推荐', effectiveFrom: localDateTimeNow(), effectiveUntil: null, fallbackEnabled: true,
  deduplicateAcrossSections: true, categories: [], sections: [defaultHeroSection(), defaultSection()],
})
const editorTitle = computed(() => editing.value ? `编辑推荐方案 #${editing.value.id}` : '新建首页推荐方案')
const publishedCount = computed(() => plans.value.filter(item => item.status === 'PUBLISHED').length)
const scheduledCount = computed(() => plans.value.filter(item => item.status === 'SCHEDULED').length)
const hasHeroSection = computed(() => form.sections.some(isHeroSection))
const categoryOptions = computed<SelectOption[]>(() => catalogCategories.value
  .filter(item => item.status === 'ACTIVE')
  .map(item => ({ label: `${item.name}（${item.code}）`, value: item.id })))
const homepageCategoryOptions = computed<SelectOption[]>(() => catalogCategories.value
  .filter(item => item.status === 'ACTIVE' && item.parentId == null)
  .map(item => ({
    label: `${item.name}（${item.code}）`,
    value: item.id,
    disabled: form.categories.some(category => category.categoryId === item.id),
  })))
const productTypeOptions = computed<SelectOption[]>(() => productTypes.value.filter(item => item.active).map(item => ({ label: `${item.name}（${item.code}）`, value: item.code })))
const tagOptions = computed<SelectOption[]>(() => tags.value.filter(item => item.active).map(item => ({ label: item.name, value: item.id })))

function errorMessage(error: unknown) {
  if (error && typeof error === 'object') {
    const value = error as { statusCode?: number; statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '请求失败'
  }
  return '请求失败'
}
function formatDate(value: string | null | undefined) { return value ? value.replace('T', ' ').slice(0, 19) : '-' }
function datetimeInputValue(value: string | null | undefined) { return value ? value.slice(0, 16) : '' }
function statusLabel(status: HomeRecommendationStatus) { return HOME_RECOMMENDATION_STATUS_OPTIONS.find(item => item.value === status)?.label ?? status }
function statusTagType(status: HomeRecommendationStatus): TagProps['type'] {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'SCHEDULED') return 'info'
  if (status === 'OFFLINE') return 'warning'
  if (status === 'EXPIRED' || status === 'ARCHIVED') return 'error'
  return 'default'
}
function strategyLabel(strategy: HomeRecommendationStrategy) { return HOME_RECOMMENDATION_STRATEGY_OPTIONS.find(item => item.value === strategy)?.label ?? strategy }
function displayStyleLabel(style: HomeRecommendationDisplayStyle) { return HOME_RECOMMENDATION_DISPLAY_STYLE_OPTIONS.find(item => item.value === style)?.label ?? style }

function resetForm() {
  form.name = '首页默认推荐'; form.effectiveFrom = localDateTimeNow(); form.effectiveUntil = null
  form.fallbackEnabled = true; form.deduplicateAcrossSections = true; form.categories = []; form.sections = [defaultHeroSection(), defaultSection()]
  editing.value = null; productOptions.value = []
}

function assignDetail(detail: HomeRecommendationPlanDetail) {
  form.name = detail.name
  form.effectiveFrom = datetimeInputValue(detail.effective_from)
  form.effectiveUntil = detail.effective_until ? datetimeInputValue(detail.effective_until) : null
  form.fallbackEnabled = detail.fallback_enabled
  form.deduplicateAcrossSections = detail.deduplicate_across_sections
  form.categories = (detail.categories ?? []).slice().sort((a, b) => a.sort_order - b.sort_order).map(category => ({
    categoryId: category.category_id,
    imageUrl: category.image_url,
    altText: category.alt_text ?? '',
  }))
  const sections: EditableSection[] = detail.sections.slice().sort((a, b) => a.sort_order - b.sort_order).map(section => ({
    code: section.code, eyebrow: section.eyebrow ?? '', title: section.title, subtitle: section.subtitle ?? '',
    displayStyle: section.display_style, desktopColumns: section.desktop_columns, mobileColumns: section.mobile_columns,
    linkLabel: section.link_label ?? '', linkUrl: section.link_url ?? '', itemLimit: section.item_limit, hideWhenEmpty: section.hide_when_empty,
    groups: section.groups.slice().sort((a, b) => a.sort_order - b.sort_order).map(group => ({
      code: group.code, title: group.title ?? '', selectionMode: group.selection_mode, strategy: group.strategy,
      itemLimit: group.item_limit, categoryId: group.category_id ?? null, productType: group.product_type ?? '', tagId: group.tag_id ?? null,
      lookbackDays: group.lookback_days ?? null, minimumStock: group.minimum_stock, fallbackStrategy: group.fallback_strategy,
      items: group.items.slice().sort((a, b) => a.sort_order - b.sort_order).map(item => ({ productId: item.product_id, pinned: item.pinned, customBadge: item.custom_badge ?? '' })),
      productToAdd: null,
    })),
  }))
  const heroIndex = sections.findIndex(section => section.code.trim().toLowerCase() === HERO_CAROUSEL_CODE)
  if (heroIndex >= 0) {
    const [heroSection] = sections.splice(heroIndex, 1)
    if (heroSection) {
      const heroGroup = heroSection.groups[0] ?? defaultHeroSection().groups[0]!
      heroSection.code = HERO_CAROUSEL_CODE
      heroSection.displayStyle = 'CAROUSEL'
      heroSection.itemLimit = Math.min(HERO_CAROUSEL_MAX_ITEMS, Math.max(1, heroSection.itemLimit))
      heroGroup.selectionMode = 'MANUAL'
      heroGroup.strategy = 'EDITOR_PICKS'
      heroGroup.itemLimit = Math.min(HERO_CAROUSEL_MAX_ITEMS, Math.max(1, heroGroup.itemLimit))
      heroGroup.categoryId = null
      heroGroup.productType = ''
      heroGroup.tagId = null
      heroGroup.lookbackDays = null
      heroGroup.fallbackStrategy = 'NONE'
      heroGroup.items = heroGroup.items.slice(0, HERO_CAROUSEL_MAX_ITEMS).map(item => ({ ...item, pinned: false }))
      heroSection.groups = [heroGroup]
      sections.unshift(heroSection)
    }
  }
  form.sections = sections
}

async function loadPlans() {
  loading.value = true
  try {
    const result = await api.list({
      page: pagination.page - 1, size: pagination.pageSize, keyword: filters.keyword.trim() || undefined,
      status: filters.status ?? undefined, sort_by: 'UPDATED_AT', sort_direction: 'DESC',
    })
    plans.value = result.items ?? []
    pagination.total = result.total_elements ?? 0
    pagination.pageCount = Math.max(1, result.total_pages ?? 1)
  } catch (error) { message.error(`加载推荐方案失败：${errorMessage(error)}`) }
  finally { loading.value = false }
}

async function loadMetadata() {
  const [categoryResult, productTypeResult, tagResult] = await Promise.allSettled([productApi.listCategories(), productApi.listProductTypes(), productApi.listTags()])
  if (categoryResult.status === 'fulfilled') catalogCategories.value = categoryResult.value
  if (productTypeResult.status === 'fulfilled') productTypes.value = productTypeResult.value
  if (tagResult.status === 'fulfilled') tags.value = tagResult.value
}

async function hydrateProductLabels(detail: HomeRecommendationPlanDetail) {
  const ids = [...new Set(detail.sections.flatMap(section => section.groups.flatMap(group => group.items.map(item => item.product_id))))].filter(id => !productCache[id]).slice(0, 100)
  const results = await Promise.allSettled(ids.map(id => productApi.getProduct(id)))
  results.forEach((result) => { if (result.status === 'fulfilled') productCache[result.value.id] = result.value })
}

function openCreate() { resetForm(); editorOpen.value = true }
async function openEdit(row: HomeRecommendationPlanListItem) {
  if (row.status !== 'DRAFT' && row.status !== 'OFFLINE') {
    if (!window.confirm(`“${row.name}”当前不可原位编辑，是否复制为新草稿后编辑？`)) return
    actionKey.value = `copy-edit-${row.id}`
    try {
      const copied = await api.copy(row.id, row.version)
      const detail = await api.getOne(copied.id)
      editing.value = detail; assignDetail(detail); editorOpen.value = true; void hydrateProductLabels(detail)
      message.success('已复制为新草稿'); await loadPlans()
    } catch (error) { await handleActionError(error, '复制方案') }
    finally { actionKey.value = '' }
    return
  }
  actionKey.value = `edit-${row.id}`
  try {
    const detail = await api.getOne(row.id)
    editing.value = detail; assignDetail(detail); editorOpen.value = true; void hydrateProductLabels(detail)
  } catch (error) { message.error(`加载方案详情失败：${errorMessage(error)}`) }
  finally { actionKey.value = '' }
}

function addHeroSection() {
  if (hasHeroSection.value) return
  if (form.sections.length >= 10) { message.warning('一个方案最多配置 10 个楼层'); return }
  form.sections.unshift(defaultHeroSection())
}
function addSection() {
  if (form.sections.length >= 10) { message.warning('一个方案最多配置 10 个楼层'); return }
  const index = form.sections.length + 1
  form.sections.push({ ...defaultSection(`section_${index}`), displayStyle: 'GRID', eyebrow: '', groups: [defaultGroup(`group_${index}`, '', 'NEW_ARRIVALS')] })
}
function removeSection(index: number) {
  if (form.sections.length === 1) { message.warning('方案至少保留一个楼层'); return }
  form.sections.splice(index, 1)
}
function addGroup(section: EditableSection) {
  if (section.groups.length >= 8) { message.warning('一个楼层最多配置 8 个商品组'); return }
  const index = section.groups.length + 1
  section.groups.push(defaultGroup(`group_${index}`, `商品组 ${index}`))
}
function removeGroup(section: EditableSection, index: number) {
  if (section.groups.length === 1) { message.warning('楼层至少保留一个商品组'); return }
  section.groups.splice(index, 1)
}
function move<T>(items: T[], index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= items.length) return
  const current = items[index]; const next = items[target]
  if (current === undefined || next === undefined) return
  items[index] = next; items[target] = current
}
function moveSection(index: number, direction: -1 | 1) {
  const section = form.sections[index]
  const target = index + direction
  if (!section || isHeroSection(section) || target < 0 || target >= form.sections.length) return
  if (target === 0 && form.sections[0] && isHeroSection(form.sections[0])) return
  move(form.sections, index, direction)
}

function addCategory(categoryId: number | null) {
  if (categoryId == null) return
  if (form.categories.length >= 8) { message.warning('首页最多展示 8 个分类'); categoryToAdd.value = null; return }
  if (form.categories.some(category => category.categoryId === categoryId)) {
    message.warning('该分类已添加'); categoryToAdd.value = null; return
  }
  const category = catalogCategories.value.find(item => item.id === categoryId)
  if (!category) { message.warning('未找到所选分类'); categoryToAdd.value = null; return }
  form.categories.push({ categoryId, imageUrl: '', altText: category.name })
  categoryToAdd.value = null
}

function categoryLabel(categoryId: number) {
  const category = catalogCategories.value.find(item => item.id === categoryId)
  return category ? `${category.name}（${category.code}）` : `分类 #${categoryId}`
}

async function uploadCategoryImage(category: HomeRecommendationCategoryInput, options: UploadCustomRequestOptions) {
  const file = options.file.file
  if (!file) { options.onError(); return }
  pendingCategoryUploads.value += 1
  try {
    const [uploaded] = await productApi.uploadImages([file])
    if (!uploaded) throw new Error('上传响应缺少图片地址')
    category.imageUrl = uploaded.stableUrl
    if (!category.altText.trim()) category.altText = file.name
    options.onFinish()
  } catch (error) {
    message.error(`分类图片上传失败：${errorMessage(error)}`)
    options.onError()
  } finally {
    pendingCategoryUploads.value -= 1
  }
}

async function searchProducts(keyword: string) {
  productSearchLoading.value = true
  try {
    const result = await productApi.listProducts({ keyword: keyword.trim() || undefined, status: 'ACTIVE', deleted: false, page: 1, size: 20 })
    result.list.forEach(product => { productCache[product.id] = product })
    productOptions.value = result.list.map(product => ({ label: `${product.name}（#${product.id}）`, value: product.id }))
  } catch (error) { message.error(`搜索商品失败：${errorMessage(error)}`) }
  finally { productSearchLoading.value = false }
}
function addSelectedProduct(group: EditableGroup, productId: number | null, limit = 24) {
  if (productId == null) return
  if (group.items.some(item => item.productId === productId)) { message.warning('该商品已在当前商品组中'); group.productToAdd = null; return }
  if (group.items.length >= limit) { message.warning(`当前商品组最多配置 ${limit} 个人工商品`); group.productToAdd = null; return }
  group.items.push({ productId, pinned: group.selectionMode === 'HYBRID', customBadge: '' }); group.productToAdd = null
}
function productLabel(productId: number) { return productCache[productId] ? `${productCache[productId].name}（#${productId}）` : `商品 #${productId}` }
function productImage(productId: number) { return productCache[productId]?.images[0]?.url }

function validateForm() {
  if (!form.name.trim()) return '请输入方案名称'
  if (form.name.trim().length > 120) return '方案名称不能超过 120 个字符'
  if (!form.effectiveFrom) return '请选择生效时间'
  if (form.effectiveUntil && form.effectiveUntil <= form.effectiveFrom) return '失效时间必须晚于生效时间'
  if (form.categories.length > 8) return '首页展示分类数量必须在 0 到 8 之间'
  const configuredCategoryIds = new Set<number>()
  for (let categoryIndex = 0; categoryIndex < form.categories.length; categoryIndex += 1) {
    const category = form.categories[categoryIndex]!
    const label = `第 ${categoryIndex + 1} 个首页展示分类`
    if (configuredCategoryIds.has(category.categoryId)) return `${label}与前面的分类重复，请删除重复项`
    configuredCategoryIds.add(category.categoryId)
    const imageUrl = category.imageUrl.trim()
    if (!imageUrl) return `${label}必须上传图片或填写图片地址`
    if (imageUrl.length > 512) return `${label}的图片地址不能超过 512 个字符`
    const localDevelopmentImage = /^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:\/|$)/i.test(imageUrl)
    if (!imageUrl.startsWith('/') && !/^https:\/\/[^\s]+$/i.test(imageUrl) && !localDevelopmentImage) return `${label}的图片地址必须是站内 / 路径或 HTTPS 地址；本地开发可使用 localhost、127.0.0.1 或 ::1 的 HTTP 地址`
    if (imageUrl.startsWith('//') || imageUrl.includes('\\')) return `${label}的图片地址必须以单个 / 开头且不能包含反斜杠`
    if (category.altText.trim().length > 255) return `${label}的图片替代文本不能超过 255 个字符`
  }
  if (form.sections.length < 1 || form.sections.length > 10) return '楼层数量必须在 1 到 10 之间'
  const sectionCodes = new Set<string>()
  for (let sectionIndex = 0; sectionIndex < form.sections.length; sectionIndex += 1) {
    const section = form.sections[sectionIndex]!
    const label = `第 ${sectionIndex + 1} 个楼层`
    const code = section.code.trim().toLowerCase()
    if (!/^[a-z][a-z0-9_]*$/.test(code)) return `${label}编码必须以字母开头，且只能包含字母、数字和下划线；字母会在保存时转为小写`
    if (sectionCodes.has(code)) return `楼层编码“${code}”重复`
    sectionCodes.add(code)
    if (code === HERO_CAROUSEL_CODE) {
      if (sectionIndex !== 0) return '首页顶部轮播必须位于第一个楼层'
      if (section.displayStyle !== 'CAROUSEL') return '首页顶部轮播的展示形态必须为轮播'
      if (section.itemLimit < 1 || section.itemLimit > HERO_CAROUSEL_MAX_ITEMS) return `首页顶部轮播展示数量必须在 1 到 ${HERO_CAROUSEL_MAX_ITEMS} 之间`
    }
    if (!section.title.trim()) return `${label}标题不能为空`
    if (section.title.trim().length > 120) return `${label}标题不能超过 120 个字符`
    if (section.desktopColumns < 2 || section.desktopColumns > 6) return `${label}桌面列数必须在 2 到 6 之间`
    if (section.mobileColumns < 1 || section.mobileColumns > 2) return `${label}移动列数必须在 1 到 2 之间`
    if (section.itemLimit < 1 || section.itemLimit > 24) return `${label}展示数量必须在 1 到 24 之间`
    if (section.groups.length < 1 || section.groups.length > 8) return `${label}商品组数量必须在 1 到 8 之间`
    if (section.displayStyle === 'TABS' && section.groups.length < 2) return `${label}使用页签时至少需要两个商品组`
    if (section.displayStyle !== 'TABS' && section.groups.length !== 1) return `${label}使用网格或轮播时必须且只能配置一个商品组`
    const url = section.linkUrl.trim()
    if (url && !url.startsWith('/') && !/^https:\/\//i.test(url)) return `${label}跳转链接必须是站内 / 路径或 HTTPS 链接`
    const groupCodes = new Set<string>()
    for (let groupIndex = 0; groupIndex < section.groups.length; groupIndex += 1) {
      const group = section.groups[groupIndex]!
      const groupLabel = `${label}的第 ${groupIndex + 1} 个商品组`
      const groupCode = group.code.trim().toLowerCase()
      if (!/^[a-z][a-z0-9_]*$/.test(groupCode)) return `${groupLabel}编码必须以字母开头，且只能包含字母、数字和下划线；字母会在保存时转为小写`
      if (groupCodes.has(groupCode)) return `${label}商品组编码“${groupCode}”重复`
      groupCodes.add(groupCode)
      if (section.displayStyle === 'TABS' && !group.title.trim()) return `${groupLabel}在页签模式下必须填写标题`
      if (group.itemLimit < 1 || group.itemLimit > 24) return `${groupLabel}展示数量必须在 1 到 24 之间`
      if (code === HERO_CAROUSEL_CODE) {
        if (group.selectionMode !== 'MANUAL') return '首页顶部轮播必须使用人工选品'
        if (group.strategy !== 'EDITOR_PICKS') return '首页顶部轮播必须使用编辑精选策略'
        if (group.fallbackStrategy !== 'NONE') return '首页顶部轮播不能配置自动补位'
        if (group.itemLimit > HERO_CAROUSEL_MAX_ITEMS || group.items.length > HERO_CAROUSEL_MAX_ITEMS) return `首页顶部轮播最多只能配置 ${HERO_CAROUSEL_MAX_ITEMS} 个商品`
      }
      if (group.minimumStock < 1) return `${groupLabel}最低库存必须大于等于 1`
      if (group.strategy === 'NEW_ARRIVALS' && (!group.lookbackDays || group.lookbackDays < 1 || group.lookbackDays > 365)) return `${groupLabel}新品回溯天数必须在 1 到 365 之间`
      if (group.selectionMode === 'MANUAL' && group.items.length === 0) return `${groupLabel}使用人工选品时至少选择一个商品`
      const productIds = new Set<number>()
      for (const item of group.items) {
        if (productIds.has(item.productId)) return `${groupLabel}存在重复商品 #${item.productId}`
        productIds.add(item.productId)
        if (item.customBadge.trim().length > 30) return `${groupLabel}的商品徽标不能超过 30 个字符`
      }
    }
  }
  return null
}

function formInput(): HomeRecommendationFormInput {
  return {
    name: form.name, effectiveFrom: form.effectiveFrom, effectiveUntil: form.effectiveUntil || null,
    fallbackEnabled: form.fallbackEnabled, deduplicateAcrossSections: form.deduplicateAcrossSections,
    categories: form.categories.map(category => ({ ...category })),
    sections: form.sections.map(section => ({
      code: section.code, eyebrow: section.eyebrow, title: section.title, subtitle: section.subtitle,
      displayStyle: section.displayStyle, desktopColumns: section.desktopColumns, mobileColumns: section.mobileColumns,
      linkLabel: section.linkLabel, linkUrl: section.linkUrl, itemLimit: section.itemLimit, hideWhenEmpty: section.hideWhenEmpty,
      groups: section.groups.map(group => ({
        code: group.code, title: group.title, selectionMode: group.selectionMode, strategy: group.strategy,
        itemLimit: group.itemLimit, categoryId: group.categoryId, productType: group.productType, tagId: group.tagId,
        lookbackDays: group.lookbackDays, minimumStock: group.minimumStock, fallbackStrategy: group.fallbackStrategy,
        items: group.items.map(item => ({ ...item })),
      })),
    })),
  }
}

async function save() {
  try { await formRef.value?.validate() } catch { return }
  const validationError = validateForm()
  if (validationError) { message.error(validationError); return }
  saving.value = true
  try {
    if (editing.value) { await api.update(editing.value.id, formInput(), editing.value.version); message.success('推荐方案已保存') }
    else { await api.create(formInput()); message.success('推荐方案草稿已创建') }
    editorOpen.value = false; await loadPlans()
  } catch (error) {
    if ((error as { statusCode?: number })?.statusCode === 409) { message.warning('方案已被其他管理员修改，请刷新后重新编辑'); await loadPlans() }
    else message.error(`保存推荐方案失败：${errorMessage(error)}`)
  } finally { saving.value = false }
}

async function duplicatePlan(row: HomeRecommendationPlanListItem) {
  actionKey.value = `copy-${row.id}`
  try { await api.copy(row.id, row.version); message.success('已复制为新草稿'); await loadPlans() }
  catch (error) { await handleActionError(error, '复制方案') }
  finally { actionKey.value = '' }
}
async function publishPlan(row: HomeRecommendationPlanListItem) {
  if (!window.confirm(`确认发布“${row.name}”吗？未来生效的方案将进入排期状态；立即生效会原子下线当前线上方案。`)) return
  actionKey.value = `publish-${row.id}`
  try {
    const result = await api.publish(row.id, row.version)
    message.success(result.status === 'SCHEDULED' ? '推荐方案已排期' : '推荐方案已发布'); await loadPlans()
  } catch (error) { await handleActionError(error, '发布方案') }
  finally { actionKey.value = '' }
}
async function offlinePlan(row: HomeRecommendationPlanListItem) {
  if (!window.confirm(`确认下线“${row.name}”吗？若没有其他生效方案，客户首页将使用系统默认推荐。`)) return
  actionKey.value = `offline-${row.id}`
  try { await api.offline(row.id, row.version); message.success('推荐方案已下线'); await loadPlans() }
  catch (error) { await handleActionError(error, '下线方案') }
  finally { actionKey.value = '' }
}
async function archivePlan(row: HomeRecommendationPlanListItem) {
  if (!window.confirm(`确认归档“${row.name}”吗？归档后不能直接编辑或发布。`)) return
  actionKey.value = `archive-${row.id}`
  try { await api.archive(row.id, row.version); message.success('推荐方案已归档'); await loadPlans() }
  catch (error) { await handleActionError(error, '归档方案') }
  finally { actionKey.value = '' }
}
async function handleActionError(error: unknown, action: string) {
  if ((error as { statusCode?: number })?.statusCode === 409) {
    const detail = errorMessage(error)
    if (detail.includes('生效区间')) message.warning(detail)
    else { message.warning('方案版本已变化，已刷新最新列表'); await loadPlans() }
  } else message.error(`${action}失败：${errorMessage(error)}`)
}
async function openPreview(row: HomeRecommendationPlanListItem) {
  previewOpen.value = true; previewLoading.value = true; preview.value = null
  try { preview.value = await api.preview(row.id, 12) }
  catch (error) { message.error(`生成预览失败：${errorMessage(error)}`) }
  finally { previewLoading.value = false }
}

const columns: DataTableColumns<HomeRecommendationPlanListItem> = [
  { title: '推荐方案', key: 'name', minWidth: 220, render: row => h('div', { class: 'plan-title-cell' }, [h('strong', row.name), h('span', `#${row.id} · v${row.version} · CUSTOMER_WEB`)]) },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: statusTagType(row.status), bordered: false }, { default: () => statusLabel(row.status) }) },
  { title: '楼层数', key: 'section_count', width: 90, render: row => `${row.section_count} 个` },
  { title: '有效期', key: 'effective_from', width: 205, render: row => h('div', { class: 'date-cell' }, [h('span', `起：${formatDate(row.effective_from)}`), h('span', `止：${formatDate(row.effective_until)}`)]) },
  { title: '更新时间', key: 'updated_at', width: 170, render: row => formatDate(row.updated_at) },
  { title: '操作', key: 'actions', width: 410, fixed: 'right', render: row => h('div', { class: 'table-actions' }, [
    h(NButton, { size: 'small', tertiary: true, type: 'primary', loading: actionKey.value === `edit-${row.id}` || actionKey.value === `copy-edit-${row.id}`, onClick: () => void openEdit(row) }, { default: () => row.status === 'DRAFT' || row.status === 'OFFLINE' ? '编辑' : '复制编辑' }),
    h(NButton, { size: 'small', tertiary: true, onClick: () => void openPreview(row) }, { default: () => '预览' }),
    h(NButton, { size: 'small', tertiary: true, loading: actionKey.value === `copy-${row.id}`, onClick: () => void duplicatePlan(row) }, { default: () => '复制' }),
    row.status === 'DRAFT' || row.status === 'OFFLINE' ? h(NButton, { size: 'small', tertiary: true, type: 'success', loading: actionKey.value === `publish-${row.id}`, onClick: () => void publishPlan(row) }, { default: () => '发布' }) : null,
    row.status === 'SCHEDULED' || row.status === 'PUBLISHED' ? h(NButton, { size: 'small', tertiary: true, type: 'warning', loading: actionKey.value === `offline-${row.id}`, onClick: () => void offlinePlan(row) }, { default: () => '下线' }) : null,
    row.status !== 'SCHEDULED' && row.status !== 'PUBLISHED' && row.status !== 'ARCHIVED' ? h(NButton, { size: 'small', tertiary: true, type: 'error', loading: actionKey.value === `archive-${row.id}`, onClick: () => void archivePlan(row) }, { default: () => '归档' }) : null,
  ]) },
]

onMounted(() => { void loadPlans(); void loadMetadata(); void searchProducts('') })
</script>

<template>
  <div class="page-shell">
    <div class="page-heading">
      <div>
        <div class="eyebrow"><Sparkles :size="16" /> 首页运营</div>
        <h1>首页商品推荐</h1>
        <p>配置客户首页的推荐楼层、页签与选品策略，通过整套方案原子发布。</p>
      </div>
      <NSpace>
        <NButton :loading="loading" @click="loadPlans"><template #icon><RefreshCw :size="16" /></template>刷新</NButton>
        <NButton type="primary" @click="openCreate"><template #icon><Plus :size="16" /></template>新建方案</NButton>
      </NSpace>
    </div>

    <NGrid cols="1 s:3" responsive="screen" :x-gap="16" :y-gap="16">
      <NGridItem><NCard size="small"><NStatistic label="当前页方案" :value="plans.length" /></NCard></NGridItem>
      <NGridItem><NCard size="small"><NStatistic label="已发布" :value="publishedCount" /></NCard></NGridItem>
      <NGridItem><NCard size="small"><NStatistic label="已排期" :value="scheduledCount" /></NCard></NGridItem>
    </NGrid>

    <NCard class="filter-card" size="small">
      <NSpace align="center" :wrap="true">
        <NInput v-model:value="filters.keyword" clearable placeholder="按方案名称搜索" style="width: 260px" @keyup.enter="pagination.page = 1; loadPlans()"><template #prefix><Search :size="16" /></template></NInput>
        <NSelect v-model:value="filters.status" clearable placeholder="全部状态" :options="HOME_RECOMMENDATION_STATUS_OPTIONS" style="width: 160px" />
        <NButton type="primary" @click="pagination.page = 1; loadPlans()">查询</NButton>
        <NButton @click="filters.keyword = ''; filters.status = null; pagination.page = 1; loadPlans()">重置</NButton>
      </NSpace>
    </NCard>

    <NCard content-style="padding: 0" :bordered="true">
      <NDataTable :columns="columns" :data="plans" :loading="loading" :row-key="row => row.id" :scroll-x="1200" :pagination="false" />
      <div class="pagination-bar"><span>共 {{ pagination.total }} 条</span><NPagination v-model:page="pagination.page" :page-count="pagination.pageCount" @update:page="loadPlans" /></div>
    </NCard>

    <NDrawer v-model:show="editorOpen" width="min(1080px, 100vw)" placement="right">
      <NDrawerContent :title="editorTitle" closable>
        <NAlert type="info" :bordered="false" class="drawer-alert">时间按 {{ homeRecommendationTimeZone }} 解释；保存后仍为草稿，需在列表中执行发布。预览由服务端按实时商品、库存和去重规则解析。</NAlert>
        <NForm ref="formRef" :model="form" label-placement="top">
          <NCard size="small" title="方案设置" class="editor-card">
            <NGrid cols="1 m:2" responsive="screen" :x-gap="16">
              <NFormItemGi label="方案名称" path="name" :rule="{ required: true, message: '请输入方案名称', trigger: ['blur', 'input'] }"><NInput v-model:value="form.name" maxlength="120" show-count /></NFormItemGi>
              <NFormItemGi label="方案渠道"><NInput value="CUSTOMER_WEB" disabled /></NFormItemGi>
              <NFormItemGi label="生效时间" path="effectiveFrom" :rule="{ required: true, message: '请选择生效时间', trigger: ['blur', 'change'] }"><input v-model="form.effectiveFrom" class="datetime-input" type="datetime-local"></NFormItemGi>
              <NFormItemGi label="失效时间（可选）"><input v-model="form.effectiveUntil" class="datetime-input" type="datetime-local"></NFormItemGi>
              <NFormItemGi label="无可用方案时启用默认推荐"><NSwitch v-model:value="form.fallbackEnabled" /></NFormItemGi>
              <NFormItemGi label="跨楼层商品去重"><NSwitch v-model:value="form.deduplicateAcrossSections" /></NFormItemGi>
            </NGrid>
          </NCard>

          <section class="category-config-section">
            <div class="section-toolbar category-toolbar">
              <div>
                <h2>首页分类展示</h2>
                <span>最多 8 个启用中的顶级分类；列表顺序就是客户首页 “Made for every moment.” 区域的展示顺序。</span>
              </div>
              <NSelect
                v-model:value="categoryToAdd"
                filterable
                clearable
                :options="homepageCategoryOptions"
                :disabled="form.categories.length >= 8"
                placeholder="选择并添加顶级分类"
                style="width: min(360px, 100%)"
                @update:value="value => addCategory(value as number | null)"
              />
            </div>
            <NAlert v-if="form.categories.length === 0" type="default" :bordered="false">
              未配置分类时，现有方案会继续按商品分类默认顺序和默认图片展示。
            </NAlert>
            <div v-else class="category-editor-list">
              <div v-for="(category, categoryIndex) in form.categories" :key="category.categoryId" class="category-editor-item">
                <div class="category-image-preview">
                  <NImage v-if="category.imageUrl" :src="category.imageUrl" object-fit="cover" preview-disabled />
                  <ImagePlus v-else :size="24" />
                </div>
                <div class="category-editor-fields">
                  <strong>{{ categoryIndex + 1 }}. {{ categoryLabel(category.categoryId) }}</strong>
                  <div class="field-with-hint">
                    <NInput v-model:value="category.imageUrl" maxlength="512" placeholder="/images/category.jpg 或 https://..." />
                    <small class="field-hint">必填；支持上传，或填写以单个 / 开头的站内路径、HTTPS 地址；本地开发允许 localhost、127.0.0.1 或 ::1 的 HTTP 地址；最多 512 个字符。</small>
                  </div>
                  <div class="field-with-hint">
                    <NInput v-model:value="category.altText" maxlength="255" show-count placeholder="图片替代文本" />
                    <small class="field-hint">可选，最多 255 个字符；建议描述分类和画面内容。</small>
                  </div>
                </div>
                <div class="category-editor-actions">
                  <NUpload
                    :show-file-list="false"
                    accept="image/jpeg,image/png,image/webp,image/gif"
                    :custom-request="options => uploadCategoryImage(category, options)"
                  >
                    <NButton secondary size="small" :loading="pendingCategoryUploads > 0">
                      <template #icon><ImagePlus :size="15" /></template>上传
                    </NButton>
                  </NUpload>
                  <NSpace :wrap="false">
                    <NButton quaternary circle size="small" aria-label="分类上移" :disabled="categoryIndex === 0" @click="move(form.categories, categoryIndex, -1)"><template #icon><ArrowUp :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" aria-label="分类下移" :disabled="categoryIndex === form.categories.length - 1" @click="move(form.categories, categoryIndex, 1)"><template #icon><ArrowDown :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" type="error" aria-label="删除分类展示" @click="form.categories.splice(categoryIndex, 1)"><template #icon><Trash2 :size="15" /></template></NButton>
                  </NSpace>
                </div>
              </div>
            </div>
          </section>

          <div class="section-toolbar">
            <div><h2>推荐楼层</h2><span>按顺序渲染到客户首页，最多 10 个楼层。</span></div>
            <NSpace>
              <NButton secondary :disabled="hasHeroSection || form.sections.length >= 10" @click="addHeroSection"><template #icon><Sparkles :size="16" /></template>{{ hasHeroSection ? '已配置顶部轮播' : '添加顶部轮播' }}</NButton>
              <NButton type="primary" secondary :disabled="form.sections.length >= 10" @click="addSection"><template #icon><Plus :size="16" /></template>添加楼层</NButton>
            </NSpace>
          </div>
          <NCollapse :default-expanded-names="form.sections.map((_, index) => `section-${index}`)">
            <NCollapseItem v-for="(section, sectionIndex) in form.sections" :key="`section-${sectionIndex}`" :name="`section-${sectionIndex}`">
              <template #header><div class="collapse-header"><strong>楼层 {{ sectionIndex + 1 }} · {{ section.title || '未命名楼层' }}</strong><NTag v-if="isHeroSection(section)" size="small" type="warning" :bordered="false">首页顶部轮播</NTag><NTag size="small" :bordered="false">{{ displayStyleLabel(section.displayStyle) }}</NTag></div></template>
              <template #header-extra><NSpace @click.stop>
                <NButton quaternary circle size="small" :disabled="sectionIndex === 0 || isHeroSection(section) || (sectionIndex === 1 && !!form.sections[0] && isHeroSection(form.sections[0]))" @click="moveSection(sectionIndex, -1)"><template #icon><ArrowUp :size="15" /></template></NButton>
                <NButton quaternary circle size="small" :disabled="sectionIndex === form.sections.length - 1 || isHeroSection(section)" @click="moveSection(sectionIndex, 1)"><template #icon><ArrowDown :size="15" /></template></NButton>
                <NButton quaternary circle size="small" type="error" @click="removeSection(sectionIndex)"><template #icon><Trash2 :size="15" /></template></NButton>
              </NSpace></template>
              <NCard size="small" embedded class="section-card" :class="{ 'hero-section-card': isHeroSection(section) }">
                <NAlert v-if="isHeroSection(section)" type="warning" :bordered="false" class="hero-section-alert">按人工商品列表顺序轮播，使用每个商品的第一张图片；客户点击轮播图、标题或按钮后会进入对应商品详情页。最多配置 {{ HERO_CAROUSEL_MAX_ITEMS }} 个商品。</NAlert>
                <NGrid cols="1 m:2" responsive="screen" :x-gap="16">
                  <NFormItemGi label="楼层编码">
                    <div class="field-with-hint">
                      <NInput v-model:value="section.code" maxlength="64" placeholder="例如 whats_hot" :disabled="isHeroSection(section)" />
                      <small class="field-hint">以字母开头，只能包含字母、数字和下划线，最多 64 个字符；保存时转为小写。</small>
                    </div>
                  </NFormItemGi>
                  <NFormItemGi label="展示形态"><NSelect v-model:value="section.displayStyle" :options="HOME_RECOMMENDATION_DISPLAY_STYLE_OPTIONS" :disabled="isHeroSection(section)" /></NFormItemGi>
                  <NFormItemGi label="眉题"><NInput v-model:value="section.eyebrow" maxlength="80" /></NFormItemGi>
                  <NFormItemGi label="楼层标题"><NInput v-model:value="section.title" maxlength="120" /></NFormItemGi>
                  <NFormItemGi span="2" label="副标题"><NInput v-model:value="section.subtitle" maxlength="255" /></NFormItemGi>
                  <NFormItemGi label="桌面端列数"><NInputNumber v-model:value="section.desktopColumns" :min="2" :max="6" /></NFormItemGi>
                  <NFormItemGi label="移动端列数"><NInputNumber v-model:value="section.mobileColumns" :min="1" :max="2" /></NFormItemGi>
                  <NFormItemGi label="楼层展示上限"><NInputNumber v-model:value="section.itemLimit" :min="1" :max="isHeroSection(section) ? HERO_CAROUSEL_MAX_ITEMS : 24" /></NFormItemGi>
                  <NFormItemGi label="空楼层自动隐藏"><NSwitch v-model:value="section.hideWhenEmpty" /></NFormItemGi>
                  <NFormItemGi label="链接文案"><NInput v-model:value="section.linkLabel" maxlength="40" placeholder="查看全部" /></NFormItemGi>
                  <NFormItemGi label="跳转链接"><NInput v-model:value="section.linkUrl" maxlength="500" placeholder="/collections/shop 或 https://..." /></NFormItemGi>
                </NGrid>
                <div class="group-toolbar">
                  <div><strong>商品组</strong><span v-if="section.displayStyle !== 'TABS'">网格和轮播只能配置一个商品组。</span><span v-else>每个商品组对应一个首页页签。</span></div>
                  <NButton size="small" secondary :disabled="isHeroSection(section) || section.groups.length >= 8" @click="addGroup(section)"><template #icon><Plus :size="14" /></template>添加商品组</NButton>
                </div>
                <NCard v-for="(group, groupIndex) in section.groups" :key="`${sectionIndex}-${groupIndex}`" size="small" class="group-card">
                  <template #header><div class="group-title"><strong>商品组 {{ groupIndex + 1 }} · {{ group.title || group.code || '未命名' }}</strong><NTag size="small" :bordered="false" type="info">{{ strategyLabel(group.strategy) }}</NTag></div></template>
                  <template #header-extra><NSpace>
                    <NButton quaternary circle size="small" :disabled="isHeroSection(section) || groupIndex === 0" @click="move(section.groups, groupIndex, -1)"><template #icon><ArrowUp :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" :disabled="isHeroSection(section) || groupIndex === section.groups.length - 1" @click="move(section.groups, groupIndex, 1)"><template #icon><ArrowDown :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" type="error" :disabled="isHeroSection(section)" @click="removeGroup(section, groupIndex)"><template #icon><Trash2 :size="15" /></template></NButton>
                  </NSpace></template>
                  <NGrid cols="1 m:2 l:3" responsive="screen" :x-gap="16">
                    <NFormItemGi label="商品组编码">
                      <div class="field-with-hint">
                        <NInput v-model:value="group.code" maxlength="64" :disabled="isHeroSection(section)" />
                        <small class="field-hint">以字母开头，只能包含字母、数字和下划线，最多 64 个字符；保存时转为小写。</small>
                      </div>
                    </NFormItemGi>
                    <NFormItemGi label="页签标题"><NInput v-model:value="group.title" maxlength="80" :placeholder="section.displayStyle === 'TABS' ? '必填' : '可选'" /></NFormItemGi>
                    <NFormItemGi label="选品方式"><NSelect v-model:value="group.selectionMode" :options="HOME_RECOMMENDATION_SELECTION_MODE_OPTIONS" :disabled="isHeroSection(section)" /></NFormItemGi>
                    <NFormItemGi label="自动策略"><NSelect v-model:value="group.strategy" :options="HOME_RECOMMENDATION_STRATEGY_OPTIONS" :disabled="isHeroSection(section)" /></NFormItemGi>
                    <NFormItemGi label="商品数量"><NInputNumber v-model:value="group.itemLimit" :min="1" :max="isHeroSection(section) ? HERO_CAROUSEL_MAX_ITEMS : 24" /></NFormItemGi>
                    <NFormItemGi label="最低可售库存"><NInputNumber v-model:value="group.minimumStock" :min="1" /></NFormItemGi>
                    <NFormItemGi label="分类过滤"><NSelect v-model:value="group.categoryId" clearable filterable :options="categoryOptions" placeholder="不限分类" :disabled="isHeroSection(section)" /></NFormItemGi>
                    <NFormItemGi label="商品类型过滤"><NSelect v-model:value="group.productType" clearable filterable :options="productTypeOptions" placeholder="不限类型" :disabled="isHeroSection(section)" /></NFormItemGi>
                    <NFormItemGi label="标签过滤"><NSelect v-model:value="group.tagId" clearable filterable :options="tagOptions" placeholder="不限标签" :disabled="isHeroSection(section)" /></NFormItemGi>
                    <NFormItemGi v-if="group.strategy === 'NEW_ARRIVALS'" label="新品回溯天数"><NInputNumber v-model:value="group.lookbackDays" :min="1" :max="365" /></NFormItemGi>
                    <NFormItemGi label="不足时补位"><NSelect v-model:value="group.fallbackStrategy" :options="HOME_RECOMMENDATION_FALLBACK_OPTIONS" :disabled="isHeroSection(section)" /></NFormItemGi>
                  </NGrid>
                  <div v-if="group.selectionMode !== 'AUTO'" class="manual-products">
                    <div class="manual-heading">
                      <div><strong>人工商品（{{ group.items.length }}/{{ isHeroSection(section) ? HERO_CAROUSEL_MAX_ITEMS : 24 }}）</strong><span>{{ isHeroSection(section) ? '顺序即轮播顺序，商品第一张图片将作为轮播图。' : (group.selectionMode === 'MANUAL' ? '按下方顺序直接展示。' : '人工商品优先，空位由自动策略补齐。') }}</span></div>
                      <NSelect v-model:value="group.productToAdd" filterable remote clearable :disabled="group.items.length >= (isHeroSection(section) ? HERO_CAROUSEL_MAX_ITEMS : 24)" :loading="productSearchLoading" :options="productOptions" placeholder="搜索并添加在售商品" style="width: min(360px, 100%)" @search="searchProducts" @update:value="value => addSelectedProduct(group, value as number | null, isHeroSection(section) ? HERO_CAROUSEL_MAX_ITEMS : 24)" />
                    </div>
                    <NEmpty v-if="group.items.length === 0" size="small" description="尚未选择人工商品" />
                    <div v-else class="manual-list">
                      <div v-for="(item, itemIndex) in group.items" :key="item.productId" class="manual-item">
                        <div class="product-summary">
                          <NImage v-if="productImage(item.productId)" :src="productImage(item.productId)" width="48" height="60" object-fit="cover" preview-disabled />
                          <div v-else class="image-placeholder">#{{ item.productId }}</div>
                          <div><strong>{{ productLabel(item.productId) }}</strong><span>位置 {{ itemIndex + 1 }}</span></div>
                        </div>
                        <NInput v-model:value="item.customBadge" maxlength="30" show-count placeholder="自定义徽标（可选）" class="badge-input" />
                        <div class="pin-control"><span>置顶</span><NSwitch v-model:value="item.pinned" :disabled="group.selectionMode === 'MANUAL'" /></div>
                        <NSpace :wrap="false">
                          <NButton quaternary circle size="small" :disabled="itemIndex === 0" @click="move(group.items, itemIndex, -1)"><template #icon><ArrowUp :size="14" /></template></NButton>
                          <NButton quaternary circle size="small" :disabled="itemIndex === group.items.length - 1" @click="move(group.items, itemIndex, 1)"><template #icon><ArrowDown :size="14" /></template></NButton>
                          <NButton quaternary circle size="small" type="error" @click="group.items.splice(itemIndex, 1)"><template #icon><Trash2 :size="14" /></template></NButton>
                        </NSpace>
                      </div>
                    </div>
                  </div>
                </NCard>
              </NCard>
            </NCollapseItem>
          </NCollapse>
        </NForm>
        <template #footer><NSpace justify="end"><NButton @click="editorOpen = false">取消</NButton><NButton type="primary" :loading="saving" @click="save"><template #icon><Save :size="16" /></template>保存草稿</NButton></NSpace></template>
      </NDrawerContent>
    </NDrawer>

    <NModal v-model:show="previewOpen" preset="card" title="首页推荐实时预览" style="width: min(1100px, 96vw)" :bordered="false">
      <NSpin :show="previewLoading">
        <NAlert v-if="preview" type="info" :bordered="false" class="preview-meta">服务端实时解析 · 请求 ID：{{ preview.request_id }}<span v-if="preview.generated_at"> · 生成时间：{{ formatDate(preview.generated_at) }}</span></NAlert>
        <NEmpty v-if="!previewLoading && (!preview || (!preview.categories?.length && preview.sections.length === 0))" description="当前规则未解析出可展示内容" />
        <div v-else-if="preview" class="preview-sections">
          <section v-if="preview.categories?.length" class="preview-categories">
            <div class="preview-section-heading"><span>MADE FOR EVERY MOMENT</span><h2>首页分类展示</h2></div>
            <div class="preview-category-grid">
              <div v-for="category in preview.categories" :key="category.category_id" class="preview-category">
                <NImage :src="category.image_url" :alt="category.alt_text || category.name" object-fit="cover" lazy />
                <strong>{{ category.name }}</strong>
                <span>{{ category.code }}</span>
              </div>
            </div>
          </section>
          <section v-for="section in preview.sections" :key="section.code" class="preview-section" :class="{ 'hero-preview': section.code === HERO_CAROUSEL_CODE }">
            <div class="preview-section-heading"><span>{{ section.eyebrow }}</span><h2>{{ section.title }}</h2><p>{{ section.subtitle }}</p><NTag size="small" :bordered="false">{{ displayStyleLabel(section.display_style) }}</NTag></div>
            <div v-for="group in section.groups" :key="group.code" class="preview-group">
              <div class="preview-group-title"><strong>{{ group.title || group.code }}</strong><span>{{ strategyLabel(group.strategy) }} · {{ group.products.length }} 件</span></div>
              <div class="preview-products">
                <div v-for="product in group.products" :key="`${group.code}-${product.id}`" class="preview-product">
                  <NImage v-if="product.image_url" :src="product.image_url" object-fit="cover" lazy />
                  <div v-else class="preview-placeholder">暂无图片</div>
                  <div class="preview-product-copy"><NTag v-if="product.badge" size="small" type="warning" :bordered="false">{{ product.badge }}</NTag><strong>{{ product.name }}</strong><span>#{{ product.id }} · {{ product.price ? `$${product.price} USD` : '暂无价格' }} · 位置 {{ product.position }}</span></div>
                </div>
              </div>
            </div>
          </section>
        </div>
      </NSpin>
      <template #footer><NSpace justify="end"><NButton @click="previewOpen = false">关闭</NButton></NSpace></template>
    </NModal>
  </div>
</template>

<style scoped>
.page-shell { display: flex; flex-direction: column; gap: 18px; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.page-heading h1 { margin: 5px 0 6px; font-size: 28px; }
.page-heading p { margin: 0; color: #6b7280; }
.eyebrow { display: flex; align-items: center; gap: 6px; color: #7c3aed; font-weight: 600; font-size: 13px; }
.filter-card { margin-top: 2px; }
.pagination-bar { display: flex; justify-content: space-between; align-items: center; padding: 16px; color: #6b7280; border-top: 1px solid #efeff5; }
.drawer-alert, .editor-card, .preview-meta { margin-bottom: 18px; }
.datetime-input { width: 100%; height: 34px; padding: 0 10px; box-sizing: border-box; border: 1px solid #e0e0e6; border-radius: 3px; color: inherit; background: transparent; outline: none; }
.datetime-input:focus { border-color: #18a058; box-shadow: 0 0 0 2px rgb(24 160 88 / 12%); }
.section-toolbar, .group-toolbar, .manual-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.section-toolbar { margin: 24px 0 12px; }
.section-toolbar h2 { margin: 0 0 4px; font-size: 20px; }
.section-toolbar span, .group-toolbar span, .manual-heading span { display: block; color: #777; font-size: 13px; }
.category-config-section { padding: 0 2px 22px; border-bottom: 1px solid #e5e7eb; }
.category-toolbar { margin-top: 0; }
.category-editor-list { display: flex; flex-direction: column; gap: 10px; }
.category-editor-item { display: grid; grid-template-columns: 112px minmax(0, 1fr) auto; align-items: start; gap: 14px; padding: 12px 0; border-top: 1px solid #e5e7eb; }
.category-image-preview { width: 112px; aspect-ratio: .76; display: grid; place-items: center; overflow: hidden; color: #999; background: #f3f4f6; }
.category-image-preview :deep(.n-image), .category-image-preview :deep(img) { width: 100%; height: 100%; }
.category-editor-fields { display: flex; flex-direction: column; gap: 9px; min-width: 0; }
.category-editor-actions { display: flex; flex-direction: column; align-items: flex-end; gap: 10px; }
.collapse-header, .group-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.section-card { margin-bottom: 8px; }
.hero-section-card { border-color: rgba(240, 160, 32, .45); }
.hero-section-alert { margin-bottom: 16px; }
.group-toolbar { padding: 12px 0; border-top: 1px solid #e5e7eb; }
.group-card + .group-card { margin-top: 12px; }
.manual-products { padding-top: 14px; margin-top: 4px; border-top: 1px dashed #d1d5db; }
.field-with-hint { width: 100%; min-width: 0; }
.field-hint { display: block; margin-top: 5px; color: #8c8c8c; font-size: 12px; line-height: 1.4; }
.manual-heading { margin-bottom: 12px; }
.manual-list { display: flex; flex-direction: column; gap: 8px; }
.manual-item { display: grid; grid-template-columns: minmax(240px, 1fr) minmax(180px, 260px) auto auto; gap: 12px; align-items: center; padding: 9px; border: 1px solid #e5e7eb; border-radius: 8px; }
.product-summary { display: flex; align-items: center; gap: 10px; min-width: 0; }
.product-summary strong, .product-summary span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.product-summary span { color: #8b8b8b; font-size: 12px; margin-top: 3px; }
.image-placeholder { width: 48px; height: 60px; display: grid; place-items: center; flex: none; border-radius: 4px; color: #888; background: #f3f4f6; font-size: 11px; }
.pin-control { display: flex; align-items: center; gap: 6px; white-space: nowrap; color: #555; }
.preview-sections { display: flex; flex-direction: column; gap: 30px; max-height: 72vh; overflow: auto; padding-right: 4px; }
.preview-section { padding-bottom: 24px; border-bottom: 1px solid #e5e7eb; }
.preview-section-heading { text-align: center; margin-bottom: 18px; }
.preview-section-heading > span { color: #7c3aed; font-size: 12px; letter-spacing: .08em; }
.preview-section-heading h2 { margin: 5px 0; }
.preview-section-heading p { color: #777; margin: 0 0 8px; }
.preview-group + .preview-group { margin-top: 22px; }
.preview-group-title { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.preview-group-title span { color: #777; font-size: 13px; }
.preview-products { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.preview-category-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.preview-category { min-width: 0; background: #f7f7f8; }
.preview-category :deep(.n-image) { width: 100%; aspect-ratio: .76; display: block; }
.preview-category :deep(img) { width: 100%; height: 100%; }
.preview-category strong, .preview-category span { display: block; padding: 0 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-category strong { padding-top: 9px; }
.preview-category span { padding-top: 3px; padding-bottom: 10px; color: #777; font-size: 12px; }
.hero-preview .preview-section-heading { text-align: left; }
.hero-preview .preview-products { display: flex; overflow-x: auto; padding-bottom: 8px; }
.hero-preview .preview-product { min-width: min(360px, 72vw); }
.preview-product { min-width: 0; border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; background: #fff; }
.preview-product :deep(.n-image) { width: 100%; aspect-ratio: 3 / 4; display: block; }
.preview-product :deep(img) { width: 100%; height: 100%; }
.preview-placeholder { display: grid; place-items: center; aspect-ratio: 3 / 4; color: #999; background: #f3f4f6; }
.preview-product-copy { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 10px; }
.preview-product-copy strong { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-product-copy span { color: #777; font-size: 12px; }
:deep(.plan-title-cell strong), :deep(.plan-title-cell span), :deep(.date-cell span) { display: block; }
:deep(.plan-title-cell span), :deep(.date-cell span) { color: #777; font-size: 12px; margin-top: 3px; }
:deep(.table-actions) { display: flex; flex-wrap: wrap; gap: 6px; }
@media (max-width: 900px) { .category-editor-item { grid-template-columns: 96px minmax(0, 1fr); } .category-image-preview { width: 96px; } .category-editor-actions { grid-column: 2; flex-direction: row; justify-content: space-between; align-items: center; } .manual-item { grid-template-columns: 1fr auto; } .badge-input { grid-column: 1 / -1; grid-row: 2; } .preview-products, .preview-category-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .page-heading, .section-toolbar, .group-toolbar, .manual-heading { align-items: stretch; flex-direction: column; } .page-heading h1 { font-size: 23px; } .pagination-bar { align-items: flex-start; flex-direction: column; gap: 12px; } .category-editor-item { grid-template-columns: 72px minmax(0, 1fr); } .category-image-preview { width: 72px; } .manual-item { grid-template-columns: 1fr; } .badge-input { grid-column: auto; grid-row: auto; } .preview-products, .preview-category-grid { grid-template-columns: 1fr 1fr; gap: 8px; } }
</style>
