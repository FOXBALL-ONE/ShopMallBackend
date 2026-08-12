import { computed } from 'vue'
import type { CustomerCart } from '~/types/customer-account'

export function useCustomerCart() {
  const api = useCustomerAccountApi()
  const session = useCustomerSession()
  const cart = useState<CustomerCart | null>('pelissa-customer-cart', () => null)
  const ownerId = useState<number | null>('pelissa-customer-cart-owner', () => null)
  const isLoading = useState('pelissa-customer-cart-loading', () => false)

  const items = computed(() => cart.value?.items || [])
  const totalQuantity = computed(() => cart.value?.total_quantity || 0)

  function reset() {
    cart.value = null
    ownerId.value = null
    isLoading.value = false
  }

  function apply(nextCart: CustomerCart) {
    cart.value = nextCart
    ownerId.value = nextCart.customer_id
    return nextCart
  }

  async function refresh(force = false) {
    const customerId = session.userId.value
    if (!session.isAuthenticated.value || !customerId) {
      reset()
      return null
    }

    if (ownerId.value !== customerId) {
      cart.value = null
      ownerId.value = customerId
    }
    if (!force && cart.value) return cart.value

    isLoading.value = true
    try {
      return apply(await api.getCart())
    } finally {
      isLoading.value = false
    }
  }

  async function addItem(variantId: number, quantity: number) {
    return apply(await api.addCartItem(variantId, quantity))
  }

  async function updateItem(itemId: number, quantity: number) {
    return apply(await api.updateCartItem(itemId, quantity))
  }

  async function removeItem(itemId: number) {
    return apply(await api.removeCartItem(itemId))
  }

  async function clear() {
    return apply(await api.clearCart())
  }

  return {
    cart,
    items,
    totalQuantity,
    isLoading,
    refresh,
    addItem,
    updateItem,
    removeItem,
    clear,
    apply,
    reset
  }
}
