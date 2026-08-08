<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  catalogProducts,
  displayProductType,
  formatPrice,
  getCollection,
  getProductById
} from '~/data/catalog'

const route = useRoute()

const product = computed(() => getProductById(Number(route.params.id)))
const selectedImage = ref(0)
const selectedTopSize = ref('S')
const selectedBottomSize = ref('S')
const selectedSize = ref('M')
const quantity = ref(1)
const isFavorite = ref(false)
const isAdded = ref(false)
const activePanel = ref('highlights')
let feedbackTimer: ReturnType<typeof setTimeout> | undefined

const topSizeOptions = ['XS', 'S', 'M', 'L', 'XL']
const bottomSizeOptions = ['XS', 'S', 'M', 'L', 'XL', 'XXL']
const standardSizeOptions = ['XS', 'S', 'M', 'L', 'XL', 'XXL']

const mainImage = computed(() => product.value?.images[selectedImage.value] || product.value?.images[0] || '/lingerie/hero-corset.jpg')
const mainImagePosition = computed(() => product.value?.image_positions?.[selectedImage.value] || 'center')

const productCollection = computed(() => getCollection(product.value?.collections[0] || 'shop'))

const singleSizeOptions = computed(() => {
  if (product.value?.product_type === 'COVER_UP' && product.value.size === 'ONE_SIZE') return ['ONE_SIZE']
  return standardSizeOptions
})

const attributeRows = computed(() => {
  if (!product.value) return []

  if (product.value.product_type === 'ONE_PIECE') {
    return [
      { label: 'Support level', value: product.value.support_level },
      { label: 'Coverage', value: product.value.coverage },
      { label: 'Torso fit', value: product.value.torso_fit },
      { label: 'Neckline', value: product.value.neckline },
      { label: 'Back style', value: product.value.back_style },
      { label: 'Tummy control', value: product.value.tummy_control },
      { label: 'Padding', value: product.value.removable_padding }
    ].filter(row => row.value)
  }

  if (product.value.product_type === 'DRESS') {
    return [
      { label: 'Length', value: product.value.length },
      { label: 'Silhouette', value: product.value.silhouette },
      { label: 'Neckline', value: product.value.neckline },
      { label: 'Sleeve', value: product.value.sleeve_type },
      { label: 'Fabric', value: product.value.fabric }
    ].filter(row => row.value)
  }

  if (product.value.product_type === 'COVER_UP') {
    return [
      { label: 'Style', value: product.value.style },
      { label: 'Sheer level', value: product.value.sheer_level },
      { label: 'Fabric', value: product.value.fabric }
    ].filter(row => row.value)
  }

  return [
    { label: 'Top fit', value: product.value.top_size_recommendation },
    { label: 'Bottom fit', value: product.value.bottom_size_recommendation }
  ].filter(row => row.value)
})

const relatedProducts = computed(() => {
  if (!product.value) return []
  const collections = product.value.collections
  return catalogProducts
    .filter(item => item.id !== product.value?.id && item.collections.some(collection => collections.includes(collection)))
    .sort((a, b) => b.score - a.score)
    .slice(0, 4)
})

watch(product, value => {
  selectedImage.value = 0
  selectedTopSize.value = value?.top_size || 'S'
  selectedBottomSize.value = value?.bottom_size || 'S'
  selectedSize.value = value?.size || 'M'
  quantity.value = 1
  isAdded.value = false
}, { immediate: true })

function formatAttribute(value: string | undefined) {
  if (!value) return '—'
  return value
    .toLocaleLowerCase()
    .replaceAll('_', ' ')
    .replace(/\b\w/g, letter => letter.toLocaleUpperCase())
}

function changeQuantity(delta: number) {
  quantity.value = Math.min(8, Math.max(1, quantity.value + delta))
}

function addToBag() {
  isAdded.value = true
  if (feedbackTimer) clearTimeout(feedbackTimer)
  feedbackTimer = setTimeout(() => {
    isAdded.value = false
  }, 2400)
}

onBeforeUnmount(() => {
  if (feedbackTimer) clearTimeout(feedbackTimer)
})

useHead(() => ({
  title: product.value ? `${product.value.name} | LUNE` : 'Product not found | LUNE',
  meta: [
    {
      name: 'description',
      content: product.value?.description || 'Explore the Lune lingerie collection.'
    }
  ]
}))
</script>

<template>
  <main class="store-page">
    <StoreHeader />

    <template v-if="product">
      <div class="store-container product-breadcrumb">
        <NuxtLink to="/">Home</NuxtLink>
        <UIcon name="i-lucide-chevron-right" />
        <NuxtLink :to="`/collections/${productCollection.slug}`">{{ productCollection.englishLabel }}</NuxtLink>
        <UIcon name="i-lucide-chevron-right" />
        <span>{{ product.name }}</span>
      </div>

      <section class="product-detail store-container">
        <div class="product-gallery">
          <div class="product-thumbnails" aria-label="Product images">
            <button
              v-for="(image, index) in product.images"
              :key="`${image}-${index}`"
              type="button"
              :class="{ active: selectedImage === index }"
              :aria-label="`View image ${index + 1}`"
              @click="selectedImage = index"
            >
              <img :src="image" alt="" :style="{ objectPosition: product.image_positions?.[index] || 'center' }">
            </button>
          </div>

          <div class="product-main-image">
            <img :src="mainImage" :alt="product.name" :style="{ objectPosition: mainImagePosition }">
            <span v-if="product.badge" :class="{ sale: product.is_sale }">{{ product.badge }}</span>
            <button type="button" aria-label="Zoom product image"><UIcon name="i-lucide-expand" /></button>
          </div>
        </div>

        <aside class="product-purchase">
          <div class="product-type-line">
            <span>{{ displayProductType(product.product_type) }}</span>
            <span>SKU {{ String(product.id).padStart(4, '0') }}</span>
          </div>

          <div class="product-title-row">
            <h1>{{ product.name }}</h1>
            <button
              type="button"
              :class="{ active: isFavorite }"
              :aria-label="isFavorite ? 'Remove from favorites' : 'Add to favorites'"
              @click="isFavorite = !isFavorite"
            >
              <UIcon name="i-lucide-heart" />
            </button>
          </div>

          <div class="product-price-row">
            <strong :class="{ sale: product.is_sale }">{{ formatPrice(product.price) }}</strong>
            <del v-if="product.compare_at_price">{{ formatPrice(product.compare_at_price) }}</del>
            <span v-if="product.is_sale">Final sale</span>
          </div>

          <a class="product-rating" href="#product-details">
            <span class="product-stars">
              <UIcon
                v-for="index in 5"
                :key="index"
                name="i-lucide-star"
                :class="{ filled: index <= Math.round(product.score) }"
              />
            </span>
            {{ product.score.toFixed(1) }} · {{ Math.max(18, Math.round(product.sales_volume / 3)) }} reviews
          </a>

          <div class="product-color">
            <div><span>Color</span><strong>{{ product.color }}</strong></div>
            <span class="product-swatch" :class="`swatch-${product.id % 6}`" />
          </div>

          <div v-if="product.product_type === 'BIKINI'" class="product-size-groups">
            <div class="product-size-group">
              <div class="product-option-heading">
                <span>Top size: <strong>{{ selectedTopSize }}</strong></span>
                <button type="button"><UIcon name="i-lucide-ruler" /> Size guide</button>
              </div>
              <div class="product-size-options">
                <button
                  v-for="size in topSizeOptions"
                  :key="`top-${size}`"
                  type="button"
                  :class="{ active: selectedTopSize === size }"
                  @click="selectedTopSize = size"
                >
                  {{ size }}
                </button>
              </div>
              <small>{{ product.top_size_recommendation }}</small>
            </div>

            <div class="product-size-group">
              <div class="product-option-heading">
                <span>Bottom size: <strong>{{ selectedBottomSize }}</strong></span>
              </div>
              <div class="product-size-options">
                <button
                  v-for="size in bottomSizeOptions"
                  :key="`bottom-${size}`"
                  type="button"
                  :class="{ active: selectedBottomSize === size }"
                  @click="selectedBottomSize = size"
                >
                  {{ size }}
                </button>
              </div>
              <small>{{ product.bottom_size_recommendation }}</small>
            </div>
          </div>

          <div v-else class="product-size-group product-single-size">
            <div class="product-option-heading">
              <span>Size: <strong>{{ formatAttribute(selectedSize) }}</strong></span>
              <button type="button"><UIcon name="i-lucide-ruler" /> Size guide</button>
            </div>
            <div class="product-size-options">
              <button
                v-for="size in singleSizeOptions"
                :key="size"
                type="button"
                :class="{ active: selectedSize === size }"
                @click="selectedSize = size"
              >
                {{ size === 'ONE_SIZE' ? 'One size' : size }}
              </button>
            </div>
            <small>{{ product.size_recommendation }}</small>
          </div>

          <div class="product-cart-row">
            <div class="product-quantity">
              <button type="button" aria-label="Decrease quantity" @click="changeQuantity(-1)"><UIcon name="i-lucide-minus" /></button>
              <span>{{ quantity }}</span>
              <button type="button" aria-label="Increase quantity" @click="changeQuantity(1)"><UIcon name="i-lucide-plus" /></button>
            </div>
            <button class="product-add-button" type="button" @click="addToBag">
              <template v-if="isAdded"><UIcon name="i-lucide-check" /> Added to bag</template>
              <template v-else>Add to bag · {{ formatPrice(product.price * quantity) }}</template>
            </button>
          </div>

          <p v-if="product.warehouse_volume <= 15" class="product-low-stock">
            <span /> Only {{ product.warehouse_volume }} left in this color
          </p>

          <div class="product-service-notes">
            <div><UIcon name="i-lucide-truck" /><span><strong>Complimentary shipping</strong>On orders over $79</span></div>
            <div><UIcon name="i-lucide-refresh-ccw" /><span><strong>30-day returns</strong>Try it in your own space</span></div>
          </div>
        </aside>
      </section>

      <section id="product-details" class="product-story">
        <div class="store-container product-story-grid">
          <div class="product-story-intro">
            <p class="store-eyebrow">WHY YOU'LL LOVE IT</p>
            <h2>Made to feel like you.</h2>
            <p>{{ product.description }}</p>
            <blockquote v-if="product.fit_sense">“{{ product.fit_sense }}”</blockquote>
          </div>

          <div class="product-attributes">
            <div class="product-attribute-title">
              <span>Product type</span>
              <strong>{{ displayProductType(product.product_type) }}</strong>
            </div>
            <div v-for="row in attributeRows" :key="row.label" class="product-attribute-row">
              <span>{{ row.label }}</span>
              <strong>{{ formatAttribute(row.value) }}</strong>
            </div>
          </div>

          <div class="product-accordions">
            <div class="product-accordion">
              <button type="button" @click="activePanel = activePanel === 'highlights' ? '' : 'highlights'">
                Highlights <UIcon :name="activePanel === 'highlights' ? 'i-lucide-minus' : 'i-lucide-plus'" />
              </button>
              <ul v-if="activePanel === 'highlights'">
                <li v-for="item in product.highlight" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div class="product-accordion">
              <button type="button" @click="activePanel = activePanel === 'design' ? '' : 'design'">
                Design &amp; extras <UIcon :name="activePanel === 'design' ? 'i-lucide-minus' : 'i-lucide-plus'" />
              </button>
              <ul v-if="activePanel === 'design'">
                <li v-for="item in product.design_and_extras" :key="item">{{ item }}</li>
              </ul>
            </div>
            <div class="product-accordion">
              <button type="button" @click="activePanel = activePanel === 'care' ? '' : 'care'">
                Care instructions <UIcon :name="activePanel === 'care' ? 'i-lucide-minus' : 'i-lucide-plus'" />
              </button>
              <ul v-if="activePanel === 'care'">
                <li v-for="item in product.care_instructions" :key="item">{{ item }}</li>
              </ul>
            </div>
          </div>
        </div>
      </section>

      <section class="product-related store-container">
        <div class="product-related-heading">
          <div>
            <p class="store-eyebrow">YOU MAY ALSO LIKE</p>
            <h2>Complete the mood.</h2>
          </div>
          <NuxtLink :to="`/collections/${productCollection.slug}`">View the edit <UIcon name="i-lucide-arrow-right" /></NuxtLink>
        </div>
        <div class="product-related-grid">
          <ProductCard v-for="item in relatedProducts" :key="item.id" :product="item" />
        </div>
      </section>
    </template>

    <section v-else class="product-not-found store-container">
      <UIcon name="i-lucide-package-x" />
      <p class="store-eyebrow">PRODUCT NOT FOUND</p>
      <h1>This piece has slipped away.</h1>
      <p>The product ID does not exist in the current catalog.</p>
      <NuxtLink class="store-button" to="/collections/shop">Back to the collection</NuxtLink>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.product-breadcrumb {
  min-height: 56px;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .04em;
  text-transform: uppercase;
}

.product-breadcrumb a {
  color: inherit;
  text-decoration: none;
}

.product-breadcrumb a:hover {
  color: var(--store-wine);
}

.product-breadcrumb .iconify {
  width: 11px;
  height: 11px;
}

.product-breadcrumb span {
  overflow: hidden;
  max-width: 320px;
  color: var(--store-ink);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-detail {
  display: grid;
  grid-template-columns: minmax(0, 1.36fr) minmax(390px, .64fr);
  gap: clamp(36px, 5vw, 82px);
  padding-bottom: 112px;
}

.product-gallery {
  min-width: 0;
  display: grid;
  grid-template-columns: 94px minmax(0, 1fr);
  gap: 13px;
}

.product-thumbnails {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.product-thumbnails button {
  overflow: hidden;
  aspect-ratio: .78;
  padding: 0;
  border: 1px solid transparent;
  background: var(--store-linen);
  cursor: pointer;
}

.product-thumbnails button.active {
  border-color: var(--store-ink);
}

.product-thumbnails img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.product-main-image {
  position: relative;
  overflow: hidden;
  min-height: 690px;
  background: #d9ced0;
}

.product-main-image > img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.product-main-image > span {
  position: absolute;
  top: 15px;
  left: 15px;
  padding: 7px 9px;
  color: #fff;
  background: var(--store-plum);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .08em;
}

.product-main-image > span.sale {
  background: var(--store-wine);
}

.product-main-image > button {
  position: absolute;
  right: 14px;
  bottom: 14px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .92);
  cursor: pointer;
}

.product-purchase {
  align-self: start;
  padding-top: 15px;
}

.product-type-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.product-title-row {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  margin-top: 15px;
}

.product-title-row h1 {
  flex: 1;
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(40px, 4vw, 57px);
  font-weight: 500;
  letter-spacing: -.025em;
  line-height: 1;
}

.product-title-row button {
  width: 41px;
  height: 41px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  padding: 0;
  border: 1px solid var(--store-line);
  border-radius: 50%;
  color: var(--store-ink);
  background: transparent;
  cursor: pointer;
}

.product-title-row button.active {
  color: #fff;
  border-color: var(--store-wine);
  background: var(--store-wine);
}

.product-title-row button.active .iconify {
  fill: currentColor;
}

.product-price-row {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 20px;
  font-family: 'DM Mono', monospace;
}

.product-price-row strong {
  font-size: 16px;
  font-weight: 500;
}

.product-price-row strong.sale {
  color: var(--store-wine);
}

.product-price-row del {
  color: #96898e;
  font-size: 11px;
}

.product-price-row > span {
  padding: 4px 6px;
  color: #fff;
  background: var(--store-wine);
  font-size: 7px;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.product-rating {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-top: 13px;
  color: var(--store-muted);
  font-size: 10px;
  text-decoration: none;
}

.product-stars {
  display: flex;
  gap: 2px;
  color: var(--store-wine);
}

.product-stars .iconify {
  width: 12px;
  height: 12px;
}

.product-stars .filled {
  fill: currentColor;
}

.product-color {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  margin-top: 29px;
  padding: 19px 0;
  border-top: 1px solid var(--store-line);
}

.product-color div {
  display: flex;
  gap: 7px;
  font-size: 12px;
}

.product-color div span {
  color: var(--store-muted);
}

.product-swatch {
  width: 28px;
  height: 28px;
  display: block;
  border: 3px solid #fff;
  border-radius: 50%;
  outline: 1px solid var(--store-ink);
  background: #4b3039;
}

.swatch-0 { background: #d6c4aa; }
.swatch-1 { background: #3b252e; }
.swatch-2 { background: #783b4a; }
.swatch-3 { background: #d8cfbf; }
.swatch-4 { background: #6e8174; }
.swatch-5 { background: #252328; }

.product-size-groups {
  display: flex;
  flex-direction: column;
  gap: 25px;
}

.product-size-group {
  padding-top: 20px;
  border-top: 1px solid var(--store-line);
}

.product-single-size {
  margin-top: 0;
}

.product-option-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 11px;
}

.product-option-heading > span {
  color: var(--store-muted);
}

.product-option-heading strong {
  color: var(--store-ink);
  font-weight: 600;
}

.product-option-heading button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0 0 2px;
  border: 0;
  border-bottom: 1px solid currentColor;
  color: var(--store-ink);
  background: transparent;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .04em;
  text-transform: uppercase;
  cursor: pointer;
}

.product-option-heading button .iconify {
  width: 12px;
  height: 12px;
}

.product-size-options {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 7px;
}

.product-size-options button {
  min-width: 0;
  min-height: 43px;
  padding: 0 4px;
  border: 1px solid var(--store-line);
  color: var(--store-ink);
  background: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  cursor: pointer;
}

.product-size-options button:hover,
.product-size-options button.active {
  color: #fff;
  border-color: var(--store-ink);
  background: var(--store-ink);
}

.product-size-group small {
  display: block;
  margin-top: 9px;
  color: var(--store-muted);
  font-size: 10px;
}

.product-cart-row {
  display: grid;
  grid-template-columns: 108px 1fr;
  gap: 8px;
  margin-top: 27px;
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

.product-quantity button .iconify {
  width: 13px;
  height: 13px;
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
  gap: 9px;
  padding: 0 15px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .065em;
  text-transform: uppercase;
  cursor: pointer;
  transition: .2s ease;
}

.product-add-button:hover {
  color: var(--store-ink);
  background: transparent;
}

.product-low-stock {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 11px 0 0;
  color: var(--store-wine);
  font-size: 10px;
}

.product-low-stock span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.product-service-notes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 27px;
  padding-top: 20px;
  border-top: 1px solid var(--store-line);
}

.product-service-notes > div {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.product-service-notes .iconify {
  width: 17px;
  height: 17px;
  flex: 0 0 auto;
  color: var(--store-plum);
}

.product-service-notes span {
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: var(--store-muted);
  font-size: 9px;
}

.product-service-notes strong {
  color: var(--store-ink);
  font-size: 10px;
}

.product-story {
  padding: 105px 0 112px;
  background: var(--store-linen);
}

.product-story-grid {
  display: grid;
  grid-template-columns: 1.05fr .82fr .95fr;
  gap: clamp(34px, 5vw, 78px);
}

.product-story-intro h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(44px, 4.7vw, 68px);
  font-weight: 500;
  line-height: 1;
}

.product-story-intro > p:not(.store-eyebrow) {
  max-width: 490px;
  margin: 23px 0 0;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.7;
}

.product-story-intro blockquote {
  margin: 28px 0 0;
  padding-left: 18px;
  border-left: 2px solid var(--store-wine);
  color: #5d464e;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 18px;
  font-style: italic;
  line-height: 1.45;
}

.product-attributes {
  border-top: 1px solid var(--store-line);
}

.product-attribute-title,
.product-attribute-row {
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 11px 0;
  border-bottom: 1px solid var(--store-line);
  font-size: 11px;
}

.product-attribute-title span,
.product-attribute-row span {
  color: var(--store-muted);
}

.product-attribute-title strong,
.product-attribute-row strong {
  max-width: 58%;
  text-align: right;
  font-size: 11px;
  font-weight: 600;
}

.product-accordions {
  border-top: 1px solid var(--store-line);
}

.product-accordion {
  border-bottom: 1px solid var(--store-line);
}

.product-accordion > button {
  width: 100%;
  min-height: 54px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0;
  border: 0;
  color: var(--store-ink);
  background: transparent;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}

.product-accordion > button .iconify {
  width: 15px;
  height: 15px;
}

.product-accordion ul {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0 0 22px 17px;
  color: var(--store-muted);
  font-size: 11px;
  line-height: 1.5;
}

.product-related {
  padding-top: 105px;
  padding-bottom: 126px;
}

.product-related-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
}

.product-related-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(42px, 4.4vw, 62px);
  font-weight: 500;
}

.product-related-heading > a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 3px;
  border-bottom: 1px solid currentColor;
  color: inherit;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-decoration: none;
  text-transform: uppercase;
}

.product-related-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
  margin-top: 40px;
}

.product-not-found {
  min-height: 650px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.product-not-found > .iconify {
  width: 50px;
  height: 50px;
  margin-bottom: 22px;
  color: var(--store-wine);
}

.product-not-found h1 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(44px, 5vw, 70px);
  font-weight: 500;
}

.product-not-found > p:not(.store-eyebrow) {
  margin: 15px 0 24px;
  color: var(--store-muted);
  font-size: 13px;
}

@media (max-width: 1080px) {
  .product-detail {
    grid-template-columns: minmax(0, 1.15fr) minmax(350px, .85fr);
    gap: 34px;
  }

  .product-gallery {
    grid-template-columns: 76px minmax(0, 1fr);
  }

  .product-story-grid {
    grid-template-columns: 1fr 1fr;
  }

  .product-accordions {
    grid-column: 1 / -1;
  }
}

@media (max-width: 820px) {
  .product-breadcrumb {
    min-height: 50px;
  }

  .product-detail {
    grid-template-columns: 1fr;
    gap: 42px;
    padding-bottom: 82px;
  }

  .product-gallery {
    grid-template-columns: 1fr;
  }

  .product-main-image {
    min-height: auto;
    aspect-ratio: .84;
    grid-row: 1;
  }

  .product-thumbnails {
    grid-row: 2;
    display: grid;
    grid-template-columns: repeat(3, 82px);
    overflow-x: auto;
  }

  .product-purchase {
    padding-top: 0;
  }

  .product-story {
    padding: 78px 0 84px;
  }

  .product-story-grid {
    grid-template-columns: 1fr;
    gap: 42px;
  }

  .product-accordions {
    grid-column: auto;
  }

  .product-related {
    padding-top: 78px;
    padding-bottom: 90px;
  }

  .product-related-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 35px 12px;
    margin-top: 30px;
  }
}

@media (max-width: 490px) {
  .product-breadcrumb span {
    max-width: 150px;
  }

  .product-title-row h1 {
    font-size: 40px;
  }

  .product-size-options {
    grid-template-columns: repeat(5, 1fr);
  }

  .product-cart-row {
    grid-template-columns: 94px 1fr;
  }

  .product-service-notes {
    grid-template-columns: 1fr;
  }

  .product-related-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .product-related-grid {
    gap: 30px 9px;
  }
}
</style>
