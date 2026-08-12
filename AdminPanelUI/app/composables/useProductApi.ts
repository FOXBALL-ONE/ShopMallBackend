import type {
  AttributeDefinition,
  AttributeDefinitionMutation,
  Product,
  ProductCategory,
  ProductCategoryMutation,
  ProductListQuery,
  ProductMutation,
  ProductPage,
  ProductStatus,
  ProductType,
  ProductTypeMutation,
  ProductVariant,
  ProductVariantMutation,
  StockAdjustmentResponse,
  Tag,
  TagMutation,
  VariantStatus,
} from '~/types/product'

type RawRecord = Record<string, any>

function normalizeVariant(value: RawRecord): ProductVariant {
  return {
    id: Number(value.id),
    sku: String(value.sku ?? ''),
    size: value.size ?? null,
    color: String(value.color ?? ''),
    price: String(value.price ?? '0.00'),
    currency: 'USD',
    warehouseVolume: Number(value.warehouse_volume ?? 0),
    salesVolume: Number(value.sales_volume ?? 0),
    displayOrder: Number(value.display_order ?? 0),
    status: value.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE',
    optionSignature: value.option_signature,
    attributes: Array.isArray(value.attributes) ? value.attributes : [],
  }
}

function normalizeProduct(value: RawRecord): Product {
  return {
    id: Number(value.id),
    productType: String(value.product_type ?? ''),
    productTypeId: Number(value.product_type_id),
    categoryId: value.category_id == null ? null : Number(value.category_id),
    name: String(value.name ?? ''),
    status: value.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE',
    deletedAt: value.deleted_at ?? null,
    highlights: Array.isArray(value.highlights) ? value.highlights : [],
    materials: Array.isArray(value.materials)
      ? value.materials.map((item: RawRecord) => ({ name: String(item.name ?? ''), percentage: String(item.percentage ?? '0') }))
      : [],
    images: Array.isArray(value.images)
      ? value.images.map((item: RawRecord) => ({ url: String(item.url ?? ''), altText: item.alt_text ?? null, primary: item.is_primary === true, sortOrder: Number(item.sort_order ?? 0) }))
      : [],
    attributes: Array.isArray(value.attributes) ? value.attributes : [],
    fitSense: value.fit_sense ?? null,
    description: value.description ?? null,
    designAndExtras: Array.isArray(value.design_and_extras) ? value.design_and_extras : [],
    careInstructions: Array.isArray(value.care_instructions) ? value.care_instructions : [],
    tagIds: Array.isArray(value.tag_ids) ? value.tag_ids.map(Number) : [],
    variants: Array.isArray(value.variants) ? value.variants.map(normalizeVariant) : [],
    createdAt: value.created_at ?? null,
    updatedAt: value.updated_at ?? null,
  }
}

function normalizeType(value: RawRecord): ProductType {
  return {
    id: Number(value.id),
    code: String(value.code ?? ''),
    name: String(value.name ?? ''),
    description: value.description ?? null,
    active: value.active !== false,
    displayOrder: Number(value.display_order ?? 0),
  }
}

function normalizeDefinition(value: RawRecord): AttributeDefinition {
  return {
    id: Number(value.id),
    code: String(value.code ?? ''),
    name: String(value.name ?? ''),
    scope: value.scope,
    valueType: value.value_type,
    required: value.required === true,
    filterable: value.filterable === true,
    allowedValues: Array.isArray(value.allowed_values) ? value.allowed_values : [],
    maxLength: value.max_length ?? null,
    displayOrder: Number(value.display_order ?? 0),
    active: value.active !== false,
    used: value.used === true,
  }
}

function normalizeCategory(value: RawRecord): ProductCategory {
  return {
    id: Number(value.id),
    code: String(value.code ?? ''),
    name: String(value.name ?? ''),
    description: value.description ?? null,
    parentId: value.parent_id == null ? null : Number(value.parent_id),
    displayOrder: Number(value.display_order ?? 0),
    status: value.status === 'ACTIVE' ? 'ACTIVE' : 'INACTIVE',
  }
}

function normalizeTag(value: RawRecord): Tag {
  return {
    id: Number(value.id),
    name: String(value.name ?? ''),
    description: value.description ?? null,
    color: value.color ?? null,
    sortOrder: Number(value.sort_order ?? 0),
    active: value.active !== false,
    createdAt: value.created_at ?? null,
    updatedAt: value.updated_at ?? null,
  }
}

function productFormData(input: ProductMutation, includeType: boolean, includeVariants: boolean): FormData {
  const data = new FormData()
  if (includeType) data.append('product_type_id', String(input.productTypeId))
  if (input.categoryId != null) data.append('category_id', String(input.categoryId))
  data.append('name', input.name)
  data.append('status', input.status)
  data.append('highlights', JSON.stringify(input.highlights))
  data.append('materials', JSON.stringify(input.materials))
  data.append('attributes', JSON.stringify(input.attributes))
  data.append('images', JSON.stringify(input.images))
  if (input.fitSense?.trim()) data.append('fit_sense', input.fitSense.trim())
  if (input.description?.trim()) data.append('description', input.description.trim())
  data.append('design_and_extras', JSON.stringify(input.designAndExtras))
  data.append('care_instructions', JSON.stringify(input.careInstructions))
  data.append('tag_ids', JSON.stringify(input.tagIds))
  if (includeVariants) data.append('variants', JSON.stringify(input.variants))
  return data
}

function variantFormData(input: ProductVariantMutation): FormData {
  const data = new FormData()
  data.append('sku', input.sku)
  if (input.size?.trim()) data.append('size', input.size.trim())
  data.append('color', input.color)
  data.append('price', input.price)
  data.append('warehouse_volume', String(input.warehouseVolume))
  data.append('status', input.status)
  data.append('display_order', String(input.displayOrder))
  data.append('attributes', JSON.stringify(input.attributes))
  return data
}

export const useProductApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put, patch, delete: del } = useHttp(adminApiBase)

  async function listProducts(query: ProductListQuery): Promise<ProductPage> {
    const data = await get<RawRecord>('/products', { ...query })
    return {
      list: Array.isArray(data.list) ? data.list.map(normalizeProduct) : [],
      pagination: {
        page: Number(data.pagination?.page ?? query.page),
        size: Number(data.pagination?.size ?? query.size),
        totalItems: Number(data.pagination?.total_items ?? 0),
        totalPages: Number(data.pagination?.total_pages ?? 0),
      },
    }
  }

  const getProduct = (id: number) => get<RawRecord>(`/products/${id}`).then(normalizeProduct)
  const listProductTypes = (): Promise<ProductType[]> => get<RawRecord>('/product-types').then(data => (data.list ?? []).map(normalizeType))
  const getProductTypeDefinitions = (typeId: number): Promise<AttributeDefinition[]> => get<RawRecord>(`/product-types/${typeId}/attributes`).then(data => (data.list ?? []).map(normalizeDefinition))
  const listCategories = (): Promise<ProductCategory[]> => get<RawRecord>('/product-categories').then(data => (data.list ?? []).map(normalizeCategory))

  const createProduct = (input: ProductMutation) => post<{ id: number }, FormData>('/products', productFormData(input, true, true), { payloadMode: 'json' })
  const updateProduct = (id: number, input: ProductMutation) => put<{ id: number }, FormData>(`/products/${id}`, productFormData(input, false, true), { payloadMode: 'json' })
  const createVariant = (productId: number, input: ProductVariantMutation) => post<RawRecord, FormData>(`/products/${productId}/variants`, variantFormData(input), { payloadMode: 'json' })
  const updateVariant = (variantId: number, input: ProductVariantMutation) => put<RawRecord, FormData>(`/products/variants/${variantId}`, variantFormData(input), { payloadMode: 'json' })
  const deleteVariant = (variantId: number) => del(`/products/variants/${variantId}`)
  const changeVariantStatus = (variantId: number, status: VariantStatus) => patch(`/products/variants/${variantId}/status`, { status })
  const changeStatus = (id: number, status: ProductStatus) => patch(`/products/${id}/status`, { status })
  const adjustVariantStock = (variantId: number, adjustment: number): Promise<StockAdjustmentResponse> =>
    patch<RawRecord>(`/products/variants/${variantId}/stock`, { adjustment }).then(result => ({ variantId: result.variant_id, adjustment: result.adjustment, warehouseVolume: result.warehouse_volume }))
  const changeVariantStatuses = (variantIds: number[], status: VariantStatus) =>
    patch<RawRecord>('/products/variants/batch/status', { variant_ids: variantIds, status })
  const adjustVariantStocks = (variantIds: number[], adjustment: number) =>
    patch<RawRecord>('/products/variants/batch/stock', { variant_ids: variantIds, adjustment })
  const changeStatuses = (ids: number[], status: ProductStatus) => post<RawRecord>('/products/batch/status', { ids, status })
  const deleteProducts = (ids: number[]) => del<RawRecord>('/products/batch', { ids })
  const permanentlyDeleteProducts = (ids: number[]) => del<RawRecord>('/products/batch/permanent', { ids })
  const restoreProduct = (id: number) => post(`/products/${id}/restore`)

  const createProductType = (input: ProductTypeMutation) => post('/product-types', {
    code: input.code,
    name: input.name,
    description: input.description,
    active: input.active,
    display_order: input.displayOrder,
  })
  const updateProductType = (id: number, input: ProductTypeMutation) => put(`/product-types/${id}`, {
    code: input.code,
    name: input.name,
    description: input.description,
    active: input.active,
    display_order: input.displayOrder,
  })
  const deleteProductType = (id: number) => del(`/product-types/${id}`)

  const createAttributeDefinition = (typeId: number, input: AttributeDefinitionMutation) => post(`/product-types/${typeId}/attributes`, {
    code: input.code,
    name: input.name,
    scope: input.scope,
    value_type: input.valueType,
    required: input.required,
    filterable: input.filterable,
    allowed_values: input.allowedValues,
    max_length: input.maxLength,
    display_order: input.displayOrder,
    active: input.active,
  })
  const updateAttributeDefinition = (id: number, input: AttributeDefinitionMutation) => put(`/attribute-definitions/${id}`, {
    code: input.code,
    name: input.name,
    scope: input.scope,
    value_type: input.valueType,
    required: input.required,
    filterable: input.filterable,
    allowed_values: input.allowedValues,
    max_length: input.maxLength,
    display_order: input.displayOrder,
    active: input.active,
  })
  const deleteAttributeDefinition = (id: number) => del(`/attribute-definitions/${id}`)

  const createCategory = (input: ProductCategoryMutation) => post('/product-categories', {
    code: input.code,
    name: input.name,
    description: input.description,
    parent_id: input.parentId,
    display_order: input.displayOrder,
    status: input.status,
  })
  const updateCategory = (id: number, input: ProductCategoryMutation) => put(`/product-categories/${id}`, {
    code: input.code,
    name: input.name,
    description: input.description,
    parent_id: input.parentId,
    display_order: input.displayOrder,
    status: input.status,
  })
  const deleteCategory = (id: number) => del(`/product-categories/${id}`)

  const listTags = (): Promise<Tag[]> => get<RawRecord>('/tags').then(data => (data.list ?? []).map(normalizeTag))
  const createTag = (input: TagMutation) => post('/tags', { ...input, sort_order: input.sortOrder })
  const updateTag = (id: number, input: TagMutation) => put(`/tags/${id}`, { ...input, sort_order: input.sortOrder })
  const deleteTag = (id: number) => del(`/tags/${id}`)
  const uploadImages = (files: File[]) => {
    const data = new FormData()
    files.forEach(file => data.append('files', file))
    return post<RawRecord, FormData>('/product-images', data, { payloadMode: 'json' }).then(result =>
      (result.list ?? []).map((item: RawRecord) => ({ stableUrl: String(item.stable_url), fileName: String(item.file_name ?? '') })))
  }

  return {
    listProducts,
    getProduct,
    listProductTypes,
    getProductTypeDefinitions,
    listCategories,
    createProduct,
    updateProduct,
    createVariant,
    updateVariant,
    deleteVariant,
    changeVariantStatus,
    changeStatus,
    adjustVariantStock,
    changeVariantStatuses,
    adjustVariantStocks,
    changeStatuses,
    deleteProducts,
    permanentlyDeleteProducts,
    restoreProduct,
    createProductType,
    updateProductType,
    deleteProductType,
    createAttributeDefinition,
    updateAttributeDefinition,
    deleteAttributeDefinition,
    createCategory,
    updateCategory,
    deleteCategory,
    listTags,
    createTag,
    updateTag,
    deleteTag,
    uploadImages,
  }
}
