export type HomeRecommendationStatus = 'DRAFT' | 'SCHEDULED' | 'PUBLISHED' | 'OFFLINE' | 'EXPIRED' | 'ARCHIVED'
export type HomeRecommendationDisplayStyle = 'GRID' | 'CAROUSEL' | 'TABS'
export type HomeRecommendationSelectionMode = 'MANUAL' | 'AUTO' | 'HYBRID'
export type HomeRecommendationStrategy = 'NEW_ARRIVALS' | 'BEST_SELLERS' | 'HIGH_RATED' | 'EDITOR_PICKS'
export type HomeRecommendationFallbackStrategy = 'NONE' | 'LATEST' | 'BEST_SELLERS'
export type HomeRecommendationSortBy = 'UPDATED_AT' | 'CREATED_AT' | 'EFFECTIVE_FROM' | 'NAME'
export type HomeRecommendationSortDirection = 'ASC' | 'DESC'

export interface HomeRecommendationListQuery {
  page: number
  size: number
  keyword?: string
  status?: HomeRecommendationStatus
  sort_by?: HomeRecommendationSortBy
  sort_direction?: HomeRecommendationSortDirection
}

export interface HomeRecommendationPlanListItem {
  id: number
  version: number
  name: string
  status: HomeRecommendationStatus
  channel: 'CUSTOMER_WEB'
  section_count: number
  effective_from: string
  effective_until?: string | null
  published_at?: string | null
  created_at?: string | null
  updated_at?: string | null
}

export interface HomeRecommendationPlanListResponse {
  items: HomeRecommendationPlanListItem[]
  page: number
  size: number
  total_elements: number
  total_pages: number
}

export interface HomeRecommendationItemDetail {
  id: number
  product_id: number
  pinned: boolean
  custom_badge?: string | null
  sort_order: number
}

export interface HomeRecommendationCategoryDetail {
  id: number
  category_id: number
  image_url: string
  alt_text?: string | null
  sort_order: number
}

export interface HomeRecommendationGroupDetail {
  id: number
  code: string
  title?: string | null
  selection_mode: HomeRecommendationSelectionMode
  strategy: HomeRecommendationStrategy
  item_limit: number
  category_id?: number | null
  product_type?: string | null
  tag_id?: number | null
  lookback_days?: number | null
  minimum_stock: number
  fallback_strategy: HomeRecommendationFallbackStrategy
  sort_order: number
  items: HomeRecommendationItemDetail[]
}

export interface HomeRecommendationSectionDetail {
  id: number
  code: string
  eyebrow?: string | null
  title: string
  subtitle?: string | null
  display_style: HomeRecommendationDisplayStyle
  desktop_columns: number
  mobile_columns: number
  link_label?: string | null
  link_url?: string | null
  item_limit: number
  hide_when_empty: boolean
  sort_order: number
  groups: HomeRecommendationGroupDetail[]
}

export interface HomeRecommendationPlanDetail {
  id: number
  version: number
  name: string
  status: HomeRecommendationStatus
  channel: 'CUSTOMER_WEB'
  effective_from: string
  effective_until?: string | null
  fallback_enabled: boolean
  deduplicate_across_sections: boolean
  created_by: number
  updated_by: number
  published_at?: string | null
  archived_at?: string | null
  created_at?: string | null
  updated_at?: string | null
  categories: HomeRecommendationCategoryDetail[]
  sections: HomeRecommendationSectionDetail[]
}

export interface HomeRecommendationCategoryInput {
  categoryId: number
  imageUrl: string
  altText: string
}

export interface HomeRecommendationItemInput {
  productId: number
  pinned: boolean
  customBadge: string
}

export interface HomeRecommendationGroupInput {
  code: string
  title: string
  selectionMode: HomeRecommendationSelectionMode
  strategy: HomeRecommendationStrategy
  itemLimit: number
  categoryId: number | null
  productType: string
  tagId: number | null
  lookbackDays: number | null
  minimumStock: number
  fallbackStrategy: HomeRecommendationFallbackStrategy
  items: HomeRecommendationItemInput[]
}

export interface HomeRecommendationSectionInput {
  code: string
  eyebrow: string
  title: string
  subtitle: string
  displayStyle: HomeRecommendationDisplayStyle
  desktopColumns: number
  mobileColumns: number
  linkLabel: string
  linkUrl: string
  itemLimit: number
  hideWhenEmpty: boolean
  groups: HomeRecommendationGroupInput[]
}

export interface HomeRecommendationFormInput {
  name: string
  effectiveFrom: string
  effectiveUntil: string | null
  fallbackEnabled: boolean
  deduplicateAcrossSections: boolean
  categories: HomeRecommendationCategoryInput[]
  sections: HomeRecommendationSectionInput[]
}

export interface HomeRecommendationMutationResponse {
  id: number
  version: number
  name?: string
  status: HomeRecommendationStatus
  published_at?: string | null
  archived_at?: string | null
}

export interface HomeRecommendationPreviewProduct {
  id: number
  name: string
  image_url?: string | null
  price?: string | null
  badge?: string | null
  position: number
}

export interface HomeRecommendationPreviewGroup {
  code: string
  title?: string | null
  strategy: HomeRecommendationStrategy
  products: HomeRecommendationPreviewProduct[]
}

export interface HomeRecommendationPreviewSection {
  code: string
  eyebrow?: string | null
  title: string
  subtitle?: string | null
  display_style: HomeRecommendationDisplayStyle
  groups: HomeRecommendationPreviewGroup[]
}

export interface HomeRecommendationPreview {
  plan_id?: number | null
  request_id: string
  generated_at?: string
  categories: Array<{
    category_id: number
    code: string
    name: string
    image_url: string
    alt_text?: string | null
  }>
  sections: HomeRecommendationPreviewSection[]
}
