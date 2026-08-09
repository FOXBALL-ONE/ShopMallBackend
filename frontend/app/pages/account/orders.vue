<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { CustomerOrder, CustomerProfile } from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import {
  customerStatusLabel,
  customerStatusTone,
  formatCustomerDate,
  formatCustomerMoney,
  orderItemCount,
  parseProductSnapshot
} from '~/utils/customer-display'

type OrderFilter = 'ALL' | 'OPEN' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED'

const filterOptions: Array<{ key: OrderFilter; label: string }> = [
  { key: 'ALL', label: 'All orders' },
  { key: 'OPEN', label: 'In progress' },
  { key: 'SHIPPED', label: 'On the way' },
  { key: 'DELIVERED', label: 'Delivered' },
  { key: 'CANCELLED', label: 'Cancelled' }
]

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Orders | Pelissa',
  meta: [{ name: 'description', content: 'Review your Pelissa orders and follow each piece from studio to door.' }]
})

const api = useCustomerAccountApi()
const session = useCustomerSession()
const router = useRouter()
const toast = useToast()

const profile = ref<CustomerProfile | null>(null)
const orders = ref<CustomerOrder[]>([])
const activeFilter = ref<OrderFilter>('ALL')
const page = ref(1)
const pageSize = 8
const totalPages = ref(1)
const expandedOrderNo = ref<string | null>(null)
const cancelTarget = ref<string | null>(null)
const cancelReason = ref('')
const isLoading = ref(true)
const isRefreshing = ref(false)
const isCancelling = ref(false)
const requestError = ref('')

const visibleOrders = computed(() => orders.value.filter(order => {
  if (activeFilter.value === 'ALL') return true
  if (activeFilter.value === 'OPEN') return ['PENDING_PAYMENT', 'PAID'].includes(order.status)
  if (activeFilter.value === 'SHIPPED') return ['SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].includes(order.status)
  if (activeFilter.value === 'DELIVERED') return ['DELIVERED', 'COMPLETED'].includes(order.status)
  return ['CANCELLED', 'DELETED'].includes(order.status)
}))

const pageLabel = computed(() => `${page.value} / ${Math.max(totalPages.value, 1)}`)
const resultLabel = computed(() => `${visibleOrders.value.length} ${visibleOrders.value.length === 1 ? 'order' : 'orders'} on this page`)

function orderAddress(order: CustomerOrder) {
  const address = order.shipping_address
  return [address.city, address.state_or_province, address.country].filter(Boolean).join(', ') || 'Address on file'
}

function itemPreview(order: CustomerOrder) {
  const first = order.items[0]
  if (!first) return { name: 'Pelissa piece', color: null as string | null, image: null as string | null }
  return parseProductSnapshot(first.product_snapshot)
}

function orderCanCancel(order: CustomerOrder) {
  return order.status === 'PENDING_PAYMENT'
}

function toggleOrder(orderNo: string) {
  expandedOrderNo.value = expandedOrderNo.value === orderNo ? null : orderNo
  if (cancelTarget.value && cancelTarget.value !== orderNo) cancelTarget.value = null
}

function setFilter(filter: OrderFilter) {
  activeFilter.value = filter
  expandedOrderNo.value = null
}

async function loadOrders(showLoading = true) {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  if (showLoading) isLoading.value = true
  else isRefreshing.value = true
  requestError.value = ''
  try {
    const [profileResult, ordersResult] = await Promise.all([
      profile.value ? Promise.resolve(profile.value) : api.getProfile(userId),
      api.getOrders(page.value, pageSize)
    ])
    profile.value = profileResult
    orders.value = ordersResult.list || []
    totalPages.value = Math.max(Number(ordersResult.pagination?.count || 1), 1)
    if (page.value > totalPages.value) {
      page.value = totalPages.value
      await loadOrders(showLoading)
      return
    }
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not load your orders.')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

async function changePage(nextPage: number) {
  if (nextPage < 1 || nextPage > totalPages.value || nextPage === page.value || isLoading.value) return
  page.value = nextPage
  await loadOrders()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function openCancel(order: CustomerOrder) {
  cancelTarget.value = order.order_no
  cancelReason.value = ''
  expandedOrderNo.value = order.order_no
}

function closeCancel() {
  if (isCancelling.value) return
  cancelTarget.value = null
  cancelReason.value = ''
}

async function cancelOrder(order: CustomerOrder) {
  if (isCancelling.value || !orderCanCancel(order)) return
  if (import.meta.client && !window.confirm(`Cancel order ${order.order_no}?`)) return

  isCancelling.value = true
  requestError.value = ''
  try {
    await api.cancelOrder(order.order_no, cancelReason.value.trim() || undefined)
    isCancelling.value = false
    closeCancel()
    toast.add({ title: 'Order cancelled', description: `${order.order_no} has been cancelled and the items returned to stock.`, color: 'success' })
    await loadOrders(false)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'This order could not be cancelled.')
    toast.add({ title: 'Unable to cancel order', description: requestError.value, color: 'error' })
  } finally {
    isCancelling.value = false
  }
}

async function viewLogistics(order: CustomerOrder) {
  await router.push({ path: '/account/logistics', query: { order_no: order.order_no } })
}

onMounted(() => {
  void loadOrders()
})
</script>
<template>
  <CustomerAccountShell
    eyebrow="THE ORDER LEDGER · 03"
    title="Every piece, remembered."
    intro="Revisit your Pelissa orders, open the details, and follow the ones already making their way to you."
    :profile="profile"
  >
    <div v-if="isLoading" class="orders-loading" aria-live="polite">
      <div class="orders-loading-toolbar" />
      <div v-for="index in 3" :key="index" class="orders-loading-card" />
    </div>

    <template v-else>
      <div v-if="requestError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" />
        <span>{{ requestError }}</span>
        <button type="button" @click="loadOrders(false)">Refresh</button>
      </div>

      <section class="orders-toolbar">
        <div>
          <p class="store-eyebrow">YOUR HISTORY</p>
          <h2>{{ resultLabel }}</h2>
        </div>
        <button class="refresh-button" type="button" :disabled="isRefreshing" @click="loadOrders(false)">
          <UIcon :name="isRefreshing ? 'i-lucide-loader-circle' : 'i-lucide-refresh-cw'" :class="{ 'is-spinning': isRefreshing }" />
          {{ isRefreshing ? 'Refreshing…' : 'Refresh' }}
        </button>
      </section>

      <nav class="order-filters" aria-label="Filter orders">
        <button
          v-for="filter in filterOptions"
          :key="filter.key"
          type="button"
          :class="{ active: activeFilter === filter.key }"
          @click="setFilter(filter.key)"
        >
          {{ filter.label }}
        </button>
      </nav>

      <div v-if="!visibleOrders.length" class="orders-empty">
        <span class="empty-order-number">03</span>
        <div>
          <p class="panel-kicker">A LITTLE SPACE FOR WHAT'S NEXT</p>
          <h2>{{ orders.length ? 'Nothing in this edit yet.' : 'Your first order is waiting.' }}</h2>
          <p>{{ orders.length ? 'Try another filter or return to the collection for a fresh start.' : 'When something feels like you, it will appear here with every detail close at hand.' }}</p>
          <NuxtLink class="store-button" to="/collections/shop"><UIcon name="i-lucide-arrow-up-right" /> Explore the collection</NuxtLink>
        </div>
      </div>

      <section v-else class="order-list" aria-label="Order list">
        <article v-for="(order, index) in visibleOrders" :key="order.order_no" class="order-card" :class="{ expanded: expandedOrderNo === order.order_no }">
          <header class="order-card-header">
            <div class="order-card-number">{{ String((page - 1) * pageSize + index + 1).padStart(2, '0') }}</div>
            <div class="order-card-heading">
              <span class="order-card-eyebrow">ORDER {{ order.order_no }}</span>
              <h3>{{ formatCustomerDate(order.created_at) }}</h3>
              <p>{{ orderItemCount(order.items) }} {{ orderItemCount(order.items) === 1 ? 'piece' : 'pieces' }} · {{ orderAddress(order) }}</p>
            </div>
            <div class="order-card-total">
              <span class="status-pill" :class="`tone-${customerStatusTone(order.status)}`">{{ customerStatusLabel(order.status) }}</span>
              <strong>{{ formatCustomerMoney(order.total_amount, order.currency) }}</strong>
            </div>
            <button class="order-expand-button" type="button" :aria-expanded="expandedOrderNo === order.order_no" :aria-label="`${expandedOrderNo === order.order_no ? 'Close' : 'Open'} order ${order.order_no}`" @click="toggleOrder(order.order_no)">
              <UIcon :name="expandedOrderNo === order.order_no ? 'i-lucide-minus' : 'i-lucide-plus'" />
            </button>
          </header>

          <div class="order-card-preview">
            <div class="order-preview-image">
              <img v-if="itemPreview(order).image" :src="itemPreview(order).image!" :alt="itemPreview(order).name">
              <span v-else>P°</span>
            </div>
            <div class="order-preview-copy">
              <strong>{{ itemPreview(order).name }}</strong>
              <span v-if="itemPreview(order).color">{{ itemPreview(order).color }}</span>
              <span v-if="order.items.length > 1">+ {{ order.items.length - 1 }} more {{ order.items.length === 2 ? 'piece' : 'pieces' }}</span>
            </div>
            <div class="order-preview-meta">
              <span>{{ order.currency }}</span>
              <span v-if="order.shipped_at">Shipped {{ formatCustomerDate(order.shipped_at) }}</span>
              <span v-else-if="order.paid_at">Paid {{ formatCustomerDate(order.paid_at) }}</span>
              <span v-else>Placed {{ formatCustomerDate(order.created_at) }}</span>
            </div>
          </div>

          <div v-if="expandedOrderNo === order.order_no" class="order-details">
            <div class="details-columns">
              <div class="order-detail-section">
                <p class="panel-kicker">ITEMS IN THIS ORDER</p>
                <div class="order-item-list">
                  <div v-for="item in order.items" :key="item.id" class="order-item-row">
                    <div class="order-item-image">
                      <img v-if="parseProductSnapshot(item.product_snapshot).image" :src="parseProductSnapshot(item.product_snapshot).image!" :alt="parseProductSnapshot(item.product_snapshot).name">
                      <span v-else>P°</span>
                    </div>
                    <div class="order-item-copy">
                      <strong>{{ parseProductSnapshot(item.product_snapshot).name }}</strong>
                      <span v-if="parseProductSnapshot(item.product_snapshot).color">{{ parseProductSnapshot(item.product_snapshot).color }}</span>
                      <small>Qty {{ item.quantity }}</small>
                    </div>
                    <strong class="order-item-price">{{ formatCustomerMoney(item.line_total, order.currency) }}</strong>
                  </div>
                </div>
              </div>

              <div class="order-detail-section order-address-detail">
                <p class="panel-kicker">DELIVERING TO</p>
                <strong>{{ order.shipping_address.name }}</strong>
                <span>{{ order.shipping_address.phone }}</span>
                <p>{{ [order.shipping_address.address1, order.shipping_address.address2, order.shipping_address.city, order.shipping_address.state_or_province, order.shipping_address.postal_code, order.shipping_address.country].filter(Boolean).join(', ') }}</p>
                <span v-if="order.client_message" class="order-message"><UIcon name="i-lucide-message-circle" /> {{ order.client_message }}</span>
              </div>

              <div class="order-detail-section order-total-detail">
                <p class="panel-kicker">ORDER TOTAL</p>
                <div><span>Items</span><strong>{{ formatCustomerMoney(order.items_subtotal, order.currency) }}</strong></div>
                <div><span>Shipping</span><strong>{{ formatCustomerMoney(order.shipping_fee, order.currency) }}</strong></div>
                <div v-if="Number(order.discount_amount) > 0"><span>Discount</span><strong>−{{ formatCustomerMoney(order.discount_amount, order.currency) }}</strong></div>
                <div><span>Tax</span><strong>{{ formatCustomerMoney(order.tax_amount, order.currency) }}</strong></div>
                <div class="order-total-line"><span>Total</span><strong>{{ formatCustomerMoney(order.total_amount, order.currency) }}</strong></div>
              </div>
            </div>

            <div class="order-detail-actions">
              <button v-if="['SHIPPED', 'DELIVERED', 'COMPLETED'].includes(order.status)" class="outline-button" type="button" @click="viewLogistics(order)">
                <UIcon name="i-lucide-truck" /> Track delivery
              </button>
              <button v-if="orderCanCancel(order)" class="text-button text-button-danger" type="button" @click="cancelTarget === order.order_no ? closeCancel() : openCancel(order)">
                <UIcon name="i-lucide-circle-x" /> {{ cancelTarget === order.order_no ? 'Close cancellation' : 'Cancel order' }}
              </button>
            </div>

            <form v-if="cancelTarget === order.order_no" class="cancel-form" @submit.prevent="cancelOrder(order)">
              <label class="field-label">
                <span>Reason <small>OPTIONAL</small></span>
                <textarea v-model="cancelReason" maxlength="200" rows="2" placeholder="Tell us why you are changing your mind…" />
              </label>
              <button class="danger-button" type="submit" :disabled="isCancelling">
                <UIcon :name="isCancelling ? 'i-lucide-loader-circle' : 'i-lucide-trash-2'" :class="{ 'is-spinning': isCancelling }" />
                {{ isCancelling ? 'Cancelling…' : 'Confirm cancellation' }}
              </button>
            </form>
          </div>
        </article>
      </section>

      <footer v-if="totalPages > 1" class="orders-pagination">
        <button type="button" :disabled="page <= 1 || isLoading" @click="changePage(page - 1)"><UIcon name="i-lucide-arrow-left" /> Previous</button>
        <span>PAGE {{ pageLabel }}</span>
        <button type="button" :disabled="page >= totalPages || isLoading" @click="changePage(page + 1)">Next <UIcon name="i-lucide-arrow-right" /></button>
      </footer>
    </template>
  </CustomerAccountShell>
</template>
<style scoped>
.orders-loading { display: flex; flex-direction: column; gap: 15px; }
.orders-loading-toolbar,
.orders-loading-card { position: relative; min-height: 84px; overflow: hidden; border: 1px solid rgba(36, 29, 33, .08); background: rgba(255, 255, 255, .52); }
.orders-loading-toolbar { min-height: 62px; }
.orders-loading-card { min-height: 164px; }
.orders-loading-toolbar::after,
.orders-loading-card::after { position: absolute; inset: 0; background: linear-gradient(90deg, transparent, rgba(255,255,255,.65), transparent); content: ''; animation: orders-shimmer 1.5s infinite; }
@keyframes orders-shimmer { from { transform: translateX(-100%); } to { transform: translateX(100%); } }

.account-notice { display: flex; align-items: center; gap: 10px; margin-bottom: 19px; padding: 12px 14px; border: 1px solid var(--store-line); color: var(--store-muted); background: rgba(255,255,255,.58); font-size: 12px; line-height: 1.5; }
.account-notice > .iconify { width: 16px; height: 16px; flex: 0 0 auto; color: var(--store-wine); }
.account-notice span { flex: 1; }
.account-notice button { padding: 0; border: 0; color: var(--store-wine-dark); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }

.orders-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 20px; padding: 22px 23px; border: 1px solid var(--store-line); background: rgba(255,255,255,.54); }
.orders-toolbar h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.refresh-button { display: inline-flex; align-items: center; gap: 7px; padding: 0; border: 0; color: var(--store-muted); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .07em; text-transform: uppercase; }
.refresh-button:hover:not(:disabled) { color: var(--store-wine); }
.refresh-button:disabled { cursor: wait; opacity: .55; }
.refresh-button .iconify { width: 14px; height: 14px; }
.is-spinning { animation: order-spin .8s linear infinite; }
@keyframes order-spin { to { transform: rotate(360deg); } }

.order-filters { display: flex; gap: 0; overflow-x: auto; margin-bottom: 17px; border-bottom: 1px solid var(--store-line); }
.order-filters button { position: relative; min-height: 42px; padding: 0 14px; border: 0; color: var(--store-muted); background: transparent; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .07em; text-transform: uppercase; white-space: nowrap; }
.order-filters button::after { position: absolute; right: 14px; bottom: -1px; left: 14px; height: 2px; background: transparent; content: ''; transition: background .2s ease; }
.order-filters button:hover,
.order-filters button.active { color: var(--store-wine); }
.order-filters button.active::after { background: var(--store-wine); }

.order-list { display: flex; flex-direction: column; gap: 12px; }
.order-card { border: 1px solid var(--store-line); background: rgba(255,255,255,.64); transition: border-color .2s ease, box-shadow .2s ease; }
.order-card:hover,
.order-card.expanded { border-color: rgba(154,64,85,.5); box-shadow: 0 10px 27px rgba(43,29,35,.06); }
.order-card-header { min-height: 89px; display: grid; grid-template-columns: 42px minmax(0,1fr) auto 30px; align-items: center; gap: 15px; padding: 14px 19px 13px; }
.order-card-number { align-self: start; padding-top: 5px; color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 33px; line-height: 1; }
.order-card-heading { min-width: 0; }
.order-card-eyebrow { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .08em; }
.order-card-heading h3 { margin: 7px 0 4px; font-family: 'Playfair Display', Georgia, serif; font-size: 22px; font-weight: 500; line-height: 1; }
.order-card-heading p { overflow: hidden; margin: 0; color: var(--store-muted); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.order-card-total { display: flex; align-items: flex-end; flex-direction: column; gap: 9px; }
.order-card-total strong { font-size: 13px; font-weight: 600; }
.status-pill { display: inline-flex; align-items: center; min-height: 21px; padding: 0 8px; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; text-transform: uppercase; white-space: nowrap; }
.tone-warm { color: #805e3d; background: #f4e7d8; }
.tone-accent { color: #77526b; background: #eadfe9; }
.tone-success { color: #52715b; background: #e0ebdf; }
.tone-muted { color: #81767b; background: #e8e3e4; }
.order-expand-button { width: 29px; height: 29px; display: grid; place-items: center; padding: 0; border: 1px solid var(--store-line); color: var(--store-ink); background: transparent; cursor: pointer; transition: color .2s ease, background .2s ease; }
.order-expand-button:hover { color: #fff; background: var(--store-ink); }
.order-expand-button .iconify { width: 14px; height: 14px; }

.order-card-preview { min-height: 77px; display: grid; grid-template-columns: 58px minmax(0,1fr) auto; align-items: center; gap: 13px; padding: 10px 19px; border-top: 1px solid rgba(36,29,33,.1); border-bottom: 1px solid rgba(36,29,33,.1); background: rgba(241,232,231,.31); }
.order-preview-image,
.order-item-image { display: grid; place-items: center; overflow: hidden; color: var(--store-wine); background: var(--store-linen); font-family: 'Playfair Display', Georgia, serif; }
.order-preview-image { width: 58px; height: 58px; }
.order-preview-image img,
.order-item-image img { width: 100%; height: 100%; object-fit: cover; }
.order-preview-image span,
.order-item-image span { font-size: 21px; }
.order-preview-copy { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.order-preview-copy strong { overflow: hidden; font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.order-preview-copy span { color: var(--store-muted); font-size: 10px; }
.order-preview-meta { display: flex; align-items: flex-end; flex-direction: column; gap: 5px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; text-align: right; text-transform: uppercase; }
.order-preview-meta span:first-child { color: var(--store-wine); }

.order-details { padding: 23px 20px 20px; background: rgba(251,247,245,.78); }
.details-columns { display: grid; grid-template-columns: minmax(0,1.2fr) minmax(160px,.8fr) minmax(160px,.7fr); gap: 25px; }
.order-detail-section { min-width: 0; }
.order-detail-section > .panel-kicker { margin-bottom: 14px; }
.order-item-list { display: flex; flex-direction: column; gap: 11px; }
.order-item-row { display: grid; grid-template-columns: 45px minmax(0,1fr) auto; align-items: center; gap: 10px; }
.order-item-image { width: 45px; height: 45px; }
.order-item-image span { font-size: 17px; }
.order-item-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.order-item-copy strong { overflow: hidden; font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.order-item-copy span,
.order-item-copy small { color: var(--store-muted); font-size: 9px; }
.order-item-price { font-size: 10px; font-weight: 600; white-space: nowrap; }
.order-address-detail { display: flex; align-items: flex-start; flex-direction: column; gap: 5px; padding-left: 20px; border-left: 1px solid var(--store-line); color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.order-address-detail strong { color: var(--store-ink); font-size: 12px; }
.order-address-detail p { margin: 4px 0 0; }
.order-message { display: flex; gap: 5px; margin-top: 5px; font-style: italic; }
.order-message .iconify { width: 13px; height: 13px; flex: 0 0 auto; color: var(--store-wine); }
.order-total-detail { padding-left: 20px; border-left: 1px solid var(--store-line); }
.order-total-detail > div { display: flex; justify-content: space-between; gap: 10px; padding: 7px 0; border-top: 1px solid rgba(36,29,33,.1); color: var(--store-muted); font-size: 10px; }
.order-total-detail > div strong { color: var(--store-ink); font-size: 10px; font-weight: 600; }
.order-total-detail .order-total-line { margin-top: 5px; color: var(--store-ink); }
.order-total-detail .order-total-line strong { color: var(--store-wine); font-size: 13px; }
.order-detail-actions { display: flex; align-items: center; gap: 18px; margin-top: 23px; padding-top: 17px; border-top: 1px solid var(--store-line); }
.outline-button,
.text-button,
.danger-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; transition: color .2s ease, background .2s ease, border-color .2s ease; }
.outline-button { min-height: 38px; padding: 0 12px; border: 1px solid var(--store-ink); color: var(--store-ink); background: transparent; }
.outline-button:hover { color: #fff; background: var(--store-ink); }
.text-button { padding: 0; border: 0; color: var(--store-muted); background: none; }
.text-button:hover { color: var(--store-wine); }
.text-button-danger:hover { color: #a33e4a; }
.outline-button .iconify,
.text-button .iconify,
.danger-button .iconify { width: 14px; height: 14px; }
.cancel-form { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: end; gap: 13px; margin-top: 18px; padding: 15px; border: 1px solid rgba(154,64,85,.28); background: rgba(241,232,231,.58); }
.field-label { display: flex; flex-direction: column; gap: 7px; color: var(--store-ink); font-size: 11px; font-weight: 600; }
.field-label span { display: flex; justify-content: space-between; gap: 10px; }
.field-label small { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 7px; font-weight: 400; letter-spacing: .05em; text-transform: uppercase; }
.field-label textarea { width: 100%; box-sizing: border-box; padding: 9px 11px; border: 1px solid rgba(36,29,33,.2); outline: 0; color: var(--store-ink); background: rgba(255,255,255,.76); font-size: 11px; resize: vertical; }
.field-label textarea:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.1); }
.danger-button { min-height: 39px; padding: 0 13px; border: 1px solid #a33e4a; color: #fff; background: #a33e4a; }
.danger-button:hover:not(:disabled) { color: #a33e4a; background: transparent; }
.danger-button:disabled { cursor: wait; opacity: .55; }

.orders-empty { min-height: 250px; display: flex; align-items: center; gap: 24px; padding: 28px; border: 1px dashed var(--store-line); background: rgba(241,232,231,.34); }
.empty-order-number { color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 70px; line-height: 1; }
.orders-empty h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; letter-spacing: -.03em; }
.orders-empty p:not(.panel-kicker) { max-width: 440px; margin: 10px 0 19px; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.orders-empty .store-button { min-height: 42px; }
.orders-pagination { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--store-line); }
.orders-pagination button { display: inline-flex; align-items: center; gap: 7px; padding: 0; border: 0; color: var(--store-ink); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-transform: uppercase; }
.orders-pagination button:hover:not(:disabled) { color: var(--store-wine); }
.orders-pagination button:disabled { cursor: not-allowed; opacity: .3; }
.orders-pagination button .iconify { width: 14px; height: 14px; }
.orders-pagination span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }

@media (max-width: 900px) {
  .details-columns { grid-template-columns: minmax(0,1fr) minmax(160px, .8fr); }
  .order-total-detail { grid-column: 2; grid-row: 1; }
  .order-address-detail { grid-column: 2; grid-row: 2; }
  .order-detail-section:first-child { grid-row: 1 / span 2; }
}

@media (max-width: 650px) {
  .orders-toolbar { align-items: flex-start; flex-direction: column; padding: 19px 17px; }
  .order-filters button { padding-inline: 10px; }
  .order-filters button::after { right: 10px; left: 10px; }
  .order-card-header { grid-template-columns: 31px minmax(0,1fr) 29px; gap: 10px; padding: 13px 13px 12px; }
  .order-card-number { font-size: 27px; }
  .order-card-total { grid-column: 2; align-items: flex-start; flex-direction: row; flex-wrap: wrap; gap: 8px 12px; }
  .order-card-total strong { order: -1; }
  .order-expand-button { grid-column: 3; grid-row: 1 / span 2; }
  .order-card-preview { grid-template-columns: 48px minmax(0,1fr); padding-inline: 13px; }
  .order-preview-image { width: 48px; height: 48px; }
  .order-preview-meta { grid-column: 2; align-items: flex-start; flex-direction: row; flex-wrap: wrap; text-align: left; }
  .order-details { padding: 19px 13px 16px; }
  .details-columns { display: flex; flex-direction: column; gap: 22px; }
  .order-address-detail,
  .order-total-detail { padding: 0; border-top: 1px solid var(--store-line); border-left: 0; padding-top: 18px; }
  .cancel-form { grid-template-columns: 1fr; }
  .danger-button { width: 100%; }
  .orders-empty { align-items: flex-start; flex-direction: column; padding: 24px 18px; }
  .empty-order-number { font-size: 54px; }
}

@media (max-width: 430px) {
  .order-card-heading h3 { font-size: 19px; }
  .order-card-heading p { font-size: 9px; }
  .order-detail-actions { align-items: flex-start; flex-direction: column; gap: 14px; }
}

@media (prefers-reduced-motion: reduce) {
  .orders-loading-toolbar::after,
  .orders-loading-card::after,
  .is-spinning { animation: none; }
}
</style>
