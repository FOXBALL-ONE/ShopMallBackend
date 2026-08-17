import type { CatalogProduct } from '~/data/catalog'

export type HomeRecommendationDisplayStyle = 'GRID' | 'CAROUSEL' | 'TABS'
export type HomeRecommendationSelectionMode = 'MANUAL' | 'AUTO' | 'HYBRID'
export type HomeRecommendationStrategy = 'NEW_ARRIVALS' | 'BEST_SELLERS' | 'HIGH_RATED' | 'EDITOR_PICKS'

export interface HomeRecommendationCategory {
  id?: number | null
  category_id: number
  code: string
  name: string
  image_url: string
  alt_text?: string | null
}

export interface HomeRecommendationContext {
  request_id: string
  plan_id?: number | null
  section_code: string
  group_code: string
  strategy: HomeRecommendationStrategy
  position: number
}

export interface HomeRecommendationProduct extends CatalogProduct {
  recommendation_context: HomeRecommendationContext
}

export interface HomeRecommendationGroup {
  id?: number | null
  code: string
  title?: string | null
  selection_mode: HomeRecommendationSelectionMode
  strategy: HomeRecommendationStrategy
  products: HomeRecommendationProduct[]
}

export interface HomeRecommendationSection {
  id?: number | null
  code: string
  eyebrow?: string | null
  title: string
  subtitle?: string | null
  display_style: HomeRecommendationDisplayStyle
  desktop_columns: number
  mobile_columns: number
  link_label?: string | null
  link_url?: string | null
  groups: HomeRecommendationGroup[]
}

export interface HomeRecommendationResponse {
  plan_id?: number | null
  plan_version: number
  request_id: string
  generated_at: string
  expires_at: string
  fallback: boolean
  categories_configured: boolean
  categories: HomeRecommendationCategory[]
  sections: HomeRecommendationSection[]
}
