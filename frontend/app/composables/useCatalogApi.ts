import type { CatalogProduct, CollectionSlug, ProductType } from '~/data/catalog'

type CatalogTag = string | {
  name?: string | null
}

type MeasurementRange = {
  min?: number | null
  max?: number | null
}

type SizeRecommendation = {
  braSizes?: string[] | null
  bust?: MeasurementRange | null
  underbust?: MeasurementRange | null
  waist?: MeasurementRange | null
  hip?: MeasurementRange | null
  torso?: MeasurementRange | null
}

type CatalogApiProduct = {
  id: number
  product_type?: string | null
  name: string
  color: string
  price: number | string
  warehouse_volume: number
  sales_volume: number
  status: string
  highlight?: string[] | null
  images?: string[] | null
  fit_sense?: string | null
  description?: string | null
  design_and_extras?: string[] | null
  care_instructions?: string[] | null
  score?: number | null
  tags?: CatalogTag[] | null
  created_at?: string | null
  updated_at?: string | null
  top_size?: string | null
  top_size_recommendation?: string | SizeRecommendation | null
  bottom_size?: string | null
  bottom_size_recommendation?: string | SizeRecommendation | null
  size?: string | null
  size_recommendation?: string | SizeRecommendation | null
  support_level?: string | null
  coverage?: string | null
  torso_fit?: string | null
  neckline?: string | null
  back_style?: string | null
  tummy_control?: boolean | string | null
  removable_padding?: boolean | string | null
  length?: string | null
  silhouette?: string | null
  sleeve_type?: string | null
  fabric?: string | null
  style?: string | null
  sheer_level?: string | null
}

type CatalogListResponse = {
  list: CatalogApiProduct[]
}

const productTypes: ProductType[] = ['BIKINI', 'ONE_PIECE', 'DRESS', 'COVER_UP']

function isProductType(value: unknown): value is ProductType {
  return productTypes.includes(value as ProductType)
}

function stringList(value: string[] | null | undefined): string[] {
  return Array.isArray(value) ? value.filter(item => typeof item === 'string' && item.trim().length > 0) : []
}

function optionalString(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined
}

function booleanLabel(value: boolean | string | null | undefined): string | undefined {
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  return optionalString(value)
}

function rangeLabel(label: string, range: MeasurementRange | null | undefined): string | null {
  if (!range || (range.min == null && range.max == null)) return null
  if (range.min != null && range.max != null) return `${label} ${range.min}-${range.max}`
  return `${label} ${range.min ?? range.max}`
}

function recommendationLabel(value: string | SizeRecommendation | null | undefined): string | undefined {
  if (typeof value === 'string') return optionalString(value)
  if (!value || typeof value !== 'object') return undefined

  const labels = [
    Array.isArray(value.braSizes) && value.braSizes.length ? `Bra ${value.braSizes.join(', ')}` : null,
    rangeLabel('Bust', value.bust),
    rangeLabel('Underbust', value.underbust),
    rangeLabel('Waist', value.waist),
    rangeLabel('Hip', value.hip),
    rangeLabel('Torso', value.torso)
  ].filter((label): label is string => Boolean(label))

  return labels.length ? labels.join(' / ') : undefined
}

function collectionSlugs(productType: ProductType, tags: string[]): CollectionSlug[] {
  const normalizedTags = tags.map(tag => tag.trim().toLocaleLowerCase())
  const collections = new Set<CollectionSlug>()
  const aliases: Array<[CollectionSlug, string[]]> = [
    ['lounge', ['lounge', '居家内衣']],
    ['swim', ['swim', '泳装内衣']],
    ['intimate', ['intimate', 'intimates', '情趣内衣']],
    ['new', ['new', 'new in', '新品']],
    ['sale', ['sale', 'discount', '折扣', '限时折扣']]
  ]

  for (const [slug, names] of aliases) {
    if (names.some(name => normalizedTags.includes(name))) collections.add(slug)
  }

  if (productType === 'BIKINI') {
    collections.add('swim')
    collections.add('intimate')
  }
  if (productType === 'ONE_PIECE') collections.add('swim')
  if (productType === 'DRESS') {
    collections.add('lounge')
    collections.add('intimate')
  }
  if (productType === 'COVER_UP') {
    collections.add('lounge')
    collections.add('swim')
  }

  return [...collections]
}

function normalizeProduct(product: CatalogApiProduct): CatalogProduct {
  if (!isProductType(product.product_type)) throw new Error(`Unsupported product type for product ${product.id}`)
  const productType = product.product_type

  const tags = (product.tags ?? [])
    .map(tag => typeof tag === 'string' ? tag : tag.name)
    .filter((tag): tag is string => typeof tag === 'string' && tag.trim().length > 0)
  const collections = collectionSlugs(productType, tags)
  const isNew = collections.includes('new')
  const isSale = collections.includes('sale')

  return {
    id: Number(product.id),
    name: product.name,
    color: product.color,
    price: Number(product.price),
    warehouse_volume: Number(product.warehouse_volume),
    sales_volume: Number(product.sales_volume),
    status: product.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE',
    highlight: stringList(product.highlight),
    images: stringList(product.images),
    fit_sense: product.fit_sense ?? null,
    description: product.description ?? '',
    design_and_extras: stringList(product.design_and_extras),
    care_instructions: stringList(product.care_instructions),
    score: Number(product.score ?? 0),
    tags,
    created_at: product.created_at ?? '',
    updated_at: product.updated_at ?? product.created_at ?? '',
    product_type: productType,
    collections,
    is_new: isNew,
    is_sale: isSale,
    badge: isSale ? 'SALE' : isNew ? 'NEW' : undefined,
    top_size: optionalString(product.top_size),
    top_size_recommendation: recommendationLabel(product.top_size_recommendation),
    bottom_size: optionalString(product.bottom_size),
    bottom_size_recommendation: recommendationLabel(product.bottom_size_recommendation),
    size: optionalString(product.size),
    size_recommendation: recommendationLabel(product.size_recommendation),
    support_level: optionalString(product.support_level),
    coverage: optionalString(product.coverage),
    torso_fit: optionalString(product.torso_fit),
    neckline: optionalString(product.neckline),
    back_style: optionalString(product.back_style),
    tummy_control: booleanLabel(product.tummy_control),
    removable_padding: booleanLabel(product.removable_padding),
    length: optionalString(product.length),
    silhouette: optionalString(product.silhouette),
    sleeve_type: optionalString(product.sleeve_type),
    fabric: optionalString(product.fabric),
    style: optionalString(product.style),
    sheer_level: optionalString(product.sheer_level)
  }
}

export function useCatalogApi() {
  const http = useHttp()

  /**
   * 后端通过统一列表返回全部商品；每个条目的 product_type 是唯一的分类依据。
   * 不为泳装、连衣裙和罩衫分别请求接口，避免分类结果依赖接口路径而非响应数据。
   */
  async function listProducts(): Promise<CatalogProduct[]> {
    const response = await http.get<CatalogListResponse>('/products')
    return (response.list ?? [])
      .filter(product => isProductType(product.product_type))
      .map(product => normalizeProduct(product))
  }

  return {
    listProducts
  }
}
