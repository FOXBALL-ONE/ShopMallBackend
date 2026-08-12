import type { RawProduct } from '~/composables/useCatalogApi'
import { normalizeCatalogProduct } from '~/composables/useCatalogApi'
import type {
  HomeRecommendationGroup,
  HomeRecommendationResponse,
  HomeRecommendationSection,
  HomeRecommendationStrategy,
} from '~/types/home-recommendation'

type RawRecommendationProduct = RawProduct & {
  badge?: string | null
  recommendation_context: {
    request_id: string
    plan_id?: number | null
    section_code: string
    group_code: string
    strategy: HomeRecommendationStrategy
    position: number
  }
}

type RawRecommendationResponse = Omit<HomeRecommendationResponse, 'sections'> & {
  sections?: Array<Omit<HomeRecommendationSection, 'groups'> & {
    groups?: Array<Omit<HomeRecommendationGroup, 'products'> & {
      products?: RawRecommendationProduct[] | null
    }> | null
  }> | null
}

export function useHomeRecommendationApi() {
  const http = useHttp()

  async function current(options: { sectionLimit?: number; productLimitPerGroup?: number } = {}): Promise<HomeRecommendationResponse> {
    const response = await http.get<RawRecommendationResponse>('/home/recommendations', {
      section_limit: options.sectionLimit ?? 10,
      product_limit_per_group: options.productLimitPerGroup,
    })
    return {
      ...response,
      sections: (response.sections ?? []).map(section => ({
        ...section,
        groups: (section.groups ?? []).map(group => ({
          ...group,
          products: (group.products ?? []).map(value => {
            const product = normalizeCatalogProduct(value)
            return {
              ...product,
              badge: value.badge?.trim() || product.badge,
              recommendation_context: value.recommendation_context,
            }
          }),
        })),
      })),
    }
  }

  return { current }
}
