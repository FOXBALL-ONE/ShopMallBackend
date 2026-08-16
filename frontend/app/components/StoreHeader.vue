<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'

const route = useRoute()
const router = useRouter()
const session = useCustomerSession()
const customerCart = useCustomerCart()
const announcementCenter = useAnnouncementCenter()
const {t, catalogCategoryName} = useStorefrontI18n()
const {data: catalogCategories} = await useCatalogCategories()
const navItems = computed(() => [
  ...catalogCategories.value.map(category => ({
    label: catalogCategoryName(category.code, category.name),
    to: `/collections/${category.code}`
  })),
  {label: t('catalog.categories.shop'), to: '/collections/shop'}
])

const isMenuOpen = ref(false)
const isSearchOpen = ref(false)
const isCartOpen = ref(false)
const searchInput = ref(typeof route.query.q === 'string' ? route.query.q : '')
const cartCount = computed(() => customerCart.totalQuantity.value)

watch(
    () => route.query.q,
    value => {
      searchInput.value = typeof value === 'string' ? value : ''
    }
)

function toggleMenu() {
  isMenuOpen.value = !isMenuOpen.value
  isSearchOpen.value = false
  isCartOpen.value = false
}

function toggleSearch() {
  isSearchOpen.value = !isSearchOpen.value
  isMenuOpen.value = false
  isCartOpen.value = false
}

async function refreshCart(force = false) {
  try {
    await customerCart.refresh(force)
  } catch {
    // The shared cart popover exposes request failures when the customer opens it.
  }
}

function toggleCart() {
  isCartOpen.value = !isCartOpen.value
  isMenuOpen.value = false
  isSearchOpen.value = false
}

async function submitSearch() {
  const q = searchInput.value.trim()
  isSearchOpen.value = false
  await router.push({path: '/search', query: q ? {q} : {}})
}

onMounted(() => {
  if (session.isAuthenticated.value) void refreshCart()
})

watch(() => session.userId.value, userId => {
  if (userId) void refreshCart(true)
  else customerCart.reset()
})
</script>

<template>
  <div class="store-header-wrap">
    <header class="store-header">
      <div class="store-utility store-container">
        <span><UIcon name="i-lucide-map-pin"/> {{ t('common.region') }}</span>
        <span>USD</span>
        <StoreLocaleSwitcher/>
        <NuxtLink class="store-notices" to="/announcements">
          <UIcon name="i-lucide-megaphone"/>
          {{ t('header.notices') }}
          <b v-if="announcementCenter.currentCount.value">
            {{ announcementCenter.currentCount.value >= 50 ? '50+' : announcementCenter.currentCount.value }}
          </b>
        </NuxtLink>
        <NuxtLink class="store-help" to="/search">{{ t('header.help') }}</NuxtLink>
      </div>

      <div class="store-brand-row store-container">
        <button
          class="store-icon-button store-mobile-menu"
          type="button"
          :aria-label="t('header.toggleNavigation')"
          @click="toggleMenu"
        >
          <UIcon :name="isMenuOpen ? 'i-lucide-x' : 'i-lucide-menu'"/>
        </button>

        <NuxtLink class="store-brand" to="/" :aria-label="t('header.home')">
          PELISSA<i>°</i>
        </NuxtLink>

        <div class="store-header-actions">
          <button
            class="store-icon-button"
            type="button"
            :aria-label="t('header.searchProducts')"
            @click="toggleSearch"
          >
            <UIcon name="i-lucide-search"/>
          </button>
          <NuxtLink class="store-icon-button store-account-link" to="/account" :aria-label="t('header.account')">
            <UIcon name="i-lucide-user-round"/>
          </NuxtLink>
          <button
            class="store-icon-button store-cart-button"
            type="button"
            :aria-label="t('header.cart')"
            @click="toggleCart"
          >
            <UIcon name="i-lucide-shopping-cart"/>
            <span v-if="cartCount">{{ cartCount > 99 ? '99+' : cartCount }}</span>
          </button>
        </div>
      </div>

      <nav class="store-main-nav" :class="{ 'is-open': isMenuOpen }" :aria-label="t('header.mainNavigation')">
        <NuxtLink class="store-mobile-account-nav" to="/account" @click="isMenuOpen = false">
          <UIcon name="i-lucide-user-round"/>
          {{ t('header.account') }}
        </NuxtLink>
        <NuxtLink v-for="item in navItems" :key="item.to" :to="item.to" @click="isMenuOpen = false">
          {{ item.label }}
        </NuxtLink>
      </nav>

      <form v-if="isSearchOpen" class="store-search-panel" role="search" @submit.prevent="submitSearch">
        <UIcon name="i-lucide-search"/>
        <label class="store-sr-only" for="store-header-search">{{ t('header.searchProducts') }}</label>
        <input
            id="store-header-search"
            v-model="searchInput"
            type="search"
            :placeholder="t('header.searchPlaceholder')"
            autofocus
        >
        <button type="submit" :aria-label="t('header.submitSearch')">
          <UIcon name="i-lucide-arrow-right"/>
        </button>
        <button type="button" :aria-label="t('header.closeSearch')" @click="isSearchOpen = false">
          <UIcon name="i-lucide-x"/>
        </button>
      </form>

      <StoreCartPopover v-if="isCartOpen" @close="isCartOpen = false"/>
    </header>
  </div>
</template>

<style scoped>
.store-header-wrap {
  position: relative;
  z-index: 40;
}

.store-header {
  position: relative;
  border-bottom: 1px solid var(--store-line);
  background: var(--store-paper);
}

.store-utility {
  height: 37px;
  display: flex;
  align-items: center;
  gap: 20px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .045em;
}

.store-utility span,
.store-utility a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: inherit;
  text-decoration: none;
}

.store-utility .iconify {
  width: 12px;
  height: 12px;
}

.store-notices {
  margin-left: auto;
}

.store-notices b {
  min-width: 16px;
  height: 16px;
  display: inline-grid;
  place-items: center;
  padding: 0 4px;
  border-radius: 8px;
  color: #fff;
  background: var(--store-wine);
  font-size: 8px;
}

.store-brand-row {
  position: relative;
  height: 74px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.store-brand {
  display: inline-flex;
  align-items: flex-start;
  color: var(--store-ink);
  font-size: 28px;
  font-weight: 700;
  letter-spacing: .09em;
  line-height: 1;
  text-decoration: none;
}

.store-brand i {
  margin: -3px 0 0 3px;
  color: var(--store-wine);
  font-family: Georgia, serif;
  font-size: 21px;
  font-style: normal;
}

.store-header-actions {
  position: absolute;
  right: 0;
  display: flex;
  align-items: center;
  gap: 3px;
}

.store-icon-button {
  position: relative;
  width: 39px;
  height: 39px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: var(--store-ink);
  background: transparent;
  cursor: pointer;
  text-decoration: none;
}

.store-icon-button:hover {
  background: var(--store-linen);
}

.store-icon-button .iconify {
  width: 19px;
  height: 19px;
  stroke-width: 1.6;
}

.store-mobile-menu {
  position: absolute;
  left: 0;
  display: none;
}

.store-cart-button span {
  position: absolute;
  top: 5px;
  right: 1px;
  min-width: 14px;
  height: 14px;
  display: grid;
  place-items: center;
  padding: 0 3px;
  border-radius: 50%;
  color: #fff;
  background: var(--store-wine);
  font-size: 8px;
}

.store-main-nav {
  height: 46px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: clamp(24px, 3.4vw, 54px);
  border-top: 1px solid var(--store-line);
}

.store-main-nav a {
  position: relative;
  padding: 7px 0;
  color: var(--store-ink);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .075em;
  text-decoration: none;
  text-transform: uppercase;
}

.store-main-nav a:last-child {
  color: var(--store-wine);
}

.store-main-nav .store-mobile-account-nav {
  display: none;
}

.store-main-nav a::after {
  position: absolute;
  right: 0;
  bottom: 2px;
  left: 0;
  height: 1px;
  background: currentColor;
  content: '';
  opacity: 0;
  transform: scaleX(.4);
  transition: opacity .2s ease, transform .2s ease;
}

.store-main-nav a:hover::after,
.store-main-nav a.router-link-active::after {
  opacity: 1;
  transform: scaleX(1);
}

.store-search-panel {
  position: absolute;
  z-index: 5;
  top: 46px;
  right: max(32px, calc((100vw - 1440px) / 2));
  border: 1px solid var(--store-ink);
  background: #fff;
  box-shadow: var(--store-shadow);
  width: min(100% - 64px, 470px);
  min-height: 56px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
}

.store-search-panel > .iconify {
  width: 18px;
  height: 18px;
}

.store-search-panel input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  color: var(--store-ink);
  background: transparent;
  font-size: 13px;
}

.store-search-panel button {
  width: 28px;
  height: 32px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

@media (max-width: 820px) {
  .store-utility {
    height: 33px;
    justify-content: space-between;
  }

  .store-utility span:first-child {
    display: none;
  }

  .store-help {
    margin-left: 0;
  }

  .store-notices {
    margin-left: auto;
  }

  .store-brand-row {
    height: 65px;
  }

  .store-brand {
    font-size: 22px;
    letter-spacing: .07em;
  }

  .store-mobile-menu {
    display: grid;
  }

  .store-account-link {
    display: none;
  }

  .store-main-nav {
    position: absolute;
    top: 98px;
    right: 0;
    left: 0;
    height: auto;
    display: none;
    align-items: stretch;
    padding: 9px 16px 16px;
    border-top: 1px solid var(--store-line);
    background: var(--store-paper);
    box-shadow: 0 12px 24px rgba(36, 29, 33, .1);
  }

  .store-main-nav.is-open {
    display: flex;
    flex-direction: column;
    gap: 0;
  }

  .store-main-nav a {
    padding: 13px 2px;
    border-bottom: 1px solid var(--store-line);
  }

  .store-main-nav .store-mobile-account-nav {
    display: flex;
    align-items: center;
    gap: 9px;
    color: var(--store-wine);
  }

  .store-mobile-account-nav .iconify {
    width: 16px;
    height: 16px;
  }

  .store-main-nav a:last-child {
    border-bottom: 0;
  }

  .store-search-panel {
    top: 65px;
    right: 16px;
    width: calc(100% - 32px);
  }
}

</style>
