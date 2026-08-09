export interface OrderCheckoutSessionContext {
  customerId: number
  idempotencyKey: string
  orderNo: string | null
  startedAt: string
}

const STORAGE_KEY = 'pelissa_order_checkout'

export function useOrderCheckoutSession() {
  const session = useCustomerSession()

  function read(): OrderCheckoutSessionContext | null {
    if (import.meta.server || !session.userId.value) return null

    try {
      const value = JSON.parse(sessionStorage.getItem(STORAGE_KEY) || '') as Partial<OrderCheckoutSessionContext>
      if (
        value.customerId !== session.userId.value
        || typeof value.idempotencyKey !== 'string'
        || !value.idempotencyKey.trim()
        || (value.orderNo !== null && typeof value.orderNo !== 'string')
        || typeof value.startedAt !== 'string'
      ) {
        sessionStorage.removeItem(STORAGE_KEY)
        return null
      }
      return value as OrderCheckoutSessionContext
    } catch {
      sessionStorage.removeItem(STORAGE_KEY)
      return null
    }
  }

  function write(value: Omit<OrderCheckoutSessionContext, 'customerId'>) {
    if (import.meta.server || !session.userId.value) return
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({
      ...value,
      customerId: session.userId.value
    }))
  }

  function clear(orderNo?: string) {
    if (import.meta.server) return
    const current = read()
    if (!orderNo || current?.orderNo === orderNo) {
      sessionStorage.removeItem(STORAGE_KEY)
    }
  }

  return { read, write, clear }
}
