<script setup lang="ts">
import { RefreshCw } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, FormInst, FormRules, TagProps } from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import { ORDER_STATUS_OPTIONS } from '~/composables/useOrderApi'
import type {
  OrderDetail,
  OrderListItem,
  OrderListQuery,
  OrderPaymentStatusResponse,
  OrderRefundStatusResponse,
  OrderStatus,
  StripeCollectionStatus,
} from '~/types/order'

definePageMeta({ layout: 'default' })

const api = useOrderApi()
const { confirmDeleteRequest } = useDeleteConfirmation()
const message = useMessage()
const loading = ref(false)
const orders = ref<OrderListItem[]>([])
const selectedOrder = ref<OrderListItem | null>(null)
const selectedOrderDetail = ref<OrderDetail | null>(null)
const paymentStatus = ref<OrderPaymentStatusResponse | null>(null)
const refundStatus = ref<OrderRefundStatusResponse | null>(null)
const detailLoading = ref(false)
const paymentStatusLoading = ref(false)
const orderStatusUpdating = ref(false)
const manualOrderStatus = ref<OrderStatus | null>(null)
const refundStatusLoading = ref(false)
const detailOpen = ref(false)
const refundOpen = ref(false)
const refundLoading = ref(false)
const deletingOrderNo = ref<string | null>(null)
const refundFormRef = ref<FormInst | null>(null)

const filters = reactive<{
  status: OrderStatus | null
  customerUsername: string
  orderNo: string
}>({
  status: null,
  customerUsername: '',
  orderNo: '',
})

const pagination = reactive({
  page: 1,
  pageSize: 25,
  pageCount: 1,
})

const refundForm = reactive({
  reason: '',
  reasonDetail: '',
})

const refundReasonOptions = [
  { label: '改变主意', value: '改变主意' },
  { label: '误下单', value: '误下单' },
  { label: '找到更优惠价格', value: '找到更优惠价格' },
  { label: '支付问题', value: '支付问题' },
  { label: '其他', value: '其他' },
]

const refundRules: FormRules = {
  reason: [
    { max: 64, message: '退款原因最多选择 64 个字符', trigger: ['blur', 'input'] },
  ],
  reasonDetail: [
    { max: 200, message: '补充说明不能超过 200 个字符', trigger: ['blur', 'input'] },
  ],
}

const pageSizeOptions = [10, 25, 50, 100]
const manualPaymentOrderStatusOptions = ORDER_STATUS_OPTIONS.filter(option =>
  ['PENDING_PAYMENT', 'PAID', 'CANCELLED'].includes(option.value),
)
const resultSummary = computed(() => {
  if (loading.value) return '正在加载订单…'
  if (orders.value.length === 0) return '当前条件下没有订单'
  return `第 ${pagination.page} / ${pagination.pageCount} 页，本页 ${orders.value.length} 条`
})

const pageStats = computed(() => ({
  total: orders.value.length,
  pendingPayment: orders.value.filter(order => order.status === 'PENDING_PAYMENT').length,
  awaitingShipment: orders.value.filter(order => order.status === 'PAID').length,
  fulfilling: orders.value.filter(order => order.status === 'SHIPPED' || order.status === 'DELIVERED').length,
}))

const refundOrder = computed(() => selectedOrder.value?.status === 'PAID' && selectedOrder.value.payment_status === 'PAID'
  ? selectedOrder.value
  : null)
const canQueryRefundStatus = computed(() => ['REFUNDING', 'PARTIALLY_REFUNDED', 'REFUNDED'].includes(selectedOrderDetail.value?.payment_status || ''))
const canQueryPaymentStatus = computed(() => Boolean(
  selectedOrderDetail.value?.payment_intent_id
  || selectedOrderDetail.value?.stripe_checkout_session_id,
))

function statusLabel(status: OrderStatus): string {
  return ORDER_STATUS_OPTIONS.find(option => option.value === status)?.label ?? status
}

function statusTagType(status: OrderStatus): TagProps['type'] {
  const types: Record<OrderStatus, TagProps['type']> = {
    PENDING_PAYMENT: 'warning',
    PAID: 'info',
    REFUNDING: 'warning',
    REFUNDED: 'default',
    SHIPPED: 'info',
    DELIVERED: 'success',
    COMPLETED: 'success',
    CANCELLED: 'error',
    DELETED: 'default',
  }
  return types[status]
}

function localPaymentStatusLabel(status: OrderListItem['payment_status']): string {
  const labels: Record<OrderListItem['payment_status'], string> = {
    PENDING_PAYMENT: '待付款',
    PAID: '已付款',
    REFUNDING: '退款中',
    PARTIALLY_REFUNDED: '部分退款',
    REFUNDED: '退款成功',
    CANCELLED: '已取消',
  }
  return labels[status]
}

function paymentStatusLabel(status: StripeCollectionStatus): string {
  const labels: Record<StripeCollectionStatus, string> = {
    REQUIRES_ACTION: '等待客户操作',
    PENDING: '待收款',
    PROCESSING: '处理中',
    SUCCEEDED: '已收款',
    FAILED: '收款失败',
    CANCELLED: '已取消',
    PARTIALLY_REFUNDED: '部分退款',
    REFUNDED: '已退款',
    UNKNOWN: '未知',
  }
  return labels[status]
}

function paymentStatusTagType(status: StripeCollectionStatus): TagProps['type'] {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'CANCELLED') return 'error'
  if (status === 'REQUIRES_ACTION' || status === 'PENDING') return 'warning'
  if (status === 'PROCESSING' || status === 'PARTIALLY_REFUNDED') return 'info'
  return 'default'
}

function paymentQuerySourceLabel(source: OrderPaymentStatusResponse['query_source']): string {
  return source === 'PAYMENT_INTENT' ? 'PaymentIntent' : 'Checkout Session'
}

function rawPaymentStatus(result: OrderPaymentStatusResponse): string {
  return [
    result.payment_intent_status ? `PaymentIntent: ${result.payment_intent_status}` : null,
    result.checkout_session_status ? `Session: ${result.checkout_session_status}` : null,
    result.checkout_payment_status ? `Payment: ${result.checkout_payment_status}` : null,
  ].filter(Boolean).join(' · ') || '-'
}

function formatDate(value: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatAmount(value: number | string, currency: string): string {
  const amount = Number(value)
  if (!Number.isFinite(amount)) return `${value} ${currency}`
  const normalizedCurrency = currency.trim().toUpperCase()
  try {
    return new Intl.NumberFormat('zh-CN', {
      style: 'currency',
      currency: normalizedCurrency,
    }).format(amount)
  } catch {
    return `${amount.toFixed(2)} ${normalizedCurrency}`
  }
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return '未知错误'
}

async function loadOrders() {
  loading.value = true
  try {
    const query: OrderListQuery = {
      page: pagination.page,
      size: pagination.pageSize,
    }
    if (filters.status) query.status = filters.status
    if (filters.customerUsername.trim()) query.customer_username = filters.customerUsername.trim()
    if (filters.orderNo.trim()) query.order_no = filters.orderNo.trim()

    const data = await api.list(query)
    const pageCount = Math.max(data.pagination.count, 1)
    if (pagination.page > pageCount) {
      pagination.page = pageCount
      await loadOrders()
      return
    }
    orders.value = data.list ?? []
    pagination.pageCount = pageCount

    if (selectedOrder.value) {
      selectedOrder.value = orders.value.find(order => order.id === selectedOrder.value?.id) ?? selectedOrder.value
    }
  } catch (error) {
    orders.value = []
    message.error(`加载订单列表失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function searchOrders() {
  pagination.page = 1
  await loadOrders()
}

async function resetFilters() {
  filters.status = null
  filters.customerUsername = ''
  filters.orderNo = ''
  pagination.page = 1
  await loadOrders()
}

async function changePage(page: number) {
  pagination.page = page
  await loadOrders()
}

async function changePageSize(pageSize: number) {
  pagination.pageSize = pageSize
  pagination.page = 1
  await loadOrders()
}

async function openDetail(order: OrderListItem) {
  selectedOrder.value = order
  selectedOrderDetail.value = null
  paymentStatus.value = null
  manualOrderStatus.value = null
  refundStatus.value = null
  detailOpen.value = true
  detailLoading.value = true
  try {
    selectedOrderDetail.value = await api.detail(order.order_no)
  } catch (error) {
    message.error(`加载订单详情失败：${errorMessage(error)}`)
  } finally {
    detailLoading.value = false
  }
  if (canQueryPaymentStatus.value) {
    await queryStripePaymentStatus(true)
  }
}

async function queryStripePaymentStatus(silent = false) {
  const detail = selectedOrderDetail.value
  if (!detail || !canQueryPaymentStatus.value || paymentStatusLoading.value) return

  paymentStatusLoading.value = true
  paymentStatus.value = null
  try {
    const result = await api.queryPaymentStatus(detail.order_no)
    paymentStatus.value = result
    manualOrderStatus.value = result.order_status === 'PENDING_PAYMENT' ? result.order_status : null
    if (selectedOrderDetail.value?.order_no === result.order_no) {
      if (result.payment_intent_id) {
        selectedOrderDetail.value.payment_intent_id = result.payment_intent_id
      }
      if (result.stripe_checkout_session_id) {
        selectedOrderDetail.value.stripe_checkout_session_id = result.stripe_checkout_session_id
      }
    }
    if (!silent) {
      message.success(`已查询订单 ${result.order_no} 的 Stripe 收款状态，请手动确认最终订单状态`)
    }
  } catch (error) {
    message.error(`查询 Stripe 收款状态失败：${errorMessage(error)}`)
  } finally {
    paymentStatusLoading.value = false
  }
}

async function updateManualOrderStatus() {
  const detail = selectedOrderDetail.value
  const status = manualOrderStatus.value
  if (!detail || !paymentStatus.value || !status || orderStatusUpdating.value) return
  if (detail.status !== 'PENDING_PAYMENT') {
    message.warning('仅待付款订单可根据 Stripe 查询结果手动更新状态')
    return
  }
  if (status === detail.status) {
    message.info(`订单当前已经是${statusLabel(status)}`)
    return
  }

  orderStatusUpdating.value = true
  try {
    const result = await api.updateStatus(detail.order_no, status)
    if (selectedOrder.value?.order_no === result.order_no) {
      selectedOrder.value.status = result.status
      selectedOrder.value.payment_status = result.payment_status
      selectedOrder.value.updated_at = result.updated_at
    }
    const listOrder = orders.value.find(order => order.order_no === result.order_no)
    if (listOrder) {
      listOrder.status = result.status
      listOrder.payment_status = result.payment_status
      listOrder.updated_at = result.updated_at
    }
    if (selectedOrderDetail.value?.order_no === result.order_no) {
      selectedOrderDetail.value = await api.detail(result.order_no)
    }
    if (paymentStatus.value?.order_no === result.order_no) {
      paymentStatus.value.order_status = result.status
      paymentStatus.value.payment_status = result.payment_status
    }
    manualOrderStatus.value = result.status
    message.success(`订单 ${result.order_no} 的最终状态已设为${statusLabel(result.status)}`)
  } catch (error) {
    message.error(`更新订单状态失败：${errorMessage(error)}`)
  } finally {
    orderStatusUpdating.value = false
  }
}

async function queryStripeRefundStatus() {
  const detail = selectedOrderDetail.value
  if (!detail || !canQueryRefundStatus.value || refundStatusLoading.value) return

  refundStatusLoading.value = true
  refundStatus.value = null
  try {
    const result = await api.queryRefundStatus(detail.order_no)
    refundStatus.value = result
    if (selectedOrder.value?.order_no === result.order_no) {
      selectedOrder.value.status = result.order_status
      selectedOrder.value.payment_status = result.payment_status
    }
    const listOrder = orders.value.find(order => order.order_no === result.order_no)
    if (listOrder) {
      listOrder.status = result.order_status
      listOrder.payment_status = result.payment_status
    }
    if (selectedOrderDetail.value?.order_no === result.order_no) {
      selectedOrderDetail.value.status = result.order_status
      selectedOrderDetail.value.payment_status = result.payment_status
    }
    message.success(`已查询订单 ${result.order_no} 的 Stripe 退款状态`)
  } catch (error) {
    message.error(`查询 Stripe 退款状态失败：${errorMessage(error)}`)
  } finally {
    refundStatusLoading.value = false
  }
}

function productSnapshotLabel(snapshot: string): string {
  try {
    const value = JSON.parse(snapshot) as { name?: string; color?: string; size?: string; sku?: string; variantAttributes?: Record<string, string> }
    const topSize = value.variantAttributes?.top_size
    const bottomSize = value.variantAttributes?.bottom_size
    return [value.name, value.color, value.size || [topSize, bottomSize].filter(Boolean).join('/'), value.sku]
      .filter(Boolean)
      .join(' · ') || snapshot
  } catch {
    return snapshot
  }
}

function openRefund(order: OrderListItem) {
  detailOpen.value = false
  selectedOrder.value = order
  refundForm.reason = ''
  refundForm.reasonDetail = ''
  refundOpen.value = true
}

function closeRefund() {
  if (refundLoading.value) return
  refundOpen.value = false
  refundForm.reason = ''
  refundForm.reasonDetail = ''
  refundFormRef.value?.restoreValidation()
}

async function submitRefund() {
  try {
    await refundFormRef.value?.validate()
  } catch {
    return
  }

  const order = refundOrder.value
  if (!order) {
    message.warning('只有未发货的已支付订单可以退款')
    return
  }

  refundLoading.value = true
  try {
    const result = await api.refund(order.order_no, refundForm.reason.trim() || undefined, refundForm.reasonDetail.trim() || undefined)
    const matchedOrder = orders.value.find(item => item.id === result.id)
    if (matchedOrder) {
      matchedOrder.status = result.status
      matchedOrder.payment_status = result.payment_status
      matchedOrder.updated_at = result.updated_at
    }
    if (selectedOrder.value?.id === result.id) {
      selectedOrder.value = {
        ...selectedOrder.value,
        status: result.status,
        payment_status: result.payment_status,
        updated_at: result.updated_at,
      }
    }
    refundOpen.value = false
    refundForm.reason = ''
    refundForm.reasonDetail = ''
    message.success(`订单 ${result.order_no} 已提交退款`)
    await loadOrders()
  } catch (error) {
    message.error(`退款失败：${errorMessage(error)}`)
  } finally {
    refundLoading.value = false
  }
}

function confirmDelete(order: OrderListItem) {
  const physicallyDelete = order.status === 'DELETED'
  confirmDeleteRequest({
    tone: physicallyDelete ? 'error' : 'warning',
    title: physicallyDelete ? '永久删除订单' : '删除订单',
    content: physicallyDelete
      ? `订单 ${order.order_no} 已处于删除状态，再次删除会从数据库永久移除且无法恢复。`
      : `确认删除订单 ${order.order_no}？本次只会将订单标记为已删除。`,
    positiveText: physicallyDelete ? '永久删除' : '删除',
    onConfirm: async () => {
      deletingOrderNo.value = order.order_no
      try {
        if (physicallyDelete) {
          await api.permanentlyDeleteOrder(order.order_no)
        } else {
          await api.deleteOrder(order.order_no)
        }
        detailOpen.value = false
        selectedOrder.value = null
        selectedOrderDetail.value = null
        message.success(physicallyDelete ? '订单已永久删除' : '订单已标记为删除')
        await loadOrders()
      } catch (error) {
        message.error(`${physicallyDelete ? '永久删除' : '删除'}失败：${errorMessage(error)}`)
      } finally {
        deletingOrderNo.value = null
      }
    },
  })
}

function canDeleteOrder(order: OrderListItem): boolean {
  return ['CANCELLED', 'DELIVERED', 'COMPLETED', 'DELETED'].includes(order.status)
}

function exportCurrentPage() {
  if (orders.value.length === 0) {
    message.warning('当前没有可导出的订单')
    return
  }

  const escapeCsv = (value: unknown) => `"${String(value ?? '').replaceAll('"', '""')}"`
  const rows = [
    ['订单号', '客户用户名', '状态', '订单金额', '币种', '创建时间', '更新时间'],
    ...orders.value.map(order => [
      order.order_no,
      order.customer_username,
      statusLabel(order.status),
      order.total_amount,
      order.currency,
      formatDate(order.created_at),
      formatDate(order.updated_at),
    ]),
  ]
  const csv = rows.map(row => row.map(escapeCsv).join(',')).join('\r\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `orders-page-${pagination.page}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

const columns: DataTableColumns<OrderListItem> = [
  {
    title: '订单号',
    key: 'order_no',
    width: 190,
    fixed: 'left',
    ellipsis: { tooltip: true },
    render: row => h(
      NButton,
      { text: true, type: 'primary', onClick: () => openDetail(row) },
      { default: () => row.order_no },
    ),
  },
  {
    title: '客户用户名',
    key: 'customer_username',
    width: 140,
  },
  {
    title: '状态',
    key: 'status',
    width: 135,
    render: row => h(
      NTag,
      { size: 'small', type: statusTagType(row.status), bordered: false },
      { default: () => statusLabel(row.status) },
    ),
  },
  {
    title: '订单金额',
    key: 'total_amount',
    width: 145,
    align: 'right',
    render: row => formatAmount(row.total_amount, row.currency),
  },
  {
    title: '创建时间',
    key: 'created_at',
    width: 180,
    render: row => formatDate(row.created_at),
  },
  {
    title: '更新时间',
    key: 'updated_at',
    width: 180,
    render: row => formatDate(row.updated_at),
  },
  {
    title: '操作',
    key: 'actions',
    width: 310,
    fixed: 'right',
    render: row => h('div', { class: 'table-actions' }, [
      h(
        NButton,
        { size: 'small', tertiary: true, onClick: () => openDetail(row) },
        { default: () => '查看' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'error',
          disabled: row.status !== 'PAID',
          onClick: () => openRefund(row),
        },
        { default: () => '退款' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'primary',
          disabled: row.status === 'DELETED',
          onClick: () => navigateTo({ path: '/shipments', query: { order_no: row.order_no } }),
        },
        { default: () => '物流' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'error',
          disabled: !canDeleteOrder(row),
          loading: deletingOrderNo.value === row.order_no,
          onClick: () => confirmDelete(row),
        },
        { default: () => row.status === 'DELETED' ? '永久删除' : '删除' },
      ),
    ]),
  },
]

onMounted(() => {
  void loadOrders()
})
</script>

<template>
  <div class="order-page">
    <NSpace vertical :size="12">
      <div class="page-heading">
        <div>
          <h2>订单管理</h2>
          <NText depth="3">查询订单状态与金额，并处理未发货订单的退款。</NText>
        </div>
        <NSpace>
          <NButton :disabled="loading || orders.length === 0" @click="exportCurrentPage">
            导出本页
          </NButton>
          <NButton :loading="loading" @click="loadOrders">
            刷新
          </NButton>
        </NSpace>
      </div>

      <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="12" responsive="screen">
        <NGridItem>
          <NCard size="small" :bordered="false">
            <NStatistic label="本页订单" :value="pageStats.total" />
          </NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false">
            <NStatistic label="待付款" :value="pageStats.pendingPayment" />
          </NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false">
            <NStatistic label="待发货" :value="pageStats.awaitingShipment" />
          </NCard>
        </NGridItem>
        <NGridItem>
          <NCard size="small" :bordered="false">
            <NStatistic label="配送中" :value="pageStats.fulfilling" />
          </NCard>
        </NGridItem>
      </NGrid>

      <NCard size="small" :bordered="false">
        <NGrid cols="1 s:2 m:3" :x-gap="12" :y-gap="4" responsive="screen">
          <NFormItemGi label="订单状态">
            <NSelect
              v-model:value="filters.status"
              :options="ORDER_STATUS_OPTIONS"
              clearable
              placeholder="全部状态"
            />
          </NFormItemGi>
          <NFormItemGi label="客户用户名">
            <NInput
              v-model:value="filters.customerUsername"
              maxlength="50"
              clearable
              placeholder="精确查询"
              @keyup.enter="searchOrders"
            />
          </NFormItemGi>
          <NFormItemGi label="订单号">
            <NInput
              v-model:value="filters.orderNo"
              maxlength="32"
              clearable
              placeholder="精确查询"
              @keyup.enter="searchOrders"
            />
          </NFormItemGi>
        </NGrid>
        <NSpace justify="end">
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
          <NButton type="primary" :loading="loading" @click="searchOrders">
            查询
          </NButton>
        </NSpace>
      </NCard>

      <NCard size="small" :bordered="false">
        <template #header>
          <div class="table-header">
            <span>订单列表</span>
            <NText depth="3" class="result-summary">{{ resultSummary }}</NText>
          </div>
        </template>

        <NDataTable
          :columns="columns"
          :data="orders"
          :loading="loading"
          :pagination="false"
          :scroll-x="1190"
          :row-key="row => row.id"
          size="small"
        />

        <div class="pagination-bar">
          <NPagination
            :page="pagination.page"
            :page-size="pagination.pageSize"
            :page-count="pagination.pageCount"
            :page-sizes="pageSizeOptions"
            show-size-picker
            :disabled="loading"
            @update:page="changePage"
            @update:page-size="changePageSize"
          />
        </div>
      </NCard>
    </NSpace>

    <NDrawer v-model:show="detailOpen" :width="520" placement="right">
      <NDrawerContent title="订单概览" closable>
        <template v-if="selectedOrder">
          <NSpin :show="detailLoading">
          <NSpace vertical :size="20">
            <div class="detail-heading">
              <div>
                <NText depth="3">订单号</NText>
                <div class="order-number">{{ selectedOrder.order_no }}</div>
              </div>
              <NTag :type="statusTagType(selectedOrder.status)" :bordered="false">
                {{ statusLabel(selectedOrder.status) }}
              </NTag>
            </div>

            <NAlert
              v-if="selectedOrder.status === 'PAID'"
              title="订单可以退款"
              type="info"
              :bordered="false"
            >
              当前订单已支付且尚未发货。退款会取消订单、恢复库存并触发支付渠道退款。
            </NAlert>
            <NAlert
              v-else-if="selectedOrder.status === 'CANCELLED'"
              title="订单已取消"
              type="error"
              :bordered="false"
            >
              已取消订单不能再次退款或进入后续履约流程。
            </NAlert>
            <NAlert
              v-else-if="selectedOrder.status === 'DELETED'"
              title="订单已逻辑删除"
              type="warning"
              :bordered="false"
            >
              再次删除会永久移除订单；存在关联运单或售后工单时，后端会拒绝永久删除。
            </NAlert>

            <NDescriptions label-placement="top" bordered :column="2">
              <NDescriptionsItem label="客户用户名">
                {{ selectedOrder.customer_username }}
              </NDescriptionsItem>
              <NDescriptionsItem label="订单 ID">
                {{ selectedOrder.id }}
              </NDescriptionsItem>
              <NDescriptionsItem label="订单金额">
                <strong>{{ formatAmount(selectedOrder.total_amount, selectedOrder.currency) }}</strong>
              </NDescriptionsItem>
              <NDescriptionsItem label="币种">
                {{ selectedOrder.currency.toUpperCase() }}
              </NDescriptionsItem>
              <NDescriptionsItem label="创建时间">
                {{ formatDate(selectedOrder.created_at) }}
              </NDescriptionsItem>
              <NDescriptionsItem label="更新时间">
                {{ formatDate(selectedOrder.updated_at) }}
              </NDescriptionsItem>
              <NDescriptionsItem label="本地付款状态">
                {{ localPaymentStatusLabel(selectedOrder.payment_status) }}
              </NDescriptionsItem>
              <NDescriptionsItem v-if="selectedOrderDetail?.refund_reason" label="退款原因">
                {{ selectedOrderDetail?.refund_reason }}
              </NDescriptionsItem>
              <NDescriptionsItem v-if="selectedOrderDetail?.refund_reason_detail" label="退款补充说明" :span="2">
                {{ selectedOrderDetail?.refund_reason_detail }}
              </NDescriptionsItem>
            </NDescriptions>

            <template v-if="selectedOrderDetail">
              <div>
                <NText strong>金额明细</NText>
                <NDescriptions class="detail-block" label-placement="top" bordered :column="2" size="small">
                  <NDescriptionsItem label="商品小计">
                    {{ formatAmount(selectedOrderDetail.items_subtotal, selectedOrderDetail.currency) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="运费">
                    {{ formatAmount(selectedOrderDetail.shipping_fee, selectedOrderDetail.currency) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="税费">
                    {{ formatAmount(selectedOrderDetail.tax_amount, selectedOrderDetail.currency) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="优惠">
                    {{ formatAmount(selectedOrderDetail.discount_amount, selectedOrderDetail.currency) }}
                  </NDescriptionsItem>
                </NDescriptions>
              </div>

              <div>
                <div class="detail-section-heading">
                  <NText strong>Stripe 收款状态</NText>
                  <NButton
                    size="small"
                    secondary
                    type="primary"
                    :loading="paymentStatusLoading"
                    :disabled="!canQueryPaymentStatus"
                    @click="queryStripePaymentStatus()"
                  >
                    <template #icon><RefreshCw :size="16" /></template>
                    查询 Stripe
                  </NButton>
                </div>

                <NAlert
                  v-if="!canQueryPaymentStatus"
                  class="detail-block"
                  type="default"
                  :bordered="false"
                  title="暂无 Stripe 支付记录"
                >
                  当前订单还没有 PaymentIntent 或 Checkout Session。
                </NAlert>

                <NDescriptions
                  v-else-if="paymentStatus"
                  class="detail-block"
                  label-placement="top"
                  bordered
                  :column="2"
                  size="small"
                >
                  <NDescriptionsItem label="远端收款状态">
                    <NTag :type="paymentStatusTagType(paymentStatus.provider_status)" :bordered="false" size="small">
                      {{ paymentStatusLabel(paymentStatus.provider_status) }}
                    </NTag>
                  </NDescriptionsItem>
                  <NDescriptionsItem label="本地订单状态">
                    {{ statusLabel(paymentStatus.order_status) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="本地付款状态">
                    {{ localPaymentStatusLabel(paymentStatus.payment_status) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="查询来源">
                    {{ paymentQuerySourceLabel(paymentStatus.query_source) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="Stripe 金额">
                    <template v-if="paymentStatus.amount !== null && paymentStatus.currency">
                      {{ formatAmount(paymentStatus.amount, paymentStatus.currency) }}
                      <NTag
                        v-if="paymentStatus.amount_matches_order !== null"
                        class="amount-match-tag"
                        size="tiny"
                        :bordered="false"
                        :type="paymentStatus.amount_matches_order ? 'success' : 'error'"
                      >
                        {{ paymentStatus.amount_matches_order ? '金额一致' : '金额不一致' }}
                      </NTag>
                    </template>
                    <template v-else>-</template>
                  </NDescriptionsItem>
                  <NDescriptionsItem label="Stripe 原始状态" :span="2">
                    {{ rawPaymentStatus(paymentStatus) }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="PaymentIntent ID" :span="2">
                    <span class="payment-reference">{{ paymentStatus.payment_intent_id || '-' }}</span>
                  </NDescriptionsItem>
                  <NDescriptionsItem label="Checkout Session ID" :span="2">
                    <span class="payment-reference">{{ paymentStatus.stripe_checkout_session_id || '-' }}</span>
                  </NDescriptionsItem>
                  <NDescriptionsItem
                    v-if="paymentStatus.failure_code || paymentStatus.failure_message"
                    label="失败信息"
                    :span="2"
                  >
                    {{ [paymentStatus.failure_code, paymentStatus.failure_message].filter(Boolean).join(' · ') }}
                  </NDescriptionsItem>
                </NDescriptions>

                <div v-if="paymentStatus" class="manual-status-editor">
                  <NAlert
                    :type="selectedOrderDetail?.status === 'PENDING_PAYMENT' ? 'info' : 'default'"
                    :bordered="false"
                    :title="selectedOrderDetail?.status === 'PENDING_PAYMENT' ? '手动确认订单状态' : '订单状态不会自动同步'"
                  >
                    {{ selectedOrderDetail?.status === 'PENDING_PAYMENT'
                      ? 'Stripe 查询仅供核对，不会修改本地订单。请选择最终状态后手动保存。'
                      : `当前订单已是${statusLabel(selectedOrderDetail?.status || paymentStatus.order_status)}，Stripe 查询结果不会覆盖该状态。` }}
                  </NAlert>
                  <div v-if="selectedOrderDetail?.status === 'PENDING_PAYMENT'" class="manual-status-actions">
                    <NSelect
                      v-model:value="manualOrderStatus"
                      class="manual-status-select"
                      :options="manualPaymentOrderStatusOptions"
                      :disabled="orderStatusUpdating"
                      placeholder="选择最终订单状态"
                    />
                    <NButton
                      type="primary"
                      :loading="orderStatusUpdating"
                      :disabled="!manualOrderStatus || manualOrderStatus === selectedOrderDetail.status"
                      @click="updateManualOrderStatus"
                    >
                      保存最终状态
                    </NButton>
                  </div>
                </div>

                <NEmpty
                  v-else
                  class="payment-empty"
                  size="small"
                  :description="paymentStatusLoading ? '正在自动查询 Stripe 收款状态' : '未获取到 Stripe 收款状态，可点击右上角重试'"
                />
              </div>

              <div v-if="canQueryRefundStatus || refundStatus">
                <div class="detail-section-heading">
                  <NText strong>Stripe 退款状态</NText>
                  <NButton size="small" secondary type="warning" :loading="refundStatusLoading" @click="queryStripeRefundStatus">
                    <template #icon><RefreshCw :size="16" /></template>
                    查询退款
                  </NButton>
                </div>
                <NDescriptions v-if="refundStatus" class="detail-block" label-placement="top" bordered :column="2" size="small">
                  <NDescriptionsItem label="本地订单状态">{{ statusLabel(refundStatus.order_status) }}</NDescriptionsItem>
                  <NDescriptionsItem label="本地付款状态">{{ localPaymentStatusLabel(refundStatus.payment_status) }}</NDescriptionsItem>
                  <NDescriptionsItem label="Stripe 退款状态">{{ refundStatus.provider_refund_status || '退款请求待投递' }}</NDescriptionsItem>
                  <NDescriptionsItem label="Stripe 退款 ID"><span class="payment-reference">{{ refundStatus.stripe_refund_id || '-' }}</span></NDescriptionsItem>
                  <NDescriptionsItem label="退款金额">
                    <template v-if="refundStatus.refund_amount !== null && refundStatus.currency">
                      {{ formatAmount(refundStatus.refund_amount, refundStatus.currency) }}
                    </template>
                    <template v-else>-</template>
                  </NDescriptionsItem>
                  <NDescriptionsItem label="金额校验">
                    <template v-if="refundStatus.amount_matches_order === null">-</template>
                    <NTag v-else :type="refundStatus.amount_matches_order ? 'success' : 'error'" :bordered="false" size="small">
                      {{ refundStatus.amount_matches_order ? '金额一致' : '金额不一致' }}
                    </NTag>
                  </NDescriptionsItem>
                </NDescriptions>
                <NEmpty v-else class="payment-empty" size="small" description="尚未查询 Stripe 退款状态" />
              </div>

              <div>
                <NText strong>收货信息</NText>
                <NDescriptions class="detail-block" label-placement="top" bordered :column="2" size="small">
                  <NDescriptionsItem label="收件人">{{ selectedOrderDetail.shipping_address.name }}</NDescriptionsItem>
                  <NDescriptionsItem label="联系电话">{{ selectedOrderDetail.shipping_address.phone }}</NDescriptionsItem>
                  <NDescriptionsItem label="地址" :span="2">
                    {{ [
                      selectedOrderDetail.shipping_address.country,
                      selectedOrderDetail.shipping_address.state_or_province,
                      selectedOrderDetail.shipping_address.city,
                      selectedOrderDetail.shipping_address.district,
                      selectedOrderDetail.shipping_address.address1,
                      selectedOrderDetail.shipping_address.address2,
                    ].filter(Boolean).join(' ') }}
                  </NDescriptionsItem>
                  <NDescriptionsItem label="客户留言" :span="2">
                    {{ selectedOrderDetail.client_message || '-' }}
                  </NDescriptionsItem>
                </NDescriptions>
              </div>

              <div>
                <NText strong>商品明细</NText>
                <NList class="detail-block" bordered>
                  <NListItem v-for="item in selectedOrderDetail.items" :key="item.id">
                    <div class="order-item-row">
                      <div>
                        <div class="order-item-name">{{ productSnapshotLabel(item.product_snapshot) }}</div>
                        <NText depth="3">{{ item.sku }} · SKU #{{ item.variant_id }} · 商品 #{{ item.product_id }}</NText>
                      </div>
                      <div class="order-item-values">
                        <div>{{ item.quantity }} 件</div>
                        <NTag size="small" :type="item.allocated ? 'success' : 'default'" :bordered="false">
                          {{ item.allocated ? '已分配' : '待发货' }}
                        </NTag>
                        <strong>{{ formatAmount(item.line_total, selectedOrderDetail.currency) }}</strong>
                      </div>
                    </div>
                  </NListItem>
                </NList>
              </div>
            </template>

            <div>
              <NText strong>订单流程</NText>
              <div class="status-flow">
                <NSteps
                  :current="selectedOrder.status === 'PENDING_PAYMENT' ? 1
                    : selectedOrder.status === 'PAID' ? 2
                      : selectedOrder.status === 'SHIPPED' ? 3
                        : selectedOrder.status === 'DELIVERED' || selectedOrder.status === 'COMPLETED' ? 4
                          : 1"
                  :status="selectedOrder.status === 'CANCELLED' || selectedOrder.status === 'DELETED' ? 'error' : 'process'"
                  size="small"
                >
                  <NStep title="下单" />
                  <NStep title="支付" />
                  <NStep title="发货" />
                  <NStep title="完成" />
                </NSteps>
              </div>
            </div>
          </NSpace>
          </NSpin>
        </template>

        <template #footer>
          <NSpace justify="end">
            <NButton @click="detailOpen = false">关闭</NButton>
            <NButton
              type="error"
              :disabled="selectedOrder?.status !== 'PAID' || selectedOrder.payment_status !== 'PAID'"
              @click="selectedOrder && openRefund(selectedOrder)"
            >
              退款
            </NButton>
            <NButton
              type="error"
              :disabled="!selectedOrder || !canDeleteOrder(selectedOrder)"
              :loading="deletingOrderNo === selectedOrder?.order_no"
              @click="selectedOrder && confirmDelete(selectedOrder)"
            >
              {{ selectedOrder?.status === 'DELETED' ? '永久删除' : '删除' }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>

    <NModal
      v-model:show="refundOpen"
      preset="card"
      title="订单退款"
      :style="{ width: 'min(520px, calc(100vw - 32px))' }"
      :mask-closable="!refundLoading"
      :closable="!refundLoading"
    >
      <NSpace v-if="refundOrder" vertical :size="16">
        <NAlert type="warning" :bordered="false">
          确认对订单 <strong>{{ refundOrder.order_no }}</strong> 发起退款？订单和付款状态将进入退款中，Stripe 确认成功后才恢复库存并作废订单。
        </NAlert>
        <NDescriptions :column="2" label-placement="top" size="small">
          <NDescriptionsItem label="客户用户名">{{ refundOrder.customer_username }}</NDescriptionsItem>
          <NDescriptionsItem label="退款金额">
            {{ formatAmount(refundOrder.total_amount, refundOrder.currency) }}
          </NDescriptionsItem>
        </NDescriptions>
        <NForm ref="refundFormRef" :model="refundForm" :rules="refundRules">
          <NFormItem label="退款原因（可选）" path="reason">
            <NSelect
              v-model:value="refundForm.reason"
              :options="refundReasonOptions"
              clearable
              placeholder="请选择退款原因"
              :disabled="refundLoading"
            />
            <NText depth="3" class="refund-field-hint">可选原因最长 64 个字符，也可以留空。</NText>
          </NFormItem>
          <NFormItem label="补充说明（可选）" path="reasonDetail">
            <NInput
              v-model:value="refundForm.reasonDetail"
              type="textarea"
              maxlength="200"
              show-count
              :autosize="{ minRows: 3, maxRows: 6 }"
              placeholder="可填写退款补充说明"
              :disabled="refundLoading"
            />
            <NText depth="3" class="refund-field-hint">补充说明可留空，最多 200 个字符。</NText>
          </NFormItem>
        </NForm>
      </NSpace>

      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="refundLoading" @click="closeRefund">取消</NButton>
          <NButton type="error" :loading="refundLoading" @click="submitRefund">
            确认退款
          </NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>

<style scoped>
.order-page {
  display: flex;
  flex-direction: column;
}

.page-heading,
.table-header,
.detail-heading {
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

.status-flow {
  margin-top: 16px;
}

.detail-block {
  margin-top: 10px;
}

.detail-section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.payment-empty {
  padding: 20px 0 4px;
}

.manual-status-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 12px;
}

.manual-status-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.manual-status-select {
  flex: 1;
  min-width: 0;
}

.payment-reference {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  overflow-wrap: anywhere;
}

.amount-match-tag {
  margin-left: 8px;
}

.refund-field-hint {
  display: block;
  margin-top: -6px;
  font-size: 12px;
}

.order-item-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  width: 100%;
}

.order-item-name {
  margin-bottom: 4px;
  font-weight: 600;
}

.order-item-values {
  display: flex;
  align-items: center;
  gap: 10px;
  white-space: nowrap;
}

:deep(.table-actions) {
  display: flex;
  gap: 8px;
}

@media (max-width: 640px) {
  .page-heading,
  .table-header,
  .detail-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .order-item-row,
  .order-item-values,
  .manual-status-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .manual-status-select,
  .manual-status-actions :deep(.n-button) {
    width: 100%;
  }
}
</style>
