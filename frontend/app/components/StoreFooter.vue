<script setup lang="ts">
const {t, catalogCategoryName} = useStorefrontI18n()
const {countryLabel, currencyCode} = useStorefrontRegion()
const {data: catalogCategories} = await useCatalogCategories()
const currentYear = new Date().getFullYear()
</script>

<template>
  <footer class="store-footer">
    <div class="store-container store-footer-grid">
      <div class="store-footer-brand">
        <NuxtLink class="store-footer-logo" to="/">PELISSA<i>°</i></NuxtLink>
        <p>{{ t('footer.tagline') }}</p>
        <div class="store-social-links">
          <a href="#" :aria-label="t('footer.instagram')">
            <UIcon name="i-lucide-instagram"/>
          </a>
          <a href="#" :aria-label="t('footer.tiktok')">
            <UIcon name="i-lucide-music-2"/>
          </a>
          <a href="#" :aria-label="t('footer.pinterest')">
            <UIcon name="i-lucide-pin"/>
          </a>
        </div>
      </div>

      <div class="store-footer-column">
        <strong>{{ t('footer.shop') }}</strong>
        <NuxtLink v-for="category in catalogCategories" :key="category.id" :to="`/collections/${category.code}`">
          {{ catalogCategoryName(category.code, category.name) }}
        </NuxtLink>
        <NuxtLink to="/collections/shop">{{ t('footer.allProducts') }}</NuxtLink>
      </div>

      <div class="store-footer-column">
        <strong>{{ t('footer.explore') }}</strong>
        <NuxtLink to="/collections/shop">{{ t('footer.fullCollection') }}</NuxtLink>
        <NuxtLink to="/search">{{ t('footer.search') }}</NuxtLink>
        <NuxtLink to="/announcements">{{ t('footer.notices') }}</NuxtLink>
        <NuxtLink to="/product/1">{{ t('footer.fitDetails') }}</NuxtLink>
        <NuxtLink to="/account">{{ t('footer.account') }}</NuxtLink>
      </div>

      <div class="store-footer-column">
        <strong>{{ t('footer.help') }}</strong>
        <NuxtLink to="/shipping-returns">{{ t('footer.shippingReturns') }}</NuxtLink>
        <NuxtLink to="/track-order">{{ t('footer.trackOrder') }}</NuxtLink>
        <NuxtLink to="/contact-us">{{ t('footer.contact') }}</NuxtLink>
        <NuxtLink to="/faqs">{{ t('footer.faqs') }}</NuxtLink>
      </div>
    </div>

    <div class="store-container store-footer-bottom">
      <span>{{ t('footer.rights', {year: currentYear}) }}</span>
      <div>
        <NuxtLink to="/privacy-policy">{{ t('footer.privacy') }}</NuxtLink>
        <NuxtLink to="/terms-of-service">{{ t('footer.terms') }}</NuxtLink>
        <span>{{ countryLabel }} / {{ currencyCode }}</span>
      </div>
    </div>
  </footer>
</template>

<style scoped>
.store-footer {
  padding-top: 74px;
  color: #fbf7f5;
  background: var(--store-ink);
}

.store-footer-grid {
  display: grid;
  grid-template-columns: 2fr repeat(3, 1fr);
  gap: 48px;
  padding-bottom: 68px;
}

.store-footer-brand {
  max-width: 270px;
}

.store-footer-logo {
  display: inline-flex;
  align-items: flex-start;
  color: #fff;
  font-size: 27px;
  font-weight: 700;
  letter-spacing: .09em;
  line-height: 1;
  text-decoration: none;
}

.store-footer-logo i {
  margin: -3px 0 0 3px;
  color: var(--store-blush);
  font-family: Georgia, serif;
  font-size: 20px;
  font-style: normal;
}

.store-footer-brand p {
  margin: 18px 0 24px;
  color: #bbaeb2;
  font-size: 13px;
  line-height: 1.6;
}

.store-social-links {
  display: flex;
  gap: 9px;
}

.store-social-links a {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid #5e5157;
  border-radius: 50%;
  color: #fff;
}

.store-social-links .iconify {
  width: 14px;
  height: 14px;
}

.store-footer-column {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 11px;
}

.store-footer-column strong {
  margin-bottom: 7px;
  color: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.store-footer-column a {
  color: #c3b8bd;
  font-size: 12px;
  text-decoration: none;
}

.store-footer-column a:hover {
  color: #fff;
}

.store-footer-bottom {
  min-height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  border-top: 1px solid #4b3d44;
  color: #b7aab0;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .035em;
  text-transform: uppercase;
}

.store-footer-bottom div {
  display: flex;
  align-items: center;
  gap: 22px;
}

.store-footer-bottom a {
  color: inherit;
  text-decoration: none;
}

.store-footer-bottom button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 0;
  border: 0;
  color: inherit;
  background: none;
  cursor: pointer;
  font-size: inherit;
  text-transform: inherit;
}

.store-footer-bottom .iconify {
  width: 12px;
  height: 12px;
}

@media (max-width: 820px) {
  .store-footer {
    padding-top: 58px;
  }

  .store-footer-grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 42px 24px;
    padding-bottom: 48px;
  }

  .store-footer-brand {
    grid-column: 1 / -1;
  }

  .store-footer-bottom {
    min-height: auto;
    align-items: flex-start;
    flex-direction: column;
    padding-top: 18px;
    padding-bottom: 22px;
  }

  .store-footer-bottom div {
    flex-wrap: wrap;
    gap: 10px 17px;
  }
}
</style>
