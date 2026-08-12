<script setup lang="ts">
import { ArrowDown, ArrowUp, ImagePlus, Trash2 } from '@lucide/vue'
import { computed, reactive, ref, watch } from 'vue'
import type { FormInst, FormRules, UploadCustomRequestOptions } from 'naive-ui'
import { useMessage } from 'naive-ui'
import type {
  AttributeDefinition,
  Product,
  ProductCategory,
  ProductMutation,
  ProductType,
  ProductVariantMutation,
  Tag,
} from '~/types/product'

const props = defineProps<{
  open: boolean
  product: Product | null
  productTypes: ProductType[]
  categories: ProductCategory[]
  tags: Tag[]
}>()

const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'submitted'): void
}>()

const api = useProductApi()
const message = useMessage()
const formRef = ref<FormInst | null>(null)
const submitting = ref(false)
const definitionsLoading = ref(false)
const pendingUploads = ref(0)
const definitions = ref<AttributeDefinition[]>([])
const attributeValues = reactive<Record<string, string>>({})
let definitionSequence = 0

function emptyModel(): ProductMutation {
  return {
    productTypeId: 0,
    categoryId: null,
    name: '',
    status: 'INACTIVE',
    highlights: [],
    materials: [],
    images: [],
    attributes: [],
    fitSense: null,
    description: null,
    designAndExtras: [],
    careInstructions: [],
    tagIds: [],
    variants: [],
  }
}

const model = reactive<ProductMutation>(emptyModel())
const isEditing = computed(() => props.product !== null)
const productDefinitions = computed(() => definitions.value.filter(definition => definition.active && definition.scope === 'PRODUCT'))
const variantDefinitions = computed(() => definitions.value.filter(definition => definition.active && definition.scope === 'VARIANT'))
const typeOptions = computed(() => props.productTypes
  .filter(type => type.active || type.id === model.productTypeId)
  .map(type => ({ label: `${type.name} (${type.code})`, value: type.id })))
const categoryOptions = computed(() => props.categories
  .filter(category => category.status === 'ACTIVE' || category.id === model.categoryId)
  .map(category => ({ label: category.name, value: category.id })))
const tagOptions = computed(() => props.tags.map(tag => ({
  label: tag.active ? tag.name : `${tag.name}（已停用）`,
  value: tag.id,
  disabled: !tag.active && !model.tagIds.includes(tag.id),
})))

const rules: FormRules = {
  productTypeId: [{ required: true, type: 'number', message: '请选择商品类型', trigger: ['change'] }],
  name: [
    { required: true, message: '请输入商品名称', trigger: ['blur', 'input'] },
    { max: 200, message: '商品名称不能超过 200 个字符', trigger: ['blur', 'input'] },
  ],
}

function clearAttributeValues() {
  Object.keys(attributeValues).forEach(key => delete attributeValues[key])
}

async function loadDefinitions(typeId: number) {
  const sequence = ++definitionSequence
  if (!typeId) {
    definitions.value = []
    return
  }
  definitionsLoading.value = true
  try {
    const result = await api.getProductTypeDefinitions(typeId)
    if (sequence === definitionSequence) definitions.value = result
  } catch (error) {
    if (sequence === definitionSequence) {
      definitions.value = []
      message.error(`加载属性定义失败：${errorMessage(error)}`)
    }
  } finally {
    if (sequence === definitionSequence) definitionsLoading.value = false
  }
}

watch(
  () => props.open,
  async open => {
    if (!open) return
    Object.assign(model, emptyModel())
    clearAttributeValues()
    const product = props.product
    if (product) {
      Object.assign(model, {
        productTypeId: product.productTypeId,
        categoryId: product.categoryId ?? null,
        name: product.name,
        status: product.status,
        highlights: [...product.highlights],
        materials: product.materials.map(item => ({ ...item })),
        images: product.images.map(item => ({ ...item })),
        attributes: product.attributes.map(item => ({ ...item })),
        fitSense: product.fitSense ?? null,
        description: product.description ?? null,
        designAndExtras: [...product.designAndExtras],
        careInstructions: [...product.careInstructions],
        tagIds: [...product.tagIds],
        variants: product.variants.map(variant => ({
          id: variant.id,
          sku: variant.sku,
          size: variant.size ?? null,
          color: variant.color,
          price: variant.price,
          warehouseVolume: variant.warehouseVolume,
          status: variant.status,
          displayOrder: variant.displayOrder,
          attributes: variant.attributes.map(attribute => ({ ...attribute })),
        })),
      })
      product.attributes.forEach(attribute => { attributeValues[attribute.code] = attribute.value })
    } else if (props.productTypes.length) {
      model.productTypeId = props.productTypes.find(type => type.active)?.id ?? 0
    }
    await loadDefinitions(model.productTypeId)
    formRef.value?.restoreValidation()
  },
)

watch(
  () => model.productTypeId,
  async (typeId, previousTypeId) => {
    if (!props.open || typeId === previousTypeId) return
    if (!isEditing.value) {
      clearAttributeValues()
      model.variants.forEach(variant => { variant.attributes = [] })
    }
    await loadDefinitions(typeId)
  },
)

function attributeValue(code: string): string {
  return attributeValues[code] ?? ''
}

function setAttributeValue(code: string, value: unknown) {
  const normalized = value === null || value === undefined ? '' : String(value)
  if (normalized) attributeValues[code] = normalized
  else delete attributeValues[code]
}

function numericValue(value: string): number | null {
  if (!value.trim()) return null
  const result = Number(value)
  return Number.isFinite(result) ? result : null
}

function addMaterial() {
  model.materials.push({ name: '', percentage: '0.00' })
}

function addImageUrl() {
  model.images.push({ url: '', altText: null, primary: model.images.length === 0 })
}

function removeImage(index: number) {
  const wasPrimary = model.images[index]?.primary === true
  model.images.splice(index, 1)
  if (wasPrimary && model.images[0]) model.images[0].primary = true
}

function setPrimaryImage(index: number) {
  model.images.forEach((image, imageIndex) => { image.primary = imageIndex === index })
}

function moveImage(index: number, offset: number) {
  const target = index + offset
  if (target < 0 || target >= model.images.length) return
  const [image] = model.images.splice(index, 1)
  if (image) model.images.splice(target, 0, image)
}

async function uploadImage(options: UploadCustomRequestOptions) {
  const file = options.file.file
  if (!file) {
    options.onError()
    return
  }
  pendingUploads.value += 1
  try {
    const [uploaded] = await api.uploadImages([file])
    if (!uploaded) throw new Error('上传响应缺少图片地址')
    model.images.push({ url: uploaded.stableUrl, altText: file.name, primary: model.images.length === 0 })
    options.onFinish()
  } catch (error) {
    message.error(`图片上传失败：${errorMessage(error)}`)
    options.onError()
  } finally {
    pendingUploads.value -= 1
  }
}

function errorMessage(error: unknown): string {
  if (error && typeof error === 'object') {
    const value = error as { statusMessage?: string; message?: string }
    return value.statusMessage || value.message || '未知错误'
  }
  return String(error || '未知错误')
}

function validateDynamicFields(): string | null {
  for (const definition of productDefinitions.value) {
    if (definition.required && !attributeValue(definition.code).trim()) return `请填写 ${definition.name}`
  }
  if (model.variants.length === 0) return '商品至少需要一个 SKU'
  const skus = new Set<string>()
  for (const variant of model.variants) {
    const sku = variant.sku.trim().toUpperCase()
    if (!sku || !/^[A-Z0-9][A-Z0-9._-]{0,63}$/.test(sku)) return 'SKU 格式无效'
    if (skus.has(sku)) return `SKU 重复：${sku}`
    skus.add(sku)
    if (!variant.color.trim()) return `SKU ${sku} 缺少颜色`
    const price = Number(variant.price)
    if (!Number.isFinite(price) || price <= 0 || !/^\d{1,8}(?:\.\d{1,2})?$/.test(variant.price.trim())) return `SKU ${sku} 的 USD 价格无效`
    if (!Number.isInteger(variant.warehouseVolume) || variant.warehouseVolume < 0) return `SKU ${sku} 的库存无效`
    for (const definition of variantDefinitions.value) {
      if (definition.required && !variant.attributes.find(attribute => attribute.code === definition.code)?.value.trim()) {
        return `SKU ${sku} 缺少 ${definition.name}`
      }
    }
  }
  if (model.status === 'ACTIVE' && model.variants.every(variant => variant.status !== 'ACTIVE')) return '上架商品至少需要一个启用 SKU'
  if (model.images.length > 0 && model.images.filter(image => image.primary).length !== 1) return '有图片时必须指定一张主图'
  if (model.images.some(image => !image.url.trim())) return '图片地址不能为空'
  if (model.materials.length > 0) {
    const total = model.materials.reduce((sum, item) => sum + Number(item.percentage), 0)
    if (model.materials.some(item => !item.name.trim() || !Number.isFinite(Number(item.percentage)) || Number(item.percentage) <= 0)) return '面料信息无效'
    if (Math.abs(total - 100) > 0.001) return '面料占比合计必须为 100%'
  }
  return null
}

async function submit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  const dynamicError = validateDynamicFields()
  if (dynamicError) {
    message.warning(dynamicError)
    return
  }

  const payload: ProductMutation = {
    ...model,
    name: model.name.trim(),
    highlights: model.highlights.map(value => value.trim()).filter(Boolean),
    materials: model.materials.map(item => ({ name: item.name.trim(), percentage: Number(item.percentage).toFixed(2) })),
    images: model.images.map(image => ({ url: image.url.trim(), altText: image.altText?.trim() || null, primary: image.primary })),
    attributes: productDefinitions.value
      .map(definition => ({ code: definition.code, value: attributeValue(definition.code).trim() }))
      .filter(attribute => attribute.value),
    fitSense: model.fitSense?.trim() || null,
    description: model.description?.trim() || null,
    designAndExtras: model.designAndExtras.map(value => value.trim()).filter(Boolean),
    careInstructions: model.careInstructions.map(value => value.trim()).filter(Boolean),
    variants: model.variants.map(variant => ({
      ...variant,
      sku: variant.sku.trim().toUpperCase(),
      size: variant.size?.trim() || null,
      color: variant.color.trim(),
      price: Number(variant.price).toFixed(2),
      attributes: variant.attributes.map(attribute => ({ code: attribute.code, value: attribute.value.trim() })).filter(attribute => attribute.value),
    })),
  }

  submitting.value = true
  try {
    if (!props.product) {
      await api.createProduct(payload)
      message.success('商品已创建')
    } else {
      await api.updateProduct(props.product.id, payload)
      message.success('商品已更新')
    }
    emit('update:open', false)
    emit('submitted')
  } catch (error) {
    message.error(`保存商品失败：${errorMessage(error)}`)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <NDrawer
    :show="open"
    width="min(1080px, 100vw)"
    placement="right"
    @update:show="value => emit('update:open', value)"
  >
    <NDrawerContent :title="isEditing ? '编辑商品' : '新增商品'" closable :native-scrollbar="false">
      <NSpin :show="definitionsLoading">
        <NForm ref="formRef" :model="model" :rules="rules" label-placement="top">
          <section class="form-section">
            <h3>基础信息</h3>
            <NGrid cols="1 m:2" :x-gap="16" responsive="screen">
              <NFormItemGi label="商品类型" path="productTypeId">
                <NSelect v-model:value="model.productTypeId" :options="typeOptions" :disabled="isEditing" filterable />
              </NFormItemGi>
              <NFormItemGi label="主分类">
                <NSelect v-model:value="model.categoryId" :options="categoryOptions" clearable filterable />
              </NFormItemGi>
              <NFormItemGi label="商品名称" path="name">
                <NInput v-model:value="model.name" maxlength="200" show-count />
              </NFormItemGi>
              <NFormItemGi label="商品状态">
                <NSelect v-model:value="model.status" :options="[{ label: '上架', value: 'ACTIVE' }, { label: '下架', value: 'INACTIVE' }]" />
              </NFormItemGi>
              <NFormItemGi label="标签" :span="2">
                <NSelect v-model:value="model.tagIds" :options="tagOptions" multiple clearable filterable />
              </NFormItemGi>
            </NGrid>
          </section>

          <section v-if="productDefinitions.length" class="form-section">
            <h3>类型属性</h3>
            <NGrid cols="1 m:2" :x-gap="16" responsive="screen">
              <NFormItemGi
                v-for="definition in productDefinitions"
                :key="definition.id"
                :label="definition.name"
                :required="definition.required"
              >
                <NSelect
                  v-if="definition.valueType === 'ENUM'"
                  :value="attributeValue(definition.code) || null"
                  :options="definition.allowedValues.map(value => ({ label: value, value }))"
                  clearable
                  @update:value="value => setAttributeValue(definition.code, value)"
                />
                <NSwitch
                  v-else-if="definition.valueType === 'BOOLEAN'"
                  :value="attributeValue(definition.code) === 'true'"
                  @update:value="value => setAttributeValue(definition.code, value)"
                />
                <NInputNumber
                  v-else-if="definition.valueType === 'INTEGER' || definition.valueType === 'DECIMAL'"
                  :value="numericValue(attributeValue(definition.code))"
                  :precision="definition.valueType === 'INTEGER' ? 0 : undefined"
                  style="width: 100%"
                  @update:value="value => setAttributeValue(definition.code, value)"
                />
                <NInput
                  v-else
                  :value="attributeValue(definition.code)"
                  :maxlength="definition.maxLength ?? 1000"
                  @update:value="value => setAttributeValue(definition.code, value)"
                />
              </NFormItemGi>
            </NGrid>
          </section>

          <section class="form-section">
            <h3>展示内容</h3>
            <NFormItem label="商品描述">
              <NInput v-model:value="model.description" type="textarea" maxlength="4000" show-count :autosize="{ minRows: 3, maxRows: 8 }" />
            </NFormItem>
            <NFormItem label="版型与穿着感受">
              <NInput v-model:value="model.fitSense" maxlength="255" show-count />
            </NFormItem>
            <NGrid cols="1 m:3" :x-gap="16" responsive="screen">
              <NFormItemGi label="商品卖点">
                <NDynamicInput v-model:value="model.highlights" :max="10" placeholder="商品卖点" />
              </NFormItemGi>
              <NFormItemGi label="设计细节">
                <NDynamicInput v-model:value="model.designAndExtras" :max="12" placeholder="设计细节" />
              </NFormItemGi>
              <NFormItemGi label="洗护说明">
                <NDynamicInput v-model:value="model.careInstructions" :max="12" placeholder="洗护说明" />
              </NFormItemGi>
            </NGrid>
          </section>

          <section class="form-section">
            <div class="section-toolbar">
              <h3>面料</h3>
              <NButton size="small" secondary @click="addMaterial">添加面料</NButton>
            </div>
            <div v-for="(material, index) in model.materials" :key="index" class="material-row">
              <NInput v-model:value="material.name" maxlength="100" placeholder="面料名称" />
              <NInput v-model:value="material.percentage" maxlength="6" placeholder="百分比" />
              <NButton quaternary circle type="error" aria-label="删除面料" @click="model.materials.splice(index, 1)"><Trash2 :size="15" /></NButton>
            </div>
            <NEmpty v-if="model.materials.length === 0" description="暂无面料" size="small" />
          </section>

          <section class="form-section">
            <div class="section-toolbar">
              <h3>商品图片</h3>
              <NSpace>
                <NUpload :show-file-list="false" accept="image/jpeg,image/png,image/webp,image/gif" :custom-request="uploadImage">
                  <NButton size="small" secondary :loading="pendingUploads > 0">
                    <template #icon><ImagePlus :size="15" /></template>
                    上传图片
                  </NButton>
                </NUpload>
                <NButton size="small" secondary @click="addImageUrl">添加地址</NButton>
              </NSpace>
            </div>
            <div v-for="(image, index) in model.images" :key="index" class="image-row">
              <NInput v-model:value="image.url" maxlength="512" placeholder="https://" />
              <NInput v-model:value="image.altText" maxlength="255" placeholder="替代文本" />
              <NRadio :checked="image.primary" @update:checked="(checked: boolean) => checked && setPrimaryImage(index)">主图</NRadio>
              <div class="image-actions">
                <NButton quaternary circle aria-label="图片上移" :disabled="index === 0" @click="moveImage(index, -1)"><ArrowUp :size="15" /></NButton>
                <NButton quaternary circle aria-label="图片下移" :disabled="index === model.images.length - 1" @click="moveImage(index, 1)"><ArrowDown :size="15" /></NButton>
                <NButton quaternary circle type="error" aria-label="删除图片" @click="removeImage(index)"><Trash2 :size="15" /></NButton>
              </div>
            </div>
            <NEmpty v-if="model.images.length === 0" description="暂无图片" size="small" />
          </section>

          <section class="form-section">
            <ProductVariantEditor v-model="model.variants" :definitions="variantDefinitions" />
          </section>
        </NForm>
      </NSpin>

      <template #footer>
        <NSpace justify="end">
          <NButton :disabled="submitting" @click="emit('update:open', false)">取消</NButton>
          <NButton type="primary" :loading="submitting" :disabled="pendingUploads > 0" @click="submit">保存</NButton>
        </NSpace>
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.form-section {
  padding: 18px 0;
  border-bottom: 1px solid #eceef1;
}

.form-section:first-child {
  padding-top: 0;
}

.form-section:last-child {
  border-bottom: 0;
}

.form-section h3 {
  margin: 0 0 14px;
  font-size: 15px;
}

.section-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.section-toolbar h3 {
  margin: 0;
}

.material-row,
.image-row {
  display: grid;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.material-row {
  grid-template-columns: minmax(180px, 1fr) 140px 34px;
}

.image-row {
  grid-template-columns: minmax(260px, 1.4fr) minmax(180px, 1fr) 70px auto;
}

.image-actions {
  display: flex;
  align-items: center;
}

@media (max-width: 720px) {
  .material-row,
  .image-row {
    grid-template-columns: 1fr;
    padding-bottom: 12px;
    border-bottom: 1px solid #eceef1;
  }

  .section-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
