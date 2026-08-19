<script setup lang="ts">
import { ImageOff } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, TagProps } from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import { CARRIER_OPTIONS } from '~/composables/useShipmentApi'
import type { OrderDetail } from '~/types/order'
import type {
  AdminShipment,
  CarrierCode,
  ShipmentItem,
  ShipmentMutationResponse,
  ShipmentStatus,
  ShipmentTrack,
} from '~/types/shipment'

definePageMeta({ layout: 'default' })

type ShipmentAction = 'dispatch' | 'cancel' | 'delivered'

interface AllocationInput {
  orderItemId: number | null
  quantity: number | null
}

interface ProductSnapshot {
  productId?: number | string
  variantId?: number | string
  sku?: string
  name?: string
  color?: string
  size?: string
  primaryImage?: string | null
  variantAttributes?: Record<string, unknown>
}

const route = useRoute()
const router = useRouter()
const api = useShipmentApi()
const orderApi = useOrderApi()
const message = useMessage()
const { confirmDeleteRequest } = useDeleteConfirmation()
const loading = ref(false)
const detailLoading = ref(false)
const orderLoading = ref(false)
const creating = ref(false)
const actionLoading = ref(false)
const deletingShipmentNo = ref<string | null>(null)
const orderNo = ref(typeof route.query.order_no === 'string' ? route.query.order_no : '')
const trackingNoFilter = ref('')
const statusFilter = ref<ShipmentStatus | null>(null)
const carrierFilter = ref<CarrierCode | null>(null)
const hasErrorFilter = ref<'error' | 'ok' | null>(null)
const orderDetail = ref<OrderDetail | null>(null)
const shipments = ref<AdminShipment[]>([])
const selectedShipment = ref<AdminShipment | null>(null)
const detailOpen = ref(false)
const createOpen = ref(false)
const actionOpen = ref(false)
const actionType = ref<ShipmentAction>('dispatch')
const actionShipment = ref<AdminShipment | null>(null)

const pagination = reactive({
  page: 1,
  pageSize: 25,
  pageCount: 1,
  totalItems: 0,
})

const statusOptions = [
  { label: '待生成面单', value: 'LABEL_PENDING' },
  { label: '待发货', value: 'LABEL_CREATED' },
  { label: '取消处理中', value: 'CANCEL_PENDING' },
  { label: '运输中', value: 'IN_TRANSIT' },
  { label: '派送中', value: 'OUT_FOR_DELIVERY' },
  { label: '已签收', value: 'DELIVERED' },
  { label: '已取消', value: 'CANCELLED' },
  { label: '已删除', value: 'DELETED' },
]

const errorOptions = [
  { label: '存在异常', value: 'error' },
  { label: '无异常', value: 'ok' },
]

const createForm = reactive<{
  carrier: CarrierCode
  trackingNo: string
  note: string
  allocations: AllocationInput[]
}>({
  carrier: 'manual',
  trackingNo: '',
  note: '',
  allocations: [{ orderItemId: null, quantity: 1 }],
})

const actionForm = reactive({
  note: '',
  reason: '',
  occurredAt: null as number | null,
})

const selected = computed(() => selectedShipment.value?.shipment ?? null)
const shipmentItemViews = computed(() => selected.value?.items.map((item) => {
  const snapshot = parseProductSnapshot(item.product_snapshot)
  const attributes = snapshot?.variantAttributes && typeof snapshot.variantAttributes === 'object' && !Array.isArray(snapshot.variantAttributes)
    ? snapshot.variantAttributes
    : {}
  const topSize = snapshotText(attributes.top_size)
  const bottomSize = snapshotText(attributes.bottom_size)
  const size = snapshotText(snapshot?.size) || [topSize, bottomSize].filter(Boolean).join(' / ')
  const specifications = [
    snapshotText(snapshot?.color) ? { label: '颜色', value: snapshotText(snapshot?.color) as string } : null,
    size ? { label: '尺码', value: size } : null,
    ...Object.entries(attributes)
      .map(([key, value]) => ({ label: key, value: snapshotText(value) }))
      .filter((value): value is { label: string; value: string } => !['top_size', 'bottom_size'].includes(value.label) && value.value !== null),
  ].filter((value): value is { label: string; value: string } => value !== null)

  return {
    item,
    snapshot,
    name: snapshotText(snapshot?.name) || '商品快照无法解析',
    sku: snapshotText(snapshot?.sku),
    image: snapshotText(snapshot?.primaryImage),
    specifications,
  }
}) ?? [])
const availableOrderItems = computed(() => orderDetail.value?.items.filter(item => item.remaining_quantity > 0) ?? [])
const allocationOptions = computed(() => availableOrderItems.value.map(item => ({
  label: `#${item.id} ${productSnapshotLabel(item.product_snapshot)} (${item.remaining_quantity} 件)`,
  value: item.id,
})))
const shipmentSummary = computed(() => ({
  total: shipments.value.length,
  readyToDispatch: shipments.value.filter(({ shipment }) => shipment.status === 'LABEL_CREATED').length,
  inTransit: shipments.value.filter(({ shipment }) => (
    shipment.status === 'IN_TRANSIT' || shipment.status === 'OUT_FOR_DELIVERY'
  )).length,
  delivered: shipments.value.filter(({ shipment }) => shipment.status === 'DELIVERED').length,
}))

const resultSummary = computed(() => {
  if (loading.value) return '正在加载运单…'
  if (shipments.value.length === 0) return '当前条件下没有运单'
  return `第 ${pagination.page} / ${pagination.pageCount} 页，共 ${pagination.totalItems} 个运单`
})

const actionTitle = computed(() => {
  const titles: Record<ShipmentAction, string> = {
    dispatch: '确认发货',
    cancel: '取消运单',
    delivered: '手动确认签收',
  }
  return titles[actionType.value]
})

function carrierLabel(carrier: CarrierCode): string {
  return CARRIER_OPTIONS.find(option => option.value === carrier)?.label ?? carrier
}

function statusLabel(status: ShipmentStatus): string {
  const labels: Record<ShipmentStatus, string> = {
    LABEL_PENDING: '待生成面单',
    LABEL_CREATED: '待发货',
    CANCEL_PENDING: '取消处理中',
    IN_TRANSIT: '运输中',
    OUT_FOR_DELIVERY: '派送中',
    DELIVERED: '已签收',
    CANCELLED: '已取消',
    DELETED: '已删除',
  }
  return labels[status]
}

function statusTagType(status: ShipmentStatus): TagProps['type'] {
  const types: Record<ShipmentStatus, TagProps['type']> = {
    LABEL_PENDING: 'warning',
    LABEL_CREATED: 'info',
    CANCEL_PENDING: 'warning',
    IN_TRANSIT: 'info',
    OUT_FOR_DELIVERY: 'warning',
    DELIVERED: 'success',
    CANCELLED: 'error',
    DELETED: 'default',
  }
  return types[status]
}

function trackTagType(status: ShipmentTrack['normalized_status']): 'default' | 'info' | 'success' | 'warning' | 'error' {
  const types: Record<ShipmentTrack['normalized_status'], 'default' | 'info' | 'success' | 'warning' | 'error'> = {
    IN_TRANSIT: 'info',
    OUT_FOR_DELIVERY: 'warning',
    DELIVERED: 'success',
    EXCEPTION: 'error',
    UNKNOWN: 'default',
  }
  return types[status]
}

function allocationLabel(status: ShipmentItem['allocation_status']): string {
  return status === 'ALLOCATED' ? '已分配' : '已释放'
}

function formatDate(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return '未知错误'
}

function productSnapshotLabel(snapshot: string): string {
  const value = parseProductSnapshot(snapshot)
  if (!value) return snapshot
  const topSize = value.variantAttributes?.top_size
  const bottomSize = value.variantAttributes?.bottom_size
  return [
    value.name,
    value.color,
    value.size || [topSize, bottomSize].filter(item => typeof item === 'string' && item.trim()).join('/'),
    value.sku,
  ].filter(item => typeof item === 'string' && item.trim()).join(' · ') || snapshot
}

function snapshotText(value: unknown): string | null {
  return typeof value === 'string' && value.trim() ? value.trim() : null
}

function parseProductSnapshot(snapshot: string): ProductSnapshot | null {
  try {
    const value: unknown = JSON.parse(snapshot)
    return value && typeof value === 'object' && !Array.isArray(value) ? value as ProductSnapshot : null
  } catch {
    return null
  }
}

function canDispatch(shipment: AdminShipment): boolean {
  return shipment.shipment.status === 'LABEL_CREATED'
}

function canCancel(shipment: AdminShipment): boolean {
  return shipment.shipment.status === 'LABEL_PENDING' || shipment.shipment.status === 'LABEL_CREATED'
}

function canMarkDelivered(shipment: AdminShipment): boolean {
  const { carrier, status } = shipment.shipment
  return carrier === 'manual' && !['DELIVERED', 'CANCEL_PENDING', 'CANCELLED', 'DELETED'].includes(status)
}

function canDeleteShipment(shipment: AdminShipment): boolean {
  return ['DELIVERED', 'CANCELLED', 'DELETED'].includes(shipment.shipment.status)
}

function confirmDeleteShipment(shipment: AdminShipment) {
  const shipmentNo = shipment.shipment.shipment_no
  const permanent = shipment.shipment.status === 'DELETED'
  confirmDeleteRequest({
    tone: permanent ? 'error' : 'warning',
    title: permanent ? '永久删除运单' : '删除运单',
    content: permanent
      ? `确认永久删除运单 ${shipmentNo}？相关商品行和物流轨迹将从数据库移除，且无法恢复。`
      : `确认删除运单 ${shipmentNo}？本次仅逻辑删除，并释放仍占用的订单商品行。`,
    positiveText: permanent ? '永久删除' : '删除',
    onConfirm: async () => {
      deletingShipmentNo.value = shipmentNo
      try {
        if (permanent) {
          await api.permanentlyDeleteShipment(shipmentNo)
          message.success(`运单 ${shipmentNo} 已永久删除`)
        } else {
          await api.deleteShipment(shipmentNo)
          message.success(`运单 ${shipmentNo} 已逻辑删除`)
        }
        if (selectedShipment.value?.shipment.shipment_no === shipmentNo) {
          selectedShipment.value = null
          detailOpen.value = false
        }
        await loadShipments()
      } catch (error) {
        message.error(`${permanent ? '永久删除' : '删除'}失败：${errorMessage(error)}`)
      } finally {
        deletingShipmentNo.value = null
      }
    },
  })
}

function resetCreateForm() {
  createForm.carrier = 'manual'
  createForm.trackingNo = ''
  createForm.note = ''
  createForm.allocations = availableOrderItems.value.map(item => ({
    orderItemId: item.id,
    quantity: item.remaining_quantity,
  }))
  if (createForm.allocations.length === 0) {
    createForm.allocations = [{ orderItemId: null, quantity: null }]
  }
}

function closeCreate() {
  if (creating.value) return
  createOpen.value = false
  resetCreateForm()
}

function addAllocation() {
  createForm.allocations.push({ orderItemId: null, quantity: null })
}

function selectAllocationItem(allocation: AllocationInput, orderItemId: number | null) {
  allocation.orderItemId = orderItemId
  allocation.quantity = availableOrderItems.value.find(item => item.id === orderItemId)?.remaining_quantity ?? null
}

function removeAllocation(index: number) {
  if (createForm.allocations.length === 1) return
  createForm.allocations.splice(index, 1)
}

function updateSelectedShipment(result: ShipmentMutationResponse) {
  const shipmentNo = result.shipment.shipment_no
  const index = shipments.value.findIndex(item => item.shipment.shipment_no === shipmentNo)
  if (index >= 0) {
    shipments.value[index] = result
  } else {
    shipments.value = [result, ...shipments.value]
  }
  if (selectedShipment.value?.shipment.shipment_no === shipmentNo) {
    selectedShipment.value = result
  }
}

async function loadShipments() {
  loading.value = true
  try {
    const data = await api.listAll({
      page: pagination.page,
      size: pagination.pageSize,
      status: statusFilter.value ?? undefined,
      carrier: carrierFilter.value ?? undefined,
      order_no: orderNo.value.trim() || undefined,
      tracking_no: trackingNoFilter.value.trim() || undefined,
      has_error: hasErrorFilter.value === null ? undefined : hasErrorFilter.value === 'error',
    })
    shipments.value = data.list ?? []
    pagination.pageCount = Math.max(data.pagination.total_pages, 1)
    pagination.totalItems = data.pagination.total_items
    if (selectedShipment.value) {
      const matched = shipments.value.find(item => item.shipment.shipment_no === selectedShipment.value?.shipment.shipment_no)
      selectedShipment.value = matched ?? null
      if (!matched) detailOpen.value = false
    }
  } catch (error) {
    shipments.value = []
    selectedShipment.value = null
    detailOpen.value = false
    message.error(`加载运单失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function searchShipments() {
  pagination.page = 1
  const normalizedOrderNo = orderNo.value.trim() || undefined
  await router.replace({ query: { ...route.query, order_no: normalizedOrderNo } })
  await loadShipments()
}

async function changePage(page: number) {
  pagination.page = page
  await loadShipments()
}

async function changePageSize(pageSize: number) {
  pagination.pageSize = pageSize
  pagination.page = 1
  await loadShipments()
}

async function resetSearch() {
  orderNo.value = ''
  trackingNoFilter.value = ''
  statusFilter.value = null
  carrierFilter.value = null
  hasErrorFilter.value = null
  pagination.page = 1
  selectedShipment.value = null
  detailOpen.value = false
  await router.replace({ query: { ...route.query, order_no: undefined } })
  await loadShipments()
}

async function openCreate() {
  if (!orderNo.value.trim()) {
    message.warning('请先填写需要发货的订单号')
    return
  }
  orderLoading.value = true
  try {
    orderDetail.value = await orderApi.detail(orderNo.value.trim())
    if (availableOrderItems.value.length === 0) {
      message.warning('该订单没有可分配的商品行')
      return
    }
    resetCreateForm()
    createOpen.value = true
  } catch (error) {
    message.error(`加载订单商品失败：${errorMessage(error)}`)
  } finally {
    orderLoading.value = false
  }
}

function validateCreateForm(): boolean {
  if (createForm.trackingNo.trim().length > 64) {
    message.warning('物流追踪号不能超过 64 个字符')
    return false
  }
  if (createForm.note.trim().length > 200) {
    message.warning('运单备注不能超过 200 个字符')
    return false
  }
  if (createForm.allocations.length === 0 || createForm.allocations.length > 50) {
    message.warning('订单商品行数量必须在 1 到 50 之间')
    return false
  }
  if (createForm.allocations.some(allocation => (
    !Number.isInteger(allocation.orderItemId) || !allocation.orderItemId || allocation.orderItemId < 1
      || !Number.isInteger(allocation.quantity) || !allocation.quantity || allocation.quantity < 1
  ))) {
    message.warning('订单商品行 ID 和发货数量必须为大于 0 的整数')
    return false
  }
  const itemIds = createForm.allocations.map(allocation => allocation.orderItemId)
  if (new Set(itemIds).size !== itemIds.length) {
    message.warning('同一订单商品行不能重复选择')
    return false
  }
  return true
}

async function submitCreate() {
  if (!validateCreateForm()) return

  creating.value = true
  try {
    const result = await api.create(orderNo.value.trim(), {
      carrier_code: createForm.carrier,
      tracking_no: createForm.trackingNo.trim() || undefined,
      order_item_ids: createForm.allocations.map(allocation => allocation.orderItemId as number),
      quantities: createForm.allocations.map(allocation => allocation.quantity as number),
      note: createForm.note.trim() || undefined,
    })
    updateSelectedShipment(result)
    createOpen.value = false
    resetCreateForm()
    message.success(`运单 ${result.shipment.shipment_no} 已创建`)
    await loadShipments()
  } catch (error) {
    message.error(`创建运单失败：${errorMessage(error)}`)
  } finally {
    creating.value = false
  }
}

async function openDetail(shipment: AdminShipment) {
  selectedShipment.value = shipment
  detailOpen.value = true
  detailLoading.value = true
  try {
    selectedShipment.value = await api.get(shipment.shipment.shipment_no)
  } catch (error) {
    message.error(`加载运单详情失败：${errorMessage(error)}`)
  } finally {
    detailLoading.value = false
  }
}

function openAction(type: ShipmentAction, shipment: AdminShipment) {
  actionType.value = type
  actionShipment.value = shipment
  actionForm.note = shipment.note ?? ''
  actionForm.reason = ''
  actionForm.occurredAt = null
  actionOpen.value = true
}

function closeAction() {
  if (actionLoading.value) return
  actionOpen.value = false
  actionShipment.value = null
  actionForm.note = ''
  actionForm.reason = ''
  actionForm.occurredAt = null
}

function validateActionForm(): boolean {
  if (actionType.value === 'dispatch' && actionForm.note.trim().length > 200) {
    message.warning('运单备注不能超过 200 个字符')
    return false
  }
  if ((actionType.value === 'cancel' || actionType.value === 'delivered') && !actionForm.reason.trim()) {
    message.warning('请填写处理原因')
    return false
  }
  if (actionForm.reason.trim().length > 200) {
    message.warning('处理原因不能超过 200 个字符')
    return false
  }
  return true
}

async function submitAction() {
  const shipment = actionShipment.value
  if (!shipment || !validateActionForm()) return

  actionLoading.value = true
  try {
    let result: ShipmentMutationResponse
    if (actionType.value === 'dispatch') {
      result = await api.dispatch(shipment.shipment.shipment_no, {
        note: actionForm.note.trim() || undefined,
      })
    } else if (actionType.value === 'cancel') {
      result = await api.cancel(shipment.shipment.shipment_no, { reason: actionForm.reason.trim() })
    } else {
      result = await api.markDelivered(shipment.shipment.shipment_no, {
        occurred_at: actionForm.occurredAt ? new Date(actionForm.occurredAt).toISOString() : undefined,
        reason: actionForm.reason.trim(),
      })
    }
    updateSelectedShipment(result)
    actionOpen.value = false
    actionShipment.value = null
    message.success(`运单 ${result.shipment.shipment_no} 已${actionTitle.value}`)
    await loadShipments()
  } catch (error) {
    message.error(`${actionTitle.value}失败：${errorMessage(error)}`)
  } finally {
    actionLoading.value = false
  }
}

function openExternalUrl(url: string | null) {
  if (url) window.open(url, '_blank', 'noopener,noreferrer')
}

const columns: DataTableColumns<AdminShipment> = [
  {
    title: '运单号',
    key: 'shipment_no',
    width: 190,
    fixed: 'left',
    ellipsis: { tooltip: true },
    render: row => h(
      NButton,
      { text: true, type: 'primary', onClick: () => openDetail(row) },
      { default: () => row.shipment.shipment_no },
    ),
  },
  {
    title: '订单号',
    key: 'order_no',
    width: 190,
    ellipsis: { tooltip: true },
    render: row => row.shipment.order_no,
  },
  {
    title: '承运商',
    key: 'carrier',
    width: 120,
    render: row => carrierLabel(row.shipment.carrier),
  },
  {
    title: '追踪号',
    key: 'tracking_no',
    width: 170,
    ellipsis: { tooltip: true },
    render: row => row.shipment.tracking_no ?? '-',
  },
  {
    title: '状态',
    key: 'status',
    width: 120,
    render: row => h(
      NTag,
      { size: 'small', bordered: false, type: statusTagType(row.shipment.status) },
      { default: () => statusLabel(row.shipment.status) },
    ),
  },
  {
    title: '商品行',
    key: 'items',
    width: 90,
    align: 'right',
    render: row => row.shipment.items.length,
  },
  {
    title: '最新轨迹',
    key: 'last_track_at',
    width: 180,
    render: row => formatDate(row.shipment.last_track_at),
  },
  {
    title: '操作',
    key: 'actions',
    width: 370,
    fixed: 'right',
    render: row => h('div', { class: 'table-actions' }, [
      h(
        NButton,
        { size: 'small', tertiary: true, onClick: () => openDetail(row) },
        { default: () => '详情' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'primary',
          disabled: !canDispatch(row),
          onClick: () => openAction('dispatch', row),
        },
        { default: () => '发货' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'warning',
          disabled: !canMarkDelivered(row),
          onClick: () => openAction('delivered', row),
        },
        { default: () => '签收' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'error',
          disabled: !canCancel(row),
          onClick: () => openAction('cancel', row),
        },
        { default: () => '取消' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'error',
          disabled: !canDeleteShipment(row),
          loading: deletingShipmentNo.value === row.shipment.shipment_no,
          onClick: () => confirmDeleteShipment(row),
        },
        { default: () => row.shipment.status === 'DELETED' ? '永久删除' : '删除' },
      ),
    ]),
  },
]

onMounted(() => {
  void loadShipments()
})
</script>

<template>
  <div class="shipment-page">
    <NSpace vertical :size="12">
      <div class="page-heading">
        <div>
          <h2>物流管理</h2>
          <NText depth="3">按订单查询运单、处理发货并跟进物流轨迹。</NText>
        </div>
        <NSpace>
          <NButton :loading="loading" @click="loadShipments">刷新</NButton>
          <NButton type="primary" :loading="orderLoading" :disabled="loading || !orderNo.trim()" @click="openCreate">
            创建运单
          </NButton>
        </NSpace>
      </div>

      <NCard size="small" :bordered="false">
        <NGrid cols="1 s:2 m:5" :x-gap="12" :y-gap="4" responsive="screen">
          <NFormItemGi label="订单号">
            <NInput
              v-model:value="orderNo"
              maxlength="32"
              clearable
              placeholder="输入订单号"
              :disabled="loading"
              @keyup.enter="searchShipments"
            />
          </NFormItemGi>
          <NFormItemGi label="物流追踪号">
            <NInput
              v-model:value="trackingNoFilter"
              maxlength="64"
              clearable
              placeholder="模糊查询"
              :disabled="loading"
              @keyup.enter="searchShipments"
            />
          </NFormItemGi>
          <NFormItemGi label="运单状态">
            <NSelect v-model:value="statusFilter" :options="statusOptions" clearable placeholder="全部状态" />
          </NFormItemGi>
          <NFormItemGi label="承运商">
            <NSelect v-model:value="carrierFilter" :options="CARRIER_OPTIONS" clearable placeholder="全部承运商" />
          </NFormItemGi>
          <NFormItemGi label="同步状态">
            <NSelect v-model:value="hasErrorFilter" :options="errorOptions" clearable placeholder="全部" />
          </NFormItemGi>
        </NGrid>
        <NSpace justify="end">
          <NButton :disabled="loading" @click="resetSearch">重置</NButton>
          <NButton type="primary" :loading="loading" @click="searchShipments">查询</NButton>
        </NSpace>
      </NCard>

      <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="12" responsive="screen">
        <NGridItem>
          <NCard size="small" :bordered="false"><NStatistic label="运单数量" :value="shipmentSummary.total" /></NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false"><NStatistic label="待发货" :value="shipmentSummary.readyToDispatch" /></NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false"><NStatistic label="运输中" :value="shipmentSummary.inTransit" /></NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false"><NStatistic label="已签收" :value="shipmentSummary.delivered" /></NCard>
        </NGridItem>
      </NGrid>

      <NCard size="small" :bordered="false">
        <template #header>
          <div class="table-header">
            <span>运单列表</span>
            <NText depth="3" class="result-summary">{{ resultSummary }}</NText>
          </div>
        </template>
        <NDataTable
          :columns="columns"
          :data="shipments"
          :loading="loading"
          :pagination="false"
          :scroll-x="1540"
          :row-key="row => row.shipment.shipment_no"
          size="small"
        />
        <div class="pagination-bar">
          <NPagination
            :page="pagination.page"
            :page-size="pagination.pageSize"
            :page-count="pagination.pageCount"
            :page-sizes="[10, 25, 50, 100]"
            show-size-picker
            :disabled="loading"
            @update:page="changePage"
            @update:page-size="changePageSize"
          />
        </div>
      </NCard>
    </NSpace>

    <NDrawer v-model:show="detailOpen" placement="right" width="min(760px, 96vw)">
      <NDrawerContent :title="selected ? `运单 ${selected.shipment_no}` : '运单详情'" closable :native-scrollbar="false">
        <NSpin :show="detailLoading">
        <template v-if="selected && selectedShipment">
          <NSpace vertical :size="20">
            <div class="detail-heading">
              <div>
                <NText depth="3">订单号</NText>
                <div class="order-number">{{ selected.order_no }}</div>
              </div>
              <NTag :type="statusTagType(selected.status)" :bordered="false">{{ statusLabel(selected.status) }}</NTag>
            </div>

            <NAlert v-if="selectedShipment.last_track_error" type="error" :bordered="false" title="物流同步异常">
              {{ selectedShipment.last_track_error }}
            </NAlert>
            <NAlert v-if="selectedShipment.cancel_reason" type="warning" :bordered="false" title="取消原因">
              {{ selectedShipment.cancel_reason }}
            </NAlert>

            <NDescriptions label-placement="top" bordered :column="2" size="small">
              <NDescriptionsItem label="承运商">{{ carrierLabel(selected.carrier) }}</NDescriptionsItem>
              <NDescriptionsItem label="追踪号">{{ selected.tracking_no ?? '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="已发货时间">{{ formatDate(selected.shipped_at) }}</NDescriptionsItem>
              <NDescriptionsItem label="签收时间">{{ formatDate(selected.delivered_at) }}</NDescriptionsItem>
              <NDescriptionsItem label="创建人">{{ selectedShipment.created_by_username }}</NDescriptionsItem>
              <NDescriptionsItem label="连续同步失败">{{ selectedShipment.consecutive_track_failures }}</NDescriptionsItem>
              <NDescriptionsItem label="运单备注" :span="2">{{ selectedShipment.note ?? '-' }}</NDescriptionsItem>
            </NDescriptions>

            <NSpace v-if="selected.tracking_url || selectedShipment.carrier_label_url">
              <NButton v-if="selected.tracking_url" @click="openExternalUrl(selected.tracking_url)">查看追踪</NButton>
              <NButton v-if="selectedShipment.carrier_label_url" @click="openExternalUrl(selectedShipment.carrier_label_url)">
                查看面单
              </NButton>
            </NSpace>

            <div>
              <div class="section-heading">
                <NText strong>发货商品</NText>
                <NText depth="3">{{ shipmentItemViews.length }} 个商品行</NText>
              </div>
              <NList class="shipment-item-list" bordered>
                <NListItem v-for="view in shipmentItemViews" :key="view.item.order_item_id">
                  <div class="shipment-item-row">
                    <div class="snapshot-image">
                      <NImage
                        v-if="view.image"
                        :src="view.image"
                        :alt="view.name"
                        width="72"
                        height="72"
                        object-fit="cover"
                        lazy
                      />
                      <ImageOff v-else :size="24" :stroke-width="1.6" aria-hidden="true" />
                    </div>

                    <div class="snapshot-content">
                      <div class="snapshot-name">{{ view.name }}</div>
                      <div v-if="view.sku" class="snapshot-sku">SKU {{ view.sku }}</div>
                      <div v-if="view.specifications.length" class="snapshot-specifications">
                        <span v-for="specification in view.specifications" :key="`${specification.label}-${specification.value}`">
                          <NText depth="3">{{ specification.label }}</NText>
                          {{ specification.value }}
                        </span>
                      </div>
                      <NText v-if="view.snapshot" depth="3" class="snapshot-identifiers">
                        商品 #{{ view.snapshot.productId ?? '-' }} · 规格 #{{ view.snapshot.variantId ?? '-' }} · 订单商品行 #{{ view.item.order_item_id }}
                      </NText>
                      <NText v-else depth="3" class="snapshot-raw">{{ view.item.product_snapshot }}</NText>
                    </div>

                    <div class="shipment-item-summary">
                      <div class="shipment-item-quantity"><strong>{{ view.item.quantity }}</strong> 件</div>
                      <NTag
                        size="small"
                        :type="view.item.allocation_status === 'ALLOCATED' ? 'success' : 'default'"
                        :bordered="false"
                      >
                        {{ allocationLabel(view.item.allocation_status) }}
                      </NTag>
                    </div>
                  </div>
                </NListItem>
              </NList>
            </div>

            <div>
              <NText strong>物流轨迹</NText>
              <NEmpty v-if="selected.tracks.length === 0" class="track-empty" description="暂无物流轨迹" size="small" />
              <NTimeline v-else class="track-timeline">
                <NTimelineItem
                  v-for="track in selected.tracks"
                  :key="`${track.carrier_event_id}-${track.occurred_at}`"
                  :time="formatDate(track.occurred_at)"
                  :type="trackTagType(track.normalized_status)"
                >
                  <div class="track-heading">
                    <NTag size="small" :type="trackTagType(track.normalized_status)" :bordered="false">
                      {{ track.normalized_status }}
                    </NTag>
                    <NText depth="3">{{ track.status_code }} · {{ track.source }}</NText>
                  </div>
                  <div v-if="track.location || track.description" class="track-copy">
                    {{ [track.location, track.description].filter(Boolean).join(' · ') }}
                  </div>
                </NTimelineItem>
              </NTimeline>
            </div>
          </NSpace>
        </template>
        </NSpin>

        <template #footer>
          <NSpace justify="end">
            <NButton @click="detailOpen = false">关闭</NButton>
            <NButton
              v-if="selectedShipment"
              type="error"
              :disabled="!canDeleteShipment(selectedShipment)"
              :loading="deletingShipmentNo === selectedShipment.shipment.shipment_no"
              @click="confirmDeleteShipment(selectedShipment)"
            >
              {{ selectedShipment.shipment.status === 'DELETED' ? '永久删除' : '删除运单' }}
            </NButton>
            <NButton
              v-if="selectedShipment && canDispatch(selectedShipment)"
              type="primary"
              @click="openAction('dispatch', selectedShipment)"
            >
              确认发货
            </NButton>
            <NButton
              v-if="selectedShipment && canMarkDelivered(selectedShipment)"
              type="warning"
              @click="openAction('delivered', selectedShipment)"
            >
              手动签收
            </NButton>
            <NButton
              v-if="selectedShipment && canCancel(selectedShipment)"
              type="error"
              @click="openAction('cancel', selectedShipment)"
            >
              取消运单
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="createOpen"
      preset="card"
      :title="`创建运单 · ${orderNo.trim()}`"
      :style="{ width: 'min(700px, calc(100vw - 32px))' }"
      :mask-closable="!creating"
      :closable="!creating"
    >
      <NForm label-placement="top">
        <NGrid cols="1 s:2" :x-gap="12" responsive="screen">
          <NFormItemGi label="承运商">
            <NSelect v-model:value="createForm.carrier" :options="CARRIER_OPTIONS" :disabled="creating" />
          </NFormItemGi>
          <NFormItemGi label="物流追踪号">
            <NInput
              v-model:value="createForm.trackingNo"
              maxlength="64"
              show-count
              :disabled="creating"
              placeholder="可选"
            />
          </NFormItemGi>
        </NGrid>
        <NFormItem label="运单备注">
          <NInput
            v-model:value="createForm.note"
            type="textarea"
            maxlength="200"
            show-count
            :autosize="{ minRows: 2, maxRows: 4 }"
            :disabled="creating"
            placeholder="可选"
          />
        </NFormItem>

        <div class="allocation-heading">
          <NText strong>发货商品行</NText>
          <NButton size="small" :disabled="creating || createForm.allocations.length >= 50" @click="addAllocation">
            添加商品行
          </NButton>
        </div>
        <div class="allocation-list">
          <div v-for="(allocation, index) in createForm.allocations" :key="index" class="allocation-row">
            <NFormItem label="订单商品行 ID" :show-feedback="false">
              <div class="field-with-hint">
                <NSelect
                  v-model:value="allocation.orderItemId"
                  :options="allocationOptions"
                  :disabled="creating"
                  placeholder="选择可发货商品"
                  @update:value="(value: number | null) => selectAllocationItem(allocation, value)"
                />
                <small class="field-hint">必须选择 ID 大于 0 的订单商品行。</small>
              </div>
            </NFormItem>
            <NFormItem label="发货数量" :show-feedback="false">
              <div class="field-with-hint">
                <NInputNumber
                  v-model:value="allocation.quantity"
                  :min="1"
                  :precision="0"
                  :show-button="false"
                  disabled
                  placeholder="数量"
                  style="width: 100%"
                />
                <small class="field-hint">必须为大于 0 的整数。</small>
              </div>
            </NFormItem>
            <NButton
              class="allocation-remove"
              size="small"
              type="error"
              tertiary
              :disabled="creating || createForm.allocations.length === 1"
              @click="removeAllocation(index)"
            >
              删除
            </NButton>
          </div>
        </div>
      </NForm>

      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="creating" @click="closeCreate">取消</NButton>
          <NButton type="primary" :loading="creating" @click="submitCreate">创建运单</NButton>
        </NSpace>
      </template>
    </NModal>

    <NModal
      v-model:show="actionOpen"
      preset="card"
      :title="actionTitle"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
      :mask-closable="!actionLoading"
      :closable="!actionLoading"
    >
      <NSpace v-if="actionShipment" vertical :size="16">
        <NDescriptions :column="2" label-placement="top" size="small">
          <NDescriptionsItem label="运单号">{{ actionShipment.shipment.shipment_no }}</NDescriptionsItem>
          <NDescriptionsItem label="当前状态">{{ statusLabel(actionShipment.shipment.status) }}</NDescriptionsItem>
        </NDescriptions>
        <NForm label-placement="top">
          <NFormItem v-if="actionType === 'dispatch'" label="运单备注">
            <NInput
              v-model:value="actionForm.note"
              type="textarea"
              maxlength="200"
              show-count
              :autosize="{ minRows: 3, maxRows: 5 }"
              :disabled="actionLoading"
              placeholder="可选"
            />
          </NFormItem>
          <NFormItem v-else :label="actionType === 'cancel' ? '取消原因' : '签收原因'" required>
            <NInput
              v-model:value="actionForm.reason"
              type="textarea"
              maxlength="200"
              show-count
              :autosize="{ minRows: 3, maxRows: 5 }"
              :disabled="actionLoading"
              placeholder="请输入处理原因"
            />
          </NFormItem>
          <NFormItem v-if="actionType === 'delivered'" label="签收时间">
            <NDatePicker
              v-model:value="actionForm.occurredAt"
              type="datetime"
              clearable
              :disabled="actionLoading"
              style="width: 100%"
            />
          </NFormItem>
        </NForm>
      </NSpace>

      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="actionLoading" @click="closeAction">取消</NButton>
          <NButton :type="actionType === 'cancel' ? 'error' : 'primary'" :loading="actionLoading" @click="submitAction">
            确认{{ actionTitle }}
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.shipment-page {
  display: flex;
  flex-direction: column;
}

.page-heading,
.table-header,
.detail-heading,
.allocation-heading,
.track-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-heading h2 {
  margin: 0 0 4px;
  font-size: 22px;
}

.result-summary {
  font-size: 13px;
  font-weight: 400;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.order-number {
  margin-top: 4px;
  font-size: 18px;
  font-weight: 650;
  word-break: break-all;
}

.section-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.shipment-item-list {
  margin-top: 10px;
}

.shipment-item-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  width: 100%;
}

.snapshot-image {
  display: grid;
  width: 72px;
  height: 72px;
  overflow: hidden;
  place-items: center;
  border: 1px solid var(--n-border-color);
  border-radius: 6px;
  color: var(--n-text-color-3);
  background: var(--n-color-embedded);
}

.snapshot-image :deep(.n-image),
.snapshot-image :deep(img) {
  display: block;
  width: 100%;
  height: 100%;
}

.snapshot-content {
  min-width: 0;
}

.snapshot-name {
  font-weight: 600;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.snapshot-sku,
.snapshot-identifiers,
.snapshot-raw {
  display: block;
  margin-top: 5px;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.snapshot-sku {
  color: var(--n-text-color-2);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
}

.snapshot-specifications {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  margin-top: 8px;
  font-size: 13px;
}

.snapshot-specifications span {
  display: inline-flex;
  gap: 5px;
}

.snapshot-raw {
  white-space: pre-wrap;
}

.shipment-item-summary {
  display: flex;
  min-width: 72px;
  align-items: flex-end;
  flex-direction: column;
  gap: 9px;
  white-space: nowrap;
}

.shipment-item-quantity strong {
  font-size: 18px;
}

.track-timeline,
.track-empty {
  margin-top: 16px;
}

.track-copy {
  margin-top: 6px;
  white-space: pre-wrap;
  word-break: break-word;
}

.allocation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.allocation-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto;
  align-items: start;
  gap: 12px;
}

.allocation-remove {
  margin-top: 30px;
}

.field-with-hint {
  width: 100%;
  min-width: 0;
}

.field-hint {
  display: block;
  margin-top: 5px;
  color: #8c8c8c;
  font-size: 12px;
  line-height: 1.4;
}

:deep(.table-actions) {
  display: flex;
  gap: 8px;
}

@media (max-width: 640px) {
  .page-heading,
  .table-header,
  .detail-heading,
  .allocation-heading,
  .track-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .allocation-row {
    grid-template-columns: 1fr;
    gap: 4px;
  }

  .shipment-item-row {
    grid-template-columns: 56px minmax(0, 1fr);
    align-items: start;
    gap: 10px;
  }

  .snapshot-image {
    width: 56px;
    height: 56px;
  }

  .shipment-item-summary {
    grid-column: 2;
    align-items: center;
    flex-direction: row;
  }

  .allocation-remove {
    margin-top: 0;
  }
}
</style>
