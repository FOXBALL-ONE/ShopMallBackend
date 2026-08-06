<script setup lang="ts">
import { Plus, Tags, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import type { DataTableColumns, DataTableRowKey, FormInst, FormRules, TagProps } from 'naive-ui'
import { NButton, NDropdown, NImage, NTag, useMessage } from 'naive-ui'
import { CATEGORIES } from '~/composables/useProductApi'
import type {
  ProductListItem,
  ProductListQuery,
  ProductSortBy,
  ProductStatus,
  ProductType,
  Tag,
} from '~/types/product'

definePageMeta({ layout: 'default' })

const api = useProductApi()
const { confirmDeleteRequest } = useDeleteConfirmation()
const message = useMessage()
const activeCategory = ref<ProductType>('DRESS')
const products = ref<ProductListItem[]>([])
const tags = ref<Tag[]>([])
const loading = ref(false)
const batchRunning = ref(false)
const busyIds = ref<Set<number>>(new Set())
let loadSequence = 0

const filters = reactive<{
  status: ProductStatus | null
  keyword: string
  lowStock: boolean
  lowStockThreshold: number
  sortBy: ProductSortBy
  ascending: boolean
}>({
  status: null,
  keyword: '',
  lowStock: false,
  lowStockThreshold: 10,
  sortBy: 'UPDATED_AT',
  ascending: false,
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  pageCount: 1,
  totalItems: 0,
})

const statusOptions = [
  { label: '上架', value: 'ACTIVE' },
  { label: '下架', value: 'INACTIVE' },
  { label: '已删除', value: 'DELETED' },
]

const sortOptions = [
  { label: '更新时间', value: 'UPDATED_AT' },
  { label: '创建时间', value: 'CREATED_AT' },
  { label: '名称', value: 'NAME' },
  { label: '价格', value: 'PRICE' },
  { label: '库存', value: 'STOCK' },
  { label: '销量', value: 'SALES' },
]

const pageSizeOptions = [10, 20, 50, 100]
const resultSummary = computed(() => {
  if (loading.value) return '正在加载商品…'
  if (pagination.totalItems === 0) return '当前条件下没有商品'
  return `共 ${pagination.totalItems} 条，第 ${pagination.page} / ${pagination.pageCount} 页`
})

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return '未知错误'
}

async function loadProducts() {
  const sequence = ++loadSequence
  loading.value = true
  try {
    const query: ProductListQuery = {
      product_type: activeCategory.value,
      sort_by: filters.sortBy,
      ascending: filters.ascending,
      page: pagination.page,
      size: pagination.pageSize,
    }
    if (filters.status) query.status = filters.status
    if (filters.keyword.trim()) query.keyword = filters.keyword.trim()
    if (filters.lowStock) {
      query.low_stock = true
      query.low_stock_threshold = filters.lowStockThreshold
    }
    const data = await api.listProducts(query)
    if (sequence !== loadSequence) return
    const pageCount = Math.max(data.pagination.totalPages, 1)
    if (pagination.page > pageCount) {
      pagination.page = pageCount
      await loadProducts()
      return
    }
    products.value = data.list
    pagination.pageCount = pageCount
    pagination.totalItems = data.pagination.totalItems
    checkedRowKeys.value = []
  } catch (error) {
    if (sequence !== loadSequence) return
    products.value = []
    pagination.pageCount = 1
    pagination.totalItems = 0
    message.error(`加载商品失败：${errorMessage(error)}`)
  } finally {
    if (sequence === loadSequence) loading.value = false
  }
}

async function loadTags() {
  try {
    tags.value = await api.listTags()
  } catch (error) {
    message.error(`加载标签失败：${errorMessage(error)}`)
  }
}

function handleTagsChanged(value: Tag[]) {
  tags.value = value
  const tagsById = new Map(value.map(tag => [tag.id, tag]))
  products.value.forEach(product => {
    product.tags = product.tags.map(tag => tagsById.get(tag.id) ?? tag)
  })
}

async function searchProducts() {
  pagination.page = 1
  await loadProducts()
}

async function resetFilters() {
  Object.assign(filters, {
    status: null,
    keyword: '',
    lowStock: false,
    lowStockThreshold: 10,
    sortBy: 'UPDATED_AT',
    ascending: false,
  })
  pagination.page = 1
  await loadProducts()
}

async function changePage(page: number) {
  pagination.page = page
  await loadProducts()
}

async function changePageSize(pageSize: number) {
  pagination.pageSize = pageSize
  pagination.page = 1
  await loadProducts()
}

watch(activeCategory, () => {
  pagination.page = 1
  checkedRowKeys.value = []
  void loadProducts()
})

onMounted(() => {
  void loadProducts()
  void loadTags()
})

const checkedRowKeys = ref<DataTableRowKey[]>([])
const selectedRows = computed(() => {
  const ids = new Set(checkedRowKeys.value)
  return products.value.filter(product => ids.has(product.id))
})
const selectedAvailable = computed(() => selectedRows.value.filter(product => product.status !== 'DELETED'))
const selectedDeleted = computed(() => selectedRows.value.filter(product => product.status === 'DELETED'))

function rowKey(row: ProductListItem): DataTableRowKey {
  return row.id
}

function handleCheck(keys: DataTableRowKey[]) {
  checkedRowKeys.value = keys
}

function statusLabel(status: ProductStatus): string {
  if (status === 'ACTIVE') return '上架'
  if (status === 'INACTIVE') return '下架'
  return '已删除'
}

function statusTagType(status: ProductStatus): TagProps['type'] {
  if (status === 'ACTIVE') return 'success'
  if (status === 'INACTIVE') return 'warning'
  return 'error'
}

function formatPrice(price: number): string {
  return Number.isFinite(price) ? `¥${price.toFixed(2)}` : '-'
}

function formatTime(value?: string): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function variantLabel(row: ProductListItem): string {
  if (row.productType === 'BIKINI') return [row.topSize, row.bottomSize].filter(Boolean).join(' / ') || '-'
  return row.size || '-'
}

const drawerVisible = ref(false)
const editingProduct = ref<ProductListItem | null>(null)

function openCreate() {
  editingProduct.value = null
  drawerVisible.value = true
}

function openEdit(row: ProductListItem) {
  if (row.status === 'DELETED') return
  editingProduct.value = row
  drawerVisible.value = true
}

async function onDrawerSubmitted() {
  await loadProducts()
}

async function toggleStatus(row: ProductListItem) {
  if (row.status === 'DELETED') return
  const target = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  busyIds.value.add(row.id)
  try {
    await api.changeStatus(row.id, target)
    message.success(target === 'ACTIVE' ? '商品已上架' : '商品已下架')
    await loadProducts()
  } catch (error) {
    message.error(`状态更新失败：${errorMessage(error)}`)
  } finally {
    busyIds.value.delete(row.id)
  }
}

function confirmDelete(row: ProductListItem) {
  confirmDeleteRequest({
    title: '移除商品',
    content: `确认移除“${row.name}”？移除后可从已删除列表恢复。`,
    positiveText: '移除',
    onConfirm: async () => {
      busyIds.value.add(row.id)
      try {
        await api.deleteProducts([row.id])
        message.success('商品已移除')
        await loadProducts()
      } catch (error) {
        message.error(`移除失败：${errorMessage(error)}`)
      } finally {
        busyIds.value.delete(row.id)
      }
    },
  })
}

function confirmPermanentDelete(row: ProductListItem) {
  if (row.status !== 'DELETED') return
  confirmDeleteRequest({
    tone: 'error',
    title: '永久删除商品',
    content: `确认永久删除“${row.name}”？商品、关联评价和购物车项会从数据库移除，且无法恢复。`,
    positiveText: '永久删除',
    onConfirm: async () => {
      busyIds.value.add(row.id)
      try {
        await api.permanentlyDeleteProducts([row.id])
        message.success('商品已永久删除')
        await loadProducts()
      } catch (error) {
        message.error(`永久删除失败：${errorMessage(error)}`)
      } finally {
        busyIds.value.delete(row.id)
      }
    },
  })
}

async function restore(row: ProductListItem) {
  busyIds.value.add(row.id)
  try {
    await api.restoreProduct(row.id)
    message.success('商品已恢复为下架状态')
    await loadProducts()
  } catch (error) {
    message.error(`恢复失败：${errorMessage(error)}`)
  } finally {
    busyIds.value.delete(row.id)
  }
}

const stockOpen = ref(false)
const stockLoading = ref(false)
const stockProduct = ref<ProductListItem | null>(null)
const stockFormRef = ref<FormInst | null>(null)
const stockForm = reactive<{
  mode: 'IN' | 'OUT'
  quantity: number | null
}>({ mode: 'IN', quantity: 1 })

const stockRules: FormRules = {
  quantity: [{
    required: true,
    validator: (_rule: unknown, value: number | null) => value !== null
      && Number.isInteger(value)
      && value >= 1
      && value <= 1_000_000
      ? true
      : new Error('数量必须是 1 到 1000000 之间的整数'),
    trigger: ['blur', 'change'],
  }],
}

function openStock(row: ProductListItem) {
  if (row.status === 'DELETED') return
  stockProduct.value = row
  stockForm.mode = 'IN'
  stockForm.quantity = 1
  stockFormRef.value?.restoreValidation()
  stockOpen.value = true
}

async function submitStockAdjustment() {
  try {
    await stockFormRef.value?.validate()
  } catch {
    return
  }
  const row = stockProduct.value
  const quantity = stockForm.quantity
  if (!row || quantity === null) return
  const adjustment = stockForm.mode === 'IN' ? quantity : -quantity
  stockLoading.value = true
  try {
    const result = await api.adjustStock(row.id, adjustment)
    row.warehouseVolume = result.warehouseVolume
    stockOpen.value = false
    message.success('库存已更新')
  } catch (error) {
    message.error(`库存调整失败：${errorMessage(error)}`)
  } finally {
    stockLoading.value = false
  }
}

async function batchChangeStatus(target: 'ACTIVE' | 'INACTIVE') {
  const ids = selectedAvailable.value.filter(row => row.status !== target).map(row => row.id)
  if (ids.length === 0) {
    message.info('没有符合条件的商品')
    return
  }
  batchRunning.value = true
  try {
    const result = await api.changeStatuses(ids, target)
    message.success(`已更新 ${result.updated} 条商品`)
    await loadProducts()
  } catch (error) {
    message.error(`批量更新失败：${errorMessage(error)}`)
  } finally {
    batchRunning.value = false
  }
}

function batchDelete() {
  const ids = selectedAvailable.value.map(row => row.id)
  if (ids.length === 0) {
    message.info('没有可移除的商品')
    return
  }
  confirmDeleteRequest({
    title: '批量移除商品',
    content: `确认移除选中的 ${ids.length} 条商品？移除后可恢复。`,
    positiveText: '批量移除',
    onConfirm: async () => {
      batchRunning.value = true
      try {
        const result = await api.deleteProducts(ids)
        message.success(`已移除 ${result.deleted} 条商品`)
        await loadProducts()
      } catch (error) {
        message.error(`批量移除失败：${errorMessage(error)}`)
      } finally {
        batchRunning.value = false
      }
    },
  })
}

function batchPermanentDelete() {
  const ids = selectedDeleted.value.map(row => row.id)
  if (ids.length === 0) {
    message.info('没有可永久删除的商品')
    return
  }
  confirmDeleteRequest({
    tone: 'error',
    title: '批量永久删除商品',
    content: `确认永久删除选中的 ${ids.length} 条商品？商品、关联评价和购物车项会从数据库移除，且无法恢复。`,
    positiveText: '永久删除',
    onConfirm: async () => {
      batchRunning.value = true
      try {
        const result = await api.permanentlyDeleteProducts(ids)
        message.success(`已永久删除 ${result.deleted} 条商品`)
        await loadProducts()
      } catch (error) {
        message.error(`批量永久删除失败：${errorMessage(error)}`)
      } finally {
        batchRunning.value = false
      }
    },
  })
}

async function batchRestore() {
  const ids = selectedDeleted.value.map(row => row.id)
  if (ids.length === 0) {
    message.info('没有可恢复的商品')
    return
  }
  batchRunning.value = true
  try {
    const result = await api.restoreProducts(ids)
    message.success(`已恢复 ${result.restored} 条商品`)
    await loadProducts()
  } catch (error) {
    message.error(`批量恢复失败：${errorMessage(error)}`)
  } finally {
    batchRunning.value = false
  }
}

function handleRowAction(key: string, row: ProductListItem) {
  if (key === 'stock') openStock(row)
  if (key === 'status') void toggleStatus(row)
  if (key === 'delete') confirmDelete(row)
  if (key === 'permanent-delete') confirmPermanentDelete(row)
  if (key === 'restore') void restore(row)
}

const columns: DataTableColumns<ProductListItem> = [
  { type: 'selection', width: 44 },
  {
    title: '商品',
    key: 'product',
    minWidth: 250,
    fixed: 'left',
    render: row => h('div', { class: 'product-cell' }, [
      row.images[0]
        ? h(NImage, {
            src: row.images[0],
            width: 48,
            height: 48,
            objectFit: 'cover',
            previewDisabled: true,
            class: 'product-thumb',
          })
        : h('div', { class: 'product-thumb product-thumb-empty' }, '无图'),
      h('div', { class: 'product-main' }, [
        h('div', { class: 'product-name', title: row.name }, row.name),
        h('div', { class: 'product-meta' }, `#${row.id} · ${row.color} · ${variantLabel(row)}`),
      ]),
    ]),
  },
  {
    title: '标签',
    key: 'tags',
    minWidth: 160,
    render: row => row.tags.length === 0
      ? '-'
      : h('div', { class: 'product-tags' }, [
          ...row.tags.slice(0, 2).map(tag => h(
            NTag,
            {
              size: 'small',
              bordered: false,
              color: tag.color
                ? { color: `${tag.color}1A`, textColor: tag.color, borderColor: tag.color }
                : undefined,
            },
            { default: () => tag.name },
          )),
          row.tags.length > 2
            ? h(NTag, { size: 'small', bordered: false }, { default: () => `+${row.tags.length - 2}` })
            : null,
        ]),
  },
  {
    title: '价格',
    key: 'price',
    width: 110,
    align: 'right',
    render: row => formatPrice(row.price),
  },
  {
    title: '库存',
    key: 'warehouseVolume',
    width: 90,
    align: 'right',
    render: row => h(
      'span',
      { class: row.status !== 'DELETED' && row.warehouseVolume <= filters.lowStockThreshold ? 'low-stock' : undefined },
      String(row.warehouseVolume),
    ),
  },
  { title: '销量', key: 'salesVolume', width: 90, align: 'right' },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: row => h(
      NTag,
      { type: statusTagType(row.status), size: 'small', bordered: false },
      { default: () => statusLabel(row.status) },
    ),
  },
  {
    title: '评分',
    key: 'score',
    width: 76,
    align: 'right',
    render: row => row.score == null ? '-' : row.score.toFixed(1),
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 180,
    render: row => formatTime(row.updatedAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 150,
    fixed: 'right',
    render: row => {
      const deleted = row.status === 'DELETED'
      const options = deleted
        ? [
            { label: '恢复', key: 'restore' },
            { label: '永久删除', key: 'permanent-delete' },
          ]
        : [
            { label: '调整库存', key: 'stock' },
            { label: row.status === 'ACTIVE' ? '下架' : '上架', key: 'status' },
            { label: '移除', key: 'delete' },
          ]
      return h('div', { class: 'row-actions' }, [
        h(
          NButton,
          {
            size: 'small',
            tertiary: true,
            disabled: deleted || batchRunning.value || busyIds.value.has(row.id),
            onClick: () => openEdit(row),
          },
          { default: () => '编辑' },
        ),
        h(
          NDropdown,
          {
            options,
            trigger: 'click',
            disabled: batchRunning.value || busyIds.value.has(row.id),
            onSelect: (key: string) => handleRowAction(key, row),
          },
          {
            default: () => h(
              NButton,
              { size: 'small', tertiary: true, loading: busyIds.value.has(row.id) },
              { default: () => '更多' },
            ),
          },
        ),
      ])
    },
  },
]

const tagManagerOpen = ref(false)
</script>

<template>
  <div class="products-page">
    <NSpace vertical :size="12">
      <div class="page-heading">
        <div>
          <h2>商品管理</h2>
          <NText depth="3">{{ resultSummary }}</NText>
        </div>
        <NSpace>
          <NButton @click="tagManagerOpen = true">
            <template #icon><Tags :size="16" /></template>
            标签管理
          </NButton>
          <NButton type="primary" @click="openCreate">
            <template #icon><Plus :size="16" /></template>
            新增商品
          </NButton>
        </NSpace>
      </div>

      <NTabs v-model:value="activeCategory" type="line" animated>
        <NTabPane v-for="category in CATEGORIES" :key="category.type" :name="category.type" :tab="category.label" />
      </NTabs>

      <NCard size="small" :bordered="false">
        <div class="filter-grid">
          <NFormItem label="商品状态">
            <NSelect
              v-model:value="filters.status"
              :options="statusOptions"
              clearable
              placeholder="全部状态"
            />
          </NFormItem>
          <NFormItem label="关键词">
            <NInput
              v-model:value="filters.keyword"
              maxlength="200"
              clearable
              placeholder="名称、颜色或商品 ID"
              @keyup.enter="searchProducts"
            />
          </NFormItem>
          <NFormItem label="库存">
            <NSpace align="center" :wrap="false">
              <ClientOnly>
                <NCheckbox v-model:checked="filters.lowStock">低库存</NCheckbox>
                <template #fallback><span>低库存</span></template>
              </ClientOnly>
              <NInputNumber
                v-model:value="filters.lowStockThreshold"
                :min="0"
                :max="1000000"
                :precision="0"
                :disabled="!filters.lowStock"
                style="width: 110px"
              />
            </NSpace>
          </NFormItem>
          <NFormItem label="排序">
            <NInputGroup>
              <NSelect v-model:value="filters.sortBy" :options="sortOptions" style="min-width: 120px" />
              <NRadioGroup v-model:value="filters.ascending" size="small">
                <NRadioButton :value="false">降序</NRadioButton>
                <NRadioButton :value="true">升序</NRadioButton>
              </NRadioGroup>
            </NInputGroup>
          </NFormItem>
        </div>
        <NSpace justify="end">
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
          <NButton type="primary" :loading="loading" @click="searchProducts">查询</NButton>
        </NSpace>
      </NCard>

      <NCard size="small" :bordered="false">
        <template #header>
          <div class="table-header">
            <NSpace align="center">
              <span>商品列表</span>
              <NText v-if="checkedRowKeys.length" depth="3">已选 {{ checkedRowKeys.length }} 项</NText>
            </NSpace>
            <NSpace>
              <NButton
                size="small"
                :disabled="selectedAvailable.length === 0 || batchRunning"
                @click="batchChangeStatus('ACTIVE')"
              >
                批量上架
              </NButton>
              <NButton
                size="small"
                :disabled="selectedAvailable.length === 0 || batchRunning"
                @click="batchChangeStatus('INACTIVE')"
              >
                批量下架
              </NButton>
              <NButton
                size="small"
                :disabled="selectedDeleted.length === 0 || batchRunning"
                @click="batchRestore"
              >
                批量恢复
              </NButton>
              <NButton
                size="small"
                type="error"
                ghost
                :disabled="selectedDeleted.length === 0 || batchRunning"
                @click="batchPermanentDelete"
              >
                <template #icon><Trash2 :size="15" /></template>
                永久删除
              </NButton>
              <NButton
                size="small"
                type="error"
                ghost
                :disabled="selectedAvailable.length === 0 || batchRunning"
                @click="batchDelete"
              >
                批量移除
              </NButton>
            </NSpace>
          </div>
        </template>

        <NDataTable
          :columns="columns"
          :data="products"
          :row-key="rowKey"
          :loading="loading || batchRunning"
          :pagination="false"
          :checked-row-keys="checkedRowKeys"
          :scroll-x="1280"
          size="small"
          @update:checked-row-keys="handleCheck"
        />

        <div class="pagination-bar">
          <NPagination
            :page="pagination.page"
            :page-size="pagination.pageSize"
            :item-count="pagination.totalItems"
            :page-sizes="pageSizeOptions"
            show-size-picker
            :disabled="loading || batchRunning"
            @update:page="changePage"
            @update:page-size="changePageSize"
          />
        </div>
      </NCard>
    </NSpace>

    <ProductFormDrawer
      v-model:open="drawerVisible"
      :category="activeCategory"
      :product="editingProduct"
      :tags="tags"
      @submitted="onDrawerSubmitted"
    />

    <TagManagerDrawer
      v-model:open="tagManagerOpen"
      @changed="handleTagsChanged"
    />

    <NModal
      v-model:show="stockOpen"
      preset="card"
      title="调整库存"
      :style="{ width: 'min(460px, calc(100vw - 32px))' }"
      :mask-closable="!stockLoading"
      :closable="!stockLoading"
    >
      <template v-if="stockProduct">
        <NDescriptions :column="2" bordered size="small" label-placement="top">
          <NDescriptionsItem label="商品">{{ stockProduct.name }}</NDescriptionsItem>
          <NDescriptionsItem label="当前库存">{{ stockProduct.warehouseVolume }}</NDescriptionsItem>
        </NDescriptions>
        <NForm ref="stockFormRef" :model="stockForm" :rules="stockRules" label-placement="top" class="stock-form">
          <NFormItem label="操作">
            <NRadioGroup v-model:value="stockForm.mode">
              <NRadioButton value="IN">入库</NRadioButton>
              <NRadioButton value="OUT">出库</NRadioButton>
            </NRadioGroup>
          </NFormItem>
          <NFormItem label="数量" path="quantity">
            <NInputNumber
              v-model:value="stockForm.quantity"
              :min="1"
              :max="1000000"
              :precision="0"
              style="width: 100%"
            />
          </NFormItem>
        </NForm>
      </template>

      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="stockLoading" @click="stockOpen = false">取消</NButton>
          <NButton type="primary" :loading="stockLoading" @click="submitStockAdjustment">确认</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.products-page {
  display: flex;
  flex-direction: column;
}

.page-heading,
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-heading h2 {
  margin: 0 0 4px;
  font-size: 22px;
}

.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(240px, 100%), 1fr));
  column-gap: 12px;
  row-gap: 4px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.stock-form {
  margin-top: 16px;
}

:deep(.product-cell),
:deep(.product-tags),
:deep(.row-actions) {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.product-thumb) {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  border-radius: 4px;
  overflow: hidden;
}

:deep(.product-thumb-empty) {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #8b8b94;
  background: #f1f1f3;
  font-size: 12px;
}

:deep(.product-main) {
  min-width: 0;
}

:deep(.product-name) {
  max-width: 220px;
  overflow: hidden;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.product-meta) {
  margin-top: 3px;
  color: #8b8b94;
  font-size: 12px;
}

:deep(.product-tags) {
  flex-wrap: wrap;
}

:deep(.low-stock) {
  color: #d03050;
  font-weight: 650;
}

@media (max-width: 720px) {
  .page-heading,
  .table-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .table-header > :last-child {
    flex-wrap: wrap;
  }
}
</style>
