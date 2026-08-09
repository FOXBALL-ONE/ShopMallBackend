<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CustomerProfile } from '~/types/customer-account'
import { customerInitials } from '~/utils/customer-display'

type AccountNavItem = {
  label: string
  note: string
  to: string
  icon: string
}

const props = withDefaults(defineProps<{
  eyebrow: string
  title: string
  intro?: string
  profile?: CustomerProfile | null
}>(), {
  intro: '',
  profile: null
})

const route = useRoute()
const router = useRouter()
const toast = useToast()
const authApi = useCustomerAuthApi()
const isMenuOpen = ref(false)
const isSigningOut = ref(false)

const navItems: AccountNavItem[] = [
  { label: 'Overview', note: 'Your Pelissa edit', to: '/account', icon: 'i-lucide-layout-dashboard' },
  { label: 'Profile', note: 'Details & preferences', to: '/account/profile', icon: 'i-lucide-circle-user-round' },
  { label: 'Orders', note: 'History & status', to: '/account/orders', icon: 'i-lucide-receipt-text' },
  { label: 'Delivery', note: 'Track your parcels', to: '/account/logistics', icon: 'i-lucide-truck' },
  { label: 'Shopping bag', note: 'Pieces waiting for you', to: '/cart', icon: 'i-lucide-shopping-bag' }
]

const displayName = computed(() => {
  const fullName = [props.profile?.first_name, props.profile?.last_name]
    .filter(value => value?.trim())
    .join(' ')
  return fullName || props.profile?.username || 'Pelissa member'
})

const initials = computed(() => customerInitials(props.profile?.first_name, props.profile?.last_name, 'P'))

function isCurrent(item: AccountNavItem) {
  if (item.to === '/account') return route.path === '/account'
  return route.path === item.to || route.path.startsWith(`${item.to}/`)
}

function closeMenu() {
  isMenuOpen.value = false
}

async function signOut() {
  if (isSigningOut.value) return
  isSigningOut.value = true
  try {
    await authApi.logout()
    toast.add({ title: 'Signed out', description: 'Come back whenever you are ready.', color: 'success' })
    await router.replace('/')
  } catch (error: unknown) {
    toast.add({ title: 'Signed out locally', description: 'Your local session was cleared.', color: 'warning' })
    await router.replace('/')
  } finally {
    isSigningOut.value = false
  }
}
</script>

<template>
  <main class="store-page customer-account-page">
    <StoreHeader />

    <section class="account-masthead">
      <div class="store-container account-masthead-inner">
        <div class="account-masthead-copy">
          <p class="store-eyebrow">{{ props.eyebrow }}</p>
          <h1>{{ props.title }}</h1>
          <p v-if="props.intro" class="account-masthead-intro">{{ props.intro }}</p>
        </div>
        <div class="account-masthead-art" aria-hidden="true">
          <div class="account-art-image" />
          <span class="account-art-index">MEMBER / 01</span>
          <span class="account-art-mark">P°</span>
        </div>
      </div>
    </section>

    <section class="store-container account-body">
      <aside class="account-sidebar">
        <button class="account-sidebar-toggle" type="button" @click="isMenuOpen = !isMenuOpen">
          <span><UIcon name="i-lucide-menu" /> Account menu</span>
          <UIcon :name="isMenuOpen ? 'i-lucide-chevron-up' : 'i-lucide-chevron-down'" />
        </button>

        <div class="account-sidebar-content" :class="{ 'is-open': isMenuOpen }">
          <div class="account-identity">
            <div v-if="props.profile?.avatar" class="account-avatar account-avatar-image">
              <img :src="props.profile.avatar" :alt="displayName">
            </div>
            <div v-else class="account-avatar">{{ initials }}</div>
            <div>
              <span class="account-identity-label">SIGNED IN AS</span>
              <strong>{{ displayName }}</strong>
              <small>{{ props.profile?.email || 'Your Pelissa account' }}</small>
            </div>
          </div>

          <nav class="account-nav" aria-label="Account navigation">
            <NuxtLink
              v-for="item in navItems"
              :key="item.to"
              :to="item.to"
              class="account-nav-item"
              :class="{ active: isCurrent(item) }"
              @click="closeMenu"
            >
              <span class="account-nav-icon"><UIcon :name="item.icon" /></span>
              <span class="account-nav-copy">
                <strong>{{ item.label }}</strong>
                <small>{{ item.note }}</small>
              </span>
              <UIcon class="account-nav-arrow" name="i-lucide-arrow-up-right" />
            </NuxtLink>
          </nav>

          <div class="account-sidebar-bottom">
            <NuxtLink class="account-back-shop" to="/collections/shop" @click="closeMenu">
              <UIcon name="i-lucide-arrow-left" /> Back to the shop
            </NuxtLink>
            <button class="account-sign-out" type="button" :disabled="isSigningOut" @click="signOut">
              <UIcon :name="isSigningOut ? 'i-lucide-loader-circle' : 'i-lucide-log-out'" :class="{ 'is-spinning': isSigningOut }" />
              {{ isSigningOut ? 'Signing out…' : 'Sign out' }}
            </button>
          </div>
        </div>
      </aside>

      <section class="account-content">
        <slot />
      </section>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.customer-account-page {
  background:
    linear-gradient(180deg, var(--store-paper) 0, var(--store-paper) 84%, #f3e9e8 100%);
}

.account-masthead {
  border-bottom: 1px solid var(--store-line);
  background: var(--store-linen);
}

.account-masthead-inner {
  min-height: 278px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 390px);
  align-items: stretch;
  gap: 48px;
}

.account-masthead-copy {
  display: flex;
  justify-content: center;
  flex-direction: column;
  padding: 48px 0 53px;
}

.account-masthead-copy h1 {
  max-width: 780px;
  margin: 0;
  color: var(--store-ink);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(44px, 5.2vw, 74px);
  font-weight: 500;
  letter-spacing: -.04em;
  line-height: .96;
}

.account-masthead-intro {
  max-width: 510px;
  margin: 20px 0 0;
  color: var(--store-muted);
  font-size: 14px;
  line-height: 1.7;
}

.account-masthead-art {
  position: relative;
  min-height: 278px;
  overflow: hidden;
  background: var(--store-ink);
}

.account-art-image {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(36, 29, 33, .05), rgba(36, 29, 33, .62)), url('/lingerie/hero-lace.jpg') center 42% / cover;
  opacity: .86;
}

.account-masthead-art::after {
  position: absolute;
  inset: 14px;
  border: 1px solid rgba(255, 255, 255, .35);
  content: '';
  pointer-events: none;
}

.account-art-index,
.account-art-mark {
  position: absolute;
  z-index: 1;
  color: rgba(255, 255, 255, .85);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .14em;
  text-transform: uppercase;
}

.account-art-index {
  top: 27px;
  left: 28px;
}

.account-art-mark {
  right: 27px;
  bottom: 21px;
  color: var(--store-blush);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 40px;
  letter-spacing: -.06em;
  text-transform: none;
}

.account-body {
  display: grid;
  grid-template-columns: 285px minmax(0, 1fr);
  align-items: start;
  gap: clamp(34px, 5vw, 84px);
  padding-top: 56px;
  padding-bottom: 96px;
}

.account-sidebar {
  position: sticky;
  top: 22px;
  min-width: 0;
}

.account-sidebar-toggle {
  display: none;
}

.account-sidebar-content {
  display: block;
}

.account-identity {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 25px;
  border-bottom: 1px solid var(--store-line);
}

.account-avatar {
  width: 54px;
  height: 54px;
  flex: 0 0 54px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #fff;
  background: var(--store-wine);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 22px;
  letter-spacing: -.04em;
}

.account-avatar-image img {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: cover;
}

.account-identity > div:last-child {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.account-identity-label {
  color: var(--store-plum);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .11em;
}

.account-identity strong {
  overflow: hidden;
  color: var(--store-ink);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-identity small {
  overflow: hidden;
  color: var(--store-muted);
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-nav {
  display: flex;
  flex-direction: column;
  padding: 18px 0;
}

.account-nav-item {
  position: relative;
  min-height: 62px;
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 9px;
  padding: 9px 8px;
  border-bottom: 1px solid rgba(36, 29, 33, .08);
  color: var(--store-muted);
  text-decoration: none;
  transition: color .2s ease, background .2s ease, padding .2s ease;
}

.account-nav-item:first-child {
  border-top: 1px solid rgba(36, 29, 33, .08);
}

.account-nav-item:hover,
.account-nav-item.active {
  padding-left: 13px;
  color: var(--store-ink);
  background: rgba(255, 255, 255, .58);
}

.account-nav-item.active::before {
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 3px;
  background: var(--store-wine);
  content: '';
}

.account-nav-icon {
  width: 31px;
  height: 31px;
  display: grid;
  place-items: center;
  border: 1px solid var(--store-line);
  color: var(--store-plum);
  background: rgba(255, 255, 255, .42);
}

.account-nav-icon .iconify {
  width: 15px;
  height: 15px;
}

.account-nav-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.account-nav-copy strong {
  color: inherit;
  font-size: 12px;
  font-weight: 600;
}

.account-nav-copy small {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .03em;
  text-transform: uppercase;
}

.account-nav-arrow {
  width: 14px;
  height: 14px;
  opacity: .4;
  transition: opacity .2s ease, transform .2s ease;
}

.account-nav-item:hover .account-nav-arrow,
.account-nav-item.active .account-nav-arrow {
  opacity: 1;
  transform: translate(2px, -2px);
}

.account-sidebar-bottom {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding-top: 8px;
}

.account-back-shop,
.account-sign-out {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  padding: 0;
  border: 0;
  color: var(--store-muted);
  background: none;
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-decoration: none;
  text-transform: uppercase;
  transition: color .2s ease;
}

.account-back-shop:hover,
.account-sign-out:hover:not(:disabled) {
  color: var(--store-wine);
}

.account-sign-out:disabled {
  cursor: wait;
  opacity: .55;
}

.account-back-shop .iconify,
.account-sign-out .iconify {
  width: 14px;
  height: 14px;
}

.account-content {
  min-width: 0;
}

.is-spinning {
  animation: account-spin .8s linear infinite;
}

@keyframes account-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 980px) {
  .account-masthead-inner {
    grid-template-columns: minmax(0, 1fr) 300px;
    gap: 28px;
  }

  .account-body {
    grid-template-columns: 230px minmax(0, 1fr);
    gap: 32px;
  }

  .account-nav-item {
    grid-template-columns: 30px minmax(0, 1fr);
  }

  .account-nav-arrow {
    display: none;
  }
}

@media (max-width: 760px) {
  .account-masthead-inner {
    min-height: auto;
    display: block;
  }

  .account-masthead-copy {
    padding: 42px 0 34px;
  }

  .account-masthead-art {
    min-height: 170px;
  }

  .account-body {
    display: block;
    padding-top: 24px;
    padding-bottom: 70px;
  }

  .account-sidebar {
    position: static;
    margin-bottom: 30px;
  }

  .account-sidebar-toggle {
    width: 100%;
    min-height: 48px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 14px;
    border: 1px solid var(--store-line);
    color: var(--store-ink);
    background: rgba(255, 255, 255, .55);
    cursor: pointer;
    font-family: 'DM Mono', monospace;
    font-size: 9px;
    letter-spacing: .08em;
    text-transform: uppercase;
  }

  .account-sidebar-toggle span {
    display: inline-flex;
    align-items: center;
    gap: 9px;
  }

  .account-sidebar-toggle .iconify {
    width: 15px;
    height: 15px;
  }

  .account-sidebar-content {
    display: none;
    padding: 17px 0 0;
  }

  .account-sidebar-content.is-open {
    display: block;
  }

  .account-identity {
    padding-bottom: 18px;
  }

  .account-nav {
    padding-block: 12px;
  }
}

@media (max-width: 480px) {
  .account-masthead-copy h1 {
    font-size: clamp(41px, 12vw, 58px);
  }

  .account-masthead-intro {
    font-size: 12px;
  }
}
</style>
