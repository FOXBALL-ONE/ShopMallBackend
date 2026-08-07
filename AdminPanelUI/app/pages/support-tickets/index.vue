<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, TagProps } from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import {
  SUPPORT_SERVICE_TYPE_OPTIONS,
  SUPPORT_TICKET_PRIORITY_OPTIONS,
  SUPPORT_TICKET_STATUS_OPTIONS,
} from '~/composables/useSupportTicketApi'
import type {
  SupportServiceType,
  SupportTicketListItem,
  SupportTicketListQuery,
  SupportTicketPriority,
  SupportTicketStatus,
} from '~/types/support-ticket'

definePageMeta({ layout: 'default' })

const api = useSupportTicketApi()
const message = useMessage()
const loading = ref(false)
const tickets = ref<SupportTicketListItem[]>([])
const selectedTicketId = ref<number | null>(null)
const detailOpen = ref(false)

const filters = reactive<{
  status: SupportTicketStatus | null
  serviceType: SupportServiceType | null
  priority: SupportTicketPriority | null
  customerUsername: string
  orderNo: string
}>({
  status: null,
  serviceType: null,
  priority: null,
  customerUsername: '',
  orderNo: '',
})

const pagination = reactive({
  page: 1,
  pageSize: 25,
  pageCount: 1,
})

const pageSizeOptions = [10, 25, 50, 100]
const resultSummary = computed(() => {
  if (loading.value) return '正在加载工单…'
  if (tickets.value.length === 0) return '当前条件下没有工单'
  return `第 ${pagination.page} / ${pagination.pageCount} 页，本页 ${tickets.value.length} 条`
})

function statusLabel(status: SupportTicketStatus): string {
  return SUPPORT_TICKET_STATUS_OPTIONS.find(option => option.value === status)?.label ?? status
}

function statusTagType(status: SupportTicketStatus): TagProps['type'] {
  const types: Record<SupportTicketStatus, TagProps['type']> = {
    OPEN: 'warning',
    IN_PROGRESS: 'info',
    RESOLVED: 'success',
    CLOSED: 'default',
  }
  return types[status]
}

function priorityLabel(priority: SupportTicketPriority): string {
  return SUPPORT_TICKET_PRIORITY_OPTIONS.find(option => option.value === priority)?.label ?? priority
}

function priorityTagType(priority: SupportTicketPriority): TagProps['type'] {
  const types: Record<SupportTicketPriority, TagProps['type']> = {
    LOW: 'default',
    MEDIUM: 'warning',
    HIGH: 'error',
  }
  return types[priority]
}

function serviceTypeLabel(serviceType: SupportServiceType): string {
  return SUPPORT_SERVICE_TYPE_OPTIONS.find(option => option.value === serviceType)?.label ?? serviceType
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

async function loadTickets() {
  loading.value = true
  try {
    const query: SupportTicketListQuery = {
      page: pagination.page,
      size: pagination.pageSize,
    }
    if (filters.status) query.status = filters.status
    if (filters.serviceType) query.service_type = filters.serviceType
    if (filters.priority) query.priority = filters.priority
    if (filters.customerUsername.trim()) query.customer_username = filters.customerUsername.trim()
    if (filters.orderNo.trim()) query.order_no = filters.orderNo.trim()

    const data = await api.list(query)
    const pageCount = Math.max(data.pagination.count, 1)
    if (pagination.page > pageCount) {
      pagination.page = pageCount
      await loadTickets()
      return
    }
    tickets.value = data.list ?? []
    pagination.pageCount = pageCount
  } catch (error) {
    tickets.value = []
    message.error(`加载工单列表失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function searchTickets() {
  pagination.page = 1
  await loadTickets()
}

async function resetFilters() {
  filters.status = null
  filters.serviceType = null
  filters.priority = null
  filters.customerUsername = ''
  filters.orderNo = ''
  pagination.page = 1
  await loadTickets()
}

async function changePage(page: number) {
  pagination.page = page
  await loadTickets()
}

async function changePageSize(pageSize: number) {
  pagination.pageSize = pageSize
  pagination.page = 1
  await loadTickets()
}

function openDetail(ticketId: number) {
  selectedTicketId.value = ticketId
  detailOpen.value = true
}

const columns: DataTableColumns<SupportTicketListItem> = [
  {
    title: '工单号',
    key: 'id',
    width: 92,
    render: row => h(
      NButton,
      { text: true, type: 'primary', onClick: () => openDetail(row.id) },
      { default: () => `#${row.id}` },
    ),
  },
  {
    title: '主题',
    key: 'subject',
    minWidth: 220,
    ellipsis: { tooltip: true },
  },
  {
    title: '客户用户名',
    key: 'customer_username',
    width: 140,
  },
  {
    title: '服务类型',
    key: 'service_type',
    width: 110,
    render: row => serviceTypeLabel(row.service_type),
  },
  {
    title: '优先级',
    key: 'priority',
    width: 90,
    render: row => h(
      NTag,
      { size: 'small', type: priorityTagType(row.priority), bordered: false },
      { default: () => priorityLabel(row.priority) },
    ),
  },
  {
    title: '状态',
    key: 'status',
    width: 100,
    render: row => h(
      NTag,
      { size: 'small', type: statusTagType(row.status) },
      { default: () => statusLabel(row.status) },
    ),
  },
  {
    title: '订单号',
    key: 'order_no',
    width: 170,
    ellipsis: { tooltip: true },
    render: row => row.order_no || '-',
  },
  {
    title: '处理人',
    key: 'handled_by_username',
    width: 120,
    render: row => row.handled_by_username ?? '-',
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
    width: 100,
    fixed: 'right',
    render: row => h(
      NButton,
      { size: 'small', tertiary: true, type: 'primary', onClick: () => openDetail(row.id) },
      { default: () => '查看处理' },
    ),
  },
]

onMounted(() => {
  void loadTickets()
})
</script>

<template>
  <div class="support-ticket-page">
    <NSpace vertical :size="12">
      <div class="page-heading">
        <div>
          <h2>工单支持</h2>
          <NText depth="3">筛选客户工单、更新处理状态并通过消息和附件回复客户。</NText>
        </div>
        <NButton :loading="loading" @click="loadTickets">
          刷新
        </NButton>
      </div>

      <NCard size="small" :bordered="false">
        <NGrid cols="1 s:2 m:3 l:5" :x-gap="12" :y-gap="4" responsive="screen">
          <NFormItemGi label="状态">
            <NSelect
              v-model:value="filters.status"
              :options="SUPPORT_TICKET_STATUS_OPTIONS"
              clearable
              placeholder="全部状态"
            />
          </NFormItemGi>
          <NFormItemGi label="服务类型">
            <NSelect
              v-model:value="filters.serviceType"
              :options="SUPPORT_SERVICE_TYPE_OPTIONS"
              clearable
              placeholder="全部类型"
            />
          </NFormItemGi>
          <NFormItemGi label="优先级">
            <NSelect
              v-model:value="filters.priority"
              :options="SUPPORT_TICKET_PRIORITY_OPTIONS"
              clearable
              placeholder="全部优先级"
            />
          </NFormItemGi>
          <NFormItemGi label="客户用户名">
            <NInput
              v-model:value="filters.customerUsername"
              maxlength="50"
              clearable
              placeholder="精确查询"
              @keyup.enter="searchTickets"
            />
          </NFormItemGi>
          <NFormItemGi label="订单号">
            <NInput
              v-model:value="filters.orderNo"
              maxlength="32"
              clearable
              placeholder="精确查询"
              @keyup.enter="searchTickets"
            />
          </NFormItemGi>
        </NGrid>
        <NSpace justify="end">
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
          <NButton type="primary" :loading="loading" @click="searchTickets">
            查询
          </NButton>
        </NSpace>
      </NCard>

      <NCard size="small" :bordered="false">
        <template #header>
          <div class="table-header">
            <span>工单列表</span>
            <NText depth="3" class="result-summary">{{ resultSummary }}</NText>
          </div>
        </template>

        <NDataTable
          :columns="columns"
          :data="tickets"
          :loading="loading"
          :pagination="false"
          :scroll-x="1280"
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

    <SupportTicketDetailDrawer
      v-model:open="detailOpen"
      :ticket-id="selectedTicketId"
      @updated="loadTickets"
    />
  </div>
</template>

<style scoped>
.support-ticket-page {
  display: flex;
  flex-direction: column;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}

.page-heading h2 {
  margin: 0 0 4px;
  font-size: 22px;
}

.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
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

@media (max-width: 640px) {
  .page-heading,
  .table-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
