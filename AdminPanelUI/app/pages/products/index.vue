<script setup lang="ts">
import { computed, h, onMounted, reactive, ref, watch } from 'vue'
import type { DataTableColumns, DataTableRowKey } from 'naive-ui'
import {
  NButton,
  NImage,
  NSpace,
  NTag,
  useDialog,
  useMessage,
} from 'naive-ui'
import { CATEGORIES } from '~/composables/useProductApi'
import type {
  BikiniSuitEditable,
  BikiniSuitResponse,
  CoverUpEditable,
  CoverUpResponse,
  DressEditable,
  DressResponse,
  OnePieceSuitEditable,
  OnePieceSuitResponse,
  OnePieceSuitUpsertRequest,
  ProductListItem,
  ProductStatus,
  ProductType,
  ProductUpsertRequest,
  Tag,
} from '~/types/product'

definePageMeta({ layout: 'default' })

const api = useProductApi()
const message = useMessage()
const dialog = useDialog()

/* ===================== 品类页签 ===================== */

const activeCategory = ref<ProductType>('DRESS')

/* ===================== 数据源（全量缓存） ===================== */

// 各品类全量列表（含下架/软删除），切换页签后缓存避免重复请求；手动刷新会覆盖。
const sourceMap = reactive<Record<ProductType, ProductListItem[]>>({
  DRESS: [],
  BIKINI: [],
  ONE_PIECE: [],
  COVER_UP: [],
})
const loading = ref(false)

// 当前页签对应的源数据（响应式引用）
const currentSource = computed<ProductListItem[]>(() => sourceMap[activeCategory.value])

/* ===================== 筛选 ===================== */

const statusFilter = ref<'ALL' | ProductStatus>('ALL')
const keyword = ref('')

const statusOptions = [
  { label: '全部', value: 'ALL' },
  { label: '上架', value: 'ACTIVE' },
  { label: '下架', value: 'INACTIVE' },
  { label: '已删除', value: 'DELETED' },
]

// 内存过滤：状态 + 名称模糊
const filteredData = computed<ProductListItem[]>(() => {
  const kw = keyword.value.trim().toLowerCase()
  return currentSource.value.filter((p) => {
    if (statusFilter.value !== 'ALL' && p.status !== statusFilter.value) return false
    if (kw && !p.name.toLowerCase().includes(kw)) return false
    return true
  })
})

function resetFilter() {
  statusFilter.value = 'ALL'
  keyword.value = ''
  pagination.page = 1
}

/* ===================== 分页（客户端） ===================== */

// 分页状态：不显式注解为 PaginationProps，避免与 NDataTable 在 DOM 库上下文下
// 的 `to` 属性类型冲突；推断出的窄类型对 page/pageSize 非可选，且仍结构兼容 PaginationProps。
const pagination = reactive({
  page: 1,
  pageSize: 10,
  showSizePicker: true,
  pageSizes: [10, 20, 50, 100],
  onChange: (page: number) => {
    pagination.page = page
  },
  onUpdatePageSize: (size: number) => {
    pagination.pageSize = size
    pagination.page = 1
  },
})

// 当前页切片数据
const pagedData = computed<ProductListItem[]>(() => {
  const start = (pagination.page - 1) * pagination.pageSize
  return filteredData.value.slice(start, start + pagination.pageSize)
})

// 过滤条件变化时回到第一页
watch([statusFilter, keyword, activeCategory], () => {
  pagination.page = 1
})

/* ===================== 勾选 ===================== */

const checkedRowKeys = ref<Array<number | string>>([])

function rowKey(row: ProductListItem): DataTableRowKey {
  return row.id
}

function handleCheck(keys: Array<number | string>) {
  checkedRowKeys.value = keys
}

const hasChecked = computed(() => checkedRowKeys.value.length > 0)

/* ===================== 数据加载 ===================== */

async function loadCurrentCategory(force = false) {
  // 已有缓存且非强制刷新时跳过，避免页签来回切换重复请求
  if (!force && currentSource.value.length > 0) return
  loading.value = true
  try {
    const list = await api.list(activeCategory.value)
    sourceMap[activeCategory.value] = list ?? []
    pagination.page = 1
    checkedRowKeys.value = []
  } catch (e: any) {
    message.error(`加载列表失败：${e?.statusMessage || e?.message || '未知错误'}`)
  } finally {
    loading.value = false
  }
}

// 标签只需加载一次，供表单多选使用。
const tags = ref<Tag[]>([])
const tagsLoaded = ref(false)
async function ensureTagsLoaded() {
  if (tagsLoaded.value) return
  try {
    tags.value = await api.listTags()
    tagsLoaded.value = true
  } catch {
    // 标签加载失败不阻塞列表，忽略
  }
}

// 切换页签：无条件清空勾选（避免跨品类 id 撞号导致批量误操作），再加载对应品类
watch(activeCategory, () => {
  checkedRowKeys.value = []
  void loadCurrentCategory()
})

onMounted(() => {
  // useMessage/useDialog 在客户端可用，onMounted 内调用更稳妥
  void loadCurrentCategory(true)
  void ensureTagsLoaded()
})

/* ===================== 状态展示辅助 ===================== */

function statusTagType(s: ProductStatus): 'success' | 'warning' | 'error' {
  if (s === 'ACTIVE') return 'success'
  if (s === 'INACTIVE') return 'warning'
  return 'error'
}

function statusLabel(s: ProductStatus): string {
  if (s === 'ACTIVE') return '上架'
  if (s === 'INACTIVE') return '下架'
  return '已删除'
}

function formatPrice(p: number): string {
  if (p == null || Number.isNaN(p)) return '-'
  return p.toFixed(2)
}

function formatTime(t?: string): string {
  if (!t) return '-'
  // ISO-8601 字符串截取到分钟展示
  return t.replace('T', ' ').slice(0, 16)
}

function firstImage(row: ProductListItem): string {
  return row.images?.[0] ?? ''
}

/* ===================== Upsert 请求体组装 ===================== */

/**
 * 从响应行剥离出可编辑体（去掉 id/score/createdAt/updatedAt/tags/productType）
 * 并提取 tagIds，组装成完整 UpsertRequest，供 PUT 整体更新使用。
 * 状态切换 / 批量上架下架都走此路径。
 */
function buildUpsertRequest(
  row: ProductListItem,
  overrideStatus?: ProductStatus,
): ProductUpsertRequest {
  const tagIds = (row.tags ?? []).map((t) => t.id)
  const status = overrideStatus ?? row.status

  // 公共可编辑字段
  const base = {
    name: row.name,
    color: row.color,
    price: row.price,
    warehouseVolume: row.warehouseVolume,
    salesVolume: row.salesVolume,
    status,
    highlight: row.highlight ?? [],
    images: row.images ?? [],
    fitSense: row.fitSense,
    description: row.description,
    designAndExtras: row.designAndExtras ?? [],
    careInstructions: row.careInstructions ?? [],
  }

  switch (row.productType) {
    case 'DRESS': {
      const r = row as DressResponse
      const dress: DressEditable = {
        ...base,
        size: r.size,
        length: r.length,
        silhouette: r.silhouette,
        neckline: r.neckline,
        sleeveType: r.sleeveType,
        fabric: r.fabric,
      }
      return { dress, tagIds }
    }
    case 'BIKINI': {
      const r = row as BikiniSuitResponse
      const bikiniSuit: BikiniSuitEditable = {
        ...base,
        topSize: r.topSize,
        bottomSize: r.bottomSize,
      }
      return { bikiniSuit, tagIds }
    }
    case 'ONE_PIECE': {
      const r = row as OnePieceSuitResponse
      const onePieceSuit: OnePieceSuitEditable = {
        ...base,
        size: r.size,
        supportLevel: r.supportLevel,
        coverage: r.coverage,
        torsoFit: r.torsoFit,
        neckline: r.neckline,
        backStyle: r.backStyle,
        tummyControl: r.tummyControl,
        removablePadding: r.removablePadding,
      }
      const req: OnePieceSuitUpsertRequest = { onePieceSuit, tagIds }
      return req
    }
    case 'COVER_UP': {
      const r = row as CoverUpResponse
      const coverUp: CoverUpEditable = {
        ...base,
        style: r.style,
        sheerLevel: r.sheerLevel,
        fabric: r.fabric,
        size: r.size,
      }
      return { coverUp, tagIds }
    }
  }
}

/* ===================== 并发限流工具 ===================== */

/**
 * 限并发执行任务，返回与输入等长的结果数组（allSettled 风格）。
 * 用于批量上架/下架/删除，限制同时 5 个请求。
 */
async function runWithConcurrency<T>(
  tasks: Array<() => Promise<T>>,
  limit = 5,
): Promise<Array<{ ok: true; value: T } | { ok: false; reason: any }>> {
  const results: Array<{ ok: true; value: T } | { ok: false; reason: any }> = []
  let cursor = 0
  const workers: Promise<void>[] = []
  const worker = async () => {
    while (cursor < tasks.length) {
      const idx = cursor++
      const task = tasks[idx]
      if (!task) break
      try {
        const value = await task()
        results[idx] = { ok: true, value }
      } catch (reason) {
        results[idx] = { ok: false, reason }
      }
    }
  }
  const workerCount = Math.min(limit, tasks.length)
  for (let i = 0; i < workerCount; i++) workers.push(worker())
  await Promise.all(workers)
  return results
}

/* ===================== 表单抽屉 ===================== */

// ProductFormDrawer 约定契约（由表单 agent 实现）：
//   props: { category: ProductType, modelValue: boolean, product?: ProductListItem | null }
//   emits: 'update:modelValue', 'saved'
const drawerVisible = ref(false)
const editingProduct = ref<ProductListItem | null>(null)

function openCreate() {
  editingProduct.value = null
  drawerVisible.value = true
}

function openEdit(row: ProductListItem) {
  editingProduct.value = row
  drawerVisible.value = true
}

function onDrawerSaved() {
  // 新增/编辑成功后刷新当前品类列表（强制重拉，覆盖缓存）
  void loadCurrentCategory(true)
}

/* ===================== 行操作：状态切换 / 删除 ===================== */

const togglingIds = ref<Set<number>>(new Set())

function isToggling(id: number): boolean {
  return togglingIds.value.has(id)
}

async function toggleStatus(row: ProductListItem) {
  if (row.status === 'DELETED') return
  const next: ProductStatus = row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  togglingIds.value.add(row.id)
  const msg = message.loading(`${next === 'ACTIVE' ? '上架' : '下架'}中...`, { duration: 0 })
  try {
    const payload = buildUpsertRequest(row, next)
    const updated = await api.update(row.productType, row.id, payload)
    // 用 PUT 返回的完整实体替换本地行（含后端刷新的 updatedAt），避免时间戳陈旧
    const idx = currentSource.value.findIndex((p) => p.id === row.id && p.productType === row.productType)
    if (idx >= 0 && updated) {
      currentSource.value[idx] = { ...updated, productType: row.productType } as ProductListItem
    }
    message.success(`已${next === 'ACTIVE' ? '上架' : '下架'}`)
  } catch (e: any) {
    message.error(`切换失败：${e?.statusMessage || e?.message || '未知错误'}`)
  } finally {
    msg.destroy()
    togglingIds.value.delete(row.id)
  }
}

function confirmDelete(row: ProductListItem) {
  dialog.warning({
    title: '确认移除',
    content: '软删除不可在后台恢复，仅标记为已删除。是否继续？',
    positiveText: '移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      try {
        await api.remove(row.productType, row.id)
        // 本地标记为 DELETED 而非移除，便于筛选切换查看
        const idx = currentSource.value.findIndex((p) => p.id === row.id && p.productType === row.productType)
        if (idx >= 0) {
          const existing = currentSource.value[idx]
          if (existing) currentSource.value[idx] = { ...existing, status: 'DELETED' }
        }
        checkedRowKeys.value = checkedRowKeys.value.filter((k) => k !== row.id)
        message.success('已移除')
      } catch (e: any) {
        message.error(`移除失败：${e?.statusMessage || e?.message || '未知错误'}`)
      }
    },
  })
}

/* ===================== 批量操作 ===================== */

const batchRunning = ref(false)
const batchProgress = reactive({ done: 0, total: 0, success: 0, failed: 0 })

function selectedRows(): ProductListItem[] {
  const ids = new Set(checkedRowKeys.value)
  return currentSource.value.filter((p) => ids.has(p.id))
}

function resetBatchProgress(total: number) {
  batchProgress.done = 0
  batchProgress.total = total
  batchProgress.success = 0
  batchProgress.failed = 0
}

// 批量改状态（上架/下架）：循环 PUT，限并发 5
async function batchChangeStatus(target: 'ACTIVE' | 'INACTIVE') {
  const rows = selectedRows().filter((r) => r.status !== 'DELETED' && r.status !== target)
  if (rows.length === 0) {
    message.info('没有符合条件的商品')
    return
  }
  batchRunning.value = true
  resetBatchProgress(rows.length)
  const actionText = target === 'ACTIVE' ? '上架' : '下架'
  const progressMsg = message.loading(`批量${actionText}中 0/${rows.length}`, { duration: 0 })

  const tasks = rows.map((row) => async () => {
    try {
      const payload = buildUpsertRequest(row, target)
      const updated = await api.update(row.productType, row.id, payload)
      batchProgress.success++
      // 本地同步：用返回实体替换（含刷新的 updatedAt）
      const idx = currentSource.value.findIndex((p) => p.id === row.id && p.productType === row.productType)
      if (idx >= 0 && updated) currentSource.value[idx] = { ...updated, productType: row.productType } as ProductListItem
    } catch (e: any) {
      batchProgress.failed++
      throw e
    } finally {
      batchProgress.done++
      progressMsg.content = `批量${actionText}中 ${batchProgress.done}/${rows.length}`
    }
  })

  const results = await runWithConcurrency(tasks, 5)
  progressMsg.destroy()

  const failures: Array<{ id: number; reason: string }> = []
  results.forEach((r, i) => {
    if (r.ok) return
    const failedRow = rows[i]
    if (!failedRow) return
    failures.push({ id: failedRow.id, reason: r.reason?.statusMessage || r.reason?.message || '未知错误' })
  })

  if (failures.length === 0) {
    message.success(`批量${actionText}完成，成功 ${batchProgress.success} 条`)
  } else {
    message.warning(`批量${actionText}完成：成功 ${batchProgress.success}，失败 ${failures.length}`)
    failures.forEach((f) => {
      message.error(`ID ${f.id} 失败：${f.reason}`, { duration: 5000 })
    })
  }
  checkedRowKeys.value = []
  batchRunning.value = false
}

// 批量删除（软删除）：循环 DELETE，限并发 5
function batchDelete() {
  const rows = selectedRows().filter((r) => r.status !== 'DELETED')
  if (rows.length === 0) {
    message.info('没有可移除的商品')
    return
  }
  dialog.warning({
    title: '确认批量移除',
    content: `将对 ${rows.length} 条商品执行软删除（不可在后台恢复，仅标记为已删除）。是否继续？`,
    positiveText: '批量移除',
    negativeText: '取消',
    onPositiveClick: async () => {
      batchRunning.value = true
      resetBatchProgress(rows.length)
      const progressMsg = message.loading(`批量移除中 0/${rows.length}`, { duration: 0 })

      const tasks = rows.map((row) => async () => {
        try {
          await api.remove(row.productType, row.id)
          batchProgress.success++
          const idx = currentSource.value.findIndex((p) => p.id === row.id && p.productType === row.productType)
          if (idx >= 0) {
            const existing = currentSource.value[idx]
            if (existing) currentSource.value[idx] = { ...existing, status: 'DELETED' }
          }
        } catch (e: any) {
          batchProgress.failed++
          throw e
        } finally {
          batchProgress.done++
          progressMsg.content = `批量移除中 ${batchProgress.done}/${rows.length}`
        }
      })

      const results = await runWithConcurrency(tasks, 5)
      progressMsg.destroy()

      const failures: Array<{ id: number; reason: string }> = []
      results.forEach((r, i) => {
        if (r.ok) return
        const failedRow = rows[i]
        if (!failedRow) return
        failures.push({ id: failedRow.id, reason: r.reason?.statusMessage || r.reason?.message || '未知错误' })
      })

      if (failures.length === 0) {
        message.success(`批量移除完成，成功 ${batchProgress.success} 条`)
      } else {
        message.warning(`批量移除完成：成功 ${batchProgress.success}，失败 ${failures.length}`)
        failures.forEach((f) => {
          message.error(`ID ${f.id} 失败：${f.reason}`, { duration: 5000 })
        })
      }
      checkedRowKeys.value = []
      batchRunning.value = false
    },
  })
}

/* ===================== 表格列定义 ===================== */

const columns = computed<DataTableColumns<ProductListItem>>(() => [
  { type: 'selection' },
  {
    title: '图片',
    key: 'image',
    width: 70,
    render: (row) => {
      const url = firstImage(row)
      if (!url) return h('span', { style: 'color: #c0c4cc' }, '无图')
      return h(NImage, { src: url, width: 44, height: 44, objectFit: 'cover', previewDisabled: true })
    },
  },
  { title: '名称', key: 'name', minWidth: 160, ellipsis: { tooltip: true } },
  { title: '颜色', key: 'color', width: 100 },
  {
    title: '价格',
    key: 'price',
    width: 100,
    render: (row) => `¥${formatPrice(row.price)}`,
  },
  { title: '库存', key: 'warehouseVolume', width: 80 },
  { title: '销量', key: 'salesVolume', width: 80 },
  {
    title: '状态',
    key: 'status',
    width: 90,
    render: (row) =>
      h(NTag, { type: statusTagType(row.status), size: 'small', round: true }, { default: () => statusLabel(row.status) }),
  },
  {
    title: '评分',
    key: 'score',
    width: 70,
    render: (row) => (row.score != null ? row.score.toFixed(1) : '-'),
  },
  {
    title: '更新时间',
    key: 'updatedAt',
    width: 150,
    render: (row) => formatTime(row.updatedAt),
  },
  {
    title: '操作',
    key: 'actions',
    width: 200,
    fixed: 'right',
    render: (row) => {
      const children: ReturnType<typeof h>[] = []
      children.push(
        h(
          NButton,
          { size: 'small', tertiary: true, disabled: batchRunning.value, onClick: () => openEdit(row) },
          { default: () => '编辑' },
        ),
      )
      if (row.status !== 'DELETED') {
        children.push(
          h(
            NButton,
            {
              size: 'small',
              tertiary: true,
              type: row.status === 'ACTIVE' ? 'warning' : 'success',
              loading: isToggling(row.id),
              disabled: batchRunning.value,
              onClick: () => toggleStatus(row),
            },
            { default: () => (row.status === 'ACTIVE' ? '下架' : '上架') },
          ),
        )
        // 已 DELETED 行不渲染移除按钮（DELETE 仅软删除，重复删除无意义）
        children.push(
          h(
            NButton,
            {
              size: 'small',
              tertiary: true,
              type: 'error',
              disabled: batchRunning.value,
              onClick: () => confirmDelete(row),
            },
            { default: () => '移除' },
          ),
        )
      }
      return h(NSpace, { size: 4 }, () => children)
    },
  },
])
</script>

<template>
  <div class="products-page">
    <NTabs v-model:value="activeCategory" type="line" animated>
      <NTabPane v-for="cat in CATEGORIES" :key="cat.type" :name="cat.type" :tab="cat.label">
        <!-- 筛选栏 -->
        <NCard size="small" :bordered="false" class="filter-bar">
          <NSpace align="center" wrap>
            <NSelect
              v-model:value="statusFilter"
              :options="statusOptions"
              style="width: 140px"
              placeholder="状态"
            />
            <NInput
              v-model:value="keyword"
              placeholder="按名称模糊搜索"
              clearable
              style="width: 240px"
              @keyup.enter="pagination.page = 1"
            />
            <NButton type="primary" @click="pagination.page = 1">搜索</NButton>
            <NButton @click="resetFilter">重置</NButton>
            <NButton quaternary @click="loadCurrentCategory(true)">刷新</NButton>
          </NSpace>
        </NCard>

        <!-- 操作栏 -->
        <NCard size="small" :bordered="false" class="action-bar">
          <NSpace align="center">
            <NButton type="primary" @click="openCreate">新增商品</NButton>
            <NButton :disabled="!hasChecked || batchRunning" @click="batchChangeStatus('ACTIVE')">
              批量上架
            </NButton>
            <NButton :disabled="!hasChecked || batchRunning" @click="batchChangeStatus('INACTIVE')">
              批量下架
            </NButton>
            <NButton :disabled="!hasChecked || batchRunning" type="error" ghost @click="batchDelete">
              批量移除
            </NButton>
            <span v-if="hasChecked" class="checked-tip">已选 {{ checkedRowKeys.length }} 项</span>
          </NSpace>
        </NCard>

        <!-- 数据表 -->
        <NDataTable
          :columns="columns"
          :data="pagedData"
          :row-key="rowKey"
          :loading="loading"
          :pagination="pagination"
          :checked-row-keys="checkedRowKeys"
          :remote="false"
          :scroll-x="1100"
          size="small"
          @update:checked-row-keys="handleCheck"
        />
      </NTabPane>
    </NTabs>

    <!-- 新增/编辑抽屉（Nuxt 自动导入 app/components 下组件） -->
    <ProductFormDrawer
      v-model:open="drawerVisible"
      :category="activeCategory"
      :product="editingProduct"
      :tags="tags"
      @submitted="onDrawerSaved"
    />
  </div>
</template>

<style scoped>
.products-page {
  display: flex;
  flex-direction: column;
}

.filter-bar {
  margin-bottom: 12px;
}

.action-bar {
  margin-bottom: 12px;
}

.checked-tip {
  color: #909399;
  font-size: 13px;
}
</style>
