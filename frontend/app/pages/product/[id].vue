<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CatalogVariant } from '~/data/catalog'
import { displayProductType, formatPrice, getCollection } from '~/data/catalog'
import { customerRequestMessage } from '~/composables/useCustomerAccountApi'

const route = useRoute()
const catalogApi = useCatalogApi()
const session = useCustomerSession()
const customerCart = useCustomerCart()
const toast = useToast()
const productId = computed(() => Number(route.params.id))

const { data: pageData, status, error, refresh } = await useAsyncData(
  () => `product-${productId.value}`,
  async () => {
    const product = await catalogApi.getProduct(productId.value)
    const [definitions, allProducts] = await Promise.all([
      catalogApi.getDefinitions(product.product_type),
      catalogApi.listProducts(),
    ])
    return { product, definitions, allProducts }
  },
  { watch: [productId] },
)

const product = computed(() => pageData.value?.product ?? null)
const definitions = computed(() => pageData.value?.definitions ?? [])
const variants = computed(() => {
  const seen = new Set<string>()
  return (product.value?.variants ?? [])
    .filter(variant => {
      const attributes = [...variant.attributes]
        .sort((left, right) => left.code.localeCompare(right.code))
        .map(attribute => `${attribute.code}:${attribute.value}`)
        .join('|')
      const key = [variant.id, variant.sku, variant.size ?? '', variant.color, attributes].join('::')
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .sort((left, right) => left.display_order - right.display_order || left.id - right.id)
})
const selectedImage = ref(0)
const isImagePreviewOpen = ref(false)
const selection = ref<Record<string, string>>({})
const quantity = ref(1)
const isFavorite = ref(false)
const isAdding = ref(false)
const isAdded = ref(false)
const addError = ref('')
let feedbackTimer: ReturnType<typeof setTimeout> | undefined

type OptionDimension = { code: string; label: string; values: string[] }

function optionValue(variant: CatalogVariant, code: string): string {
  if (code === 'size') return variant.size ?? ''
  if (code === 'color') return variant.color
  return variant.attributes.find(attribute => attribute.code === code)?.value ?? ''
}

const dimensions = computed<OptionDimension[]>(() => {
  const result: OptionDimension[] = []
  const sizeValues = [...new Set(variants.value.map(variant => variant.size).filter((value): value is string => Boolean(value)))]
  const colorValues = [...new Set(variants.value.map(variant => variant.color).filter(Boolean))]
  const variantDefinitions = [...definitions.value]
    .filter(definition => definition.active && definition.scope === 'VARIANT')
    .sort((left, right) => left.display_order - right.display_order)
  const hasSeparateTopAndBottomSizes = ['top_size', 'bottom_size'].every(code =>
    variantDefinitions.some(definition => definition.code === code),
  )
  const sizeIsDerivedFromVariantAttributes = hasSeparateTopAndBottomSizes
    && variants.value.length > 0
    && variants.value.every(variant => {
      const topSize = optionValue(variant, 'top_size')
      const bottomSize = optionValue(variant, 'bottom_size')
      return Boolean(topSize && bottomSize && variant.size === `${topSize}/${bottomSize}`)
    })

  // SKU 同时返回了组合尺码（例如 S/S）和上下分开的规格时，组合尺码是冗余信息，避免用户重复选择。
  if (sizeValues.length && !sizeIsDerivedFromVariantAttributes) {
    result.push({ code: 'size', label: 'Size', values: sizeValues })
  }
  variantDefinitions.forEach(definition => {
    const values = [...new Set(variants.value.map(variant => optionValue(variant, definition.code)).filter(Boolean))]
    if (values.length) result.push({ code: definition.code, label: definition.name, values })
  })
  if (colorValues.length) result.push({ code: 'color', label: 'Color', values: colorValues })
  return result
})

const selectedVariant = computed(() => {
  if (!dimensions.value.every(dimension => selection.value[dimension.code])) return null
  return variants.value.find(variant => dimensions.value.every(dimension => optionValue(variant, dimension.code) === selection.value[dimension.code])) ?? null
})

const hasSelection = computed(() => Object.values(selection.value).some(Boolean))

const displayedPrice = computed(() => {
  if (selectedVariant.value) return formatPrice(Number(selectedVariant.value.price))
  const compatibleVariants = matchingVariants(selection.value)
  // 部分规格已选中时，只展示仍可匹配的 SKU 价格；没有库存时也保留同规格的价格反馈。
  const prices = (compatibleVariants.length ? compatibleVariants : variants.value)
    .map(variant => Number(variant.price))
    .filter(Number.isFinite)
  if (!prices.length) return formatPrice(0)
  const min = Math.min(...prices)
  const max = Math.max(...prices)
  return min === max ? formatPrice(min) : `${formatPrice(min)} - ${formatPrice(max)}`
})
const availableStock = computed(() => selectedVariant.value?.warehouse_volume ?? 0)
const maximumQuantity = computed(() => Math.min(availableStock.value, 99))
const canAddToCart = computed(() => Boolean(selectedVariant.value && availableStock.value > 0 && !isAdding.value))
const productCollection = computed(() => getCollection(product.value?.collections[0] || 'shop'))
const images = computed(() => product.value?.image_details?.length
  ? product.value.image_details
  : [{ url: '/lingerie/hero-corset.jpg', alt_text: product.value?.name ?? '', is_primary: true }])
const mainImage = computed(() => images.value[selectedImage.value] ?? images.value[0])
const definitionNames = computed(() => new Map(definitions.value.map(definition => [definition.code, definition.name])))
const productAttributes = computed(() => (product.value?.attributes ?? []).map(attribute => ({
  label: definitionNames.value.get(attribute.code) ?? displayCode(attribute.code),
  value: formatAttribute(attribute.value),
})))
const relatedProducts = computed(() => {
  const current = product.value
  if (!current) return []
  return (pageData.value?.allProducts ?? [])
    .filter(item => item.id !== current.id && (
      item.product_type === current.product_type
      || item.collections.some(collection => current.collections.includes(collection))
    ))
    .slice(0, 4)
})

watch(dimensions, () => {
  selection.value = {}
  quantity.value = 1
  selectedImage.value = 0
  isImagePreviewOpen.value = false
}, { immediate: true })

watch(selectedVariant, () => { quantity.value = 1 })

function matchingVariants(candidateSelection: Record<string, string>): CatalogVariant[] {
  return variants.value.filter(variant => Object.entries(candidateSelection).every(([code, value]) => !value || optionValue(variant, code) === value))
}

function hasAvailableVariant(candidateSelection: Record<string, string>): boolean {
  return matchingVariants(candidateSelection).some(variant => variant.warehouse_volume > 0)
}

function selectionThrough(code: string, value?: string): Record<string, string> {
  const next = Object.fromEntries(
    Object.entries(selection.value).filter(([selectedCode]) => selectedCode !== code),
  )
  if (!value) return next

  // 将刚操作的规格放到选择顺序末尾。若组合不存在，优先保留更多已有选择，
  // 并在同样数量的候选中保留更早选中的规格，避免误清理颜色等独立规格。
  next[code] = value
  if (hasAvailableVariant(next)) return next

  const selectedCodes = Object.keys(next).filter(selectedCode => selectedCode !== code)
  const availableCandidates = variants.value.filter(variant =>
    variant.warehouse_volume > 0 && optionValue(variant, code) === value,
  )
  let bestCandidate: CatalogVariant | null = null
  let bestKeptCount = -1
  for (const candidate of availableCandidates) {
    const keptCount = selectedCodes.reduce((count, selectedCode) =>
      count + (optionValue(candidate, selectedCode) === next[selectedCode] ? 1 : 0), 0)
    let keepsEarlierSelection = false
    if (keptCount === bestKeptCount && bestCandidate) {
      for (const selectedCode of selectedCodes) {
        const candidateKeeps = optionValue(candidate, selectedCode) === next[selectedCode]
        const bestKeeps = optionValue(bestCandidate, selectedCode) === next[selectedCode]
        if (candidateKeeps === bestKeeps) continue
        keepsEarlierSelection = candidateKeeps
        break
      }
    }
    if (keptCount > bestKeptCount || keepsEarlierSelection) {
      bestCandidate = candidate
      bestKeptCount = keptCount
    }
  }

  if (!bestCandidate) return next
  const compatibleSelection: Record<string, string> = {}
  selectedCodes.forEach(selectedCode => {
    if (optionValue(bestCandidate!, selectedCode) === next[selectedCode]) {
      compatibleSelection[selectedCode] = next[selectedCode]!
    }
  })
  compatibleSelection[code] = value
  return compatibleSelection
}

type OptionAvailability = { available: boolean; adjustsSelection: boolean; soldOut: boolean; title: string }

function optionAvailability(code: string, value: string): OptionAvailability {
  const directSelection = { ...selection.value, [code]: value }
  const directMatches = matchingVariants(directSelection)
  if (directMatches.some(variant => variant.warehouse_volume > 0)) {
    return { available: true, adjustsSelection: false, soldOut: false, title: 'Available' }
  }

  const compatibleSelection = selectionThrough(code, value)
  const hasCompatibleStock = hasAvailableVariant(compatibleSelection)
  const adjustsSelection = hasCompatibleStock && Object.keys(directSelection).some(
    selectedCode => compatibleSelection[selectedCode] !== directSelection[selectedCode],
  )
  const soldOut = !hasCompatibleStock && directMatches.length > 0
  return {
    available: hasCompatibleStock,
    adjustsSelection,
    soldOut,
    title: hasCompatibleStock
      ? 'Selecting this will update an incompatible option'
      : soldOut ? 'Sold out for the current selection' : 'Unavailable with the current selection',
  }
}

function optionAvailabilityKey(code: string, value: string): string {
  return `${code}\u0000${value}`
}

const optionAvailabilityByKey = computed(() => Object.fromEntries(
  dimensions.value.flatMap(dimension => dimension.values.map(value => [
    optionAvailabilityKey(dimension.code, value),
    optionAvailability(dimension.code, value),
  ])),
))

function getOptionAvailability(code: string, value: string): OptionAvailability {
  return optionAvailabilityByKey.value[optionAvailabilityKey(code, value)]
    ?? { available: false, adjustsSelection: false, soldOut: false, title: 'Unavailable' }
}

const colorSwatches: Record<string, string> = {
  black: '#1f1d1d',
  blue: '#4c78a8',
  ocean_blue: '#2f6174',
  navy: '#263b63',
  pink: '#d894a6',
  red: '#a84b55',
  seafoam: '#8eb8ae',
  white: '#f8f5f0',
}

function colorSwatch(value: string): string {
  const normalized = value.trim().toLowerCase().replaceAll(' ', '_')
  if (/^#(?:[\da-f]{3}|[\da-f]{6}|[\da-f]{8})$/i.test(normalized)) return normalized
  return colorSwatches[normalized] ?? '#d8d0cd'
}

function selectOption(code: string, value: string) {
  if (!getOptionAvailability(code, value).available) return
  selection.value = selectionThrough(code, selection.value[code] === value ? undefined : value)
  isAdded.value = false
  addError.value = ''
}

function clearOption(code: string) {
  if (!selection.value[code]) return
  selection.value = selectionThrough(code)
  isAdded.value = false
  addError.value = ''
}

function clearAllSelections() {
  if (!hasSelection.value) return
  selection.value = {}
  quantity.value = 1
  isAdded.value = false
  addError.value = ''
}

function changeQuantity(delta: number) {
  quantity.value = Math.min(Math.max(maximumQuantity.value, 1), Math.max(1, quantity.value + delta))
}

async function addToCart() {
  const variant = selectedVariant.value
  if (!product.value || !variant || !canAddToCart.value) return
  const userId = await session.requireSignIn(route.fullPath)
  if (!userId) return
  isAdding.value = true
  addError.value = ''
  try {
    await customerCart.addItem(variant.id, quantity.value)
    isAdded.value = true
    toast.add({
      title: 'Added to cart',
      description: `${quantity.value} ${quantity.value === 1 ? 'piece' : 'pieces'} of ${product.value.name} added.`,
      color: 'success',
    })
    if (feedbackTimer) clearTimeout(feedbackTimer)
    feedbackTimer = setTimeout(() => { isAdded.value = false }, 2400)
  } catch (requestError: unknown) {
    addError.value = customerRequestMessage(requestError, 'We could not add this piece to your cart.')
    toast.add({ title: 'Cart not updated', description: addError.value, color: 'error' })
  } finally {
    isAdding.value = false
  }
}

function displayCode(code: string): string {
  return code.toLocaleLowerCase().replaceAll('_', ' ').replace(/\b\w/g, letter => letter.toLocaleUpperCase())
}

function formatAttribute(value: string): string {
  if (value === 'true') return 'Yes'
  if (value === 'false') return 'No'
  return displayCode(value)
}

function handlePreviewKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') isImagePreviewOpen.value = false
}

onMounted(() => document.addEventListener('keydown', handlePreviewKeydown))

onBeforeUnmount(() => {
  if (feedbackTimer) clearTimeout(feedbackTimer)
  document.removeEventListener('keydown', handlePreviewKeydown)
})

useHead(() => ({
  title: product.value ? `${product.value.name} | Pelissa` : 'Product | Pelissa',
  meta: [{ name: 'description', content: product.value?.description || 'Explore the Pelissa collection.' }],
}))
</script>

<template>
  <main class="store-page">
    <StoreHeader />

    <section v-if="status === 'pending'" class="product-state" aria-busy="true">
      <UIcon name="i-lucide-loader-circle" class="is-spinning" />
      <p>Loading product</p>
    </section>

    <section v-else-if="error || !product" class="product-state">
      <UIcon name="i-lucide-package-x" />
      <h1>Product unavailable</h1>
      <button class="store-button" type="button" @click="refresh()">Try again</button>
    </section>

    <template v-else>
      <nav class="store-container product-breadcrumb" aria-label="Breadcrumb">
        <NuxtLink to="/">Home</NuxtLink>
        <UIcon name="i-lucide-chevron-right" />
        <NuxtLink :to="`/collections/${productCollection.slug}`">{{ productCollection.englishLabel }}</NuxtLink>
        <UIcon name="i-lucide-chevron-right" />
        <span>{{ product.name }}</span>
      </nav>

      <section class="product-detail store-container">
        <div class="product-gallery">
          <div class="product-thumbnails">
            <button
              v-for="(image, index) in images"
              :key="`${image.url}-${index}`"
              type="button"
              :class="{ active: selectedImage === index }"
              :aria-label="`View image ${index + 1}`"
              @click="selectedImage = index"
            >
              <img :src="image.url" :alt="image.alt_text || ''">
            </button>
          </div>
          <div class="product-main-image">
            <button
              type="button"
              class="product-image-preview-trigger"
              aria-label="Open larger product image"
              title="View larger image"
              @click="isImagePreviewOpen = true"
            >
              <img :src="mainImage?.url" :alt="mainImage?.alt_text || product.name">
              <span class="product-image-zoom" aria-hidden="true"><UIcon name="i-lucide-zoom-in" /></span>
            </button>
            <span v-if="product.badge" class="product-detail-badge" :class="{ sale: product.is_sale }">{{ product.badge }}</span>
            <button
              type="button"
              class="favorite-button"
              :class="{ active: isFavorite }"
              :aria-label="isFavorite ? 'Remove from favorites' : 'Add to favorites'"
              @click="isFavorite = !isFavorite"
            >
              <UIcon name="i-lucide-heart" />
            </button>
          </div>
        </div>

        <div class="product-purchase">
          <p class="store-eyebrow">{{ displayProductType(product.product_type) }}</p>
          <div class="product-title-row">
            <h1>{{ product.name }}</h1>
            <span v-if="product.score > 0"><UIcon name="i-lucide-star" /> {{ product.score.toFixed(1) }}</span>
          </div>
          <p class="product-price">{{ displayedPrice }} <small>USD</small></p>
          <p v-if="product.fit_sense" class="product-fit">{{ product.fit_sense }}</p>

          <div class="product-options">
            <div class="product-options-heading">
              <span>Choose your options</span>
              <button
                v-if="hasSelection"
                type="button"
                class="clear-all-options"
                title="Clear all selected options"
                @click="clearAllSelections"
              >
                Clear all
              </button>
            </div>
            <fieldset v-for="dimension in dimensions" :key="dimension.code">
              <legend>
                <span>{{ dimension.label }}</span>
                <strong>
                  <span>{{ selection[dimension.code] ? formatAttribute(selection[dimension.code] ?? '') : '—' }}</span>
                  <button
                    v-if="selection[dimension.code]"
                    type="button"
                    class="option-group-clear"
                    :aria-label="`Clear ${dimension.label} selection`"
                    :title="`Clear ${dimension.label} selection`"
                    @click="clearOption(dimension.code)"
                  >
                    <UIcon name="i-lucide-x" />
                  </button>
                </strong>
              </legend>
              <div class="option-grid">
                <button
                  v-for="value in dimension.values"
                  :key="value"
                  type="button"
                  :class="{ active: selection[dimension.code] === value, color: dimension.code === 'color' }"
                  :aria-pressed="selection[dimension.code] === value"
                  :aria-label="`${dimension.label}: ${formatAttribute(value)}. ${getOptionAvailability(dimension.code, value).title}`"
                  :title="getOptionAvailability(dimension.code, value).title"
                  :disabled="!getOptionAvailability(dimension.code, value).available"
                  @click="selectOption(dimension.code, value)"
                >
                  <span v-if="dimension.code === 'color'" class="option-swatch" :style="{ backgroundColor: colorSwatch(value) }" />
                  {{ formatAttribute(value) }}
                </button>
              </div>
            </fieldset>
          </div>

          <div class="product-cart-row">
            <div class="product-quantity">
              <button type="button" aria-label="Decrease quantity" :disabled="quantity <= 1" @click="changeQuantity(-1)"><UIcon name="i-lucide-minus" /></button>
              <span>{{ quantity }}</span>
              <button type="button" aria-label="Increase quantity" :disabled="!selectedVariant || quantity >= maximumQuantity" @click="changeQuantity(1)"><UIcon name="i-lucide-plus" /></button>
            </div>
            <button class="product-add-button" type="button" :disabled="!canAddToCart" @click="addToCart">
              <UIcon v-if="isAdding" name="i-lucide-loader-circle" class="is-spinning" />
              <UIcon v-else-if="isAdded" name="i-lucide-check" />
              <UIcon v-else name="i-lucide-shopping-cart" />
              {{ isAdded ? 'Added' : selectedVariant ? (availableStock > 0 ? 'Add to cart' : 'Sold out') : 'Select options' }}
            </button>
          </div>
          <p v-if="selectedVariant && availableStock > 0" class="stock-line">{{ availableStock }} in stock · {{ selectedVariant.sku }}</p>
          <p v-if="addError" class="product-error"><UIcon name="i-lucide-circle-alert" /> {{ addError }}</p>
        </div>
      </section>

      <section class="product-story">
        <div class="store-container story-grid">
          <div>
            <p class="store-eyebrow">DETAILS</p>
            <h2>{{ product.name }}</h2>
            <p>{{ product.description }}</p>
            <ul v-if="product.highlight.length">
              <li v-for="highlight in product.highlight" :key="highlight">{{ highlight }}</li>
            </ul>
          </div>

          <dl class="attribute-list">
            <template v-for="attribute in productAttributes" :key="attribute.label">
              <dt>{{ attribute.label }}</dt>
              <dd>{{ attribute.value }}</dd>
            </template>
            <template v-for="material in product.materials ?? []" :key="material.name">
              <dt>{{ material.name }}</dt>
              <dd>{{ material.percentage }}%</dd>
            </template>
          </dl>

          <div class="care-list">
            <div v-if="product.design_and_extras.length">
              <h3>Design</h3>
              <ul><li v-for="item in product.design_and_extras" :key="item">{{ item }}</li></ul>
            </div>
            <div v-if="product.care_instructions.length">
              <h3>Care</h3>
              <ul><li v-for="item in product.care_instructions" :key="item">{{ item }}</li></ul>
            </div>
          </div>
        </div>
      </section>

      <section v-if="relatedProducts.length" class="product-related store-container">
        <div class="related-heading">
          <div><p class="store-eyebrow">MORE TO EXPLORE</p><h2>You may also like</h2></div>
          <NuxtLink to="/collections/shop">Shop all <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
        </div>
        <div class="related-grid">
          <ProductCard v-for="item in relatedProducts" :key="item.id" :product="item" />
        </div>
      </section>

      <Teleport to="body">
        <div
          v-if="isImagePreviewOpen"
          class="product-image-preview"
          role="dialog"
          aria-modal="true"
          aria-label="Product image preview"
          @click.self="isImagePreviewOpen = false"
        >
          <div class="product-image-preview-panel">
            <button
              type="button"
              class="product-image-preview-close"
              aria-label="Close image preview"
              title="Close image preview"
              @click="isImagePreviewOpen = false"
            >
              <UIcon name="i-lucide-x" />
            </button>
            <img :src="mainImage?.url" :alt="mainImage?.alt_text || product.name">
          </div>
        </div>
      </Teleport>
    </template>

    <StoreFooter />
  </main>
</template>

<style scoped>
.product-state {
  min-height: 620px;
  display: grid;
  place-items: center;
  align-content: center;
  gap: 16px;
  text-align: center;
}

.product-state > .iconify {
  width: 42px;
  height: 42px;
  color: var(--store-wine);
}

.product-state h1,
.product-state p {
  margin: 0;
}

.product-breadcrumb {
  min-height: 58px;
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
  color: var(--store-muted);
  font-size: 10px;
}

.product-breadcrumb a {
  color: inherit;
  text-decoration: none;
}

.product-breadcrumb .iconify {
  width: 12px;
  height: 12px;
}

.product-breadcrumb span {
  overflow: hidden;
  color: var(--store-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-detail {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, .8fr);
  align-items: start;
  gap: clamp(36px, 6vw, 90px);
  padding-top: 16px;
  padding-bottom: 102px;
}

.product-gallery {
  min-width: 0;
  display: grid;
  grid-template-columns: 82px minmax(0, 1fr);
  align-items: start;
  align-self: start;
  gap: 12px;
}

.product-thumbnails {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
  overflow-y: auto;
}

.product-thumbnails button {
  aspect-ratio: .78;
  overflow: hidden;
  padding: 0;
  border: 1px solid transparent;
  background: #eee;
  cursor: pointer;
}

.product-thumbnails button.active {
  border-color: var(--store-ink);
}

.product-thumbnails img,
.product-image-preview-trigger img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.product-main-image {
  position: relative;
  min-height: 0;
  aspect-ratio: .8;
  overflow: hidden;
  background: #ded6d5;
}

.product-image-preview-trigger {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: zoom-in;
}

.product-image-zoom {
  position: absolute;
  right: 15px;
  bottom: 15px;
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .9);
}

.product-image-zoom .iconify {
  width: 17px;
  height: 17px;
}

.product-detail-badge {
  position: absolute;
  z-index: 2;
  top: 18px;
  left: 18px;
  padding: 7px 10px;
  color: #fff;
  background: var(--store-plum);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
}

.product-detail-badge.sale {
  background: var(--store-wine);
}

.favorite-button {
  position: absolute;
  z-index: 2;
  top: 14px;
  right: 14px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .9);
  cursor: pointer;
}

.favorite-button.active {
  color: #fff;
  background: var(--store-wine);
}

.favorite-button.active .iconify {
  fill: currentColor;
}

.product-image-preview {
  position: fixed;
  z-index: 100;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 32px;
  background: rgba(28, 21, 25, .72);
}

.product-image-preview-panel {
  position: relative;
  width: min(900px, calc(100vw - 64px));
  max-height: calc(100vh - 64px);
  display: grid;
  place-items: center;
  padding: 16px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(16, 10, 13, .3);
}

.product-image-preview-panel img {
  width: 100%;
  max-height: calc(100vh - 96px);
  display: block;
  object-fit: contain;
}

.product-image-preview-close {
  position: absolute;
  z-index: 1;
  top: 24px;
  right: 24px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 4px 16px rgba(16, 10, 13, .16);
  cursor: pointer;
}

.product-image-preview-close .iconify {
  width: 19px;
  height: 19px;
}

.product-purchase {
  padding-top: 24px;
}

.product-title-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.product-title-row h1 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(40px, 4vw, 58px);
  font-weight: 500;
  line-height: 1.02;
}

.product-title-row span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--store-muted);
  font-size: 11px;
}

.product-price {
  margin: 22px 0 0;
  font-family: 'DM Mono', monospace;
  font-size: 17px;
}

.product-price small {
  color: var(--store-muted);
  font-size: 9px;
}

.product-fit {
  margin: 18px 0 0;
  color: var(--store-muted);
  font-size: 12px;
  line-height: 1.6;
}

.product-options {
  margin-top: 28px;
}

.product-options-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
  color: var(--store-muted);
  font-size: 10px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.clear-all-options {
  padding: 0;
  border: 0;
  color: var(--store-muted);
  background: transparent;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

.clear-all-options:hover {
  color: var(--store-ink);
}

.product-options fieldset {
  margin: 0;
  padding: 20px 0;
  border: 0;
  border-top: 1px solid var(--store-line);
}

.product-options legend {
  width: 100%;
  display: flex;
  justify-content: space-between;
  margin-bottom: 12px;
  padding: 0;
  color: var(--store-muted);
  font-size: 11px;
}

.product-options legend strong {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--store-ink);
  font-weight: 600;
}

.option-group-clear {
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--store-muted);
  background: transparent;
  cursor: pointer;
}

.option-group-clear:hover {
  color: #fff;
  background: var(--store-ink);
}

.option-group-clear .iconify {
  width: 13px;
  height: 13px;
}

.option-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 7px;
}

.option-grid button {
  min-height: 42px;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px;
  border: 1px solid var(--store-line);
  color: var(--store-ink);
  background: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  overflow-wrap: anywhere;
  cursor: pointer;
}

.option-grid button.active {
  color: #fff;
  border-color: var(--store-ink);
  background: var(--store-ink);
}

.option-grid button:disabled {
  opacity: .35;
  cursor: not-allowed;
  text-decoration: line-through;
}

.option-swatch {
  width: 14px;
  height: 14px;
  flex: 0 0 14px;
  border: 1px solid currentColor;
  border-radius: 50%;
  background: #ddd;
}

.product-cart-row {
  display: grid;
  grid-template-columns: 108px 1fr;
  gap: 8px;
  margin-top: 8px;
}

.product-quantity {
  min-height: 52px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  align-items: center;
  border: 1px solid var(--store-ink);
}

.product-quantity button {
  height: 100%;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.product-quantity button:disabled {
  opacity: .3;
  cursor: not-allowed;
}

.product-quantity span {
  text-align: center;
  font-size: 11px;
}

.product-add-button {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 14px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  text-transform: uppercase;
  cursor: pointer;
}

.product-add-button:disabled {
  opacity: .45;
  cursor: not-allowed;
}

.stock-line,
.product-error {
  margin: 10px 0 0;
  color: var(--store-muted);
  font-size: 10px;
}

.product-error {
  display: flex;
  gap: 6px;
  color: #963f4f;
}

.product-story {
  padding: 96px 0 104px;
  background: var(--store-linen);
}

.story-grid {
  display: grid;
  grid-template-columns: 1.15fr .8fr .8fr;
  gap: clamp(36px, 6vw, 82px);
}

.story-grid h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(40px, 4.5vw, 64px);
  font-weight: 500;
  line-height: 1;
}

.story-grid p:not(.store-eyebrow),
.story-grid li {
  color: var(--store-muted);
  font-size: 12px;
  line-height: 1.7;
}

.story-grid ul {
  display: grid;
  gap: 7px;
  padding-left: 18px;
}

.attribute-list {
  margin: 0;
  border-top: 1px solid var(--store-line);
}

.attribute-list dt,
.attribute-list dd {
  min-height: 48px;
  display: flex;
  align-items: center;
  margin: 0;
  border-bottom: 1px solid var(--store-line);
  font-size: 11px;
}

.attribute-list dt {
  float: left;
  width: 52%;
  clear: left;
  color: var(--store-muted);
}

.attribute-list dd {
  justify-content: flex-end;
  text-align: right;
}

.care-list {
  display: grid;
  gap: 24px;
}

.care-list h3 {
  margin: 0;
  font-size: 12px;
}

.product-related {
  padding-top: 96px;
  padding-bottom: 116px;
}

.related-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 20px;
}

.related-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(38px, 4vw, 56px);
  font-weight: 500;
}

.related-heading a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: inherit;
  font-size: 10px;
  text-decoration: none;
}

.related-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-top: 36px;
}

.is-spinning {
  animation: spin .8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 900px) {
  .product-detail {
    grid-template-columns: 1fr;
  }

  .product-main-image {
    min-height: 0;
    aspect-ratio: .8;
  }

  .story-grid {
    grid-template-columns: 1fr 1fr;
  }

  .care-list {
    grid-column: 1 / -1;
  }
}

@media (max-width: 620px) {
  .product-detail {
    padding-bottom: 72px;
  }

  .product-gallery {
    grid-template-columns: 1fr;
  }

  .product-main-image {
    grid-row: 1;
  }

  .product-thumbnails {
    grid-row: 2;
    flex-direction: row;
    overflow-x: auto;
    overflow-y: hidden;
  }

  .product-thumbnails button {
    width: 72px;
    flex: 0 0 72px;
  }

  .option-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .story-grid,
  .related-grid {
    grid-template-columns: 1fr 1fr;
  }

  .story-grid > div:first-child,
  .attribute-list,
  .care-list {
    grid-column: 1 / -1;
  }

  .product-image-preview {
    padding: 16px;
  }

  .product-image-preview-panel {
    width: calc(100vw - 32px);
    max-height: calc(100vh - 32px);
    padding: 10px;
  }

  .product-image-preview-panel img {
    max-height: calc(100vh - 52px);
  }
}
</style>
