import { ofetch } from 'ofetch'
import type { ApiResult } from '~/types/http'
import type {
  BikiniSuitResponse,
  CoverUpResponse,
  DressResponse,
  FileMetadataResponse,
  OnePieceSuitResponse,
  ProductListItem,
  ProductListQuery,
  ProductPage,
  ProductStatus,
  ProductType,
  ProductUpsertRequest,
  StockAdjustmentResponse,
  Tag,
  TagMutation,
} from '~/types/product'

export interface CategoryConfig {
  type: ProductType
  label: string
  basePath: string
  singularKey: string
}

export const CATEGORIES: readonly CategoryConfig[] = [
  { type: 'DRESS', label: '连衣裙', basePath: '/dresses', singularKey: 'dress' },
  { type: 'BIKINI', label: '比基尼', basePath: '/bikini-suits', singularKey: 'bikiniSuit' },
  { type: 'ONE_PIECE', label: '一件式', basePath: '/one-piece-suits', singularKey: 'onePieceSuit' },
  { type: 'COVER_UP', label: '罩衫', basePath: '/cover-ups', singularKey: 'coverUp' },
] as const

export function getCategoryConfig(type: ProductType): CategoryConfig {
  const config = CATEGORIES.find(category => category.type === type)
  if (!config) throw new Error(`未知的商品品类: ${type}`)
  return config
}

interface RawTag {
  id: number
  name: string
  description?: string | null
  color?: string | null
  sort_order: number
  active: boolean
  created_at?: string | null
  updated_at?: string | null
}

interface RawProduct {
  id: number
  product_type: ProductType
  name: string
  color: string
  price: number | string
  warehouse_volume: number
  sales_volume: number
  status: ProductStatus
  highlight?: string[]
  images?: string[]
  fit_sense?: string | null
  description?: string | null
  design_and_extras?: string[]
  care_instructions?: string[]
  score?: number | null
  tags?: RawTag[]
  created_at?: string | null
  updated_at?: string | null
  size?: string | null
  length?: string | null
  silhouette?: string | null
  neckline?: string | null
  sleeve_type?: string | null
  fabric?: string | null
  top_size?: string | null
  bottom_size?: string | null
  support_level?: string | null
  coverage?: string | null
  torso_fit?: string | null
  back_style?: string | null
  tummy_control?: boolean | null
  removable_padding?: boolean | null
  style?: string | null
  sheer_level?: string | null
}

interface RawProductPage {
  list: RawProduct[]
  pagination: {
    page: number
    size: number
    total_items: number
    total_pages: number
  }
}

interface RawProductImage {
  id: string
  file_name: string
  content_type?: string | null
  size_bytes: number
  sha256: string
  created_at?: string | null
  stable_url: string
  storage: string
}

const UNAUTHORIZED_STATUS = 401

function normalizeTag(raw: RawTag): Tag {
  return {
    id: raw.id,
    name: raw.name,
    description: raw.description ?? undefined,
    color: raw.color ?? undefined,
    sortOrder: raw.sort_order,
    active: raw.active,
    createdAt: raw.created_at ?? undefined,
    updatedAt: raw.updated_at ?? undefined,
  }
}

function normalizeProduct(raw: RawProduct): ProductListItem {
  const base = {
    id: raw.id,
    name: raw.name,
    color: raw.color,
    price: Number(raw.price),
    warehouseVolume: raw.warehouse_volume,
    salesVolume: raw.sales_volume,
    status: raw.status,
    highlight: raw.highlight ?? [],
    images: raw.images ?? [],
    fitSense: raw.fit_sense ?? undefined,
    description: raw.description ?? undefined,
    designAndExtras: raw.design_and_extras ?? [],
    careInstructions: raw.care_instructions ?? [],
    score: raw.score ?? undefined,
    tags: (raw.tags ?? []).map(normalizeTag),
    createdAt: raw.created_at ?? undefined,
    updatedAt: raw.updated_at ?? undefined,
  }

  if (raw.product_type === 'DRESS') {
    return {
      ...base,
      productType: 'DRESS',
      size: raw.size,
      length: raw.length ?? undefined,
      silhouette: raw.silhouette ?? undefined,
      neckline: raw.neckline ?? undefined,
      sleeveType: raw.sleeve_type ?? undefined,
      fabric: raw.fabric ?? undefined,
    } as DressResponse
  }
  if (raw.product_type === 'BIKINI') {
    return {
      ...base,
      productType: 'BIKINI',
      topSize: raw.top_size ?? undefined,
      bottomSize: raw.bottom_size ?? undefined,
    } as BikiniSuitResponse
  }
  if (raw.product_type === 'ONE_PIECE') {
    return {
      ...base,
      productType: 'ONE_PIECE',
      size: raw.size,
      supportLevel: raw.support_level ?? undefined,
      coverage: raw.coverage ?? undefined,
      torsoFit: raw.torso_fit ?? undefined,
      neckline: raw.neckline ?? undefined,
      backStyle: raw.back_style ?? undefined,
      tummyControl: raw.tummy_control ?? false,
      removablePadding: raw.removable_padding ?? false,
    } as OnePieceSuitResponse
  }
  return {
    ...base,
    productType: 'COVER_UP',
    style: raw.style ?? undefined,
    sheerLevel: raw.sheer_level ?? undefined,
    fabric: raw.fabric ?? undefined,
    size: raw.size,
  } as CoverUpResponse
}

function toWirePayload(category: ProductType, payload: unknown, includeOperationalFields: boolean): Record<string, unknown> {
  const config = getCategoryConfig(category)
  const request = payload as Record<string, unknown>
  const item = (request[config.singularKey] ?? {}) as Record<string, unknown>
  const wire: Record<string, unknown> = {
    name: item.name,
    color: item.color,
    price: item.price,
    highlight: item.highlight,
    images: item.images,
    fit_sense: item.fitSense,
    description: item.description,
    design_and_extras: item.designAndExtras,
    care_instructions: item.careInstructions,
    tag_ids: request.tagIds,
  }
  if (includeOperationalFields) {
    wire.warehouse_volume = item.warehouseVolume
    wire.status = item.status
  }

  if (category === 'DRESS') {
    Object.assign(wire, {
      size: item.size,
      length: item.length,
      silhouette: item.silhouette,
      neckline: item.neckline,
      sleeve_type: item.sleeveType,
      fabric: item.fabric,
    })
  } else if (category === 'BIKINI') {
    Object.assign(wire, { top_size: item.topSize, bottom_size: item.bottomSize })
  } else if (category === 'ONE_PIECE') {
    Object.assign(wire, {
      size: item.size,
      support_level: item.supportLevel,
      coverage: item.coverage,
      torso_fit: item.torsoFit,
      neckline: item.neckline,
      back_style: item.backStyle,
      tummy_control: item.tummyControl,
      removable_padding: item.removablePadding,
    })
  } else {
    Object.assign(wire, {
      style: item.style,
      sheer_level: item.sheerLevel,
      fabric: item.fabric,
      size: item.size,
    })
  }

  return Object.fromEntries(Object.entries(wire).filter(([, value]) => value !== undefined && value !== null))
}

function toTagWirePayload(payload: TagMutation): Record<string, unknown> {
  return {
    name: payload.name,
    description: payload.description || undefined,
    color: payload.color || undefined,
    sort_order: payload.sortOrder,
    active: payload.active,
  }
}

export const useProductApi = () => {
  const runtimeConfig = useRuntimeConfig()
  const adminApiBase = (runtimeConfig.public.adminApiBase as string) || 'http://127.0.0.1:8080/admin/api'
  const { get, post, put, patch, delete: del } = useHttp(adminApiBase)
  const authToken = useCookie<string | null>('admin_auth_token')
  const authUser = useCookie<unknown | null>('admin_user_info')
  const route = useRoute()

  function authHeader(): Record<string, string> {
    const token = authToken.value?.trim()
    if (!token) return {}
    return { Authorization: /^bearer\s+/i.test(token) ? token : `Bearer ${token}` }
  }

  function handleSessionExpired() {
    authToken.value = null
    authUser.value = null
    if (import.meta.server) {
      throw createError({ statusCode: 401, statusMessage: 'Unauthorized' })
    }
    if (route.path !== '/login') navigateTo('/login')
  }

  async function listProducts(query: ProductListQuery): Promise<ProductPage> {
    const data = await get<RawProductPage>('/products', { ...query })
    return {
      list: (data.list ?? []).map(normalizeProduct),
      pagination: {
        page: data.pagination.page,
        size: data.pagination.size,
        totalItems: data.pagination.total_items,
        totalPages: data.pagination.total_pages,
      },
    }
  }

  async function create(category: ProductType, payload: ProductUpsertRequest): Promise<number> {
    const result = await post<{ id: number }>(getCategoryConfig(category).basePath, toWirePayload(category, payload, true))
    return result.id
  }

  async function update(category: ProductType, id: number, payload: ProductUpsertRequest): Promise<void> {
    await put<{ id: number }>(`${getCategoryConfig(category).basePath}/${id}`, toWirePayload(category, payload, false))
  }

  function changeStatus(id: number, status: Exclude<ProductStatus, 'DELETED'>): Promise<{ id: number; status: ProductStatus }> {
    return patch(`/products/${id}/status`, { status })
  }

  function adjustStock(id: number, adjustment: number): Promise<StockAdjustmentResponse> {
    return patch<{ id: number; adjustment: number; warehouse_volume: number }>(`/products/${id}/stock`, { adjustment })
      .then(result => ({
        id: result.id,
        adjustment: result.adjustment,
        warehouseVolume: result.warehouse_volume,
      }))
  }

  function changeStatuses(ids: number[], status: Exclude<ProductStatus, 'DELETED'>): Promise<{ updated: number }> {
    return post('/products/batch/status', { ids, status })
  }

  function deleteProducts(ids: number[]): Promise<{ deleted: number }> {
    return del('/products/batch', { ids })
  }

  function permanentlyDeleteProducts(ids: number[]): Promise<{ deleted: number }> {
    return del('/products/batch/permanent', { ids })
  }

  function restoreProduct(id: number): Promise<{ id: number; status: ProductStatus }> {
    return post(`/products/${id}/restore`)
  }

  function restoreProducts(ids: number[]): Promise<{ restored: number }> {
    return post('/products/batch/restore', { ids })
  }

  function listTags(): Promise<Tag[]> {
    return get<{ list: RawTag[] }>('/tags').then(data => (data.list ?? []).map(normalizeTag))
  }

  async function createTag(payload: TagMutation): Promise<void> {
    await post('/tags', toTagWirePayload(payload))
  }

  async function updateTag(id: number, payload: TagMutation): Promise<void> {
    await put(`/tags/${id}`, toTagWirePayload(payload))
  }

  async function deleteTag(id: number): Promise<void> {
    await del(`/tags/${id}`)
  }

  async function uploadImages(files: File[]): Promise<FileMetadataResponse[]> {
    const form = new FormData()
    files.forEach(file => form.append('files', file))
    const envelope = await ofetch<ApiResult<{ list: RawProductImage[] }>>('/product-images', {
      baseURL: adminApiBase,
      method: 'POST',
      body: form,
      credentials: 'include',
      headers: { Accept: 'application/json', ...authHeader() },
    }).catch((error: unknown) => {
      const value = error as { response?: { status?: number }; statusCode?: number }
      const status = value.response?.status ?? value.statusCode ?? 0
      if (status === UNAUTHORIZED_STATUS) handleSessionExpired()
      throw error
    })
    return (envelope?.data?.list ?? []).map(file => ({
      id: file.id,
      fileName: file.file_name,
      contentType: file.content_type ?? undefined,
      sizeBytes: file.size_bytes,
      sha256: file.sha256,
      createdAt: file.created_at ?? undefined,
      stableUrl: file.stable_url,
      storage: file.storage,
    }))
  }

  return {
    listProducts,
    create,
    update,
    changeStatus,
    adjustStock,
    changeStatuses,
    deleteProducts,
    permanentlyDeleteProducts,
    restoreProduct,
    restoreProducts,
    listTags,
    createTag,
    updateTag,
    deleteTag,
    uploadImages,
  }
}
