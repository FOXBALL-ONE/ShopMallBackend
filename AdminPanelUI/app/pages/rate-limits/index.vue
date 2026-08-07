<script setup lang="ts">
import { Plus, RefreshCw, Save, Trash2 } from '@lucide/vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'
import { useMessage } from 'naive-ui'
import type { RateLimitSettings } from '~/types/rate-limit'

definePageMeta({ layout: 'default' })

const api = useRateLimitApi()
const message = useMessage()
const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const saving = ref(false)
const switching = ref(false)
const settings = ref<RateLimitSettings | null>(null)

const form = reactive({
  enabled: true,
  authenticatedRequestsPerMinute: 10,
  anonymousRequestsPerMinute: 5,
  excludedPaths: [] as string[],
})

const formRules: FormRules = {
  authenticatedRequestsPerMinute: [
    { required: true, type: 'number', min: 1, max: 1000, message: '请输入 1 到 1000 的整数', trigger: ['blur', 'change'] },
  ],
  anonymousRequestsPerMinute: [
    { required: true, type: 'number', min: 1, max: 1000, message: '请输入 1 到 1000 的整数', trigger: ['blur', 'change'] },
  ],
}

const sourceLabel = computed(() => settings.value?.source === 'REDIS' ? 'Redis 运行时设置' : '部署默认设置')
const updateLabel = computed(() => {
  if (!settings.value?.updated_at) return '尚未保存运行时设置'
  return `${formatDate(settings.value.updated_at)}${settings.value.updated_by ? `，管理员 #${settings.value.updated_by}` : ''}`
})
const isDirty = computed(() => {
  const current = settings.value
  if (!current) return false
  return form.authenticatedRequestsPerMinute !== current.authenticated_requests_per_minute ||
    form.anonymousRequestsPerMinute !== current.anonymous_requests_per_minute ||
    JSON.stringify(form.excludedPaths) !== JSON.stringify(current.excluded_paths)
})

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusCode?: number; statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '请求失败'
  }
  return '请求失败'
}

function formatDate(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleString('zh-CN', { hour12: false })
}

function applySettings(value: RateLimitSettings) {
  settings.value = value
  form.enabled = value.enabled
  form.authenticatedRequestsPerMinute = value.authenticated_requests_per_minute
  form.anonymousRequestsPerMinute = value.anonymous_requests_per_minute
  form.excludedPaths = [...value.excluded_paths]
  formRef.value?.restoreValidation()
}

async function loadSettings(showSuccess = false) {
  loading.value = true
  try {
    applySettings(await api.getSettings())
    if (showSuccess) message.success('已加载最新限速设置')
  } catch (error) {
    message.error(`加载限速设置失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

function addPath() {
  if (form.excludedPaths.length >= 20) {
    message.warning('最多添加 20 条免限速路径')
    return
  }
  form.excludedPaths.push('')
}

function removePath(index: number) {
  form.excludedPaths.splice(index, 1)
}

async function changeEnabled(enabled: boolean) {
  const current = settings.value
  if (!current || switching.value) return
  const previousEnabled = current.enabled
  form.enabled = enabled
  if (enabled === previousEnabled) return

  switching.value = true
  try {
    const saved = await api.updateEnabled(enabled, current.version)
    applySettings(saved)
    message.success(enabled ? '全局限速已启用' : '全局限速已关闭')
  } catch (error) {
    form.enabled = previousEnabled
    const status = (error as { statusCode?: number })?.statusCode
    if (status === 409) {
      await loadSettings()
      message.warning('设置已被其他管理员修改，已重新加载最新值')
    } else {
      message.error(`更新限速开关失败：${errorMessage(error)}`)
    }
  } finally {
    switching.value = false
  }
}

function validatePaths(): string[] | null {
  if (form.excludedPaths.length > 20) {
    message.error('最多添加 20 条免限速路径')
    return null
  }
  const normalized = form.excludedPaths.map(path => path.trim())
  if (normalized.some(path => !path)) {
    message.error('免限速路径不能为空')
    return null
  }
  if (normalized.some(path => path.length > 160)) {
    message.error('单条免限速路径不能超过 160 个字符')
    return null
  }
  if (normalized.some(path => !/^\/api\/(?:[A-Za-z0-9._~-]+\/)*[A-Za-z0-9._~-]+(?:\/\*\*)?$/.test(path))) {
    message.error('路径仅支持 /api/ 下的精确路径或末尾 /** 子树路径')
    return null
  }
  if (normalized.some(path => path.includes('..') || path.split('/').some(segment => segment === '.'))) {
    message.error('免限速路径不能包含 . 或 .. 路径段')
    return null
  }
  if (normalized.some(path => path === '/api/**' || path === '/api/auth' || path.startsWith('/api/auth/') || path === '/api/logistics/webhook' || path.startsWith('/api/logistics/webhook/'))) {
    message.error('认证和外部回调路径不能设置为免限速')
    return null
  }
  if (new Set(normalized).size !== normalized.length) {
    message.error('免限速路径不能重复')
    return null
  }
  if (normalized.reduce((total, path) => total + path.length, 0) > 2000) {
    message.error('免限速路径总长度不能超过 2000 个字符')
    return null
  }
  return normalized.sort()
}

async function saveSettings() {
  const current = settings.value
  if (!current) return
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const excludedPaths = validatePaths()
  if (!excludedPaths) return

  saving.value = true
  try {
    const saved = await api.updateSettings({
      enabled: current.enabled,
      authenticated_requests_per_minute: form.authenticatedRequestsPerMinute,
      anonymous_requests_per_minute: form.anonymousRequestsPerMinute,
      excluded_paths: excludedPaths,
      expected_version: current.version,
    })
    applySettings(saved)
    message.success('限速设置已保存并即时生效')
  } catch (error) {
    const status = (error as { statusCode?: number })?.statusCode
    if (status === 409) {
      await loadSettings()
      message.warning('设置已被其他管理员修改，已重新加载最新值')
    } else {
      message.error(`保存限速设置失败：${errorMessage(error)}`)
    }
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void loadSettings()
})
</script>

<template>
  <div class="rate-limit-page">
    <div class="page-heading">
      <div>
        <h2>限速设置</h2>
        <NText depth="3">全局 API 滑动窗口与免限速路径</NText>
      </div>
      <NSpace>
        <NTooltip>
          <template #trigger>
            <NButton circle quaternary :loading="loading" aria-label="刷新限速设置" @click="loadSettings(true)">
              <template #icon><RefreshCw :size="17" /></template>
            </NButton>
          </template>
          刷新
        </NTooltip>
        <NButton type="primary" :loading="saving" :disabled="loading || switching || !settings || !isDirty" @click="saveSettings">
          <template #icon><Save :size="17" /></template>
          保存
        </NButton>
      </NSpace>
    </div>

    <NSpin :show="loading && !settings">
      <NAlert v-if="settings?.source === 'DEFAULT'" type="warning" :bordered="false" class="source-alert">
        当前使用部署默认值。首次保存后会写入 Redis 并在所有后端节点立即生效。
      </NAlert>

      <section class="settings-section">
        <div class="section-heading">
          <div>
            <h3>全局限速</h3>
            <NText depth="3">关闭后受管 API 会继续执行鉴权，但不进行本方案的额度统计</NText>
          </div>
          <NSwitch
            :value="form.enabled"
            :loading="switching"
            :disabled="loading || saving || !settings"
            @update:value="changeEnabled"
          >
            <template #checked>已启用</template>
            <template #unchecked>已关闭</template>
          </NSwitch>
        </div>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <div>
            <h3>请求额度</h3>
            <NText depth="3">固定 {{ settings?.window_seconds ?? 60 }} 秒滑动窗口</NText>
          </div>
          <NTag :type="settings?.source === 'REDIS' ? 'success' : 'warning'" size="small">{{ sourceLabel }}</NTag>
        </div>
        <NForm ref="formRef" :model="form" :rules="formRules" label-placement="top" class="quota-form">
          <NFormItem label="已登录用户每分钟请求次数" path="authenticatedRequestsPerMinute">
            <NInputNumber
              v-model:value="form.authenticatedRequestsPerMinute"
              :min="1"
              :max="1000"
              :precision="0"
              :show-button="false"
              :disabled="loading || saving || switching"
              style="width: 100%"
            />
          </NFormItem>
          <NFormItem label="未登录用户每分钟请求次数" path="anonymousRequestsPerMinute">
            <NInputNumber
              v-model:value="form.anonymousRequestsPerMinute"
              :min="1"
              :max="1000"
              :precision="0"
              :show-button="false"
              :disabled="loading || saving || switching"
              style="width: 100%"
            />
          </NFormItem>
        </NForm>
      </section>

      <section class="settings-section">
        <div class="section-heading">
          <div>
            <h3>免限速路径</h3>
            <NText depth="3">仅允许 /api/ 下的精确路径或末尾 /** 子树路径</NText>
          </div>
          <NButton size="small" :disabled="loading || saving || switching || form.excludedPaths.length >= 20" @click="addPath">
            <template #icon><Plus :size="16" /></template>
            添加路径
          </NButton>
        </div>
        <NEmpty v-if="form.excludedPaths.length === 0" size="small" description="暂无动态免限速路径" class="empty-paths" />
        <div v-else class="path-list">
          <div v-for="(_, index) in form.excludedPaths" :key="index" class="path-row">
            <NInput
              v-model:value="form.excludedPaths[index]"
              :disabled="loading || saving || switching"
              placeholder="/api/catalog/**"
              maxlength="160"
              spellcheck="false"
            />
            <NTooltip>
              <template #trigger>
                <NButton circle tertiary type="error" :disabled="loading || saving || switching" :aria-label="`删除第 ${index + 1} 条路径`" @click="removePath(index)">
                  <template #icon><Trash2 :size="16" /></template>
                </NButton>
              </template>
              删除路径
            </NTooltip>
          </div>
        </div>
      </section>

      <section class="metadata-section">
        <NDescriptions :column="3" label-placement="top" size="small" bordered>
          <NDescriptionsItem label="配置来源">{{ sourceLabel }}</NDescriptionsItem>
          <NDescriptionsItem label="版本">{{ settings?.version ?? '-' }}</NDescriptionsItem>
          <NDescriptionsItem label="最后更新">{{ updateLabel }}</NDescriptionsItem>
        </NDescriptions>
      </section>
    </NSpin>
  </div>
</template>

<style scoped>
.rate-limit-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.page-heading,
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-heading h2,
.section-heading h3 {
  margin: 0 0 4px;
}

.page-heading h2 {
  font-size: 22px;
}

.section-heading h3 {
  font-size: 16px;
}

.source-alert {
  margin-bottom: 20px;
}

.settings-section {
  padding: 20px 0;
  border-top: 1px solid var(--n-border-color, #e5e7eb);
}

.quota-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  margin-top: 16px;
}

.quota-form :deep(.n-form-item) {
  margin-bottom: 0;
}

.path-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 16px;
}

.path-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 32px;
  gap: 8px;
  align-items: center;
}

.empty-paths {
  margin: 24px 0 8px;
}

.metadata-section {
  padding-top: 20px;
  border-top: 1px solid var(--n-border-color, #e5e7eb);
}

@media (max-width: 640px) {
  .page-heading,
  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .quota-form {
    grid-template-columns: 1fr;
  }

  .metadata-section :deep(.n-descriptions-table) {
    display: block;
    overflow-x: auto;
  }
}
</style>
