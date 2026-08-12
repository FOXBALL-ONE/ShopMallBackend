<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const catalogApi = useCatalogApi()
const {
  data: catalog,
  status: productRequestStatus,
  error: productRequestError,
  refresh: refreshCatalog
} = await useAsyncData('home-catalog-products', () => catalogApi.listProducts(), { default: () => [] })
const {
  data: catalogCategories,
  status: categoryRequestStatus,
  error: categoryRequestError,
  refresh: refreshCategories
} = await useCatalogCategories()

const router = useRouter()
const session = useCustomerSession()
const customerCart = useCustomerCart()
const announcementCenter = useAnnouncementCenter()

useHead({
  title: 'Pelissa | Modern lingerie',
  meta: [
    {
      name: 'description',
      content: 'Modern lingerie designed for comfort, confidence, and every version of you.'
    }
  ]
})

type HeroSlide = {
  eyebrow: string
  title: string
  copy: string
  action: string
  image: string
  position: string
}

type StoreLocation = {
  label: string
  value: string
}

type StoreCurrency = {
  label: string
  value: string
}

const navItems = computed(() => [
  ...catalogCategories.value.map(category => ({ label: category.name, to: `/collections/${category.code}` })),
  { label: 'Shop all', to: '/collections/shop' }
])

const heroSlides: HeroSlide[] = [
  {
    eyebrow: 'THE EVERYDAY EDIT',
    title: 'Feel good in\nyour own skin.',
    copy: 'Soft support, thoughtful details, and the kind of confidence you can feel all day.',
    action: 'Shop lingerie',
    image: '/lingerie/hero-corset.jpg',
    position: 'center 34%'
  },
  {
    eyebrow: 'LACE AFTER DARK',
    title: 'A little more\nsomething.',
    copy: 'Sheer lace, sculpted lines, and pieces that turn an ordinary night into a mood.',
    action: 'Explore lace',
    image: '/lingerie/hero-lace.jpg',
    position: 'center 42%'
  },
  {
    eyebrow: 'THE SOFT SET',
    title: 'Comfort,\nreimagined.',
    copy: 'Second-skin layers and easy silhouettes for slow mornings, late nights, and everything between.',
    action: 'Shop loungewear',
    image: '/lingerie/hero-soft.jpg',
    position: 'center 51%'
  }
]

const products = computed(() => {
  const active = catalog.value.filter(product => product.status === 'ACTIVE')
  const newProducts = active.filter(product =>
    product.is_new || product.tags.some(tag => tag.trim().toLocaleLowerCase() === 'new arrival')
  )
  return [...(newProducts.length ? newProducts : active)]
    .sort((left, right) => right.created_at.localeCompare(left.created_at))
    .slice(0, 4)
})

const categories = computed(() => catalogCategories.value
  .filter(category => category.parent_id === null)
  .map(category => {
    const relatedProduct = catalog.value.find(product => product.category_id === category.id && product.images[0])
    return {
      id: category.id,
      label: category.name,
      title: category.name,
      image: relatedProduct?.images[0] ?? '/lingerie/hero-corset.jpg',
      to: `/collections/${category.code}`
    }
  })
)
const firstCategoryLink = computed(() => categories.value[0]?.to ?? '/collections/shop')

const locationOptions: StoreLocation[] = [
  { label: 'United States', value: 'US' },
  { label: 'United Kingdom', value: 'GB' },
  { label: 'Canada', value: 'CA' },
  { label: 'Australia', value: 'AU' },
  { label: 'China', value: 'CN' }
]

const currencyOptions: StoreCurrency[] = [
  { label: 'USD - US Dollar', value: 'USD' },
  { label: 'GBP - British Pound', value: 'GBP' },
  { label: 'CAD - Canadian Dollar', value: 'CAD' },
  { label: 'AUD - Australian Dollar', value: 'AUD' },
  { label: 'CNY - Chinese Yuan', value: 'CNY' }
]

const isMenuOpen = ref(false)
const isSearchOpen = ref(false)
const isCartOpen = ref(false)
const isLocationMenuOpen = ref(false)
const isCurrencyMenuOpen = ref(false)
const activeSlide = ref(0)
const email = ref('')
const searchQuery = ref('')
const isSubscribed = ref(false)
const selectedLocation = ref('US')
const selectedCurrency = ref('USD')
let carouselTimer: ReturnType<typeof setInterval> | undefined

const activeHero = computed<HeroSlide>(() => heroSlides[activeSlide.value] ?? heroSlides[0]!)
const activeHeroLink = computed(() => firstCategoryLink.value)
const locationLabel = computed(() => locationOptions.find(option => option.value === selectedLocation.value)?.label ?? locationOptions[0]!.label)
const currencyLabel = computed(() => selectedCurrency.value)
const cartCount = computed(() => customerCart.totalQuantity.value)

function setActiveSlide(index: number) {
  activeSlide.value = index
}

function previousSlide() {
  activeSlide.value = (activeSlide.value + heroSlides.length - 1) % heroSlides.length
}

function nextSlide() {
  activeSlide.value = (activeSlide.value + 1) % heroSlides.length
}

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

function toggleCart() {
  isCartOpen.value = !isCartOpen.value
  isMenuOpen.value = false
  isSearchOpen.value = false
  isLocationMenuOpen.value = false
  isCurrencyMenuOpen.value = false
}

async function refreshCart(force = false) {
  try {
    await customerCart.refresh(force)
  } catch {
    // The shared cart popover displays refresh failures when opened.
  }
}

function selectLocation(value: string) {
  selectedLocation.value = value
  isLocationMenuOpen.value = false
  localStorage.setItem('pelissa-location', value)
}

function selectCurrency(value: string) {
  selectedCurrency.value = value
  isCurrencyMenuOpen.value = false
  localStorage.setItem('pelissa-currency', value)
}

function subscribe() {
  if (email.value.trim()) {
    isSubscribed.value = true
  }
}

async function submitSearch() {
  const q = searchQuery.value.trim()
  isSearchOpen.value = false
  await router.push({ path: '/search', query: q ? { q } : {} })
}

onMounted(() => {
  const savedLocation = localStorage.getItem('pelissa-location')
  const savedCurrency = localStorage.getItem('pelissa-currency')
  if (locationOptions.some(option => option.value === savedLocation)) selectedLocation.value = savedLocation!
  if (currencyOptions.some(option => option.value === savedCurrency)) selectedCurrency.value = savedCurrency!
  if (session.isAuthenticated.value) void refreshCart()
  carouselTimer = setInterval(nextSlide, 8000)
})

watch(() => session.userId.value, userId => {
  if (userId) void refreshCart(true)
  else customerCart.reset()
})

onBeforeUnmount(() => {
  if (carouselTimer) {
    clearInterval(carouselTimer)
  }
})
</script>

<template>
  <main class="site-shell">
    <header class="site-header">
      <div class="utility-row">
        <div class="utility-picker location-link">
          <button
            class="utility-link"
            type="button"
            aria-label="Choose shipping location"
            :aria-expanded="isLocationMenuOpen"
            @click="isLocationMenuOpen = !isLocationMenuOpen; isCurrencyMenuOpen = false"
          >
            <UIcon name="i-lucide-map-pin" /> {{ locationLabel }}
            <UIcon name="i-lucide-chevron-down" />
          </button>
          <div v-if="isLocationMenuOpen" class="utility-menu" role="menu" aria-label="Shipping locations">
            <button
              v-for="option in locationOptions"
              :key="option.value"
              type="button"
              role="menuitemradio"
              :aria-checked="selectedLocation === option.value"
              :class="{ active: selectedLocation === option.value }"
              @click="selectLocation(option.value)"
            >
              <span>{{ option.label }}</span>
              <UIcon v-if="selectedLocation === option.value" name="i-lucide-check" />
            </button>
          </div>
        </div>
        <div class="utility-picker">
          <button
            class="utility-link"
            type="button"
            aria-label="Choose currency"
            :aria-expanded="isCurrencyMenuOpen"
            @click="isCurrencyMenuOpen = !isCurrencyMenuOpen; isLocationMenuOpen = false"
          >
            {{ currencyLabel }} <UIcon name="i-lucide-chevron-down" />
          </button>
          <div v-if="isCurrencyMenuOpen" class="utility-menu currency-menu" role="menu" aria-label="Currencies">
            <button
              v-for="option in currencyOptions"
              :key="option.value"
              type="button"
              role="menuitemradio"
              :aria-checked="selectedCurrency === option.value"
              :class="{ active: selectedCurrency === option.value }"
              @click="selectCurrency(option.value)"
            >
              <span>{{ option.label }}</span>
              <UIcon v-if="selectedCurrency === option.value" name="i-lucide-check" />
            </button>
          </div>
        </div>
        <NuxtLink class="utility-link utility-notices" to="/announcements">
          <UIcon name="i-lucide-megaphone" /> Notices
          <b v-if="announcementCenter.currentCount.value">
            {{ announcementCenter.currentCount.value >= 50 ? '50+' : announcementCenter.currentCount.value }}
          </b>
        </NuxtLink>
        <button class="utility-link utility-help" type="button">Help</button>
      </div>

      <div class="brand-row">
        <button class="icon-button mobile-only" type="button" aria-label="Open menu" @click="toggleMenu">
          <UIcon :name="isMenuOpen ? 'i-lucide-x' : 'i-lucide-menu'" />
        </button>

        <NuxtLink class="brand" to="/" aria-label="Pelissa home">
          <span>PELISSA</span><i>°</i>
        </NuxtLink>

        <div class="header-actions">
          <button class="icon-button" type="button" aria-label="Search" @click="toggleSearch">
            <UIcon name="i-lucide-search" />
          </button>
          <NuxtLink class="icon-button desktop-only" to="/account" aria-label="My account">
            <UIcon name="i-lucide-user-round" />
          </NuxtLink>
          <button class="icon-button cart-button" type="button" aria-label="Shopping cart" @click="toggleCart">
            <UIcon name="i-lucide-shopping-cart" />
            <span v-if="cartCount" class="cart-count">{{ cartCount > 99 ? '99+' : cartCount }}</span>
          </button>
        </div>
      </div>

      <nav class="main-nav" :class="{ 'is-open': isMenuOpen }" aria-label="Main navigation">
        <NuxtLink class="mobile-account-nav" to="/account" @click="isMenuOpen = false">
          <UIcon name="i-lucide-user-round" />
          My account
        </NuxtLink>
        <NuxtLink v-for="item in navItems" :key="item.to" :to="item.to" @click="isMenuOpen = false">{{ item.label }}</NuxtLink>
      </nav>

      <form v-if="isSearchOpen" class="search-panel" role="search" @submit.prevent="submitSearch">
        <label class="sr-only" for="site-search">Search products</label>
        <UIcon name="i-lucide-search" />
        <input id="site-search" v-model="searchQuery" type="search" placeholder="Search bras, lace, sets..." autofocus>
        <button type="submit" aria-label="Submit search"><UIcon name="i-lucide-arrow-right" /></button>
        <button type="button" aria-label="Close search" @click="isSearchOpen = false"><UIcon name="i-lucide-x" /></button>
      </form>

      <StoreCartPopover v-if="isCartOpen" @close="isCartOpen = false" />
    </header>

    <section id="top" class="hero" :style="{ '--hero-image': `url(${activeHero.image})`, '--hero-position': activeHero.position }">
      <div class="hero-shade" />
      <div class="hero-content">
        <p class="eyebrow light">{{ activeHero.eyebrow }}</p>
        <h1>{{ activeHero.title }}</h1>
        <p class="hero-copy">{{ activeHero.copy }}</p>
        <NuxtLink class="button button-light" :to="activeHeroLink">{{ activeHero.action }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </div>

      <div class="hero-controls">
        <div class="hero-progress" aria-label="Hero slides">
          <button
            v-for="(_, index) in heroSlides"
            :key="index"
            class="progress-dot"
            :class="{ active: activeSlide === index }"
            type="button"
            :aria-label="`Show slide ${index + 1}`"
            @click="setActiveSlide(index)"
          />
        </div>
        <div class="hero-arrows">
          <button type="button" aria-label="Previous slide" @click="previousSlide"><UIcon name="i-lucide-arrow-left" /></button>
          <button type="button" aria-label="Next slide" @click="nextSlide"><UIcon name="i-lucide-arrow-right" /></button>
        </div>
      </div>
    </section>

    <section class="benefit-bar" aria-label="Shopping benefits">
      <div><UIcon name="i-lucide-truck" /><span><strong>Easy delivery</strong> Fast, trackable shipping</span></div>
      <div><UIcon name="i-lucide-refresh-ccw" /><span><strong>30-day returns</strong> Made to try at home</span></div>
      <div><UIcon name="i-lucide-sparkles" /><span><strong>Made for every body</strong> Sizes XS-4X</span></div>
    </section>

    <section class="category-section page-width">
      <div class="section-heading centered">
        <p class="eyebrow">THE LINGERIE EDIT</p>
        <h2>Made for every moment.</h2>
        <p>Thoughtful intimates with an effortless point of view.</p>
      </div>
      <div class="category-grid">
        <template v-if="categoryRequestStatus === 'pending'">
          <div v-for="index in 4" :key="index" class="category-card category-skeleton" aria-hidden="true">
            <div class="category-image" />
            <div class="category-caption"><span /><strong /></div>
          </div>
        </template>
        <div v-else-if="categoryRequestError" class="category-state" role="alert">
          <UIcon name="i-lucide-cloud-alert" />
          <p>Categories are unavailable right now.</p>
          <button type="button" @click="refreshCategories()">Try again</button>
        </div>
        <NuxtLink v-for="category in categories" v-else :key="category.id" class="category-card" :to="category.to">
          <div class="category-image" :style="{ backgroundImage: `url(${category.image})` }" />
          <div class="category-caption">
            <span>{{ category.label }}</span>
            <strong>{{ category.title }}</strong>
            <UIcon name="i-lucide-arrow-up-right" />
          </div>
        </NuxtLink>
        <div v-if="categoryRequestStatus === 'success' && !categories.length" class="category-state">
          <UIcon name="i-lucide-folder-open" />
          <p>No categories are available yet.</p>
        </div>
      </div>
    </section>

    <section id="new-in" class="product-section">
      <div class="page-width">
        <div class="section-heading section-heading-row">
          <div>
            <p class="eyebrow">NEW &amp; NOTEWORTHY</p>
            <h2>New intimates, right on time.</h2>
          </div>
          <NuxtLink class="text-link" to="/collections/shop">Shop all <UIcon name="i-lucide-arrow-right" /></NuxtLink>
        </div>
        <div
          v-if="productRequestStatus === 'pending'"
          class="product-grid"
          aria-label="Loading new products"
          aria-busy="true"
        >
          <article v-for="index in 4" :key="index" class="product-skeleton" aria-hidden="true">
            <div class="product-skeleton-media" />
            <span />
            <small />
          </article>
        </div>
        <div v-else-if="productRequestError" class="product-request-state" role="alert">
          <UIcon name="i-lucide-cloud-alert" />
          <div>
            <h3>New arrivals are unavailable right now.</h3>
            <p>Please try loading the catalog again.</p>
          </div>
          <button class="product-retry-button" type="button" @click="refreshCatalog()">Try again</button>
        </div>
        <div v-else-if="products.length" class="product-grid">
          <ProductCard v-for="(product, index) in products" :key="product.id" :product="product" :eager="index < 4" />
        </div>
        <div v-else class="product-request-state">
          <UIcon name="i-lucide-package-open" />
          <div>
            <h3>New arrivals are on their way.</h3>
            <p>Explore the full collection while the latest pieces are being added.</p>
          </div>
          <NuxtLink class="product-retry-button" to="/collections/shop">Shop all</NuxtLink>
        </div>
      </div>
    </section>

    <section class="escape-section">
      <div class="escape-image" role="img" aria-label="Soft lingerie and loungewear">
        <div class="escape-stamp">PELISSA<br>AFTER<br>DARK</div>
      </div>
      <div class="escape-content">
        <p class="eyebrow">THE LOUNGE EDIT</p>
        <h2>From first light to last call.</h2>
        <p>Silky layers and soft sets made for slow mornings, candlelit evenings, and everything between.</p>
        <div class="button-group">
          <NuxtLink class="button button-dark" :to="firstCategoryLink">Shop the collection <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
          <NuxtLink class="text-link underlined" :to="firstCategoryLink">View the collection</NuxtLink>
        </div>
      </div>
    </section>

    <section class="editorial-section page-width">
      <div class="editorial-card editorial-copy">
        <p class="eyebrow">PELISSA NOTES</p>
        <h2>Wear it your way.</h2>
        <p>Fit notes, care rituals, and thoughtful ways to make every layer feel like yours.</p>
        <NuxtLink class="text-link underlined" to="/search?q=fit">Discover our journal <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </div>
      <NuxtLink class="editorial-card editorial-image" to="/search?q=lace" aria-label="Explore the Pelissa journal">
        <div class="editorial-image-inner" />
        <span>The Pelissa journal <UIcon name="i-lucide-arrow-up-right" /></span>
      </NuxtLink>
    </section>

    <section class="newsletter-section">
      <div class="newsletter-content">
        <p class="eyebrow light">A LITTLE SOMETHING IN YOUR INBOX</p>
        <h2>Take 15% off your first order.</h2>
        <p>New drops, fit notes, and private offers.</p>
        <form class="newsletter-form" @submit.prevent="subscribe">
          <label class="sr-only" for="newsletter-email">Email address</label>
          <input id="newsletter-email" v-model="email" type="email" required placeholder="Email address">
          <button type="submit">{{ isSubscribed ? 'You are on the list' : 'Sign me up' }} <UIcon v-if="!isSubscribed" name="i-lucide-arrow-right" /></button>
        </form>
        <small>By subscribing, you agree to our terms and privacy policy.</small>
      </div>
    </section>

    <footer class="site-footer">
      <div class="page-width footer-grid">
        <div class="footer-brand">
          <NuxtLink class="brand" to="/">PELISSA<i>°</i></NuxtLink>
          <p>Modern intimates for every version of you.</p>
          <div class="social-links">
            <a href="#top" aria-label="Instagram"><UIcon name="i-lucide-instagram" /></a>
            <a href="#top" aria-label="TikTok"><UIcon name="i-lucide-music-2" /></a>
            <a href="#top" aria-label="Pinterest"><UIcon name="i-lucide-pin" /></a>
          </div>
        </div>
        <div class="footer-column">
          <strong>Shop</strong>
          <NuxtLink v-for="category in catalogCategories" :key="category.id" :to="`/collections/${category.code}`">{{ category.name }}</NuxtLink>
          <NuxtLink to="/collections/shop">Shop all</NuxtLink>
        </div>
        <div class="footer-column">
          <strong>About</strong>
          <NuxtLink to="/search?q=story">Our story</NuxtLink>
          <NuxtLink to="/search?q=journal">Journal</NuxtLink>
          <NuxtLink to="/announcements">Notices</NuxtLink>
          <NuxtLink to="/search?q=size">Size guide</NuxtLink>
          <NuxtLink to="/search?q=care">Careers</NuxtLink>
        </div>
        <div class="footer-column">
          <strong>Help</strong>
          <NuxtLink to="/search?q=shipping">Shipping &amp; returns</NuxtLink>
          <NuxtLink to="/account/logistics">Track an order</NuxtLink>
          <NuxtLink to="/search?q=contact">Contact us</NuxtLink>
          <NuxtLink to="/search?q=faq">FAQs</NuxtLink>
        </div>
      </div>
      <div class="page-width footer-bottom">
        <span>© 2026 Pelissa. All rights reserved.</span>
        <div><a href="#top">Privacy</a><a href="#top">Terms</a><button type="button">{{ locationLabel }} / {{ currencyLabel }}</button></div>
      </div>
    </footer>
  </main>
</template>

<style>
@import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=DM+Sans:opsz,wght@9..40,400;9..40,500;9..40,600;9..40,700&family=Playfair+Display:ital,wght@0,500;0,600;1,500;1,600&display=swap');

:root {
  --ink: #241d21;
  --off-white: #fbf7f5;
  --linen: #f1e8e7;
  --coral: #9a4055;
  --coral-dark: #753043;
  --sea: #75636a;
  --blush: #dfb8b8;
  --line: rgba(36, 29, 33, .18);
}

* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body { margin: 0; background: var(--off-white); color: var(--ink); font-family: 'DM Sans', Arial, sans-serif; }
button, input { font: inherit; }
button { color: inherit; }
a { color: inherit; text-decoration: none; }

.site-shell { min-width: 320px; overflow: clip; }
.page-width { width: min(100% - 64px, 1440px); margin-inline: auto; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.mobile-only { display: none !important; }


.site-header { position: relative; z-index: 10; background: var(--off-white); border-bottom: 1px solid var(--line); }
.utility-row { height: 42px; display: flex; align-items: center; gap: 20px; width: min(100% - 64px, 1440px); margin: auto; color: #5f655f; font-family: 'DM Mono', monospace; font-size: 12px; letter-spacing: .025em; }
.utility-picker { position: relative; }
.utility-link { min-height: 32px; display: inline-flex; align-items: center; gap: 5px; padding: 0; border: 0; background: transparent; cursor: pointer; }
.utility-link .iconify { width: 14px; height: 14px; }
.utility-menu { position: absolute; z-index: 20; top: calc(100% + 4px); left: 0; width: max-content; min-width: 188px; padding: 5px; border: 1px solid var(--line); background: #fff; box-shadow: 0 10px 22px rgba(36, 29, 33, .12); }
.currency-menu { min-width: 220px; }
.utility-menu button { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 0 9px; border: 0; color: var(--ink); background: transparent; font-family: 'DM Sans', Arial, sans-serif; font-size: 13px; text-align: left; cursor: pointer; }
.utility-menu button:hover, .utility-menu button.active { background: var(--linen); }
.utility-menu .iconify { width: 14px; height: 14px; color: var(--coral); }
.utility-notices { margin-left: auto; text-decoration: none; }
.utility-notices b { min-width: 17px; height: 17px; display: inline-grid; place-items: center; padding: 0 4px; border-radius: 9px; color: #fff; background: var(--coral); font-size: 9px; }
.brand-row { height: 75px; width: min(100% - 64px, 1440px); margin: auto; position: relative; display: flex; justify-content: center; align-items: center; }
.brand { display: inline-flex; align-items: flex-start; color: var(--ink); font-size: 28px; font-weight: 700; letter-spacing: .09em; line-height: 1; }
.brand i { margin: -3px 0 0 3px; color: var(--coral); font-family: Georgia, serif; font-size: 21px; font-style: normal; }
.header-actions { position: absolute; right: 0; display: flex; align-items: center; gap: 4px; }
.icon-button { position: relative; display: grid; place-items: center; width: 38px; height: 38px; padding: 0; border: 0; background: transparent; cursor: pointer; }
.icon-button .iconify { width: 19px; height: 19px; stroke-width: 1.6; }
.icon-button:hover { background: var(--linen); }
.cart-count { position: absolute; top: 6px; right: 2px; min-width: 14px; height: 14px; display: grid; place-items: center; padding: 0 3px; border-radius: 50%; color: #fff; background: var(--coral); font-size: 8px; line-height: 1; }
.main-nav { height: 46px; display: flex; align-items: center; justify-content: center; gap: clamp(20px, 3vw, 48px); border-top: 1px solid var(--line); }
.main-nav a { position: relative; padding: 5px 0; font-family: 'DM Mono', monospace; font-size: 11px; font-weight: 500; letter-spacing: .06em; text-transform: uppercase; }
.main-nav a:last-child { color: var(--coral); }
.main-nav .mobile-account-nav { display: none; }
.main-nav a::after { position: absolute; bottom: 0; left: 0; width: 0; height: 1px; background: currentColor; content: ''; transition: width .2s ease; }
.main-nav a:hover::after { width: 100%; }
.search-panel { position: absolute; right: max(32px, calc((100vw - 1440px) / 2)); top: 46px; width: min(100% - 64px, 430px); height: 54px; display: flex; align-items: center; gap: 10px; padding: 0 13px; border: 1px solid var(--ink); background: #fff; box-shadow: 0 10px 22px rgba(28, 37, 32, .12); }
.search-panel .iconify { width: 19px; height: 19px; }
.search-panel input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 13px; }
.search-panel button { display: grid; place-items: center; padding: 0; border: 0; background: transparent; cursor: pointer; }

.hero { --hero-image: none; --hero-position: center; position: relative; min-height: min(690px, calc(100vh - 190px)); display: flex; align-items: end; background-image: var(--hero-image); background-position: var(--hero-position); background-size: cover; color: #fff; transition: background-image .45s ease; }
.hero-shade { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(30, 16, 22, .52) 0%, rgba(30, 16, 22, .17) 53%, rgba(30, 16, 22, .05) 100%); }
.hero-content { position: relative; width: min(100% - 64px, 1440px); margin: 0 auto; padding: 88px 0 82px; }
.eyebrow { margin: 0 0 16px; color: var(--sea); font-family: 'DM Mono', monospace; font-size: 11px; font-weight: 500; letter-spacing: .125em; line-height: 1.3; text-transform: uppercase; }
.eyebrow.light { color: inherit; }
.hero h1, h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-weight: 500; letter-spacing: 0; }
.hero h1 { max-width: 645px; white-space: pre-line; font-size: clamp(55px, 6.3vw, 97px); line-height: .94; }
.hero-copy { max-width: 385px; margin: 24px 0 29px; font-size: 15px; line-height: 1.55; }
.button { min-height: 45px; display: inline-flex; align-items: center; justify-content: center; gap: 11px; padding: 0 19px; border: 1px solid transparent; font-family: 'DM Mono', monospace; font-size: 10px; font-weight: 500; letter-spacing: .05em; text-transform: uppercase; transition: .2s ease; }
.button .iconify { width: 15px; height: 15px; }
.button-light { color: var(--ink); background: #fff; }
.button-light:hover { color: #fff; border-color: #fff; background: transparent; }
.button-dark { color: #fff; background: var(--ink); }
.button-dark:hover { color: var(--ink); border-color: var(--ink); background: transparent; }
.hero-controls { position: absolute; right: max(32px, calc((100vw - 1440px) / 2)); bottom: 31px; left: max(32px, calc((100vw - 1440px) / 2)); display: flex; align-items: center; justify-content: space-between; }
.hero-progress { display: flex; gap: 8px; }
.progress-dot { width: 36px; height: 2px; padding: 0; border: 0; background: rgba(255, 255, 255, .48); cursor: pointer; }
.progress-dot.active { background: #fff; }
.hero-arrows { display: flex; gap: 8px; }
.hero-arrows button { display: grid; place-items: center; width: 36px; height: 36px; padding: 0; border: 1px solid rgba(255,255,255,.7); border-radius: 50%; color: #fff; background: transparent; cursor: pointer; transition: .2s; }
.hero-arrows button:hover { color: var(--ink); background: #fff; }
.hero-arrows .iconify { width: 16px; height: 16px; }

.benefit-bar { min-height: 88px; display: grid; grid-template-columns: repeat(3, 1fr); border-bottom: 1px solid var(--line); background: #fff; }
.benefit-bar > div { display: flex; justify-content: center; align-items: center; gap: 11px; padding: 12px 20px; border-right: 1px solid var(--line); }
.benefit-bar > div:last-child { border-right: 0; }
.benefit-bar .iconify { width: 19px; height: 19px; color: var(--sea); stroke-width: 1.5; }
.benefit-bar span { display: flex; flex-direction: column; gap: 2px; color: #636963; font-size: 11px; }
.benefit-bar strong { color: var(--ink); font-family: 'DM Mono', monospace; font-size: 10px; font-weight: 500; letter-spacing: .05em; text-transform: uppercase; }

.category-section { padding: 116px 0 128px; }
.section-heading h2 { font-size: clamp(36px, 4.2vw, 58px); line-height: 1.03; }
.section-heading > p:not(.eyebrow) { max-width: 380px; margin: 16px auto 0; color: #746a6e; font-size: 14px; line-height: 1.55; }
.centered { text-align: center; }
.category-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; margin-top: 48px; }
.category-card { overflow: hidden; display: block; background: var(--linen); }
.category-image { aspect-ratio: .76; background-size: cover; transition: transform .45s ease; }
.category-card:hover .category-image { transform: scale(1.045); }
.category-skeleton .category-image,
.category-skeleton .category-caption span,
.category-skeleton .category-caption strong { background: rgba(223, 207, 210, .72); animation: category-skeleton-pulse 1.15s ease-in-out infinite alternate; }
.category-skeleton .category-caption span { width: 55%; height: 9px; display: block; }
.category-skeleton .category-caption strong { width: 76%; height: 23px; display: block; }
.category-state { min-height: 250px; grid-column: 1 / -1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); color: #746a6e; text-align: center; }
.category-state .iconify { width: 25px; height: 25px; color: var(--coral); }
.category-state p { margin: 0; font-size: 13px; }
.category-state button { padding: 7px 10px; border: 1px solid var(--ink); color: var(--ink); background: #fff; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .05em; text-transform: uppercase; cursor: pointer; }
@keyframes category-skeleton-pulse { from { opacity: .5; } to { opacity: 1; } }
.category-caption { display: grid; grid-template-columns: 1fr auto; grid-template-areas: 'label arrow' 'title arrow'; gap: 3px 8px; padding: 15px 15px 17px; background: var(--off-white); }
.category-caption span { grid-area: label; color: var(--sea); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .09em; text-transform: uppercase; }
.category-caption strong { grid-area: title; font-family: 'Playfair Display', Georgia, serif; font-size: 21px; font-weight: 500; line-height: 1.1; }
.category-caption .iconify { grid-area: arrow; align-self: center; width: 17px; height: 17px; }

.product-section { padding: 98px 0 116px; background: var(--linen); }
.section-heading-row { display: flex; align-items: end; justify-content: space-between; gap: 20px; }
.text-link { display: inline-flex; align-items: center; gap: 8px; padding-bottom: 3px; border-bottom: 1px solid currentColor; font-family: 'DM Mono', monospace; font-size: 10px; font-weight: 500; letter-spacing: .055em; text-transform: uppercase; }
.text-link .iconify { width: 15px; height: 15px; }
.text-link:hover { color: var(--coral); }
.product-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 15px; margin-top: 44px; }
.product-skeleton { min-width: 0; }
.product-skeleton-media { aspect-ratio: .785; background: rgba(255, 255, 255, .48); animation: product-skeleton-pulse 1.15s ease-in-out infinite alternate; }
.product-skeleton span, .product-skeleton small { width: 70%; height: 12px; display: block; margin-top: 14px; background: rgba(255, 255, 255, .48); }
.product-skeleton small { width: 38%; height: 9px; margin-top: 9px; }
.product-request-state { min-height: 264px; display: flex; align-items: center; justify-content: center; gap: 18px; margin-top: 44px; padding: 34px; border-top: 1px solid var(--line); border-bottom: 1px solid var(--line); text-align: left; }
.product-request-state > .iconify { width: 30px; height: 30px; flex: 0 0 auto; color: var(--coral); }
.product-request-state h3 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 26px; font-weight: 500; line-height: 1.1; }
.product-request-state p { max-width: 370px; margin: 7px 0 0; color: #746a6e; font-size: 13px; line-height: 1.5; }
.product-retry-button { min-height: 38px; display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; padding: 0 14px; border: 1px solid var(--ink); color: var(--ink); background: transparent; font-family: 'DM Mono', monospace; font-size: 9px; font-weight: 500; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; cursor: pointer; }
.product-retry-button:hover { color: #fff; background: var(--ink); }
@keyframes product-skeleton-pulse { from { opacity: .55; } to { opacity: 1; } }
.product-media { position: relative; }
.product-image { position: relative; display: block; overflow: hidden; aspect-ratio: .785; background-color: #d7c9cc; background-size: cover; }
.product-badge { position: absolute; top: 12px; left: 12px; padding: 6px 7px; color: #fff; background: var(--sea); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .065em; }
.favorite-button { position: absolute; top: 8px; right: 8px; display: grid; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 50%; background: rgba(255, 255, 255, .86); cursor: pointer; }
.favorite-button .iconify { width: 17px; height: 17px; stroke-width: 1.5; }
.quick-add { position: absolute; bottom: 0; left: 0; right: 0; padding: 13px; transform: translateY(100%); color: var(--ink); background: #fff; font-family: 'DM Mono', monospace; font-size: 10px; letter-spacing: .05em; text-align: center; text-transform: uppercase; transition: transform .22s ease; }
.product-card:hover .quick-add { transform: translateY(0); }
.product-info { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; padding-top: 12px; }
.product-info h3 { margin: 0; font-size: 13px; font-weight: 600; line-height: 1.35; }
.product-info p { margin: 3px 0 0; color: #766a70; font-size: 11px; }
.product-info strong { flex: 0 0 auto; font-family: 'DM Mono', monospace; font-size: 12px; font-weight: 500; }

.escape-section { display: grid; grid-template-columns: 1.18fr .82fr; min-height: 680px; background: #eadfde; }
.escape-image { position: relative; min-height: 500px; background: url('/lingerie/hero-soft.jpg') center 46% / cover; }
.escape-stamp { position: absolute; right: 34px; bottom: 32px; width: 112px; height: 112px; display: grid; place-content: center; border: 1px solid currentColor; border-radius: 50%; color: #fff; font-family: 'DM Mono', monospace; font-size: 12px; letter-spacing: .08em; line-height: 1.35; text-align: center; transform: rotate(-12deg); }
.escape-content { align-self: center; max-width: 470px; padding: 58px clamp(36px, 8vw, 132px) 58px clamp(36px, 7vw, 104px); }
.escape-content h2 { font-size: clamp(42px, 4.8vw, 68px); line-height: .98; }
.escape-content > p:not(.eyebrow) { margin: 21px 0 30px; color: #5c4e54; font-size: 15px; line-height: 1.65; }
.button-group { display: flex; align-items: center; flex-wrap: wrap; gap: 23px; }
.underlined { border-bottom: 1px solid currentColor; }

.editorial-section { display: grid; grid-template-columns: .83fr 1.17fr; gap: 15px; padding-top: 116px; padding-bottom: 116px; }
.editorial-card { min-height: 440px; }
.editorial-copy { display: flex; flex-direction: column; justify-content: center; padding: 55px clamp(32px, 6vw, 94px); background: var(--blush); }
.editorial-copy h2 { max-width: 360px; font-size: clamp(40px, 4.5vw, 62px); line-height: .98; }
.editorial-copy > p:not(.eyebrow) { max-width: 340px; margin: 21px 0 28px; color: #543f46; font-size: 14px; line-height: 1.6; }
.editorial-image { position: relative; overflow: hidden; }
.editorial-image-inner { position: absolute; inset: 0; background: url('/lingerie/lace-texture.jpg') center 40% / cover; transition: transform .45s ease; }
.editorial-image:hover .editorial-image-inner { transform: scale(1.04); }
.editorial-image span { position: absolute; right: 0; bottom: 0; display: inline-flex; align-items: center; gap: 10px; padding: 17px 20px; color: var(--ink); background: #fff; font-family: 'DM Mono', monospace; font-size: 10px; letter-spacing: .05em; text-transform: uppercase; }
.editorial-image .iconify { width: 14px; height: 14px; }

.newsletter-section { padding: 92px 32px; color: #fff; background: var(--sea); text-align: center; }
.newsletter-content { max-width: 600px; margin: auto; }
.newsletter-section h2 { font-size: clamp(38px, 4.5vw, 60px); line-height: 1; }
.newsletter-section > .newsletter-content > p:not(.eyebrow) { margin: 15px 0 27px; color: rgba(255,255,255,.82); font-size: 14px; }
.newsletter-form { max-width: 510px; min-height: 53px; display: flex; margin: auto; padding: 4px; background: #fff; }
.newsletter-form input { flex: 1; min-width: 0; padding: 0 14px; border: 0; outline: none; color: var(--ink); font-size: 13px; }
.newsletter-form button { display: inline-flex; align-items: center; justify-content: center; gap: 10px; min-width: 145px; border: 0; color: #fff; background: var(--coral); font-family: 'DM Mono', monospace; font-size: 10px; font-weight: 500; letter-spacing: .04em; text-transform: uppercase; cursor: pointer; }
.newsletter-form button:hover { background: var(--coral-dark); }
.newsletter-form .iconify { width: 14px; height: 14px; }
.newsletter-section small { display: block; margin-top: 14px; color: rgba(255,255,255,.62); font-size: 10px; }

.site-footer { padding-top: 72px; background: #241d21; color: #fbf7f5; }
.footer-grid { display: grid; grid-template-columns: 2.1fr repeat(3, 1fr); gap: 36px; padding-bottom: 65px; }
.footer-brand .brand { color: #fff; font-size: 25px; }
.footer-brand p { max-width: 250px; margin: 17px 0 22px; color: #bbaeb2; font-size: 13px; line-height: 1.55; }
.social-links { display: flex; gap: 7px; }
.social-links a { display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid #5e5157; border-radius: 50%; }
.social-links a:hover { color: var(--ink); background: #fff; }
.social-links .iconify { width: 14px; height: 14px; }
.footer-column { display: flex; flex-direction: column; align-items: flex-start; gap: 11px; }
.footer-column strong { margin-bottom: 7px; color: #fff; font-family: 'DM Mono', monospace; font-size: 10px; font-weight: 500; letter-spacing: .09em; text-transform: uppercase; }
.footer-column a { color: #c3b8bd; font-size: 12px; }
.footer-column a:hover { color: #fff; }
.footer-bottom { min-height: 60px; display: flex; align-items: center; justify-content: space-between; gap: 20px; border-top: 1px solid #4b3d44; color: #b7aab0; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .03em; }
.footer-bottom > div { display: flex; align-items: center; gap: 19px; }
.footer-bottom button { display: inline-flex; align-items: center; gap: 4px; padding: 0; border: 0; color: inherit; background: none; font-size: inherit; cursor: pointer; }
.footer-bottom .iconify { width: 12px; height: 12px; }

/* Lingerie palette and editorial imagery */
.escape-section { background: #eadfde; }
.escape-image { background-image: url('/lingerie/hero-soft.jpg'); }
.escape-content > p:not(.eyebrow) { color: #5c4e54; }
.editorial-copy { background: var(--blush); }
.editorial-copy > p:not(.eyebrow) { color: #543f46; }
.editorial-image-inner { background-image: url('/lingerie/lace-texture.jpg'); }
.newsletter-section { background: var(--sea); }

/* Keep the darker utility surfaces within the Pelissa palette. */
.utility-row { color: #706469; }
.search-panel { box-shadow: 0 10px 22px rgba(36, 29, 33, .12); }
.hero-shade { background: linear-gradient(90deg, rgba(30, 16, 22, .52) 0%, rgba(30, 16, 22, .17) 53%, rgba(30, 16, 22, .05) 100%); }
.section-heading > p:not(.eyebrow) { color: #746a6e; }
.product-image { background-color: #d7c9cc; }
.product-info p { color: #766a70; }
.site-footer { background: #241d21; color: #fbf7f5; }
.footer-brand p { color: #bbaeb2; }
.social-links a { border-color: #5e5157; }
.footer-column a { color: #c3b8bd; }
.footer-bottom { border-top-color: #4b3d44; color: #b7aab0; }

@media (max-width: 820px) {
  .page-width, .brand-row, .utility-row { width: min(100% - 32px, 1440px); }
  .desktop-only { display: none; }
  .mobile-only { display: grid !important; position: absolute; left: 0; }
  .utility-row { justify-content: space-between; height: 38px; font-size: 11px; }
  .utility-notices { margin-left: 0; }
  .location-link { display: none; }
  .brand-row { height: 65px; }
  .brand { font-size: 22px; letter-spacing: .07em; }
  .brand i { font-size: 18px; }
  .main-nav { position: absolute; top: 99px; left: 0; right: 0; height: auto; display: none; align-items: stretch; gap: 0; padding: 10px 16px 17px; border: 0; background: var(--off-white); box-shadow: 0 8px 16px rgba(28, 37, 32, .1); }
  .main-nav.is-open { display: flex; flex-direction: column; }
  .main-nav a { padding: 13px 0; border-bottom: 1px solid var(--line); }
  .main-nav .mobile-account-nav { display: flex; align-items: center; gap: 9px; color: var(--coral); }
  .mobile-account-nav .iconify { width: 16px; height: 16px; }
  .main-nav a:last-child { border: 0; }
  .search-panel { right: 16px; top: 99px; width: calc(100% - 32px); }
  .hero { min-height: 620px; }
  .hero-content { width: min(100% - 32px, 1440px); padding-bottom: 73px; }
  .hero-controls { right: 16px; left: 16px; bottom: 22px; }
  .benefit-bar { grid-template-columns: 1fr; }
  .benefit-bar > div { justify-content: flex-start; min-height: 59px; padding: 9px 16px; border-right: 0; border-bottom: 1px solid var(--line); }
  .benefit-bar > div:last-child { border-bottom: 0; }
  .category-section { padding: 76px 0 84px; }
  .category-grid { grid-template-columns: repeat(2, 1fr); gap: 12px; margin-top: 34px; }
  .category-caption { padding: 12px 10px 14px; }
  .category-caption strong { font-size: 18px; }
  .product-section { padding: 75px 0 83px; }
  .product-grid { grid-template-columns: repeat(2, 1fr); gap: 28px 12px; margin-top: 32px; }
  .product-request-state { min-height: 220px; align-items: flex-start; flex-wrap: wrap; justify-content: flex-start; margin-top: 32px; padding: 28px 0; }
  .product-retry-button { margin-left: 48px; }
  .escape-section { grid-template-columns: 1fr; }
  .escape-image { min-height: 460px; }
  .escape-content { max-width: none; padding: 72px 32px 80px; }
  .editorial-section { grid-template-columns: 1fr; padding-top: 76px; padding-bottom: 76px; }
  .editorial-card { min-height: 365px; }
  .editorial-copy { min-height: 330px; }
  .newsletter-section { padding: 74px 16px; }
  .footer-grid { grid-template-columns: repeat(2, 1fr); gap: 42px 24px; padding-bottom: 48px; }
  .footer-brand { grid-column: 1 / -1; }
  .footer-bottom { min-height: auto; align-items: flex-start; flex-direction: column; padding-top: 18px; padding-bottom: 21px; }
}

@media (max-width: 490px) {
  .hero { min-height: 555px; background-position: 58% var(--hero-position); }
  .hero-shade { background: linear-gradient(90deg, rgba(30, 16, 22, .53), rgba(30, 16, 22, .08)); }
  .hero h1 { font-size: 50px; }
  .hero-copy { max-width: 300px; font-size: 13px; }
  .progress-dot { width: 25px; }
  .section-heading h2 { font-size: 37px; }
  .section-heading-row { align-items: flex-end; }
  .section-heading-row .text-link { font-size: 9px; white-space: nowrap; }
  .product-info { gap: 4px; }
  .product-info h3 { font-size: 12px; }
  .product-info strong { font-size: 11px; }
  .escape-image { min-height: 390px; }
  .escape-content { padding-inline: 24px; }
  .escape-content h2 { font-size: 44px; }
  .newsletter-form { min-height: 48px; }
  .newsletter-form button { min-width: 115px; padding: 0 9px; font-size: 9px; }
  .newsletter-form input { padding: 0 9px; font-size: 12px; }
  .footer-bottom > div { flex-wrap: wrap; gap: 10px 16px; }
}
</style>
