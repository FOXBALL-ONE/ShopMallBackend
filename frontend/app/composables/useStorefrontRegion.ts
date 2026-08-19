import { computed } from 'vue'

export const STOREFRONT_REGION_OPTIONS = [
  { code: 'US', currency: 'USD' },
  { code: 'CA', currency: 'CAD' },
  { code: 'GB', currency: 'GBP' },
  { code: 'AU', currency: 'AUD' },
  { code: 'CN', currency: 'CNY' },
  { code: 'JP', currency: 'JPY' },
  { code: 'KR', currency: 'KRW' },
  { code: 'SG', currency: 'SGD' },
  { code: 'DE', currency: 'EUR' },
  { code: 'FR', currency: 'EUR' },
] as const

export const STOREFRONT_CURRENCY_CODES = [
  'USD',
  'CAD',
  'GBP',
  'AUD',
  'CNY',
  'JPY',
  'KRW',
  'SGD',
  'EUR',
] as const

export type StorefrontCountryCode = typeof STOREFRONT_REGION_OPTIONS[number]['code']
export type StorefrontCurrencyCode = typeof STOREFRONT_CURRENCY_CODES[number]

function isCountryCode(value: string | null | undefined): value is StorefrontCountryCode {
  return STOREFRONT_REGION_OPTIONS.some(option => option.code === value)
}

function isCurrencyCode(value: string | null | undefined): value is StorefrontCurrencyCode {
  return STOREFRONT_CURRENCY_CODES.some(code => code === value)
}

export function useStorefrontRegion() {
  const { currentLocale } = useStorefrontI18n()
  const countryCode = useCookie<StorefrontCountryCode>('pelissa_country', {
    default: () => 'US',
    maxAge: 60 * 60 * 24 * 365,
    sameSite: 'lax',
    path: '/',
  })
  const currencyCode = useCookie<StorefrontCurrencyCode>('pelissa_currency', {
    default: () => 'USD',
    maxAge: 60 * 60 * 24 * 365,
    sameSite: 'lax',
    path: '/',
  })

  const countryOptions = computed(() => {
    const displayNames = new Intl.DisplayNames([currentLocale.value], { type: 'region' })
    return STOREFRONT_REGION_OPTIONS.map(option => ({
      code: option.code,
      label: displayNames.of(option.code) || option.code,
    }))
  })
  const currencyOptions = computed(() => {
    const displayNames = new Intl.DisplayNames([currentLocale.value], { type: 'currency' })
    return STOREFRONT_CURRENCY_CODES.map(code => ({
      code,
      label: displayNames.of(code) || code,
    }))
  })
  const countryLabel = computed(() => countryOptions.value.find(option => option.code === countryCode.value)?.label ?? countryCode.value)

  function setCountry(value: string) {
    if (isCountryCode(value)) countryCode.value = value
  }

  function setCurrency(value: string) {
    if (isCurrencyCode(value)) currencyCode.value = value
  }

  return {
    countryCode,
    currencyCode,
    countryLabel,
    countryOptions,
    currencyOptions,
    setCountry,
    setCurrency,
  }
}
