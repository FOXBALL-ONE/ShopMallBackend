import type {
  HomeRecommendationFormInput,
  HomeRecommendationListQuery,
  HomeRecommendationMutationResponse,
  HomeRecommendationPlanDetail,
  HomeRecommendationPlanListResponse,
  HomeRecommendationPreview,
} from '~/types/home-recommendation'

export const HOME_RECOMMENDATION_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已排期', value: 'SCHEDULED' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已下线', value: 'OFFLINE' },
  { label: '已过期', value: 'EXPIRED' },
  { label: '已归档', value: 'ARCHIVED' },
]

export const HOME_RECOMMENDATION_DISPLAY_STYLE_OPTIONS = [
  { label: '网格', value: 'GRID' },
  { label: '横向轮播', value: 'CAROUSEL' },
  { label: '页签', value: 'TABS' },
]

export const HOME_RECOMMENDATION_SELECTION_MODE_OPTIONS = [
  { label: '人工选品', value: 'MANUAL' },
  { label: '自动选品', value: 'AUTO' },
  { label: '人工置顶 + 自动补齐', value: 'HYBRID' },
]

export const HOME_RECOMMENDATION_STRATEGY_OPTIONS = [
  { label: '新品', value: 'NEW_ARRIVALS' },
  { label: '畅销', value: 'BEST_SELLERS' },
  { label: '高评分', value: 'HIGH_RATED' },
  { label: '编辑精选', value: 'EDITOR_PICKS' },
]

export const HOME_RECOMMENDATION_FALLBACK_OPTIONS = [
  { label: '不补位', value: 'NONE' },
  { label: '最新商品补位', value: 'LATEST' },
  { label: '畅销商品补位', value: 'BEST_SELLERS' },
]

export function useHomeRecommendationApi() {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put } = useHttp(adminApiBase)

  function formData(input: HomeRecommendationFormInput, expectedVersion?: number) {
    const data = new FormData()
    data.append('name', input.name.trim())
    data.append('effective_from', input.effectiveFrom)
    if (input.effectiveUntil) data.append('effective_until', input.effectiveUntil)
    data.append('fallback_enabled', String(input.fallbackEnabled))
    data.append('deduplicate_across_sections', String(input.deduplicateAcrossSections))
    data.append('categories', JSON.stringify(input.categories.map((category, categoryIndex) => ({
      category_id: category.categoryId,
      image_url: category.imageUrl.trim(),
      alt_text: category.altText.trim() || null,
      sort_order: categoryIndex,
    }))))
    data.append('sections', JSON.stringify(input.sections.map((section, sectionIndex) => ({
      code: section.code.trim().toLowerCase(),
      eyebrow: section.eyebrow.trim() || null,
      title: section.title.trim(),
      subtitle: section.subtitle.trim() || null,
      display_style: section.displayStyle,
      desktop_columns: section.desktopColumns,
      mobile_columns: section.mobileColumns,
      link_label: section.linkLabel.trim() || null,
      link_url: section.linkUrl.trim() || null,
      item_limit: section.itemLimit,
      hide_when_empty: section.hideWhenEmpty,
      sort_order: sectionIndex,
      groups: section.groups.map((group, groupIndex) => ({
        code: group.code.trim().toLowerCase(),
        title: group.title.trim() || null,
        selection_mode: group.selectionMode,
        strategy: group.strategy,
        item_limit: group.itemLimit,
        category_id: group.categoryId,
        product_type: group.productType.trim() || null,
        tag_id: group.tagId,
        lookback_days: group.lookbackDays,
        minimum_stock: group.minimumStock,
        fallback_strategy: group.fallbackStrategy,
        sort_order: groupIndex,
        items: group.selectionMode === 'AUTO' ? [] : group.items.map((item, itemIndex) => ({
          product_id: item.productId,
          pinned: item.pinned,
          custom_badge: item.customBadge.trim() || null,
          sort_order: itemIndex,
        })),
      })),
    }))))
    if (expectedVersion !== undefined) data.append('expected_version', String(expectedVersion))
    return data
  }

  const base = '/home-recommendations/plans'
  return {
    list(query: HomeRecommendationListQuery) {
      return get<HomeRecommendationPlanListResponse>(base, { ...query })
    },
    getOne(id: number) {
      return get<HomeRecommendationPlanDetail>(`${base}/${id}`)
    },
    create(input: HomeRecommendationFormInput) {
      return post<HomeRecommendationMutationResponse, FormData>(base, formData(input), { payloadMode: 'json' })
    },
    update(id: number, input: HomeRecommendationFormInput, expectedVersion: number) {
      return put<HomeRecommendationMutationResponse, FormData>(`${base}/${id}`, formData(input, expectedVersion), { payloadMode: 'json' })
    },
    copy(id: number, expectedVersion: number) {
      return post<HomeRecommendationMutationResponse>(`${base}/${id}/copy`, { expected_version: expectedVersion })
    },
    publish(id: number, expectedVersion: number) {
      return post<HomeRecommendationMutationResponse>(`${base}/${id}/publish`, { expected_version: expectedVersion })
    },
    offline(id: number, expectedVersion: number) {
      return post<HomeRecommendationMutationResponse>(`${base}/${id}/offline`, { expected_version: expectedVersion })
    },
    archive(id: number, expectedVersion: number) {
      return post<HomeRecommendationMutationResponse>(`${base}/${id}/archive`, { expected_version: expectedVersion })
    },
    preview(id: number, productLimitPerGroup = 12) {
      return get<HomeRecommendationPreview>(`${base}/${id}/preview`, { product_limit_per_group: productLimitPerGroup })
    },
  }
}
