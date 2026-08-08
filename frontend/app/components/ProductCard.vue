<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CatalogProduct } from '~/data/catalog'
import { formatPrice } from '~/data/catalog'

const props = withDefaults(defineProps<{
  product: CatalogProduct
  eager?: boolean
}>(), {
  eager: false
})

const isFavorite = ref(false)
const primaryImage = computed(() => props.product.images[0] || '/lingerie/hero-corset.jpg')
const alternateImage = computed(() => props.product.images[1] || primaryImage.value)
</script>

<template>
  <article class="catalog-product-card">
    <div class="catalog-product-media">
      <NuxtLink :to="`/product/${product.id}`" :aria-label="`View ${product.name}`">
        <img
          class="catalog-product-image catalog-primary-image"
          :src="primaryImage"
          :alt="product.name"
          :loading="eager ? 'eager' : 'lazy'"
          :style="{ objectPosition: product.image_positions?.[0] || 'center' }"
        >
        <img
          class="catalog-product-image catalog-alternate-image"
          :src="alternateImage"
          alt=""
          loading="lazy"
          :style="{ objectPosition: product.image_positions?.[1] || 'center' }"
        >
        <span v-if="product.badge" class="catalog-product-badge" :class="{ sale: product.is_sale }">{{ product.badge }}</span>
        <span class="catalog-quick-add">View details <UIcon name="i-lucide-arrow-up-right" /></span>
      </NuxtLink>

      <button
        class="catalog-favorite"
        :class="{ active: isFavorite }"
        type="button"
        :aria-label="`${isFavorite ? 'Remove' : 'Add'} ${product.name} ${isFavorite ? 'from' : 'to'} favorites`"
        @click="isFavorite = !isFavorite"
      >
        <UIcon :name="isFavorite ? 'i-lucide-heart' : 'i-lucide-heart'" />
      </button>
    </div>

    <NuxtLink class="catalog-product-copy" :to="`/product/${product.id}`">
      <div class="catalog-product-heading">
        <div>
          <h3>{{ product.name }}</h3>
          <p>{{ product.color }}</p>
        </div>
        <span class="catalog-rating"><UIcon name="i-lucide-star" /> {{ product.score.toFixed(1) }}</span>
      </div>
      <div class="catalog-price-row">
        <strong :class="{ sale: product.is_sale }">{{ formatPrice(product.price) }}</strong>
        <del v-if="product.compare_at_price">{{ formatPrice(product.compare_at_price) }}</del>
      </div>
    </NuxtLink>
  </article>
</template>

<style scoped>
.catalog-product-card {
  min-width: 0;
}

.catalog-product-media {
  position: relative;
  overflow: hidden;
  background: #d9ced0;
}

.catalog-product-media > a {
  position: relative;
  display: block;
  aspect-ratio: .785;
  color: inherit;
  text-decoration: none;
}

.catalog-product-image {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
  transition: opacity .45s ease, transform .55s ease;
}

.catalog-alternate-image {
  opacity: 0;
  transform: scale(1.025);
}

.catalog-product-media:hover .catalog-primary-image {
  opacity: 0;
}

.catalog-product-media:hover .catalog-alternate-image {
  opacity: 1;
  transform: scale(1);
}

.catalog-product-badge {
  position: absolute;
  top: 12px;
  left: 12px;
  padding: 6px 8px;
  color: #fff;
  background: var(--store-plum);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .075em;
  line-height: 1;
}

.catalog-product-badge.sale {
  background: var(--store-wine);
}

.catalog-favorite {
  position: absolute;
  z-index: 2;
  top: 8px;
  right: 8px;
  width: 35px;
  height: 35px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .9);
  cursor: pointer;
}

.catalog-favorite .iconify {
  width: 16px;
  height: 16px;
  stroke-width: 1.6;
}

.catalog-favorite.active {
  color: #fff;
  background: var(--store-wine);
}

.catalog-favorite.active .iconify {
  fill: currentColor;
}

.catalog-quick-add {
  position: absolute;
  right: 10px;
  bottom: 10px;
  left: 10px;
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .94);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .07em;
  opacity: 0;
  text-transform: uppercase;
  transform: translateY(8px);
  transition: opacity .25s ease, transform .25s ease;
}

.catalog-quick-add .iconify {
  width: 14px;
  height: 14px;
}

.catalog-product-media:hover .catalog-quick-add {
  opacity: 1;
  transform: translateY(0);
}

.catalog-product-copy {
  display: block;
  padding-top: 13px;
  color: inherit;
  text-decoration: none;
}

.catalog-product-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.catalog-product-heading h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
}

.catalog-product-heading p {
  margin: 5px 0 0;
  color: var(--store-muted);
  font-size: 11px;
}

.catalog-rating {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  flex: 0 0 auto;
  color: var(--store-muted);
  font-size: 10px;
}

.catalog-rating .iconify {
  width: 11px;
  height: 11px;
  fill: currentColor;
}

.catalog-price-row {
  display: flex;
  align-items: baseline;
  gap: 7px;
  margin-top: 9px;
  font-family: 'DM Mono', monospace;
  font-size: 11px;
}

.catalog-price-row strong {
  font-weight: 500;
}

.catalog-price-row strong.sale {
  color: var(--store-wine);
}

.catalog-price-row del {
  color: #998c91;
  font-size: 9px;
}

@media (hover: none) {
  .catalog-quick-add {
    opacity: 1;
    transform: none;
  }
}

@media (max-width: 520px) {
  .catalog-product-badge {
    top: 8px;
    left: 8px;
    font-size: 7px;
  }

  .catalog-favorite {
    top: 5px;
    right: 5px;
    width: 32px;
    height: 32px;
  }

  .catalog-quick-add {
    right: 7px;
    bottom: 7px;
    left: 7px;
    min-height: 36px;
    font-size: 8px;
  }

  .catalog-product-heading h3 {
    font-size: 12px;
  }

  .catalog-rating {
    display: none;
  }
}
</style>
