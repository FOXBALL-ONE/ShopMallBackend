<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { CustomerOrder, CustomerOrderStatus } from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import {
  formatAddressLine,
  parseProductSnapshot
} from '~/utils/customer-display'

definePageMeta({ middleware: ['customer-auth'] })

const { formatDate, formatMoney, t } = useStorefrontI18n()

useHead(() => ({
  title: t('paymentPage.result.seoTitle'),
  meta: [
    { name: 'description', content: t('paymentPage.result.seoDescription') },
    { name: 'robots', content: 'noindex' }
  ]
}))

type ResultState = 'loading' | 'verifying' | 'success' | 'pending' | 'failed' | 'error'

const SUCCESS_STATUSES = new Set<CustomerOrderStatus>(['PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED'])
const MAX_VERIFICATION_ATTEMPTS = 15
const VERIFICATION_DELAY_MS = 2000

const route = useRoute()
const api = useCustomerAccountApi()
const session = useCustomerSession()
const checkoutSession = useOrderCheckoutSession()

const order = ref<CustomerOrder | null>(null)
const state = ref<ResultState>('loading')
const messageKey = ref('')
const requestMessage = ref('')
const attempts = ref(0)
let verificationTimer: ReturnType<typeof setTimeout> | null = null

const orderNo = computed(() => String(route.params.orderNo || '').trim())
const stripeSessionId = computed(() => typeof route.query.session_id === 'string' ? route.query.session_id.trim() : '')
const itemCount = computed(() => (order.value?.items || []).reduce((total, item) => total + item.quantity, 0))
const message = computed(() => requestMessage.value || (messageKey.value ? t(`paymentPage.result.${messageKey.value}`) : ''))
const resultTitle = computed(() => {
  if (state.value === 'success') return t('paymentPage.result.successTitle')
  if (state.value === 'failed') return t('paymentPage.result.failedTitle')
  if (state.value === 'pending') return t('paymentPage.result.pendingTitle')
  if (state.value === 'error') return t('paymentPage.result.errorTitle')
  return t('paymentPage.result.checkingTitle')
})
const resultEyebrow = computed(() => state.value === 'success'
  ? t('paymentPage.result.successEyebrow')
  : t('paymentPage.result.statusEyebrow'))

function scheduleVerification() {
  if (verificationTimer) clearTimeout(verificationTimer)
  verificationTimer = setTimeout(() => { void verifyPayment() }, VERIFICATION_DELAY_MS)
}

async function verifyPayment() {
  if (!orderNo.value) {
    state.value = 'error'
    messageKey.value = 'missingOrder'
    return
  }

  state.value = 'verifying'
  attempts.value += 1

  try {
    const payment = await api.getOrderPayment(orderNo.value)
    if (
      stripeSessionId.value
      && payment.checkout_session_id
      && stripeSessionId.value !== payment.checkout_session_id
    ) {
      state.value = 'error'
      messageKey.value = 'sessionMismatch'
      return
    }

    if (SUCCESS_STATUSES.has(payment.status)) {
      order.value = await api.getOrder(orderNo.value)
      checkoutSession.clear(orderNo.value)
      state.value = 'success'
      messageKey.value = 'confirmedMessage'
      return
    }

    if (['CANCELLED', 'DELETED'].includes(payment.status)) {
      order.value = await api.getOrder(orderNo.value)
      checkoutSession.clear(orderNo.value)
      state.value = 'failed'
      messageKey.value = 'failedMessage'
      return
    }

    if (attempts.value < MAX_VERIFICATION_ATTEMPTS) {
      scheduleVerification()
      return
    }

    state.value = 'pending'
    messageKey.value = 'pendingMessage'
  } catch (error: unknown) {
    state.value = 'error'
    requestMessage.value = customerRequestMessage(error, t('paymentPage.result.unavailable'))
  }
}

async function loadResult() {
  const userId = await session.requireSignIn()
  if (!userId) return

  state.value = 'loading'
  messageKey.value = ''
  requestMessage.value = ''
  try {
    const orderResult = await api.getOrder(orderNo.value)
    order.value = orderResult

    if (SUCCESS_STATUSES.has(orderResult.status) && !stripeSessionId.value) {
      checkoutSession.clear(orderNo.value)
      state.value = 'success'
      messageKey.value = 'confirmedMessage'
      return
    }
    if (['CANCELLED', 'DELETED'].includes(orderResult.status)) {
      checkoutSession.clear(orderNo.value)
      state.value = 'failed'
      messageKey.value = 'failedMessage'
      return
    }
    await verifyPayment()
  } catch (error: unknown) {
    state.value = 'error'
    requestMessage.value = customerRequestMessage(error, t('paymentPage.result.loadFailed'))
  }
}

function retryVerification() {
  attempts.value = 0
  messageKey.value = ''
  requestMessage.value = ''
  void verifyPayment()
}

onMounted(() => { void loadResult() })
onBeforeUnmount(() => {
  if (verificationTimer) clearTimeout(verificationTimer)
})
</script>

<template>
  <main class="store-page result-page">
    <StoreHeader />

    <div class="result-progress-wrap">
      <nav class="store-container result-progress" :aria-label="t('paymentPage.result.progressLabel')">
        <span><b>01</b> {{ t('paymentPage.result.cart') }}</span><UIcon name="i-lucide-chevron-right" />
        <span><b>02</b> {{ t('paymentPage.result.delivery') }}</span><UIcon name="i-lucide-chevron-right" />
        <strong><b>03</b> {{ state === 'success' ? t('paymentPage.result.complete') : t('paymentPage.result.payment') }}</strong>
      </nav>
    </div>

    <section class="result-hero" :class="`state-${state}`">
      <div class="store-container result-hero-inner">
        <div class="result-copy">
          <span class="result-symbol" aria-hidden="true">
            <UIcon v-if="state === 'success'" name="i-lucide-check" />
            <UIcon v-else-if="state === 'failed' || state === 'error'" name="i-lucide-x" />
            <UIcon v-else name="i-lucide-loader-circle" class="spinning" />
          </span>
          <p class="store-eyebrow">{{ resultEyebrow }}</p>
          <h1>{{ resultTitle }}</h1>
          <p class="result-message">{{ message || t('paymentPage.result.checkingMessage') }}</p>
          <div v-if="order" class="result-reference">
            <span>{{ t('paymentPage.result.order') }}</span><strong>{{ order.order_no }}</strong>
            <span>{{ t('paymentPage.result.total') }}</span><strong>{{ formatMoney(order.total_amount, order.currency) }}</strong>
          </div>
          <div class="result-actions">
            <NuxtLink class="primary-action" :to="`/orders/${encodeURIComponent(orderNo)}`"><UIcon name="i-lucide-receipt-text" /> {{ t('paymentPage.result.viewOrder') }}</NuxtLink>
            <NuxtLink class="secondary-action" to="/collections/shop">{{ t('paymentPage.result.continueShopping') }} <UIcon name="i-lucide-arrow-right" /></NuxtLink>
            <button v-if="state === 'pending' || state === 'error'" type="button" @click="retryVerification"><UIcon name="i-lucide-refresh-cw" /> {{ t('paymentPage.result.checkAgain') }}</button>
          </div>
        </div>
        <div class="result-visual" aria-hidden="true">
          <div class="result-photo" />
          <span>{{ t('paymentPage.result.visualLabel') }}</span>
          <b>P°</b>
        </div>
      </div>
    </section>

    <section v-if="order" class="store-container result-details">
      <div class="result-summary">
        <div class="details-heading">
          <p>{{ t('paymentPage.result.summary') }}</p>
          <h2>{{ t('paymentPage.result.pieces', itemCount) }}</h2>
        </div>
        <div class="result-items">
          <article v-for="item in order.items" :key="item.id">
            <span class="item-mark">{{ item.quantity }}</span>
            <div>
              <strong>{{ parseProductSnapshot(item.product_snapshot).name }}</strong>
              <small>{{ parseProductSnapshot(item.product_snapshot).color || t('paymentPage.result.pieceFallback') }}</small>
            </div>
            <b>{{ formatMoney(item.line_total, order.currency) }}</b>
          </article>
        </div>
        <div class="result-total">
          <span>{{ state === 'success' ? t('paymentPage.result.paidTotal') : t('paymentPage.result.orderTotal') }}</span>
          <strong>{{ formatMoney(order.total_amount, order.currency) }}</strong>
        </div>
      </div>

      <div class="result-delivery">
        <div class="details-heading">
          <p>{{ t('paymentPage.result.deliveryDetails') }}</p>
          <h2>{{ t('paymentPage.result.nextTitle') }}</h2>
        </div>
        <div class="delivery-address">
          <span><UIcon name="i-lucide-map-pin" /></span>
          <div>
            <strong>{{ order.shipping_address.name }}</strong>
            <p>{{ formatAddressLine({
              address_line1: order.shipping_address.address1,
              address_line2: order.shipping_address.address2,
              city: order.shipping_address.city,
              district: order.shipping_address.district,
              state_or_province: order.shipping_address.state_or_province,
              postal_code: order.shipping_address.postal_code,
              country: order.shipping_address.country
            }) }}</p>
          </div>
        </div>
        <ol class="next-steps">
          <li :class="{ active: state === 'success' }">
            <span><UIcon :name="state === 'success' ? 'i-lucide-check' : 'i-lucide-clock-3'" /></span>
            <div>
              <strong>{{ state === 'success' ? t('paymentPage.result.paymentConfirmed') : t('paymentPage.result.paymentPending') }}</strong>
              <small>{{ state === 'success' ? formatDate(order.paid_at, 'long') : t('paymentPage.result.waitingStripe') }}</small>
            </div>
          </li>
          <li><span><UIcon name="i-lucide-package-check" /></span><div><strong>{{ t('paymentPage.result.preparing') }}</strong><small>{{ t('paymentPage.result.preparingCopy') }}</small></div></li>
          <li><span><UIcon name="i-lucide-truck" /></span><div><strong>{{ t('paymentPage.result.deliveryUpdates') }}</strong><small>{{ t('paymentPage.result.deliveryUpdatesCopy') }}</small></div></li>
        </ol>
      </div>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.result-page { background: #fbf7f5; }
.result-progress-wrap { border-bottom: 1px solid var(--store-line); background: #fff; }
.result-progress { min-height: 49px; display: flex; align-items: center; gap: 13px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: 0; text-transform: uppercase; }
.result-progress > span, .result-progress strong { display: inline-flex; align-items: center; gap: 7px; }
.result-progress strong { color: var(--store-ink); }
.result-progress b { color: var(--store-wine); font-size: 8px; font-weight: 500; }
.result-progress > .iconify { width: 12px; height: 12px; opacity: .45; }

.result-hero { border-bottom: 1px solid var(--store-line); background: #f1e8e7; }
.result-hero-inner { min-height: 440px; display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(330px, .8fr); gap: 70px; }
.result-copy { display: flex; justify-content: center; flex-direction: column; padding: 58px 0 64px; }
.result-symbol { width: 48px; height: 48px; display: grid; place-items: center; margin-bottom: 25px; color: #fff; background: #2d5d50; }
.result-symbol .iconify { width: 24px; height: 24px; }
.state-failed .result-symbol, .state-error .result-symbol { background: #963f4f; }
.state-loading .result-symbol, .state-verifying .result-symbol, .state-pending .result-symbol { background: #3e5968; }
.result-copy h1 { max-width: 690px; margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 58px; font-weight: 500; letter-spacing: 0; line-height: .98; }
.result-message { max-width: 540px; min-height: 42px; margin: 20px 0 0; color: var(--store-muted); font-size: 13px; line-height: 1.65; }
.result-reference { width: fit-content; display: grid; grid-template-columns: auto auto; gap: 6px 21px; margin-top: 22px; padding: 12px 15px; border-left: 3px solid var(--store-wine); background: rgba(255,255,255,.5); }
.result-reference span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.result-reference strong { font-size: 10px; font-weight: 600; }
.result-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px 19px; margin-top: 28px; }
.primary-action, .secondary-action, .result-actions button { min-height: 43px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; box-sizing: border-box; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; text-decoration: none; text-transform: uppercase; }
.primary-action { padding: 0 16px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); }
.primary-action:hover { color: var(--store-ink); background: transparent; }
.secondary-action { color: var(--store-ink); }
.secondary-action:hover { color: var(--store-wine); }
.result-actions button { padding: 0; border: 0; color: var(--store-wine); background: transparent; cursor: pointer; }
.result-actions .iconify { width: 13px; height: 13px; }

.result-visual { position: relative; min-height: 440px; overflow: hidden; background: #24383b; }
.result-photo { position: absolute; inset: 0; background: linear-gradient(180deg, rgba(23,38,39,.05), rgba(23,38,39,.58)), url('/lingerie/lace-green.jpg') center / cover; }
.result-visual::after { position: absolute; inset: 17px; border: 1px solid rgba(255,255,255,.45); content: ''; }
.result-visual > span, .result-visual > b { position: absolute; z-index: 1; color: #fff; }
.result-visual > span { top: 31px; left: 33px; font-family: 'DM Mono', monospace; font-size: 8px; font-weight: 400; letter-spacing: 0; }
.result-visual > b { right: 34px; bottom: 26px; color: #e5cfcb; font-family: 'Playfair Display', Georgia, serif; font-size: 41px; font-weight: 500; }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.result-details { display: grid; grid-template-columns: minmax(0, 1fr) minmax(330px, .82fr); align-items: start; gap: 22px; padding-top: 42px; padding-bottom: 92px; }
.result-summary, .result-delivery { border: 1px solid var(--store-line); background: rgba(255,255,255,.72); }
.details-heading { padding: 21px 22px 18px; border-bottom: 1px solid var(--store-line); }
.details-heading p { margin: 0 0 7px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; }
.details-heading h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 27px; font-weight: 500; letter-spacing: 0; }
.result-items { padding: 5px 20px; }
.result-items article { min-height: 63px; display: grid; grid-template-columns: 28px minmax(0, 1fr) auto; align-items: center; gap: 12px; border-bottom: 1px solid rgba(36,29,33,.1); }
.result-items article:last-child { border-bottom: 0; }
.item-mark { width: 28px; height: 28px; display: grid; place-items: center; color: #fff; background: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; }
.result-items article div { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.result-items article strong { overflow: hidden; font-size: 11px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.result-items article small { color: var(--store-muted); font-size: 9px; }
.result-items article > b { font-size: 10px; font-weight: 600; }
.result-total { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 17px 20px; border-top: 1px solid var(--store-line); background: #f8efee; }
.result-total span { font-size: 11px; font-weight: 600; }
.result-total strong { color: var(--store-wine); font-family: 'Playfair Display', Georgia, serif; font-size: 24px; font-weight: 500; }
.delivery-address { display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 12px; padding: 18px 20px; border-bottom: 1px solid var(--store-line); }
.delivery-address > span { width: 36px; height: 36px; display: grid; place-items: center; color: #fff; background: #2d5d50; }
.delivery-address .iconify { width: 16px; height: 16px; }
.delivery-address strong { font-size: 11px; }
.delivery-address p { margin: 5px 0 0; color: var(--store-muted); font-size: 9px; line-height: 1.5; }
.next-steps { margin: 0; padding: 17px 20px 20px; list-style: none; }
.next-steps li { position: relative; min-height: 54px; display: grid; grid-template-columns: 31px minmax(0, 1fr); gap: 11px; }
.next-steps li:not(:last-child)::after { position: absolute; top: 31px; bottom: 0; left: 15px; width: 1px; background: var(--store-line); content: ''; }
.next-steps li > span { width: 31px; height: 31px; z-index: 1; display: grid; place-items: center; border: 1px solid var(--store-line); color: var(--store-muted); background: #fff; }
.next-steps li.active > span { border-color: #2d5d50; color: #fff; background: #2d5d50; }
.next-steps li .iconify { width: 14px; height: 14px; }
.next-steps li div { display: flex; flex-direction: column; gap: 3px; padding-top: 2px; }
.next-steps li strong { font-size: 10px; }
.next-steps li small { color: var(--store-muted); font-size: 8px; line-height: 1.4; }

@media (max-width: 820px) {
  .result-hero-inner { grid-template-columns: minmax(0, 1fr) 280px; gap: 38px; }
  .result-copy h1 { font-size: 48px; }
  .result-details { grid-template-columns: 1fr; }
}

@media (max-width: 650px) {
  .result-progress { overflow-x: auto; white-space: nowrap; }
  .result-hero-inner { display: block; }
  .result-copy { min-height: 400px; padding: 48px 0; box-sizing: border-box; }
  .result-copy h1 { font-size: 42px; }
  .result-visual { min-height: 210px; }
  .result-details { padding-top: 24px; padding-bottom: 64px; }
}

@media (max-width: 420px) {
  .result-copy h1 { font-size: 36px; }
  .result-actions { align-items: flex-start; flex-direction: column; }
  .primary-action { width: 100%; }
  .result-reference { width: 100%; box-sizing: border-box; }
  .result-items { padding-inline: 14px; }
}
</style>
