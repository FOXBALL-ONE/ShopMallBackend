<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type {
  CustomerAddress,
  CustomerCart,
  CustomerOrder,
  CustomerOrderCheckout,
  CustomerOrderStatus,
  CustomerPlaceOrderInput
} from '~/types/customer-account'
import {
  customerRequestDetails,
  customerRequestMessage,
  useCustomerAccountApi
} from '~/composables/useCustomerAccountApi'
import { formatAddressLine, formatCustomerMoney, parseProductSnapshot } from '~/utils/customer-display'

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Secure checkout | Pelissa',
  meta: [{ name: 'description', content: 'Confirm delivery and complete your Pelissa order.' }]
})

type CheckoutLine = {
  key: string
  name: string
  detail: string
  image: string | null
  quantity: number
  lineTotal: number | string | null
}

const SUCCESS_STATUSES = new Set<CustomerOrderStatus>(['PAID', 'SHIPPED', 'DELIVERED', 'COMPLETED'])
const CONSUMED_KEY_STATUSES = new Set([403, 409, 429])

const api = useCustomerAccountApi()
const session = useCustomerSession()
const checkoutSession = useOrderCheckoutSession()
const toast = useToast()

const cart = ref<CustomerCart | null>(null)
const addresses = ref<CustomerAddress[]>([])
const selectedAddressId = ref('')
const clientMessage = ref('')
const pendingOrder = ref<CustomerOrder | null>(null)
const isLoading = ref(true)
const isSubmitting = ref(false)
const requestError = ref('')
const lastCheckoutUrl = ref('')
const retryAvailableAt = ref<number | null>(null)
const now = ref(Date.now())
let clockTimer: ReturnType<typeof setInterval> | null = null

const currency = computed(() => pendingOrder.value?.currency || 'USD')
const cartItems = computed(() => cart.value?.items || [])
const hasUnavailableItems = computed(() => cartItems.value.some(item => !item.purchasable || item.stock <= 0 || item.quantity > item.stock))
const exceedsOrderLineLimit = computed(() => !pendingOrder.value && cartItems.value.length > 10)
const selectedAddress = computed(() => addresses.value.find(address => address.id === selectedAddressId.value) || null)
const paymentDeadline = computed(() => pendingOrder.value?.expires_at || null)
const isExpired = computed(() => Boolean(paymentDeadline.value && new Date(paymentDeadline.value).getTime() <= now.value))
const retrySeconds = computed(() => retryAvailableAt.value
  ? Math.max(0, Math.ceil((retryAvailableAt.value - now.value) / 1000))
  : 0)

const lines = computed<CheckoutLine[]>(() => {
  if (pendingOrder.value) {
    return pendingOrder.value.items.map(item => {
      const snapshot = parseProductSnapshot(item.product_snapshot)
      return {
        key: `order-${item.id}`,
        name: snapshot.name,
        detail: snapshot.color || 'Pelissa piece',
        image: snapshot.image,
        quantity: item.quantity,
        lineTotal: item.line_total
      }
    })
  }

  return cartItems.value.map(item => ({
    key: `cart-${item.id}`,
    name: item.name,
    detail: [item.color, item.size || item.top_size, item.bottom_size].filter(Boolean).join(' / ') || 'Pelissa piece',
    image: item.primary_image,
    quantity: item.quantity,
    lineTotal: item.line_total
  }))
})

const subtotal = computed(() => pendingOrder.value?.items_subtotal ?? cart.value?.subtotal ?? 0)
const shippingFee = computed(() => pendingOrder.value?.shipping_fee ?? 0)
const taxAmount = computed(() => pendingOrder.value?.tax_amount ?? 0)
const total = computed(() => pendingOrder.value?.total_amount ?? cart.value?.subtotal ?? 0)
const canPay = computed(() => Boolean(
  !isLoading.value
  && !isSubmitting.value
  && retrySeconds.value === 0
  && lines.value.length
  && !isExpired.value
  && (pendingOrder.value || (selectedAddress.value && !hasUnavailableItems.value && !exceedsOrderLineLimit.value))
))

const retryLabel = computed(() => {
  const minutes = Math.floor(retrySeconds.value / 60)
  const seconds = retrySeconds.value % 60
  return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`
})

const payButtonLabel = computed(() => {
  if (isSubmitting.value) return 'Preparing Stripe…'
  if (retrySeconds.value > 0) return `Try again in ${retryLabel.value}`
  return pendingOrder.value ? 'Continue with Stripe' : 'Place order & pay'
})

const deadlineLabel = computed(() => {
  if (!paymentDeadline.value) return ''
  const remainingSeconds = Math.max(0, Math.floor((new Date(paymentDeadline.value).getTime() - now.value) / 1000))
  const minutes = Math.floor(remainingSeconds / 60)
  const seconds = remainingSeconds % 60
  return `${minutes}:${seconds.toString().padStart(2, '0')}`
})

function addressLabel(address: CustomerAddress) {
  return address.label?.trim() || (address.is_default ? 'Default address' : 'Delivery address')
}

async function loadCheckout() {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  isLoading.value = true
  requestError.value = ''
  try {
    const [cartResult, addressResult] = await Promise.all([
      api.getCart(),
      api.getAddresses()
    ])
    cart.value = cartResult
    addresses.value = addressResult.list
    selectedAddressId.value = addressResult.list.find(address => address.is_default)?.id || addressResult.list[0]?.id || ''

    const storedContext = checkoutSession.read()
    if (storedContext?.orderNo) {
      const order = await api.getOrder(storedContext.orderNo)
      if (order.status === 'PENDING_PAYMENT') {
        pendingOrder.value = order
      } else if (SUCCESS_STATUSES.has(order.status)) {
        await navigateTo(`/orders/${encodeURIComponent(order.order_no)}/payment/result`)
      } else {
        checkoutSession.clear(order.order_no)
      }
    }
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not prepare checkout. Please try again.')
  } finally {
    isLoading.value = false
  }
}

async function openStripeCheckout() {
  if (!canPay.value || import.meta.server) return

  const paymentWindow = window.open('/checkout/redirecting', '_blank')
  if (!paymentWindow) {
    requestError.value = 'Your browser blocked the payment tab. Allow pop-ups for Pelissa and try again.'
    toast.add({ title: 'Payment tab blocked', description: requestError.value, color: 'warning' })
    return
  }
  try {
    paymentWindow.opener = null
  } catch {
    // The tab can still be redirected even when the browser prevents changing opener.
  }

  isSubmitting.value = true
  requestError.value = ''
  lastCheckoutUrl.value = ''

  try {
    let context = checkoutSession.read()
    let order = pendingOrder.value

    if (!order) {
      if (!selectedAddress.value) throw new Error('Select a delivery address before continuing.')
      const orderInput: CustomerPlaceOrderInput = {
        variant_ids: cartItems.value.map(item => item.variant_id),
        quantities: cartItems.value.map(item => item.quantity),
        address_id: selectedAddress.value.id,
        client_message: clientMessage.value.trim() || undefined
      }
      let ambiguousRetries = 0
      let invalidKeyRetries = 0

      while (!order) {
        if (!context?.idempotencyKey || context.orderNo) {
          const issued = await api.issueOrderIdempotencyKey()
          context = {
            customerId: session.userId.value!,
            idempotencyKey: issued.idempotency_key,
            orderNo: null,
            startedAt: new Date().toISOString()
          }
          checkoutSession.write(context)
        }

        const orderContext = context
        try {
          order = await api.placeOrder(orderInput, orderContext.idempotencyKey)
        } catch (error: unknown) {
          const failure = customerRequestDetails(error)
          if ((failure.transportFailure || failure.status >= 500) && ambiguousRetries < 1) {
            ambiguousRetries += 1
            continue
          }

          if (CONSUMED_KEY_STATUSES.has(failure.status)) {
            checkoutSession.clear()
            context = null
          }
          if (failure.status === 403 && failure.message.includes('幂等键') && invalidKeyRetries < 1) {
            ambiguousRetries = 0
            invalidKeyRetries += 1
            continue
          }
          throw error
        }
      }

      if (!context) throw new Error('The secure checkout session is no longer available.')
      context = { ...context, orderNo: order.order_no }
      checkoutSession.write(context)
      pendingOrder.value = order

      if (SUCCESS_STATUSES.has(order.status)) {
        if (!paymentWindow.closed) paymentWindow.close()
        checkoutSession.clear(order.order_no)
        await navigateTo(`/orders/${encodeURIComponent(order.order_no)}/payment/result`)
        return
      }
      if (order.status !== 'PENDING_PAYMENT') {
        checkoutSession.clear(order.order_no)
        throw new Error(`Order ${order.order_no} cannot be paid in its current state.`)
      }
    }

    let checkout: CustomerOrderCheckout | null = null
    let checkoutError: unknown = new Error('Stripe checkout is temporarily unavailable.')
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        checkout = await api.openOrderCheckout(order.order_no)
        break
      } catch (error: unknown) {
        checkoutError = error
        const failure = customerRequestDetails(error)
        if (attempt === 0 && (failure.transportFailure || failure.status >= 500)) continue
        break
      }
    }

    if (!checkout) {
      try {
        const payment = await api.getOrderPayment(order.order_no)
        if (SUCCESS_STATUSES.has(payment.status)) {
          if (!paymentWindow.closed) paymentWindow.close()
          checkoutSession.clear(order.order_no)
          await navigateTo(`/orders/${encodeURIComponent(order.order_no)}/payment/result`)
          return
        }
        if (payment.status === 'CANCELLED' || payment.status === 'DELETED') {
          checkoutSession.clear(order.order_no)
        }
      } catch {
        // Preserve the checkout error when the status fallback is also unavailable.
      }
      throw checkoutError
    }
    if (checkout.order_no !== order.order_no || checkout.status !== 'PENDING_PAYMENT') {
      throw new Error('The payment provider returned an unexpected order state.')
    }
    const target = new URL(checkout.checkout_url)
    if (target.protocol !== 'https:') throw new Error('The payment provider returned an invalid checkout link.')

    lastCheckoutUrl.value = target.toString()
    paymentWindow.location.replace(target.toString())
    toast.add({
      title: 'Stripe checkout opened',
      description: `Order ${order.order_no} is reserved while payment is completed.`,
      color: 'success'
    })
  } catch (error: unknown) {
    if (!paymentWindow.closed) paymentWindow.close()
    const failure = customerRequestDetails(error, 'We could not open Stripe checkout. Your order has not been charged.')
    const retryDelay = failure.retryAfterSeconds ?? 0
    const hasRetryDelay = retryDelay > 0
    if (hasRetryDelay) {
      now.value = Date.now()
      retryAvailableAt.value = now.value + retryDelay * 1000
    }
    requestError.value = hasRetryDelay ? '' : failure.message
    toast.add({
      title: hasRetryDelay ? 'Checkout temporarily unavailable' : 'Checkout not opened',
      description: failure.message,
      color: hasRetryDelay ? 'warning' : 'error'
    })
  } finally {
    isSubmitting.value = false
  }
}

onMounted(() => {
  clockTimer = setInterval(() => { now.value = Date.now() }, 1000)
  void loadCheckout()
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <main class="store-page checkout-page">
    <StoreHeader />

    <div class="checkout-progress-wrap">
      <nav class="store-container checkout-progress" aria-label="Checkout progress">
        <NuxtLink to="/cart"><span>01</span> Cart</NuxtLink>
        <UIcon name="i-lucide-chevron-right" />
        <strong><span>02</span> Delivery</strong>
        <UIcon name="i-lucide-chevron-right" />
        <span><b>03</b> Payment</span>
      </nav>
    </div>

    <header class="store-container checkout-heading">
      <div>
        <p class="store-eyebrow">SECURE CHECKOUT / 02</p>
        <h1>Complete your order.</h1>
      </div>
      <div class="checkout-security">
        <span><UIcon name="i-lucide-shield-check" /></span>
        <div><strong>Protected checkout</strong><small>Payment secured by Stripe</small></div>
      </div>
    </header>

    <div v-if="isLoading" class="store-container checkout-loading" aria-live="polite">
      <div /><div /><div />
    </div>

    <div v-else class="store-container checkout-layout">
      <section class="checkout-main">
        <div v-if="requestError" class="checkout-notice error" role="alert">
          <UIcon name="i-lucide-circle-alert" />
          <span>{{ requestError }}</span>
          <button type="button" aria-label="Dismiss message" @click="requestError = ''"><UIcon name="i-lucide-x" /></button>
        </div>

        <div v-if="retrySeconds > 0" class="checkout-notice waiting" role="status">
          <UIcon name="i-lucide-hourglass" />
          <span><strong>Order creation is temporarily limited.</strong> Try again in {{ retryLabel }}.</span>
        </div>

        <div v-if="pendingOrder" class="checkout-notice reserved" role="status">
          <UIcon name="i-lucide-timer" />
          <span><strong>Order {{ pendingOrder.order_no }} is reserved.</strong> Complete payment within {{ deadlineLabel }}.</span>
        </div>

        <section class="checkout-section">
          <div class="section-heading">
            <span class="section-icon"><UIcon name="i-lucide-map-pin" /></span>
            <div><p>01 / DELIVERY</p><h2>Where it is going</h2></div>
            <NuxtLink v-if="!pendingOrder" to="/account/profile#addresses"><UIcon name="i-lucide-plus" /> Manage addresses</NuxtLink>
          </div>

          <div v-if="pendingOrder" class="fixed-address">
            <span class="address-check"><UIcon name="i-lucide-check" /></span>
            <div>
              <strong>{{ pendingOrder.shipping_address.name }}</strong>
              <p>{{ formatAddressLine({
                address_line1: pendingOrder.shipping_address.address1,
                address_line2: pendingOrder.shipping_address.address2,
                city: pendingOrder.shipping_address.city,
                district: pendingOrder.shipping_address.district,
                state_or_province: pendingOrder.shipping_address.state_or_province,
                postal_code: pendingOrder.shipping_address.postal_code,
                country: pendingOrder.shipping_address.country
              }) }}</p>
              <small>{{ pendingOrder.shipping_address.phone }}</small>
            </div>
            <span class="locked-label"><UIcon name="i-lucide-lock-keyhole" /> Confirmed</span>
          </div>

          <div v-else-if="addresses.length" class="address-list">
            <label
              v-for="address in addresses"
              :key="address.id"
              class="address-option"
              :class="{ selected: selectedAddressId === address.id }"
            >
              <input v-model="selectedAddressId" type="radio" name="delivery-address" :value="address.id">
              <span class="radio-mark"><UIcon name="i-lucide-check" /></span>
              <span class="address-copy">
                <span class="address-title"><strong>{{ addressLabel(address) }}</strong><small v-if="address.is_default">Default</small></span>
                <b>{{ address.name }}</b>
                <span>{{ formatAddressLine(address) }}</span>
                <span>{{ address.phone }}</span>
              </span>
            </label>
          </div>

          <div v-else class="address-empty">
            <span><UIcon name="i-lucide-map-pinned" /></span>
            <div><strong>Add a delivery address</strong><p>A saved recipient and street address are required before checkout.</p></div>
            <NuxtLink class="outline-action" to="/account/profile#addresses"><UIcon name="i-lucide-plus" /> Add address</NuxtLink>
          </div>
        </section>

        <section class="checkout-section">
          <div class="section-heading">
            <span class="section-icon"><UIcon name="i-lucide-message-square-text" /></span>
            <div><p>02 / ORDER NOTE</p><h2>A note for delivery</h2></div>
          </div>
          <label class="message-field">
            <span>Optional message <small>{{ clientMessage.length }} / 500</small></span>
            <textarea
              v-model="clientMessage"
              maxlength="500"
              rows="4"
              :disabled="Boolean(pendingOrder)"
              placeholder="Delivery details or a note for your order"
            />
          </label>
        </section>
      </section>

      <aside class="order-summary">
        <div class="summary-heading">
          <div><p>03 / YOUR ORDER</p><h2>Order summary</h2></div>
          <NuxtLink v-if="!pendingOrder" to="/cart">Edit cart</NuxtLink>
        </div>

        <div v-if="lines.length" class="checkout-lines">
          <article v-for="line in lines" :key="line.key" class="checkout-line">
            <div class="line-image">
              <img v-if="line.image" :src="line.image" :alt="line.name">
              <span v-else>P°</span>
              <b>{{ line.quantity }}</b>
            </div>
            <div class="line-copy"><strong>{{ line.name }}</strong><span>{{ line.detail }}</span></div>
            <strong class="line-price">{{ formatCustomerMoney(line.lineTotal, currency) }}</strong>
          </article>
        </div>
        <div v-else class="summary-empty">
          <UIcon name="i-lucide-shopping-cart" />
          <strong>Your cart is empty</strong>
          <NuxtLink to="/collections/shop">Return to the shop</NuxtLink>
        </div>

        <div v-if="lines.length" class="summary-totals">
          <div><span>Subtotal</span><strong>{{ formatCustomerMoney(subtotal, currency) }}</strong></div>
          <div><span>Delivery</span><strong>{{ Number(shippingFee) ? formatCustomerMoney(shippingFee, currency) : 'Complimentary' }}</strong></div>
          <div v-if="Number(taxAmount)"><span>Tax</span><strong>{{ formatCustomerMoney(taxAmount, currency) }}</strong></div>
          <div class="total-line"><span>Total</span><strong>{{ formatCustomerMoney(total, currency) }}</strong></div>
        </div>

        <div v-if="hasUnavailableItems && !pendingOrder" class="summary-warning">
          <UIcon name="i-lucide-circle-alert" /> Remove unavailable pieces before checkout.
        </div>
        <div v-if="exceedsOrderLineLimit" class="summary-warning">
          <UIcon name="i-lucide-list-x" /> An order can include at most 10 different products.
        </div>
        <div v-if="isExpired" class="summary-warning">
          <UIcon name="i-lucide-clock-alert" /> This order's payment window has expired.
        </div>

        <button class="pay-button" type="button" :disabled="!canPay" @click="openStripeCheckout">
          <UIcon :name="isSubmitting ? 'i-lucide-loader-circle' : 'i-lucide-lock-keyhole'" :class="{ spinning: isSubmitting }" />
          {{ payButtonLabel }}
          <UIcon v-if="!isSubmitting" name="i-lucide-arrow-up-right" />
        </button>
        <a v-if="lastCheckoutUrl" class="payment-fallback" :href="lastCheckoutUrl" target="_blank" rel="noopener noreferrer">
          Open Stripe checkout again <UIcon name="i-lucide-external-link" />
        </a>

        <div class="payment-trust">
          <span><UIcon name="i-lucide-shield-check" /> Secure payment</span>
          <span><UIcon name="i-lucide-credit-card" /> Stripe checkout</span>
        </div>
      </aside>
    </div>

    <StoreFooter />
  </main>
</template>

<style scoped>
.checkout-page { background: #fbf7f5; }
.checkout-progress-wrap { border-bottom: 1px solid var(--store-line); background: #fff; }
.checkout-progress { min-height: 49px; display: flex; align-items: center; gap: 13px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: 0; text-transform: uppercase; }
.checkout-progress a, .checkout-progress strong, .checkout-progress > span { display: inline-flex; align-items: center; gap: 7px; color: inherit; text-decoration: none; }
.checkout-progress a:hover, .checkout-progress strong { color: var(--store-ink); }
.checkout-progress span span, .checkout-progress strong span, .checkout-progress b { color: var(--store-wine); font-size: 8px; font-weight: 500; }
.checkout-progress > .iconify { width: 12px; height: 12px; opacity: .45; }

.checkout-heading { min-height: 176px; display: flex; align-items: center; justify-content: space-between; gap: 32px; }
.checkout-heading h1 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 52px; font-weight: 500; letter-spacing: 0; line-height: 1; }
.checkout-security { display: flex; align-items: center; gap: 12px; padding-left: 28px; border-left: 1px solid var(--store-line); }
.checkout-security > span { width: 40px; height: 40px; display: grid; place-items: center; color: #fff; background: #2d5d50; }
.checkout-security .iconify { width: 19px; height: 19px; }
.checkout-security div { display: flex; flex-direction: column; gap: 3px; }
.checkout-security strong { font-size: 12px; font-weight: 600; }
.checkout-security small { color: var(--store-muted); font-size: 10px; }

.checkout-loading { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(320px, .75fr); gap: 22px; padding-bottom: 100px; }
.checkout-loading div { position: relative; min-height: 480px; overflow: hidden; border: 1px solid var(--store-line); background: rgba(255,255,255,.68); }
.checkout-loading div:nth-child(2), .checkout-loading div:nth-child(3) { display: none; }
.checkout-loading div:last-child { min-height: 400px; display: block; }
.checkout-loading div::after { position: absolute; inset: 0; background: linear-gradient(90deg, transparent, rgba(255,255,255,.78), transparent); content: ''; animation: shimmer 1.4s infinite; }
@keyframes shimmer { from { transform: translateX(-100%); } to { transform: translateX(100%); } }

.checkout-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(330px, .75fr); align-items: start; gap: 22px; padding-bottom: 100px; }
.checkout-main { min-width: 0; display: flex; flex-direction: column; gap: 18px; }
.checkout-notice { min-height: 48px; display: flex; align-items: center; gap: 10px; padding: 10px 14px; border: 1px solid; box-sizing: border-box; font-size: 11px; line-height: 1.45; }
.checkout-notice > .iconify { width: 16px; height: 16px; flex: 0 0 auto; }
.checkout-notice span { flex: 1; }
.checkout-notice button { width: 28px; height: 28px; display: grid; place-items: center; padding: 0; border: 0; color: inherit; background: transparent; cursor: pointer; }
.checkout-notice button .iconify { width: 14px; height: 14px; }
.checkout-notice.error { border-color: rgba(157,54,67,.3); color: #8d3140; background: #fff2f2; }
.checkout-notice.waiting { border-color: rgba(172,119,30,.32); color: #765116; background: #fff8e9; }
.checkout-notice.reserved { border-color: rgba(45,93,80,.3); color: #285448; background: #eef7f3; }

.checkout-section, .order-summary { border: 1px solid var(--store-line); background: rgba(255,255,255,.72); }
.section-heading, .summary-heading { min-height: 86px; display: flex; align-items: center; gap: 14px; padding: 17px 20px; border-bottom: 1px solid var(--store-line); box-sizing: border-box; }
.section-icon { width: 38px; height: 38px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid var(--store-line); color: var(--store-wine); background: var(--store-linen); }
.section-icon .iconify { width: 17px; height: 17px; }
.section-heading div, .summary-heading div { flex: 1; }
.section-heading p, .summary-heading p { margin: 0 0 5px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; }
.section-heading h2, .summary-heading h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 27px; font-weight: 500; letter-spacing: 0; line-height: 1; }
.section-heading > a, .summary-heading > a { display: inline-flex; align-items: center; gap: 6px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; text-decoration: none; text-transform: uppercase; }
.section-heading > a:hover, .summary-heading > a:hover { color: var(--store-wine); }
.section-heading > a .iconify { width: 13px; height: 13px; }

.address-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; padding: 18px 20px 21px; }
.address-option { position: relative; min-height: 142px; display: flex; gap: 12px; padding: 15px; border: 1px solid rgba(36,29,33,.16); box-sizing: border-box; cursor: pointer; transition: border-color .2s ease, background .2s ease; }
.address-option:hover, .address-option.selected { border-color: var(--store-wine); background: #fff8f8; }
.address-option input { position: absolute; opacity: 0; pointer-events: none; }
.radio-mark { width: 19px; height: 19px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid var(--store-line); color: transparent; background: #fff; }
.radio-mark .iconify { width: 12px; height: 12px; }
.address-option.selected .radio-mark { border-color: var(--store-wine); color: #fff; background: var(--store-wine); }
.address-copy { min-width: 0; display: flex; flex-direction: column; gap: 5px; color: var(--store-muted); font-size: 10px; line-height: 1.4; }
.address-title { display: flex; align-items: center; justify-content: space-between; gap: 9px; margin-bottom: 3px; }
.address-title strong { color: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 8px; font-weight: 500; text-transform: uppercase; }
.address-title small { padding: 2px 5px; color: #285448; background: #e5f2ed; font-size: 7px; text-transform: uppercase; }
.address-copy b { color: var(--store-ink); font-size: 11px; font-weight: 600; }
.fixed-address { min-height: 125px; display: grid; grid-template-columns: 31px minmax(0, 1fr) auto; align-items: start; gap: 13px; padding: 21px 20px; box-sizing: border-box; }
.address-check { width: 29px; height: 29px; display: grid; place-items: center; color: #fff; background: #2d5d50; }
.address-check .iconify { width: 15px; height: 15px; }
.fixed-address > div { display: flex; flex-direction: column; gap: 5px; }
.fixed-address strong { font-size: 12px; }
.fixed-address p { max-width: 520px; margin: 0; color: var(--store-muted); font-size: 10px; line-height: 1.55; }
.fixed-address small { color: var(--store-muted); font-size: 9px; }
.locked-label { display: inline-flex; align-items: center; gap: 5px; color: #285448; font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.locked-label .iconify { width: 12px; height: 12px; }
.address-empty { min-height: 125px; display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 13px; padding: 20px; }
.address-empty > span { width: 42px; height: 42px; display: grid; place-items: center; color: var(--store-wine); background: var(--store-linen); }
.address-empty > span .iconify { width: 19px; height: 19px; }
.address-empty strong { font-size: 12px; }
.address-empty p { margin: 5px 0 0; color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.outline-action { min-height: 36px; display: inline-flex; align-items: center; gap: 6px; padding: 0 11px; border: 1px solid var(--store-ink); color: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 8px; text-decoration: none; text-transform: uppercase; }
.outline-action:hover { color: #fff; background: var(--store-ink); }
.outline-action .iconify { width: 12px; height: 12px; }

.message-field { display: flex; flex-direction: column; gap: 8px; padding: 18px 20px 21px; color: var(--store-ink); font-size: 10px; font-weight: 600; }
.message-field > span { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.message-field small { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; font-weight: 400; }
.message-field textarea { width: 100%; min-height: 88px; padding: 12px; border: 1px solid rgba(36,29,33,.18); border-radius: 0; box-sizing: border-box; outline: 0; color: var(--store-ink); background: #fff; font: inherit; font-weight: 400; line-height: 1.5; resize: vertical; }
.message-field textarea:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.09); }
.message-field textarea:disabled { color: var(--store-muted); background: #f2eeee; cursor: not-allowed; }

.order-summary { position: sticky; top: 18px; min-width: 0; }
.summary-heading { justify-content: space-between; }
.checkout-lines { max-height: 370px; overflow-y: auto; }
.checkout-line { min-height: 83px; display: grid; grid-template-columns: 58px minmax(0, 1fr) auto; align-items: center; gap: 11px; padding: 11px 16px; border-bottom: 1px solid rgba(36,29,33,.1); box-sizing: border-box; }
.line-image { position: relative; width: 58px; height: 66px; display: grid; place-items: center; color: var(--store-wine); background: var(--store-linen); font-family: 'Playfair Display', Georgia, serif; }
.line-image img { width: 100%; height: 100%; object-fit: cover; }
.line-image > span { font-size: 20px; }
.line-image b { position: absolute; top: -5px; right: -5px; width: 19px; height: 19px; display: grid; place-items: center; border: 2px solid #fff; border-radius: 50%; color: #fff; background: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 7px; font-weight: 500; }
.line-copy { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.line-copy strong { overflow: hidden; font-family: 'Playfair Display', Georgia, serif; font-size: 16px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }
.line-copy span { overflow: hidden; color: var(--store-muted); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.line-price { font-size: 10px; font-weight: 600; white-space: nowrap; }
.summary-empty { min-height: 180px; display: flex; align-items: center; justify-content: center; flex-direction: column; gap: 9px; color: var(--store-muted); }
.summary-empty > .iconify { width: 24px; height: 24px; color: var(--store-wine); }
.summary-empty strong { color: var(--store-ink); font-size: 12px; }
.summary-empty a { color: var(--store-wine); font-size: 10px; }
.summary-totals { padding: 16px 17px 7px; }
.summary-totals > div { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 7px 0; color: var(--store-muted); font-size: 10px; }
.summary-totals > div strong { color: var(--store-ink); font-size: 10px; font-weight: 600; }
.summary-totals .total-line { margin-top: 8px; padding: 15px 0 11px; border-top: 1px solid var(--store-line); color: var(--store-ink); font-size: 12px; }
.summary-totals .total-line strong { color: var(--store-wine); font-family: 'Playfair Display', Georgia, serif; font-size: 25px; font-weight: 500; }
.summary-warning { display: flex; align-items: flex-start; gap: 7px; margin: 6px 17px 12px; padding: 9px 10px; color: #8d3140; background: #fff1f1; font-size: 9px; line-height: 1.45; }
.summary-warning .iconify { width: 13px; height: 13px; flex: 0 0 auto; }
.pay-button { width: calc(100% - 34px); min-height: 51px; display: flex; align-items: center; justify-content: center; gap: 9px; margin: 10px 17px 0; padding: 0 14px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; font-weight: 500; letter-spacing: 0; text-transform: uppercase; transition: color .2s ease, background .2s ease; }
.pay-button:hover:not(:disabled) { color: var(--store-ink); background: #fff; }
.pay-button:disabled { cursor: not-allowed; opacity: .42; }
.pay-button .iconify { width: 14px; height: 14px; }
.pay-button .iconify:last-child { margin-left: auto; }
.spinning { animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.payment-fallback { display: flex; align-items: center; justify-content: center; gap: 6px; margin: 12px 17px 0; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.payment-fallback .iconify { width: 12px; height: 12px; }
.payment-trust { display: flex; align-items: center; justify-content: center; gap: 18px; padding: 16px 10px 18px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 7px; text-transform: uppercase; }
.payment-trust span { display: inline-flex; align-items: center; gap: 5px; }
.payment-trust .iconify { width: 12px; height: 12px; color: #2d5d50; }

@media (max-width: 940px) {
  .checkout-heading h1 { font-size: 44px; }
  .checkout-layout, .checkout-loading { grid-template-columns: minmax(0, 1.2fr) minmax(300px, .8fr); }
  .address-list { grid-template-columns: 1fr; }
}

@media (max-width: 760px) {
  .checkout-progress { overflow-x: auto; white-space: nowrap; }
  .checkout-heading { min-height: 144px; align-items: flex-start; flex-direction: column; justify-content: center; gap: 20px; padding-block: 25px; box-sizing: border-box; }
  .checkout-heading h1 { font-size: 40px; }
  .checkout-security { padding: 0; border: 0; }
  .checkout-layout, .checkout-loading { display: block; padding-bottom: 68px; }
  .checkout-loading div:first-child { min-height: 420px; }
  .checkout-loading div:last-child { display: none; }
  .order-summary { position: static; margin-top: 18px; }
  .fixed-address { grid-template-columns: 31px minmax(0, 1fr); }
  .locked-label { grid-column: 2; }
}

@media (max-width: 480px) {
  .checkout-heading h1 { font-size: 35px; }
  .checkout-security > span { width: 36px; height: 36px; }
  .section-heading, .summary-heading { min-height: 78px; padding-inline: 14px; }
  .section-icon { width: 34px; height: 34px; }
  .section-heading h2, .summary-heading h2 { font-size: 23px; }
  .section-heading > a { width: 30px; height: 30px; justify-content: center; overflow: hidden; border: 1px solid var(--store-line); font-size: 0; }
  .address-list, .message-field { padding-inline: 14px; }
  .address-empty { grid-template-columns: 38px minmax(0, 1fr); padding-inline: 14px; }
  .address-empty .outline-action { grid-column: 2; width: fit-content; }
  .checkout-line { grid-template-columns: 54px minmax(0, 1fr) auto; padding-inline: 13px; }
  .line-image { width: 54px; height: 62px; }
  .payment-trust { gap: 11px; }
}
</style>
