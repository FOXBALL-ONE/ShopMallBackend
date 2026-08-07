<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { TagProps, UploadFileInfo } from 'naive-ui'
import { useDialog, useMessage } from 'naive-ui'
import {
  SUPPORT_TICKET_PRIORITY_OPTIONS,
  SUPPORT_TICKET_STATUS_OPTIONS,
} from '~/composables/useSupportTicketApi'
import type {
  SupportServiceType,
  SupportTicketDetail,
  SupportTicketPriority,
  SupportTicketStatus,
  UpdateSupportTicketPayload,
} from '~/types/support-ticket'

const props = defineProps<{
  ticketId: number | null
}>()

const emit = defineEmits<{
  updated: []
}>()

const open = defineModel<boolean>('open', { default: false })
const api = useSupportTicketApi()
const message = useMessage()
const dialog = useDialog()

const detail = ref<SupportTicketDetail | null>(null)
const loading = ref(false)
const saving = ref(false)
const sending = ref(false)
const messagePage = ref(1)
const managementStatus = ref<SupportTicketStatus | null>(null)
const managementPriority = ref<SupportTicketPriority | null>(null)
const replyContent = ref('')
const replyFileList = ref<UploadFileInfo[]>([])
let requestSequence = 0

const messagePageCount = computed(() => Math.max(detail.value?.message_pagination.count ?? 0, 1))
const showInitialMessage = computed(() => messagePage.value === messagePageCount.value)
const canSendMessage = computed(() => detail.value?.status !== 'CLOSED')
const selectableStatusOptions = computed(() => {
  if (detail.value?.status === 'CLOSED') {
    return SUPPORT_TICKET_STATUS_OPTIONS.filter(option => option.value === 'CLOSED')
  }
  return SUPPORT_TICKET_STATUS_OPTIONS
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
  return serviceType === 'PRE_SALES' ? '售前咨询' : '售后支持'
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString('zh-CN', { hour12: false })
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return '未知错误'
}

async function loadDetail(page = messagePage.value) {
  if (!props.ticketId) return

  const sequence = ++requestSequence
  loading.value = true
  try {
    const data = await api.getOne(props.ticketId, page, 50)
    if (sequence !== requestSequence || !open.value) return
    detail.value = data
    messagePage.value = page
    managementStatus.value = data.status
    managementPriority.value = data.priority
  } catch (error) {
    if (sequence === requestSequence) {
      message.error(`加载工单详情失败：${errorMessage(error)}`)
    }
  } finally {
    if (sequence === requestSequence) {
      loading.value = false
    }
  }
}

async function persistManagement(payload: UpdateSupportTicketPayload) {
  if (!props.ticketId || saving.value) return

  saving.value = true
  try {
    await api.update(props.ticketId, payload)
    message.success('工单处理信息已更新')
    emit('updated')
    await loadDetail(messagePage.value)
  } catch (error) {
    message.error(`更新工单失败：${errorMessage(error)}`)
  } finally {
    saving.value = false
  }
}

async function saveManagement() {
  if (!detail.value || !managementStatus.value || !managementPriority.value) return

  const payload: UpdateSupportTicketPayload = {}
  if (managementStatus.value !== detail.value.status) {
    payload.status = managementStatus.value
  }
  if (managementPriority.value !== detail.value.priority) {
    payload.priority = managementPriority.value
  }

  if (Object.keys(payload).length === 0) {
    message.info('工单状态和优先级没有变化')
    return
  }

  if (payload.status === 'CLOSED') {
    dialog.warning({
      title: '确认关闭工单',
      content: '工单关闭后不能重新打开，也不能继续发送消息。确定要关闭吗？',
      positiveText: '确认关闭',
      negativeText: '取消',
      onPositiveClick: () => persistManagement(payload),
    })
    return
  }

  await persistManagement(payload)
}

async function sendReply() {
  if (!detail.value || !props.ticketId || !canSendMessage.value || sending.value) return

  const content = replyContent.value.trim()
  const files = replyFileList.value
    .map(item => item.file)
    .filter((file): file is File => file instanceof File)

  if (!content && files.length === 0) {
    message.warning('请输入回复内容或选择附件')
    return
  }
  if (files.length > 10) {
    message.warning('单条消息最多上传 10 个附件')
    return
  }
  if (files.reduce((total, file) => total + file.size, 0) > 50 * 1024 * 1024) {
    message.warning('单条消息附件总大小不能超过 50 MB')
    return
  }

  sending.value = true
  try {
    await api.sendMessage(props.ticketId, content || null, files)
    replyContent.value = ''
    replyFileList.value = []
    message.success('回复已发送')
    emit('updated')
    await loadDetail(1)
  } catch (error) {
    message.error(`发送回复失败：${errorMessage(error)}`)
  } finally {
    sending.value = false
  }
}

async function changeMessagePage(page: number) {
  messagePage.value = page
  await loadDetail(page)
}

watch([open, () => props.ticketId], ([visible, ticketId]) => {
  if (visible && ticketId) {
    detail.value = null
    messagePage.value = 1
    replyContent.value = ''
    replyFileList.value = []
    void loadDetail(1)
  } else if (!visible) {
    requestSequence++
    detail.value = null
  }
})
</script>

<template>
  <NDrawer v-model:show="open" placement="right" width="min(880px, 96vw)">
    <NDrawerContent :title="detail ? `工单 #${detail.id}` : '工单详情'" closable :native-scrollbar="false">
      <NSpin :show="loading">
        <template v-if="detail">
          <NSpace vertical :size="16">
            <NCard size="small" title="基本信息">
              <template #header-extra>
                <NSpace size="small">
                  <NTag :type="priorityTagType(detail.priority)" size="small">
                    {{ priorityLabel(detail.priority) }}优先级
                  </NTag>
                  <NTag :type="statusTagType(detail.status)" size="small">
                    {{ statusLabel(detail.status) }}
                  </NTag>
                </NSpace>
              </template>

              <NDescriptions :column="2" label-placement="left" bordered size="small">
                <NDescriptionsItem label="主题" :span="2">
                  {{ detail.subject }}
                </NDescriptionsItem>
                <NDescriptionsItem label="客户用户名">
                  {{ detail.customer_username }}
                </NDescriptionsItem>
                <NDescriptionsItem label="服务类型">
                  {{ serviceTypeLabel(detail.service_type) }}
                </NDescriptionsItem>
                <NDescriptionsItem label="订单号">
                  {{ detail.order_no || '-' }}
                </NDescriptionsItem>
                <NDescriptionsItem label="处理人">
                  {{ detail.handled_by_username ?? '-' }}
                </NDescriptionsItem>
                <NDescriptionsItem label="创建时间">
                  {{ formatDate(detail.created_at) }}
                </NDescriptionsItem>
                <NDescriptionsItem label="更新时间">
                  {{ formatDate(detail.updated_at) }}
                </NDescriptionsItem>
                <NDescriptionsItem v-if="detail.replied_at" label="最近回复">
                  {{ formatDate(detail.replied_at) }}
                </NDescriptionsItem>
                <NDescriptionsItem v-if="detail.resolved_at" label="解决时间">
                  {{ formatDate(detail.resolved_at) }}
                </NDescriptionsItem>
                <NDescriptionsItem v-if="detail.closed_at" label="关闭时间">
                  {{ formatDate(detail.closed_at) }}
                </NDescriptionsItem>
              </NDescriptions>
            </NCard>

            <NCard size="small" title="处理设置">
              <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
                <NFormItemGi label="工单状态">
                  <NSelect
                    v-model:value="managementStatus"
                    :options="selectableStatusOptions"
                    :disabled="saving"
                  />
                </NFormItemGi>
                <NFormItemGi label="优先级">
                  <NSelect
                    v-model:value="managementPriority"
                    :options="SUPPORT_TICKET_PRIORITY_OPTIONS"
                    :disabled="saving"
                  />
                </NFormItemGi>
              </NGrid>
              <NSpace justify="end">
                <NButton type="primary" :loading="saving" @click="saveManagement">
                  保存处理信息
                </NButton>
              </NSpace>
            </NCard>

            <NCard size="small" title="沟通记录">
              <template #header-extra>
                <NText depth="3">
                  共 {{ detail.message_pagination.total }} 条后续消息
                </NText>
              </template>

              <NSpace vertical :size="12">
                <div v-if="showInitialMessage" class="message-row customer-message">
                  <div class="message-meta">
                    <span>客户 {{ detail.customer_username }} · 初始问题</span>
                    <span>{{ formatDate(detail.created_at) }}</span>
                  </div>
                  <div class="message-bubble">
                    {{ detail.content }}
                  </div>
                </div>

                <div
                  v-for="item in detail.messages"
                  :key="item.id"
                  class="message-row"
                  :class="item.sender_type === 'ADMIN' ? 'admin-message' : 'customer-message'"
                >
                  <div class="message-meta">
                    <span>
                      {{ item.sender_type === 'ADMIN' ? '管理员' : '客户' }} {{ item.sender_username }}
                    </span>
                    <span>{{ formatDate(item.created_at) }}</span>
                  </div>
                  <div class="message-bubble">
                    <div v-if="item.content" class="message-content">
                      {{ item.content }}
                    </div>
                    <div v-if="item.attachments.length" class="attachment-list">
                      <a
                        v-for="attachment in item.attachments"
                        :key="attachment.id"
                        class="attachment-link"
                        :href="attachment.download_url"
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        <span>{{ attachment.file_name }}</span>
                        <small>{{ formatFileSize(attachment.size_bytes) }}</small>
                      </a>
                    </div>
                  </div>
                </div>

                <NEmpty
                  v-if="!showInitialMessage && detail.messages.length === 0"
                  description="当前消息页没有记录"
                  size="small"
                />

                <NSpace v-if="messagePageCount > 1" justify="center">
                  <NPagination
                    :page="messagePage"
                    :page-count="messagePageCount"
                    :disabled="loading"
                    @update:page="changeMessagePage"
                  />
                </NSpace>
              </NSpace>
            </NCard>

            <NCard size="small" title="回复客户">
              <NAlert v-if="!canSendMessage" type="warning" :bordered="false" style="margin-bottom: 12px">
                已关闭的工单不能继续发送消息。
              </NAlert>
              <NInput
                v-model:value="replyContent"
                type="textarea"
                :rows="5"
                maxlength="5000"
                show-count
                placeholder="输入回复内容；也可以只发送附件"
                :disabled="!canSendMessage || sending"
              />
              <div class="upload-block">
                <NUpload
                  v-model:file-list="replyFileList"
                  multiple
                  :max="10"
                  :default-upload="false"
                  :disabled="!canSendMessage || sending"
                >
                  <NButton :disabled="!canSendMessage || sending">
                    选择附件
                  </NButton>
                </NUpload>
                <NText depth="3" class="upload-tip">
                  最多 10 个附件，单条消息附件总大小不超过 50 MB。
                </NText>
              </div>
              <NSpace justify="end">
                <NButton
                  type="primary"
                  :loading="sending"
                  :disabled="!canSendMessage"
                  @click="sendReply"
                >
                  发送回复
                </NButton>
              </NSpace>
            </NCard>
          </NSpace>
        </template>

        <NEmpty v-else-if="!loading" description="未找到工单详情" />
      </NSpin>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.message-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.message-meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  color: #8c8c8c;
  font-size: 12px;
}

.message-bubble {
  max-width: 82%;
  padding: 10px 12px;
  border-radius: 10px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.customer-message {
  align-items: flex-start;
}

.customer-message .message-bubble {
  background: #f5f5f5;
  border: 1px solid #e5e7eb;
}

.admin-message {
  align-items: flex-end;
}

.admin-message .message-meta {
  flex-direction: row-reverse;
}

.admin-message .message-bubble {
  color: #0b5d35;
  background: #eaf8f0;
  border: 1px solid #b7e4c7;
}

.message-content + .attachment-list {
  margin-top: 10px;
}

.attachment-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.attachment-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-width: 240px;
  padding: 7px 9px;
  color: inherit;
  text-decoration: none;
  background: rgb(255 255 255 / 70%);
  border: 1px solid rgb(0 0 0 / 8%);
  border-radius: 6px;
}

.attachment-link:hover {
  border-color: #18a058;
}

.attachment-link small {
  flex: none;
  color: #8c8c8c;
}

.upload-block {
  margin: 12px 0;
}

.upload-tip {
  display: block;
  margin-top: 6px;
  font-size: 12px;
}

@media (max-width: 640px) {
  .message-bubble {
    max-width: 94%;
  }

  .message-meta {
    flex-direction: column;
    gap: 2px;
  }

  .admin-message .message-meta {
    flex-direction: column;
    align-items: flex-end;
  }

  .attachment-link {
    min-width: 0;
  }
}
</style>
