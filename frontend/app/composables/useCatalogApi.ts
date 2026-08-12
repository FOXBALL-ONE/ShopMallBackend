import type {
  CatalogAttribute,
  CatalogCategory,
  CatalogImage,
  CatalogMaterial,
  CatalogProduct,
  CatalogVariant,
  CollectionSlug,
} from '~/data/catalog'

export type CatalogAttributeDefinition = {
  id: number
  code: string
  name: string
  scope: 'PRODUCT' | 'VARIANT'
  value_type: 'STRING' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'ENUM'
  required: boolean
  filterable: boolean
  allowed_values: string[]
  max_length?: number | null
  display_order: number
  active: boolean
}

type RawProduct = {
  id: number
  product_type: string
  category_id?: number | null
  name: string
  status: string
  highlights?: string[] | null
  materials?: CatalogMaterial[] | null
  images?: CatalogImage[] | null
  attributes?: CatalogAttribute[] | null
  fit_sense?: string | null
  description?: string | null
  design_and_extras?: string[] | null
  care_instructions?: string[] | null
  tags?: string[] | null
  score?: number | null
  variants?: Array<{
    id: number
    sku: string
    size?: string | null
    color: string
    price: string | number
    currency: string
    warehouse_volume: number
    sales_volume?: number
    display_order?: number
    attributes?: CatalogAttribute[] | null
  }> | null
  created_at?: string | null
  updated_at?: string | null
}

type ProductListResponse = {
  list: RawProduct[]
  pagination?: { page: number; size: number; total_items: number; total_pages: number }
}
type ProductTypeResponse = { list: Array<{ id: number; code: string; name: string }> }
type DefinitionResponse = { list: CatalogAttributeDefinition[] }
type ProductCategoryResponse = { list: CatalogCategory[] }

function collectionSlugs(tags: string[]): CollectionSlug[] {
  const normalizedTags = tags.map(tag => tag.trim().toLocaleLowerCase())
  const collections = new Set<CollectionSlug>()
  const aliases: Array<[CollectionSlug, string[]]> = [
    ['lounge', ['lounge', '居家内衣']],
    ['swim', ['swim', '泳装内衣']],
    ['intimate', ['intimate', 'intimates', '情趣内衣']],
    ['new', ['new', 'new in', '新品']],
    ['sale', ['sale', 'discount', '折扣', '限时折扣']],
  ]
  aliases.forEach(([slug, names]) => {
    if (names.some(name => normalizedTags.includes(name))) collections.add(slug)
  })
  return [...collections]
}

function normalizeProduct(value: RawProduct): CatalogProduct {
  const variants: CatalogVariant[] = (value.variants ?? []).map(variant => ({
    id: Number(variant.id),
    sku: String(variant.sku),
    size: variant.size ?? null,
    color: String(variant.color),
    price: Number(variant.price).toFixed(2),
    currency: 'USD',
    warehouse_volume: Number(variant.warehouse_volume ?? 0),
    sales_volume: Number(variant.sales_volume ?? 0),
    display_order: Number(variant.display_order ?? 0),
    attributes: Array.isArray(variant.attributes) ? variant.attributes : [],
  }))
  const images = Array.isArray(value.images) ? [...value.images].sort((left, right) => Number(left.sort_order ?? 0) - Number(right.sort_order ?? 0)) : []
  const tags = Array.isArray(value.tags) ? value.tags : []
  const collections = collectionSlugs(tags)
  const prices = variants.map(variant => Number(variant.price)).filter(Number.isFinite)
  const colors = [...new Set(variants.map(variant => variant.color).filter(Boolean))]
  const product: CatalogProduct = {
    id: Number(value.id),
    name: value.name,
    color: colors.join(' / '),
    price: prices.length ? Math.min(...prices) : 0,
    warehouse_volume: variants.reduce((total, variant) => total + variant.warehouse_volume, 0),
    sales_volume: variants.reduce((total, variant) => total + variant.sales_volume, 0),
    status: value.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE',
    highlight: Array.isArray(value.highlights) ? value.highlights : [],
    images: images.map(image => image.url),
    image_details: images,
    fit_sense: value.fit_sense ?? null,
    description: value.description ?? '',
    design_and_extras: Array.isArray(value.design_and_extras) ? value.design_and_extras : [],
    care_instructions: Array.isArray(value.care_instructions) ? value.care_instructions : [],
    score: Number(value.score ?? 0),
    tags,
    created_at: value.created_at ?? '',
    updated_at: value.updated_at ?? value.created_at ?? '',
    product_type: value.product_type,
    category_id: value.category_id ?? null,
    attributes: Array.isArray(value.attributes) ? value.attributes : [],
    materials: Array.isArray(value.materials) ? value.materials : [],
    variants,
    collections,
    is_new: collections.includes('new'),
    is_sale: collections.includes('sale'),
    badge: collections.includes('sale') ? 'SALE' : collections.includes('new') ? 'NEW' : undefined,
  }
  return product
}

export function useCatalogApi() {
  const http = useHttp()

  async function listProducts(): Promise<CatalogProduct[]> {
    const first = await http.get<ProductListResponse>('/products', { page: 1, size: 100 })
    const totalPages = Math.max(1, Number(first.pagination?.total_pages ?? 1))
    const remaining = totalPages > 1
      ? await Promise.all(Array.from({ length: totalPages - 1 }, (_, index) => http.get<ProductListResponse>('/products', { page: index + 2, size: 100 })) )
      : []
    return [first, ...remaining].flatMap(response => response.list ?? []).map(normalizeProduct)
  }

  async function getProduct(id: number): Promise<CatalogProduct> {
    return normalizeProduct(await http.get<RawProduct>(`/products/${id}`))
  }

  async function listCategories(): Promise<CatalogCategory[]> {
    const response = await http.get<ProductCategoryResponse>('/product-categories')
    return [...(response.list ?? [])].sort((left, right) =>
      left.display_order - right.display_order || left.name.localeCompare(right.name)
    )
  }

  async function getDefinitions(productTypeCode: string): Promise<CatalogAttributeDefinition[]> {
    const typeResponse = await http.get<ProductTypeResponse>('/product-types')
    const productType = typeResponse.list.find(type => type.code === productTypeCode)
    if (!productType) return []
    const response = await http.get<DefinitionResponse>(`/product-types/${productType.id}/attributes`)
    return response.list ?? []
  }

  return { listProducts, getProduct, listCategories, getDefinitions }
}
