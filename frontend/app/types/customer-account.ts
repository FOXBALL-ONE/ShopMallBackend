export type NullableNumber = number | string | null

export interface CustomerProfile {
  id: number
  email: string
  username: string
  first_name: string
  last_name: string
  phone: string | null
  avatar: string | null
  locale: string | null
  currency: string | null
  birthday: string | null
  bust: NullableNumber
  waist: NullableNumber
  hip: NullableNumber
  torso: NullableNumber
  bra_size: string | null
  cup_size: string | null
  weight: NullableNumber
  weight_unit: string | null
  height: NullableNumber
  length_unit: string | null
  email_verified: boolean
  marketing_consent: boolean
  role: string
  enabled: boolean
  status: string
  last_login_at: string | null
  created_at: string | null
  updated_at: string | null
}

export interface CustomerProfileUpdateInput {
  first_name: string
  last_name: string
  phone?: string
  avatar?: string
  locale?: string
  currency?: string
  birthday?: string
  bust?: NullableNumber
  waist?: NullableNumber
  hip?: NullableNumber
  torso?: NullableNumber
  bra_size?: string
  cup_size?: string
  weight?: NullableNumber
  weight_unit?: string
  height?: NullableNumber
  length_unit?: string
  marketing_consent: boolean
}

export interface CustomerProfileUpdateResponse {
  id: number
  email: string
  username: string
  first_name: string
  last_name: string
  phone: string | null
  avatar: string | null
  locale: string | null
  currency: string | null
  birthday: string | null
  marketing_consent: boolean
  updated_at: string | null
}

export interface CustomerAddress {
  id: string
  label: string | null
  name: string
  phone: string
  company: string | null
  country_code: string
  state_or_province: string | null
  city: string
  district: string | null
  postal_code: string | null
  address_line1: string
  address_line2: string | null
  is_default: boolean
  delivery_instructions: string | null
}

export interface CustomerAddressInput {
  label?: string
  name: string
  phone: string
  company?: string
  country_code: string
  state_or_province?: string
  city: string
  district?: string
  postal_code?: string
  address_line1: string
  address_line2?: string
  is_default: boolean
  delivery_instructions?: string
}

export interface CustomerAddressMutationResponse {
  id: string
  label: string | null
  name: string
  phone: string
  country_code: string
  city: string
  address_line1: string
  is_default: boolean
}

export interface CustomerAddressDeleteResponse {
  id: string
  deleted: boolean
}

export interface CustomerCartItem {
  id: number
  product_id: number
  product_type: string
  name: string
  color: string
  size: string | null
  top_size: string | null
  bottom_size: string | null
  unit_price: NullableNumber
  quantity: number
  line_total: NullableNumber
  stock: number
  product_status: string
  purchasable: boolean
  primary_image: string | null
  created_at: string | null
  updated_at: string | null
}

export interface CustomerCart {
  customer_id: number
  items: CustomerCartItem[]
  total_quantity: number
  subtotal: NullableNumber
  updated_at: string | null
}

export interface CustomerOrderItem {
  id: number
  product_id: number
  product_snapshot: string
  unit_price: NullableNumber
  quantity: number
  line_total: NullableNumber
  created_at: string | null
}

export interface CustomerShippingAddress {
  name: string
  phone: string
  country: string
  state_or_province: string | null
  city: string
  district: string | null
  postal_code: string | null
  address1: string
  address2: string | null
  company: string | null
  delivery_instructions: string | null
}

export interface CustomerOrder {
  id: number
  order_no: string
  customer_id: number
  status: string
  items_subtotal: NullableNumber
  shipping_fee: NullableNumber
  tax_amount: NullableNumber
  discount_amount: NullableNumber
  total_amount: NullableNumber
  currency: string
  payment_intent_id: string | null
  shipping_address: CustomerShippingAddress
  client_message: string | null
  expires_at: string | null
  paid_at: string | null
  cancelled_at: string | null
  shipped_at: string | null
  delivered_at: string | null
  cancel_reason: string | null
  created_at: string | null
  updated_at: string | null
  items: CustomerOrderItem[]
}

export interface CustomerOrdersResponse {
  list: CustomerOrder[]
  pagination: {
    count: number
  }
}

export interface CustomerOrderCancellation {
  id: number
  order_no: string
  status: string
  cancel_reason: string | null
  items: CustomerOrderItem[]
}

export interface CustomerShipmentItem {
  order_item_id: number
  product_snapshot: string
  quantity: number
  allocation_status: string
}

export interface CustomerShipmentTrack {
  carrier_event_id: string
  status_code: string
  normalized_status: string
  source: string
  location: string | null
  description: string | null
  occurred_at: string
  received_at: string | null
}

export interface CustomerShipment {
  shipment_no: string
  order_no: string
  carrier: string
  tracking_no: string | null
  tracking_url: string | null
  status: string
  shipped_at: string | null
  delivered_at: string | null
  last_track_status: string | null
  last_track_location: string | null
  last_track_at: string | null
  items: CustomerShipmentItem[]
  tracks: CustomerShipmentTrack[]
}

export interface CustomerShipmentsResponse {
  list: CustomerShipment[]
}

export interface CustomerSessionUser {
  username: string
  email: string
  first_name: string
  last_name: string
  avatar: string | null
  locale: string | null
  currency: string | null
  role: string
}
