<script setup lang="ts">
import { Pencil, Plus, Trash2 } from '@lucide/vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useMessage } from 'naive-ui'
import type {
  AttributeDefinition,
  AttributeDefinitionMutation,
  AttributeScope,
  AttributeValueType,
  ProductCategory,
  ProductCategoryMutation,
  ProductType,
  ProductTypeMutation,
} from '~/types/product'

definePageMeta({ layout: 'default' })

const api = useProductApi()
const message = useMessage()
const { confirmDeleteRequest } = useDeleteConfirmation()
const activeTab = ref<'types' | 'categories'>('types')
const productTypes = ref<ProductType[]>([])
const categories = ref<ProductCategory[]>([])
const definitions = ref<AttributeDefinition[]>([])
const selectedTypeId = ref<number | null>(null)
const loading = ref(false)
const definitionsLoading = ref(false)

const typeModalOpen = ref(false)
const editingTypeId = ref<number | null>(null)
const typeForm = reactive<ProductTypeMutation>({ code: '', name: '', description: null, active: true, displayOrder: 0 })

const definitionModalOpen = ref(false)
const editingDefinitionId = ref<number | null>(null)
const editingDefinitionUsed = computed(() => definitions.value.find(definition => definition.id === editingDefinitionId.value)?.used === true)
const definitionForm = reactive<AttributeDefinitionMutation>({
  code: '',
  name: '',
  scope: 'PRODUCT',
  valueType: 'STRING',
  required: false,
  filterable: false,
  allowedValues: [],
  maxLength: null,
  displayOrder: 0,
  active: true,
})

const categoryModalOpen = ref(false)
const editingCategoryId = ref<number | null>(null)
const categoryForm = reactive<ProductCategoryMutation>({ code: '', name: '', description: null, parentId: null, displayOrder: 0, status: 'ACTIVE' })
const submitting = ref(false)

const selectedType = computed(() => productTypes.value.find(type => type.id === selectedTypeId.value) ?? null)
const typeOptions = computed(() => productTypes.value.map(type => ({ label: `${type.name} (${type.code})`, value: type.id })))
const parentOptions = computed(() => categories.value
  .filter(category => category.id !== editingCategoryId.value)
  .map(category => ({ label: category.name, value: category.id })))
const scopeOptions: Array<{ label: string; value: AttributeScope }> = [
  { label: '商品属性', value: 'PRODUCT' },
  { label: 'SKU 属性', value: 'VARIANT' },
]
const valueTypeOptions: Array<{ label: string; value: AttributeValueType }> = [
  { label: '文本', value: 'STRING' },
  { label: '布尔值', value: 'BOOLEAN' },
  { label: '整数', value: 'INTEGER' },
  { label: '小数', value: 'DECIMAL' },
  { label: '枚举', value: 'ENUM' },
]

async function loadMetadata() {
  loading.value = true
  try {
    const [types, categoryList] = await Promise.all([api.listProductTypes(), api.listCategories()])
    productTypes.value = types
    categories.value = categoryList
    if (selectedTypeId.value == null || !types.some(type => type.id === selectedTypeId.value)) {
      selectedTypeId.value = types[0]?.id ?? null
    }
  } catch (error) {
    message.error(`加载元数据失败：${errorMessage(error)}`)
  } finally {
    loading.value = false
  }
}

async function loadDefinitions() {
  if (selectedTypeId.value == null) {
    definitions.value = []
    return
  }
  definitionsLoading.value = true
  try {
    definitions.value = await api.getProductTypeDefinitions(selectedTypeId.value)
  } catch (error) {
    definitions.value = []
    message.error(`加载属性定义失败：${errorMessage(error)}`)
  } finally {
    definitionsLoading.value = false
  }
}

watch(selectedTypeId, () => { void loadDefinitions() })

function openCreateType() {
  editingTypeId.value = null
  Object.assign(typeForm, { code: '', name: '', description: null, active: true, displayOrder: productTypes.value.length })
  typeModalOpen.value = true
}

function openEditType(type: ProductType) {
  editingTypeId.value = type.id
  Object.assign(typeForm, { code: type.code, name: type.name, description: type.description ?? null, active: type.active, displayOrder: type.displayOrder })
  typeModalOpen.value = true
}

async function submitType() {
  if (!/^[A-Z][A-Z0-9_]*$/.test(typeForm.code.trim()) || !typeForm.name.trim()) {
    message.warning('类型 code 必须以大写字母开头，且只能包含大写字母、数字和下划线；名称不能为空')
    return
  }
  submitting.value = true
  try {
    const payload = { ...typeForm, code: typeForm.code.trim().toUpperCase(), name: typeForm.name.trim() }
    if (editingTypeId.value == null) await api.createProductType(payload)
    else await api.updateProductType(editingTypeId.value, payload)
    typeModalOpen.value = false
    await loadMetadata()
    message.success('商品类型已保存')
  } catch (error) {
    message.error(`保存商品类型失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

function deleteType(type: ProductType) {
  confirmDeleteRequest({
    tone: 'error',
    title: '删除商品类型',
    content: `确认删除“${type.name}”？`,
    positiveText: '删除',
    onConfirm: async () => {
      try {
        await api.deleteProductType(type.id)
        await loadMetadata()
      } catch (error) {
        message.error(`删除商品类型失败：${errorMessage(error)}`)
      }
    },
  })
}

function openCreateDefinition() {
  if (selectedTypeId.value == null) return
  editingDefinitionId.value = null
  Object.assign(definitionForm, {
    code: '', name: '', scope: 'PRODUCT', valueType: 'STRING', required: false, filterable: false,
    allowedValues: [], maxLength: null, displayOrder: definitions.value.length, active: true,
  })
  definitionModalOpen.value = true
}

function openEditDefinition(definition: AttributeDefinition) {
  editingDefinitionId.value = definition.id
  Object.assign(definitionForm, {
    code: definition.code,
    name: definition.name,
    scope: definition.scope,
    valueType: definition.valueType,
    required: definition.required,
    filterable: definition.filterable,
    allowedValues: [...definition.allowedValues],
    maxLength: definition.maxLength ?? null,
    displayOrder: definition.displayOrder,
    active: definition.active,
  })
  definitionModalOpen.value = true
}

async function submitDefinition() {
  if (selectedTypeId.value == null || !/^[a-z][a-z0-9_]*$/.test(definitionForm.code.trim()) || !definitionForm.name.trim()) {
    message.warning('属性 code 必须以小写字母开头，且只能包含小写字母、数字和下划线；名称不能为空')
    return
  }
  if (definitionForm.valueType === 'ENUM' && definitionForm.allowedValues.length === 0) {
    message.warning('枚举属性至少需要一个允许值')
    return
  }
  submitting.value = true
  try {
    const payload: AttributeDefinitionMutation = {
      ...definitionForm,
      code: definitionForm.code.trim().toLowerCase(),
      name: definitionForm.name.trim(),
      allowedValues: definitionForm.valueType === 'ENUM'
        ? definitionForm.allowedValues.map(value => value.trim().toUpperCase()).filter(Boolean)
        : [],
    }
    if (editingDefinitionId.value == null) await api.createAttributeDefinition(selectedTypeId.value, payload)
    else await api.updateAttributeDefinition(editingDefinitionId.value, payload)
    definitionModalOpen.value = false
    await loadDefinitions()
    message.success('属性定义已保存')
  } catch (error) {
    message.error(`保存属性定义失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

function deleteDefinition(definition: AttributeDefinition) {
  confirmDeleteRequest({
    tone: 'error',
    title: '删除属性定义',
    content: `确认删除“${definition.name}”？`,
    positiveText: '删除',
    onConfirm: async () => {
      try {
        await api.deleteAttributeDefinition(definition.id)
        await loadDefinitions()
      } catch (error) {
        message.error(`删除属性定义失败：${errorMessage(error)}`)
      }
    },
  })
}

function openCreateCategory() {
  editingCategoryId.value = null
  Object.assign(categoryForm, { code: '', name: '', description: null, parentId: null, displayOrder: categories.value.length, status: 'ACTIVE' })
  categoryModalOpen.value = true
}

function openEditCategory(category: ProductCategory) {
  editingCategoryId.value = category.id
  Object.assign(categoryForm, {
    code: category.code,
    name: category.name,
    description: category.description ?? null,
    parentId: category.parentId ?? null,
    displayOrder: category.displayOrder,
    status: category.status,
  })
  categoryModalOpen.value = true
}

async function submitCategory() {
  if (!/^[a-z][a-z0-9-]*$/.test(categoryForm.code.trim()) || !categoryForm.name.trim()) {
    message.warning('分类 code 必须以小写字母开头，且只能包含小写字母、数字和连字符；名称不能为空')
    return
  }
  submitting.value = true
  try {
    const payload = { ...categoryForm, code: categoryForm.code.trim().toLowerCase(), name: categoryForm.name.trim() }
    if (editingCategoryId.value == null) await api.createCategory(payload)
    else await api.updateCategory(editingCategoryId.value, payload)
    categoryModalOpen.value = false
    await loadMetadata()
    message.success('商品分类已保存')
  } catch (error) {
    message.error(`保存商品分类失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}

function deleteCategory(category: ProductCategory) {
  confirmDeleteRequest({
    tone: 'error',
    title: '删除商品分类',
    content: `确认删除“${category.name}”？`,
    positiveText: '删除',
    onConfirm: async () => {
      try {
        await api.deleteCategory(category.id)
        await loadMetadata()
      } catch (error) {
        message.error(`删除商品分类失败：${errorMessage(error)}`)
      }
    },
  })
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return String(error || '未知错误')
}

onMounted(async () => {
  await loadMetadata()
  await loadDefinitions()
})
</script>

<template>
  <div class="metadata-page">
    <header class="page-header">
      <div>
        <h2>商品类型与分类</h2>
        <NText depth="3">{{ productTypes.length }} 个类型 · {{ categories.length }} 个分类</NText>
      </div>
      <NButton @click="navigateTo('/products')">返回商品</NButton>
    </header>

    <NTabs v-model:value="activeTab" type="line">
      <NTabPane name="types" tab="类型与属性">
        <div class="type-layout">
          <section class="type-list">
            <div class="section-toolbar">
              <h3>商品类型</h3>
              <NButton size="small" type="primary" secondary @click="openCreateType">
                <template #icon><Plus :size="15" /></template>
                新增
              </NButton>
            </div>
            <NSpin :show="loading">
              <button
                v-for="type in productTypes"
                :key="type.id"
                class="type-row"
                :class="{ active: selectedTypeId === type.id }"
                type="button"
                @click="selectedTypeId = type.id"
              >
                <span><strong>{{ type.name }}</strong><small>{{ type.code }}</small></span>
                <NTag :type="type.active ? 'success' : 'default'" size="small" :bordered="false">{{ type.active ? '启用' : '停用' }}</NTag>
                <NButton quaternary circle aria-label="编辑类型" @click.stop="openEditType(type)"><Pencil :size="15" /></NButton>
                <NButton quaternary circle type="error" aria-label="删除类型" @click.stop="deleteType(type)"><Trash2 :size="15" /></NButton>
              </button>
              <NEmpty v-if="productTypes.length === 0" description="暂无商品类型" />
            </NSpin>
          </section>

          <section class="definition-list">
            <div class="section-toolbar">
              <h3>{{ selectedType?.name ?? '属性定义' }}</h3>
              <NButton size="small" type="primary" secondary :disabled="!selectedTypeId" @click="openCreateDefinition">
                <template #icon><Plus :size="15" /></template>
                新增属性
              </NButton>
            </div>
            <NSpin :show="definitionsLoading">
              <div class="metadata-table-wrap">
                <table class="metadata-table">
                  <thead><tr><th>属性</th><th>作用域</th><th>类型</th><th>规则</th><th>状态</th><th /></tr></thead>
                  <tbody>
                    <tr v-for="definition in definitions" :key="definition.id">
                      <td><strong>{{ definition.name }}</strong><small>{{ definition.code }}</small></td>
                      <td>{{ definition.scope }}</td>
                      <td>{{ definition.valueType }}</td>
                      <td>{{ definition.required ? '必填' : '可选' }}<span v-if="definition.filterable"> · 可筛选</span></td>
                      <td><NTag :type="definition.active ? 'success' : 'default'" size="small" :bordered="false">{{ definition.active ? '启用' : '停用' }}</NTag></td>
                      <td class="table-actions">
                        <NButton quaternary circle aria-label="编辑属性" @click="openEditDefinition(definition)"><Pencil :size="15" /></NButton>
                        <NButton quaternary circle type="error" aria-label="删除属性" @click="deleteDefinition(definition)"><Trash2 :size="15" /></NButton>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <NEmpty v-if="definitions.length === 0" description="暂无属性定义" />
            </NSpin>
          </section>
        </div>
      </NTabPane>

      <NTabPane name="categories" tab="商品分类">
        <section>
          <div class="section-toolbar">
            <h3>分类目录</h3>
            <NButton size="small" type="primary" secondary @click="openCreateCategory">
              <template #icon><Plus :size="15" /></template>
              新增分类
            </NButton>
          </div>
          <div class="metadata-table-wrap">
            <table class="metadata-table">
              <thead><tr><th>分类</th><th>上级分类</th><th>排序</th><th>状态</th><th /></tr></thead>
              <tbody>
                <tr v-for="category in categories" :key="category.id">
                  <td><strong>{{ category.name }}</strong><small>{{ category.code }}</small></td>
                  <td>{{ categories.find(item => item.id === category.parentId)?.name ?? '-' }}</td>
                  <td>{{ category.displayOrder }}</td>
                  <td><NTag :type="category.status === 'ACTIVE' ? 'success' : 'default'" size="small" :bordered="false">{{ category.status === 'ACTIVE' ? '启用' : '停用' }}</NTag></td>
                  <td class="table-actions">
                    <NButton quaternary circle aria-label="编辑分类" @click="openEditCategory(category)"><Pencil :size="15" /></NButton>
                    <NButton quaternary circle type="error" aria-label="删除分类" @click="deleteCategory(category)"><Trash2 :size="15" /></NButton>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <NEmpty v-if="categories.length === 0" description="暂无商品分类" />
        </section>
      </NTabPane>
    </NTabs>

    <NModal v-model:show="typeModalOpen" preset="card" :title="editingTypeId == null ? '新增商品类型' : '编辑商品类型'" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <NForm label-placement="top">
        <NFormItem label="Code" required>
          <div class="field-with-hint">
            <NInput v-model:value="typeForm.code" :disabled="editingTypeId !== null" maxlength="64" />
            <small class="field-hint">以大写字母开头，只能包含大写字母、数字和下划线，最多 64 个字符。</small>
          </div>
        </NFormItem>
        <NFormItem label="名称" required>
          <div class="field-with-hint">
            <NInput v-model:value="typeForm.name" maxlength="100" />
            <small class="field-hint">必填，最多 100 个字符。</small>
          </div>
        </NFormItem>
        <NFormItem label="说明"><NInput v-model:value="typeForm.description" type="textarea" maxlength="1000" /></NFormItem>
        <NGrid cols="2" :x-gap="16">
          <NFormItemGi label="排序"><NInputNumber v-model:value="typeForm.displayOrder" :min="0" :precision="0" /></NFormItemGi>
          <NFormItemGi label="启用"><NSwitch v-model:value="typeForm.active" /></NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="typeModalOpen = false">取消</NButton><NButton type="primary" :loading="submitting" @click="submitType">保存</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="definitionModalOpen" preset="card" :title="editingDefinitionId == null ? '新增属性定义' : '编辑属性定义'" :style="{ width: 'min(620px, calc(100vw - 32px))' }">
      <NForm label-placement="top">
        <NGrid cols="1 s:2" :x-gap="16" responsive="screen">
          <NFormItemGi label="Code" required>
            <div class="field-with-hint">
              <NInput v-model:value="definitionForm.code" :disabled="editingDefinitionId !== null" maxlength="64" />
              <small class="field-hint">以小写字母开头，只能包含小写字母、数字和下划线，最多 64 个字符。</small>
            </div>
          </NFormItemGi>
          <NFormItemGi label="名称" required>
            <div class="field-with-hint">
              <NInput v-model:value="definitionForm.name" maxlength="100" />
              <small class="field-hint">必填，最多 100 个字符。</small>
            </div>
          </NFormItemGi>
          <NFormItemGi label="作用域"><NSelect v-model:value="definitionForm.scope" :options="scopeOptions" :disabled="editingDefinitionUsed" /></NFormItemGi>
          <NFormItemGi label="值类型"><NSelect v-model:value="definitionForm.valueType" :options="valueTypeOptions" :disabled="editingDefinitionUsed" /></NFormItemGi>
          <NFormItemGi label="最大长度"><NInputNumber v-model:value="definitionForm.maxLength" :min="1" :max="1000" :disabled="editingDefinitionUsed || definitionForm.valueType !== 'STRING'" /></NFormItemGi>
          <NFormItemGi label="排序"><NInputNumber v-model:value="definitionForm.displayOrder" :min="0" :precision="0" /></NFormItemGi>
        </NGrid>
        <NFormItem v-if="definitionForm.valueType === 'ENUM'" label="允许值" required>
          <div class="field-with-hint">
            <NDynamicTags v-model:value="definitionForm.allowedValues" :disabled="editingDefinitionUsed" />
            <small class="field-hint">至少添加一个允许值，保存时会转为大写。</small>
          </div>
        </NFormItem>
        <NSpace :size="24">
          <NCheckbox v-model:checked="definitionForm.required" :disabled="editingDefinitionUsed">必填</NCheckbox>
          <NCheckbox v-model:checked="definitionForm.filterable">可筛选</NCheckbox>
          <NCheckbox v-model:checked="definitionForm.active" :disabled="editingDefinitionUsed">启用</NCheckbox>
        </NSpace>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="definitionModalOpen = false">取消</NButton><NButton type="primary" :loading="submitting" @click="submitDefinition">保存</NButton></NSpace></template>
    </NModal>

    <NModal v-model:show="categoryModalOpen" preset="card" :title="editingCategoryId == null ? '新增商品分类' : '编辑商品分类'" :style="{ width: 'min(520px, calc(100vw - 32px))' }">
      <NForm label-placement="top">
        <NFormItem label="Code" required>
          <div class="field-with-hint">
            <NInput v-model:value="categoryForm.code" :disabled="editingCategoryId !== null" maxlength="64" />
            <small class="field-hint">以小写字母开头，只能包含小写字母、数字和连字符，最多 64 个字符。</small>
          </div>
        </NFormItem>
        <NFormItem label="名称" required>
          <div class="field-with-hint">
            <NInput v-model:value="categoryForm.name" maxlength="100" />
            <small class="field-hint">必填，最多 100 个字符。</small>
          </div>
        </NFormItem>
        <NFormItem label="说明"><NInput v-model:value="categoryForm.description" type="textarea" maxlength="1000" /></NFormItem>
        <NFormItem label="上级分类"><NSelect v-model:value="categoryForm.parentId" :options="parentOptions" clearable /></NFormItem>
        <NGrid cols="2" :x-gap="16">
          <NFormItemGi label="排序"><NInputNumber v-model:value="categoryForm.displayOrder" :min="0" :precision="0" /></NFormItemGi>
          <NFormItemGi label="状态"><NSelect v-model:value="categoryForm.status" :options="[{ label: '启用', value: 'ACTIVE' }, { label: '停用', value: 'INACTIVE' }]" /></NFormItemGi>
        </NGrid>
      </NForm>
      <template #footer><NSpace justify="end"><NButton @click="categoryModalOpen = false">取消</NButton><NButton type="primary" :loading="submitting" @click="submitCategory">保存</NButton></NSpace></template>
    </NModal>
  </div>
</template>

<style scoped>
.page-header,
.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header {
  margin-bottom: 16px;
}

.page-header h2,
.section-toolbar h3 {
  margin: 0;
}

.page-header h2 {
  margin-bottom: 4px;
  font-size: 22px;
}

.section-toolbar {
  margin-bottom: 12px;
}

.type-layout {
  display: grid;
  grid-template-columns: minmax(260px, .7fr) minmax(0, 1.8fr);
  gap: 28px;
}

.type-list {
  padding-right: 24px;
  border-right: 1px solid #eceef1;
}

.type-row {
  width: 100%;
  min-height: 56px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 34px 34px;
  align-items: center;
  gap: 6px;
  padding: 8px;
  border: 0;
  border-bottom: 1px solid #eceef1;
  color: inherit;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.type-row.active {
  background: #f4f7fb;
}

.type-row span,
.metadata-table td:first-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.type-row small,
.metadata-table small {
  color: #8b8b94;
  font-size: 11px;
}

.metadata-table-wrap {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.metadata-table {
  width: 100%;
  min-width: 680px;
  border-collapse: collapse;
}

.metadata-table th,
.metadata-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #eceef1;
  text-align: left;
}

.metadata-table th {
  background: #f7f8fa;
  font-size: 12px;
}

.metadata-table tbody tr:last-child td {
  border-bottom: 0;
}

.table-actions {
  width: 84px;
  white-space: nowrap;
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

@media (max-width: 840px) {
  .type-layout {
    grid-template-columns: 1fr;
  }

  .type-list {
    padding-right: 0;
    padding-bottom: 18px;
    border-right: 0;
    border-bottom: 1px solid #eceef1;
  }
}
</style>
