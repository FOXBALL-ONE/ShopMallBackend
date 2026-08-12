<script setup lang="ts">
import { ArrowDown, ArrowUp, Plus, RefreshCw, Save, Search, Sparkles, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, FormInst, SelectOption, TagProps } from 'naive-ui'
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
const categories = ref<ProductCategory[]>([])
const productTypes = ref<ProductType[]>([])
const tags = ref<Tag[]>([])
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

const form = reactive<EditableForm>({
  name: '首页默认推荐', effectiveFrom: localDateTimeNow(), effectiveUntil: null, fallbackEnabled: true,
  deduplicateAcrossSections: true, sections: [defaultSection()],
})
const editorTitle = computed(() => editing.value ? `编辑推荐方案 #${editing.value.id}` : '新建首页推荐方案')
const publishedCount = computed(() => plans.value.filter(item => item.status === 'PUBLISHED').length)
const scheduledCount = computed(() => plans.value.filter(item => item.status === 'SCHEDULED').length)
const categoryOptions = computed<SelectOption[]>(() => categories.value.filter(item => item.status === 'ACTIVE').map(item => ({ label: `${item.name}（${item.code}）`, value: item.id })))
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
  form.fallbackEnabled = true; form.deduplicateAcrossSections = true; form.sections = [defaultSection()]
  editing.value = null; productOptions.value = []
}

function assignDetail(detail: HomeRecommendationPlanDetail) {
  form.name = detail.name
  form.effectiveFrom = datetimeInputValue(detail.effective_from)
  form.effectiveUntil = detail.effective_until ? datetimeInputValue(detail.effective_until) : null
  form.fallbackEnabled = detail.fallback_enabled
  form.deduplicateAcrossSections = detail.deduplicate_across_sections
  form.sections = detail.sections.slice().sort((a, b) => a.sort_order - b.sort_order).map(section => ({
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
  if (categoryResult.status === 'fulfilled') categories.value = categoryResult.value
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

async function searchProducts(keyword: string) {
  productSearchLoading.value = true
  try {
    const result = await productApi.listProducts({ keyword: keyword.trim() || undefined, status: 'ACTIVE', deleted: false, page: 0, size: 20 })
    result.list.forEach(product => { productCache[product.id] = product })
    productOptions.value = result.list.map(product => ({ label: `${product.name}（#${product.id}）`, value: product.id }))
  } catch (error) { message.error(`搜索商品失败：${errorMessage(error)}`) }
  finally { productSearchLoading.value = false }
}
function addSelectedProduct(group: EditableGroup, productId: number | null) {
  if (productId == null) return
  if (group.items.some(item => item.productId === productId)) { message.warning('该商品已在当前商品组中'); group.productToAdd = null; return }
  if (group.items.length >= 24) { message.warning('一个商品组最多配置 24 个人工商品'); group.productToAdd = null; return }
  group.items.push({ productId, pinned: group.selectionMode === 'HYBRID', customBadge: '' }); group.productToAdd = null
}
function productLabel(productId: number) { return productCache[productId] ? `${productCache[productId].name}（#${productId}）` : `商品 #${productId}` }
function productImage(productId: number) { return productCache[productId]?.images.find(image => image.primary)?.url || productCache[productId]?.images[0]?.url }

function validateForm() {
  if (!form.name.trim()) return '请输入方案名称'
  if (form.name.trim().length > 120) return '方案名称不能超过 120 个字符'
  if (!form.effectiveFrom) return '请选择生效时间'
  if (form.effectiveUntil && form.effectiveUntil <= form.effectiveFrom) return '失效时间必须晚于生效时间'
  if (form.sections.length < 1 || form.sections.length > 10) return '楼层数量必须在 1 到 10 之间'
  const sectionCodes = new Set<string>()
  for (let sectionIndex = 0; sectionIndex < form.sections.length; sectionIndex += 1) {
    const section = form.sections[sectionIndex]!
    const label = `第 ${sectionIndex + 1} 个楼层`
    const code = section.code.trim().toLowerCase()
    if (!/^[a-z][a-z0-9_]*$/.test(code)) return `${label}编码只能使用小写字母、数字和下划线，且必须以字母开头`
    if (sectionCodes.has(code)) return `楼层编码“${code}”重复`
    sectionCodes.add(code)
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
      if (!/^[a-z][a-z0-9_]*$/.test(groupCode)) return `${groupLabel}编码格式不正确`
      if (groupCodes.has(groupCode)) return `${label}商品组编码“${groupCode}”重复`
      groupCodes.add(groupCode)
      if (section.displayStyle === 'TABS' && !group.title.trim()) return `${groupLabel}在页签模式下必须填写标题`
      if (group.itemLimit < 1 || group.itemLimit > 24) return `${groupLabel}展示数量必须在 1 到 24 之间`
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

          <div class="section-toolbar"><div><h2>推荐楼层</h2><span>按顺序渲染到客户首页，最多 10 个楼层。</span></div><NButton type="primary" secondary @click="addSection"><template #icon><Plus :size="16" /></template>添加楼层</NButton></div>
          <NCollapse :default-expanded-names="form.sections.map((_, index) => `section-${index}`)">
            <NCollapseItem v-for="(section, sectionIndex) in form.sections" :key="`section-${sectionIndex}`" :name="`section-${sectionIndex}`">
              <template #header><div class="collapse-header"><strong>楼层 {{ sectionIndex + 1 }} · {{ section.title || '未命名楼层' }}</strong><NTag size="small" :bordered="false">{{ displayStyleLabel(section.displayStyle) }}</NTag></div></template>
              <template #header-extra><NSpace @click.stop>
                <NButton quaternary circle size="small" :disabled="sectionIndex === 0" @click="move(form.sections, sectionIndex, -1)"><template #icon><ArrowUp :size="15" /></template></NButton>
                <NButton quaternary circle size="small" :disabled="sectionIndex === form.sections.length - 1" @click="move(form.sections, sectionIndex, 1)"><template #icon><ArrowDown :size="15" /></template></NButton>
                <NButton quaternary circle size="small" type="error" @click="removeSection(sectionIndex)"><template #icon><Trash2 :size="15" /></template></NButton>
              </NSpace></template>
              <NCard size="small" embedded class="section-card">
                <NGrid cols="1 m:2" responsive="screen" :x-gap="16">
                  <NFormItemGi label="楼层编码"><NInput v-model:value="section.code" maxlength="64" placeholder="例如 whats_hot" /></NFormItemGi>
                  <NFormItemGi label="展示形态"><NSelect v-model:value="section.displayStyle" :options="HOME_RECOMMENDATION_DISPLAY_STYLE_OPTIONS" /></NFormItemGi>
                  <NFormItemGi label="眉题"><NInput v-model:value="section.eyebrow" maxlength="80" /></NFormItemGi>
                  <NFormItemGi label="楼层标题"><NInput v-model:value="section.title" maxlength="120" /></NFormItemGi>
                  <NFormItemGi span="2" label="副标题"><NInput v-model:value="section.subtitle" maxlength="255" /></NFormItemGi>
                  <NFormItemGi label="桌面端列数"><NInputNumber v-model:value="section.desktopColumns" :min="2" :max="6" /></NFormItemGi>
                  <NFormItemGi label="移动端列数"><NInputNumber v-model:value="section.mobileColumns" :min="1" :max="2" /></NFormItemGi>
                  <NFormItemGi label="楼层展示上限"><NInputNumber v-model:value="section.itemLimit" :min="1" :max="24" /></NFormItemGi>
                  <NFormItemGi label="空楼层自动隐藏"><NSwitch v-model:value="section.hideWhenEmpty" /></NFormItemGi>
                  <NFormItemGi label="链接文案"><NInput v-model:value="section.linkLabel" maxlength="40" placeholder="查看全部" /></NFormItemGi>
                  <NFormItemGi label="跳转链接"><NInput v-model:value="section.linkUrl" maxlength="500" placeholder="/collections/shop 或 https://..." /></NFormItemGi>
                </NGrid>
                <div class="group-toolbar">
                  <div><strong>商品组</strong><span v-if="section.displayStyle !== 'TABS'">网格和轮播只能配置一个商品组。</span><span v-else>每个商品组对应一个首页页签。</span></div>
                  <NButton size="small" secondary :disabled="section.groups.length >= 8" @click="addGroup(section)"><template #icon><Plus :size="14" /></template>添加商品组</NButton>
                </div>
                <NCard v-for="(group, groupIndex) in section.groups" :key="`${sectionIndex}-${groupIndex}`" size="small" class="group-card">
                  <template #header><div class="group-title"><strong>商品组 {{ groupIndex + 1 }} · {{ group.title || group.code || '未命名' }}</strong><NTag size="small" :bordered="false" type="info">{{ strategyLabel(group.strategy) }}</NTag></div></template>
                  <template #header-extra><NSpace>
                    <NButton quaternary circle size="small" :disabled="groupIndex === 0" @click="move(section.groups, groupIndex, -1)"><template #icon><ArrowUp :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" :disabled="groupIndex === section.groups.length - 1" @click="move(section.groups, groupIndex, 1)"><template #icon><ArrowDown :size="15" /></template></NButton>
                    <NButton quaternary circle size="small" type="error" @click="removeGroup(section, groupIndex)"><template #icon><Trash2 :size="15" /></template></NButton>
                  </NSpace></template>
                  <NGrid cols="1 m:2 l:3" responsive="screen" :x-gap="16">
                    <NFormItemGi label="商品组编码"><NInput v-model:value="group.code" maxlength="64" /></NFormItemGi>
                    <NFormItemGi label="页签标题"><NInput v-model:value="group.title" maxlength="80" :placeholder="section.displayStyle === 'TABS' ? '必填' : '可选'" /></NFormItemGi>
                    <NFormItemGi label="选品方式"><NSelect v-model:value="group.selectionMode" :options="HOME_RECOMMENDATION_SELECTION_MODE_OPTIONS" /></NFormItemGi>
                    <NFormItemGi label="自动策略"><NSelect v-model:value="group.strategy" :options="HOME_RECOMMENDATION_STRATEGY_OPTIONS" /></NFormItemGi>
                    <NFormItemGi label="商品数量"><NInputNumber v-model:value="group.itemLimit" :min="1" :max="24" /></NFormItemGi>
                    <NFormItemGi label="最低可售库存"><NInputNumber v-model:value="group.minimumStock" :min="1" /></NFormItemGi>
                    <NFormItemGi label="分类过滤"><NSelect v-model:value="group.categoryId" clearable filterable :options="categoryOptions" placeholder="不限分类" /></NFormItemGi>
                    <NFormItemGi label="商品类型过滤"><NSelect v-model:value="group.productType" clearable filterable :options="productTypeOptions" placeholder="不限类型" /></NFormItemGi>
                    <NFormItemGi label="标签过滤"><NSelect v-model:value="group.tagId" clearable filterable :options="tagOptions" placeholder="不限标签" /></NFormItemGi>
                    <NFormItemGi v-if="group.strategy === 'NEW_ARRIVALS'" label="新品回溯天数"><NInputNumber v-model:value="group.lookbackDays" :min="1" :max="365" /></NFormItemGi>
                    <NFormItemGi label="不足时补位"><NSelect v-model:value="group.fallbackStrategy" :options="HOME_RECOMMENDATION_FALLBACK_OPTIONS" /></NFormItemGi>
                  </NGrid>
                  <div v-if="group.selectionMode !== 'AUTO'" class="manual-products">
                    <div class="manual-heading">
                      <div><strong>人工商品</strong><span>{{ group.selectionMode === 'MANUAL' ? '按下方顺序直接展示。' : '人工商品优先，空位由自动策略补齐。' }}</span></div>
                      <NSelect v-model:value="group.productToAdd" filterable remote clearable :loading="productSearchLoading" :options="productOptions" placeholder="搜索并添加在售商品" style="width: min(360px, 100%)" @search="searchProducts" @update:value="value => addSelectedProduct(group, value as number | null)" />
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
        <NEmpty v-if="!previewLoading && (!preview || preview.sections.length === 0)" description="当前规则未解析出可展示商品" />
        <div v-else-if="preview" class="preview-sections">
          <section v-for="section in preview.sections" :key="section.code" class="preview-section">
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
.collapse-header, .group-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.section-card { margin-bottom: 8px; }
.group-toolbar { padding: 12px 0; border-top: 1px solid #e5e7eb; }
.group-card + .group-card { margin-top: 12px; }
.manual-products { padding-top: 14px; margin-top: 4px; border-top: 1px dashed #d1d5db; }
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
@media (max-width: 900px) { .manual-item { grid-template-columns: 1fr auto; } .badge-input { grid-column: 1 / -1; grid-row: 2; } .preview-products { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .page-heading, .section-toolbar, .group-toolbar, .manual-heading { align-items: stretch; flex-direction: column; } .page-heading h1 { font-size: 23px; } .pagination-bar { align-items: flex-start; flex-direction: column; gap: 12px; } .manual-item { grid-template-columns: 1fr; } .badge-input { grid-column: auto; grid-row: auto; } .preview-products { grid-template-columns: 1fr 1fr; gap: 8px; } }
</style>
