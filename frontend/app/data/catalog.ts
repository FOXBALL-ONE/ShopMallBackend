/** 客户端商品展示模型，对应统一 Product API。 */
export type ProductType = string
export type CollectionSlug = 'shop' | 'lounge' | 'swim' | 'intimate' | 'new' | 'sale'

export type CatalogProduct = {
  id: number
  name: string
  color: string
  price: number
  warehouse_volume: number
  sales_volume: number
  status: 'ACTIVE' | 'INACTIVE'
  highlight: string[]
  images: string[]
  image_positions?: string[]
  fit_sense: string | null
  description: string
  design_and_extras: string[]
  care_instructions: string[]
  score: number
  tags: string[]
  created_at: string
  updated_at: string
  product_type: ProductType
  category_id?: number | null
  attributes?: CatalogAttribute[]
  materials?: CatalogMaterial[]
  image_details?: CatalogImage[]
  variants?: CatalogVariant[]
  collections: CollectionSlug[]
  is_new?: boolean
  is_sale?: boolean
  badge?: string
  compare_at_price?: number
}

export type CatalogAttribute = { code: string; value: string }
export type CatalogMaterial = { name: string; percentage: string }
export type CatalogImage = { url: string; alt_text?: string | null; is_primary: boolean; sort_order?: number }
export type CatalogVariant = {
  id: number
  sku: string
  size?: string | null
  color: string
  price: string
  currency: 'USD'
  warehouse_volume: number
  sales_volume: number
  display_order: number
  attributes: CatalogAttribute[]
}

export type CollectionMeta = {
  slug: CollectionSlug
  label: string
  englishLabel: string
  eyebrow: string
  title: string
  subtitle: string
  description: string
  image: string
  position: string
}

export const collectionNavigation: Array<Pick<CollectionMeta, 'slug' | 'label' | 'englishLabel'>> = [
  { slug: 'shop', label: '全部商品', englishLabel: 'Shop all' },
  { slug: 'lounge', label: '居家内衣', englishLabel: 'Lounge lingerie' },
  { slug: 'swim', label: '泳装内衣', englishLabel: 'Swim lingerie' },
  { slug: 'intimate', label: '情趣内衣', englishLabel: 'Intimates' },
  { slug: 'new', label: '新品', englishLabel: 'New in' },
  { slug: 'sale', label: '限时折扣', englishLabel: 'Sale' },
]

export const collectionMeta: Record<CollectionSlug, CollectionMeta> = {
  shop: {
    slug: 'shop',
    label: '全部商品',
    englishLabel: 'Shop all',
    eyebrow: 'THE PELISSA COLLECTION',
    title: 'Every layer,\nevery mood.',
    subtitle: 'Modern lingerie for the way you move through the day.',
    description: 'Discover soft essentials, sculpting swim, and after-dark silhouettes in one considered edit.',
    image: '/lingerie/hero-corset.jpg',
    position: 'center 34%',
  },
  lounge: {
    slug: 'lounge',
    label: '居家内衣',
    englishLabel: 'Lounge lingerie',
    eyebrow: 'THE LOUNGE EDIT',
    title: 'Soft starts\nhere.',
    subtitle: 'Second-skin layers for slow mornings and late-night rituals.',
    description: 'Ribbed bralettes, fluid slips, and easy robes designed to feel as good as they look.',
    image: '/lingerie/hero-soft.jpg',
    position: 'center 50%',
  },
  swim: {
    slug: 'swim',
    label: '泳装内衣',
    englishLabel: 'Swim lingerie',
    eyebrow: 'SUN, SALT, SKIN',
    title: 'Made for\nthe water.',
    subtitle: 'Sculpted one-pieces and airy layers with confidence built in.',
    description: 'Meet the Pelissa swim edit: supportive shapes, thoughtful coverage, and colors that catch the light.',
    image: '/lingerie/lace-green.jpg',
    position: 'center 42%',
  },
  intimate: {
    slug: 'intimate',
    label: '情趣内衣',
    englishLabel: 'Intimate lingerie',
    eyebrow: 'AFTER DARK',
    title: 'A little more\nsomething.',
    subtitle: 'Lace, satin, and sculpted lines for your own kind of night.',
    description: 'Make room for a little drama with sheer textures, soft shine, and details worth lingering over.',
    image: '/lingerie/hero-lace.jpg',
    position: 'center 43%',
  },
  new: {
    slug: 'new',
    label: '新品',
    englishLabel: 'New arrivals',
    eyebrow: 'JUST IN',
    title: 'Fresh from\nthe studio.',
    subtitle: 'New shapes and soft colors, ready for their first wear.',
    description: 'The latest Pelissa arrivals bring a little more ease, a little more edge, and a lot of good feeling.',
    image: '/lingerie/hero-corset.jpg',
    position: 'center 35%',
  },
  sale: {
    slug: 'sale',
    label: '限时折扣',
    englishLabel: 'Last chance',
    eyebrow: 'THE EDIT, LESS',
    title: 'Good things,\nbetter price.',
    subtitle: 'Limited-time prices on pieces you will keep reaching for.',
    description: 'A considered selection of Pelissa favorites, marked down while sizes last.',
    image: '/lingerie/lace-black.jpg',
    position: 'center 47%',
  },
}

export const productTypeLabels: Record<string, string> = {
  BIKINI: 'Bikini set',
  ONE_PIECE: 'One-piece suit',
  DRESS: 'Lingerie dress',
  COVER_UP: 'Cover-up',
}

export function formatPrice(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(value)
}

export function getCollection(slug: string | undefined): CollectionMeta {
  return collectionMeta[(slug as CollectionSlug) || 'shop'] || collectionMeta.shop
}

export function displayProductType(type: ProductType) {
  return productTypeLabels[type] ?? type.toLocaleLowerCase().replaceAll('_', ' ').replace(/\b\w/g, letter => letter.toLocaleUpperCase())
}
