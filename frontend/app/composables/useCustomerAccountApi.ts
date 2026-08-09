import type {
  CustomerAddress,
  CustomerAddressDeleteResponse,
  CustomerAddressInput,
  CustomerAddressMutationResponse,
  CustomerCart,
  CustomerOrder,
  CustomerOrderCheckout,
  CustomerOrderCancellation,
  CustomerOrderIdempotencyKey,
  CustomerOrderPayment,
  CustomerOrdersResponse,
  CustomerPlaceOrderInput,
  CustomerProfile,
  CustomerProfileUpdateInput,
  CustomerProfileUpdateResponse,
  CustomerShipment,
  CustomerShipmentsResponse
} from '~/types/customer-account'
import type { ApiResult } from '~/types/http'

export interface CustomerRequestError {
  data?: ApiResult<unknown> & {
    retry_after?: number
    transport_failure?: boolean
  }
  response?: {
    status?: number
    _data?: ApiResult<unknown> & {
      retry_after?: number
      transport_failure?: boolean
    }
  }
  status?: number
  statusCode?: number
  statusMessage?: string
  message?: string
}

export interface CustomerRequestDetails {
  status: number
  message: string
  retryAfterSeconds: number | null
  transportFailure: boolean
}

export function customerRequestDetails(
  error: unknown,
  fallback = 'Something went wrong. Please try again.'
): CustomerRequestDetails {
  const value = error as CustomerRequestError
  const payload = value.data ?? value.response?._data
  const status = Number(payload?.status ?? value.response?.status ?? value.statusCode ?? value.status)
  const retryAfterSeconds = Number(payload?.retry_after)

  return {
    status: Number.isFinite(status) && status > 0 ? status : 500,
    message: payload?.message ?? value.statusMessage ?? value.message ?? fallback,
    retryAfterSeconds: Number.isFinite(retryAfterSeconds) && retryAfterSeconds >= 0
      ? Math.ceil(retryAfterSeconds)
      : null,
    transportFailure: payload?.transport_failure === true
  }
}

export function customerRequestMessage(error: unknown, fallback = 'Something went wrong. Please try again.') {
  return customerRequestDetails(error, fallback).message
}

export function useCustomerAccountApi() {
  const http = useHttp()

  return {
    getProfile(userId: number) {
      return http.get<CustomerProfile>(`/users/${userId}`)
    },

    updateProfile(userId: number, input: CustomerProfileUpdateInput) {
      return http.put<CustomerProfileUpdateResponse, CustomerProfileUpdateInput>(`/users/${userId}`, input)
    },

    getAddresses() {
      return http.get<{ list: CustomerAddress[] }>('/users/me/delivery-addresses')
    },

    createAddress(input: CustomerAddressInput) {
      return http.post<CustomerAddressMutationResponse, CustomerAddressInput>('/users/me/delivery-addresses', input)
    },

    updateAddress(addressId: string, input: CustomerAddressInput) {
      return http.put<CustomerAddressMutationResponse, CustomerAddressInput>(
        `/users/me/delivery-addresses/${encodeURIComponent(addressId)}`,
        input
      )
    },

    deleteAddress(addressId: string) {
      return http.delete<CustomerAddressDeleteResponse>(
        `/users/me/delivery-addresses/${encodeURIComponent(addressId)}`
      )
    },

    getCart() {
      return http.get<CustomerCart>('/cart')
    },

    addCartItem(productId: number, quantity: number) {
      return http.post<CustomerCart, { product_id: number, quantity: number }>('/cart/items', {
        product_id: productId,
        quantity
      })
    },

    updateCartItem(itemId: number, quantity: number) {
      return http.put<CustomerCart, { quantity: number }>(`/cart/items/${itemId}`, { quantity })
    },

    removeCartItem(itemId: number) {
      return http.delete<CustomerCart>(`/cart/items/${itemId}`)
    },

    clearCart() {
      return http.delete<CustomerCart>('/cart')
    },

    getOrders(page = 1, size = 25) {
      return http.get<CustomerOrdersResponse>('/orders', { page, size })
    },

    getOrder(orderNo: string) {
      return http.get<CustomerOrder>(`/orders/${encodeURIComponent(orderNo)}`)
    },

    issueOrderIdempotencyKey() {
      return http.post<CustomerOrderIdempotencyKey>('/orders/idempotency-keys')
    },

    placeOrder(input: CustomerPlaceOrderInput, idempotencyKey: string) {
      return http.post<CustomerOrder, CustomerPlaceOrderInput>('/orders', input, {
        headers: { 'Idempotency-Key': idempotencyKey },
        businessErrorStatuses: [403]
      })
    },

    openOrderCheckout(orderNo: string) {
      return http.post<CustomerOrderCheckout>(
        `/orders/${encodeURIComponent(orderNo)}/checkout`,
        undefined,
        {
          businessErrorStatuses: [403]
        }
      )
    },

    getOrderPayment(orderNo: string) {
      return http.get<CustomerOrderPayment>(`/orders/${encodeURIComponent(orderNo)}/payment`)
    },

    cancelOrder(orderNo: string, reason?: string) {
      return http.post<CustomerOrderCancellation, { reason?: string }>(
        `/orders/${encodeURIComponent(orderNo)}/cancel`,
        reason ? { reason } : undefined
      )
    },

    getShipments(orderNo: string) {
      return http.get<CustomerShipmentsResponse>(`/orders/${encodeURIComponent(orderNo)}/shipments`)
    },

    getShipment(orderNo: string, shipmentNo: string) {
      return http.get<CustomerShipment>(
        `/orders/${encodeURIComponent(orderNo)}/shipments/${encodeURIComponent(shipmentNo)}`
      )
    },

    trackShipment(carrier: string, trackingNo: string) {
      return http.get<CustomerShipment>(
        `/logistics/track/${encodeURIComponent(carrier)}/${encodeURIComponent(trackingNo)}`
      )
    }
  }
}
