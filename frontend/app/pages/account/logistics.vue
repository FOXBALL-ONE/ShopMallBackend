<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import type { CustomerOrder, CustomerProfile, CustomerShipment } from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import {
  customerStatusLabel,
  customerStatusTone,
  formatCustomerDate,
  orderItemCount,
  parseProductSnapshot
} from '~/utils/customer-display'

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Delivery tracking | Pelissa',
  meta: [{ name: 'description', content: 'Follow your Pelissa deliveries with live shipment updates and tracking history.' }]
})

const api = useCustomerAccountApi()
const session = useCustomerSession()
const route = useRoute()
const router = useRouter()

const profile = ref<CustomerProfile | null>(null)
const orders = ref<CustomerOrder[]>([])
const shipments = ref<CustomerShipment[]>([])
const selectedShipment = ref<CustomerShipment | null>(null)
const isLoading = ref(true)
const isLoadingShipments = ref(false)
const isLoadingDetail = ref(false)
const requestError = ref('')
const lookupError = ref('')

const lookupForm = reactive({ carrier: '', trackingNo: '' })
const lookupLoading = ref(false)

const requestedOrderNo = computed(() => {
  const value = route.query.order_no
  return Array.isArray(value) ? value[0] || '' : typeof value === 'string' ? value : ''
})
const selectedOrder = computed(() => {
  if (!selectedShipment.value) return orders.value.find(order => order.order_no === requestedOrderNo.value) || null
  return orders.value.find(order => order.order_no === selectedShipment.value?.order_no) || null
})
const selectedOrderShipments = computed(() => {
  const orderNo = selectedShipment.value?.order_no
  return orderNo ? shipments.value.filter(shipment => shipment.order_no === orderNo) : []
})
const shipmentProgress = computed(() => {
  const status = selectedShipment.value?.status
  if (status === 'DELIVERED') return 100
  if (status === 'OUT_FOR_DELIVERY') return 82
  if (status === 'IN_TRANSIT') return 55
  if (status === 'LABEL_CREATED') return 24
  if (status === 'LABEL_PENDING') return 10
  if (['CANCELLED', 'DELETED'].includes(status || '')) return 0
  return 16
})
const sortedTracks = computed(() => [...(selectedShipment.value?.tracks || [])].sort((a, b) => {
  return new Date(b.occurred_at).getTime() - new Date(a.occurred_at).getTime()
}))
const availableOrders = computed(() => orders.value.filter(order => !['CANCELLED', 'DELETED'].includes(order.status)))

function orderLabel(order: CustomerOrder) {
  return `${order.order_no} · ${orderItemCount(order.items)} ${orderItemCount(order.items) === 1 ? 'piece' : 'pieces'}`
}

function shipmentLabel(shipment: CustomerShipment) {
  return shipment.tracking_no || shipment.shipment_no
}

function shipmentDestination(order: CustomerOrder | null) {
  if (!order) return 'Delivery details on file'
  const address = order.shipping_address
  return [address.city, address.state_or_province, address.country].filter(Boolean).join(', ') || 'Delivery details on file'
}

async function selectOrder(orderNo: string) {
  if (!orderNo || orderNo === requestedOrderNo.value) return
  await router.push({ path: '/account/logistics', query: { order_no: orderNo } })
}

async function selectShipment(shipment: CustomerShipment, fetchDetail = true) {
  selectedShipment.value = shipment
  if (!fetchDetail) return

  isLoadingDetail.value = true
  try {
    selectedShipment.value = await api.getShipment(shipment.order_no, shipment.shipment_no)
    const index = shipments.value.findIndex(item => item.shipment_no === shipment.shipment_no && item.order_no === shipment.order_no)
    if (index >= 0) shipments.value[index] = selectedShipment.value
  } catch (error: unknown) {
    // The list response already contains a useful timeline; retain it when a
    // detail refresh is temporarily unavailable.
    requestError.value = customerRequestMessage(error, 'The latest tracking details are not available right now.')
  } finally {
    isLoadingDetail.value = false
  }
}

async function loadShipmentsForOrders(orderList: CustomerOrder[]) {
  isLoadingShipments.value = true
  shipments.value = []
  selectedShipment.value = null
  const candidates = orderList.filter(order => !['PENDING_PAYMENT', 'CANCELLED', 'DELETED'].includes(order.status)).slice(0, 12)
  const results = await Promise.allSettled(candidates.map(order => api.getShipments(order.order_no)))
  for (const result of results) {
    if (result.status === 'fulfilled') shipments.value.push(...(result.value.list || []))
  }
  const requested = requestedOrderNo.value
  const first = requested
    ? shipments.value.find(shipment => shipment.order_no === requested) || shipments.value[0]
    : shipments.value[0]
  if (first) await selectShipment(first)
  isLoadingShipments.value = false

  if (!shipments.value.length && candidates.length && results.every(result => result.status === 'rejected')) {
    requestError.value = 'We could not reach the carrier service just now. Try refreshing in a moment.'
  }
}

async function loadLogistics() {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  isLoading.value = true
  requestError.value = ''
  try {
    const [profileResult, orderResult] = await Promise.all([
      profile.value ? Promise.resolve(profile.value) : api.getProfile(userId),
      api.getOrders(1, 25)
    ])
    profile.value = profileResult
    orders.value = orderResult.list || []

    if (requestedOrderNo.value && !orders.value.some(order => order.order_no === requestedOrderNo.value)) {
      try {
        orders.value.unshift(await api.getOrder(requestedOrderNo.value))
      } catch (error: unknown) {
        requestError.value = customerRequestMessage(error, 'We could not open the requested order, so your recent delivery history is shown instead.')
      }
    }

    await loadShipmentsForOrders(orders.value)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not load your delivery history.')
  } finally {
    isLoading.value = false
  }
}

async function lookupTracking() {
  const carrier = lookupForm.carrier.trim()
  const trackingNo = lookupForm.trackingNo.trim()
  lookupError.value = ''
  if (!carrier || !trackingNo) {
    lookupError.value = 'Enter both a carrier code and a tracking number.'
    return
  }

  lookupLoading.value = true
  try {
    const result = await api.trackShipment(carrier, trackingNo)
    const existingIndex = shipments.value.findIndex(item => item.shipment_no === result.shipment_no)
    if (existingIndex >= 0) shipments.value[existingIndex] = result
    else shipments.value.unshift(result)
    if (!orders.value.some(order => order.order_no === result.order_no)) {
      try {
        orders.value.unshift(await api.getOrder(result.order_no))
      } catch {
        // Tracking details remain useful even if the order summary is delayed.
      }
    }
    await selectShipment(result, false)
  } catch (error: unknown) {
    lookupError.value = customerRequestMessage(error, 'We could not find that shipment. Check the carrier code and tracking number.')
  } finally {
    lookupLoading.value = false
  }
}

watch(requestedOrderNo, () => {
  if (import.meta.client && !isLoading.value) void loadLogistics()
})

onMounted(() => {
  void loadLogistics()
})
</script>
<template>
  <CustomerAccountShell
    eyebrow="THE DELIVERY JOURNAL · 05"
    title="Know where it is."
    intro="From the first label scan to the moment it arrives, keep the journey of every dispatched piece close."
    :profile="profile"
  >
    <div v-if="isLoading" class="logistics-loading" aria-live="polite">
      <div class="logistics-loading-top" />
      <div class="logistics-loading-main" />
    </div>

    <template v-else>
      <div v-if="requestError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" />
        <span>{{ requestError }}</span>
        <button type="button" @click="loadLogistics">Refresh</button>
      </div>

      <section class="logistics-order-picker">
        <div>
          <p class="store-eyebrow">CHOOSE A JOURNEY</p>
          <h2>{{ selectedOrder ? `Order ${selectedOrder.order_no}` : 'Your dispatched pieces' }}</h2>
          <p>{{ selectedOrder ? `${orderItemCount(selectedOrder.items)} ${orderItemCount(selectedOrder.items) === 1 ? 'piece' : 'pieces'} · ${shipmentDestination(selectedOrder)}` : 'Select an order below to see its latest carrier updates.' }}</p>
        </div>
        <label v-if="availableOrders.length" class="order-select-label">
          <span>ORDER</span>
          <select :value="selectedOrder?.order_no || requestedOrderNo" @change="selectOrder(($event.target as HTMLSelectElement).value)">
            <option v-for="order in availableOrders" :key="order.order_no" :value="order.order_no">{{ orderLabel(order) }}</option>
          </select>
        </label>
      </section>

      <nav v-if="availableOrders.length > 1" class="order-journal-nav" aria-label="Orders with delivery history">
        <button
          v-for="order in availableOrders"
          :key="order.order_no"
          type="button"
          :class="{ active: selectedOrder?.order_no === order.order_no }"
          @click="selectOrder(order.order_no)"
        >
          <span>{{ order.order_no }}</span>
          <small>{{ formatCustomerDate(order.created_at) }}</small>
        </button>
      </nav>

      <div v-if="isLoadingShipments" class="shipment-loading" aria-live="polite">
        <div v-for="index in 2" :key="index" class="shipment-loading-card" />
      </div>

      <template v-else-if="selectedShipment">
        <section v-if="selectedOrderShipments.length > 1" class="shipment-selector-panel">
          <div>
            <p class="panel-kicker">AVAILABLE SHIPMENTS</p>
            <h2>This order is arriving in {{ selectedOrderShipments.length }} parts.</h2>
          </div>
          <div class="shipment-chips">
            <button
              v-for="shipment in selectedOrderShipments"
              :key="shipment.shipment_no"
              type="button"
              :class="{ active: selectedShipment.shipment_no === shipment.shipment_no }"
              @click="selectShipment(shipment)"
            >
              <span>{{ shipment.shipment_no }}</span>
              <small>{{ customerStatusLabel(shipment.status) }}</small>
            </button>
          </div>
        </section>

        <section class="shipment-hero-card">
          <div class="shipment-hero-top">
            <div>
              <span class="shipment-eyebrow">SHIPMENT {{ selectedShipment.shipment_no }}</span>
              <h2>{{ customerStatusLabel(selectedShipment.status) }}</h2>
              <p v-if="selectedShipment.last_track_status">{{ selectedShipment.last_track_status }}<span v-if="selectedShipment.last_track_location"> · {{ selectedShipment.last_track_location }}</span></p>
              <p v-else>{{ selectedShipment.tracking_no ? 'Your carrier has the details.' : 'The carrier label is being prepared.' }}</p>
            </div>
            <span class="status-pill" :class="`tone-${customerStatusTone(selectedShipment.status)}`">{{ selectedShipment.status.replaceAll('_', ' ') }}</span>
          </div>
          <div class="shipment-progress" aria-label="Delivery progress">
            <div class="shipment-progress-line"><span :style="{ width: `${shipmentProgress}%` }" /></div>
            <div class="shipment-progress-points">
              <span :class="{ active: shipmentProgress >= 10 }">Label</span>
              <span :class="{ active: shipmentProgress >= 55 }">In transit</span>
              <span :class="{ active: shipmentProgress >= 82 }">Out for delivery</span>
              <span :class="{ active: shipmentProgress >= 100 }">Delivered</span>
            </div>
          </div>
          <div class="shipment-reference-row">
            <div><span>CARRIER</span><strong>{{ selectedShipment.carrier || 'Carrier pending' }}</strong></div>
            <div><span>TRACKING NUMBER</span><strong>{{ selectedShipment.tracking_no || 'Not assigned yet' }}</strong></div>
            <a v-if="selectedShipment.tracking_url" :href="selectedShipment.tracking_url" target="_blank" rel="noreferrer">Open carrier site <UIcon name="i-lucide-arrow-up-right" /></a>
          </div>
        </section>

        <section class="logistics-detail-grid">
          <div class="tracking-panel">
            <div class="panel-heading-row">
              <div>
                <p class="panel-kicker">01 / MOVEMENT</p>
                <h2>Tracking history</h2>
              </div>
              <UIcon v-if="isLoadingDetail" class="is-spinning panel-loading-icon" name="i-lucide-loader-circle" />
            </div>
            <div v-if="sortedTracks.length" class="tracking-timeline">
              <article v-for="(track, index) in sortedTracks" :key="track.carrier_event_id || `${track.occurred_at}-${index}`" class="tracking-event">
                <div class="tracking-event-rail"><span :class="{ current: index === 0 }" /></div>
                <div class="tracking-event-copy">
                  <div class="tracking-event-top">
                    <strong>{{ customerStatusLabel(track.normalized_status || track.status_code) }}</strong>
                    <time>{{ formatCustomerDate(track.occurred_at, true) }}</time>
                  </div>
                  <p>{{ track.description || 'Carrier update received.' }}</p>
                  <span v-if="track.location"><UIcon name="i-lucide-map-pin" /> {{ track.location }}</span>
                </div>
              </article>
            </div>
            <div v-else class="tracking-empty"><UIcon name="i-lucide-hourglass" /><p>Tracking scans will appear here as soon as the carrier receives the parcel.</p></div>
          </div>

          <aside class="shipment-side-column">
            <div class="shipment-info-card">
              <p class="panel-kicker">02 / DESTINATION</p>
              <h3>{{ selectedOrder?.shipping_address.name || 'Your delivery' }}</h3>
              <p v-if="selectedOrder">{{ selectedOrder.shipping_address.phone }}</p>
              <p v-if="selectedOrder" class="shipment-address">{{ [selectedOrder.shipping_address.address1, selectedOrder.shipping_address.address2, selectedOrder.shipping_address.city, selectedOrder.shipping_address.state_or_province, selectedOrder.shipping_address.postal_code, selectedOrder.shipping_address.country].filter(Boolean).join(', ') }}</p>
              <NuxtLink to="/account/profile#addresses">Manage addresses <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
            </div>

            <div class="shipment-info-card shipment-items-card">
              <p class="panel-kicker">03 / IN THIS PARCEL</p>
              <div v-if="selectedShipment.items.length" class="shipment-item-list">
                <div v-for="item in selectedShipment.items" :key="item.order_item_id" class="shipment-item-row">
                  <div class="shipment-item-mark">{{ String(item.quantity).padStart(2, '0') }}</div>
                  <div>
                    <strong>{{ parseProductSnapshot(item.product_snapshot).name }}</strong>
                    <span>{{ customerStatusLabel(item.allocation_status) }}</span>
                  </div>
                </div>
              </div>
              <p v-else class="side-muted">Item allocation is still being prepared.</p>
            </div>
          </aside>
        </section>
      </template>

      <section v-else class="logistics-empty">
        <div class="logistics-empty-art" aria-hidden="true"><span>05</span></div>
        <div>
          <p class="panel-kicker">THE JOURNEY HAS NOT STARTED</p>
          <h2>No shipments to trace yet.</h2>
          <p>Once an order leaves our studio, its carrier updates will unfold here. You can also look up a shipment directly below.</p>
          <NuxtLink class="store-button" to="/account/orders"><UIcon name="i-lucide-receipt-text" /> View your orders</NuxtLink>
        </div>
      </section>

      <section class="tracking-lookup-panel">
        <div>
          <p class="panel-kicker">DIRECT LOOKUP</p>
          <h2>Have a tracking number?</h2>
          <p>Use the carrier code and tracking number from your dispatch email.</p>
        </div>
        <form class="tracking-lookup-form" @submit.prevent="lookupTracking">
          <label class="field-label"><span>Carrier code</span><input v-model="lookupForm.carrier" type="text" placeholder="ups, fedex, usps…" autocomplete="off"></label>
          <label class="field-label"><span>Tracking number</span><input v-model="lookupForm.trackingNo" type="text" placeholder="Enter tracking number" autocomplete="off"></label>
          <button class="store-button" type="submit" :disabled="lookupLoading">
            <UIcon :name="lookupLoading ? 'i-lucide-loader-circle' : 'i-lucide-search'" :class="{ 'is-spinning': lookupLoading }" />
            {{ lookupLoading ? 'Looking up…' : 'Find shipment' }}
          </button>
          <div v-if="lookupError" class="inline-error"><UIcon name="i-lucide-circle-alert" /> {{ lookupError }}</div>
        </form>
      </section>
    </template>
  </CustomerAccountShell>
</template>
<style scoped>
.logistics-loading { display: flex; flex-direction: column; gap: 18px; }
.logistics-loading-top,
.logistics-loading-main { position: relative; min-height: 142px; overflow: hidden; border: 1px solid rgba(36,29,33,.08); background: rgba(255,255,255,.52); }
.logistics-loading-main { min-height: 460px; }
.logistics-loading-top::after,
.logistics-loading-main::after,
.shipment-loading-card::after { position: absolute; inset: 0; background: linear-gradient(90deg, transparent, rgba(255,255,255,.65), transparent); content: ''; animation: logistics-shimmer 1.5s infinite; }
@keyframes logistics-shimmer { from { transform: translateX(-100%); } to { transform: translateX(100%); } }

.account-notice { display: flex; align-items: center; gap: 10px; margin-bottom: 19px; padding: 12px 14px; border: 1px solid var(--store-line); color: var(--store-muted); background: rgba(255,255,255,.58); font-size: 12px; line-height: 1.5; }
.account-notice > .iconify { width: 16px; height: 16px; flex: 0 0 auto; color: var(--store-wine); }
.account-notice span { flex: 1; }
.account-notice button { padding: 0; border: 0; color: var(--store-wine-dark); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }

.logistics-order-picker { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; margin-bottom: 16px; padding: 22px 23px; border: 1px solid var(--store-line); background: rgba(255,255,255,.58); }
.logistics-order-picker h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 32px; font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.logistics-order-picker p:not(.store-eyebrow) { margin: 9px 0 0; color: var(--store-muted); font-size: 11px; }
.order-select-label { min-width: 190px; display: flex; align-items: flex-end; flex-direction: column; gap: 7px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .08em; text-transform: uppercase; }
.order-select-label select { width: 100%; min-height: 39px; padding: 0 11px; border: 1px solid var(--store-ink); border-radius: 0; outline: 0; color: var(--store-ink); background: var(--store-paper); font-family: 'DM Mono', monospace; font-size: 9px; }
.order-journal-nav { display: flex; gap: 0; overflow-x: auto; margin-bottom: 17px; border-bottom: 1px solid var(--store-line); }
.order-journal-nav button { position: relative; min-width: 148px; display: flex; align-items: flex-start; flex-direction: column; gap: 5px; min-height: 52px; padding: 10px 13px; border: 0; color: var(--store-muted); background: transparent; cursor: pointer; text-align: left; }
.order-journal-nav button::after { position: absolute; right: 13px; bottom: -1px; left: 13px; height: 2px; background: transparent; content: ''; }
.order-journal-nav button:hover,
.order-journal-nav button.active { color: var(--store-wine); }
.order-journal-nav button.active::after { background: var(--store-wine); }
.order-journal-nav button span { font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .06em; }
.order-journal-nav button small { color: var(--store-muted); font-size: 9px; }

.shipment-loading { display: flex; flex-direction: column; gap: 13px; }
.shipment-loading-card { position: relative; min-height: 100px; overflow: hidden; border: 1px solid rgba(36,29,33,.08); background: rgba(255,255,255,.52); }
.shipment-hero-card { position: relative; overflow: hidden; margin-bottom: 18px; padding: 26px 27px 24px; color: #fff; background: var(--store-wine-dark); }
.shipment-hero-card::after { position: absolute; right: -54px; bottom: -74px; width: 240px; height: 240px; border: 1px solid rgba(255,255,255,.23); border-radius: 50%; content: ''; }
.shipment-hero-top { position: relative; z-index: 1; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.shipment-eyebrow { color: #f0caca; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .12em; }
.shipment-hero-card h2 { margin: 10px 0 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(32px,4vw,49px); font-weight: 500; letter-spacing: -.045em; line-height: .98; }
.shipment-hero-card p { margin: 9px 0 0; color: rgba(255,255,255,.73); font-size: 11px; }
.shipment-hero-card .status-pill { color: #fff; background: rgba(255,255,255,.16); }
.shipment-progress { position: relative; z-index: 1; margin-top: 35px; }
.shipment-progress-line { height: 2px; overflow: hidden; background: rgba(255,255,255,.25); }
.shipment-progress-line span { display: block; height: 100%; background: #f4c1c1; transition: width .4s ease; }
.shipment-progress-points { display: flex; justify-content: space-between; gap: 10px; margin-top: 10px; color: rgba(255,255,255,.52); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; text-transform: uppercase; }
.shipment-progress-points span.active { color: #fff; }
.shipment-reference-row { position: relative; z-index: 1; display: flex; align-items: flex-end; gap: 30px; margin-top: 28px; padding-top: 17px; border-top: 1px solid rgba(255,255,255,.22); }
.shipment-reference-row > div { display: flex; flex-direction: column; gap: 6px; }
.shipment-reference-row span { color: rgba(255,255,255,.55); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .08em; }
.shipment-reference-row strong { font-size: 11px; font-weight: 500; }
.shipment-reference-row a { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: #fff; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; }
.shipment-reference-row a:hover { color: #f4c1c1; }
.shipment-reference-row a .iconify { width: 13px; height: 13px; }

.shipment-selector-panel { display: flex; align-items: center; justify-content: space-between; gap: 20px; margin-bottom: 18px; padding: 18px 20px; border: 1px solid var(--store-line); background: rgba(255,255,255,.58); }
.shipment-selector-panel h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 24px; font-weight: 500; letter-spacing: -.03em; }
.shipment-chips { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 7px; }
.shipment-chips button { min-width: 105px; display: flex; align-items: flex-start; flex-direction: column; gap: 4px; padding: 9px 10px; border: 1px solid var(--store-line); color: var(--store-muted); background: transparent; cursor: pointer; text-align: left; }
.shipment-chips button:hover,
.shipment-chips button.active { border-color: var(--store-wine); color: var(--store-wine); background: rgba(241,232,231,.65); }
.shipment-chips button span { font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; }
.shipment-chips button small { font-size: 9px; }

.logistics-detail-grid { display: grid; grid-template-columns: minmax(0,1.35fr) minmax(230px,.65fr); gap: 18px; }
.tracking-panel,
.shipment-info-card,
.tracking-lookup-panel { border: 1px solid var(--store-line); background: rgba(255,255,255,.64); }
.tracking-panel { min-height: 395px; padding: 25px 24px; }
.panel-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; margin-bottom: 25px; }
.panel-kicker { margin: 0 0 8px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .1em; text-transform: uppercase; }
.panel-heading-row h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 32px; font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.panel-loading-icon { width: 17px; height: 17px; color: var(--store-wine); }
.tracking-timeline { display: flex; flex-direction: column; }
.tracking-event { display: grid; grid-template-columns: 25px minmax(0,1fr); gap: 12px; min-height: 79px; }
.tracking-event-rail { position: relative; display: flex; justify-content: center; }
.tracking-event-rail::before { position: absolute; top: 0; bottom: -1px; width: 1px; background: var(--store-line); content: ''; }
.tracking-event:last-child .tracking-event-rail::before { bottom: 50%; }
.tracking-event-rail span { position: relative; z-index: 1; width: 9px; height: 9px; margin-top: 5px; border: 2px solid var(--store-paper); border-radius: 50%; background: var(--store-muted); box-shadow: 0 0 0 1px var(--store-muted); }
.tracking-event-rail span.current { width: 11px; height: 11px; margin-top: 4px; background: var(--store-wine); box-shadow: 0 0 0 1px var(--store-wine), 0 0 0 5px rgba(154,64,85,.12); }
.tracking-event-copy { min-width: 0; padding-bottom: 20px; }
.tracking-event-top { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.tracking-event-top strong { color: var(--store-ink); font-size: 12px; font-weight: 600; }
.tracking-event-top time { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .02em; text-align: right; }
.tracking-event-copy p { margin: 6px 0 0; color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.tracking-event-copy > span { display: inline-flex; align-items: center; gap: 5px; margin-top: 7px; color: var(--store-wine); font-size: 9px; }
.tracking-event-copy > span .iconify { width: 12px; height: 12px; }
.tracking-empty { min-height: 210px; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 11px; color: var(--store-muted); text-align: center; }
.tracking-empty .iconify { width: 24px; height: 24px; color: var(--store-blush); }
.tracking-empty p { max-width: 260px; margin: 0; font-size: 11px; line-height: 1.6; }

.shipment-side-column { display: flex; flex-direction: column; gap: 18px; }
.shipment-info-card { padding: 23px 21px; }
.shipment-info-card h3 { margin: 0 0 5px; font-family: 'Playfair Display', Georgia, serif; font-size: 25px; font-weight: 500; letter-spacing: -.03em; }
.shipment-info-card > p:not(.panel-kicker) { margin: 0; color: var(--store-muted); font-size: 10px; line-height: 1.55; }
.shipment-info-card .shipment-address { margin-top: 12px !important; color: var(--store-ink) !important; }
.shipment-info-card > a { display: inline-flex; align-items: center; gap: 5px; margin-top: 17px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .05em; text-decoration: none; text-transform: uppercase; }
.shipment-info-card > a:hover { color: var(--store-ink); }
.shipment-info-card > a .iconify { width: 13px; height: 13px; }
.shipment-item-list { display: flex; flex-direction: column; gap: 12px; }
.shipment-item-row { display: grid; grid-template-columns: 31px minmax(0,1fr); align-items: center; gap: 10px; padding-top: 10px; border-top: 1px solid rgba(36,29,33,.1); }
.shipment-item-mark { width: 30px; height: 30px; display: grid; place-items: center; color: var(--store-wine); background: var(--store-linen); font-family: 'DM Mono', monospace; font-size: 8px; }
.shipment-item-row strong,
.shipment-item-row span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.shipment-item-row strong { font-size: 10px; font-weight: 600; }
.shipment-item-row span { margin-top: 4px; color: var(--store-muted); font-size: 9px; }
.side-muted { margin-top: 14px !important; font-style: italic; }

.logistics-empty { min-height: 260px; display: grid; grid-template-columns: 180px minmax(0,1fr); align-items: center; gap: 28px; padding: 28px; border: 1px dashed var(--store-line); background: rgba(241,232,231,.34); }
.logistics-empty-art { height: 190px; display: grid; place-items: center; color: rgba(255,255,255,.88); background: linear-gradient(135deg, rgba(154,64,85,.28), rgba(36,29,33,.8)), url('/lingerie/hero-lace.jpg') center / cover; font-family: 'Playfair Display', Georgia, serif; font-size: 57px; }
.logistics-empty h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 34px; font-weight: 500; letter-spacing: -.04em; }
.logistics-empty p:not(.panel-kicker) { max-width: 450px; margin: 10px 0 20px; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.store-button { min-height: 43px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 0 15px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; transition: color .2s ease, background .2s ease; }
.store-button:hover { color: var(--store-ink); background: transparent; }
.store-button .iconify { width: 14px; height: 14px; }

.tracking-lookup-panel { display: grid; grid-template-columns: minmax(0,.8fr) minmax(0,1.2fr); gap: 27px; margin-top: 18px; padding: 24px; }
.tracking-lookup-panel h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 29px; font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.tracking-lookup-panel > div:first-child p:last-child { max-width: 270px; margin: 10px 0 0; color: var(--store-muted); font-size: 11px; line-height: 1.55; }
.tracking-lookup-form { display: grid; grid-template-columns: minmax(100px,.55fr) minmax(150px,1fr) auto; align-items: end; gap: 12px; }
.field-label { display: flex; flex-direction: column; gap: 7px; color: var(--store-ink); font-size: 10px; font-weight: 600; }
.field-label input { width: 100%; min-height: 42px; box-sizing: border-box; padding: 0 11px; border: 1px solid rgba(36,29,33,.2); outline: 0; color: var(--store-ink); background: rgba(251,247,245,.86); font-size: 11px; }
.field-label input:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.1); }
.tracking-lookup-form .store-button { min-height: 42px; white-space: nowrap; }
.tracking-lookup-form .store-button:disabled { cursor: wait; opacity: .55; }
.inline-error { grid-column: 1 / -1; display: flex; align-items: flex-start; gap: 7px; color: #9a4055; font-size: 10px; line-height: 1.5; }
.inline-error .iconify { width: 14px; height: 14px; flex: 0 0 auto; }

@media (max-width: 900px) {
  .logistics-detail-grid { grid-template-columns: 1fr; }
  .shipment-side-column { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); }
  .tracking-lookup-panel { grid-template-columns: 1fr; }
}

@media (max-width: 650px) {
  .logistics-order-picker { align-items: flex-start; flex-direction: column; padding: 19px 17px; }
  .order-select-label { width: 100%; align-items: stretch; }
  .order-journal-nav button { min-width: 130px; }
  .shipment-selector-panel { align-items: flex-start; flex-direction: column; padding: 17px; }
  .shipment-chips { justify-content: flex-start; }
  .shipment-hero-card { padding: 22px 18px 20px; }
  .shipment-hero-top { flex-direction: column; gap: 13px; }
  .shipment-hero-top .status-pill { align-self: flex-start; }
  .shipment-progress { margin-top: 28px; }
  .shipment-progress-points { font-size: 7px; }
  .shipment-reference-row { align-items: flex-start; flex-wrap: wrap; gap: 17px 24px; }
  .shipment-reference-row a { width: 100%; margin-left: 0; }
  .tracking-panel,
  .shipment-info-card,
  .tracking-lookup-panel { padding: 20px 17px; }
  .shipment-side-column { display: flex; }
  .tracking-event-top { align-items: flex-start; flex-direction: column; gap: 5px; }
  .tracking-event-top time { text-align: left; }
  .logistics-empty { grid-template-columns: 1fr; padding: 22px 18px; }
  .logistics-empty-art { height: 150px; }
  .tracking-lookup-form { grid-template-columns: 1fr; }
  .tracking-lookup-form .store-button { width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  .logistics-loading-top::after,
  .logistics-loading-main::after,
  .shipment-loading-card::after,
  .is-spinning { animation: none; }
}
</style>
