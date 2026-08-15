<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { displayProductType } from '~/data/catalog'

const route = useRoute()
const catalogApi = useCatalogApi()

const activeSlug = computed(() => String(route.params.slug || 'shop'))
const isShopCollection = computed(() => activeSlug.value === 'shop')
const productType = ref<string>('ALL')
const sortBy = ref('featured')

const {
  data: catalog,
  status: catalogRequestStatus,
  error: catalogRequestError,
  refresh: refreshCatalog
} = await useAsyncData(
  'collection-catalog',
  async () => {
    const [categories, products] = await Promise.all([
      catalogApi.listCategories(),
      catalogApi.listProducts()
    ])
    return { categories, products }
  },
  { default: () => ({ categories: [], products: [] }) }
)

const categories = computed(() => catalog.value.categories)
const products = computed(() => catalog.value.products)
const activeCategory = computed(() => categories.value.find(category => category.code === activeSlug.value) ?? null)
const collectionNotFound = computed(() =>
  catalogRequestStatus.value === 'success' && !isShopCollection.value && !activeCategory.value
)

const activeCategoryIds = computed(() => {
  if (isShopCollection.value || !activeCategory.value) return new Set<number>()

  const ids = new Set([activeCategory.value.id])
  let added = true
  while (added) {
    added = false
    categories.value.forEach(category => {
      if (category.parent_id !== null && ids.has(category.parent_id) && !ids.has(category.id)) {
        ids.add(category.id)
        added = true
      }
    })
  }
  return ids
})

const collectionProducts = computed(() => products.value.filter(product => {
  if (product.status !== 'ACTIVE') return false
  const categoryId = product.category_id
  return isShopCollection.value || (categoryId != null && activeCategoryIds.value.has(categoryId))
}))

const activeCollection = computed(() => {
  const category = activeCategory.value
  const image = collectionProducts.value.find(product => product.images[0])?.images[0] ?? '/lingerie/hero-corset.jpg'
  if (isShopCollection.value) {
    return {
      label: 'All products',
      eyebrow: 'THE PELISSA COLLECTION',
      title: 'Every layer,\nevery mood.',
      subtitle: 'Modern lingerie for the way you move through the day.',
      description: 'Discover every active piece in the collection.',
      image
    }
  }
  return {
    label: category?.name ?? 'Collection',
    eyebrow: 'THE PELISSA COLLECTION',
    title: category?.name ?? 'Collection not found',
    subtitle: category ? `Explore the ${category.name} collection.` : '',
    description: category ? `Browse every active ${category.name} piece, selected directly from our catalog.` : '',
    image
  }
})

const visibleProducts = computed(() => {
  let result = collectionProducts.value

  if (productType.value !== 'ALL') {
    result = result.filter(product => product.product_type === productType.value)
  }

  const sorted = [...result]
  if (sortBy.value === 'newest') sorted.sort((a, b) => b.created_at.localeCompare(a.created_at))
  if (sortBy.value === 'price-low') sorted.sort((a, b) => a.price - b.price)
  if (sortBy.value === 'price-high') sorted.sort((a, b) => b.price - a.price)
  if (sortBy.value === 'best-selling') sorted.sort((a, b) => b.sales_volume - a.sales_volume)
  return sorted
})

const availableTypes = computed(() => [...new Set(collectionProducts.value.map(product => product.product_type))].sort())

watch(activeSlug, () => {
  productType.value = 'ALL'
  sortBy.value = 'featured'
})

function selectProductType(type: string) {
  productType.value = type
}

useHead(() => ({
  title: `${activeCollection.value.label} | Pelissa`,
  meta: [
    { name: 'description', content: activeCollection.value.description }
  ]
}))
</script>

<template>
  <main class="store-page">
    <StoreHeader />

    <section v-if="collectionNotFound" class="collection-empty collection-missing" role="alert">
      <UIcon class="collection-empty-icon" name="i-lucide-folder-x" />
      <h1>Collection not found.</h1>
      <p>This category is not available in the current catalog.</p>
      <NuxtLink class="store-button" to="/collections/shop">View all products</NuxtLink>
    </section>

    <template v-else>
      <section class="collection-hero">
        <div class="collection-hero-copy">
          <div>
            <div class="collection-hero-kicker">
              <p class="store-eyebrow">{{ activeCollection.eyebrow }}</p>
              <span>{{ catalogRequestStatus === 'pending' ? 'Curating' : `${collectionProducts.length} pieces` }}</span>
            </div>
            <h1>{{ activeCollection.title }}</h1>
            <p class="collection-hero-subtitle">{{ activeCollection.subtitle }}</p>
            <p class="collection-hero-description">{{ activeCollection.description }}</p>
            <div class="collection-hero-actions">
              <a href="#collection-products" class="collection-scroll-link collection-scroll-link-primary">
                Explore the edit <UIcon class="collection-scroll-icon" name="i-lucide-arrow-down" />
              </a>
              <NuxtLink v-if="!isShopCollection" to="/collections/shop" class="collection-scroll-link">
                Shop all <UIcon class="collection-scroll-icon" name="i-lucide-arrow-up-right" />
              </NuxtLink>
            </div>
          </div>
        </div>
        <div class="collection-hero-image-wrap">
          <div class="collection-hero-image">
            <img :src="activeCollection.image" :alt="activeCollection.label">
            <div class="collection-hero-image-caption">
              <span>{{ activeCollection.label }}</span>
              <small>Pelissa editorial selection</small>
            </div>
          </div>
        </div>
      </section>

      <section id="collection-products" class="collection-products store-container">
        <div class="collection-heading">
          <div class="collection-heading-title">
            <p class="store-eyebrow">CURATED FOR YOU</p>
            <div>
              <h2>{{ activeCollection.label }}</h2>
              <span>{{ catalogRequestStatus === 'pending' ? 'Loading pieces' : `${visibleProducts.length} pieces` }}</span>
            </div>
          </div>
          <p>Every piece is designed around soft structure, thoughtful detail, and an easy sense of confidence.</p>
        </div>

        <div class="collection-toolbar">
          <div class="collection-type-filter" aria-label="Filter by product type">
            <button
              type="button"
              :class="{ active: productType === 'ALL' }"
              :aria-pressed="productType === 'ALL'"
              @click="selectProductType('ALL')"
            >
              All types
            </button>
            <button
              v-for="type in availableTypes"
              :key="type"
              type="button"
              :class="{ active: productType === type }"
              :aria-pressed="productType === type"
              @click="selectProductType(type)"
            >
              {{ displayProductType(type) }}
            </button>
          </div>

          <label class="collection-sort">
            <span>Sort by</span>
            <select v-model="sortBy">
              <option value="featured">Featured</option>
              <option value="newest">Newest</option>
              <option value="best-selling">Best selling</option>
              <option value="price-low">Price: low to high</option>
              <option value="price-high">Price: high to low</option>
            </select>
            <UIcon class="collection-sort-icon" name="i-lucide-chevron-down" />
          </label>
        </div>

        <div
          v-if="catalogRequestStatus === 'pending'"
          class="collection-product-grid"
          aria-label="Loading products"
          aria-busy="true"
        >
          <article v-for="index in 4" :key="index" class="collection-product-skeleton" aria-hidden="true">
            <div class="collection-product-skeleton-media" />
            <span />
            <small />
          </article>
        </div>

        <div v-else-if="catalogRequestError" class="collection-empty" role="alert">
          <UIcon class="collection-empty-icon" name="i-lucide-cloud-alert" />
          <h3>We could not load this edit.</h3>
          <p>The catalog service is unavailable right now. Please try again.</p>
          <button class="store-button" type="button" @click="refreshCatalog()">Try again</button>
        </div>

        <div v-else-if="visibleProducts.length" class="collection-product-grid">
          <ProductCard
            v-for="(product, index) in visibleProducts"
            :key="product.id"
            :product="product"
            :eager="index < 4"
          />
        </div>

        <div v-else class="collection-empty">
          <UIcon class="collection-empty-icon" name="i-lucide-search-x" />
          <h3>No pieces in this edit yet.</h3>
          <p>Try another product type or explore the full Pelissa collection.</p>
          <button v-if="productType !== 'ALL'" class="store-button" type="button" @click="selectProductType('ALL')">Clear filter</button>
        </div>
      </section>

      <section class="collection-note">
        <div class="collection-note-image">
          <img src="/lingerie/lace-texture.jpg" alt="Pelissa lace and fabric detail">
        </div>
        <div class="collection-note-copy">
          <p class="store-eyebrow">FIT, FEEL, REPEAT</p>
          <h2>The details make the difference.</h2>
          <p>Each product page translates the backend item type into the fit details that matter—from support and coverage to fabric, length, and sheerness.</p>
          <NuxtLink to="/product/1">See a product story <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
        </div>
      </section>
    </template>

    <StoreFooter />
  </main>
</template>

<style scoped>
.collection-hero {
  position: relative;
  min-height: 650px;
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr);
  overflow: hidden;
  background:
    radial-gradient(circle at 8% 14%, rgba(154, 64, 85, .11), transparent 28%),
    linear-gradient(135deg, #f6efed 0%, var(--store-linen) 58%, #eadbdc 100%);
  isolation: isolate;
}

.collection-hero::before {
  position: absolute;
  z-index: -1;
  width: min(34vw, 480px);
  aspect-ratio: 1;
  left: clamp(-190px, -11vw, -80px);
  bottom: -52%;
  border: 1px solid rgba(154, 64, 85, .14);
  border-radius: 50%;
  box-shadow: 0 0 0 42px rgba(255, 255, 255, .14), 0 0 0 84px rgba(154, 64, 85, .035);
  content: '';
}

.collection-hero-copy {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 96px clamp(40px, 6vw, 112px);
}

.collection-hero-copy::after {
  position: absolute;
  top: 52px;
  bottom: 52px;
  right: 0;
  width: 1px;
  background: linear-gradient(transparent, rgba(36, 29, 33, .16) 18%, rgba(36, 29, 33, .16) 82%, transparent);
  content: '';
}

.collection-hero-copy > div {
  width: 100%;
  max-width: 530px;
}

.collection-hero-kicker {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 23px;
}

.collection-hero-kicker .store-eyebrow {
  margin: 0;
}

.collection-hero-kicker > span {
  flex: 0 0 auto;
  padding: 7px 10px;
  border: 1px solid rgba(117, 99, 106, .28);
  border-radius: 999px;
  color: var(--store-muted);
  background: rgba(255, 255, 255, .36);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .075em;
  text-transform: uppercase;
}

.collection-hero h1 {
  max-width: 640px;
  margin: 0;
  white-space: pre-line;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(58px, 6.2vw, 94px);
  font-weight: 500;
  letter-spacing: -.045em;
  line-height: .91;
  text-wrap: balance;
}

.collection-hero-subtitle {
  max-width: 430px;
  margin: 31px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(19px, 1.6vw, 23px);
  font-style: italic;
  line-height: 1.35;
}

.collection-hero-description {
  max-width: 430px;
  margin: 15px 0 29px;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.75;
}

.collection-hero-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 24px;
}

.collection-scroll-link {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 0 4px;
  border-bottom: 1px solid currentColor;
  color: inherit;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .07em;
  text-decoration: none;
  text-transform: uppercase;
  transition: color .2s ease, transform .2s ease;
}

.collection-scroll-link:hover {
  color: var(--store-wine);
  transform: translateY(-2px);
}

.collection-scroll-link-primary {
  min-width: 174px;
  padding: 0 18px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
}

.collection-scroll-link-primary:hover {
  color: #fff;
  background: var(--store-wine-dark);
  border-color: var(--store-wine-dark);
}

.collection-scroll-icon {
  width: 14px;
  height: 14px;
}

.collection-hero-image-wrap {
  min-width: 0;
  display: flex;
  padding: 28px 28px 28px 0;
}

.collection-hero-image {
  position: relative;
  min-height: 594px;
  flex: 1;
  overflow: hidden;
  border-radius: 210px 3px 3px 3px;
  background: #d9ced0;
  box-shadow: 0 24px 70px rgba(70, 43, 54, .18);
}

.collection-hero-image::before,
.collection-hero-image::after {
  position: absolute;
  z-index: 1;
  content: '';
  pointer-events: none;
}

.collection-hero-image::before {
  inset: 13px;
  border: 1px solid rgba(255, 255, 255, .42);
  border-radius: 198px 1px 1px 1px;
}

.collection-hero-image::after {
  inset: 0;
  background: linear-gradient(180deg, rgba(30, 17, 23, .02) 42%, rgba(30, 17, 23, .48));
}

.collection-hero-image img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: transform .8s cubic-bezier(.2, .6, .2, 1);
}

.collection-hero-image:hover img {
  transform: scale(1.025);
}

.collection-hero-image-caption {
  position: absolute;
  z-index: 2;
  right: 36px;
  bottom: 31px;
  left: 36px;
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
  color: #fff;
}

.collection-hero-image-caption span {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(24px, 2.2vw, 34px);
  line-height: 1;
}

.collection-hero-image-caption small {
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .08em;
  text-align: right;
  text-transform: uppercase;
}

.collection-products {
  position: relative;
  padding-top: 112px;
  padding-bottom: 132px;
}

.collection-products::before {
  position: absolute;
  top: 0;
  left: 50%;
  width: min(100vw - 64px, 1440px);
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--store-line) 12%, var(--store-line) 88%, transparent);
  content: '';
  transform: translateX(-50%);
}

.collection-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 56px;
}

.collection-heading-title > .store-eyebrow {
  margin-bottom: 17px;
}

.collection-heading-title > div {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 12px 16px;
}

.collection-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(42px, 4.4vw, 66px);
  font-weight: 500;
  letter-spacing: -.025em;
  line-height: .95;
}

.collection-heading span {
  padding: 6px 9px;
  border-radius: 999px;
  color: var(--store-wine-dark);
  background: rgba(154, 64, 85, .09);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-transform: uppercase;
}

.collection-heading > p {
  max-width: 430px;
  margin: 0;
  padding-left: 22px;
  border-left: 1px solid var(--store-line);
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.75;
}

.collection-toolbar {
  min-height: 68px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 46px;
  padding: 10px 14px;
  border: 1px solid rgba(36, 29, 33, .12);
  background: rgba(255, 255, 255, .68);
  box-shadow: 0 12px 35px rgba(43, 29, 35, .055);
  backdrop-filter: blur(12px);
}

.collection-type-filter {
  display: flex;
  align-items: center;
  gap: 7px;
  overflow-x: auto;
  scrollbar-width: none;
}

.collection-type-filter::-webkit-scrollbar {
  display: none;
}

.collection-type-filter button {
  min-height: 36px;
  flex: 0 0 auto;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 999px;
  color: var(--store-muted);
  background: transparent;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-transform: uppercase;
  cursor: pointer;
  transition: border-color .2s ease, color .2s ease, background .2s ease, transform .2s ease;
}

.collection-type-filter button:hover {
  border-color: rgba(36, 29, 33, .26);
  color: var(--store-ink);
  background: #fff;
  transform: translateY(-1px);
}

.collection-type-filter button.active {
  border-color: var(--store-ink);
  color: #fff;
  background: var(--store-ink);
}

.collection-sort {
  position: relative;
  min-width: 210px;
  min-height: 38px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex: 0 0 auto;
  padding-left: 18px;
  border-left: 1px solid var(--store-line);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-transform: uppercase;
}

.collection-sort span {
  color: var(--store-muted);
}

.collection-sort select {
  flex: 1;
  min-width: 0;
  padding: 4px 22px 4px 0;
  border: 0;
  outline: 0;
  appearance: none;
  color: var(--store-ink);
  background: transparent;
  font-size: inherit;
  font-weight: 500;
  text-transform: uppercase;
  cursor: pointer;
}

.collection-sort-icon {
  position: absolute;
  right: 0;
  width: 13px;
  height: 13px;
  pointer-events: none;
}

.collection-product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 52px 20px;
  margin-top: 46px;
}

.collection-product-skeleton {
  min-width: 0;
}

.collection-product-skeleton-media {
  aspect-ratio: .785;
  overflow: hidden;
  border-radius: 2px;
  background: linear-gradient(110deg, #eee7e9 8%, #f8f2f1 18%, #eee7e9 33%);
  background-size: 220% 100%;
  animation: collection-skeleton-pulse 1.25s linear infinite;
}

.collection-product-skeleton span,
.collection-product-skeleton small {
  width: 72%;
  height: 11px;
  display: block;
  margin-top: 14px;
  background: #eee7e9;
}

.collection-product-skeleton small {
  width: 42%;
  height: 8px;
  margin-top: 9px;
}

@keyframes collection-skeleton-pulse {
  to { background-position-x: -220%; }
}

.collection-empty {
  min-height: 360px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 50px;
  text-align: center;
}

.collection-missing {
  min-height: 560px;
  background:
    radial-gradient(circle at 50% 45%, rgba(255, 255, 255, .72), transparent 28%),
    var(--store-linen);
}

.collection-empty-icon {
  width: 32px;
  height: 32px;
  color: var(--store-plum);
}

.collection-empty h1,
.collection-empty h3 {
  margin: 18px 0 8px;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(32px, 4vw, 48px);
  font-weight: 500;
  letter-spacing: -.02em;
}

.collection-empty p {
  margin: 0 0 22px;
  color: var(--store-muted);
  font-size: 13px;
}

.collection-note {
  position: relative;
  display: grid;
  grid-template-columns: 1.08fr .92fr;
  overflow: hidden;
  background: linear-gradient(135deg, #e7c8c7, var(--store-blush));
}

.collection-note::after {
  position: absolute;
  width: 320px;
  height: 320px;
  right: -145px;
  bottom: -180px;
  border: 1px solid rgba(117, 48, 67, .18);
  border-radius: 50%;
  content: '';
}

.collection-note-image {
  position: relative;
  min-height: 540px;
  overflow: hidden;
}

.collection-note-image::after {
  position: absolute;
  inset: 18px;
  border: 1px solid rgba(255, 255, 255, .42);
  content: '';
  pointer-events: none;
}

.collection-note-image img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  object-position: center 47%;
  transition: transform .8s cubic-bezier(.2, .6, .2, 1);
}

.collection-note-image:hover img {
  transform: scale(1.025);
}

.collection-note-copy {
  position: relative;
  z-index: 1;
  max-width: 590px;
  align-self: center;
  padding: 88px clamp(40px, 6vw, 108px);
}

.collection-note-copy h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(44px, 4.8vw, 70px);
  font-weight: 500;
  letter-spacing: -.03em;
  line-height: .96;
}

.collection-note-copy > p:not(.store-eyebrow) {
  margin: 22px 0 26px;
  color: #5e464e;
  font-size: 13px;
  line-height: 1.65;
}

.collection-note-copy a {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 3px;
  border-bottom: 1px solid currentColor;
  color: inherit;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .065em;
  text-decoration: none;
  text-transform: uppercase;
}

@media (max-width: 1100px) {
  .collection-hero {
    grid-template-columns: minmax(0, .95fr) minmax(0, 1.05fr);
  }

  .collection-hero-copy {
    padding-inline: clamp(32px, 4.5vw, 64px);
  }

  .collection-product-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .collection-hero {
    min-height: auto;
    grid-template-columns: 1fr;
  }

  .collection-hero::before {
    width: 390px;
    left: -220px;
    bottom: auto;
    top: 70px;
  }

  .collection-hero-copy {
    padding: 72px 24px 68px;
  }

  .collection-hero-copy::after {
    display: none;
  }

  .collection-hero h1 {
    font-size: clamp(55px, 13vw, 78px);
  }

  .collection-hero-image-wrap {
    padding: 0 16px 16px;
  }

  .collection-hero-image {
    min-height: 500px;
    border-radius: 150px 2px 2px 2px;
  }

  .collection-hero-image::before {
    border-radius: 138px 1px 1px 1px;
  }

  .collection-products {
    padding-top: 82px;
    padding-bottom: 96px;
  }

  .collection-products::before {
    width: calc(100vw - 32px);
  }

  .collection-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 24px;
  }

  .collection-heading > p {
    max-width: 520px;
  }

  .collection-toolbar {
    align-items: stretch;
    flex-direction: column;
    margin-top: 34px;
    padding: 11px;
  }

  .collection-sort {
    min-height: 42px;
    padding: 4px 2px 0;
    border-top: 1px solid var(--store-line);
    border-left: 0;
  }

  .collection-product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 38px 12px;
    margin-top: 32px;
  }

  .collection-note {
    grid-template-columns: 1fr;
  }

  .collection-note-image {
    min-height: 430px;
  }

  .collection-note-copy {
    padding: 72px 24px 82px;
  }
}

@media (max-width: 430px) {
  .collection-hero-kicker {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
  }

  .collection-hero-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .collection-scroll-link {
    width: fit-content;
  }

  .collection-scroll-link-primary {
    width: auto;
  }

  .collection-hero-image {
    min-height: 410px;
    border-radius: 112px 2px 2px 2px;
  }

  .collection-hero-image::before {
    border-radius: 100px 1px 1px 1px;
  }

  .collection-hero-image-caption {
    right: 25px;
    bottom: 24px;
    left: 25px;
  }

  .collection-hero-image-caption small {
    display: none;
  }

  .collection-heading > p {
    padding-left: 16px;
  }

  .collection-product-grid {
    gap: 32px 9px;
  }

  .collection-note-image {
    min-height: 360px;
  }
}
</style>
