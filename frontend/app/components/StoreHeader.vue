<script setup lang="ts">
import { ref, watch } from 'vue'

const route = useRoute()
const router = useRouter()

const navItems = [
  { label: 'New in', to: '/collections/new' },
  { label: 'Lounge', to: '/collections/lounge' },
  { label: 'Swim', to: '/collections/swim' },
  { label: 'Intimates', to: '/collections/intimate' },
  { label: 'Shop all', to: '/collections/shop' },
  { label: 'Sale', to: '/collections/sale' }
]

const isMenuOpen = ref(false)
const isSearchOpen = ref(false)
const isBagOpen = ref(false)
const searchInput = ref(typeof route.query.q === 'string' ? route.query.q : '')

watch(
  () => route.query.q,
  value => {
    searchInput.value = typeof value === 'string' ? value : ''
  }
)

function toggleMenu() {
  isMenuOpen.value = !isMenuOpen.value
  isSearchOpen.value = false
  isBagOpen.value = false
}

function toggleSearch() {
  isSearchOpen.value = !isSearchOpen.value
  isMenuOpen.value = false
  isBagOpen.value = false
}

function toggleBag() {
  isBagOpen.value = !isBagOpen.value
  isMenuOpen.value = false
  isSearchOpen.value = false
}

async function submitSearch() {
  const q = searchInput.value.trim()
  isSearchOpen.value = false
  await router.push({ path: '/search', query: q ? { q } : {} })
}
</script>

<template>
  <div class="store-header-wrap">
    <div class="store-announcement" aria-label="Promotion">
      <span>Complimentary shipping on orders over $79</span>
      <NuxtLink to="/collections/new">
        Shop new arrivals <UIcon name="i-lucide-arrow-up-right" />
      </NuxtLink>
    </div>

    <header class="store-header">
      <div class="store-utility store-container">
        <span><UIcon name="i-lucide-map-pin" /> United States</span>
        <span>USD <UIcon name="i-lucide-chevron-down" /></span>
        <NuxtLink class="store-help" to="/search">Help</NuxtLink>
      </div>

      <div class="store-brand-row store-container">
        <button class="store-icon-button store-mobile-menu" type="button" aria-label="Toggle navigation" @click="toggleMenu">
          <UIcon :name="isMenuOpen ? 'i-lucide-x' : 'i-lucide-menu'" />
        </button>

        <NuxtLink class="store-brand" to="/" aria-label="Pelissa home">
          PELISSA<i>°</i>
        </NuxtLink>

        <div class="store-header-actions">
          <button class="store-icon-button" type="button" aria-label="Search products" @click="toggleSearch">
            <UIcon name="i-lucide-search" />
          </button>
          <NuxtLink class="store-icon-button store-account-link" to="/account" aria-label="My account">
            <UIcon name="i-lucide-user-round" />
          </NuxtLink>
          <button class="store-icon-button store-bag-button" type="button" aria-label="Shopping bag" @click="toggleBag">
            <UIcon name="i-lucide-shopping-bag" />
            <span>0</span>
          </button>
        </div>
      </div>

      <nav class="store-main-nav" :class="{ 'is-open': isMenuOpen }" aria-label="Main navigation">
        <NuxtLink class="store-mobile-account-nav" to="/account" @click="isMenuOpen = false">
          <UIcon name="i-lucide-user-round" />
          My account
        </NuxtLink>
        <NuxtLink v-for="item in navItems" :key="item.to" :to="item.to" @click="isMenuOpen = false">
          {{ item.label }}
        </NuxtLink>
      </nav>

      <form v-if="isSearchOpen" class="store-search-panel" role="search" @submit.prevent="submitSearch">
        <UIcon name="i-lucide-search" />
        <label class="store-sr-only" for="store-header-search">Search products</label>
        <input
          id="store-header-search"
          v-model="searchInput"
          type="search"
          placeholder="Search lace, swim, lounge..."
          autofocus
        >
        <button type="submit" aria-label="Submit search"><UIcon name="i-lucide-arrow-right" /></button>
        <button type="button" aria-label="Close search" @click="isSearchOpen = false"><UIcon name="i-lucide-x" /></button>
      </form>

      <aside v-if="isBagOpen" class="store-bag-popover" aria-label="Shopping bag">
        <div>
          <span class="store-popover-eyebrow">YOUR BAG</span>
          <strong>Your bag is empty</strong>
          <p>Your Pelissa picks will appear here.</p>
          <div class="store-bag-popover-actions">
            <NuxtLink to="/cart" @click="isBagOpen = false">View your bag</NuxtLink>
            <NuxtLink to="/collections/shop" @click="isBagOpen = false">Start shopping</NuxtLink>
          </div>
        </div>
        <button type="button" aria-label="Close shopping bag" @click="isBagOpen = false"><UIcon name="i-lucide-x" /></button>
      </aside>
    </header>
  </div>
</template>

<style scoped>
.store-header-wrap {
  position: relative;
  z-index: 40;
}

.store-announcement {
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 22px;
  padding: 7px 24px;
  color: #fff;
  background: var(--store-wine);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .045em;
  text-transform: uppercase;
}

.store-announcement a {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: inherit;
  text-underline-offset: 3px;
}

.store-announcement .iconify {
  width: 12px;
  height: 12px;
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

.store-help {
  margin-left: auto;
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

.store-bag-button span {
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

.store-search-panel,
.store-bag-popover {
  position: absolute;
  z-index: 5;
  top: 46px;
  right: max(32px, calc((100vw - 1440px) / 2));
  border: 1px solid var(--store-ink);
  background: #fff;
  box-shadow: var(--store-shadow);
}

.store-search-panel {
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

.store-search-panel button,
.store-bag-popover > button {
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

.store-bag-popover {
  width: min(100% - 64px, 345px);
  display: flex;
  justify-content: space-between;
  gap: 22px;
  padding: 26px;
}

.store-bag-popover div {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.store-popover-eyebrow {
  margin-bottom: 10px;
  color: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .1em;
}

.store-bag-popover strong {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 22px;
  font-weight: 500;
}

.store-bag-popover p {
  margin: 8px 0 18px;
  color: var(--store-muted);
  font-size: 12px;
}

.store-bag-popover a {
  padding-bottom: 3px;
  border-bottom: 1px solid currentColor;
  color: inherit;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-decoration: none;
  text-transform: uppercase;
}

.store-bag-popover .store-bag-popover-actions {
  flex-flow: row wrap;
  align-items: center;
  gap: 10px 14px;
}

.store-bag-popover-actions a:first-child {
  padding: 8px 11px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
}

.store-bag-popover-actions a:first-child:hover {
  border-color: var(--store-wine);
  background: var(--store-wine);
}

@media (max-width: 820px) {
  .store-announcement {
    justify-content: space-between;
    gap: 10px;
    padding-inline: 16px;
    font-size: 9px;
  }

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

  .store-search-panel,
  .store-bag-popover {
    top: 65px;
    right: 16px;
    width: calc(100% - 32px);
  }
}

@media (max-width: 480px) {
  .store-announcement span {
    overflow: hidden;
    max-width: 58%;
    white-space: nowrap;
    text-overflow: ellipsis;
  }
}
</style>
