<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { CSSProperties } from 'vue'
import type { HomeRecommendationSection } from '~/types/home-recommendation'

const props = withDefaults(defineProps<{
  section: HomeRecommendationSection
  eager?: boolean
}>(), {
  eager: false,
})

const activeGroupCode = ref(props.section.groups[0]?.code ?? '')
watch(() => props.section.groups, (groups) => {
  if (!groups.some(group => group.code === activeGroupCode.value)) activeGroupCode.value = groups[0]?.code ?? ''
}, { deep: true })

const activeGroup = computed(() =>
  props.section.groups.find(group => group.code === activeGroupCode.value) ?? props.section.groups[0]
)
const products = computed(() => activeGroup.value?.products ?? [])
const productLayoutStyle = computed<CSSProperties>(() => ({
  '--recommendation-desktop-columns': String(props.section.desktop_columns),
  '--recommendation-mobile-columns': String(props.section.mobile_columns),
}))
const externalLink = computed(() => /^https:\/\//i.test(props.section.link_url ?? ''))
</script>

<template>
  <section :id="section.code" class="home-recommendation-section">
    <div class="page-width">
      <div class="recommendation-heading">
        <div>
          <p v-if="section.eyebrow" class="eyebrow">{{ section.eyebrow }}</p>
          <h2>{{ section.title }}</h2>
          <p v-if="section.subtitle" class="recommendation-subtitle">{{ section.subtitle }}</p>
        </div>
        <a
          v-if="section.link_url && section.link_label && externalLink"
          class="text-link"
          :href="section.link_url"
          target="_blank"
          rel="noopener noreferrer"
        >
          {{ section.link_label }} <UIcon name="i-lucide-arrow-up-right" />
        </a>
        <NuxtLink v-else-if="section.link_url && section.link_label" class="text-link" :to="section.link_url">
          {{ section.link_label }} <UIcon name="i-lucide-arrow-right" />
        </NuxtLink>
      </div>

      <div v-if="section.display_style === 'TABS' && section.groups.length > 1" class="recommendation-tabs" role="tablist" :aria-label="section.title">
        <button
          v-for="group in section.groups"
          :key="group.code"
          type="button"
          role="tab"
          :aria-selected="activeGroup?.code === group.code"
          :class="{ active: activeGroup?.code === group.code }"
          @click="activeGroupCode = group.code"
        >
          {{ group.title || group.code }}
        </button>
      </div>

      <div
        v-if="products.length"
        class="recommendation-products"
        :class="{ carousel: section.display_style === 'CAROUSEL' }"
        :style="productLayoutStyle"
      >
        <ProductCard
          v-for="(product, index) in products"
          :key="`${activeGroup?.code}-${product.id}`"
          :product="product"
          :eager="eager && index < section.desktop_columns"
        />
      </div>
    </div>
  </section>
</template>

<style scoped>
.home-recommendation-section {
  padding: 98px 0 116px;
  background: var(--linen);
}

.home-recommendation-section + .home-recommendation-section {
  padding-top: 0;
}

.recommendation-heading {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 24px;
}

.recommendation-heading h2 {
  max-width: 820px;
  margin: 4px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(36px, 4.2vw, 58px);
  font-weight: 500;
  letter-spacing: -.045em;
  line-height: 1.03;
}

.recommendation-subtitle {
  max-width: 620px;
  margin: 15px 0 0;
  color: #746a6e;
  font-size: 14px;
  line-height: 1.55;
}

.recommendation-tabs {
  display: flex;
  gap: 28px;
  margin-top: 34px;
  border-bottom: 1px solid var(--line);
}

.recommendation-tabs button {
  position: relative;
  padding: 0 0 13px;
  border: 0;
  color: #857a7e;
  background: transparent;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .09em;
  text-transform: uppercase;
  cursor: pointer;
}

.recommendation-tabs button::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: var(--store-wine);
  content: '';
  opacity: 0;
  transform: scaleX(.4);
  transition: opacity .2s ease, transform .2s ease;
}

.recommendation-tabs button.active {
  color: var(--store-ink);
}

.recommendation-tabs button.active::after {
  opacity: 1;
  transform: scaleX(1);
}

.recommendation-products {
  display: grid;
  grid-template-columns: repeat(var(--recommendation-desktop-columns, 4), minmax(0, 1fr));
  gap: 28px 15px;
  margin-top: 38px;
}

.recommendation-products.carousel {
  display: flex;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
  scrollbar-width: thin;
  padding-bottom: 14px;
}

.recommendation-products.carousel > * {
  min-width: min(310px, calc((100% - 45px) / var(--recommendation-desktop-columns, 4)));
  scroll-snap-align: start;
}

@media (max-width: 760px) {
  .home-recommendation-section {
    padding: 75px 0 83px;
  }

  .recommendation-heading {
    align-items: flex-end;
  }

  .recommendation-heading .text-link {
    flex: 0 0 auto;
    font-size: 9px;
  }

  .recommendation-tabs {
    gap: 20px;
    overflow-x: auto;
  }

  .recommendation-tabs button {
    flex: 0 0 auto;
  }

  .recommendation-products {
    grid-template-columns: repeat(var(--recommendation-mobile-columns, 2), minmax(0, 1fr));
    gap: 28px 12px;
    margin-top: 32px;
  }

  .recommendation-products.carousel > * {
    min-width: calc((100% - 12px) / var(--recommendation-mobile-columns, 2));
  }
}

@media (max-width: 520px) {
  .recommendation-heading h2 {
    font-size: 37px;
  }

  .recommendation-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
