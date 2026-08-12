<script setup lang="ts">
import { Boxes, Pencil, Plus, RotateCcw, Settings2, Tags, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, TagProps } from 'naive-ui'
import { NButton, NImage, NTag, useMessage } from 'naive-ui'
import type { Product, ProductCategory, ProductListQuery, ProductStatus, ProductType, ProductVariant, Tag } from '~/types/product'

definePageMeta({ layout: 'default' })

const api = useProductApi()
const message = useMessage()
const { confirmDeleteRequest } = useDeleteConfirmation()
const products = ref<Product[]>([])
const productTypes = ref<ProductType[]>([])
const categories = ref<ProductCategory[]>([])
const tags = ref<Tag[]>([])
const loading = ref(false)
const busyId = ref<number | null>(null)
let requestSequence = 0

const filters = reactive<{
  productType: string | null
  status: ProductStatus | null
  deleted: 'CURRENT' | 'DELETED' | 'ALL'
  keyword: string
  lowStock: boolean
  lowStockThreshold: number
  sortBy: NonNullable<ProductListQuery['sort_by']>
  ascending: boolean
}>({
  productType: null,
  status: null,
  deleted: 'CURRENT',
  keyword: '',
  lowStock: false,
  lowStockThreshold: 10,
  sortBy: 'UPDATED_AT',
  ascending: false,
})

const pagination = reactive({ page: 1, pageSize: 20, totalItems: 0, pageCount: 1 })
const typeOptions = computed(() => productTypes.value.map(type => ({ label: type.name, value: type.code })))
const typeNames = computed(() => new Map(productTypes.value.map(type => [type.code, type.name])))
const statusOptions = [{ label: '上架', value: 'ACTIVE' }, { label: '下架', value: 'INACTIVE' }]
const deletedOptions = [
  { label: '正常商品', value: 'CURRENT' },
  { label: '已删除商品', value: 'DELETED' },
  { label: '全部', value: 'ALL' },
]
const sortOptions = [
  { label: '更新时间', value: 'UPDATED_AT' },
  { label: '创建时间', value: 'CREATED_AT' },
  { label: '名称', value: 'NAME' },
  { label: '最低价格', value: 'PRICE' },
  { label: '总库存', value: 'STOCK' },
  { label: '总销量', value: 'SALES' },
]

async function loadMetadata() {
  try {
    const [types, categoryList, tagList] = await Promise.all([
      api.listProductTypes(),
      api.listCategories(),
      api.listTags(),
    ])
    productTypes.value = types
    categories.value = categoryList
    tags.value = tagList
  } catch (error) {
    message.error(`加载商品元数据失败：${errorMessage(error)}`)
  }
}

async function loadProducts() {
  const sequence = ++requestSequence
  loading.value = true
  try {
    const query: ProductListQuery = {
      page: pagination.page,
      size: pagination.pageSize,
      sort_by: filters.sortBy,
      ascending: filters.ascending,
      deleted: filters.deleted === 'ALL' ? undefined : filters.deleted === 'DELETED',
    }
    if (filters.productType) query.product_type = filters.productType
    if (filters.status) query.status = filters.status
    if (filters.keyword.trim()) query.keyword = filters.keyword.trim()
    if (filters.lowStock) {
      query.low_stock = true
      query.low_stock_threshold = filters.lowStockThreshold
    }
    const result = await api.listProducts(query)
    if (sequence !== requestSequence) return
    products.value = result.list
    pagination.totalItems = result.pagination.totalItems
    pagination.pageCount = Math.max(result.pagination.totalPages, 1)
  } catch (error) {
    if (sequence !== requestSequence) return
    products.value = []
    message.error(`加载商品失败：${errorMessage(error)}`)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function search() {
  pagination.page = 1
  await loadProducts()
}

async function resetFilters() {
  Object.assign(filters, {
    productType: null,
    status: null,
    deleted: 'CURRENT',
    keyword: '',
    lowStock: false,
    lowStockThreshold: 10,
    sortBy: 'UPDATED_AT',
    ascending: false,
  })
  pagination.page = 1
  await loadProducts()
}

const drawerOpen = ref(false)
const editingProduct = ref<Product | null>(null)

function openCreate() {
  editingProduct.value = null
  drawerOpen.value = true
}

async function openEdit(row: Product) {
  busyId.value = row.id
  try {
    editingProduct.value = await api.getProduct(row.id)
    drawerOpen.value = true
  } catch (error) {
    message.error(`加载商品详情失败：${errorMessage(error)}`)
  } finally {
    busyId.value = null
  }
}

async function toggleStatus(row: Product) {
  busyId.value = row.id
  try {
    await api.changeStatus(row.id, row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE')
    await loadProducts()
  } catch (error) {
    message.error(`更新商品状态失败：${errorMessage(error)}`)
  } finally {
    busyId.value = null
  }
}

function removeProduct(row: Product) {
  confirmDeleteRequest({
    title: '删除商品',
    content: `确认删除“${row.name}”？`,
    positiveText: '删除',
    onConfirm: async () => {
      busyId.value = row.id
      try {
        await api.deleteProducts([row.id])
        await loadProducts()
      } catch (error) {
        message.error(`删除商品失败：${errorMessage(error)}`)
      } finally {
        busyId.value = null
      }
    },
  })
}

async function restoreProduct(row: Product) {
  busyId.value = row.id
  try {
    await api.restoreProduct(row.id)
    await loadProducts()
  } catch (error) {
    message.error(`恢复商品失败：${errorMessage(error)}`)
  } finally {
    busyId.value = null
  }
}

function permanentlyDelete(row: Product) {
  confirmDeleteRequest({
    tone: 'error',
    title: '永久删除商品',
    content: `确认永久删除“${row.name}”？此操作无法恢复。`,
    positiveText: '永久删除',
    onConfirm: async () => {
      busyId.value = row.id
      try {
        await api.permanentlyDeleteProducts([row.id])
        await loadProducts()
      } catch (error) {
        message.error(`永久删除失败：${errorMessage(error)}`)
      } finally {
        busyId.value = null
      }
    },
  })
}

const stockOpen = ref(false)
const stockProduct = ref<Product | null>(null)
const selectedVariantId = ref<number | null>(null)
const stockAdjustment = ref<number | null>(1)
const stockSubmitting = ref(false)
const selectedVariant = computed(() => stockProduct.value?.variants.find(variant => variant.id === selectedVariantId.value) ?? null)
const variantOptions = computed(() => stockProduct.value?.variants.map(variant => ({
  label: `${variant.sku} · ${variant.size || '-'} · ${variant.color} · 库存 ${variant.warehouseVolume}`,
  value: variant.id,
})) ?? [])

function openStock(row: Product) {
  stockProduct.value = row
  selectedVariantId.value = row.variants[0]?.id ?? null
  stockAdjustment.value = 1
  stockOpen.value = true
}

async function submitStock() {
  if (!selectedVariantId.value || !stockAdjustment.value || !Number.isInteger(stockAdjustment.value)) {
    message.warning('请选择 SKU 并填写非零整数调整量')
    return
  }
  stockSubmitting.value = true
  try {
    await api.adjustVariantStock(selectedVariantId.value, stockAdjustment.value)
    stockOpen.value = false
    await loadProducts()
  } catch (error) {
    message.error(`调整库存失败：${errorMessage(error)}`)
  } finally {
    stockSubmitting.value = false
  }
}

function statusType(status: ProductStatus): TagProps['type'] {
  return status === 'ACTIVE' ? 'success' : 'warning'
}

function priceRange(variants: ProductVariant[]): string {
  if (!variants.length) return '-'
  const prices = variants.map(variant => Number(variant.price)).filter(Number.isFinite)
  if (!prices.length) return '-'
  const min = Math.min(...prices).toFixed(2)
  const max = Math.max(...prices).toFixed(2)
  return min === max ? `$${min}` : `$${min} - $${max}`
}

function formatTime(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return String(error || '未知错误')
}

const columns: DataTableColumns<Product> = [
  {
    title: '商品',
    key: 'product',
    minWidth: 250,
    fixed: 'left',
    render: row => {
      const image = row.images.find(item => item.primary) ?? row.images[0]
      return h('div', { class: 'product-cell' }, [
        image
          ? h(NImage, { src: image.url, width: 48, height: 48, objectFit: 'cover', previewDisabled: true, class: 'product-thumb' })
          : h('div', { class: 'product-thumb product-thumb-empty' }, '无图'),
        h('div', { class: 'product-copy' }, [
          h('strong', row.name),
          h('span', `#${row.id} · ${typeNames.value.get(row.productType) ?? row.productType}`),
        ]),
      ])
    },
  },
  {
    title: 'SKU 状态',
    key: 'variants',
    width: 112,
    align: 'right',
    render: row => `${row.variants.filter(variant => variant.status === 'ACTIVE').length} / ${row.variants.length} 启用`,
  },
  { title: '价格区间', key: 'price', width: 150, align: 'right', render: row => priceRange(row.variants) },
  { title: '总库存', key: 'stock', width: 90, align: 'right', render: row => row.variants.reduce((sum, variant) => sum + variant.warehouseVolume, 0) },
  {
    title: '低库存 SKU',
    key: 'lowStock',
    width: 110,
    align: 'right',
    render: row => row.variants.filter(variant => variant.warehouseVolume <= filters.lowStockThreshold).length,
  },
  {
    title: '商品状态',
    key: 'status',
    width: 100,
    render: row => row.deletedAt
      ? h(NTag, { type: 'error', size: 'small', bordered: false }, { default: () => '已删除' })
      : h(NTag, { type: statusType(row.status), size: 'small', bordered: false }, { default: () => row.status === 'ACTIVE' ? '上架' : '下架' }),
  },
  { title: '更新时间', key: 'updatedAt', width: 170, render: row => formatTime(row.updatedAt) },
  {
    title: '操作',
    key: 'actions',
    width: 250,
    fixed: 'right',
    render: row => h('div', { class: 'row-actions' }, row.deletedAt
      ? [
          h(NButton, { size: 'small', tertiary: true, loading: busyId.value === row.id, onClick: () => restoreProduct(row) }, { default: () => '恢复' }),
          h(NButton, { size: 'small', tertiary: true, type: 'error', disabled: busyId.value !== null, onClick: () => permanentlyDelete(row) }, { default: () => '永久删除' }),
        ]
      : [
          h(NButton, { size: 'small', tertiary: true, loading: busyId.value === row.id, onClick: () => openEdit(row) }, { default: () => '编辑' }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => openStock(row) }, { default: () => '库存' }),
          h(NButton, { size: 'small', tertiary: true, onClick: () => toggleStatus(row) }, { default: () => row.status === 'ACTIVE' ? '下架' : '上架' }),
          h(NButton, { size: 'small', tertiary: true, type: 'error', onClick: () => removeProduct(row) }, { default: () => '删除' }),
        ]),
  },
]

const tagManagerOpen = ref(false)

onMounted(async () => {
  await loadMetadata()
  await loadProducts()
})
</script>

<template>
  <div class="products-page">
    <header class="page-header">
      <div>
        <h2>商品管理</h2>
        <NText depth="3">{{ pagination.totalItems }} 个商品款式</NText>
      </div>
      <NSpace>
        <NButton @click="navigateTo('/product-metadata')">
          <template #icon><Settings2 :size="16" /></template>
          类型与分类
        </NButton>
        <NButton @click="tagManagerOpen = true">
          <template #icon><Tags :size="16" /></template>
          标签
        </NButton>
        <NButton type="primary" @click="openCreate">
          <template #icon><Plus :size="16" /></template>
          新增商品
        </NButton>
      </NSpace>
    </header>

    <section class="filter-band">
      <NSelect v-model:value="filters.productType" :options="typeOptions" clearable placeholder="全部类型" />
      <NSelect v-model:value="filters.status" :options="statusOptions" clearable placeholder="全部状态" />
      <NSelect v-model:value="filters.deleted" :options="deletedOptions" placeholder="删除状态" />
      <NInput v-model:value="filters.keyword" clearable maxlength="200" placeholder="商品名称" @keyup.enter="search" />
      <div class="low-stock-filter">
        <NCheckbox v-model:checked="filters.lowStock">低库存</NCheckbox>
        <NInputNumber v-model:value="filters.lowStockThreshold" :min="0" :precision="0" :disabled="!filters.lowStock" />
      </div>
      <NSelect v-model:value="filters.sortBy" :options="sortOptions" />
      <NRadioGroup v-model:value="filters.ascending" size="small">
        <NRadioButton :value="false">降序</NRadioButton>
        <NRadioButton :value="true">升序</NRadioButton>
      </NRadioGroup>
      <NSpace justify="end">
        <NButton :disabled="loading" @click="resetFilters">重置</NButton>
        <NButton type="primary" :loading="loading" @click="search">查询</NButton>
      </NSpace>
    </section>

    <section class="table-band">
      <NDataTable
        :columns="columns"
        :data="products"
        :loading="loading"
        :pagination="false"
        :row-key="row => row.id"
        :scroll-x="1250"
        size="small"
      />
      <div class="pagination-bar">
        <NPagination
          v-model:page="pagination.page"
          v-model:page-size="pagination.pageSize"
          :item-count="pagination.totalItems"
          :page-sizes="[10, 20, 50, 100]"
          show-size-picker
          @update:page="loadProducts"
          @update:page-size="() => { pagination.page = 1; loadProducts() }"
        />
      </div>
    </section>

    <ProductFormDrawer
      v-model:open="drawerOpen"
      :product="editingProduct"
      :product-types="productTypes"
      :categories="categories"
      :tags="tags"
      @submitted="loadProducts"
    />

    <TagManagerDrawer v-model:open="tagManagerOpen" @changed="value => { tags = value }" />

    <NModal
      v-model:show="stockOpen"
      preset="card"
      title="SKU 与库存"
      :style="{ width: 'min(760px, calc(100vw - 32px))' }"
      :mask-closable="!stockSubmitting"
    >
      <div class="sku-list" role="list" aria-label="SKU 列表">
        <button
          v-for="variant in stockProduct?.variants ?? []"
          :key="variant.id"
          type="button"
          class="sku-row"
          :class="{ selected: selectedVariantId === variant.id }"
          :disabled="stockSubmitting"
          @click="selectedVariantId = variant.id"
        >
          <span><strong>{{ variant.sku }}</strong><small>{{ variant.size || '无尺码' }} · {{ variant.color }}</small></span>
          <span>USD {{ variant.price }}</span>
          <span>库存 {{ variant.warehouseVolume }}</span>
          <NTag :type="variant.status === 'ACTIVE' ? 'success' : 'default'" size="small" :bordered="false">
            {{ variant.status === 'ACTIVE' ? '启用' : '停用' }}
          </NTag>
        </button>
      </div>
      <NForm label-placement="top">
        <NFormItem label="SKU">
          <NSelect v-model:value="selectedVariantId" :options="variantOptions" filterable />
        </NFormItem>
        <NDescriptions v-if="selectedVariant" :column="2" bordered size="small" class="stock-summary">
          <NDescriptionsItem label="SKU 状态">{{ selectedVariant.status }}</NDescriptionsItem>
          <NDescriptionsItem label="当前库存">{{ selectedVariant.warehouseVolume }}</NDescriptionsItem>
        </NDescriptions>
        <NFormItem label="调整量">
          <NInputNumber v-model:value="stockAdjustment" :min="-1000000" :max="1000000" :precision="0" style="width: 100%" />
        </NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="stockSubmitting" @click="stockOpen = false">取消</NButton>
          <NButton type="primary" :loading="stockSubmitting" @click="submitStock">保存</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.products-page {
  min-width: 0;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.page-header h2 {
  margin: 0 0 4px;
  font-size: 22px;
}

.filter-band {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 12px;
  padding: 14px 0 18px;
  border-top: 1px solid #eceef1;
  border-bottom: 1px solid #eceef1;
}

.low-stock-filter {
  display: grid;
  grid-template-columns: auto minmax(90px, 1fr);
  align-items: center;
  gap: 8px;
}

.sku-list {
  max-height: 300px;
  margin-bottom: 16px;
  overflow-y: auto;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.sku-row {
  width: 100%;
  min-height: 56px;
  display: grid;
  grid-template-columns: minmax(180px, 1.6fr) minmax(100px, .7fr) minmax(80px, .5fr) 64px;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border: 0;
  border-bottom: 1px solid #eceef1;
  color: inherit;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.sku-row:last-child {
  border-bottom: 0;
}

.sku-row.selected {
  background: #f4f7fb;
  box-shadow: inset 3px 0 #2563eb;
}

.sku-row > span:first-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.sku-row strong,
.sku-row small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sku-row small {
  color: #73737d;
}

@media (max-width: 620px) {
  .sku-row {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .sku-row > span:nth-child(2),
  .sku-row > span:nth-child(3) {
    font-size: 12px;
  }
}

.table-band {
  padding-top: 18px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.stock-summary {
  margin-bottom: 16px;
}

:deep(.product-cell),
:deep(.row-actions) {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.product-thumb) {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  overflow: hidden;
  border-radius: 4px;
}

:deep(.product-thumb-empty) {
  display: grid;
  place-items: center;
  color: #8b8b94;
  background: #f1f2f4;
  font-size: 11px;
}

:deep(.product-copy) {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

:deep(.product-copy strong) {
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.product-copy span) {
  color: #8b8b94;
  font-size: 12px;
}

@media (max-width: 900px) {
  .page-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .filter-band {
    grid-template-columns: repeat(2, minmax(140px, 1fr));
  }
}

@media (max-width: 560px) {
  .filter-band {
    grid-template-columns: 1fr;
  }
}
</style>
