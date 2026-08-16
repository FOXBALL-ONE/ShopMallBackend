<script setup lang="ts">
import { Copy, Plus, Trash2 } from '@lucide/vue'
import type { AttributeDefinition, ProductVariantMutation } from '~/types/product'

const variants = defineModel<ProductVariantMutation[]>({ required: true })
const props = defineProps<{ definitions: AttributeDefinition[] }>()

function addVariant(source?: ProductVariantMutation) {
  variants.value.push(source
    ? {
        ...source,
        id: undefined,
        sku: '',
        warehouseVolume: 0,
        attributes: source.attributes.map(attribute => ({ ...attribute })),
      }
    : {
        sku: '',
        size: null,
        color: '',
        price: '0.00',
        warehouseVolume: 0,
        status: 'INACTIVE',
        displayOrder: variants.value.length,
        attributes: [],
      })
}

function removeVariant(index: number) {
  variants.value.splice(index, 1)
}

function attributeValue(variant: ProductVariantMutation, code: string): string {
  return variant.attributes.find(attribute => attribute.code === code)?.value ?? ''
}

function setAttributeValue(variant: ProductVariantMutation, code: string, value: unknown) {
  const normalized = value === null || value === undefined ? '' : String(value)
  const index = variant.attributes.findIndex(attribute => attribute.code === code)
  if (!normalized) {
    if (index >= 0) variant.attributes.splice(index, 1)
    return
  }
  if (index >= 0) variant.attributes[index]!.value = normalized
  else variant.attributes.push({ code, value: normalized })
}

function numericValue(value: string): number | null {
  if (!value.trim()) return null
  const result = Number(value)
  return Number.isFinite(result) ? result : null
}
</script>

<template>
  <section class="variant-editor">
    <div class="variant-toolbar">
      <h3>SKU</h3>
      <NButton size="small" type="primary" secondary @click="addVariant()">
        <template #icon><Plus :size="15" /></template>
        新增 SKU
      </NButton>
    </div>

    <div class="variant-table-wrap">
      <table class="variant-table">
        <thead>
          <tr>
            <th>SKU</th>
            <th>尺码</th>
            <th>颜色</th>
            <th>价格 (USD)</th>
            <th>库存</th>
            <th>状态</th>
            <th>排序</th>
            <th v-for="definition in props.definitions" :key="definition.id">
              {{ definition.name }}<span v-if="definition.required"> *</span>
            </th>
            <th aria-label="操作" />
          </tr>
        </thead>
        <tbody>
          <tr v-for="(variant, index) in variants" :key="variant.id ?? `new-${index}`">
            <td>
              <div class="field-with-hint">
                <NInput v-model:value="variant.sku" :disabled="variant.id !== undefined" maxlength="64" placeholder="SKU" />
                <small class="field-hint">1–64 个字符；只允许字母、数字、点、下划线和连字符，首字符须为字母或数字。</small>
              </div>
            </td>
            <td><NInput v-model:value="variant.size" maxlength="30" clearable placeholder="可空" /></td>
            <td>
              <div class="color-input">
                <span class="color-swatch" :style="{ backgroundColor: variant.color }" />
                <div class="field-with-hint">
                  <NInput v-model:value="variant.color" maxlength="50" placeholder="#000000" />
                  <small class="field-hint">必填，最多 50 个字符。</small>
                </div>
              </div>
            </td>
            <td>
              <div class="field-with-hint">
                <NInput v-model:value="variant.price" maxlength="12" placeholder="0.00" />
                <small class="field-hint">大于 0；整数最多 8 位，小数最多 2 位。</small>
              </div>
            </td>
            <td>
              <div class="field-with-hint">
                <NInputNumber v-model:value="variant.warehouseVolume" :min="0" :precision="0" />
                <small class="field-hint">必须为非负整数。</small>
              </div>
            </td>
            <td>
              <NSelect
                v-model:value="variant.status"
                :options="[{ label: '启用', value: 'ACTIVE' }, { label: '停用', value: 'INACTIVE' }]"
              />
            </td>
            <td><NInputNumber v-model:value="variant.displayOrder" :min="0" :precision="0" /></td>
            <td v-for="definition in props.definitions" :key="definition.id">
              <NSelect
                v-if="definition.valueType === 'ENUM'"
                :value="attributeValue(variant, definition.code) || null"
                :options="definition.allowedValues.map(value => ({ label: value, value }))"
                clearable
                @update:value="value => setAttributeValue(variant, definition.code, value)"
              />
              <NSwitch
                v-else-if="definition.valueType === 'BOOLEAN'"
                :value="attributeValue(variant, definition.code) === 'true'"
                @update:value="value => setAttributeValue(variant, definition.code, value)"
              />
              <NInputNumber
                v-else-if="definition.valueType === 'INTEGER' || definition.valueType === 'DECIMAL'"
                :value="numericValue(attributeValue(variant, definition.code))"
                :precision="definition.valueType === 'INTEGER' ? 0 : undefined"
                @update:value="value => setAttributeValue(variant, definition.code, value)"
              />
              <NInput
                v-else
                :value="attributeValue(variant, definition.code)"
                :maxlength="definition.maxLength ?? 1000"
                @update:value="value => setAttributeValue(variant, definition.code, value)"
              />
            </td>
            <td>
              <div class="variant-actions">
                <NTooltip>
                  <template #trigger>
                    <NButton quaternary circle aria-label="复制 SKU" @click="addVariant(variant)"><Copy :size="15" /></NButton>
                  </template>
                  复制 SKU
                </NTooltip>
                <NTooltip>
                  <template #trigger>
                    <NButton quaternary circle type="error" aria-label="删除 SKU" @click="removeVariant(index)"><Trash2 :size="15" /></NButton>
                  </template>
                  删除 SKU
                </NTooltip>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <NEmpty v-if="variants.length === 0" description="暂无 SKU" class="variant-empty" />
  </section>
</template>

<style scoped>
.variant-editor {
  min-width: 0;
}

.variant-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.variant-toolbar h3 {
  margin: 0;
  font-size: 15px;
}

.variant-table-wrap {
  overflow-x: auto;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.variant-table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
}

.variant-table th,
.variant-table td {
  width: 150px;
  min-width: 150px;
  padding: 8px;
  border-right: 1px solid #e5e7eb;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
  vertical-align: top;
}

.variant-table th {
  background: #f7f8fa;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.variant-table th:nth-child(5),
.variant-table td:nth-child(5),
.variant-table th:nth-child(7),
.variant-table td:nth-child(7) {
  width: 110px;
  min-width: 110px;
}

.variant-table th:last-child,
.variant-table td:last-child {
  width: 84px;
  min-width: 84px;
  border-right: 0;
}

.variant-table tbody tr:last-child td {
  border-bottom: 0;
}

.field-with-hint {
  width: 100%;
  min-width: 0;
}

.field-hint {
  display: block;
  margin-top: 5px;
  color: #8c8c8c;
  font-size: 11px;
  font-weight: 400;
  line-height: 1.4;
}

.color-input,
.variant-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.color-swatch {
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  border: 1px solid #c8cbd1;
  border-radius: 3px;
  background: #fff;
}

.variant-empty {
  padding: 24px 0 8px;
}
</style>
