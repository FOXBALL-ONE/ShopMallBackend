<script setup lang="ts">
import { Megaphone, Plus, RefreshCw, Trash2 } from '@lucide/vue'
import { computed, h, onMounted, reactive, ref } from 'vue'
import type { DataTableColumns, DataTableRowKey, FormInst, FormRules, TagProps } from 'naive-ui'
import { NButton, NDatePicker, NTag, useMessage } from 'naive-ui'
import AnnouncementAuditModal from '~/components/AnnouncementAuditModal.vue'
import {
  ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS,
  ANNOUNCEMENT_STATUS_OPTIONS,
  ANNOUNCEMENT_TYPE_OPTIONS,
  useAnnouncementApi,
} from '~/composables/useAnnouncementApi'
import type {
  AdminAnnouncementDetail,
  AdminAnnouncementListItem,
  AnnouncementAuditLog,
  AnnouncementAutoShowMode,
  AnnouncementFormInput,
  AnnouncementStatus,
  AnnouncementType,
} from '~/types/announcement'

definePageMeta({ layout: 'default' })

const api = useAnnouncementApi()
const message = useMessage()
const { confirmDeleteRequest } = useDeleteConfirmation()
const runtimeConfig = useRuntimeConfig()
const announcementTimeZone = runtimeConfig.public.announcementTimeZone as string
const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const editorOpen = ref(false)
const auditOpen = ref(false)
const offlineOpen = ref(false)
const editing = ref<AdminAnnouncementDetail | null>(null)
const auditTarget = ref<AdminAnnouncementListItem | null>(null)
const auditItems = ref<AnnouncementAuditLog[]>([])
const auditLoading = ref(false)
const offlineTarget = ref<AdminAnnouncementListItem | null>(null)
const offlineReason = ref('')
const announcements = ref<AdminAnnouncementListItem[]>([])
const checkedRowKeys = ref<DataTableRowKey[]>([])

const filters = reactive({
  keyword: '',
  status: null as AnnouncementStatus | null,
  type: null as AnnouncementType | null,
  autoShowEnabled: null as 'true' | 'false' | null,
})
const pagination = reactive({ page: 1, pageSize: 25, pageCount: 1, total: 0 })

function localDateTimeNow() {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: announcementTimeZone,
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  }).formatToParts(new Date())
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}T${values.hour}:${values.minute}:${values.second}`
}

const form = reactive({
  title: '', summary: '', content: '', type: 'GENERAL' as AnnouncementType, priority: 50,
  publicHistory: true, autoShowEnabled: false,
  autoShowMode: 'ONCE_PER_ANNOUNCEMENT' as AnnouncementAutoShowMode,
  autoShowCooldownHours: null as number | null, actionUrl: '',
  publishedAt: null as string | null,
  effectiveFrom: localDateTimeNow() as string | null,
  effectiveUntil: null as string | null,
})

const formRules: FormRules = {
  title: [{ required: true, message: '请输入公告标题', trigger: ['blur', 'input'] }],
  summary: [{ required: true, message: '请输入公告摘要', trigger: ['blur', 'input'] }],
  content: [{ required: true, message: '请输入公告正文', trigger: ['blur', 'input'] }],
  priority: [{ required: true, type: 'number', min: 0, max: 100, message: '优先级必须在 0 到 100 之间', trigger: ['blur', 'change'] }],
  effectiveFrom: [{ required: true, message: `请选择生效时间，时间按 ${announcementTimeZone} 解释`, trigger: ['blur', 'change'] }],
  effectiveUntil: [{
    validator: (_rule, value: string | null) => !value || !form.effectiveFrom || value > form.effectiveFrom,
    message: '结束时间必须晚于生效时间；如公告长期有效，请将结束时间留空',
    trigger: ['blur', 'change'],
  }],
}

const editorTitle = computed(() => editing.value ? `编辑公告 #${editing.value.id}` : '新建公告草稿')
const activeCount = computed(() => announcements.value.filter(item => item.status === 'PUBLISHED').length)
const autoShowCount = computed(() => announcements.value.filter(item => item.auto_show_enabled).length)
const cooldownRequired = computed(() => form.autoShowEnabled && form.autoShowMode === 'COOLDOWN')
const selectedAnnouncementIds = computed(() => checkedRowKeys.value.map(Number).filter(Number.isInteger))

function errorMessage(error: unknown) {
  if (error && typeof error === 'object') {
    const value = error as { statusCode?: number; statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '请求失败'
  }
  return '请求失败'
}

function statusLabel(status: AnnouncementStatus) {
  return ANNOUNCEMENT_STATUS_OPTIONS.find(item => item.value === status)?.label ?? status
}

function typeLabel(type: AnnouncementType) {
  return ANNOUNCEMENT_TYPE_OPTIONS.find(item => item.value === type)?.label ?? type
}

function autoShowModeLabel(mode: AnnouncementAutoShowMode) {
  return ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS.find(item => item.value === mode)?.label ?? mode
}

function statusTagType(status: AnnouncementStatus): TagProps['type'] {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'SCHEDULED') return 'info'
  if (status === 'DRAFT') return 'default'
  if (status === 'OFFLINE') return 'warning'
  return 'error'
}

function priorityTagType(priority: number): TagProps['type'] {
  if (priority >= 80) return 'error'
  if (priority >= 50) return 'warning'
  return 'default'
}

function formatDate(value: string | null | undefined) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

function datetimeInputValue(value: string | null | undefined) {
  return value ? value.slice(0, 19) : null
}

function resetForm() {
  form.title = ''; form.summary = ''; form.content = ''; form.type = 'GENERAL'; form.priority = 50
  form.publicHistory = true; form.autoShowEnabled = false; form.autoShowMode = 'ONCE_PER_ANNOUNCEMENT'
  form.autoShowCooldownHours = null; form.actionUrl = ''; form.publishedAt = null; form.effectiveFrom = localDateTimeNow(); form.effectiveUntil = null
  formRef.value?.restoreValidation()
}

function fillForm(item: AdminAnnouncementDetail) {
  form.title = item.title; form.summary = item.summary; form.content = item.content; form.type = item.type
  form.priority = item.priority; form.publicHistory = item.public_history; form.autoShowEnabled = item.auto_show_enabled
  form.autoShowMode = item.auto_show_mode; form.autoShowCooldownHours = item.auto_show_cooldown_hours
  form.actionUrl = item.action_url || ''; form.publishedAt = datetimeInputValue(item.published_at); form.effectiveFrom = datetimeInputValue(item.effective_from)
  form.effectiveUntil = datetimeInputValue(item.effective_until)
  formRef.value?.restoreValidation()
}

function formInput(): AnnouncementFormInput {
  return {
    title: form.title.trim(), summary: form.summary.trim(), content: form.content.trim(), type: form.type,
    priority: Number(form.priority), publicHistory: form.publicHistory, autoShowEnabled: form.autoShowEnabled,
    autoShowMode: form.autoShowMode,
    autoShowCooldownHours: form.autoShowMode === 'COOLDOWN' ? form.autoShowCooldownHours : null,
    actionUrl: form.actionUrl.trim(), publishedAt: form.publishedAt,
    effectiveFrom: form.effectiveFrom || '', effectiveUntil: form.effectiveUntil,
  }
}

async function loadAnnouncements(showSuccess = false) {
  loading.value = true
  try {
    const data = await api.list({
      page: pagination.page - 1, size: pagination.pageSize, keyword: filters.keyword.trim() || undefined,
      status: filters.status || undefined, type: filters.type || undefined,
      auto_show_enabled: filters.autoShowEnabled === null ? undefined : filters.autoShowEnabled === 'true', sort_by: 'UPDATED_AT', sort_direction: 'DESC',
    })
    announcements.value = data.items || []
    pagination.page = data.page + 1
    pagination.pageCount = Math.max(data.total_pages, 1)
    pagination.total = data.total_elements
    checkedRowKeys.value = []
    if (showSuccess) message.success('已刷新公告列表')
  } catch (error) {
    announcements.value = []
    message.error(`加载公告失败：${errorMessage(error)}`)
  } finally { loading.value = false }
}

async function search() { pagination.page = 1; await loadAnnouncements() }
async function resetFilters() {
  filters.keyword = ''; filters.status = null; filters.type = null; filters.autoShowEnabled = null; pagination.page = 1
  await loadAnnouncements()
}
async function changePage(page: number) { pagination.page = page; await loadAnnouncements() }
async function changePageSize(size: number) { pagination.pageSize = size; pagination.page = 1; await loadAnnouncements() }

function confirmDelete(row: AdminAnnouncementListItem) {
  confirmDeleteRequest({
    title: '删除公告',
    content: `确认删除“${row.title}”？删除后公告将从管理列表和客户可见内容中移除，且不能恢复。`,
    positiveText: '删除',
    tone: 'error',
    onConfirm: async () => {
      deleting.value = true
      try {
        await api.deleteOne(row.id)
        message.success(`公告“${row.title}”已删除`)
        await loadAnnouncements()
      } catch (error) {
        message.error(`删除公告失败：${errorMessage(error)}`)
      } finally {
        deleting.value = false
      }
    },
  })
}

function confirmBatchDelete() {
  const ids = selectedAnnouncementIds.value
  if (ids.length === 0) {
    message.info('请先选择要删除的公告')
    return
  }
  confirmDeleteRequest({
    title: '批量删除公告',
    content: `确认删除选中的 ${ids.length} 条公告？删除后公告将从管理列表和客户可见内容中移除，且不能恢复。`,
    positiveText: '批量删除',
    tone: 'error',
    onConfirm: async () => {
      deleting.value = true
      try {
        const result = await api.deleteBatch(ids)
        message.success(`已删除 ${result.deleted} 条公告`)
        await loadAnnouncements()
      } catch (error) {
        message.error(`批量删除公告失败：${errorMessage(error)}`)
      } finally {
        deleting.value = false
      }
    },
  })
}

function openCreate() { editing.value = null; resetForm(); editorOpen.value = true }
async function openEdit(row: AdminAnnouncementListItem) {
  try { const detail = await api.getOne(row.id); editing.value = detail; fillForm(detail); editorOpen.value = true }
  catch (error) { message.error(`加载公告详情失败：${errorMessage(error)}`) }
}

async function save() {
  try { await formRef.value?.validate() } catch { return }
  const input = formInput()
  if (!input.title) { message.error('请输入公告标题'); return }
  if (input.effectiveUntil && input.effectiveUntil <= input.effectiveFrom) { message.error('结束时间必须晚于生效时间；如公告长期有效，请将结束时间留空'); return }
  if (input.autoShowEnabled && !input.publicHistory) { message.error('开启主动展示时必须允许公开保留历史公告'); return }
  if (input.autoShowMode === 'COOLDOWN' && (!input.autoShowCooldownHours || input.autoShowCooldownHours < 1 || input.autoShowCooldownHours > 720)) {
    message.error('冷却展示模式必须设置 1 到 720 小时的冷却时间'); return
  }
  saving.value = true
  try {
    if (editing.value) { await api.update(editing.value.id, input, editing.value.version); message.success('公告已保存') }
    else { await api.create(input); message.success('公告草稿已创建') }
    editorOpen.value = false; await loadAnnouncements()
  } catch (error) {
    if ((error as { statusCode?: number })?.statusCode === 409) { message.warning('公告已被其他管理员修改，请刷新后重新编辑'); await loadAnnouncements() }
    else message.error(`保存公告失败：${errorMessage(error)}`)
  } finally { saving.value = false }
}

async function publish(row: AdminAnnouncementListItem) {
  if (!window.confirm(`确认发布“${row.title}”吗？生效时间晚于当前时间时将进入排期状态。`)) return
  try { const result = await api.publish(row.id, row.version); message.success(result.status === 'SCHEDULED' ? '公告已排期' : '公告已发布'); await loadAnnouncements() }
  catch (error) { await handleActionError(error, '发布') }
}
function openOffline(row: AdminAnnouncementListItem) { offlineTarget.value = row; offlineReason.value = ''; offlineOpen.value = true }
async function offline() {
  const row = offlineTarget.value; const reason = offlineReason.value.trim()
  if (!row || !reason) { message.warning('请填写下线原因'); return }
  saving.value = true
  try { await api.offline(row.id, row.version, reason); message.success('公告已下线'); offlineOpen.value = false; await loadAnnouncements() }
  catch (error) { await handleActionError(error, '下线') } finally { saving.value = false }
}
async function archive(row: AdminAnnouncementListItem) {
  if (!window.confirm(`确认归档“${row.title}”吗？归档后不能直接编辑。`)) return
  try { await api.archive(row.id, row.version, row.public_history); message.success('公告已归档'); await loadAnnouncements() }
  catch (error) { await handleActionError(error, '归档') }
}
async function copyAnnouncement(row: AdminAnnouncementListItem) {
  if (!window.confirm(`确认复制“${row.title}”为新草稿吗？`)) return
  try { const copied = await api.copy(row.id, row.version); message.success(`已创建草稿 #${copied.id}`); await loadAnnouncements() }
  catch (error) { await handleActionError(error, '复制') }
}
async function handleActionError(error: unknown, action: string) {
  if ((error as { statusCode?: number })?.statusCode === 409) { message.warning('版本已变化，已刷新最新公告列表'); await loadAnnouncements() }
  else message.error(`${action}失败：${errorMessage(error)}`)
}
async function openAudit(row: AdminAnnouncementListItem) {
  auditTarget.value = row; auditItems.value = []; auditOpen.value = true; auditLoading.value = true
  try { auditItems.value = (await api.auditLogs(row.id)).items || [] }
  catch (error) { message.error(`加载审计记录失败：${errorMessage(error)}`) }
  finally { auditLoading.value = false }
}

const columns: DataTableColumns<AdminAnnouncementListItem> = [
  { type: 'selection' },
  { title: '公告', key: 'title', minWidth: 220, render: row => h('div', { class: 'title-cell' }, [h('strong', row.title), h('span', row.summary)]) },
  { title: '状态', key: 'status', width: 100, render: row => h(NTag, { size: 'small', type: statusTagType(row.status), bordered: false }, { default: () => statusLabel(row.status) }) },
  { title: '类型 / 优先级', key: 'priority', width: 150, render: row => h('div', { class: 'priority-cell' }, [h('span', typeLabel(row.type)), h(NTag, { size: 'small', type: priorityTagType(row.priority), bordered: false }, { default: () => String(row.priority) })]) },
  { title: '主动展示', key: 'auto_show_enabled', width: 155, render: row => row.auto_show_enabled ? h('div', { class: 'auto-show-cell' }, [h(NTag, { size: 'small', type: 'success', bordered: false }, { default: () => '已开启' }), h('span', row.auto_show_mode === 'COOLDOWN' ? `${autoShowModeLabel(row.auto_show_mode)}（${row.auto_show_cooldown_hours}h）` : autoShowModeLabel(row.auto_show_mode))]) : h(NTag, { size: 'small', bordered: false }, { default: () => '未开启' }) },
  { title: '展示时间', key: 'effective_from', width: 210, render: row => h('div', { class: 'date-cell' }, [h('span', `发布：${formatDate(row.published_at)}`), h('span', `生效：${formatDate(row.effective_from)}`), h('span', `结束：${formatDate(row.effective_until)}`)]) },
  { title: '更新时间', key: 'updated_at', width: 170, render: row => formatDate(row.updated_at) },
  { title: '操作', key: 'actions', width: 280, fixed: 'right', render: row => h('div', { class: 'table-actions' }, [
    h(NButton, { size: 'small', tertiary: true, type: 'primary', onClick: () => void openEdit(row) }, { default: () => '编辑' }),
    row.status === 'DRAFT' || row.status === 'OFFLINE' ? h(NButton, { size: 'small', tertiary: true, type: 'success', onClick: () => void publish(row) }, { default: () => '发布' }) : null,
    row.status === 'SCHEDULED' || row.status === 'PUBLISHED' ? h(NButton, { size: 'small', tertiary: true, type: 'warning', onClick: () => openOffline(row) }, { default: () => '下线' }) : null,
    row.status !== 'ARCHIVED' ? h(NButton, { size: 'small', tertiary: true, onClick: () => void archive(row) }, { default: () => '归档' }) : null,
    h(NButton, { size: 'small', tertiary: true, onClick: () => void copyAnnouncement(row) }, { default: () => '复制' }),
    h(NButton, { size: 'small', tertiary: true, onClick: () => void openAudit(row) }, { default: () => '审计' }),
    h(NButton, { size: 'small', tertiary: true, type: 'error', disabled: deleting.value, onClick: () => confirmDelete(row) }, { default: () => [h(Trash2, { size: 14 }), '删除'] }),
  ]) },
]

onMounted(() => { void loadAnnouncements() })
</script>

<template>
  <div class="announcement-page">
    <NSpace vertical :size="14">
      <div class="page-heading">
        <div><div class="eyebrow"><Megaphone :size="17" /> 客户网站内容运营</div><h2>公告管理</h2><NText depth="3">设置优先级、有效期和加载完成后的主动展示规则，并保留完整审计历史。</NText></div>
        <NSpace><NButton :loading="loading" @click="loadAnnouncements(true)"><template #icon><RefreshCw :size="16" /></template>刷新</NButton><NButton type="primary" @click="openCreate"><template #icon><Plus :size="17" /></template>新建公告</NButton></NSpace>
      </div>
      <NGrid cols="1 s:3" :x-gap="12" :y-gap="12" responsive="screen">
        <NGridItem><NCard size="small" :bordered="false" class="stat-card"><NStatistic label="本页公告" :value="announcements.length" /></NCard></NGridItem>
        <NGridItem><NCard size="small" :bordered="false" class="stat-card"><NStatistic label="当前已发布" :value="activeCount" /></NCard></NGridItem>
        <NGridItem><NCard size="small" :bordered="false" class="stat-card"><NStatistic label="主动展示开启" :value="autoShowCount" /></NCard></NGridItem>
      </NGrid>
      <NCard size="small" :bordered="false">
        <NGrid cols="1 s:2 m:5" :x-gap="12" :y-gap="10" responsive="screen">
          <NFormItemGi label="关键词"><NInput v-model:value="filters.keyword" clearable placeholder="标题或摘要" @keyup.enter="search" /></NFormItemGi>
          <NFormItemGi label="状态"><NSelect v-model:value="filters.status" clearable :options="ANNOUNCEMENT_STATUS_OPTIONS" /></NFormItemGi>
          <NFormItemGi label="类型"><NSelect v-model:value="filters.type" clearable :options="ANNOUNCEMENT_TYPE_OPTIONS" /></NFormItemGi>
          <NFormItemGi label="主动展示"><NSelect v-model:value="filters.autoShowEnabled" clearable :options="[{ label: '已开启', value: 'true' }, { label: '未开启', value: 'false' }]" /></NFormItemGi>
          <NFormItemGi label="筛选"><NSpace><NButton type="primary" @click="search">查询</NButton><NButton @click="resetFilters">重置</NButton></NSpace></NFormItemGi>
        </NGrid>
      </NCard>
      <NCard size="small" :bordered="false" content-style="padding: 0">
        <template #header>
          <div class="table-header">
            <NSpace align="center">
              <span>公告列表</span>
              <NText v-if="checkedRowKeys.length" depth="3">已选 {{ checkedRowKeys.length }} 项</NText>
            </NSpace>
            <NButton
              size="small"
              type="error"
              ghost
              :disabled="selectedAnnouncementIds.length === 0 || deleting"
              :loading="deleting"
              @click="confirmBatchDelete"
            >
              <template #icon><Trash2 :size="15" /></template>
              批量删除
            </NButton>
          </div>
        </template>
        <NDataTable
          :columns="columns"
          :data="announcements"
          :loading="loading || deleting"
          :row-key="row => row.id"
          :scroll-x="1400"
          :single-line="false"
          :checked-row-keys="checkedRowKeys"
          @update:checked-row-keys="keys => checkedRowKeys = keys"
        />
        <div class="table-footer"><NText depth="3">共 {{ pagination.total }} 条公告</NText><NPagination v-model:page="pagination.page" :page-count="pagination.pageCount" :page-size="pagination.pageSize" show-size-picker :page-sizes="[10, 25, 50, 100]" @update:page="changePage" @update:page-size="changePageSize" /></div>
      </NCard>
    </NSpace>

    <NDrawer
      v-model:show="editorOpen"
      width="min(920px, 100vw)"
      placement="right"
      :mask-closable="!saving"
      :close-on-esc="!saving"
    >
      <NDrawerContent :title="editorTitle" :closable="!saving" :native-scrollbar="false">
        <NAlert v-if="editing?.status === 'PUBLISHED' || editing?.status === 'SCHEDULED'" type="warning" :show-icon="true" style="margin-bottom: 16px">修改已发布或已排期公告会立即影响客户可见内容、优先级和主动展示策略。</NAlert>
        <NForm ref="formRef" :model="form" :rules="formRules" label-placement="top">
          <NFormItem label="公告标题" path="title" required>
            <NInput
              v-model:value="form.title"
              maxlength="120"
              show-count
              clearable
              autofocus
              placeholder="例如：配送服务调整通知"
            />
          </NFormItem>
          <NFormItem label="公告类型" path="type"><NSelect v-model:value="form.type" :options="ANNOUNCEMENT_TYPE_OPTIONS" /></NFormItem>
          <NDivider>发布时间与有效期</NDivider>
          <NAlert type="info" :bordered="false" class="time-alert">发布时间用于客户侧显示；生效时间和结束时间决定客户是否能看到公告。编辑已发布、已排期或已过期公告后，状态会按新有效期立即重新计算。</NAlert>
          <NGrid cols="1 m:3" :x-gap="14" responsive="screen">
            <NFormItemGi :label="`发布时间（${announcementTimeZone}，可选）`" path="publishedAt">
              <div class="datetime-field">
                <NDatePicker
                  v-model:formatted-value="form.publishedAt"
                  type="datetime"
                  format="yyyy-MM-dd HH:mm:ss"
                  value-format="yyyy-MM-dd'T'HH:mm:ss"
                  time-picker-format="HH:mm:ss"
                  :actions="['now', 'clear', 'confirm']"
                  clearable
                  placeholder="选择发布时间"
                  style="width: 100%"
                />
                <NText depth="3" class="field-hint">用户端显示的发布时间；可留空，首次发布时由后端自动记录。提交格式为 YYYY-MM-DDTHH:mm:ss。</NText>
              </div>
            </NFormItemGi>
            <NFormItemGi :label="`生效时间（${announcementTimeZone}）`" path="effectiveFrom" required>
              <div class="datetime-field">
                <NDatePicker
                  v-model:formatted-value="form.effectiveFrom"
                  type="datetime"
                  format="yyyy-MM-dd HH:mm:ss"
                  value-format="yyyy-MM-dd'T'HH:mm:ss"
                  time-picker-format="HH:mm:ss"
                  :actions="['now', 'confirm']"
                  placeholder="选择生效时间"
                  style="width: 100%"
                />
                <NText depth="3" class="field-hint">必填，提交格式为 YYYY-MM-DDTHH:mm:ss；公告从此时间点开始生效。</NText>
              </div>
            </NFormItemGi>
            <NFormItemGi :label="`结束时间（${announcementTimeZone}，可选）`" path="effectiveUntil">
              <div class="datetime-field">
                <NDatePicker
                  v-model:formatted-value="form.effectiveUntil"
                  type="datetime"
                  format="yyyy-MM-dd HH:mm:ss"
                  value-format="yyyy-MM-dd'T'HH:mm:ss"
                  time-picker-format="HH:mm:ss"
                  :actions="['now', 'clear', 'confirm']"
                  clearable
                  placeholder="选择结束时间"
                  style="width: 100%"
                />
                <NText depth="3" class="field-hint">可留空；填写时提交格式为 YYYY-MM-DDTHH:mm:ss，且必须晚于生效时间。</NText>
              </div>
            </NFormItemGi>
          </NGrid>
          <NFormItem label="公告摘要" path="summary"><NInput v-model:value="form.summary" type="textarea" :autosize="{ minRows: 2, maxRows: 3 }" maxlength="255" show-count /></NFormItem>
          <NFormItem label="公告正文（纯文本安全渲染）" path="content"><NInput v-model:value="form.content" type="textarea" :autosize="{ minRows: 7, maxRows: 14 }" maxlength="20000" show-count /></NFormItem>
          <NGrid cols="1 m:2" :x-gap="14" responsive="screen"><NFormItemGi label="优先级" path="priority"><NInputNumber v-model:value="form.priority" :min="0" :max="100" style="width: 100%" /></NFormItemGi><NFormItemGi label="跳转链接"><NInput v-model:value="form.actionUrl" placeholder="/collections/new 或 https://…" maxlength="512" /></NFormItemGi></NGrid>
          <NDivider>历史与主动展示</NDivider>
          <NGrid cols="1 m:2" :x-gap="18" responsive="screen"><NFormItemGi label="公开历史公告"><NSwitch v-model:value="form.publicHistory"><template #checked>公开</template><template #unchecked>不公开</template></NSwitch></NFormItemGi><NFormItemGi label="网站加载完成后主动展示"><NSwitch v-model:value="form.autoShowEnabled"><template #checked>开启</template><template #unchecked>关闭</template></NSwitch></NFormItemGi></NGrid>
          <NGrid v-if="form.autoShowEnabled" cols="1 m:2" :x-gap="14" responsive="screen"><NFormItemGi label="主动展示模式"><NSelect v-model:value="form.autoShowMode" :options="ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS" /></NFormItemGi><NFormItemGi v-if="cooldownRequired" label="冷却时间（小时）"><NInputNumber v-model:value="form.autoShowCooldownHours" :min="1" :max="720" style="width: 100%" /></NFormItemGi></NGrid>
          <NAlert v-if="form.autoShowEnabled && form.autoShowMode === 'EVERY_LOAD'" type="warning" :show-icon="true">每次加载都会由客户端请求候选，适合紧急公告；请谨慎使用。</NAlert>
        </NForm>
        <template #footer><NSpace justify="end"><NButton :disabled="saving" @click="editorOpen = false">取消</NButton><NButton type="primary" :loading="saving" @click="save">保存</NButton></NSpace></template>
      </NDrawerContent>
    </NDrawer>
    <NModal v-model:show="offlineOpen" preset="dialog" title="下线公告" positive-text="确认下线" negative-text="取消" :positive-button-props="{ loading: saving }" @positive-click="offline"><NText>下线后公告会立即从当前公告和主动展示候选中移除。</NText><NInput v-model:value="offlineReason" type="textarea" maxlength="255" show-count placeholder="请填写下线原因" style="margin-top: 14px" /></NModal>
    <AnnouncementAuditModal v-model:show="auditOpen" :announcement="auditTarget" :items="auditItems" :loading="auditLoading" />
  </div>
</template>

<style scoped>
.announcement-page { max-width: 1640px; margin: 0 auto; }
.page-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-heading h2 { margin: 3px 0 5px; font-size: 25px; }
.eyebrow { display: flex; align-items: center; gap: 7px; color: #d97706; font-size: 12px; font-weight: 700; letter-spacing: .04em; }
.stat-card { background: linear-gradient(130deg, #fff9ed, #fff); }
.title-cell, .priority-cell, .auto-show-cell, .date-cell { display: grid; gap: 4px; }
.title-cell span, .auto-show-cell span, .date-cell { color: rgba(0, 0, 0, .58); font-size: 12px; }
.priority-cell { grid-template-columns: minmax(0, 1fr) auto; align-items: center; }
.table-actions { display: flex; flex-wrap: wrap; gap: 5px; }
.table-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.table-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 16px; border-top: 1px solid rgba(0, 0, 0, .07); }
.time-alert { margin-bottom: 14px; }
.datetime-field { width: 100%; }
.field-hint { display: block; margin-top: 6px; font-size: 12px; line-height: 1.5; }
@media (max-width: 700px) { .page-heading, .table-footer { align-items: stretch; flex-direction: column; } }
</style>
