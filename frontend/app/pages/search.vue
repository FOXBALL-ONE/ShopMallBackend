<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CollectionSlug } from '~/data/catalog'
import { collectionMeta, collectionNavigation } from '~/data/catalog'

const route = useRoute()
const router = useRouter()
const catalogApi = useCatalogApi()
const { data: products } = await useAsyncData('search-catalog-products', () => catalogApi.listProducts(), { default: () => [] })

const queryInput = ref(typeof route.query.q === 'string' ? route.query.q : '')
const collectionFilter = ref<'all' | CollectionSlug>('all')
const sortBy = ref('relevance')

const submittedQuery = computed(() => typeof route.query.q === 'string' ? route.query.q.trim() : '')

const searchResults = computed(() => {
  const needle = submittedQuery.value.toLocaleLowerCase()
  let result = products.value.filter(product => product.status === 'ACTIVE')

  if (needle) {
    result = result.filter(product => {
      const searchable = [
        product.name,
        product.color,
        product.description,
        product.fit_sense || '',
        product.product_type,
        ...product.tags,
        ...product.highlight,
        ...product.design_and_extras
      ].join(' ').toLocaleLowerCase()

      return searchable.includes(needle)
    })
  }

  if (collectionFilter.value !== 'all' && collectionFilter.value !== 'shop') {
    result = result.filter(product => product.collections.includes(collectionFilter.value as CollectionSlug))
  }

  const sorted = [...result]
  if (sortBy.value === 'newest') sorted.sort((a, b) => b.created_at.localeCompare(a.created_at))
  if (sortBy.value === 'best-selling') sorted.sort((a, b) => b.sales_volume - a.sales_volume)
  if (sortBy.value === 'price-low') sorted.sort((a, b) => a.price - b.price)
  if (sortBy.value === 'price-high') sorted.sort((a, b) => b.price - a.price)
  return sorted
})

const popularCollections = computed(() => [
  collectionMeta.lounge,
  collectionMeta.swim,
  collectionMeta.intimate
])

watch(
  () => route.query.q,
  value => {
    queryInput.value = typeof value === 'string' ? value : ''
  }
)

async function submitSearch() {
  const q = queryInput.value.trim()
  await router.replace({ path: '/search', query: q ? { q } : {} })
}

function resetSearch() {
  queryInput.value = ''
  collectionFilter.value = 'all'
  sortBy.value = 'relevance'
  void router.replace('/search')
}

useHead(() => ({
  title: submittedQuery.value ? `Search: ${submittedQuery.value} | Pelissa` : 'Search | Pelissa',
  meta: [
    { name: 'description', content: 'Search the Pelissa lingerie, lounge and swim collection.' }
  ]
}))
</script>

<template>
  <main class="store-page search-page">
    <StoreHeader />

    <section class="search-hero">
      <div class="store-container search-hero-inner">
        <p class="store-eyebrow">FIND YOUR PELISSA</p>
        <h1>Search the collection.</h1>
        <p>Try a mood, fabric, color, or silhouette—lace, satin, swim, lounge, black...</p>
        <form class="search-main-form" role="search" @submit.prevent="submitSearch">
          <UIcon name="i-lucide-search" />
          <label class="store-sr-only" for="catalog-search">Search the Pelissa catalog</label>
          <input
            id="catalog-search"
            v-model="queryInput"
            type="search"
            placeholder="What are you looking for?"
            autofocus
          >
          <button type="submit">Search <UIcon name="i-lucide-arrow-right" /></button>
        </form>
      </div>
    </section>

    <section class="search-results store-container">
      <div class="search-results-heading">
        <div>
          <p class="store-eyebrow">{{ submittedQuery ? 'SEARCH RESULTS' : 'EXPLORE EVERYTHING' }}</p>
          <h2 v-if="submittedQuery">Results for “{{ submittedQuery }}”</h2>
          <h2 v-else>All Pelissa pieces</h2>
          <span>{{ searchResults.length }} matches</span>
        </div>
        <p>Search covers the backend product name, color, description, tags, fit notes, highlights, and design details.</p>
      </div>

      <div class="search-toolbar">
        <div class="search-collection-filter" aria-label="Filter search by collection">
          <button :class="{ active: collectionFilter === 'all' }" type="button" @click="collectionFilter = 'all'">
            All
          </button>
          <button
            v-for="item in collectionNavigation.filter(item => item.slug !== 'shop')"
            :key="item.slug"
            :class="{ active: collectionFilter === item.slug }"
            type="button"
            @click="collectionFilter = item.slug"
          >
            {{ item.label }}
          </button>
        </div>

        <label class="search-sort">
          <span>Sort</span>
          <select v-model="sortBy">
            <option value="relevance">Relevance</option>
            <option value="newest">Newest</option>
            <option value="best-selling">Best selling</option>
            <option value="price-low">Price: low to high</option>
            <option value="price-high">Price: high to low</option>
          </select>
          <UIcon name="i-lucide-chevron-down" />
        </label>
      </div>

      <div v-if="searchResults.length" class="search-product-grid">
        <ProductCard
          v-for="(product, index) in searchResults"
          :key="product.id"
          :product="product"
          :eager="index < 4"
        />
      </div>

      <div v-else class="search-empty">
        <span class="search-empty-icon"><UIcon name="i-lucide-search-x" /></span>
        <p class="store-eyebrow">NOTHING FOUND</p>
        <h2>No match for “{{ submittedQuery }}”</h2>
        <p>Check the spelling, try “lace” or “swim”, or browse one of the edits below.</p>
        <button class="store-button" type="button" @click="resetSearch">Clear search</button>
      </div>
    </section>

    <section class="search-discover">
      <div class="store-container">
        <div class="search-discover-heading">
          <p class="store-eyebrow">NOT SURE WHERE TO START?</p>
          <h2>Shop by mood.</h2>
        </div>
        <div class="search-discover-grid">
          <NuxtLink v-for="collection in popularCollections" :key="collection.slug" :to="`/collections/${collection.slug}`">
            <img :src="collection.image" :alt="collection.englishLabel" :style="{ objectPosition: collection.position }">
            <div>
              <span>{{ collection.label }}</span>
              <strong>{{ collection.englishLabel }}</strong>
              <UIcon name="i-lucide-arrow-up-right" />
            </div>
          </NuxtLink>
        </div>
      </div>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.search-hero {
  padding: 96px 0 88px;
  color: #fff;
  background: var(--store-ink);
}

.search-hero-inner {
  max-width: 920px;
  text-align: center;
}

.search-hero .store-eyebrow {
  color: var(--store-blush);
}

.search-hero h1 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(48px, 6vw, 80px);
  font-weight: 500;
  line-height: 1;
}

.search-hero-inner > p:not(.store-eyebrow) {
  margin: 18px auto 31px;
  color: #cbbfc3;
  font-size: 13px;
}

.search-main-form {
  min-height: 65px;
  display: flex;
  align-items: center;
  gap: 13px;
  max-width: 760px;
  margin: auto;
  padding: 7px 8px 7px 19px;
  color: var(--store-ink);
  background: #fff;
}

.search-main-form > .iconify {
  width: 21px;
  height: 21px;
  color: var(--store-plum);
}

.search-main-form input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: 0;
  color: var(--store-ink);
  background: transparent;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 20px;
}

.search-main-form input::placeholder {
  color: #a89ca1;
}

.search-main-form button {
  min-width: 132px;
  min-height: 49px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  padding: 0 18px;
  border: 1px solid var(--store-wine);
  color: #fff;
  background: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .075em;
  text-transform: uppercase;
  cursor: pointer;
}

.search-main-form button:hover {
  color: var(--store-wine);
  background: transparent;
}

.search-results {
  padding-top: 96px;
  padding-bottom: 124px;
}

.search-results-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 45px;
}

.search-results-heading h2 {
  display: inline;
  margin: 0 14px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(38px, 4.4vw, 62px);
  font-weight: 500;
  line-height: 1;
}

.search-results-heading span {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  text-transform: uppercase;
}

.search-results-heading > p {
  max-width: 410px;
  margin: 0;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.65;
}

.search-toolbar {
  min-height: 70px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  margin-top: 39px;
  padding: 12px 0;
  border-top: 1px solid var(--store-line);
  border-bottom: 1px solid var(--store-line);
}

.search-collection-filter {
  display: flex;
  gap: 7px;
  overflow-x: auto;
  scrollbar-width: none;
}

.search-collection-filter::-webkit-scrollbar {
  display: none;
}

.search-collection-filter button {
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

.search-collection-filter button:hover,
.search-collection-filter button.active {
  border-color: var(--store-ink);
  color: var(--store-ink);
  background: #fff;
}

.search-sort {
  position: relative;
  min-width: 190px;
  display: flex;
  align-items: center;
  gap: 9px;
  flex: 0 0 auto;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.search-sort span {
  color: var(--store-muted);
}

.search-sort select {
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

.search-sort .iconify {
  position: absolute;
  right: 0;
  width: 13px;
  height: 13px;
  pointer-events: none;
}

.search-product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 44px 16px;
  margin-top: 36px;
}

.search-empty {
  min-height: 500px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
}

.search-empty-icon {
  width: 60px;
  height: 60px;
  display: grid;
  place-items: center;
  margin-bottom: 22px;
  border-radius: 50%;
  color: var(--store-wine);
  background: var(--store-linen);
}

.search-empty-icon .iconify {
  width: 25px;
  height: 25px;
}

.search-empty h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(38px, 4vw, 56px);
  font-weight: 500;
}

.search-empty > p:not(.store-eyebrow) {
  max-width: 430px;
  margin: 14px 0 24px;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.6;
}

.search-discover {
  padding: 96px 0 112px;
  background: var(--store-linen);
}

.search-discover-heading {
  text-align: center;
}

.search-discover-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(44px, 5vw, 68px);
  font-weight: 500;
}

.search-discover-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 44px;
}

.search-discover-grid > a {
  position: relative;
  overflow: hidden;
  aspect-ratio: .82;
  color: #fff;
  text-decoration: none;
}

.search-discover-grid > a::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, transparent 52%, rgba(30, 17, 23, .6));
  content: '';
}

.search-discover-grid img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: transform .5s ease;
}

.search-discover-grid > a:hover img {
  transform: scale(1.035);
}

.search-discover-grid > a > div {
  position: absolute;
  z-index: 1;
  right: 20px;
  bottom: 20px;
  left: 20px;
  display: grid;
  grid-template-columns: 1fr auto;
  grid-template-areas: 'label icon' 'title icon';
  align-items: center;
  gap: 3px 10px;
}

.search-discover-grid span {
  grid-area: label;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .08em;
}

.search-discover-grid strong {
  grid-area: title;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 27px;
  font-weight: 500;
}

.search-discover-grid .iconify {
  grid-area: icon;
  width: 20px;
  height: 20px;
}

@media (max-width: 1000px) {
  .search-product-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 820px) {
  .search-hero {
    padding: 76px 0 70px;
  }

  .search-main-form {
    min-height: 58px;
  }

  .search-main-form input {
    font-size: 17px;
  }

  .search-main-form button {
    min-width: 104px;
    min-height: 44px;
    padding-inline: 12px;
  }

  .search-results {
    padding-top: 76px;
    padding-bottom: 90px;
  }

  .search-results-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 19px;
  }

  .search-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .search-sort {
    min-height: 38px;
  }

  .search-product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 35px 12px;
    margin-top: 28px;
  }

  .search-discover {
    padding: 76px 0 86px;
  }

  .search-discover-grid {
    grid-template-columns: 1fr;
    gap: 12px;
    margin-top: 34px;
  }

  .search-discover-grid > a {
    aspect-ratio: 1.15;
  }
}

@media (max-width: 500px) {
  .search-main-form {
    padding-left: 13px;
  }

  .search-main-form button {
    min-width: 49px;
    font-size: 0;
  }

  .search-main-form button .iconify {
    width: 17px;
    height: 17px;
  }

  .search-product-grid {
    gap: 30px 9px;
  }
}
</style>
