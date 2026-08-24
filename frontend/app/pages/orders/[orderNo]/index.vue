<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type {
  CustomerOrder,
  CustomerOrderItem,
  CustomerOrderStatus,
  CustomerShipment,
  CustomerShipmentTrack
} from '~/types/customer-account'
import {
  customerRequestDetails,
  customerRequestMessage,
  useCustomerAccountApi
} from '~/composables/useCustomerAccountApi'
import {
  customerStatusTone,
  formatAddressLine,
  orderItemCount,
  parseProductSnapshot
} from '~/utils/customer-display'
import { useStorefrontI18n } from '~/composables/useStorefrontI18n'

definePageMeta({ middleware: ['customer-auth'] })

const { t, formatDate, formatMoney, orderStatusLabel } = useStorefrontI18n()

const SUCCESS_STATUSES = new Set<CustomerOrderStatus>(['PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED'])
const SHIPMENT_STATUSES = new Set<CustomerOrderStatus>(['PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED'])
const lifecycleSteps = computed(() => [
  { label: t('orderDetail.lifecycle.placed'), icon: 'i-lucide-receipt-text' },
  { label: t('orderDetail.lifecycle.payment'), icon: 'i-lucide-credit-card' },
  { label: t('orderDetail.lifecycle.shipped'), icon: 'i-lucide-package-check' },
  { label: t('orderDetail.lifecycle.delivered'), icon: 'i-lucide-house' }
])

const route = useRoute()
const api = useCustomerAccountApi()
const session = useCustomerSession()
const checkoutSession = useOrderCheckoutSession()
const toast = useToast()

const orderNo = computed(() => String(route.params.orderNo || '').trim())
const order = ref<CustomerOrder | null>(null)
const shipments = ref<CustomerShipment[]>([])
const isLoading = ref(true)
const isRefreshing = ref(false)
const isCancelling = ref(false)
const isCompleting = ref(false)
const isRefundRequesting = ref(false)
const isRefundStatusLoading = ref(false)
const isOpeningPayment = ref(false)
const requestError = ref('')
const shipmentError = ref('')
const cancelFormOpen = ref(false)
const cancelReason = ref('')
const refundFormOpen = ref(false)
const refundReason = ref('')
const refundReasonDetail = ref('')
const refundProviderStatus = ref<string | null>(null)
const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | null = null
let orderLoadVersion = 0

const refundReasonOptions = [
  { value: 'Changed my mind', key: 'changedMind' },
  { value: 'Ordered by mistake', key: 'orderedMistake' },
  { value: 'Found a better price', key: 'betterPrice' },
  { value: 'Payment issue', key: 'paymentIssue' },
  { value: 'Other', key: 'other' }
]

useHead(() => ({
  title: order.value ? `${t('orderDetail.seoTitle')} · ${order.value.order_no}` : t('orderDetail.seoTitle'),
  meta: [{ name: 'description', content: t('orderDetail.seoDescription') }]
}))

const itemCount = computed(() => orderItemCount(order.value?.items))
const isClosed = computed(() => ['CANCELLED', 'REFUNDED', 'DELETED'].includes(order.value?.status || ''))
const expiresAt = computed(() => {
  if (!order.value?.expires_at) return null
  const value = new Date(order.value.expires_at).getTime()
  return Number.isFinite(value) ? value : null
})
const remainingSeconds = computed(() => expiresAt.value === null
  ? null
  : Math.max(0, Math.floor((expiresAt.value - now.value) / 1000)))
const isPaymentExpired = computed(() => remainingSeconds.value !== null && remainingSeconds.value <= 0)
const canCancel = computed(() => order.value?.status === 'PENDING_PAYMENT')
const canComplete = computed(() => order.value?.status === 'DELIVERED')
const canRequestRefund = computed(() => order.value?.status === 'PAID' && order.value.payment_status === 'PAID')
const isRefunding = computed(() => order.value?.payment_status === 'REFUNDING')
const canQueryRefundStatus = computed(() => ['REFUNDING', 'PARTIALLY_REFUNDED', 'REFUNDED'].includes(order.value?.payment_status || ''))
const canResumePayment = computed(() => Boolean(
  order.value?.status === 'PENDING_PAYMENT'
  && !isPaymentExpired.value
))
const deadlineLabel = computed(() => {
  if (remainingSeconds.value === null) return ''
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})
const currentStage = computed(() => {
  const status = order.value?.status
  if (status === 'PENDING_PAYMENT') return 0
  if (status === 'PAID') return 1
  if (status === 'SHIPPED') return 2
  if (status === 'DELIVERED' || status === 'COMPLETED') return 3
  return -1
})

const itemDisplayLabels = computed(() => ({
  fallbackName: t('orderDetail.pieceFallback'),
  size: t('orderDetail.size'),
  top: t('orderDetail.topSize'),
  bottom: t('orderDetail.bottomSize')
}))

const itemDisplayMap = computed(() => new Map(
  (order.value?.items || []).map(item => [item.id, parseProductSnapshot(item.product_snapshot, itemDisplayLabels.value)])
))

function itemDisplay(item: CustomerOrderItem) {
  return itemDisplayMap.value.get(item.id) || parseProductSnapshot(item.product_snapshot, itemDisplayLabels.value)
}

function stepDate(index: number) {
  if (!order.value) return null
  return [order.value.created_at, order.value.paid_at, order.value.shipped_at, order.value.delivered_at][index] || null
}

function latestTracks(tracks: CustomerShipmentTrack[]) {
  return [...tracks]
    .sort((left, right) => new Date(right.occurred_at).getTime() - new Date(left.occurred_at).getTime())
    .slice(0, 4)
}

function safeTrackingUrl(value: string | null) {
  if (!value) return null
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? url.toString() : null
  } catch {
    return null
  }
}

async function loadOrder(showLoading = true) {
  const requestedOrderNo = orderNo.value
  const loadVersion = ++orderLoadVersion
  if (showLoading) isLoading.value = true
  else isRefreshing.value = true
  requestError.value = ''
  shipmentError.value = ''

  try {
    const userId = await session.requireSignIn()
    if (loadVersion !== orderLoadVersion || !userId) return
    if (!requestedOrderNo) {
      requestError.value = t('orderDetail.errors.missingReference')
      return
    }

    const result = await api.getOrder(requestedOrderNo)
    if (loadVersion !== orderLoadVersion) return
    order.value = result

    if (SHIPMENT_STATUSES.has(result.status)) {
      try {
        shipments.value = ((await api.getShipments(result.order_no)).list || [])
          .filter(shipment => shipment.status !== 'DELETED')
        if (loadVersion !== orderLoadVersion) return
      } catch (error: unknown) {
        if (loadVersion !== orderLoadVersion) return
        shipments.value = []
        shipmentError.value = customerRequestMessage(error, t('orderDetail.errors.shipment'))
      }
    } else {
      shipments.value = []
    }
  } catch (error: unknown) {
    if (loadVersion !== orderLoadVersion) return
    requestError.value = customerRequestMessage(error, t('orderDetail.errors.load'))
  } finally {
    if (loadVersion === orderLoadVersion) {
      isLoading.value = false
      isRefreshing.value = false
    }
  }
}

async function cancelOrder() {
  if (!order.value || !canCancel.value || isCancelling.value) return
  if (import.meta.client && !window.confirm(t('accountOrders.cancelConfirm', { orderNo: order.value.order_no }))) return

  isCancelling.value = true
  requestError.value = ''
  try {
    await api.cancelOrder(order.value.order_no, cancelReason.value.trim() || undefined)
    checkoutSession.clear(order.value.order_no)
    cancelFormOpen.value = false
    cancelReason.value = ''
    toast.add({ title: t('orderDetail.notices.cancelled'), description: t('orderDetail.notices.cancelledDescription', { orderNo: order.value.order_no }), color: 'success' })
    await loadOrder(false)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, t('orderDetail.errors.cancel'))
    toast.add({ title: t('orderDetail.errors.cancelTitle'), description: requestError.value, color: 'error' })
  } finally {
    isCancelling.value = false
  }
}

async function completeOrder() {
  if (!order.value || !canComplete.value || isCompleting.value) return

  isCompleting.value = true
  requestError.value = ''
  try {
    await api.completeOrder(order.value.order_no)
    toast.add({
      title: t('orderCompletion.completedTitle'),
      description: t('orderCompletion.completedDescription', { orderNo: order.value.order_no }),
      color: 'success'
    })
    await loadOrder(false)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, t('orderCompletion.error'))
    toast.add({ title: t('orderCompletion.errorTitle'), description: requestError.value, color: 'error' })
  } finally {
    isCompleting.value = false
  }
}

function openRefundForm() {
  if (!canRequestRefund.value || isRefundRequesting.value) return
  refundReason.value = ''
  refundReasonDetail.value = ''
  refundFormOpen.value = true
}

function closeRefundForm() {
  if (isRefundRequesting.value) return
  refundFormOpen.value = false
  refundReason.value = ''
  refundReasonDetail.value = ''
}

function closeRefundFormOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') closeRefundForm()
}

async function requestRefund() {
  if (!order.value || !canRequestRefund.value || isRefundRequesting.value) return

  isRefundRequesting.value = true
  requestError.value = ''
  try {
    await api.refundOrder(
      order.value.order_no,
      refundReason.value.trim() || undefined,
      refundReasonDetail.value.trim() || undefined
    )
    refundFormOpen.value = false
    refundReason.value = ''
    refundReasonDetail.value = ''
    toast.add({
      title: t('orderDetail.notices.refundRequested'),
      description: t('orderDetail.notices.refundRequestedDescription'),
      color: 'success'
    })
    await loadOrder(false)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, t('orderDetail.errors.refund'))
    toast.add({ title: t('orderDetail.errors.refundTitle'), description: requestError.value, color: 'error' })
  } finally {
    isRefundRequesting.value = false
  }
}

async function queryRefundStatus() {
  if (!order.value || !canQueryRefundStatus.value || isRefundStatusLoading.value) return

  isRefundStatusLoading.value = true
  requestError.value = ''
  try {
    const result = await api.getOrderRefundStatus(order.value.order_no)
    refundProviderStatus.value = result.provider_refund_status
    order.value.status = result.order_status
    order.value.payment_status = result.payment_status
    toast.add({
      title: result.payment_status === 'REFUNDED' ? t('orderDetail.notices.refundCompleted') : t('orderDetail.notices.refundUpdated'),
      description: result.payment_status === 'REFUNDED'
        ? t('orderDetail.notices.refundCompletedDescription')
        : t('orderDetail.notices.refundProcessing'),
      color: result.payment_status === 'REFUNDED' ? 'success' : 'neutral'
    })
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, t('orderDetail.errors.refundStatus'))
    toast.add({ title: t('orderDetail.errors.refundStatusTitle'), description: requestError.value, color: 'error' })
  } finally {
    isRefundStatusLoading.value = false
  }
}

async function openStripeCheckout() {
  if (!order.value || !canResumePayment.value || isOpeningPayment.value || import.meta.server) return

  const paymentWindow = window.open('/checkout/redirecting', '_blank')
  if (!paymentWindow) {
    requestError.value = t('orderDetail.errors.popup')
    return
  }
  try {
    paymentWindow.opener = null
  } catch {
    // The browser can still redirect the new tab when opener cannot be changed.
  }

  isOpeningPayment.value = true
  requestError.value = ''
  try {
    const checkout = await api.openOrderCheckout(order.value.order_no)
    if (checkout.order_no !== order.value.order_no || checkout.status !== 'PENDING_PAYMENT') {
      throw new Error(t('orderDetail.errors.unexpectedState'))
    }
    const target = new URL(checkout.checkout_url)
    if (target.protocol !== 'https:') throw new Error(t('orderDetail.errors.invalidLink'))
    paymentWindow.location.replace(target.toString())
    toast.add({
      title: t('orderDetail.notices.checkoutOpened'),
      description: t('orderDetail.notices.checkoutOpenedDescription', { orderNo: order.value.order_no }),
      color: 'success'
    })
  } catch (error: unknown) {
    if (!paymentWindow.closed) paymentWindow.close()
    const failure = customerRequestDetails(error, t('orderDetail.errors.checkout'))
    try {
      const payment = await api.getOrderPayment(order.value.order_no)
      if (SUCCESS_STATUSES.has(payment.status)) {
        checkoutSession.clear(order.value.order_no)
        await navigateTo(`/orders/${encodeURIComponent(order.value.order_no)}/payment/result`)
        return
      }
      if (payment.status !== 'PENDING_PAYMENT') checkoutSession.clear(order.value.order_no)
    } catch {
      // Keep the checkout failure when the payment status fallback is unavailable.
    }
    requestError.value = failure.message
    toast.add({ title: t('orderDetail.errors.checkoutTitle'), description: failure.message, color: 'error' })
    await loadOrder(false)
  } finally {
    isOpeningPayment.value = false
  }
}

onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  document.addEventListener('keydown', closeRefundFormOnEscape)
  void loadOrder()
})

watch(orderNo, (nextOrderNo, previousOrderNo) => {
  if (!nextOrderNo || nextOrderNo === previousOrderNo) return
  order.value = null
  shipments.value = []
  requestError.value = ''
  shipmentError.value = ''
  cancelReason.value = ''
  refundReason.value = ''
  refundReasonDetail.value = ''
  refundProviderStatus.value = null
  cancelFormOpen.value = false
  refundFormOpen.value = false
  void loadOrder()
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  document.removeEventListener('keydown', closeRefundFormOnEscape)
})
</script>

<template>
  <main class="store-page order-detail-page">
    <StoreHeader />

    <section class="order-title-band">
      <div class="store-container">
        <nav class="order-breadcrumb" :aria-label="t('orderDetail.breadcrumb')">
          <NuxtLink to="/account/orders">{{ t('orderDetail.breadcrumb') }}</NuxtLink>
          <UIcon name="i-lucide-chevron-right" />
          <span>{{ orderNo }}</span>
        </nav>

        <div v-if="isLoading && !order" class="order-title-loading" aria-live="polite" />
        <div v-else-if="order" class="order-title-layout">
          <div>
            <p class="store-eyebrow">{{ t('orderDetail.eyebrow', { orderNo: order.order_no }) }}</p>
            <h1>{{ t('orderDetail.title') }}</h1>
            <p class="order-title-copy">{{ t('orderDetail.placedPieces', { date: formatDate(order.created_at, 'long'), count: itemCount }) }}</p>
          </div>
          <div class="order-title-status">
            <span class="status-pill" :class="`tone-${customerStatusTone(order.status)}`">{{ orderStatusLabel(order.status) }}</span>
            <strong>{{ formatMoney(order.total_amount, order.currency) }}</strong>
            <button type="button" :aria-label="isRefreshing ? t('orderDetail.refreshing') : t('orderDetail.refresh')" :disabled="isRefreshing" @click="loadOrder(false)">
              <UIcon :name="isRefreshing ? 'i-lucide-loader-circle' : 'i-lucide-refresh-cw'" :class="{ spinning: isRefreshing }" />
              {{ isRefreshing ? t('orderDetail.refreshing') : t('orderDetail.refresh') }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <div v-if="requestError" class="store-container order-notice error" role="status">
      <UIcon name="i-lucide-circle-alert" />
      <span>{{ requestError }}</span>
      <button type="button" @click="loadOrder(false)">{{ t('orderDetail.tryAgain') }}</button>
    </div>

    <section v-if="isLoading && !order" class="store-container order-loading" aria-live="polite">
      <div />
      <div />
    </section>

    <section v-else-if="!order" class="store-container order-missing">
      <span>404</span>
      <div>
        <p class="store-eyebrow">{{ t('orderDetail.unavailableEyebrow') }}</p>
        <h2>{{ t('orderDetail.unavailableTitle') }}</h2>
        <p>{{ t('orderDetail.unavailableCopy') }}</p>
        <NuxtLink class="primary-button" to="/account/orders"><UIcon name="i-lucide-arrow-left" /> {{ t('orderDetail.backToOrders') }}</NuxtLink>
      </div>
    </section>

    <template v-else>
      <section class="order-progress-band" :class="{ closed: isClosed }">
        <ol class="store-container order-progress">
          <li
            v-for="(step, index) in lifecycleSteps"
            :key="step.label"
            :class="{ active: currentStage === index, complete: currentStage > index }"
          >
            <span><UIcon :name="currentStage > index ? 'i-lucide-check' : step.icon" /></span>
            <div>
              <strong>{{ step.label }}</strong>
              <small>{{ stepDate(index) ? formatDate(stepDate(index), 'long') : t('orderDetail.lifecycle.pending') }}</small>
            </div>
          </li>
        </ol>
      </section>

      <div v-if="order.status === 'PENDING_PAYMENT'" class="store-container order-payment-notice" :class="{ expired: isPaymentExpired }">
        <UIcon :name="isPaymentExpired ? 'i-lucide-timer-off' : 'i-lucide-timer'" />
        <div>
          <strong>{{ isPaymentExpired ? t('orderDetail.paymentClosed') : t('orderDetail.reservedPayment', { time: deadlineLabel }) }}</strong>
          <span v-if="!isPaymentExpired">{{ t('orderDetail.completePayment') }}</span>
          <span v-else>{{ t('orderDetail.refreshFinalStatus') }}</span>
        </div>
        <button v-if="canResumePayment" type="button" :disabled="isOpeningPayment" @click="openStripeCheckout">
          <UIcon :name="isOpeningPayment ? 'i-lucide-loader-circle' : 'i-lucide-external-link'" :class="{ spinning: isOpeningPayment }" />
          {{ isOpeningPayment ? t('orderDetail.openingStripe') : t('orderDetail.payWithStripe') }}
        </button>
      </div>

      <div class="store-container order-content">
        <div class="order-main-column">
          <section class="order-section order-items-section">
            <header class="section-heading">
              <div><p>{{ t('orderDetail.itemsSection') }}</p><h2>{{ t('orderDetail.pieces', itemCount) }}</h2></div>
              <span>{{ order.currency }}</span>
            </header>
            <div class="order-item-list">
              <article v-for="item in order.items" :key="item.id" class="order-item">
                <NuxtLink class="order-item-image" :to="`/product/${item.product_id}`" :aria-label="t('orderDetail.viewProduct', { name: itemDisplay(item).name })">
                  <img v-if="itemDisplay(item).image" :src="itemDisplay(item).image!" :alt="itemDisplay(item).name">
                  <span v-else>P°</span>
                </NuxtLink>
                <div class="order-item-copy">
                  <span>{{ t('orderDetail.product', { id: item.product_id }) }}</span>
                  <NuxtLink :to="`/product/${item.product_id}`">{{ itemDisplay(item).name }}</NuxtLink>
                  <small>{{ itemDisplay(item).variant || t('orderDetail.pieceFallback') }}</small>
                </div>
                <div class="order-item-quantity"><span>{{ t('orderDetail.quantity') }}</span><strong>{{ item.quantity }}</strong></div>
                <div class="order-item-price"><span>{{ t('orderDetail.each', { price: formatMoney(item.unit_price, order.currency) }) }}</span><strong>{{ formatMoney(item.line_total, order.currency) }}</strong></div>
              </article>
            </div>
          </section>

          <section class="order-section delivery-section">
            <header class="section-heading">
              <div><p>{{ t('orderDetail.deliverySection') }}</p><h2>{{ t('orderDetail.shippingDetails') }}</h2></div>
              <UIcon name="i-lucide-map-pin" />
            </header>
            <div class="delivery-layout">
              <div>
                <span class="detail-label">{{ t('orderDetail.deliveringTo') }}</span>
                <strong>{{ order.shipping_address.name }}</strong>
                <p>{{ formatAddressLine({
                  address_line1: order.shipping_address.address1,
                  address_line2: order.shipping_address.address2,
                  district: order.shipping_address.district,
                  city: order.shipping_address.city,
                  state_or_province: order.shipping_address.state_or_province,
                  postal_code: order.shipping_address.postal_code,
                  country: order.shipping_address.country
                }) }}</p>
              </div>
              <div>
                <span class="detail-label">{{ t('orderDetail.contact') }}</span>
                <strong>{{ order.shipping_address.phone }}</strong>
                <p v-if="order.shipping_address.company">{{ order.shipping_address.company }}</p>
                <p v-if="order.shipping_address.delivery_instructions">{{ order.shipping_address.delivery_instructions }}</p>
              </div>
              <div v-if="order.client_message">
                <span class="detail-label">{{ t('orderDetail.orderNote') }}</span>
                <p>{{ order.client_message }}</p>
              </div>
            </div>
          </section>

          <section v-if="SHIPMENT_STATUSES.has(order.status)" class="order-section shipment-section">
            <header class="section-heading">
              <div><p>{{ t('orderDetail.trackingSection') }}</p><h2>{{ t('orderDetail.deliveryProgress') }}</h2></div>
              <UIcon name="i-lucide-truck" />
            </header>
            <div v-if="shipmentError" class="shipment-empty"><UIcon name="i-lucide-info" /><span>{{ shipmentError }}</span></div>
            <div v-else-if="!shipments.length" class="shipment-empty"><UIcon name="i-lucide-package" /><span>{{ t('orderDetail.trackingEmpty') }}</span></div>
            <div v-else class="shipment-list">
              <article v-for="shipment in shipments" :key="shipment.shipment_no" class="shipment">
                <header>
                  <div><span>{{ shipment.carrier.toUpperCase() }} · {{ shipment.shipment_no }}</span><strong>{{ orderStatusLabel(shipment.status) }}</strong></div>
                  <a v-if="safeTrackingUrl(shipment.tracking_url)" :href="safeTrackingUrl(shipment.tracking_url)!" target="_blank" rel="noopener noreferrer">{{ t('orderDetail.trackParcel') }} <UIcon name="i-lucide-external-link" /></a>
                </header>
                <dl>
                  <div><dt>{{ t('orderDetail.trackingNumber') }}</dt><dd>{{ shipment.tracking_no || t('orderDetail.pending') }}</dd></div>
                  <div><dt>{{ t('orderDetail.lastLocation') }}</dt><dd>{{ shipment.last_track_location || t('orderDetail.updating') }}</dd></div>
                  <div><dt>{{ t('orderDetail.lastUpdate') }}</dt><dd>{{ formatDate(shipment.last_track_at, 'long') }}</dd></div>
                </dl>
                <ol v-if="shipment.tracks.length" class="track-events">
                  <li v-for="track in latestTracks(shipment.tracks)" :key="track.carrier_event_id">
                    <span />
                    <div><strong>{{ track.description || orderStatusLabel(track.normalized_status) }}</strong><small>{{ [track.location, formatDate(track.occurred_at, 'long')].filter(Boolean).join(' · ') }}</small></div>
                  </li>
                </ol>
              </article>
            </div>
          </section>
        </div>

        <aside class="order-summary">
          <p class="store-eyebrow">{{ t('orderDetail.summary') }}</p>
          <h2>{{ orderStatusLabel(order.status) }}</h2>
          <dl class="summary-lines">
            <div><dt>{{ t('orderDetail.items') }}</dt><dd>{{ formatMoney(order.items_subtotal, order.currency) }}</dd></div>
            <div><dt>{{ t('orderDetail.shipping') }}</dt><dd>{{ formatMoney(order.shipping_fee, order.currency) }}</dd></div>
            <div v-if="Number(order.discount_amount) > 0"><dt>{{ t('orderDetail.discount') }}</dt><dd>-{{ formatMoney(order.discount_amount, order.currency) }}</dd></div>
            <div><dt>{{ t('orderDetail.tax') }}</dt><dd>{{ formatMoney(order.tax_amount, order.currency) }}</dd></div>
          </dl>
          <div class="summary-total"><span>{{ t('orderDetail.total') }}</span><strong>{{ formatMoney(order.total_amount, order.currency) }}</strong></div>

          <dl class="summary-dates">
            <div><dt>{{ t('orderDetail.orderNumber') }}</dt><dd>{{ order.order_no }}</dd></div>
            <div><dt>{{ t('orderDetail.payment') }}</dt><dd>{{ orderStatusLabel(order.payment_status) }}</dd></div>
            <div><dt>{{ t('orderDetail.placed') }}</dt><dd>{{ formatDate(order.created_at, 'long') }}</dd></div>
            <div v-if="order.paid_at"><dt>{{ t('orderDetail.paid') }}</dt><dd>{{ formatDate(order.paid_at, 'long') }}</dd></div>
            <div v-if="order.cancelled_at"><dt>{{ t('orderDetail.cancelled') }}</dt><dd>{{ formatDate(order.cancelled_at, 'long') }}</dd></div>
          </dl>

          <div v-if="order.status === 'CANCELLED' && order.cancel_reason" class="cancel-reason"><span>{{ t('orderDetail.cancellationReason') }}</span><p>{{ order.cancel_reason }}</p></div>
          <div v-if="order.refund_reason" class="cancel-reason"><span>{{ t('orderDetail.refundReasonLabel') }}</span><p>{{ order.refund_reason }}</p></div>
          <div v-if="order.refund_reason_detail" class="cancel-reason"><span>{{ t('orderDetail.refundDetailsLabel') }}</span><p>{{ order.refund_reason_detail }}</p></div>

          <div class="summary-actions">
            <button v-if="canResumePayment" class="primary-button" type="button" :disabled="isOpeningPayment" @click="openStripeCheckout"><UIcon :name="isOpeningPayment ? 'i-lucide-loader-circle' : 'i-lucide-external-link'" :class="{ spinning: isOpeningPayment }" /> {{ isOpeningPayment ? t('orderDetail.openingStripe') : t('orderDetail.payWithStripe') }}</button>
            <button v-if="canComplete" class="primary-button" type="button" :disabled="isCompleting" @click="completeOrder"><UIcon :name="isCompleting ? 'i-lucide-loader-circle' : 'i-lucide-circle-check'" :class="{ spinning: isCompleting }" /> {{ isCompleting ? t('orderCompletion.completing') : t('orderCompletion.complete') }}</button>
            <button v-if="canCancel" class="danger-link" type="button" :disabled="isCancelling" @click="cancelFormOpen = !cancelFormOpen"><UIcon name="i-lucide-circle-x" /> {{ cancelFormOpen ? t('orderDetail.closeCancellation') : t('orderDetail.cancel') }}</button>
            <button v-if="canRequestRefund" class="danger-link" type="button" :disabled="isRefundRequesting" @click="openRefundForm"><UIcon :name="isRefundRequesting ? 'i-lucide-loader-circle' : 'i-lucide-rotate-ccw'" :class="{ spinning: isRefundRequesting }" /> {{ isRefundRequesting ? t('orderDetail.requestingRefund') : t('orderDetail.requestRefund') }}</button>
            <button v-if="canQueryRefundStatus" class="outline-button" type="button" :disabled="isRefundStatusLoading" @click="queryRefundStatus"><UIcon :name="isRefundStatusLoading ? 'i-lucide-loader-circle' : 'i-lucide-refresh-cw'" :class="{ spinning: isRefundStatusLoading }" /> {{ isRefundStatusLoading ? t('orderDetail.checkingRefund') : t('orderDetail.checkRefund') }}</button>
            <NuxtLink
              class="support-ticket-link"
              :to="{ path: '/account/support-tickets', query: { create: '1', order_no: order.order_no } }"
            >
              <UIcon name="i-lucide-message-square-plus" /> {{ t('orderDetail.createSupportTicket') }}
            </NuxtLink>
            <NuxtLink to="/account/orders"><UIcon name="i-lucide-arrow-left" /> {{ t('orderDetail.backToOrders') }}</NuxtLink>
          </div>

          <div v-if="isRefunding" class="refund-notice">
            <UIcon name="i-lucide-circle-dollar-sign" />
            <span>{{ t('orderDetail.refundInProgress') }}{{ refundProviderStatus ? ` · ${t('orderDetail.refundProviderStatus', { status: refundProviderStatus })}` : '' }}</span>
          </div>

          <form v-if="cancelFormOpen && canCancel" class="cancel-form" @submit.prevent="cancelOrder">
            <label for="order-cancel-reason">{{ t('orderDetail.reason') }} <small>{{ t('orderDetail.optional') }}</small></label>
            <textarea id="order-cancel-reason" v-model="cancelReason" maxlength="200" rows="3" :placeholder="t('orderDetail.cancellationPlaceholder')" />
            <span>{{ cancelReason.length }} / 200</span>
            <button type="submit" :disabled="isCancelling"><UIcon :name="isCancelling ? 'i-lucide-loader-circle' : 'i-lucide-trash-2'" :class="{ spinning: isCancelling }" /> {{ isCancelling ? t('orderDetail.cancelling') : t('orderDetail.confirmCancellation') }}</button>
          </form>
        </aside>
      </div>
    </template>

    <div v-if="refundFormOpen" class="refund-modal-backdrop" role="presentation" @click.self="closeRefundForm">
      <section class="refund-modal" role="dialog" aria-modal="true" aria-labelledby="refund-modal-title" aria-describedby="refund-modal-copy">
        <header class="refund-modal-header">
          <div>
            <p class="store-eyebrow">{{ t('orderDetail.refundRequest') }}</p>
            <h2 id="refund-modal-title">{{ t('orderDetail.refundTitle') }}</h2>
          </div>
          <button type="button" :aria-label="t('orderDetail.closeRefund')" :disabled="isRefundRequesting" @click="closeRefundForm"><UIcon name="i-lucide-x" /></button>
        </header>
        <p id="refund-modal-copy" class="refund-modal-copy">{{ t('orderDetail.refundCopy') }}</p>
        <form class="refund-form" @submit.prevent="requestRefund">
          <label for="refund-reason">{{ t('orderDetail.reason') }} <small>{{ t('orderDetail.optional') }}</small></label>
          <select id="refund-reason" v-model="refundReason" :disabled="isRefundRequesting">
            <option value="">{{ t('orderDetail.selectReason') }}</option>
            <option v-for="option in refundReasonOptions" :key="option.value" :value="option.value">{{ t(`orderDetail.${option.key}`) }}</option>
          </select>
          <label for="refund-reason-detail">{{ t('orderDetail.additionalDetails') }} <small>{{ t('orderDetail.optional') }}</small></label>
          <textarea id="refund-reason-detail" v-model="refundReasonDetail" maxlength="200" rows="4" :placeholder="t('orderDetail.refundDetailsPlaceholder')" :disabled="isRefundRequesting" />
          <span class="refund-form-count">{{ refundReasonDetail.length }} / 200</span>
          <div class="refund-form-actions">
            <button type="button" class="outline-button" :disabled="isRefundRequesting" @click="closeRefundForm">{{ t('orderDetail.cancelAction') }}</button>
            <button type="submit" class="danger-link" :disabled="isRefundRequesting"><UIcon :name="isRefundRequesting ? 'i-lucide-loader-circle' : 'i-lucide-rotate-ccw'" :class="{ spinning: isRefundRequesting }" /> {{ isRefundRequesting ? t('orderDetail.requestingRefund') : t('orderDetail.confirmRefund') }}</button>
          </div>
        </form>
      </section>
    </div>

    <StoreFooter />
  </main>
</template>

<style scoped>
.order-detail-page { min-height: 100vh; background: #fbf7f5; }
.order-title-band { border-bottom: 1px solid var(--store-line); background: #f1e8e7; }
.order-breadcrumb { min-height: 47px; display: flex; align-items: center; gap: 8px; border-bottom: 1px solid rgba(36,29,33,.1); color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.order-breadcrumb a { color: inherit; text-decoration: none; }
.order-breadcrumb a:hover { color: var(--store-wine); }
.order-breadcrumb .iconify { width: 11px; height: 11px; }
.order-title-layout { min-height: 220px; display: flex; align-items: center; justify-content: space-between; gap: 45px; padding-block: 39px; }
.order-title-layout h1 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 49px; font-weight: 500; letter-spacing: 0; line-height: 1; }
.order-title-copy { margin: 15px 0 0; color: var(--store-muted); font-size: 12px; }
.order-title-status { display: flex; align-items: flex-end; flex-direction: column; gap: 12px; }
.order-title-status > strong { font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; }
.order-title-status button { display: inline-flex; align-items: center; gap: 6px; padding: 0; border: 0; color: var(--store-muted); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.order-title-status button:disabled { cursor: wait; opacity: .55; }
.order-title-status button .iconify { width: 13px; height: 13px; }
.order-title-loading { min-height: 220px; background: linear-gradient(90deg, transparent, rgba(255,255,255,.6), transparent); animation: shimmer 1.4s infinite; }
.status-pill { display: inline-flex; align-items: center; min-height: 24px; padding: 0 9px; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.tone-warm { color: #805e3d; background: #f4e7d8; }
.tone-accent { color: #77526b; background: #eadfe9; }
.tone-success { color: #52715b; background: #e0ebdf; }
.tone-muted { color: #81767b; background: #e8e3e4; }

.order-notice { display: flex; align-items: center; gap: 10px; margin-top: 19px; padding: 12px 14px; border: 1px solid #d9a6ad; color: #7b3442; background: #fbebed; font-size: 11px; }
.order-notice .iconify { width: 15px; height: 15px; flex: 0 0 auto; }
.order-notice span { flex: 1; }
.order-notice button { padding: 0; border: 0; color: inherit; background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.refund-notice { display: flex; align-items: center; gap: 10px; margin-top: 14px; padding: 12px 14px; border: 1px solid #d7c6a7; color: #6b5635; background: #faf3e3; font-size: 11px; }
.refund-notice .iconify { width: 15px; height: 15px; flex: 0 0 auto; }
.order-loading { display: grid; grid-template-columns: minmax(0,1.45fr) minmax(270px,.55fr); gap: 18px; padding-block: 38px 80px; }
.order-loading div { min-height: 520px; border: 1px solid var(--store-line); background: linear-gradient(90deg, #f4efed, #fff, #f4efed); background-size: 200% 100%; animation: shimmer 1.4s infinite; }
@keyframes shimmer { from { background-position: 100% 0; } to { background-position: -100% 0; } }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.order-missing { min-height: 480px; display: grid; grid-template-columns: 120px minmax(0,1fr); align-items: center; gap: 35px; padding-block: 70px; }
.order-missing > span { color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 75px; }
.order-missing h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 38px; font-weight: 500; }
.order-missing p:not(.store-eyebrow) { color: var(--store-muted); font-size: 12px; }

.order-progress-band { border-bottom: 1px solid var(--store-line); background: #fff; }
.order-progress { min-height: 105px; display: grid; grid-template-columns: repeat(4, minmax(0,1fr)); align-items: center; padding-block: 18px; }
.order-progress li { position: relative; display: grid; grid-template-columns: 38px minmax(0,1fr); align-items: center; gap: 10px; list-style: none; }
.order-progress li:not(:last-child)::after { position: absolute; top: 19px; right: 13px; left: 51px; height: 1px; background: var(--store-line); content: ''; }
.order-progress li > span { width: 38px; height: 38px; z-index: 1; display: grid; place-items: center; border: 1px solid var(--store-line); color: var(--store-muted); background: #fff; }
.order-progress li.complete > span { border-color: #2d5d50; color: #fff; background: #2d5d50; }
.order-progress li.active > span { border-color: var(--store-wine); color: #fff; background: var(--store-wine); }
.order-progress .iconify { width: 16px; height: 16px; }
.order-progress li div { min-width: 0; display: flex; flex-direction: column; gap: 4px; padding-right: 17px; }
.order-progress strong { font-size: 10px; }
.order-progress small { overflow: hidden; color: var(--store-muted); font-size: 8px; text-overflow: ellipsis; white-space: nowrap; }
.order-progress-band.closed .order-progress { opacity: .48; }

.order-payment-notice { display: grid; grid-template-columns: 34px minmax(0,1fr) auto; align-items: center; gap: 12px; margin-top: 20px; padding: 13px 15px; border: 1px solid #d8bd91; color: #72583b; background: #f7eddf; }
.order-payment-notice > .iconify { width: 20px; height: 20px; }
.order-payment-notice div { display: flex; flex-direction: column; gap: 3px; }
.order-payment-notice strong { font-size: 11px; }
.order-payment-notice span { font-size: 9px; }
.order-payment-notice button { min-height: 37px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding: 0 12px; border: 1px solid currentColor; color: inherit; background: transparent; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.order-payment-notice.expired { border-color: var(--store-line); color: var(--store-muted); background: #eee9e9; }

.order-content { display: grid; grid-template-columns: minmax(0,1.45fr) minmax(280px,.55fr); align-items: start; gap: 18px; padding-top: 30px; padding-bottom: 90px; }
.order-main-column { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
.order-section, .order-summary { border: 1px solid var(--store-line); background: rgba(255,255,255,.72); }
.section-heading { min-height: 83px; display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 17px 20px; border-bottom: 1px solid var(--store-line); box-sizing: border-box; }
.section-heading p { margin: 0 0 6px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; }
.section-heading h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 27px; font-weight: 500; letter-spacing: 0; }
.section-heading > span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; }
.section-heading > .iconify { width: 20px; height: 20px; color: var(--store-wine); }
.order-item { min-height: 121px; display: grid; grid-template-columns: 76px minmax(0,1fr) 70px auto; align-items: center; gap: 15px; padding: 14px 17px; border-bottom: 1px solid rgba(36,29,33,.1); box-sizing: border-box; }
.order-item:last-child { border-bottom: 0; }
.order-item-image { width: 76px; height: 94px; display: grid; place-items: center; overflow: hidden; color: var(--store-wine); background: var(--store-linen); font-family: 'Playfair Display', Georgia, serif; text-decoration: none; }
.order-item-image img { width: 100%; height: 100%; object-fit: cover; }
.order-item-image span { font-size: 25px; }
.order-item-copy { min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }
.order-item-copy > span { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 7px; }
.order-item-copy a { overflow: hidden; max-width: 100%; color: var(--store-ink); font-family: 'Playfair Display', Georgia, serif; font-size: 20px; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; }
.order-item-copy a:hover { color: var(--store-wine); }
.order-item-copy small, .order-item-quantity span, .order-item-price span { color: var(--store-muted); font-size: 8px; }
.order-item-quantity, .order-item-price { display: flex; align-items: flex-end; flex-direction: column; gap: 7px; white-space: nowrap; }
.order-item-quantity strong, .order-item-price strong { font-size: 11px; }
.delivery-layout { display: grid; grid-template-columns: 1.2fr .8fr; gap: 0; }
.delivery-layout > div { min-height: 125px; padding: 19px 20px; border-right: 1px solid rgba(36,29,33,.1); box-sizing: border-box; }
.delivery-layout > div:nth-child(2) { border-right: 0; }
.delivery-layout > div:nth-child(3) { grid-column: 1 / -1; min-height: auto; border-top: 1px solid rgba(36,29,33,.1); border-right: 0; }
.detail-label { display: block; margin-bottom: 9px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 7px; }
.delivery-layout strong { font-size: 11px; }
.delivery-layout p { max-width: 520px; margin: 6px 0 0; color: var(--store-muted); font-size: 9px; line-height: 1.6; }

.shipment-empty { min-height: 92px; display: flex; align-items: center; gap: 10px; padding: 18px 20px; color: var(--store-muted); font-size: 10px; box-sizing: border-box; }
.shipment-empty .iconify { width: 17px; height: 17px; color: var(--store-wine); }
.shipment { border-bottom: 1px solid rgba(36,29,33,.1); }
.shipment:last-child { border-bottom: 0; }
.shipment > header { min-height: 67px; display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 12px 20px; background: rgba(241,232,231,.3); box-sizing: border-box; }
.shipment > header div { display: flex; flex-direction: column; gap: 5px; }
.shipment > header span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 7px; }
.shipment > header strong { font-size: 11px; }
.shipment > header a { display: inline-flex; align-items: center; gap: 6px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; text-decoration: none; text-transform: uppercase; }
.shipment > header a .iconify { width: 12px; height: 12px; }
.shipment dl { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); margin: 0; padding: 15px 20px; }
.shipment dl div { min-width: 0; display: flex; flex-direction: column; gap: 5px; }
.shipment dt { color: var(--store-muted); font-size: 8px; }
.shipment dd { overflow-wrap: anywhere; margin: 0; font-size: 9px; font-weight: 600; }
.track-events { margin: 0; padding: 0 20px 17px; list-style: none; }
.track-events li { min-height: 39px; display: grid; grid-template-columns: 10px minmax(0,1fr); gap: 9px; }
.track-events li > span { width: 7px; height: 7px; margin-top: 4px; border-radius: 50%; background: #2d5d50; }
.track-events li div { display: flex; flex-direction: column; gap: 3px; }
.track-events strong { font-size: 9px; }
.track-events small { color: var(--store-muted); font-size: 8px; }

.order-summary { position: sticky; top: 20px; padding: 23px 21px; }
.order-summary h2 { margin: 0 0 20px; font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; letter-spacing: 0; }
.summary-lines, .summary-dates { margin: 0; }
.summary-lines div, .summary-dates div { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; }
.summary-lines div { padding: 10px 0; border-top: 1px solid rgba(36,29,33,.1); }
.summary-lines dt, .summary-lines dd { font-size: 10px; }
.summary-lines dt, .summary-dates dt { color: var(--store-muted); }
.summary-lines dd, .summary-dates dd { margin: 0; text-align: right; }
.summary-total { display: flex; align-items: baseline; justify-content: space-between; gap: 15px; margin-top: 7px; padding: 15px 0; border-top: 1px solid var(--store-ink); border-bottom: 1px solid var(--store-line); }
.summary-total span { font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.summary-total strong { color: var(--store-wine); font-family: 'Playfair Display', Georgia, serif; font-size: 24px; font-weight: 500; }
.summary-dates { padding: 13px 0; border-bottom: 1px solid var(--store-line); }
.summary-dates div { padding: 4px 0; }
.summary-dates dt, .summary-dates dd { font-size: 8px; }
.cancel-reason { padding: 13px 0; border-bottom: 1px solid var(--store-line); }
.cancel-reason span { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 7px; text-transform: uppercase; }
.cancel-reason p { margin: 6px 0 0; color: var(--store-muted); font-size: 9px; line-height: 1.5; }
.summary-actions { display: flex; align-items: stretch; flex-direction: column; gap: 11px; padding-top: 17px; }
.summary-actions > a, .summary-actions > button { min-height: 39px; display: flex; align-items: center; justify-content: center; gap: 7px; box-sizing: border-box; font-family: 'DM Mono', monospace; font-size: 8px; text-decoration: none; text-transform: uppercase; }
.summary-actions > a { color: var(--store-ink); }
.summary-actions .support-ticket-link { border: 1px solid var(--store-wine); color: var(--store-wine); background: rgba(154,64,85,.06); }
.summary-actions .support-ticket-link:hover { color: #fff; background: var(--store-wine); }
.summary-actions .danger-link { border: 0; color: #963f4f; background: none; cursor: pointer; }
.primary-button { min-height: 41px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; padding: 0 15px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; text-decoration: none; text-transform: uppercase; }
.primary-button:hover { color: var(--store-ink); background: transparent; }
.primary-button:disabled { cursor: wait; opacity: .55; }
.cancel-form { display: flex; flex-direction: column; margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--store-line); }
.cancel-form label { margin-bottom: 7px; color: var(--store-ink); font-size: 9px; font-weight: 600; }
.cancel-form label small { color: var(--store-muted); font-size: 7px; font-weight: 400; }
.cancel-form textarea { width: 100%; min-height: 76px; resize: vertical; box-sizing: border-box; padding: 9px; border: 1px solid var(--store-line); outline: 0; color: var(--store-ink); background: #fff; font: inherit; font-size: 10px; }
.cancel-form textarea:focus { border-color: var(--store-wine); }
.cancel-form > span { align-self: flex-end; margin-top: 4px; color: var(--store-muted); font-size: 7px; }
.cancel-form button { min-height: 38px; display: flex; align-items: center; justify-content: center; gap: 7px; margin-top: 10px; border: 1px solid #963f4f; color: #fff; background: #963f4f; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.cancel-form button:disabled { cursor: wait; opacity: .55; }
.refund-modal-backdrop { position: fixed; z-index: 30; inset: 0; display: grid; place-items: center; padding: 18px; background: rgba(36,29,33,.42); }
.refund-modal { width: min(440px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; padding: 24px; border: 1px solid var(--store-line); background: #fffdfa; box-shadow: 0 18px 55px rgba(36,29,33,.2); }
.refund-modal-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.refund-modal-header h2 { margin: 4px 0 0; font-family: 'Playfair Display', Georgia, serif; font-size: 27px; font-weight: 500; line-height: 1.15; }
.refund-modal-header button { display: grid; place-items: center; width: 30px; height: 30px; padding: 0; border: 1px solid var(--store-line); color: var(--store-muted); background: transparent; cursor: pointer; }
.refund-modal-header button:disabled { cursor: wait; opacity: .55; }
.refund-modal-header button .iconify { width: 15px; height: 15px; }
.refund-modal-copy { margin: 13px 0 0; color: var(--store-muted); font-size: 11px; line-height: 1.55; }
.refund-form { display: flex; flex-direction: column; margin-top: 20px; }
.refund-form label { margin-bottom: 7px; color: var(--store-ink); font-size: 9px; font-weight: 600; }
.refund-form label small { color: var(--store-muted); font-size: 7px; font-weight: 400; }
.refund-form select, .refund-form textarea { width: 100%; box-sizing: border-box; margin-bottom: 14px; padding: 10px; border: 1px solid var(--store-line); outline: 0; color: var(--store-ink); background: #fff; font: inherit; font-size: 10px; }
.refund-form select:focus, .refund-form textarea:focus { border-color: var(--store-wine); }
.refund-form textarea { min-height: 90px; resize: vertical; }
.refund-form-count { align-self: flex-end; margin-top: -10px; color: var(--store-muted); font-size: 7px; }
.refund-form-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 17px; }
.refund-form-actions button { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 7px; padding-inline: 14px; }
@media (max-width: 520px) { .refund-modal { padding: 20px; } .refund-modal-header h2 { font-size: 24px; } .refund-form-actions { flex-direction: column-reverse; } .refund-form-actions button { width: 100%; } }

@media (max-width: 900px) {
  .order-content { grid-template-columns: minmax(0,1fr) 270px; }
  .order-item { grid-template-columns: 66px minmax(0,1fr) auto; }
  .order-item-image { width: 66px; height: 84px; }
  .order-item-quantity { display: none; }
  .order-progress { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 15px 0; }
  .order-progress li:nth-child(2)::after { display: none; }
}

@media (max-width: 720px) {
  .order-title-layout { align-items: flex-start; flex-direction: column; gap: 24px; min-height: 250px; }
  .order-title-layout h1 { font-size: 42px; }
  .order-title-status { align-items: flex-start; }
  .order-progress { grid-template-columns: 1fr; padding-block: 20px; }
  .order-progress li:not(:last-child)::after { top: 38px; right: auto; bottom: -15px; left: 18px; width: 1px; height: auto; }
  .order-content, .order-loading { grid-template-columns: 1fr; }
  .order-summary { position: static; order: -1; }
  .order-payment-notice { grid-template-columns: 28px minmax(0,1fr); }
  .order-payment-notice button { grid-column: 1 / -1; }
}

@media (max-width: 520px) {
  .order-title-layout h1 { font-size: 36px; }
  .order-missing { grid-template-columns: 1fr; gap: 10px; }
  .order-missing > span { font-size: 55px; }
  .order-item { grid-template-columns: 58px minmax(0,1fr); gap: 11px; }
  .order-item-image { width: 58px; height: 72px; }
  .order-item-price { grid-column: 2; align-items: flex-start; flex-direction: row; }
  .order-item-price span { display: none; }
  .delivery-layout, .shipment dl { grid-template-columns: 1fr; }
  .delivery-layout > div { min-height: auto; border-right: 0; border-bottom: 1px solid rgba(36,29,33,.1); }
  .delivery-layout > div:last-child { border-bottom: 0; }
  .shipment dl { gap: 11px; }
  .shipment > header { align-items: flex-start; flex-direction: column; }
}
</style>
