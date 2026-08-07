<script setup lang="ts">
import { Pencil, Plus, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type {
  DataTableColumns,
  DataTableRowKey,
  DropdownOption,
  FormInst,
  FormRules,
  TagProps,
} from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import type {
  AdminUserBatchMutation,
  AdminUserDetail,
  AdminUserListItem,
  AdminUserListQuery,
  AdminUserMutation,
  AdminUserRole,
  AdminUserStatus,
} from '~/types/user'

definePageMeta({ layout: 'default' })

type EditorMode = 'create' | 'edit'
type BatchAction = 'enable' | 'disable' | 'customer' | 'admin' | 'active' | 'inactive' | 'restore'

const api = useUserApi()
const message = useMessage()
const { confirmDeleteRequest } = useDeleteConfirmation()
const { user: currentSession } = useHttp()

const loading = ref(false)
const editorLoading = ref(false)
const saving = ref(false)
const batchLoading = ref(false)
const deletingUserId = ref<number | null>(null)
const users = ref<AdminUserListItem[]>([])
const checkedRowKeys = ref<DataTableRowKey[]>([])
const editorOpen = ref(false)
const editorMode = ref<EditorMode>('create')
const editingUser = ref<AdminUserDetail | null>(null)
const formRef = ref<FormInst | null>(null)

const filters = reactive<{
  keyword: string
  role: AdminUserRole | null
  status: AdminUserStatus | null
  enabled: 'enabled' | 'disabled' | null
}>({
  keyword: '',
  role: null,
  status: null,
  enabled: null,
})

const pagination = reactive({
  page: 1,
  pageSize: 25,
  totalItems: 0,
  pageCount: 1,
})

const form = reactive<{
  email: string
  username: string
  password: string
  firstName: string
  lastName: string
  phone: string
  avatar: string
  locale: string
  currency: string
  birthday: string | null
  emailVerified: boolean
  marketingConsent: boolean
  role: AdminUserRole
  enabled: boolean
  status: AdminUserStatus
}>({
  email: '',
  username: '',
  password: '',
  firstName: '',
  lastName: '',
  phone: '',
  avatar: '',
  locale: '',
  currency: 'USD',
  birthday: null,
  emailVerified: false,
  marketingConsent: false,
  role: 'CUSTOMER',
  enabled: true,
  status: 'ACTIVE',
})

const roleOptions = [
  { label: '客户', value: 'CUSTOMER' },
  { label: '管理员', value: 'ADMIN' },
]

const statusOptions = [
  { label: '正常', value: 'ACTIVE' },
  { label: '停用', value: 'INACTIVE' },
  { label: '已删除', value: 'DELETED' },
]

const enabledOptions = [
  { label: '允许登录', value: 'enabled' },
  { label: '禁止登录', value: 'disabled' },
]

const batchOptions: DropdownOption[] = [
  { label: '允许登录', key: 'enable' },
  { label: '禁止登录', key: 'disable' },
  { type: 'divider', key: 'access-divider' },
  { label: '设为客户', key: 'customer' },
  { label: '设为管理员', key: 'admin' },
  { type: 'divider', key: 'role-divider' },
  { label: '状态设为正常', key: 'active' },
  { label: '状态设为停用', key: 'inactive' },
]

const pageSizeOptions = [10, 25, 50, 100]
const currentAdminId = computed(() => (currentSession.value as { id?: number } | null)?.id ?? null)
const selectedRows = computed(() => {
  const ids = new Set(checkedRowKeys.value.map(Number))
  return users.value.filter(item => ids.has(item.id))
})
const selectedAvailable = computed(() => selectedRows.value.filter(item => item.status !== 'DELETED'))
const selectedDeleted = computed(() => selectedRows.value.filter(item => item.status === 'DELETED'))
const isEditingSelf = computed(() => (
  editorMode.value === 'edit' && editingUser.value?.id === currentAdminId.value
))
const resultSummary = computed(() => {
  if (loading.value) return '正在加载用户…'
  if (users.value.length === 0) return '当前条件下没有用户'
  return `第 ${pagination.page} / ${pagination.pageCount} 页，共 ${pagination.totalItems} 位用户`
})
const pageStats = computed(() => ({
  current: users.value.length,
  customers: users.value.filter(item => item.role === 'CUSTOMER').length,
  admins: users.value.filter(item => item.role === 'ADMIN').length,
  blocked: users.value.filter(item => !item.enabled || item.status !== 'ACTIVE').length,
}))

const formRules = computed<FormRules>(() => ({
  email: [
    { required: true, message: '请输入邮箱', trigger: ['blur', 'input'] },
    { type: 'email', message: '请输入有效邮箱', trigger: ['blur', 'input'] },
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: ['blur', 'input'] },
    { min: 3, max: 50, message: '用户名长度为 3 到 50 个字符', trigger: ['blur', 'input'] },
  ],
  password: editorMode.value === 'create'
    ? [
        { required: true, message: '请输入初始密码', trigger: ['blur', 'input'] },
        { min: 8, max: 72, message: '密码长度为 8 到 72 个字符', trigger: ['blur', 'input'] },
      ]
    : [],
  phone: [{ pattern: /^\+[1-9]\d{7,14}$/, message: '电话需使用 E.164 格式，例如 +14155550123', trigger: ['blur', 'input'] }],
  currency: [{ pattern: /^[A-Za-z]{3}$/, message: '币种需为 3 位代码，例如 USD', trigger: ['blur', 'input'] }],
}))

function roleLabel(role: AdminUserRole): string {
  return role === 'ADMIN' ? '管理员' : '客户'
}

function statusLabel(status: AdminUserStatus): string {
  if (status === 'ACTIVE') return '正常'
  if (status === 'INACTIVE') return '停用'
  return '已删除'
}

function statusTagType(status: AdminUserStatus): TagProps['type'] {
  if (status === 'ACTIVE') return 'success'
  if (status === 'INACTIVE') return 'warning'
  return 'default'
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

function resetForm() {
  form.email = ''
  form.username = ''
  form.password = ''
  form.firstName = ''
  form.lastName = ''
  form.phone = ''
  form.avatar = ''
  form.locale = ''
  form.currency = 'USD'
  form.birthday = null
  form.emailVerified = false
  form.marketingConsent = false
  form.role = 'CUSTOMER'
  form.enabled = true
  form.status = 'ACTIVE'
  formRef.value?.restoreValidation()
}

async function loadUsers() {
  loading.value = true
  try {
    const query: AdminUserListQuery = {
      page: pagination.page,
      size: pagination.pageSize,
    }
    if (filters.keyword.trim()) query.keyword = filters.keyword.trim()
    if (filters.role) query.role = filters.role
    if (filters.status) query.status = filters.status
    if (filters.enabled !== null) query.enabled = filters.enabled === 'enabled'
    const data = await api.list(query)
    const pageCount = Math.max(data.pagination.total_pages, 1)
    if (pagination.page > pageCount) {
      pagination.page = pageCount
      await loadUsers()
      return
    }
    users.value = data.list ?? []
    pagination.pageCount = pageCount
    pagination.totalItems = data.pagination.total_items
    checkedRowKeys.value = []
  } catch (error) {
    users.value = []
    pagination.totalItems = 0
    pagination.pageCount = 1
    message.error(`加载用户失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function searchUsers() {
  pagination.page = 1
  await loadUsers()
}

async function resetFilters() {
  filters.keyword = ''
  filters.role = null
  filters.status = null
  filters.enabled = null
  pagination.page = 1
  await loadUsers()
}

async function changePage(page: number) {
  pagination.page = page
  await loadUsers()
}

async function changePageSize(size: number) {
  pagination.pageSize = size
  pagination.page = 1
  await loadUsers()
}

function openCreate() {
  editorMode.value = 'create'
  editingUser.value = null
  resetForm()
  editorOpen.value = true
}

async function openEdit(row: AdminUserListItem) {
  editorMode.value = 'edit'
  editingUser.value = null
  resetForm()
  editorOpen.value = true
  editorLoading.value = true
  try {
    const detail = await api.getOne(row.id)
    editingUser.value = detail
    form.email = detail.email
    form.username = detail.username
    form.firstName = detail.first_name
    form.lastName = detail.last_name
    form.phone = detail.phone ?? ''
    form.avatar = detail.avatar ?? ''
    form.locale = detail.locale ?? ''
    form.currency = detail.currency ?? ''
    form.birthday = detail.birthday
    form.emailVerified = detail.email_verified
    form.marketingConsent = detail.marketing_consent
    form.role = detail.role
    form.enabled = detail.enabled
    form.status = detail.status
  } catch (error) {
    editorOpen.value = false
    message.error(`加载用户详情失败：${errorMessage(error)}`)
  } finally {
    editorLoading.value = false
  }
}

function closeEditor() {
  if (saving.value) return
  editorOpen.value = false
  editingUser.value = null
  resetForm()
}

function changeFormStatus(status: AdminUserStatus) {
  form.status = status
  if (status !== 'ACTIVE') form.enabled = false
}

async function submitEditor() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  if (form.status !== 'ACTIVE' && form.enabled) {
    message.warning('非正常状态的用户不能允许登录')
    return
  }
  const payload: AdminUserMutation = {
    email: form.email.trim(),
    username: form.username.trim(),
    first_name: form.firstName.trim(),
    last_name: form.lastName.trim(),
    phone: form.phone.trim() || undefined,
    avatar: form.avatar.trim() || undefined,
    locale: form.locale.trim() || undefined,
    currency: form.currency.trim().toUpperCase() || undefined,
    birthday: form.birthday || undefined,
    email_verified: form.emailVerified,
    marketing_consent: form.marketingConsent,
    role: form.role,
    enabled: form.enabled,
    status: form.status,
  }
  if (editorMode.value === 'create') payload.password = form.password

  saving.value = true
  try {
    if (editorMode.value === 'create') {
      await api.create(payload)
      message.success('用户已创建')
    } else if (editingUser.value) {
      await api.update(editingUser.value.id, payload)
      message.success('用户信息已更新')
    }
    editorOpen.value = false
    editingUser.value = null
    await loadUsers()
  } catch (error) {
    message.error(`${editorMode.value === 'create' ? '创建' : '更新'}失败：${errorMessage(error)}`)
  } finally {
    saving.value = false
  }
}

function confirmDelete(row: AdminUserListItem) {
  confirmDeleteRequest({
    title: '删除用户',
    content: `确认删除用户 ${row.username}？账号将立即禁止登录，历史订单、工单和物流记录会保留。`,
    positiveText: '删除',
    onConfirm: async () => {
      deletingUserId.value = row.id
      try {
        await api.deleteOne(row.id)
        message.success(`用户 ${row.username} 已删除`)
        await loadUsers()
      } catch (error) {
        message.error(`删除失败：${errorMessage(error)}`)
      } finally {
        deletingUserId.value = null
      }
    },
  })
}

async function runBatchAction(action: BatchAction) {
  const rows = action === 'restore'
    ? selectedDeleted.value
    : action === 'enable'
      ? selectedAvailable.value.filter(item => item.status === 'ACTIVE')
      : selectedAvailable.value
  if (rows.length === 0) {
    message.info(action === 'restore' ? '没有可恢复的用户' : '没有可批量处理的用户')
    return
  }
  const payload: AdminUserBatchMutation = { ids: rows.map(item => item.id) }
  if (action === 'enable') {
    payload.status = 'ACTIVE'
    payload.enabled = true
  }
  if (action === 'disable') payload.enabled = false
  if (action === 'customer') payload.role = 'CUSTOMER'
  if (action === 'admin') payload.role = 'ADMIN'
  if (action === 'active') payload.status = 'ACTIVE'
  if (action === 'active') payload.enabled = true
  if (action === 'inactive') {
    payload.status = 'INACTIVE'
    payload.enabled = false
  }
  if (action === 'restore') {
    payload.status = 'ACTIVE'
    payload.enabled = true
  }
  batchLoading.value = true
  try {
    const result = await api.updateBatch(payload)
    message.success(`已更新 ${result.updated} 位用户`)
    await loadUsers()
  } catch (error) {
    message.error(`批量更新失败：${errorMessage(error)}`)
  } finally {
    batchLoading.value = false
  }
}

function confirmBatchDelete() {
  const ids = selectedAvailable.value.map(item => item.id)
  if (ids.length === 0) {
    message.info('没有可删除的用户')
    return
  }
  confirmDeleteRequest({
    title: '批量删除用户',
    content: `确认删除选中的 ${ids.length} 位用户？这些账号将立即禁止登录，历史业务记录会保留。`,
    positiveText: '批量删除',
    onConfirm: async () => {
      batchLoading.value = true
      try {
        const result = await api.deleteBatch(ids)
        message.success(`已删除 ${result.deleted} 位用户`)
        await loadUsers()
      } catch (error) {
        message.error(`批量删除失败：${errorMessage(error)}`)
      } finally {
        batchLoading.value = false
      }
    },
  })
}

function handleBatchSelect(key: string) {
  void runBatchAction(key as BatchAction)
}

const columns: DataTableColumns<AdminUserListItem> = [
  {
    type: 'selection',
    disabled: row => row.id === currentAdminId.value,
  },
  {
    title: '用户',
    key: 'username',
    width: 190,
    fixed: 'left',
    render: row => h('div', { class: 'identity-cell' }, [
      h('strong', row.username),
      h('span', [row.first_name, row.last_name].filter(Boolean).join(' ') || '未填写姓名'),
    ]),
  },
  {
    title: '邮箱',
    key: 'email',
    minWidth: 220,
    ellipsis: { tooltip: true },
  },
  {
    title: '角色',
    key: 'role',
    width: 100,
    render: row => h(
      NTag,
      { size: 'small', type: row.role === 'ADMIN' ? 'info' : 'default', bordered: false },
      { default: () => roleLabel(row.role) },
    ),
  },
  {
    title: '账号状态',
    key: 'status',
    width: 130,
    render: row => h('div', { class: 'status-cell' }, [
      h(NTag, { size: 'small', type: statusTagType(row.status), bordered: false }, { default: () => statusLabel(row.status) }),
      h('span', { class: row.enabled ? 'login-allowed' : 'login-blocked' }, row.enabled ? '可登录' : '禁止登录'),
    ]),
  },
  {
    title: '邮箱验证',
    key: 'email_verified',
    width: 100,
    render: row => row.email_verified ? '已验证' : '未验证',
  },
  {
    title: '最近登录',
    key: 'last_login_at',
    width: 180,
    render: row => formatDate(row.last_login_at),
  },
  {
    title: '创建时间',
    key: 'created_at',
    width: 180,
    render: row => formatDate(row.created_at),
  },
  {
    title: '操作',
    key: 'actions',
    width: 170,
    fixed: 'right',
    render: row => h('div', { class: 'table-actions' }, [
      h(
        NButton,
        { size: 'small', tertiary: true, onClick: () => openEdit(row) },
        { icon: () => h(Pencil, { size: 14 }), default: () => '编辑' },
      ),
      h(
        NButton,
        {
          size: 'small',
          tertiary: true,
          type: 'error',
          disabled: row.status === 'DELETED' || row.id === currentAdminId.value,
          loading: deletingUserId.value === row.id,
          onClick: () => confirmDelete(row),
        },
        { icon: () => h(Trash2, { size: 14 }), default: () => '删除' },
      ),
    ]),
  },
]

onMounted(() => {
  void loadUsers()
})
</script>

<template>
  <div class="user-page">
    <NSpace vertical :size="12">
      <div class="page-heading">
        <div>
          <h2>用户管理</h2>
          <NText depth="3">管理客户与管理员账号、登录准入和账号生命周期。</NText>
        </div>
        <NSpace>
          <NButton :loading="loading" @click="loadUsers">刷新</NButton>
          <NButton type="primary" @click="openCreate">
            <template #icon><Plus :size="16" /></template>
            新建用户
          </NButton>
        </NSpace>
      </div>

      <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="12" responsive="screen">
        <NGridItem><NCard size="small" :bordered="false"><NStatistic label="本页用户" :value="pageStats.current" /></NCard></NGridItem>
        <NGridItem><NCard size="small" :bordered="false"><NStatistic label="客户" :value="pageStats.customers" /></NCard></NGridItem>
        <NGridItem><NCard size="small" :bordered="false"><NStatistic label="管理员" :value="pageStats.admins" /></NCard></NGridItem>
        <NGridItem><NCard size="small" :bordered="false"><NStatistic label="受限账号" :value="pageStats.blocked" /></NCard></NGridItem>
      </NGrid>

      <NCard size="small" :bordered="false">
        <NGrid cols="1 s:2 m:4" :x-gap="12" :y-gap="4" responsive="screen">
          <NFormItemGi label="关键词">
            <NInput
              v-model:value="filters.keyword"
              maxlength="100"
              clearable
              placeholder="用户名、邮箱或姓名"
              @keyup.enter="searchUsers"
            />
          </NFormItemGi>
          <NFormItemGi label="角色">
            <NSelect v-model:value="filters.role" :options="roleOptions" clearable placeholder="全部角色" />
          </NFormItemGi>
          <NFormItemGi label="状态">
            <NSelect v-model:value="filters.status" :options="statusOptions" clearable placeholder="全部状态" />
          </NFormItemGi>
          <NFormItemGi label="登录权限">
            <NSelect v-model:value="filters.enabled" :options="enabledOptions" clearable placeholder="全部账号" />
          </NFormItemGi>
        </NGrid>
        <NSpace justify="end">
          <NButton :disabled="loading" @click="resetFilters">重置</NButton>
          <NButton type="primary" :loading="loading" @click="searchUsers">查询</NButton>
        </NSpace>
      </NCard>

      <NCard size="small" :bordered="false">
        <template #header>
          <div class="table-header">
            <NSpace align="center">
              <span>用户列表</span>
              <NText v-if="checkedRowKeys.length" depth="3">已选 {{ checkedRowKeys.length }} 项</NText>
              <NText v-else depth="3" class="result-summary">{{ resultSummary }}</NText>
            </NSpace>
            <NSpace>
              <NButton
                size="small"
                :disabled="selectedDeleted.length === 0 || batchLoading"
                @click="runBatchAction('restore')"
              >
                恢复账号
              </NButton>
              <NDropdown
                trigger="click"
                :options="batchOptions"
                :disabled="selectedAvailable.length === 0 || batchLoading"
                @select="handleBatchSelect"
              >
                <NButton size="small" :loading="batchLoading">批量设置</NButton>
              </NDropdown>
              <NButton
                size="small"
                type="error"
                ghost
                :disabled="selectedAvailable.length === 0 || batchLoading"
                @click="confirmBatchDelete"
              >
                <template #icon><Trash2 :size="15" /></template>
                批量删除
              </NButton>
            </NSpace>
          </div>
        </template>

        <NDataTable
          :columns="columns"
          :data="users"
          :row-key="row => row.id"
          :loading="loading || batchLoading"
          :pagination="false"
          :checked-row-keys="checkedRowKeys"
          :scroll-x="1380"
          size="small"
          @update:checked-row-keys="keys => checkedRowKeys = keys"
        />

        <div class="pagination-bar">
          <NPagination
            :page="pagination.page"
            :page-size="pagination.pageSize"
            :item-count="pagination.totalItems"
            :page-sizes="pageSizeOptions"
            show-size-picker
            :disabled="loading || batchLoading"
            @update:page="changePage"
            @update:page-size="changePageSize"
          />
        </div>
      </NCard>
    </NSpace>

    <NDrawer v-model:show="editorOpen" placement="right" width="min(720px, 96vw)">
      <NDrawerContent :title="editorMode === 'create' ? '新建用户' : `编辑用户 · ${editingUser?.username ?? ''}`" closable>
        <NSpin :show="editorLoading">
          <NForm ref="formRef" :model="form" :rules="formRules" label-placement="top">
            <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
              <NFormItemGi label="用户名" path="username">
                <NInput v-model:value="form.username" maxlength="50" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="邮箱" path="email">
                <NInput v-model:value="form.email" maxlength="100" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi v-if="editorMode === 'create'" label="初始密码" path="password" :span="2">
                <NInput
                  v-model:value="form.password"
                  type="password"
                  show-password-on="click"
                  maxlength="72"
                  :disabled="saving"
                />
              </NFormItemGi>
              <NFormItemGi label="名">
                <NInput v-model:value="form.firstName" maxlength="50" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="姓">
                <NInput v-model:value="form.lastName" maxlength="50" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="电话" path="phone">
                <NInput v-model:value="form.phone" maxlength="20" placeholder="+14155550123" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="生日">
                <NDatePicker
                  v-model:formatted-value="form.birthday"
                  type="date"
                  value-format="yyyy-MM-dd"
                  clearable
                  style="width: 100%"
                  :disabled="saving"
                />
              </NFormItemGi>
              <NFormItemGi label="语言区域">
                <NInput v-model:value="form.locale" maxlength="16" placeholder="zh-CN" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="币种" path="currency">
                <NInput v-model:value="form.currency" maxlength="3" placeholder="USD" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="头像 URL" :span="2">
                <NInput v-model:value="form.avatar" maxlength="512" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="角色">
                <NSelect
                  v-model:value="form.role"
                  :options="roleOptions"
                  :disabled="saving || isEditingSelf"
                />
              </NFormItemGi>
              <NFormItemGi label="账号状态">
                <NSelect
                  :value="form.status"
                  :options="statusOptions"
                  :disabled="saving || isEditingSelf"
                  @update:value="changeFormStatus"
                />
              </NFormItemGi>
              <NFormItemGi label="允许登录">
                <NSwitch v-model:value="form.enabled" :disabled="saving || isEditingSelf || form.status !== 'ACTIVE'" />
              </NFormItemGi>
              <NFormItemGi label="邮箱已验证">
                <NSwitch v-model:value="form.emailVerified" :disabled="saving" />
              </NFormItemGi>
              <NFormItemGi label="接收营销邮件">
                <NSwitch v-model:value="form.marketingConsent" :disabled="saving" />
              </NFormItemGi>
            </NGrid>
          </NForm>

          <template v-if="editingUser">
            <NDivider>登录记录</NDivider>
            <NDescriptions :column="2" label-placement="left" size="small">
              <NDescriptionsItem label="最近登录">{{ formatDate(editingUser.last_login_at) }}</NDescriptionsItem>
              <NDescriptionsItem label="登录 IP">{{ editingUser.last_login_ip ?? '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="创建时间">{{ formatDate(editingUser.created_at) }}</NDescriptionsItem>
              <NDescriptionsItem label="更新时间">{{ formatDate(editingUser.updated_at) }}</NDescriptionsItem>
            </NDescriptions>
          </template>
        </NSpin>

        <template #footer>
          <NSpace justify="end">
            <NButton :disabled="saving" @click="closeEditor">取消</NButton>
            <NButton type="primary" :loading="saving" :disabled="editorLoading" @click="submitEditor">
              {{ editorMode === 'create' ? '创建用户' : '保存修改' }}
            </NButton>
          </NSpace>
        </template>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.user-page {
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

.result-summary {
  font-size: 13px;
  font-weight: 400;
}

.identity-cell,
.status-cell {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.identity-cell span,
.login-allowed,
.login-blocked {
  font-size: 12px;
  color: #8c8c8c;
}

.login-blocked {
  color: #d03050;
}

.table-actions {
  display: flex;
  gap: 8px;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

@media (max-width: 720px) {
  .page-heading,
  .table-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
