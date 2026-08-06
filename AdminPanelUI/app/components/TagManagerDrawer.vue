<script setup lang="ts">
import { TagPlus } from '@lucide/vue'
import { h, reactive, ref, watch } from 'vue'
import type { DataTableColumns, FormInst, FormRules, TagProps } from 'naive-ui'
import { NButton, NTag, useMessage } from 'naive-ui'
import type { Tag, TagMutation } from '~/types/product'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'changed', tags: Tag[]): void
}>()

const api = useProductApi()
const { confirmDeleteRequest } = useDeleteConfirmation()
const message = useMessage()
const tags = ref<Tag[]>([])
const loading = ref(false)
const formOpen = ref(false)
const submitting = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInst | null>(null)

interface TagFormModel {
  name: string
  description: string
  color: string | null
  sortOrder: number | null
  active: boolean
}

const form = reactive<TagFormModel>({
  name: '',
  description: '',
  color: null,
  sortOrder: 0,
  active: true,
})

const rules: FormRules = {
  name: [
    { required: true, message: '请输入标签名称', trigger: ['blur', 'input'] },
    { max: 64, message: '名称不超过 64 个字符', trigger: ['blur', 'input'] },
  ],
  description: [{ max: 255, message: '说明不超过 255 个字符', trigger: ['blur', 'input'] }],
  color: [{
    validator: (_rule: unknown, value: string | null) => !value || /^#[0-9a-f]{6}$/i.test(value)
      ? true
      : new Error('颜色必须使用 #RRGGBB 格式'),
    trigger: ['blur', 'change'],
  }],
  sortOrder: [{
    required: true,
    validator: (_rule: unknown, value: number | null) => value !== null && Number.isInteger(value) && value >= 0
      ? true
      : new Error('排序值必须是非负整数'),
    trigger: ['blur', 'change'],
  }],
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return '未知错误'
}

async function loadTags() {
  loading.value = true
  try {
    tags.value = await api.listTags()
    emit('changed', [...tags.value])
  } catch (error) {
    message.error(`加载标签失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', description: '', color: null, sortOrder: 0, active: true })
  formRef.value?.restoreValidation()
  formOpen.value = true
}

function openEdit(tag: Tag) {
  editingId.value = tag.id
  Object.assign(form, {
    name: tag.name,
    description: tag.description ?? '',
    color: tag.color ?? null,
    sortOrder: tag.sortOrder,
    active: tag.active,
  })
  formRef.value?.restoreValidation()
  formOpen.value = true
}

async function submitTag() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const payload: TagMutation = {
    name: form.name.trim(),
    description: form.description.trim() || undefined,
    color: form.color || undefined,
    sortOrder: form.sortOrder ?? 0,
    active: form.active,
  }
  submitting.value = true
  try {
    if (editingId.value === null) {
      await api.createTag(payload)
      message.success('标签已创建')
    } else {
      await api.updateTag(editingId.value, payload)
      message.success('标签已更新')
    }
    formOpen.value = false
    await loadTags()
  } catch (error) {
    message.error(`保存标签失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

function confirmDelete(tag: Tag) {
  confirmDeleteRequest({
    title: '删除标签',
    content: `确认删除“${tag.name}”？`,
    positiveText: '删除',
    onConfirm: async () => {
      try {
        await api.deleteTag(tag.id)
        message.success('标签已删除')
        await loadTags()
      } catch (error) {
        message.error(`删除标签失败：${errorMessage(error)}`)
      }
    },
  })
}

const columns: DataTableColumns<Tag> = [
  {
    title: '标签',
    key: 'name',
    minWidth: 150,
    render: row => h('div', { class: 'tag-name-cell' }, [
      h('span', {
        class: 'tag-color-swatch',
        style: { backgroundColor: row.color ?? '#d9d9df' },
      }),
      h('span', row.name),
    ]),
  },
  {
    title: '说明',
    key: 'description',
    minWidth: 180,
    ellipsis: { tooltip: true },
    render: row => row.description || '-',
  },
  { title: '排序', key: 'sortOrder', width: 72 },
  {
    title: '状态',
    key: 'active',
    width: 90,
    render: row => h(
      NTag,
      { size: 'small', bordered: false, type: (row.active ? 'success' : 'default') as TagProps['type'] },
      { default: () => row.active ? '启用' : '停用' },
    ),
  },
  {
    title: '操作',
    key: 'actions',
    width: 136,
    fixed: 'right',
    render: row => h('div', { class: 'tag-actions' }, [
      h(NButton, { size: 'small', tertiary: true, onClick: () => openEdit(row) }, { default: () => '编辑' }),
      h(
        NButton,
        { size: 'small', tertiary: true, type: 'error', onClick: () => confirmDelete(row) },
        { default: () => '删除' },
      ),
    ]),
  },
]

watch(
  () => props.open,
  open => {
    if (open) void loadTags()
  },
)
</script>

<template>
  <NDrawer
    :show="open"
    width="min(680px, 100vw)"
    placement="right"
    @update:show="value => emit('update:open', value)"
  >
    <NDrawerContent title="标签管理" closable :native-scrollbar="false">
      <div class="tag-toolbar">
        <NButton type="primary" size="small" @click="openCreate">
          <template #icon><TagPlus :size="16" /></template>
          新增标签
        </NButton>
      </div>
      <NDataTable
        :columns="columns"
        :data="tags"
        :loading="loading"
        :pagination="false"
        :row-key="row => row.id"
        :scroll-x="630"
        size="small"
      />
      <NEmpty v-if="!loading && tags.length === 0" description="暂无标签" class="tag-empty" />
    </NDrawerContent>
  </NDrawer>

  <NModal
    v-model:show="formOpen"
    preset="card"
    :title="editingId === null ? '新增标签' : '编辑标签'"
    :style="{ width: 'min(520px, calc(100vw - 32px))' }"
    :mask-closable="!submitting"
    :closable="!submitting"
  >
    <NForm ref="formRef" :model="form" :rules="rules" label-placement="top">
      <NFormItem label="名称" path="name">
        <NInput v-model:value="form.name" maxlength="64" show-count placeholder="标签名称" />
      </NFormItem>
      <NFormItem label="说明" path="description">
        <NInput
          v-model:value="form.description"
          type="textarea"
          maxlength="255"
          show-count
          :autosize="{ minRows: 2, maxRows: 5 }"
        />
      </NFormItem>
      <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
        <NFormItemGi label="显示色" path="color">
          <NColorPicker
            v-model:value="form.color"
            :show-alpha="false"
            :modes="['hex']"
            :swatches="['#18A058', '#2080F0', '#F0A020', '#D03050', '#8A2BE2', '#666666']"
            :actions="['confirm', 'clear']"
          />
        </NFormItemGi>
        <NFormItemGi label="排序值" path="sortOrder">
          <NInputNumber v-model:value="form.sortOrder" :min="0" :precision="0" style="width: 100%" />
        </NFormItemGi>
      </NGrid>
      <NFormItem label="启用">
        <NSwitch v-model:value="form.active" />
      </NFormItem>
    </NForm>

    <template #footer>
      <NSpace justify="end">
        <NButton :disabled="submitting" @click="formOpen = false">取消</NButton>
        <NButton type="primary" :loading="submitting" @click="submitTag">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped>
.tag-empty {
  margin-top: 48px;
}

.tag-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

:deep(.tag-name-cell),
:deep(.tag-actions) {
  display: flex;
  align-items: center;
  gap: 8px;
}

:deep(.tag-color-swatch) {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 3px;
}
</style>
