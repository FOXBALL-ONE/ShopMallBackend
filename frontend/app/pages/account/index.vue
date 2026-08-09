<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { CustomerCart, CustomerOrder, CustomerProfile } from '~/types/customer-account'
import {
  customerRequestMessage
} from '~/composables/useCustomerAccountApi'
import {
  customerStatusLabel,
  customerStatusTone,
  formatCustomerDate,
  formatCustomerMoney,
  orderItemCount,
  parseProductSnapshot
} from '~/utils/customer-display'

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Your account | Pelissa',
  meta: [{ name: 'description', content: 'Manage your Pelissa profile, orders, bag, and delivery updates.' }]
})

const api = useCustomerAccountApi()
const session = useCustomerSession()

const profile = ref<CustomerProfile | null>(null)
const cart = ref<CustomerCart | null>(null)
const orders = ref<CustomerOrder[]>([])
const addressCount = ref(0)
const isLoading = ref(true)
const requestError = ref('')

const displayName = computed(() => profile.value?.first_name?.trim() || profile.value?.username || 'there')
const openOrders = computed(() => orders.value.filter(order => !['CANCELLED', 'DELETED', 'COMPLETED', 'DELIVERED'].includes(order.status)).length)
const deliveredOrders = computed(() => orders.value.filter(order => ['DELIVERED', 'COMPLETED'].includes(order.status)).length)
const bagQuantity = computed(() => cart.value?.total_quantity || 0)
const bagSubtotal = computed(() => formatCustomerMoney(cart.value?.subtotal, profile.value?.currency || 'USD'))
const recentOrders = computed(() => orders.value.slice(0, 3))

function orderItemPreview(order: CustomerOrder) {
  const firstItem = order.items[0]
  if (!firstItem) return 'No item details yet'
  const item = parseProductSnapshot(firstItem.product_snapshot)
  const extra = order.items.length > 1 ? ` + ${order.items.length - 1} more` : ''
  return `${item.name}${extra}`
}

function orderAddress(order: CustomerOrder) {
  const address = order.shipping_address
  return [address.city, address.state_or_province].filter(Boolean).join(', ') || 'Address on file'
}

async function loadDashboard() {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  isLoading.value = true
  requestError.value = ''

  const [profileResult, ordersResult, cartResult, addressesResult] = await Promise.allSettled([
    api.getProfile(userId),
    api.getOrders(1, 5),
    api.getCart(),
    api.getAddresses()
  ])

  if (profileResult.status === 'fulfilled') profile.value = profileResult.value
  if (ordersResult.status === 'fulfilled') orders.value = ordersResult.value.list || []
  if (cartResult.status === 'fulfilled') cart.value = cartResult.value
  if (addressesResult.status === 'fulfilled') addressCount.value = addressesResult.value.list?.length || 0

  const failures = [profileResult, ordersResult, cartResult, addressesResult].filter(result => result.status === 'rejected')
  if (failures.length && !profile.value && !orders.value.length && !cart.value) {
    requestError.value = customerRequestMessage(failures[0]?.reason, 'We could not load your account just now.')
  } else if (failures.length) {
    requestError.value = 'Some account details are still catching up. You can refresh to try again.'
  }

  isLoading.value = false
}

onMounted(() => {
  void loadDashboard()
})
</script>

<template>
  <CustomerAccountShell
    eyebrow="THE MEMBER EDIT · 01"
    title="A quiet place for your edit."
    intro="Keep your details close, revisit every order, and follow each piece from our studio to your door."
    :profile="profile"
  >
    <div v-if="isLoading" class="account-loading" aria-live="polite">
      <div class="account-loading-line wide" />
      <div class="account-loading-stats">
        <div v-for="index in 3" :key="index" class="account-loading-card" />
      </div>
      <div class="account-loading-panel" />
    </div>

    <template v-else>
      <div v-if="requestError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" />
        <span>{{ requestError }}</span>
        <button type="button" @click="loadDashboard">Refresh</button>
      </div>

      <section class="dashboard-welcome">
        <div>
          <p class="panel-kicker">WELCOME BACK</p>
          <h2>Good to see you, {{ displayName }}.</h2>
          <p>Everything you love, gathered in one place — from saved details to the latest delivery note.</p>
        </div>
        <NuxtLink class="panel-link" to="/collections/new">
          Explore new in <UIcon name="i-lucide-arrow-up-right" />
        </NuxtLink>
      </section>

      <section class="dashboard-stats" aria-label="Account summary">
        <NuxtLink class="dashboard-stat" to="/account/orders">
          <span class="dashboard-stat-number">{{ orders.length }}</span>
          <span class="dashboard-stat-label">Recent orders</span>
          <UIcon name="i-lucide-arrow-up-right" />
        </NuxtLink>
        <NuxtLink class="dashboard-stat" to="/account/logistics">
          <span class="dashboard-stat-number">{{ openOrders }}</span>
          <span class="dashboard-stat-label">In the making</span>
          <UIcon name="i-lucide-arrow-up-right" />
        </NuxtLink>
        <NuxtLink class="dashboard-stat" to="/cart">
          <span class="dashboard-stat-number">{{ bagQuantity }}</span>
          <span class="dashboard-stat-label">Pieces in your bag</span>
          <UIcon name="i-lucide-arrow-up-right" />
        </NuxtLink>
      </section>

      <section class="dashboard-grid">
        <article class="dashboard-panel dashboard-orders-panel">
          <div class="panel-heading">
            <div>
              <p class="panel-kicker">RECENTLY YOURS</p>
              <h2>Orders in motion</h2>
            </div>
            <NuxtLink class="panel-text-link" to="/account/orders">View all <UIcon name="i-lucide-arrow-right" /></NuxtLink>
          </div>

          <div v-if="recentOrders.length" class="mini-order-list">
            <NuxtLink v-for="order in recentOrders" :key="order.order_no" class="mini-order" :to="{ path: '/account/logistics', query: { order_no: order.order_no } }">
              <span class="mini-order-index">{{ String(recentOrders.indexOf(order) + 1).padStart(2, '0') }}</span>
              <span class="mini-order-copy">
                <strong>{{ orderItemPreview(order) }}</strong>
                <small>{{ order.order_no }} · {{ orderAddress(order) }}</small>
              </span>
              <span class="status-pill" :class="`tone-${customerStatusTone(order.status)}`">{{ customerStatusLabel(order.status) }}</span>
              <UIcon name="i-lucide-arrow-up-right" />
            </NuxtLink>
          </div>
          <div v-else class="panel-empty">
            <UIcon name="i-lucide-package-open" />
            <p>Your first order will feel right at home here.</p>
            <NuxtLink to="/collections/shop">Start exploring <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
          </div>
        </article>

        <article class="dashboard-panel dashboard-bag-panel">
          <div class="panel-heading">
            <div>
              <p class="panel-kicker">THE FITTING ROOM</p>
              <h2>Your bag</h2>
            </div>
            <UIcon name="i-lucide-shopping-bag" class="panel-heading-icon" />
          </div>
          <div class="bag-summary-art">
            <img src="/lingerie/lace-texture.jpg" alt="Lace texture" loading="lazy">
            <div>
              <strong>{{ bagQuantity ? `${bagQuantity} ${bagQuantity === 1 ? 'piece' : 'pieces'}` : 'A little space' }}</strong>
              <span>{{ bagQuantity ? `${bagSubtotal} ready when you are.` : 'Your next favorite might be waiting.' }}</span>
            </div>
          </div>
          <NuxtLink class="panel-button" to="/cart">
            {{ bagQuantity ? 'Review your bag' : 'Shop the collection' }} <UIcon name="i-lucide-arrow-up-right" />
          </NuxtLink>
        </article>

        <article class="dashboard-panel dashboard-details-panel">
          <div class="panel-heading">
            <div>
              <p class="panel-kicker">YOUR DETAILS</p>
              <h2>Made personal</h2>
            </div>
            <NuxtLink class="panel-text-link" to="/account/profile">Edit <UIcon name="i-lucide-arrow-right" /></NuxtLink>
          </div>
          <div class="detail-lines">
            <div><span>Email</span><strong>{{ profile?.email || 'Not available' }}</strong></div>
            <div><span>Delivery addresses</span><strong>{{ addressCount }} saved</strong></div>
            <div><span>Completed orders</span><strong>{{ deliveredOrders }}</strong></div>
          </div>
        </article>

        <article class="dashboard-note-panel">
          <span class="note-mark">P°</span>
          <p class="panel-kicker">A NOTE FROM THE STUDIO</p>
          <blockquote>“The best pieces are the ones that make the rest of your day feel more like you.”</blockquote>
          <NuxtLink to="/collections/lounge">Find your soft start <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
        </article>
      </section>
    </template>
  </CustomerAccountShell>
</template>

<style scoped>
.account-loading {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.account-loading-line,
.account-loading-card,
.account-loading-panel {
  position: relative;
  overflow: hidden;
  background: #eee2e1;
}

.account-loading-line::after,
.account-loading-card::after,
.account-loading-panel::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,.58), transparent);
  content: '';
  animation: dashboard-shimmer 1.4s infinite;
}

.account-loading-line {
  width: 42%;
  height: 16px;
}

.account-loading-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.account-loading-card {
  min-height: 128px;
}

.account-loading-panel {
  min-height: 290px;
}

@keyframes dashboard-shimmer {
  from { transform: translateX(-100%); }
  to { transform: translateX(100%); }
}

.account-notice {
  min-height: 48px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  padding: 11px 14px;
  border: 1px solid var(--store-line);
  font-size: 12px;
}

.account-notice .iconify {
  flex: 0 0 auto;
  width: 16px;
  height: 16px;
}

.account-notice-warning {
  color: var(--store-wine-dark);
  background: #f7e9e7;
}

.account-notice button {
  margin-left: auto;
  padding: 0;
  border: 0;
  color: inherit;
  background: none;
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .08em;
  text-decoration: underline;
  text-transform: uppercase;
}

.dashboard-welcome {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  margin-bottom: 18px;
  padding: 25px 27px 26px;
  color: #fff;
  background: var(--store-ink);
}

.dashboard-welcome h2,
.dashboard-panel h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(28px, 3vw, 42px);
  font-weight: 500;
  letter-spacing: -.03em;
  line-height: 1;
}

.dashboard-welcome p:not(.panel-kicker) {
  max-width: 570px;
  margin: 11px 0 0;
  color: #c9bcc1;
  font-size: 12px;
  line-height: 1.65;
}

.panel-kicker {
  margin: 0 0 9px;
  color: var(--store-blush);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .13em;
  line-height: 1.2;
  text-transform: uppercase;
}

.dashboard-welcome .panel-link {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 13px;
  border: 1px solid #74646b;
  color: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-decoration: none;
  text-transform: uppercase;
}

.dashboard-welcome .panel-link:hover {
  border-color: var(--store-blush);
  color: var(--store-blush);
}

.dashboard-welcome .iconify,
.panel-link .iconify {
  width: 14px;
  height: 14px;
}

.dashboard-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 30px;
}

.dashboard-stat {
  position: relative;
  min-height: 126px;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 18px 19px 16px;
  border: 1px solid var(--store-line);
  color: var(--store-ink);
  background: rgba(255,255,255,.36);
  text-decoration: none;
  transition: color .2s ease, background .2s ease, transform .2s ease;
}

.dashboard-stat:hover {
  color: #fff;
  background: var(--store-wine);
  transform: translateY(-3px);
}

.dashboard-stat-number {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 43px;
  line-height: .9;
}

.dashboard-stat-label {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.dashboard-stat:hover .dashboard-stat-label {
  color: rgba(255,255,255,.78);
}

.dashboard-stat > .iconify {
  position: absolute;
  right: 15px;
  bottom: 15px;
  width: 15px;
  height: 15px;
}

.dashboard-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(245px, .7fr);
  gap: 14px;
}

.dashboard-panel,
.dashboard-note-panel {
  min-width: 0;
  border: 1px solid var(--store-line);
  background: rgba(255,255,255,.3);
}

.dashboard-panel {
  padding: 24px 22px 23px;
}

.dashboard-orders-panel {
  min-height: 300px;
}

.panel-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 15px;
  margin-bottom: 22px;
}

.panel-heading-icon {
  width: 19px;
  height: 19px;
  color: var(--store-wine);
}

.panel-text-link,
.dashboard-note-panel > a,
.panel-empty a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--store-wine-dark);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-decoration: none;
  text-transform: uppercase;
  white-space: nowrap;
}

.panel-text-link:hover,
.dashboard-note-panel > a:hover,
.panel-empty a:hover {
  color: var(--store-ink);
}

.panel-text-link .iconify,
.dashboard-note-panel > a .iconify,
.panel-empty a .iconify {
  width: 13px;
  height: 13px;
}

.mini-order-list {
  display: flex;
  flex-direction: column;
}

.mini-order {
  min-width: 0;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr) auto 15px;
  align-items: center;
  gap: 9px;
  padding: 13px 0;
  border-top: 1px solid rgba(36, 29, 33, .1);
  color: var(--store-ink);
  text-decoration: none;
}

.mini-order:hover {
  color: var(--store-wine-dark);
}

.mini-order-index {
  color: var(--store-plum);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
}

.mini-order-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mini-order-copy strong {
  overflow: hidden;
  font-size: 12px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-order-copy small {
  overflow: hidden;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .025em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-order > .iconify {
  width: 14px;
  height: 14px;
  opacity: .55;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 0 8px;
  border: 1px solid transparent;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .04em;
  text-transform: uppercase;
  white-space: nowrap;
}

.tone-warm {
  color: var(--store-wine-dark);
  border-color: #d8afb0;
  background: #f6e5e4;
}

.tone-accent {
  color: #496b6a;
  border-color: #abc7c2;
  background: #e7f0ee;
}

.tone-success {
  color: #4e654d;
  border-color: #b8c9ad;
  background: #edf2e7;
}

.tone-muted {
  color: var(--store-muted);
  border-color: #d1c7c7;
  background: #efebea;
}

.panel-empty {
  min-height: 170px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: 12px;
  color: var(--store-muted);
  text-align: center;
}

.panel-empty > .iconify {
  width: 23px;
  height: 23px;
  color: var(--store-blush);
}

.panel-empty p {
  max-width: 220px;
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
}

.bag-summary-art {
  min-height: 126px;
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  align-items: stretch;
  gap: 15px;
  margin-bottom: 17px;
  background: var(--store-linen);
}

.bag-summary-art img {
  width: 100%;
  height: 126px;
  display: block;
  object-fit: cover;
}

.bag-summary-art > div {
  display: flex;
  justify-content: center;
  flex-direction: column;
  gap: 7px;
  padding: 12px 12px 12px 0;
}

.bag-summary-art strong {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 22px;
  font-weight: 500;
  line-height: 1;
}

.bag-summary-art span {
  color: var(--store-muted);
  font-size: 11px;
  line-height: 1.45;
}

.panel-button {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .07em;
  text-decoration: none;
  text-transform: uppercase;
  transition: color .2s ease, background .2s ease;
}

.panel-button:hover {
  color: var(--store-ink);
  background: transparent;
}

.panel-button .iconify {
  width: 14px;
  height: 14px;
}

.dashboard-details-panel {
  min-height: 236px;
}

.detail-lines {
  display: flex;
  flex-direction: column;
}

.detail-lines > div {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 0;
  border-top: 1px solid rgba(36, 29, 33, .1);
}

.detail-lines span {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.detail-lines strong {
  max-width: 62%;
  overflow: hidden;
  color: var(--store-ink);
  font-size: 11px;
  font-weight: 600;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dashboard-note-panel {
  position: relative;
  min-height: 236px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-direction: column;
  padding: 25px 23px 23px;
  color: #fff;
  background: var(--store-wine);
}

.note-mark {
  position: absolute;
  top: 16px;
  right: 21px;
  color: rgba(255,255,255,.5);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 35px;
}

.dashboard-note-panel .panel-kicker {
  color: #f0caca;
}

.dashboard-note-panel blockquote {
  max-width: 280px;
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(22px, 2.5vw, 31px);
  font-style: italic;
  letter-spacing: -.025em;
  line-height: 1.08;
}

.dashboard-note-panel > a {
  color: #fff;
}

.dashboard-note-panel > a:hover {
  color: #f0caca;
}

@media (max-width: 920px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .dashboard-note-panel {
    min-height: 190px;
  }
}

@media (max-width: 640px) {
  .dashboard-welcome {
    align-items: flex-start;
    flex-direction: column;
    padding: 22px 19px 21px;
  }

  .dashboard-stats {
    gap: 8px;
  }

  .dashboard-stat {
    min-height: 112px;
    padding: 14px 12px;
  }

  .dashboard-stat-number {
    font-size: 34px;
  }

  .dashboard-stat-label {
    max-width: 80px;
    font-size: 8px;
    line-height: 1.4;
  }

  .dashboard-panel {
    padding: 20px 16px;
  }

  .mini-order {
    grid-template-columns: 23px minmax(0, 1fr) 14px;
  }

  .mini-order .status-pill {
    grid-column: 2;
    justify-self: start;
  }

  .mini-order > .iconify {
    grid-column: 3;
    grid-row: 1 / span 2;
  }
}

@media (prefers-reduced-motion: reduce) {
  .account-loading-line::after,
  .account-loading-card::after,
  .account-loading-panel::after,
  .panel-button,
  .dashboard-stat {
    animation: none;
    transition: none;
  }
}
</style>
