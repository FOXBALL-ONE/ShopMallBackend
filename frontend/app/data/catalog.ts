/**
 * Local storefront data mirrors ProductController's JSON contract. It can be
 * replaced by the API response later without reshaping the page components.
 */

export type ProductType = 'BIKINI' | 'ONE_PIECE' | 'DRESS' | 'COVER_UP'
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
  collections: CollectionSlug[]
  is_new?: boolean
  is_sale?: boolean
  badge?: string
  compare_at_price?: number

  // BIKINI fields
  top_size?: string
  top_size_recommendation?: string
  bottom_size?: string
  bottom_size_recommendation?: string

  // ONE_PIECE, DRESS and COVER_UP fields
  size?: string
  size_recommendation?: string

  // ONE_PIECE fields
  support_level?: string
  coverage?: string
  torso_fit?: string
  neckline?: string
  back_style?: string
  tummy_control?: string
  removable_padding?: string

  // DRESS fields
  length?: string
  silhouette?: string
  sleeve_type?: string
  fabric?: string

  // COVER_UP fields
  style?: string
  sheer_level?: string
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
  { slug: 'lounge', label: '居家内衣', englishLabel: 'Lounge' },
  { slug: 'swim', label: '泳装内衣', englishLabel: 'Swim' },
  { slug: 'intimate', label: '情趣内衣', englishLabel: 'Intimates' },
  { slug: 'new', label: '新品', englishLabel: 'New in' },
  { slug: 'sale', label: '限时折扣', englishLabel: 'Sale' }
]

export const collectionMeta: Record<CollectionSlug, CollectionMeta> = {
  shop: {
    slug: 'shop',
    label: '全部商品',
    englishLabel: 'Shop all',
    eyebrow: 'THE LUNE COLLECTION',
    title: 'Every layer,\nevery mood.',
    subtitle: 'Modern lingerie for the way you move through the day.',
    description: 'Discover soft essentials, sculpting swim, and after-dark silhouettes in one considered edit.',
    image: '/lingerie/hero-corset.jpg',
    position: 'center 34%'
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
    position: 'center 50%'
  },
  swim: {
    slug: 'swim',
    label: '泳装内衣',
    englishLabel: 'Swim lingerie',
    eyebrow: 'SUN, SALT, SKIN',
    title: 'Made for\nthe water.',
    subtitle: 'Sculpted one-pieces and airy layers with confidence built in.',
    description: 'Meet the Lune swim edit: supportive shapes, thoughtful coverage, and colors that catch the light.',
    image: '/lingerie/lace-green.jpg',
    position: 'center 42%'
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
    position: 'center 43%'
  },
  new: {
    slug: 'new',
    label: '新品',
    englishLabel: 'New arrivals',
    eyebrow: 'JUST IN',
    title: 'Fresh from\nthe studio.',
    subtitle: 'New shapes and soft colors, ready for their first wear.',
    description: 'The latest Lune arrivals bring a little more ease, a little more edge, and a lot of good feeling.',
    image: '/lingerie/hero-corset.jpg',
    position: 'center 35%'
  },
  sale: {
    slug: 'sale',
    label: '限时折扣',
    englishLabel: 'Last chance',
    eyebrow: 'THE EDIT, LESS',
    title: 'Good things,\nbetter price.',
    subtitle: 'Limited-time prices on pieces you will keep reaching for.',
    description: 'A considered selection of Lune favorites, marked down while sizes last.',
    image: '/lingerie/lace-black.jpg',
    position: 'center 47%'
  }
}

export const productTypeLabels: Record<ProductType, string> = {
  BIKINI: 'Bikini set',
  ONE_PIECE: 'One-piece suit',
  DRESS: 'Lingerie dress',
  COVER_UP: 'Cover-up'
}

export const catalogProducts: CatalogProduct[] = [
  {
    id: 1,
    name: 'Afterglow Lace Triangle Set',
    color: 'Black cherry lace',
    price: 68,
    compare_at_price: 82,
    warehouse_volume: 24,
    sales_volume: 184,
    status: 'ACTIVE',
    highlight: ['Soft triangle support', 'Adjustable satin straps', 'Designed to mix and match'],
    images: ['/lingerie/hero-lace.jpg', '/lingerie/lace-black.jpg', '/lingerie/lace-texture.jpg'],
    image_positions: ['center 42%', 'center 45%', 'center 50%'],
    fit_sense: 'Light support with a barely-there feel.',
    description: 'A romantic lace set with a clean triangle shape and a little lift where you want it. Wear the pieces together or style them your own way.',
    design_and_extras: ['Scalloped stretch lace', 'Satin picot trim', 'Hook-and-eye back closure'],
    care_instructions: ['Hand wash cold', 'Do not bleach', 'Lay flat to dry'],
    score: 4.9,
    tags: ['lace', 'set', 'black', '情趣内衣', 'new'],
    created_at: '2026-08-01T10:00:00',
    updated_at: '2026-08-06T12:20:00',
    product_type: 'BIKINI',
    collections: ['intimate', 'new'],
    is_new: true,
    badge: 'NEW',
    top_size: 'S',
    top_size_recommendation: 'Fits 32A–34B',
    bottom_size: 'S',
    bottom_size_recommendation: 'Fits 26–28 in waist'
  },
  {
    id: 2,
    name: 'Velvet Hour Underwire Set',
    color: 'Merlot velvet',
    price: 54,
    compare_at_price: 72,
    warehouse_volume: 12,
    sales_volume: 267,
    status: 'ACTIVE',
    highlight: ['Contoured underwire', 'Velvet-touch finish', 'Medium coverage brief'],
    images: ['/lingerie/hero-corset.jpg', '/lingerie/bra-detail.jpg', '/lingerie/lace-black.jpg'],
    image_positions: ['center 33%', 'center 45%', 'center 45%'],
    fit_sense: 'A secure, lifted fit with softly lined cups.',
    description: 'Velvet Hour pairs a classic underwire silhouette with a rich merlot finish. The result is supportive, polished, and made for after-dark plans.',
    design_and_extras: ['Lightly lined cups', 'Gold-tone hardware', 'Cotton-lined gusset'],
    care_instructions: ['Hand wash cold', 'Use mild detergent', 'Do not tumble dry'],
    score: 4.7,
    tags: ['velvet', 'underwire', 'set', 'sale', '情趣内衣'],
    created_at: '2026-07-23T09:40:00',
    updated_at: '2026-08-05T16:10:00',
    product_type: 'BIKINI',
    collections: ['intimate', 'sale'],
    is_sale: true,
    badge: '30% OFF',
    top_size: 'M',
    top_size_recommendation: 'Fits 34C–36D',
    bottom_size: 'M',
    bottom_size_recommendation: 'Fits 29–31 in waist'
  },
  {
    id: 3,
    name: 'Second Skin Ribbed Bralette',
    color: 'Oat milk rib',
    price: 39,
    warehouse_volume: 43,
    sales_volume: 512,
    status: 'ACTIVE',
    highlight: ['Wire-free comfort', 'Seamless rib texture', 'Convertible straps'],
    images: ['/lingerie/hero-soft.jpg', '/lingerie/lace-white.jpg', '/lingerie/bra-detail.jpg'],
    image_positions: ['center 51%', 'center 42%', 'center 45%'],
    fit_sense: 'Gentle everyday support that moves with you.',
    description: 'Second Skin is the bralette you reach for on repeat. A softly ribbed knit, wide underband, and clean neckline keep it comfortable from morning to midnight.',
    design_and_extras: ['Double-layer rib knit', 'Wide comfort band', 'Removable padding'],
    care_instructions: ['Machine wash cold in a lingerie bag', 'Do not iron', 'Lay flat to dry'],
    score: 4.8,
    tags: ['bralette', 'ribbed', 'soft', '居家内衣', '情趣内衣'],
    created_at: '2026-07-19T08:25:00',
    updated_at: '2026-08-04T11:30:00',
    product_type: 'BIKINI',
    collections: ['lounge', 'intimate'],
    badge: 'BESTSELLER',
    top_size: 'M',
    top_size_recommendation: 'Fits 34B–36C',
    bottom_size: 'M',
    bottom_size_recommendation: 'Fits 29–31 in waist'
  },
  {
    id: 4,
    name: 'Tideglass Sculpt One-Piece',
    color: 'Sea glass green',
    price: 88,
    warehouse_volume: 18,
    sales_volume: 144,
    status: 'ACTIVE',
    highlight: ['Sculpting compression', 'Adjustable halter tie', 'UPF 50 sun protection'],
    images: ['/lingerie/lace-green.jpg', '/lingerie/hero-corset.jpg', '/lingerie/lace-texture.jpg'],
    image_positions: ['center 38%', 'center 35%', 'center 48%'],
    fit_sense: 'Firm smoothing through the waist with a flexible bust fit.',
    description: 'Tideglass is a sculpted one-piece that feels confident on the beach and elegant at the bar. A softly squared neckline keeps the silhouette modern.',
    design_and_extras: ['Recycled swim fabric', 'Fully lined', 'Adjustable back tie'],
    care_instructions: ['Rinse after swimming', 'Hand wash cold', 'Dry away from direct sun'],
    score: 4.8,
    tags: ['swim', 'one-piece', 'sculpting', '泳装内衣', 'new'],
    created_at: '2026-08-02T14:00:00',
    updated_at: '2026-08-07T09:15:00',
    product_type: 'ONE_PIECE',
    collections: ['swim', 'new'],
    is_new: true,
    badge: 'NEW',
    size: 'M',
    size_recommendation: 'Fits US 6–8',
    support_level: 'Medium support',
    coverage: 'Moderate coverage',
    torso_fit: 'Long-torso friendly',
    neckline: 'Square neck',
    back_style: 'Tie-back',
    tummy_control: 'Medium smoothing',
    removable_padding: 'Removable cups'
  },
  {
    id: 5,
    name: 'Midnight Plunge Suit',
    color: 'Ink black matte',
    price: 76,
    compare_at_price: 94,
    warehouse_volume: 9,
    sales_volume: 201,
    status: 'ACTIVE',
    highlight: ['Deep plunge neckline', 'Adjustable shoulder straps', 'Medium-high support'],
    images: ['/lingerie/lace-black.jpg', '/lingerie/hero-lace.jpg', '/lingerie/bra-detail.jpg'],
    image_positions: ['center 44%', 'center 43%', 'center 44%'],
    fit_sense: 'A close fit with smoothing through the core.',
    description: 'Midnight Plunge brings a little drama to a classic black suit. The low neckline is balanced by supportive construction and a clean, high-cut leg.',
    design_and_extras: ['Double-lined front', 'Soft brushed lining', 'Low scoop back'],
    care_instructions: ['Rinse in cool water', 'Hand wash cold', 'Avoid rough surfaces'],
    score: 4.6,
    tags: ['swim', 'black', 'plunge', 'sale', '泳装内衣'],
    created_at: '2026-07-28T13:05:00',
    updated_at: '2026-08-03T18:00:00',
    product_type: 'ONE_PIECE',
    collections: ['swim', 'sale'],
    is_sale: true,
    badge: '20% OFF',
    size: 'S',
    size_recommendation: 'Fits US 2–4',
    support_level: 'High support',
    coverage: 'Moderate coverage',
    torso_fit: 'True to size',
    neckline: 'Plunge',
    back_style: 'Scoop back',
    tummy_control: 'Firm smoothing',
    removable_padding: 'Removable cups'
  },
  {
    id: 6,
    name: 'Seabloom Halter Suit',
    color: 'Petal pink floral',
    price: 82,
    warehouse_volume: 22,
    sales_volume: 119,
    status: 'ACTIVE',
    highlight: ['Halter neckline', 'Soft floral print', 'Adjustable waist tie'],
    images: ['/lingerie/lace-white.jpg', '/lingerie/lace-green.jpg', '/lingerie/hero-soft.jpg'],
    image_positions: ['center 44%', 'center 41%', 'center 49%'],
    fit_sense: 'Flexible support with a gently defined waist.',
    description: 'A soft floral print, a flattering halter, and a tie waist make Seabloom the suit for long lunches and longer swims.',
    design_and_extras: ['Recycled stretch fabric', 'Removable soft cups', 'Moderate leg line'],
    care_instructions: ['Rinse after each wear', 'Hand wash cold', 'Lay flat to dry'],
    score: 4.7,
    tags: ['swim', 'floral', 'halter', '泳装内衣'],
    created_at: '2026-07-16T10:30:00',
    updated_at: '2026-07-30T15:45:00',
    product_type: 'ONE_PIECE',
    collections: ['swim'],
    size: 'M',
    size_recommendation: 'Fits US 6–8',
    support_level: 'Medium support',
    coverage: 'Fuller coverage',
    torso_fit: 'True to size',
    neckline: 'Halter',
    back_style: 'Open back',
    tummy_control: 'Light smoothing',
    removable_padding: 'Removable cups'
  },
  {
    id: 7,
    name: 'Cloudline Slip Dress',
    color: 'Ivory satin',
    price: 72,
    warehouse_volume: 26,
    sales_volume: 334,
    status: 'ACTIVE',
    highlight: ['Fluid satin drape', 'Adjustable spaghetti straps', 'Bias-cut hem'],
    images: ['/lingerie/lace-white.jpg', '/lingerie/hero-soft.jpg', '/lingerie/lace-texture.jpg'],
    image_positions: ['center 45%', 'center 48%', 'center 46%'],
    fit_sense: 'Easy, fluid fit that skims rather than clings.',
    description: 'Cloudline is cut on the bias for a soft, liquid drape. Layer it over your favorite set or let it stand alone.',
    design_and_extras: ['Glossy satin finish', 'Lace-trim neckline', 'Adjustable straps'],
    care_instructions: ['Hand wash cold', 'Do not wring', 'Hang to dry'],
    score: 4.9,
    tags: ['slip', 'satin', 'dress', '居家内衣', '情趣内衣', 'new'],
    created_at: '2026-08-03T11:20:00',
    updated_at: '2026-08-06T10:10:00',
    product_type: 'DRESS',
    collections: ['lounge', 'intimate', 'new'],
    is_new: true,
    badge: 'NEW',
    size: 'M',
    size_recommendation: 'Fits US 6–8',
    length: 'MIDI',
    silhouette: 'SLIP',
    neckline: 'V_NECK',
    sleeve_type: 'SLEEVELESS',
    fabric: '100% recycled satin'
  },
  {
    id: 8,
    name: 'Sunday Soft Knit Dress',
    color: 'Mushroom heather',
    price: 64,
    warehouse_volume: 31,
    sales_volume: 298,
    status: 'ACTIVE',
    highlight: ['Soft knit jersey', 'Relaxed column shape', 'Side slit detail'],
    images: ['/lingerie/hero-soft.jpg', '/lingerie/lace-white.jpg', '/lingerie/bra-detail.jpg'],
    image_positions: ['center 49%', 'center 43%', 'center 46%'],
    fit_sense: 'Relaxed through the body with a clean, easy line.',
    description: 'Sunday Soft is the dress you pull on when comfort is the whole point. A breathable knit and side slit keep it polished but never precious.',
    design_and_extras: ['Stretch rib binding', 'Low scoop neck', 'Machine-washable knit'],
    care_instructions: ['Machine wash cold', 'Wash with like colors', 'Lay flat to dry'],
    score: 4.6,
    tags: ['knit', 'dress', 'soft', '居家内衣'],
    created_at: '2026-07-11T12:00:00',
    updated_at: '2026-07-26T14:40:00',
    product_type: 'DRESS',
    collections: ['lounge'],
    size: 'M',
    size_recommendation: 'Fits US 6–8',
    length: 'MAXI',
    silhouette: 'SHIFT',
    neckline: 'ROUND',
    sleeve_type: 'SLEEVELESS',
    fabric: 'Modal-blend knit'
  },
  {
    id: 9,
    name: 'Moonlight Cowl Dress',
    color: 'Plum cowl satin',
    price: 58,
    compare_at_price: 76,
    warehouse_volume: 14,
    sales_volume: 163,
    status: 'ACTIVE',
    highlight: ['Cowl neckline', 'Adjustable cross-back', 'Soft stretch satin'],
    images: ['/lingerie/lace-black.jpg', '/lingerie/lace-green.jpg', '/lingerie/hero-lace.jpg'],
    image_positions: ['center 46%', 'center 43%', 'center 44%'],
    fit_sense: 'Skims the body with a little stretch for movement.',
    description: 'Moonlight is all about the details: a fluid cowl neckline, a cross-back, and a deep plum color that catches every low light.',
    design_and_extras: ['Cowl neckline', 'Cross-back straps', 'Lace-inset hem'],
    care_instructions: ['Hand wash cold', 'Do not bleach', 'Hang to dry'],
    score: 4.7,
    tags: ['cowl', 'satin', 'dress', 'sale', '情趣内衣'],
    created_at: '2026-07-05T09:15:00',
    updated_at: '2026-07-25T17:20:00',
    product_type: 'DRESS',
    collections: ['intimate', 'sale'],
    is_sale: true,
    badge: '25% OFF',
    size: 'S',
    size_recommendation: 'Fits US 2–4',
    length: 'MINI',
    silhouette: 'SLIP',
    neckline: 'SCOOP',
    sleeve_type: 'SLEEVELESS',
    fabric: 'Stretch satin'
  },
  {
    id: 10,
    name: 'Satin Tie Robe',
    color: 'Rosewood satin',
    price: 78,
    warehouse_volume: 15,
    sales_volume: 251,
    status: 'ACTIVE',
    highlight: ['Wrap-front closure', 'Wide removable belt', 'Roomy sleeves'],
    images: ['/lingerie/hero-corset.jpg', '/lingerie/hero-soft.jpg', '/lingerie/lace-white.jpg'],
    image_positions: ['center 31%', 'center 48%', 'center 42%'],
    fit_sense: 'Relaxed fit with an adjustable wrap waist.',
    description: 'The Satin Tie Robe is the finishing layer for a slow morning. A fluid drape and wide tie make it easy to wear open, closed, or over swim.',
    design_and_extras: ['Wide satin tie', 'Dropped shoulder', 'Inside tie for security'],
    care_instructions: ['Hand wash cold', 'Do not wring', 'Steam on low'],
    score: 4.8,
    tags: ['robe', 'satin', 'cover-up', '居家内衣', '情趣内衣'],
    created_at: '2026-07-14T07:45:00',
    updated_at: '2026-07-29T13:10:00',
    product_type: 'COVER_UP',
    collections: ['lounge', 'intimate'],
    size: 'ONE_SIZE',
    size_recommendation: 'Fits XS–XL',
    style: 'ROBE',
    sheer_level: 'OPAQUE',
    fabric: 'Satin charmeuse'
  },
  {
    id: 11,
    name: 'Sheer Halo Kimono',
    color: 'Black sheer mesh',
    price: 61,
    compare_at_price: 79,
    warehouse_volume: 17,
    sales_volume: 187,
    status: 'ACTIVE',
    highlight: ['Airy sheer mesh', 'Wide kimono sleeve', 'Contrast satin edging'],
    images: ['/lingerie/lace-texture.jpg', '/lingerie/lace-black.jpg', '/lingerie/hero-lace.jpg'],
    image_positions: ['center 47%', 'center 45%', 'center 43%'],
    fit_sense: 'Loose, floaty fit with a soft open front.',
    description: 'Sheer Halo turns any set into a look. The open-front kimono shape is finished with a satin edge for just enough polish.',
    design_and_extras: ['Open front', 'Satin piping', 'Lightweight mesh'],
    care_instructions: ['Hand wash cold', 'Do not iron directly', 'Hang to dry'],
    score: 4.5,
    tags: ['kimono', 'mesh', 'cover-up', 'new', '情趣内衣', '泳装内衣'],
    created_at: '2026-08-04T15:00:00',
    updated_at: '2026-08-07T08:50:00',
    product_type: 'COVER_UP',
    collections: ['intimate', 'swim', 'new'],
    is_new: true,
    badge: 'NEW',
    size: 'ONE_SIZE',
    size_recommendation: 'Fits XS–XL',
    style: 'KIMONO',
    sheer_level: 'SHEER',
    fabric: 'Recycled mesh'
  },
  {
    id: 12,
    name: 'Shoreline Gauze Wrap',
    color: 'Sand washed gauze',
    price: 49,
    warehouse_volume: 29,
    sales_volume: 226,
    status: 'ACTIVE',
    highlight: ['Breathable cotton gauze', 'Adjustable wrap tie', 'Beach-to-dinner ease'],
    images: ['/lingerie/lace-white.jpg', '/lingerie/lace-green.jpg', '/lingerie/hero-soft.jpg'],
    image_positions: ['center 42%', 'center 40%', 'center 50%'],
    fit_sense: 'Relaxed, airy fit with an adjustable waist.',
    description: 'Shoreline is made for warm air and bare feet. Lightweight gauze gives it a lived-in softness that works over swim or sleepwear.',
    design_and_extras: ['Cotton gauze texture', 'Wide wrap belt', 'Side seam pockets'],
    care_instructions: ['Machine wash cold', 'Wash with like colors', 'Tumble dry low'],
    score: 4.6,
    tags: ['wrap', 'gauze', 'cover-up', '泳装内衣', '居家内衣'],
    created_at: '2026-07-08T10:10:00',
    updated_at: '2026-07-24T12:35:00',
    product_type: 'COVER_UP',
    collections: ['swim', 'lounge'],
    size: 'ONE_SIZE',
    size_recommendation: 'Fits XS–XL',
    style: 'WRAP',
    sheer_level: 'SEMI_SHEER',
    fabric: 'Cotton gauze'
  }
]

export function formatPrice(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    maximumFractionDigits: 0
  }).format(value)
}

export function getCollection(slug: string | undefined): CollectionMeta {
  return collectionMeta[(slug as CollectionSlug) || 'shop'] || collectionMeta.shop
}

export function getProductById(id: number) {
  return catalogProducts.find(product => product.id === id)
}

export function productsForCollection(slug: CollectionSlug) {
  if (slug === 'shop') return catalogProducts
  return catalogProducts.filter(product => product.collections.includes(slug))
}

export function displayProductType(type: ProductType) {
  return productTypeLabels[type]
}
