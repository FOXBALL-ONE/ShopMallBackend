<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import type { CustomerCartItem, CustomerProfile } from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import { formatCustomerMoney } from '~/utils/customer-display'

definePageMeta({ middleware: ['customer-auth'] })

useHead({
  title: 'Shopping cart | Pelissa',
  meta: [{ name: 'description', content: 'Review the pieces in your Pelissa shopping cart.' }]
})

const api = useCustomerAccountApi()
const session = useCustomerSession()
const customerCart = useCustomerCart()
const toast = useToast()

const profile = ref<CustomerProfile | null>(null)
const cart = customerCart.cart
const isLoading = ref(true)
const isRefreshing = ref(false)
const busyItemId = ref<number | null>(null)
const isClearing = ref(false)
const requestError = ref('')

const currency = 'USD'
const cartItems = computed(() => cart.value?.items || [])
const unavailableItems = computed(() => cartItems.value.filter(item => !isPurchasable(item)))
const cartLabel = computed(() => `${cart.value?.total_quantity || 0} ${cart.value?.total_quantity === 1 ? 'piece' : 'pieces'}`)
const subtotalLabel = computed(() => formatCustomerMoney(cart.value?.subtotal, currency))

function isPurchasable(item: CustomerCartItem) {
  return Boolean(item.purchasable && item.stock > 0 && item.quantity <= item.stock)
}

function itemVariant(item: CustomerCartItem) {
  return [item.color, item.size || item.top_size, item.bottom_size].filter(Boolean).join(' · ')
}

function maxQuantity(item: CustomerCartItem) {
  return Math.max(1, Math.min(Number(item.stock || 1), 99))
}

async function updateQuantity(item: CustomerCartItem, quantity: number) {
  if (busyItemId.value === item.id || !isPurchasable(item)) return
  const nextQuantity = Math.max(1, Math.min(quantity, maxQuantity(item)))
  if (nextQuantity === item.quantity) return

  busyItemId.value = item.id
  requestError.value = ''
  try {
    await customerCart.updateItem(item.id, nextQuantity)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not update this item in your cart.')
    toast.add({ title: 'Cart not updated', description: requestError.value, color: 'error' })
  } finally {
    busyItemId.value = null
  }
}

async function removeItem(item: CustomerCartItem) {
  if (busyItemId.value) return
  busyItemId.value = item.id
  requestError.value = ''
  try {
    await customerCart.removeItem(item.id)
    toast.add({ title: 'Removed from cart', description: `${item.name} is no longer in your shopping cart.`, color: 'success' })
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not remove this piece.')
    toast.add({ title: 'Piece not removed', description: requestError.value, color: 'error' })
  } finally {
    busyItemId.value = null
  }
}

async function clearCart() {
  if (isClearing.value || !cartItems.value.length) return
  if (import.meta.client && !window.confirm('Clear every item from your shopping cart?')) return

  isClearing.value = true
  requestError.value = ''
  try {
    await customerCart.clear()
    toast.add({ title: 'Cart cleared', description: 'Your shopping cart is now empty.', color: 'success' })
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not clear your cart.')
    toast.add({ title: 'Cart not cleared', description: requestError.value, color: 'error' })
  } finally {
    isClearing.value = false
  }
}

async function loadCart(showLoading = true) {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  if (showLoading) isLoading.value = true
  else isRefreshing.value = true
  requestError.value = ''
  try {
    const [profileResult] = await Promise.all([
      profile.value ? Promise.resolve(profile.value) : api.getProfile(userId),
      customerCart.refresh(true)
    ])
    profile.value = profileResult
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not load your shopping cart.')
  } finally {
    isLoading.value = false
    isRefreshing.value = false
  }
}

onMounted(() => {
  void loadCart()
})
</script>
<template>
  <CustomerAccountShell
    eyebrow="THE CART · 04"
    title="The pieces you kept."
    intro="A softer pause before checkout. Revisit your edit, adjust the details, and make room for what feels right."
    :profile="profile"
  >
    <div v-if="isLoading" class="cart-loading" aria-live="polite">
      <div class="cart-loading-main" />
      <div class="cart-loading-side" />
    </div>

    <template v-else>
      <div v-if="requestError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" />
        <span>{{ requestError }}</span>
        <button type="button" @click="loadCart(false)">Refresh</button>
      </div>

      <section v-if="cartItems.length" class="cart-toolbar">
        <div>
          <p class="store-eyebrow">YOUR CURRENT EDIT</p>
          <h2>{{ cartLabel }}</h2>
        </div>
        <div class="cart-toolbar-actions">
          <button class="refresh-button" type="button" :disabled="isRefreshing" @click="loadCart(false)">
            <UIcon :name="isRefreshing ? 'i-lucide-loader-circle' : 'i-lucide-refresh-cw'" :class="{ 'is-spinning': isRefreshing }" />
            {{ isRefreshing ? 'Refreshing…' : 'Refresh' }}
          </button>
          <button class="text-button text-button-danger" type="button" :disabled="isClearing" @click="clearCart">
            <UIcon :name="isClearing ? 'i-lucide-loader-circle' : 'i-lucide-trash-2'" :class="{ 'is-spinning': isClearing }" />
            {{ isClearing ? 'Clearing…' : 'Clear cart' }}
          </button>
        </div>
      </section>

      <div v-if="cartItems.length" class="cart-layout">
        <section class="cart-items-panel">
          <div class="cart-panel-heading">
            <div>
              <p class="panel-kicker">01 / YOUR PIECES</p>
              <h2>Held for you</h2>
            </div>
            <span>{{ cartItems.length }} {{ cartItems.length === 1 ? 'line item' : 'line items' }}</span>
          </div>

          <div class="cart-item-list">
            <article v-for="item in cartItems" :key="item.id" class="cart-item" :class="{ 'is-unavailable': !isPurchasable(item) }">
              <NuxtLink class="cart-item-image" :to="`/product/${item.product_id}`">
                <img v-if="item.primary_image" :src="item.primary_image" :alt="item.name">
                <span v-else>P°</span>
              </NuxtLink>
              <div class="cart-item-copy">
                <span class="cart-item-type">{{ item.product_type || 'PELISSA PIECE' }}</span>
                <NuxtLink :to="`/product/${item.product_id}`">
                  <h3>{{ item.name }}</h3>
                </NuxtLink>
                <p>{{ itemVariant(item) || 'Made for your edit' }}</p>
                <span v-if="!isPurchasable(item)" class="unavailable-note"><UIcon name="i-lucide-circle-alert" /> {{ item.stock <= 0 ? 'Out of stock' : 'No longer available in this selection' }}</span>
                <span v-else-if="item.stock <= 3" class="stock-note">Only {{ item.stock }} left</span>
              </div>
              <div class="cart-item-price">
                <span>{{ formatCustomerMoney(item.unit_price, currency) }} each</span>
                <strong>{{ formatCustomerMoney(item.line_total, currency) }}</strong>
              </div>
              <div class="quantity-control" :class="{ disabled: !isPurchasable(item) || busyItemId === item.id }">
                <button type="button" :disabled="!isPurchasable(item) || busyItemId === item.id || item.quantity <= 1" aria-label="Decrease quantity" @click="updateQuantity(item, item.quantity - 1)"><UIcon name="i-lucide-minus" /></button>
                <span>{{ busyItemId === item.id ? '…' : item.quantity }}</span>
                <button type="button" :disabled="!isPurchasable(item) || busyItemId === item.id || item.quantity >= maxQuantity(item)" aria-label="Increase quantity" @click="updateQuantity(item, item.quantity + 1)"><UIcon name="i-lucide-plus" /></button>
              </div>
              <button class="cart-remove" type="button" :disabled="busyItemId === item.id" aria-label="Remove item" @click="removeItem(item)">
                <UIcon :name="busyItemId === item.id ? 'i-lucide-loader-circle' : 'i-lucide-x'" :class="{ 'is-spinning': busyItemId === item.id }" />
              </button>
            </article>
          </div>

          <div v-if="unavailableItems.length" class="cart-warning">
            <UIcon name="i-lucide-info" />
            <span>{{ unavailableItems.length }} {{ unavailableItems.length === 1 ? 'piece is' : 'pieces are' }} currently unavailable. Remove {{ unavailableItems.length === 1 ? 'it' : 'them' }} before placing an order.</span>
          </div>

          <NuxtLink class="continue-shopping" to="/collections/shop"><UIcon name="i-lucide-arrow-left" /> Continue shopping</NuxtLink>
        </section>

        <aside class="cart-summary-panel">
          <div class="summary-art" aria-hidden="true">
            <div class="summary-art-image" />
            <span>THE FINAL TOUCH</span>
          </div>
          <div class="cart-summary-content">
            <p class="panel-kicker">02 / SUMMARY</p>
            <h2>Almost yours.</h2>
            <div class="summary-lines">
              <div><span>Pieces</span><strong>{{ cart?.total_quantity || 0 }}</strong></div>
              <div><span>Subtotal</span><strong>{{ subtotalLabel }}</strong></div>
              <div><span>Shipping</span><strong>Calculated next</strong></div>
            </div>
            <div class="summary-total"><span>Estimated total</span><strong>{{ subtotalLabel }}</strong></div>
            <NuxtLink
              class="store-button summary-button"
              :class="{ disabled: unavailableItems.length > 0 }"
              :to="unavailableItems.length ? '/cart' : '/checkout'"
              :aria-disabled="unavailableItems.length > 0"
            >
              <UIcon name="i-lucide-lock-keyhole" /> Secure checkout
            </NuxtLink>
            <p class="summary-note"><UIcon name="i-lucide-sparkles" /> Complimentary shipping on orders over $79.</p>
          </div>
        </aside>
      </div>

      <section v-else class="cart-empty">
        <div class="cart-empty-art" aria-hidden="true"><span>P°</span></div>
        <div>
          <p class="panel-kicker">A LITTLE SPACE FOR SOMETHING NEW</p>
          <h2>Your cart is beautifully empty.</h2>
          <p>When a piece catches your eye, save it here. Your edit will be waiting when you are ready.</p>
          <div class="cart-empty-actions">
            <NuxtLink class="store-button" to="/collections/shop"><UIcon name="i-lucide-arrow-up-right" /> Start shopping</NuxtLink>
            <NuxtLink class="outline-button" to="/account/profile#addresses"><UIcon name="i-lucide-map-pin" /> Add delivery details</NuxtLink>
          </div>
        </div>
      </section>
    </template>
  </CustomerAccountShell>
</template>
<style scoped>
.cart-loading { display: grid; grid-template-columns: minmax(0,1.4fr) minmax(260px,.7fr); gap: 18px; }
.cart-loading-main,
.cart-loading-side { position: relative; min-height: 500px; overflow: hidden; border: 1px solid rgba(36,29,33,.08); background: rgba(255,255,255,.52); }
.cart-loading-side { min-height: 340px; }
.cart-loading-main::after,
.cart-loading-side::after { position: absolute; inset: 0; background: linear-gradient(90deg, transparent, rgba(255,255,255,.65), transparent); content: ''; animation: cart-shimmer 1.5s infinite; }
@keyframes cart-shimmer { from { transform: translateX(-100%); } to { transform: translateX(100%); } }

.account-notice { display: flex; align-items: center; gap: 10px; margin-bottom: 19px; padding: 12px 14px; border: 1px solid var(--store-line); color: var(--store-muted); background: rgba(255,255,255,.58); font-size: 12px; line-height: 1.5; }
.account-notice > .iconify { width: 16px; height: 16px; flex: 0 0 auto; color: var(--store-wine); }
.account-notice span { flex: 1; }
.account-notice button { padding: 0; border: 0; color: var(--store-wine-dark); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }

.cart-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 19px; padding: 21px 23px; border: 1px solid var(--store-line); background: rgba(255,255,255,.54); }
.cart-toolbar h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.cart-toolbar-actions { display: flex; align-items: center; gap: 19px; }
.refresh-button,
.text-button { display: inline-flex; align-items: center; gap: 7px; padding: 0; border: 0; color: var(--store-muted); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .07em; text-transform: uppercase; }
.refresh-button:hover:not(:disabled),
.text-button:hover:not(:disabled) { color: var(--store-wine); }
.text-button-danger:hover:not(:disabled) { color: #a33e4a; }
.refresh-button:disabled,
.text-button:disabled { cursor: wait; opacity: .55; }
.refresh-button .iconify,
.text-button .iconify { width: 14px; height: 14px; }
.is-spinning { animation: cart-spin .8s linear infinite; }
@keyframes cart-spin { to { transform: rotate(360deg); } }

.cart-layout { display: grid; grid-template-columns: minmax(0,1.4fr) minmax(260px,.7fr); align-items: start; gap: 18px; }
.cart-items-panel,
.cart-summary-panel { border: 1px solid var(--store-line); background: rgba(255,255,255,.64); }
.cart-panel-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 15px; padding: 24px 23px 19px; border-bottom: 1px solid var(--store-line); }
.panel-kicker { margin: 0 0 8px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .1em; text-transform: uppercase; }
.cart-panel-heading h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 33px; font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.cart-panel-heading > span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .06em; text-transform: uppercase; }
.cart-item-list { display: flex; flex-direction: column; }
.cart-item { display: grid; grid-template-columns: 89px minmax(0,1fr) auto 90px 22px; align-items: center; gap: 15px; padding: 16px 18px; border-bottom: 1px solid rgba(36,29,33,.1); }
.cart-item:last-child { border-bottom: 0; }
.cart-item.is-unavailable { background: rgba(232,227,228,.3); }
.cart-item-image { width: 89px; height: 112px; display: grid; place-items: center; overflow: hidden; color: var(--store-wine); background: var(--store-linen); font-family: 'Playfair Display', Georgia, serif; text-decoration: none; }
.cart-item-image img { width: 100%; height: 100%; object-fit: cover; transition: transform .35s ease; }
.cart-item-image:hover img { transform: scale(1.04); }
.cart-item-image span { font-size: 28px; }
.cart-item-copy { min-width: 0; display: flex; align-items: flex-start; flex-direction: column; gap: 5px; }
.cart-item-type { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .08em; text-transform: uppercase; }
.cart-item-copy a { color: var(--store-ink); text-decoration: none; }
.cart-item-copy a:hover { color: var(--store-wine); }
.cart-item-copy h3 { overflow: hidden; margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 23px; font-weight: 500; letter-spacing: -.025em; line-height: 1.03; text-overflow: ellipsis; white-space: nowrap; }
.cart-item-copy p { margin: 0; color: var(--store-muted); font-size: 10px; }
.stock-note,
.unavailable-note { display: inline-flex; align-items: center; gap: 5px; margin-top: 5px; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .03em; text-transform: uppercase; }
.stock-note { color: #9b6c3e; }
.unavailable-note { color: #a33e4a; }
.unavailable-note .iconify { width: 12px; height: 12px; }
.cart-item-price { display: flex; align-items: flex-end; flex-direction: column; gap: 7px; white-space: nowrap; }
.cart-item-price span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .03em; }
.cart-item-price strong { font-size: 13px; font-weight: 600; }
.quantity-control { min-height: 33px; display: grid; grid-template-columns: 27px 28px 27px; align-items: center; border: 1px solid var(--store-line); }
.quantity-control button { width: 27px; height: 31px; display: grid; place-items: center; padding: 0; border: 0; color: var(--store-ink); background: transparent; cursor: pointer; }
.quantity-control button:hover:not(:disabled) { color: var(--store-wine); }
.quantity-control button:disabled { cursor: not-allowed; opacity: .3; }
.quantity-control span { color: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 10px; text-align: center; }
.quantity-control.disabled { opacity: .5; }
.quantity-control .iconify { width: 12px; height: 12px; }
.cart-remove { width: 20px; height: 25px; display: grid; place-items: center; padding: 0; border: 0; color: var(--store-muted); background: transparent; cursor: pointer; }
.cart-remove:hover:not(:disabled) { color: var(--store-wine); }
.cart-remove:disabled { cursor: wait; opacity: .5; }
.cart-remove .iconify { width: 15px; height: 15px; }
.cart-warning { display: flex; align-items: flex-start; gap: 8px; margin: 17px 18px 0; padding: 11px 12px; color: #895e3e; background: #f5e9dc; font-size: 10px; line-height: 1.5; }
.cart-warning .iconify { width: 14px; height: 14px; flex: 0 0 auto; }
.continue-shopping { display: inline-flex; align-items: center; gap: 7px; margin: 23px 19px 21px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; }
.continue-shopping:hover { color: var(--store-wine); }
.continue-shopping .iconify { width: 14px; height: 14px; }

.cart-summary-panel { position: sticky; top: 24px; overflow: hidden; }
.summary-art { position: relative; height: 164px; overflow: hidden; color: #fff; background: var(--store-ink); }
.summary-art-image { position: absolute; inset: 0; background: linear-gradient(135deg, rgba(36,29,33,.1), rgba(36,29,33,.7)), url('/lingerie/lace-green.jpg') center / cover; opacity: .88; }
.summary-art::after { position: absolute; inset: 13px; border: 1px solid rgba(255,255,255,.35); content: ''; }
.summary-art span { position: absolute; z-index: 1; right: 21px; bottom: 19px; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .12em; }
.cart-summary-content { padding: 24px 22px 23px; }
.cart-summary-content h2 { margin: 0 0 22px; font-family: 'Playfair Display', Georgia, serif; font-size: 34px; font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.summary-lines { display: flex; flex-direction: column; }
.summary-lines > div { display: flex; align-items: center; justify-content: space-between; gap: 15px; padding: 11px 0; border-top: 1px solid rgba(36,29,33,.1); color: var(--store-muted); font-size: 11px; }
.summary-lines strong { color: var(--store-ink); font-size: 11px; font-weight: 600; }
.summary-total { display: flex; align-items: baseline; justify-content: space-between; gap: 15px; margin-top: 10px; padding-top: 16px; border-top: 1px solid var(--store-ink); }
.summary-total span { font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-transform: uppercase; }
.summary-total strong { color: var(--store-wine); font-size: 18px; font-weight: 600; }
.summary-button { width: 100%; box-sizing: border-box; margin-top: 20px; }
.summary-button.disabled { cursor: not-allowed; opacity: .48; pointer-events: none; }
.summary-note { display: flex; align-items: flex-start; gap: 6px; margin: 16px 0 0; color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.summary-note .iconify { width: 13px; height: 13px; flex: 0 0 auto; color: var(--store-wine); }

.store-button,
.outline-button { display: inline-flex; align-items: center; justify-content: center; gap: 8px; min-height: 45px; padding: 0 16px; border: 1px solid var(--store-ink); color: #fff; background: var(--store-ink); cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; transition: color .2s ease, background .2s ease; }
.store-button:hover { color: var(--store-ink); background: transparent; }
.outline-button { color: var(--store-ink); background: transparent; }
.outline-button:hover { color: #fff; background: var(--store-ink); }
.store-button .iconify,
.outline-button .iconify { width: 14px; height: 14px; }

.cart-empty { min-height: 340px; display: grid; grid-template-columns: 210px minmax(0,1fr); align-items: center; gap: 33px; padding: 35px; border: 1px solid var(--store-line); background: rgba(255,255,255,.58); }
.cart-empty-art { width: 190px; height: 230px; display: grid; place-items: center; color: rgba(255,255,255,.86); background: linear-gradient(135deg, rgba(154,64,85,.35), rgba(36,29,33,.76)), url('/lingerie/hero-soft.jpg') center / cover; font-family: 'Playfair Display', Georgia, serif; font-size: 54px; }
.cart-empty h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 39px; font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.cart-empty p:not(.panel-kicker) { max-width: 460px; margin: 12px 0 21px; color: var(--store-muted); font-size: 12px; line-height: 1.65; }
.cart-empty-actions { display: flex; flex-wrap: wrap; gap: 10px; }

@media (max-width: 930px) {
  .cart-layout,
  .cart-loading { grid-template-columns: 1fr; }
  .cart-summary-panel { position: static; }
  .cart-loading-side { min-height: 250px; }
}

@media (max-width: 680px) {
  .cart-toolbar { align-items: flex-start; flex-direction: column; padding: 19px 17px; }
  .cart-toolbar-actions { width: 100%; justify-content: space-between; }
  .cart-item { grid-template-columns: 67px minmax(0,1fr) 20px; align-items: start; gap: 11px; padding: 14px 13px; }
  .cart-item-image { width: 67px; height: 88px; grid-row: 1 / span 3; }
  .cart-item-copy h3 { font-size: 20px; }
  .cart-item-price { grid-column: 2; align-items: flex-start; flex-direction: row; justify-content: space-between; gap: 10px; margin-top: 5px; }
  .quantity-control { grid-column: 2; width: fit-content; margin-top: 3px; }
  .cart-remove { grid-column: 3; grid-row: 1; }
  .cart-empty { grid-template-columns: 1fr; gap: 22px; padding: 24px 19px; }
  .cart-empty-art { width: 100%; height: 170px; }
}

@media (max-width: 420px) {
  .cart-summary-content { padding-inline: 17px; }
  .cart-empty h2 { font-size: 32px; }
  .cart-empty-actions { flex-direction: column; }
  .cart-empty-actions > * { width: 100%; box-sizing: border-box; }
}

@media (prefers-reduced-motion: reduce) {
  .cart-loading-main::after,
  .cart-loading-side::after,
  .is-spinning { animation: none; }
}
</style>
