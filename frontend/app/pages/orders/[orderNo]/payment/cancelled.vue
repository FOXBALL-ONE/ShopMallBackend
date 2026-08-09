<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CustomerOrderPayment, CustomerOrderStatus } from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Payment paused | Pelissa',
  meta: [{ name: 'robots', content: 'noindex' }]
})

const route = useRoute()
const api = useCustomerAccountApi()
const session = useCustomerSession()
const checkoutSession = useOrderCheckoutSession()

const SUCCESS_STATUSES = new Set<CustomerOrderStatus>(['PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED'])
const orderNo = computed(() => String(route.params.orderNo || '').trim())
const payment = ref<CustomerOrderPayment | null>(null)
const state = ref<'loading' | 'pending' | 'closed' | 'error'>('loading')
const message = ref('Checking the latest order status.')
const canResume = ref(false)
const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | null = null

const remainingSeconds = computed(() => {
  if (!payment.value?.expires_at) return 0
  return Math.max(0, Math.floor((new Date(payment.value.expires_at).getTime() - now.value) / 1000))
})

const remainingLabel = computed(() => {
  const minutes = Math.floor(remainingSeconds.value / 60)
  const seconds = remainingSeconds.value % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})

const paymentWindowOpen = computed(() => state.value === 'pending' && remainingSeconds.value > 0)
const displayMessage = computed(() => paymentWindowOpen.value
  ? message.value
  : state.value === 'pending'
    ? 'The payment window has closed. View your orders for the latest status.'
    : message.value)

const pageTitle = computed(() => {
  if (state.value === 'closed' || (state.value === 'pending' && !paymentWindowOpen.value)) return 'This order can no longer be paid.'
  if (state.value === 'error') return 'We could not check this order.'
  if (state.value === 'loading') return 'Checking your order.'
  return 'Payment was not completed.'
})

async function loadPayment() {
  const userId = await session.requireSignIn()
  if (!userId) return
  if (!orderNo.value) {
    state.value = 'error'
    message.value = 'The order reference is missing.'
    return
  }

  try {
    const result = await api.getOrderPayment(orderNo.value)
    payment.value = result

    if (SUCCESS_STATUSES.has(result.status)) {
      checkoutSession.clear(orderNo.value)
      await navigateTo(`/orders/${encodeURIComponent(orderNo.value)}/payment/result`)
      return
    }

    const expiresAt = result.expires_at ? new Date(result.expires_at).getTime() : Number.NaN
    if (result.status !== 'PENDING_PAYMENT' || !Number.isFinite(expiresAt) || expiresAt <= Date.now()) {
      checkoutSession.clear(orderNo.value)
      state.value = 'closed'
      message.value = 'No payment was captured. View your orders for the latest status.'
      return
    }

    const context = checkoutSession.read()
    canResume.value = context?.orderNo === orderNo.value
    state.value = 'pending'
    message.value = canResume.value
      ? 'Your order is still reserved. Continue with Stripe before the payment window closes.'
      : 'Your order is reserved, but this browser session no longer has the payment key. It will cancel automatically when the payment window closes.'
  } catch (error: unknown) {
    state.value = 'error'
    message.value = customerRequestMessage(error, 'Payment status is temporarily unavailable. Please try again from your orders.')
  }
}

onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  void loadPayment()
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <main class="store-page cancelled-page">
    <StoreHeader />
    <section class="cancelled-band">
      <div class="store-container cancelled-layout">
        <div class="cancelled-copy">
          <span class="cancelled-icon">
            <UIcon :name="state === 'loading' ? 'i-lucide-loader-circle' : state === 'pending' ? 'i-lucide-credit-card' : 'i-lucide-circle-alert'" :class="{ spinning: state === 'loading' }" />
          </span>
          <p class="store-eyebrow">PAYMENT PAUSED / 03</p>
          <h1>{{ pageTitle }}</h1>
          <p>{{ displayMessage }}</p>
          <div class="cancelled-reference"><span>Order</span><strong>{{ orderNo }}</strong></div>
          <p v-if="paymentWindowOpen" class="cancelled-deadline">Payment window: {{ remainingLabel }}</p>
          <div class="cancelled-actions">
            <NuxtLink v-if="paymentWindowOpen && canResume" class="primary-action" to="/checkout"><UIcon name="i-lucide-lock-keyhole" /> Return to checkout</NuxtLink>
            <NuxtLink class="secondary-action" :to="`/orders/${encodeURIComponent(orderNo)}`">View order <UIcon name="i-lucide-arrow-right" /></NuxtLink>
          </div>
        </div>
        <div class="cancelled-visual" aria-hidden="true"><span>YOUR ORDER IS HELD</span><b>P°</b></div>
      </div>
    </section>
    <StoreFooter />
  </main>
</template>

<style scoped>
.cancelled-page { background: #fbf7f5; }
.cancelled-band { border-bottom: 1px solid var(--store-line); background: #f1e8e7; }
.cancelled-layout { min-height: 560px; display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(330px, .85fr); gap: 70px; }
.cancelled-copy { display: flex; justify-content: center; flex-direction: column; padding: 65px 0; }
.cancelled-icon { width: 48px; height: 48px; display: grid; place-items: center; margin-bottom: 24px; color: #fff; background: #3e5968; }
.cancelled-icon .iconify { width: 23px; height: 23px; }
.cancelled-copy h1 { max-width: 650px; margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 55px; font-weight: 500; letter-spacing: 0; line-height: 1; }
.cancelled-copy > p:not(.store-eyebrow) { max-width: 540px; margin: 20px 0 0; color: var(--store-muted); font-size: 13px; line-height: 1.65; }
.cancelled-reference { width: fit-content; display: flex; align-items: center; gap: 17px; margin-top: 22px; padding: 11px 14px; border-left: 3px solid var(--store-wine); background: rgba(255,255,255,.52); }
.cancelled-reference span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.cancelled-reference strong { font-size: 10px; }
.cancelled-deadline { width: fit-content; margin-top: 10px !important; color: var(--store-wine) !important; font-family: 'DM Mono', monospace; font-size: 9px !important; }
.cancelled-actions { display: flex; align-items: center; gap: 20px; margin-top: 28px; }
.primary-action, .secondary-action { min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; text-decoration: none; text-transform: uppercase; }
.primary-action { padding: 0 16px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); }
.primary-action:hover { color: var(--store-ink); background: transparent; }
.secondary-action { color: var(--store-ink); }
.secondary-action:hover { color: var(--store-wine); }
.primary-action .iconify, .secondary-action .iconify { width: 13px; height: 13px; }
.cancelled-visual { position: relative; min-height: 560px; overflow: hidden; background: linear-gradient(180deg, rgba(36,29,33,.04), rgba(36,29,33,.58)), url('/lingerie/lace-black.jpg') center / cover; }
.cancelled-visual::after { position: absolute; inset: 17px; border: 1px solid rgba(255,255,255,.42); content: ''; }
.cancelled-visual span, .cancelled-visual b { position: absolute; z-index: 1; color: #fff; }
.cancelled-visual span { top: 31px; left: 33px; font-family: 'DM Mono', monospace; font-size: 8px; }
.cancelled-visual b { right: 34px; bottom: 26px; color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 41px; font-weight: 500; }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 760px) {
  .cancelled-layout { display: block; }
  .cancelled-copy { min-height: 440px; padding: 50px 0; box-sizing: border-box; }
  .cancelled-copy h1 { font-size: 43px; }
  .cancelled-visual { min-height: 220px; }
}
@media (max-width: 440px) {
  .cancelled-copy h1 { font-size: 36px; }
  .cancelled-actions { align-items: flex-start; flex-direction: column; }
  .primary-action { width: 100%; box-sizing: border-box; }
}
</style>
