<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { customerRequestMessage } from '~/composables/useCustomerAccountApi'
import type { CustomerCartItem } from '~/types/customer-account'
import { formatCustomerMoney } from '~/utils/customer-display'

const emit = defineEmits<{ close: [] }>()
const session = useCustomerSession()
const customerCart = useCustomerCart()
const toast = useToast()

const busyItemId = ref<number | null>(null)
const requestError = ref('')
const cartItems = computed(() => customerCart.items.value)
const cartCount = computed(() => customerCart.totalQuantity.value)
const hasUnavailableItems = computed(() => cartItems.value.some(item => !item.purchasable || item.quantity > item.stock))

function itemVariant(item: CustomerCartItem) {
  return [item.color, item.size || item.top_size, item.bottom_size].filter(Boolean).join(' / ')
}

function canAdjustQuantity(item: CustomerCartItem) {
  return item.purchasable || (item.product_status === 'ACTIVE' && item.stock > 0 && item.quantity > item.stock)
}

async function refreshCart() {
  if (!session.isAuthenticated.value) return
  requestError.value = ''
  try {
    await customerCart.refresh(true)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not load your cart.')
  }
}

async function updateQuantity(item: CustomerCartItem, quantity: number) {
  if (busyItemId.value !== null || !canAdjustQuantity(item)) return
  const nextQuantity = Math.max(1, Math.min(quantity, Math.min(item.stock, 99)))
  if (nextQuantity === item.quantity) return

  busyItemId.value = item.id
  requestError.value = ''
  try {
    await customerCart.updateItem(item.id, nextQuantity)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not update this item.')
    toast.add({ title: 'Cart not updated', description: requestError.value, color: 'error' })
  } finally {
    busyItemId.value = null
  }
}

async function removeItem(item: CustomerCartItem) {
  if (busyItemId.value !== null) return
  busyItemId.value = item.id
  requestError.value = ''
  try {
    await customerCart.removeItem(item.id)
    toast.add({ title: 'Removed from cart', description: `${item.name} was removed.`, color: 'success' })
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, 'We could not remove this item.')
    toast.add({ title: 'Item not removed', description: requestError.value, color: 'error' })
  } finally {
    busyItemId.value = null
  }
}

onMounted(() => {
  if (session.isAuthenticated.value) void refreshCart()
})
</script>

<template>
  <aside class="store-cart-popover" aria-label="Shopping cart">
    <header class="store-cart-heading">
      <div>
        <span>YOUR CART</span>
        <strong>Shopping cart</strong>
      </div>
      <button type="button" aria-label="Close shopping cart" title="Close" @click="emit('close')">
        <UIcon name="i-lucide-x" />
      </button>
    </header>

    <div v-if="!session.isAuthenticated.value" class="store-cart-state">
      <UIcon name="i-lucide-lock-keyhole" />
      <strong>Sign in to use your cart</strong>
      <p>Your saved items and quantities are linked to your account.</p>
      <div class="store-cart-state-actions">
        <NuxtLink :to="{ path: '/login', query: { redirect: '/cart' } }" @click="emit('close')">Sign in</NuxtLink>
        <NuxtLink to="/collections/shop" @click="emit('close')">Keep browsing</NuxtLink>
      </div>
    </div>

    <div v-else-if="customerCart.isLoading.value && !customerCart.cart.value" class="store-cart-state" aria-live="polite">
      <UIcon name="i-lucide-loader-circle" class="is-spinning" />
      <strong>Loading your cart</strong>
      <p>Checking current prices and availability.</p>
    </div>

    <template v-else-if="cartItems.length">
      <div class="store-cart-summary-line">
        <span>{{ cartCount }} {{ cartCount === 1 ? 'item' : 'items' }}</span>
        <strong>{{ formatCustomerMoney(customerCart.cart.value?.subtotal, 'USD') }}</strong>
      </div>

      <div class="store-cart-items">
        <article
          v-for="item in cartItems"
          :key="item.id"
          class="store-cart-item"
          :class="{ unavailable: !item.purchasable || item.quantity > item.stock }"
        >
          <NuxtLink class="store-cart-item-image" :to="`/product/${item.product_id}`" @click="emit('close')">
            <img v-if="item.primary_image" :src="item.primary_image" :alt="item.name">
            <span v-else>P°</span>
          </NuxtLink>

          <div class="store-cart-item-copy">
            <div class="store-cart-item-title">
              <NuxtLink :to="`/product/${item.product_id}`" @click="emit('close')">{{ item.name }}</NuxtLink>
              <strong>{{ formatCustomerMoney(item.line_total, 'USD') }}</strong>
            </div>
            <p>{{ itemVariant(item) || item.sku }}</p>
            <small v-if="!item.purchasable || item.quantity > item.stock">Unavailable or insufficient stock</small>

            <div class="store-cart-item-actions">
              <div class="store-cart-quantity" aria-label="Item quantity">
                <button
                  type="button"
                  aria-label="Decrease quantity"
                  title="Decrease quantity"
                  :disabled="busyItemId === item.id || item.quantity <= 1 || !canAdjustQuantity(item)"
                  @click="updateQuantity(item, item.quantity - 1)"
                >
                  <UIcon name="i-lucide-minus" />
                </button>
                <span>{{ item.quantity }}</span>
                <button
                  type="button"
                  aria-label="Increase quantity"
                  title="Increase quantity"
                  :disabled="busyItemId === item.id || item.quantity >= item.stock || item.quantity >= 99 || !canAdjustQuantity(item)"
                  @click="updateQuantity(item, item.quantity + 1)"
                >
                  <UIcon name="i-lucide-plus" />
                </button>
              </div>
              <button
                type="button"
                class="store-cart-remove"
                aria-label="Remove item"
                title="Remove item"
                :disabled="busyItemId !== null"
                @click="removeItem(item)"
              >
                <UIcon name="i-lucide-trash-2" />
              </button>
            </div>
          </div>
        </article>
      </div>

      <p v-if="requestError" class="store-cart-notice" role="alert">{{ requestError }}</p>
      <p v-else-if="hasUnavailableItems" class="store-cart-notice">Resolve unavailable items before checkout.</p>

      <footer class="store-cart-footer">
        <div><span>Subtotal</span><strong>{{ formatCustomerMoney(customerCart.cart.value?.subtotal, 'USD') }}</strong></div>
        <div class="store-cart-footer-actions">
          <NuxtLink to="/cart" @click="emit('close')">View cart</NuxtLink>
          <NuxtLink v-if="!hasUnavailableItems" to="/checkout" @click="emit('close')">Checkout</NuxtLink>
        </div>
      </footer>
    </template>

    <div v-else class="store-cart-state">
      <UIcon name="i-lucide-shopping-cart" />
      <strong>Your cart is empty</strong>
      <p>{{ requestError || 'Add a piece to start building your order.' }}</p>
      <div class="store-cart-state-actions">
        <NuxtLink to="/collections/shop" @click="emit('close')">Start shopping</NuxtLink>
        <button v-if="requestError" type="button" @click="refreshCart">Try again</button>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.store-cart-popover {
  position: absolute;
  z-index: 50;
  top: 100%;
  right: max(32px, calc((100vw - 1440px) / 2));
  width: min(calc(100% - 64px), 440px);
  max-height: min(680px, calc(100vh - 180px));
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--store-ink, var(--ink, #241d21));
  color: var(--store-ink, var(--ink, #241d21));
  background: #fff;
  box-shadow: 0 18px 48px rgba(36, 29, 33, .18);
}

.store-cart-heading {
  min-height: 72px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 15px 18px;
  border-bottom: 1px solid var(--store-line, var(--line, #ded6d8));
  background: var(--store-linen, var(--linen, #f2eded));
}

.store-cart-heading > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.store-cart-heading span {
  color: var(--store-wine, var(--coral, #9a4055));
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .1em;
}

.store-cart-heading strong {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 23px;
  font-weight: 500;
}

.store-cart-heading button,
.store-cart-state-actions button {
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.store-cart-heading button {
  width: 34px;
  height: 34px;
}

.store-cart-heading button:hover {
  background: rgba(36, 29, 33, .07);
}

.store-cart-heading .iconify {
  width: 18px;
  height: 18px;
}

.store-cart-state {
  min-height: 250px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  padding: 34px 28px;
  text-align: center;
}

.store-cart-state > .iconify {
  width: 28px;
  height: 28px;
  margin-bottom: 16px;
  color: var(--store-wine, var(--coral, #9a4055));
}

.store-cart-state > strong {
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 25px;
  font-weight: 500;
}

.store-cart-state p {
  max-width: 300px;
  margin: 9px 0 20px;
  color: var(--store-muted, #756c70);
  font-size: 11px;
  line-height: 1.55;
}

.store-cart-state-actions,
.store-cart-footer-actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.store-cart-state-actions a,
.store-cart-state-actions button,
.store-cart-footer-actions a {
  min-height: 38px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 13px;
  border: 1px solid var(--store-ink, var(--ink, #241d21));
  color: inherit;
  background: #fff;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .055em;
  text-decoration: none;
  text-transform: uppercase;
}

.store-cart-state-actions a:first-child,
.store-cart-footer-actions a:last-child {
  color: #fff;
  background: var(--store-ink, var(--ink, #241d21));
}

.store-cart-summary-line {
  min-height: 42px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 0 18px;
  border-bottom: 1px solid var(--store-line, var(--line, #ded6d8));
  color: var(--store-muted, #756c70);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  text-transform: uppercase;
}

.store-cart-summary-line strong {
  color: var(--store-ink, var(--ink, #241d21));
  font-size: 10px;
}

.store-cart-items {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.store-cart-item {
  min-height: 118px;
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 13px;
  padding: 13px 18px;
  border-bottom: 1px solid var(--store-line, var(--line, #ded6d8));
}

.store-cart-item.unavailable {
  background: rgba(232, 227, 228, .38);
}

.store-cart-item-image {
  width: 76px;
  height: 96px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: var(--store-wine, var(--coral, #9a4055));
  background: var(--store-linen, var(--linen, #f2eded));
  font-family: 'Playfair Display', Georgia, serif;
  text-decoration: none;
}

.store-cart-item-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.store-cart-item-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.store-cart-item-title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}

.store-cart-item-title a {
  overflow: hidden;
  color: inherit;
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.store-cart-item-title strong {
  flex: 0 0 auto;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
}

.store-cart-item-copy p,
.store-cart-item-copy small {
  margin: 5px 0 0;
  color: var(--store-muted, #756c70);
  font-size: 9px;
}

.store-cart-item-copy small {
  color: #963f4f;
}

.store-cart-item-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: auto;
  padding-top: 9px;
}

.store-cart-quantity {
  height: 29px;
  display: grid;
  grid-template-columns: 29px 30px 29px;
  align-items: center;
  border: 1px solid var(--store-line, var(--line, #ded6d8));
}

.store-cart-quantity button,
.store-cart-remove {
  height: 100%;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.store-cart-quantity button:disabled,
.store-cart-remove:disabled {
  opacity: .3;
  cursor: not-allowed;
}

.store-cart-quantity span {
  text-align: center;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
}

.store-cart-quantity .iconify,
.store-cart-remove .iconify {
  width: 13px;
  height: 13px;
}

.store-cart-remove {
  width: 29px;
  color: var(--store-muted, #756c70);
}

.store-cart-remove:hover:not(:disabled) {
  color: var(--store-wine, var(--coral, #9a4055));
}

.store-cart-notice {
  margin: 0;
  padding: 10px 18px;
  color: #963f4f;
  background: #f8eff1;
  font-size: 9px;
  line-height: 1.45;
}

.store-cart-footer {
  padding: 15px 18px 18px;
  border-top: 1px solid var(--store-line, var(--line, #ded6d8));
  background: #fff;
}

.store-cart-footer > div:first-child {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 14px;
  font-size: 10px;
}

.store-cart-footer > div:first-child strong {
  color: var(--store-wine, var(--coral, #9a4055));
  font-family: 'DM Mono', monospace;
  font-size: 14px;
}

.store-cart-footer-actions a {
  flex: 1;
}

.is-spinning {
  animation: store-cart-spin .8s linear infinite;
}

@keyframes store-cart-spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 820px) {
  .store-cart-popover {
    right: 16px;
    width: calc(100% - 32px);
    max-height: calc(100vh - 114px);
  }
}

@media (max-width: 430px) {
  .store-cart-heading {
    min-height: 64px;
  }

  .store-cart-item {
    grid-template-columns: 64px minmax(0, 1fr);
    padding-inline: 13px;
  }

  .store-cart-item-image {
    width: 64px;
    height: 82px;
  }

  .store-cart-state-actions {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .is-spinning { animation: none; }
}
</style>
