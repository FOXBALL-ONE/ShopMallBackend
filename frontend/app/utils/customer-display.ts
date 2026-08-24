import type { CustomerOrderItem } from '~/types/customer-account'

export interface ProductSnapshotDisplay {
  name: string
  color: string | null
  image: string | null
  variant: string | null
}

export interface ProductSnapshotLabels {
  fallbackName?: string
  size?: string
  top?: string
  bottom?: string
}

export function formatCustomerMoney(value: number | string | null | undefined, currency = 'USD', locale = 'en-US') {
  const amount = Number(value ?? 0)
  if (!Number.isFinite(amount)) return '—'

  try {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: currency || 'USD',
      maximumFractionDigits: 2
    }).format(amount)
  } catch {
    return `$${amount.toFixed(2)}`
  }
}

export function formatCustomerDate(value: string | null | undefined, withTime = false, locale = 'en-US') {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return new Intl.DateTimeFormat(locale, withTime
    ? { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' }
    : { month: 'short', day: 'numeric', year: 'numeric' }).format(date)
}

export function customerStatusLabel(status: string | null | undefined) {
  const labels: Record<string, string> = {
    PENDING_PAYMENT: 'Awaiting payment',
    PAID: 'Preparing',
    REFUNDING: 'Refund in progress',
    PARTIALLY_REFUNDED: 'Partially refunded',
    REFUNDED: 'Voided after refund',
    SHIPPED: 'On the way',
    DELIVERED: 'Delivered',
    COMPLETED: 'Complete',
    CANCELLED: 'Cancelled',
    DELETED: 'Removed',
    LABEL_PENDING: 'Preparing label',
    LABEL_CREATED: 'Label created',
    CANCEL_PENDING: 'Cancellation pending',
    IN_TRANSIT: 'In transit',
    OUT_FOR_DELIVERY: 'Out for delivery',
    EXCEPTION: 'Needs attention',
    UNKNOWN: 'Updating'
  }

  return labels[status || ''] || (status ? status.replaceAll('_', ' ').toLowerCase() : 'Updating')
}

export function customerStatusTone(status: string | null | undefined) {
  if (['CANCELLED', 'DELETED', 'REFUNDED', 'EXCEPTION'].includes(status || '')) return 'muted'
  if (['DELIVERED', 'COMPLETED'].includes(status || '')) return 'success'
  if (['SHIPPED', 'IN_TRANSIT', 'OUT_FOR_DELIVERY'].includes(status || '')) return 'accent'
  return 'warm'
}

export function parseProductSnapshot(snapshot: string | null | undefined, labels: ProductSnapshotLabels = {}): ProductSnapshotDisplay {
  const fallback = snapshot?.trim() || labels.fallbackName || 'Pelissa piece'
  try {
    const parsed = JSON.parse(fallback) as Record<string, unknown>
    const name = parsed.name ?? parsed.title ?? parsed.product_name
    const color = parsed.color ?? parsed.colour
    const image = parsed.primary_image ?? parsed.primaryImage ?? parsed.image
    const size = parsed.size
    const topSize = parsed.top_size ?? parsed.topSize
    const bottomSize = parsed.bottom_size ?? parsed.bottomSize
    const sku = parsed.sku
    const variantAttributes = parsed.variantAttributes && typeof parsed.variantAttributes === 'object'
      ? parsed.variantAttributes as Record<string, unknown>
      : {}
    const resolvedTopSize = topSize ?? variantAttributes.top_size
    const resolvedBottomSize = bottomSize ?? variantAttributes.bottom_size
    const variant = [
      typeof color === 'string' ? color : null,
      typeof size === 'string' ? `${labels.size || 'Size'} ${size}` : null,
      typeof resolvedTopSize === 'string' ? `${labels.top || 'Top'} ${resolvedTopSize}` : null,
      typeof resolvedBottomSize === 'string' ? `${labels.bottom || 'Bottom'} ${resolvedBottomSize}` : null,
      typeof sku === 'string' ? sku : null
    ].filter(Boolean).join(' · ')

    return {
      name: typeof name === 'string' && name.trim() ? name : fallback,
      color: typeof color === 'string' && color.trim() ? color : null,
      image: typeof image === 'string' && image.trim() ? image : null,
      variant: variant || null
    }
  } catch {
    return { name: fallback, color: null, image: null, variant: null }
  }
}

export function orderItemCount(items: CustomerOrderItem[] | null | undefined) {
  return (items || []).reduce((total, item) => total + Number(item.quantity || 0), 0)
}

export function customerInitials(firstName?: string | null, lastName?: string | null, fallback = 'P') {
  const initials = `${firstName?.trim().charAt(0) || ''}${lastName?.trim().charAt(0) || ''}`.trim()
  return initials || fallback
}

export function formatAddressLine(address: {
  address_line1: string
  address_line2?: string | null
  city: string
  state_or_province?: string | null
  district?: string | null
  postal_code?: string | null
  country_code?: string | null
  country?: string | null
}) {
  return [
    address.address_line1,
    address.address_line2,
    address.district,
    address.city,
    address.state_or_province,
    address.postal_code,
    address.country_code || address.country
  ].filter(Boolean).join(', ')
}
