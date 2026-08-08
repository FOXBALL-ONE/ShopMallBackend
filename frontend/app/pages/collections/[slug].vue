<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CollectionSlug, ProductType } from '~/data/catalog'
import {
  collectionMeta,
  collectionNavigation,
  displayProductType,
  getCollection,
  productsForCollection
} from '~/data/catalog'

const route = useRoute()

const activeSlug = computed<CollectionSlug>(() => {
  const candidate = String(route.params.slug || 'shop')
  return Object.prototype.hasOwnProperty.call(collectionMeta, candidate) ? candidate as CollectionSlug : 'shop'
})

const activeCollection = computed(() => getCollection(activeSlug.value))
const productType = ref<'ALL' | ProductType>('ALL')
const sortBy = ref('featured')

const availableTypes = computed(() => {
  const types = productsForCollection(activeSlug.value).map(product => product.product_type)
  return [...new Set(types)]
})

const visibleProducts = computed(() => {
  let result = productsForCollection(activeSlug.value).filter(product => product.status === 'ACTIVE')

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

watch(activeSlug, () => {
  productType.value = 'ALL'
  sortBy.value = 'featured'
})

useHead(() => ({
  title: `${activeCollection.value.englishLabel} | LUNE`,
  meta: [
    { name: 'description', content: activeCollection.value.description }
  ]
}))
</script>

<template>
  <main class="store-page">
    <StoreHeader />

    <section class="collection-switcher" aria-label="Product collections">
      <nav class="store-container collection-switcher-inner">
        <NuxtLink
          v-for="item in collectionNavigation"
          :key="item.slug"
          :to="`/collections/${item.slug}`"
          :class="{ active: activeSlug === item.slug }"
        >
          <span>{{ item.label }}</span>
          <small>{{ item.englishLabel }}</small>
        </NuxtLink>
      </nav>
    </section>

    <section class="collection-hero">
      <div class="collection-hero-copy">
        <div>
          <p class="store-eyebrow">{{ activeCollection.eyebrow }}</p>
          <h1>{{ activeCollection.title }}</h1>
          <p class="collection-hero-subtitle">{{ activeCollection.subtitle }}</p>
          <p class="collection-hero-description">{{ activeCollection.description }}</p>
          <a href="#collection-products" class="collection-scroll-link">
            Explore the edit <UIcon name="i-lucide-arrow-down" />
          </a>
        </div>
      </div>
      <div class="collection-hero-image">
        <img :src="activeCollection.image" :alt="activeCollection.englishLabel" :style="{ objectPosition: activeCollection.position }">
        <span>{{ activeCollection.label }}</span>
      </div>
    </section>

    <section id="collection-products" class="collection-products store-container">
      <div class="collection-heading">
        <div>
          <p class="store-eyebrow">CURATED FOR YOU</p>
          <h2>{{ activeCollection.englishLabel }}</h2>
          <span>{{ visibleProducts.length }} pieces</span>
        </div>
        <p>Every piece is designed around soft structure, thoughtful detail, and an easy sense of confidence.</p>
      </div>

      <div class="collection-toolbar">
        <div class="collection-type-filter" aria-label="Filter by product type">
          <button type="button" :class="{ active: productType === 'ALL' }" @click="productType = 'ALL'">
            All types
          </button>
          <button
            v-for="type in availableTypes"
            :key="type"
            type="button"
            :class="{ active: productType === type }"
            @click="productType = type"
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
          <UIcon name="i-lucide-chevron-down" />
        </label>
      </div>

      <div v-if="visibleProducts.length" class="collection-product-grid">
        <ProductCard
          v-for="(product, index) in visibleProducts"
          :key="product.id"
          :product="product"
          :eager="index < 4"
        />
      </div>

      <div v-else class="collection-empty">
        <UIcon name="i-lucide-search-x" />
        <h3>No pieces in this edit yet.</h3>
        <p>Try another product type or explore the full Lune collection.</p>
        <button class="store-button" type="button" @click="productType = 'ALL'">Clear filter</button>
      </div>
    </section>

    <section class="collection-note">
      <div class="collection-note-image">
        <img src="/lingerie/lace-texture.jpg" alt="Lune lace and fabric detail">
      </div>
      <div class="collection-note-copy">
        <p class="store-eyebrow">FIT, FEEL, REPEAT</p>
        <h2>The details make the difference.</h2>
        <p>Each product page translates the backend item type into the fit details that matter—from support and coverage to fabric, length, and sheerness.</p>
        <NuxtLink to="/product/1">See a product story <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </div>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.collection-switcher {
  border-bottom: 1px solid var(--store-line);
  background: #fff;
}

.collection-switcher-inner {
  min-height: 76px;
  display: grid;
  grid-template-columns: repeat(6, 1fr);
}

.collection-switcher a {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 12px 8px;
  border-right: 1px solid var(--store-line);
  color: var(--store-ink);
  text-align: center;
  text-decoration: none;
}

.collection-switcher a:first-child {
  border-left: 1px solid var(--store-line);
}

.collection-switcher a::after {
  position: absolute;
  right: -1px;
  bottom: -1px;
  left: -1px;
  height: 3px;
  background: var(--store-wine);
  content: '';
  opacity: 0;
  transform: scaleX(.4);
  transition: opacity .2s ease, transform .2s ease;
}

.collection-switcher a:hover,
.collection-switcher a.active {
  background: var(--store-linen);
}

.collection-switcher a.active::after {
  opacity: 1;
  transform: scaleX(1);
}

.collection-switcher span {
  font-size: 13px;
  font-weight: 600;
}

.collection-switcher small {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-transform: uppercase;
}

.collection-hero {
  min-height: 570px;
  display: grid;
  grid-template-columns: .82fr 1.18fr;
  background: var(--store-linen);
}

.collection-hero-copy {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 78px clamp(32px, 6vw, 112px);
}

.collection-hero-copy > div {
  max-width: 510px;
}

.collection-hero h1 {
  margin: 0;
  white-space: pre-line;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(56px, 6vw, 90px);
  font-weight: 500;
  letter-spacing: -.035em;
  line-height: .94;
}

.collection-hero-subtitle {
  max-width: 430px;
  margin: 27px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 20px;
  line-height: 1.35;
}

.collection-hero-description {
  max-width: 420px;
  margin: 14px 0 25px;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.65;
}

.collection-scroll-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 4px;
  border-bottom: 1px solid currentColor;
  color: inherit;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .07em;
  text-decoration: none;
  text-transform: uppercase;
}

.collection-scroll-link .iconify {
  width: 14px;
  height: 14px;
}

.collection-hero-image {
  position: relative;
  overflow: hidden;
  min-height: 570px;
}

.collection-hero-image::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 58%, rgba(30, 17, 23, .3));
  content: '';
}

.collection-hero-image img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.collection-hero-image span {
  position: absolute;
  z-index: 1;
  right: 28px;
  bottom: 24px;
  color: #fff;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 24px;
}

.collection-products {
  padding-top: 102px;
  padding-bottom: 126px;
}

.collection-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 48px;
}

.collection-heading h2 {
  display: inline;
  margin: 0 15px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(40px, 4.2vw, 62px);
  font-weight: 500;
  line-height: 1;
}

.collection-heading span {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  text-transform: uppercase;
}

.collection-heading > p {
  max-width: 410px;
  margin: 0;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.65;
}

.collection-toolbar {
  min-height: 70px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 42px;
  padding: 12px 0;
  border-top: 1px solid var(--store-line);
  border-bottom: 1px solid var(--store-line);
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
  min-height: 34px;
  flex: 0 0 auto;
  padding: 0 13px;
  border: 1px solid transparent;
  border-radius: 20px;
  color: var(--store-muted);
  background: transparent;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-transform: uppercase;
  cursor: pointer;
}

.collection-type-filter button:hover,
.collection-type-filter button.active {
  border-color: var(--store-ink);
  color: var(--store-ink);
  background: #fff;
}

.collection-sort {
  position: relative;
  min-width: 190px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
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
  padding: 4px 20px 4px 0;
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

.collection-sort .iconify {
  position: absolute;
  right: 0;
  width: 13px;
  height: 13px;
  pointer-events: none;
}

.collection-product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 44px 16px;
  margin-top: 36px;
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

.collection-empty > .iconify {
  width: 32px;
  height: 32px;
  color: var(--store-plum);
}

.collection-empty h3 {
  margin: 18px 0 8px;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 32px;
  font-weight: 500;
}

.collection-empty p {
  margin: 0 0 22px;
  color: var(--store-muted);
  font-size: 13px;
}

.collection-note {
  display: grid;
  grid-template-columns: 1.15fr .85fr;
  background: var(--store-blush);
}

.collection-note-image {
  min-height: 500px;
  overflow: hidden;
}

.collection-note-image img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  object-position: center 47%;
}

.collection-note-copy {
  max-width: 570px;
  align-self: center;
  padding: 80px clamp(32px, 6vw, 100px);
}

.collection-note-copy h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(42px, 4.5vw, 66px);
  font-weight: 500;
  line-height: 1;
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

@media (max-width: 1000px) {
  .collection-product-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .collection-switcher {
    overflow-x: auto;
    scrollbar-width: none;
  }

  .collection-switcher::-webkit-scrollbar {
    display: none;
  }

  .collection-switcher-inner {
    width: max-content;
    min-width: 100%;
    grid-template-columns: repeat(6, minmax(118px, 1fr));
  }

  .collection-hero {
    min-height: auto;
    grid-template-columns: 1fr;
  }

  .collection-hero-copy {
    padding: 72px 24px 66px;
  }

  .collection-hero h1 {
    font-size: clamp(55px, 13vw, 78px);
  }

  .collection-hero-image {
    min-height: 480px;
  }

  .collection-products {
    padding-top: 76px;
    padding-bottom: 90px;
  }

  .collection-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 20px;
  }

  .collection-toolbar {
    align-items: stretch;
    flex-direction: column;
    margin-top: 32px;
    padding: 14px 0;
  }

  .collection-sort {
    min-height: 38px;
  }

  .collection-product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 35px 12px;
    margin-top: 28px;
  }

  .collection-note {
    grid-template-columns: 1fr;
  }

  .collection-note-image {
    min-height: 410px;
  }

  .collection-note-copy {
    padding: 68px 24px 76px;
  }
}

@media (max-width: 430px) {
  .collection-hero-image {
    min-height: 390px;
  }

  .collection-product-grid {
    gap: 30px 9px;
  }
}
</style>
