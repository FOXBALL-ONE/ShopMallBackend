<script setup lang="ts">
import { computed } from 'vue'
import type { TagProps } from 'naive-ui'
import {
  ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS,
  ANNOUNCEMENT_STATUS_OPTIONS,
  ANNOUNCEMENT_TYPE_OPTIONS,
} from '~/composables/useAnnouncementApi'
import type {
  AdminAnnouncementListItem,
  AnnouncementAuditLog,
  AnnouncementAutoShowMode,
  AnnouncementStatus,
  AnnouncementType,
} from '~/types/announcement'

const props = defineProps<{
  show: boolean
  announcement: AdminAnnouncementListItem | null
  items: AnnouncementAuditLog[]
  loading: boolean
}>()

const emit = defineEmits<{
  'update:show': [value: boolean]
}>()

type AuditSnapshot = Record<string, unknown>
type AuditSnapshotField = { key: string; label: string }

const AUDIT_ACTION_LABELS: Record<AnnouncementAuditLog['action'], string> = {
  CREATED: '创建公告',
  UPDATED: '更新公告',
  PUBLISHED: '发布公告',
  SYSTEM_PUBLISHED: '系统自动发布',
  SYSTEM_EXPIRED: '系统自动过期',
  OFFLINE: '下线公告',
  ARCHIVED: '归档公告',
  COPIED: '复制公告',
}
const AUDIT_SNAPSHOT_FIELDS: AuditSnapshotField[] = [
  { key: 'title', label: '标题' },
  { key: 'summary', label: '摘要' },
  { key: 'content_length', label: '正文长度' },
  { key: 'type', label: '公告类型' },
  { key: 'priority', label: '优先级' },
  { key: 'status', label: '状态' },
  { key: 'public_history', label: '公开历史公告' },
  { key: 'auto_show_enabled', label: '主动展示' },
  { key: 'auto_show_mode', label: '主动展示模式' },
  { key: 'auto_show_cooldown_hours', label: '展示冷却时间' },
  { key: 'action_url', label: '跳转链接' },
  { key: 'effective_from', label: '生效时间' },
  { key: 'effective_until', label: '结束时间' },
  { key: 'published_at', label: '发布时间' },
]

const visible = computed({
  get: () => props.show,
  set: value => emit('update:show', value),
})

function statusLabel(status: AnnouncementStatus) {
  return ANNOUNCEMENT_STATUS_OPTIONS.find(item => item.value === status)?.label ?? status
}

function typeLabel(type: AnnouncementType) {
  return ANNOUNCEMENT_TYPE_OPTIONS.find(item => item.value === type)?.label ?? type
}

function autoShowModeLabel(mode: AnnouncementAutoShowMode) {
  return ANNOUNCEMENT_AUTO_SHOW_MODE_OPTIONS.find(item => item.value === mode)?.label ?? mode
}

function formatDate(value: string | null | undefined) {
  if (!value) return '-'
  return value.replace('T', ' ').slice(0, 19)
}

function operatorLabel(operatorId: number) {
  return operatorId === 0 ? '系统' : `管理员 #${operatorId}`
}

function auditActionLabel(action: AnnouncementAuditLog['action']) {
  return AUDIT_ACTION_LABELS[action] ?? action
}

function auditActionTagType(action: AnnouncementAuditLog['action']): TagProps['type'] {
  if (action === 'PUBLISHED') return 'success'
  if (action === 'SYSTEM_PUBLISHED') return 'info'
  if (action === 'SYSTEM_EXPIRED') return 'error'
  if (action === 'OFFLINE' || action === 'UPDATED') return 'warning'
  if (action === 'ARCHIVED') return 'default'
  return 'info'
}

function parseAuditSnapshot(snapshot: string | null | undefined): AuditSnapshot | null {
  if (!snapshot) return null
  try {
    const parsed: unknown = JSON.parse(snapshot)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as AuditSnapshot : null
  } catch {
    return null
  }
}

function auditValueLabel(key: string, value: unknown, present = true) {
  if (!present) return '未记录'
  if (value === null || value === '') return '未设置'
  if (key === 'content_length') return `${value} 个字符`
  if (key === 'type') return typeLabel(String(value) as AnnouncementType)
  if (key === 'status') return statusLabel(String(value) as AnnouncementStatus)
  if (key === 'auto_show_mode') return autoShowModeLabel(String(value) as AnnouncementAutoShowMode)
  if (key === 'public_history' || key === 'auto_show_enabled') return value === true ? '是' : '否'
  if (key === 'auto_show_cooldown_hours') return `${value} 小时`
  if (key === 'effective_from' || key === 'effective_until' || key === 'published_at') return formatDate(String(value))
  return String(value)
}

const auditDetails = computed(() => props.items.map(item => {
  const before = parseAuditSnapshot(item.before_snapshot)
  const after = parseAuditSnapshot(item.after_snapshot)
  const afterFields = AUDIT_SNAPSHOT_FIELDS
    .filter(field => Boolean(after && Object.prototype.hasOwnProperty.call(after, field.key)))
    .map(field => ({ ...field, value: after?.[field.key] }))
  const changes = AUDIT_SNAPSHOT_FIELDS
    .filter(field => {
      const beforePresent = Boolean(before && Object.prototype.hasOwnProperty.call(before, field.key))
      const afterPresent = Boolean(after && Object.prototype.hasOwnProperty.call(after, field.key))
      if (!afterPresent) return false
      if (!before) return true
      return beforePresent && JSON.stringify(before[field.key]) !== JSON.stringify(after?.[field.key])
    })
    .map(field => ({
      ...field,
      beforePresent: Boolean(before && Object.prototype.hasOwnProperty.call(before, field.key)),
      beforeValue: before?.[field.key],
      afterValue: after?.[field.key],
    }))
  return { item, before, after, afterFields, changes }
}))
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    :title="announcement ? `公告 #${announcement.id} 的审计记录` : '审计记录'"
    class="audit-modal"
  >
    <NSpin :show="loading">
      <NEmpty v-if="!loading && auditDetails.length === 0" description="暂无审计记录" />
      <div v-else class="audit-list">
        <NCard v-for="detail in auditDetails" :key="detail.item.id" size="small" class="audit-card">
          <div class="audit-heading">
            <div class="audit-title">
              <NTag :type="auditActionTagType(detail.item.action)" :bordered="false">
                {{ auditActionLabel(detail.item.action) }}
              </NTag>
              <strong>{{ operatorLabel(detail.item.operator_id) }}</strong>
            </div>
            <NText depth="3">{{ formatDate(detail.item.created_at) }}</NText>
          </div>

          <NAlert v-if="detail.item.reason" type="info" :show-icon="false" class="audit-reason">
            <strong>操作说明：</strong>{{ detail.item.reason }}
          </NAlert>

          <template v-if="detail.after">
            <div class="audit-section-title">{{ detail.before ? '字段变更' : '操作后的公告配置' }}</div>
            <NEmpty
              v-if="detail.changes.length === 0"
              description="该操作未改变快照中记录的公告字段"
              size="small"
            />
            <div v-else class="audit-change-list">
              <div
                v-for="change in detail.changes"
                :key="change.key"
                class="audit-change-row"
                :class="{ 'audit-change-row-current': !detail.before }"
              >
                <div class="audit-field-label">{{ change.label }}</div>
                <template v-if="detail.before">
                  <div class="audit-value audit-value-before">
                    <span class="audit-value-caption">变更前</span>
                    {{ auditValueLabel(change.key, change.beforeValue, change.beforePresent) }}
                  </div>
                  <div class="audit-arrow">→</div>
                </template>
                <div class="audit-value audit-value-after">
                  <span v-if="detail.before" class="audit-value-caption">变更后</span>
                  {{ auditValueLabel(change.key, change.afterValue) }}
                </div>
              </div>
            </div>
          </template>
          <NAlert v-else type="warning" :show-icon="true">
            快照数据无法解析，请在下方查看原始数据。
          </NAlert>

          <NCollapse class="audit-detail-collapse">
            <NCollapseItem v-if="detail.before && detail.after" title="查看操作后的完整配置" name="after-detail">
              <div class="audit-current-list">
                <div v-for="field in detail.afterFields" :key="field.key" class="audit-current-row">
                  <div class="audit-field-label">{{ field.label }}</div>
                  <div class="audit-value audit-value-after">{{ auditValueLabel(field.key, field.value) }}</div>
                </div>
              </div>
            </NCollapseItem>
            <NCollapseItem title="查看原始快照数据" name="raw-data">
              <div v-if="detail.item.before_snapshot" class="audit-raw-block">
                <NText depth="3">操作前</NText>
                <NCode :code="detail.item.before_snapshot" language="json" word-wrap />
              </div>
              <div class="audit-raw-block">
                <NText depth="3">操作后</NText>
                <NCode :code="detail.item.after_snapshot" language="json" word-wrap />
              </div>
            </NCollapseItem>
          </NCollapse>
        </NCard>
      </div>
    </NSpin>
  </NModal>
</template>

<style scoped>
.audit-modal { width: min(960px, calc(100vw - 30px)); max-height: calc(100vh - 44px); overflow: auto; }
.audit-list { display: grid; gap: 14px; }
.audit-card { border-color: rgba(0, 0, 0, .09); }
.audit-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.audit-title { display: flex; align-items: center; flex-wrap: wrap; gap: 9px; }
.audit-reason { margin-top: 12px; }
.audit-section-title { margin: 16px 0 8px; color: rgba(0, 0, 0, .72); font-size: 13px; font-weight: 700; }
.audit-change-list, .audit-current-list { overflow: hidden; border: 1px solid rgba(0, 0, 0, .08); border-radius: 8px; }
.audit-change-row { display: grid; grid-template-columns: 132px minmax(0, 1fr) 24px minmax(0, 1fr); align-items: stretch; }
.audit-change-row-current, .audit-current-row { display: grid; grid-template-columns: 132px minmax(0, 1fr); }
.audit-change-row + .audit-change-row, .audit-current-row + .audit-current-row { border-top: 1px solid rgba(0, 0, 0, .07); }
.audit-field-label, .audit-value, .audit-arrow { padding: 9px 11px; line-height: 1.55; overflow-wrap: anywhere; }
.audit-field-label { display: flex; align-items: center; background: rgba(0, 0, 0, .025); font-weight: 600; }
.audit-value-before { color: rgba(0, 0, 0, .55); background: rgba(239, 68, 68, .035); }
.audit-value-after { color: rgba(0, 0, 0, .82); background: rgba(34, 197, 94, .045); }
.audit-value-caption { display: block; margin-bottom: 2px; color: rgba(0, 0, 0, .42); font-size: 11px; }
.audit-arrow { display: flex; align-items: center; justify-content: center; padding-right: 0; padding-left: 0; color: rgba(0, 0, 0, .38); }
.audit-detail-collapse { margin-top: 10px; }
.audit-raw-block { display: grid; gap: 6px; }
.audit-raw-block + .audit-raw-block { margin-top: 12px; }

@media (max-width: 700px) {
  .audit-heading { align-items: stretch; flex-direction: column; }
  .audit-change-row, .audit-change-row-current, .audit-current-row { grid-template-columns: 108px minmax(0, 1fr); }
  .audit-change-row .audit-arrow { display: none; }
  .audit-change-row .audit-value-before { grid-column: 2; border-bottom: 1px dashed rgba(0, 0, 0, .08); }
  .audit-change-row .audit-value-after { grid-column: 2; }
}
</style>