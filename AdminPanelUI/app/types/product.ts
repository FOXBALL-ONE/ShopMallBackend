export type ProductStatus = 'ACTIVE' | 'INACTIVE'
export type VariantStatus = 'ACTIVE' | 'INACTIVE'
export type AttributeScope = 'PRODUCT' | 'VARIANT'
export type AttributeValueType = 'STRING' | 'BOOLEAN' | 'INTEGER' | 'DECIMAL' | 'ENUM'
export type ProductSortBy = 'CREATED_AT' | 'UPDATED_AT' | 'NAME' | 'PRICE' | 'STOCK' | 'SALES'

export interface ProductType {
  id: number
  code: string
  name: string
  description?: string | null
  active: boolean
  displayOrder: number
}

export interface AttributeDefinition {
  id: number
  code: string
  name: string
  scope: AttributeScope
  valueType: AttributeValueType
  required: boolean
  filterable: boolean
  allowedValues: string[]
  maxLength?: number | null
  displayOrder: number
  active: boolean
  used: boolean
}

export interface ProductCategory {
  id: number
  code: string
  name: string
  description?: string | null
  parentId?: number | null
  displayOrder: number
  status: ProductStatus
}

export interface ProductAttribute {
  code: string
  value: string
}

export interface ProductMaterial {
  name: string
  percentage: string
}

export interface ProductImage {
  url: string
  altText?: string | null
  primary: boolean
  sortOrder?: number
}

export interface ProductVariant {
  id: number
  sku: string
  size?: string | null
  color: string
  price: string
  currency: 'USD'
  warehouseVolume: number
  salesVolume: number
  displayOrder: number
  status: VariantStatus
  optionSignature?: string
  attributes: ProductAttribute[]
}

export interface Product {
  id: number
  productType: string
  productTypeId: number
  categoryId?: number | null
  name: string
  status: ProductStatus
  deletedAt?: string | null
  highlights: string[]
  materials: ProductMaterial[]
  images: ProductImage[]
  attributes: ProductAttribute[]
  fitSense?: string | null
  description?: string | null
  designAndExtras: string[]
  careInstructions: string[]
  tagIds: number[]
  variants: ProductVariant[]
  createdAt?: string | null
  updatedAt?: string | null
}

export interface ProductVariantMutation {
  id?: number
  sku: string
  size?: string | null
  color: string
  price: string
  warehouseVolume: number
  status: VariantStatus
  displayOrder: number
  attributes: ProductAttribute[]
}

export interface ProductMutation {
  productTypeId: number
  categoryId?: number | null
  name: string
  status: ProductStatus
  highlights: string[]
  materials: ProductMaterial[]
  images: ProductImage[]
  attributes: ProductAttribute[]
  fitSense?: string | null
  description?: string | null
  designAndExtras: string[]
  careInstructions: string[]
  tagIds: number[]
  variants: ProductVariantMutation[]
}

export interface ProductListQuery {
  product_type?: string
  status?: ProductStatus
  deleted?: boolean
  keyword?: string
  low_stock?: boolean
  low_stock_threshold?: number
  sort_by?: ProductSortBy
  ascending?: boolean
  page: number
  size: number
}

export interface ProductPage {
  list: Product[]
  pagination: { page: number; size: number; totalItems: number; totalPages: number }
}

export interface ProductTypeMutation {
  code: string
  name: string
  description?: string | null
  active: boolean
  displayOrder: number
}

export interface AttributeDefinitionMutation {
  code: string
  name: string
  scope: AttributeScope
  valueType: AttributeValueType
  required: boolean
  filterable: boolean
  allowedValues: string[]
  maxLength?: number | null
  displayOrder: number
  active: boolean
}

export interface ProductCategoryMutation {
  code: string
  name: string
  description?: string | null
  parentId?: number | null
  displayOrder: number
  status: ProductStatus
}

export interface Tag {
  id: number
  name: string
  description?: string | null
  color?: string | null
  sortOrder: number
  active: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface TagMutation {
  name: string
  description?: string
  color?: string
  sortOrder: number
  active: boolean
}

export interface StockAdjustmentResponse {
  variantId: number
  adjustment: number
  warehouseVolume: number
}
