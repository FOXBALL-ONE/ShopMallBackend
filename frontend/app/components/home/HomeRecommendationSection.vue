<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { CSSProperties } from 'vue'
import type { HomeRecommendationSection } from '~/types/home-recommendation'

const props = withDefaults(defineProps<{
  section: HomeRecommendationSection
  eager?: boolean
}>(), {
  eager: false,
})

const { t } = useStorefrontI18n()
const availableGroups = computed(() => props.section.groups.filter(group => group.products.length > 0))
const activeGroupCode = ref('')
const carouselViewport = ref<HTMLElement | null>(null)
const canScrollPrevious = ref(false)
const canScrollNext = ref(false)
let resizeObserver: ResizeObserver | undefined

watch(availableGroups, (groups) => {
  if (!groups.some(group => group.code === activeGroupCode.value)) activeGroupCode.value = groups[0]?.code ?? ''
}, { deep: true, immediate: true })

const activeGroup = computed(() =>
  availableGroups.value.find(group => group.code === activeGroupCode.value) ?? availableGroups.value[0]
)
const products = computed(() => activeGroup.value?.products ?? [])
const productLayoutStyle = computed<CSSProperties>(() => ({
  '--recommendation-desktop-columns': String(props.section.desktop_columns),
  '--recommendation-mobile-columns': String(props.section.mobile_columns),
  '--recommendation-desktop-card-width': `calc((100% - ${(props.section.desktop_columns - 1) * 15}px) / ${props.section.desktop_columns})`,
  '--recommendation-mobile-card-width': `calc((100% - ${(props.section.mobile_columns - 1) * 12}px) / ${props.section.mobile_columns})`,
}))
const externalLink = computed(() => /^https:\/\//i.test(props.section.link_url ?? ''))

function updateCarouselState() {
  const viewport = carouselViewport.value
  if (!viewport || props.section.display_style !== 'CAROUSEL') {
    canScrollPrevious.value = false
    canScrollNext.value = false
    return
  }

  const maximumScrollLeft = Math.max(0, viewport.scrollWidth - viewport.clientWidth)
  canScrollPrevious.value = viewport.scrollLeft > 2
  canScrollNext.value = viewport.scrollLeft < maximumScrollLeft - 2
}

function scrollCarousel(direction: -1 | 1) {
  const viewport = carouselViewport.value
  if (!viewport) return
  viewport.scrollBy({ left: direction * Math.max(viewport.clientWidth * .82, 240), behavior: 'smooth' })
}

watch(products, async () => {
  await nextTick()
  carouselViewport.value?.scrollTo({ left: 0 })
  updateCarouselState()
})

watch(carouselViewport, (viewport, previousViewport) => {
  if (previousViewport) resizeObserver?.unobserve(previousViewport)
  if (viewport) resizeObserver?.observe(viewport)
  void nextTick(updateCarouselState)
})

onMounted(() => {
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(updateCarouselState)
    if (carouselViewport.value) resizeObserver.observe(carouselViewport.value)
  }
  window.addEventListener('resize', updateCarouselState)
  updateCarouselState()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.removeEventListener('resize', updateCarouselState)
})
</script>

<template>
  <section v-if="availableGroups.length" :id="section.code" class="home-recommendation-section">
    <div class="page-width">
      <div class="recommendation-heading">
        <div class="recommendation-heading-copy">
          <p v-if="section.eyebrow" class="eyebrow">{{ section.eyebrow }}</p>
          <h2>{{ section.title }}</h2>
          <p v-if="section.subtitle" class="recommendation-subtitle">{{ section.subtitle }}</p>
        </div>
        <div class="recommendation-heading-actions">
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

          <div v-if="section.display_style === 'CAROUSEL' && (canScrollPrevious || canScrollNext)" class="recommendation-carousel-controls" :aria-label="t('recommendation.controls')">
            <button type="button" :disabled="!canScrollPrevious" :aria-label="t('recommendation.previous')" @click="scrollCarousel(-1)">
              <UIcon name="i-lucide-arrow-left" />
            </button>
            <button type="button" :disabled="!canScrollNext" :aria-label="t('recommendation.next')" @click="scrollCarousel(1)">
              <UIcon name="i-lucide-arrow-right" />
            </button>
          </div>
        </div>
      </div>

      <div v-if="section.display_style === 'TABS' && availableGroups.length > 1" class="recommendation-tabs" role="tablist" :aria-label="section.title">
        <button
          v-for="group in availableGroups"
          :key="group.code"
          type="button"
          role="tab"
          :aria-selected="activeGroup?.code === group.code"
          :aria-controls="`${section.code}-products`"
          :class="{ active: activeGroup?.code === group.code }"
          @click="activeGroupCode = group.code"
        >
          {{ group.title || group.code }}
        </button>
      </div>

      <div
        v-if="products.length"
        :id="`${section.code}-products`"
        ref="carouselViewport"
        class="recommendation-products"
        :class="{ carousel: section.display_style === 'CAROUSEL' }"
        :style="productLayoutStyle"
        @scroll.passive="updateCarouselState"
      >
        <ProductCard
          v-for="(product, index) in products"
          :key="`${activeGroup?.code}-${product.id}`"
          :product="product"
          :eager="eager && index < 4"
        />
      </div>
      <p v-if="section.display_style === 'CAROUSEL' && canScrollNext" class="recommendation-swipe-hint">
        {{ t('recommendation.swipeHint') }} <UIcon name="i-lucide-move-right" />
      </p>
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
  display: grid;
  grid-template-columns: minmax(120px, 1fr) minmax(0, 780px) minmax(120px, 1fr);
  align-items: end;
  gap: 24px;
}

.recommendation-heading-copy {
  grid-column: 2;
  text-align: center;
}

.recommendation-heading h2 {
  margin: 4px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(36px, 4.2vw, 58px);
  font-weight: 500;
  letter-spacing: -.045em;
  line-height: 1.03;
}

.recommendation-subtitle {
  max-width: 620px;
  margin: 15px auto 0;
  color: #746a6e;
  font-size: 14px;
  line-height: 1.55;
}

.recommendation-heading-actions {
  grid-column: 3;
  display: flex;
  align-items: center;
  justify-self: end;
  gap: 16px;
}

.recommendation-carousel-controls {
  display: flex;
  gap: 7px;
}

.recommendation-carousel-controls button {
  width: 38px;
  height: 38px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 1px solid var(--line);
  border-radius: 50%;
  background: rgba(255, 255, 255, .54);
  cursor: pointer;
  transition: color .2s ease, background .2s ease, border-color .2s ease, opacity .2s ease;
}

.recommendation-carousel-controls button:not(:disabled):hover {
  border-color: var(--store-ink);
  color: #fff;
  background: var(--store-ink);
}

.recommendation-carousel-controls button:disabled {
  cursor: default;
  opacity: .35;
}

.recommendation-carousel-controls .iconify {
  width: 15px;
  height: 15px;
}

.recommendation-tabs {
  display: flex;
  justify-content: center;
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
  --recommendation-desktop-columns: 4;
  --recommendation-mobile-columns: 2;
  --recommendation-desktop-card-width: calc((100% - 45px) / 4);
  --recommendation-mobile-card-width: calc((100% - 12px) / 2);
  --recommendation-gap: 15px;
  display: grid;
  grid-template-columns: repeat(var(--recommendation-desktop-columns, 4), minmax(0, 1fr));
  gap: 28px var(--recommendation-gap);
  margin-top: 38px;
}

.recommendation-products.carousel {
  display: flex;
  gap: var(--recommendation-gap);
  overflow-x: auto;
  overscroll-behavior-inline: contain;
  scroll-snap-type: x mandatory;
  scrollbar-width: none;
  padding-bottom: 2px;
  -webkit-overflow-scrolling: touch;
}

.recommendation-products.carousel::-webkit-scrollbar {
  display: none;
}

.recommendation-products.carousel > * {
  flex: 0 0 var(--recommendation-desktop-card-width);
  min-width: 0;
  scroll-snap-align: start;
}

.recommendation-swipe-hint {
  display: none;
}

@media (max-width: 760px) {
  .home-recommendation-section {
    padding: 75px 0 83px;
  }

  .recommendation-heading {
    display: flex;
    align-items: center;
    flex-direction: column;
    text-align: center;
  }

  .recommendation-heading-actions {
    justify-content: center;
  }

  .recommendation-heading .text-link {
    flex: 0 0 auto;
    font-size: 9px;
  }

  .recommendation-tabs {
    justify-content: flex-start;
    gap: 20px;
    overflow-x: auto;
    scrollbar-width: none;
  }

  .recommendation-tabs::-webkit-scrollbar {
    display: none;
  }

  .recommendation-tabs button {
    flex: 0 0 auto;
  }

  .recommendation-products {
    --recommendation-gap: 12px;
    grid-template-columns: repeat(var(--recommendation-mobile-columns, 2), minmax(0, 1fr));
    gap: 28px var(--recommendation-gap);
    margin-top: 32px;
  }

  .recommendation-products.carousel > * {
    flex-basis: var(--recommendation-mobile-card-width);
  }

  .recommendation-carousel-controls {
    display: none;
  }

  .recommendation-swipe-hint {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 7px;
    margin: 18px 0 0;
    color: #857a7e;
    font-family: 'DM Mono', monospace;
    font-size: 9px;
    letter-spacing: .08em;
    text-transform: uppercase;
  }

  .recommendation-swipe-hint .iconify {
    width: 15px;
    height: 15px;
  }
}

@media (max-width: 520px) {
  .recommendation-heading h2 {
    font-size: 37px;
  }

}
</style>
