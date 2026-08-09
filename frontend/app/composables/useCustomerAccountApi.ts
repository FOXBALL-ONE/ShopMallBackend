import type {
  CustomerAddress,
  CustomerAddressDeleteResponse,
  CustomerAddressInput,
  CustomerAddressMutationResponse,
  CustomerCart,
  CustomerOrder,
  CustomerOrderCancellation,
  CustomerOrdersResponse,
  CustomerProfile,
  CustomerProfileUpdateInput,
  CustomerProfileUpdateResponse,
  CustomerShipment,
  CustomerShipmentsResponse
} from '~/types/customer-account'
import type { ApiResult } from '~/types/http'

export interface CustomerRequestError {
  data?: ApiResult<unknown>
  response?: { _data?: ApiResult<unknown> }
  statusMessage?: string
  message?: string
}

export function customerRequestMessage(error: unknown, fallback = 'Something went wrong. Please try again.') {
  const value = error as CustomerRequestError
  return value.data?.message
    ?? value.response?._data?.message
    ?? value.statusMessage
    ?? value.message
    ?? fallback
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
