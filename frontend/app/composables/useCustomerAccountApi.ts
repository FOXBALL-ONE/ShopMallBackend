import type {
  CustomerAddress,
  CustomerAddressDeleteResponse,
  CustomerAddressInput,
  CustomerAddressMutationResponse,
  CustomerCart,
  CustomerOrder,
  CustomerOrderCheckout,
  CustomerOrderCancellation,
  CustomerOrderCompletion,
  CustomerOrderIdempotencyKey,
  CustomerOrderPayment,
  CustomerOrderRefund,
  CustomerOrderRefundStatus,
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

    addCartItem(variantId: number, quantity: number) {
      return http.post<CustomerCart, { variant_id: number, quantity: number }>('/cart/items', {
        variant_id: variantId,
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

    setOrderShippingAddressAsDefault(orderNo: string) {
      return http.post<CustomerAddress>(
        `/orders/${encodeURIComponent(orderNo)}/shipping-address/default`
      )
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

    completeOrder(orderNo: string) {
      return http.post<CustomerOrderCompletion>(`/orders/${encodeURIComponent(orderNo)}/complete`)
    },

    refundOrder(orderNo: string, reason?: string, reasonDetail?: string) {
      const payload: { reason?: string, reason_detail?: string } = {}
      if (reason) payload.reason = reason
      if (reasonDetail) payload.reason_detail = reasonDetail
      return http.post<CustomerOrderRefund, { reason?: string, reason_detail?: string }>(
        `/orders/${encodeURIComponent(orderNo)}/refund`,
        Object.keys(payload).length ? payload : undefined
      )
    },

    getOrderRefundStatus(orderNo: string) {
      return http.get<CustomerOrderRefundStatus>(`/orders/${encodeURIComponent(orderNo)}/refund-status`)
    },

    getShipments(orderNo: string) {
      return http.get<CustomerShipmentsResponse>(`/orders/${encodeURIComponent(orderNo)}/shipments`)
    },

    getShipment(orderNo: string, shipmentNo: string) {
      return http.get<CustomerShipment>(
        `/orders/${encodeURIComponent(orderNo)}/shipments/${encodeURIComponent(shipmentNo)}`
      )
    },

    markShipmentDelivered(orderNo: string, shipmentNo: string, idempotencyKey: string) {
      return http.post<CustomerShipment>(
        `/orders/${encodeURIComponent(orderNo)}/shipments/${encodeURIComponent(shipmentNo)}/delivered`,
        undefined,
        { headers: { 'Idempotency-Key': idempotencyKey } }
      )
    },

    trackShipment(carrier: string, trackingNo: string) {
      return http.get<CustomerShipment>(
        `/logistics/track/${encodeURIComponent(carrier)}/${encodeURIComponent(trackingNo)}`
      )
    }
  }
}
