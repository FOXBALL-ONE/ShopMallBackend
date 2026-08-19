export const STOREFRONT_LOCALE_OPTIONS = [
  { code: 'en-US', label: 'English', shortLabel: 'EN' },
  { code: 'zh-CN', label: '简体中文', shortLabel: '中文' },
  { code: 'ru-RU', label: 'Русский', shortLabel: 'RU' },
] as const

export type StorefrontLocale = typeof STOREFRONT_LOCALE_OPTIONS[number]['code']

export function isStorefrontLocale(value: string | null | undefined): value is StorefrontLocale {
  return STOREFRONT_LOCALE_OPTIONS.some(option => option.code === value)
}

export function normalizeStorefrontLocale(value: string | null | undefined): StorefrontLocale {
  if (isStorefrontLocale(value)) return value
  if (value?.toLowerCase().startsWith('zh')) return 'zh-CN'
  if (value?.toLowerCase().startsWith('ru')) return 'ru-RU'
  return 'en-US'
}

export function useStorefrontI18n() {
  const { locale, setLocale, t, te, n, d } = useI18n()

  const currentLocale = computed<StorefrontLocale>(() => normalizeStorefrontLocale(locale.value))

  async function setStorefrontLocale(value: string) {
    const nextLocale = normalizeStorefrontLocale(value)
    if (nextLocale === currentLocale.value) return
    await setLocale(nextLocale)
  }

  function catalogCategoryName(code: string, fallback: string) {
    const key = `catalog.categories.${code}`
    return te(key) ? t(key) : fallback
  }

  function catalogProductTypeName(type: string) {
    const key = `catalogPage.productTypes.${type}`
    if (te(key)) return t(key)
    return type.toLocaleLowerCase().replaceAll('_', ' ').replace(/\b\w/g, letter => letter.toLocaleUpperCase())
  }

  function orderStatusLabel(status: string | null | undefined) {
    const key = `orderStatus.${status || 'UNKNOWN'}`
    if (te(key)) return t(key)
    return status ? status.replaceAll('_', ' ').toLocaleLowerCase() : t('orderStatus.UNKNOWN')
  }

  function formatMoney(value: number | string | null | undefined, currency = 'USD') {
    const amount = Number(value ?? 0)
    return n(Number.isFinite(amount) ? amount : 0, {
      style: 'currency',
      currency,
      currencyDisplay: 'symbol',
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })
  }

  function formatDate(value: string | Date | null | undefined, format: 'short' | 'long' = 'short') {
    if (!value) return ''
    const date = value instanceof Date ? value : new Date(value)
    if (Number.isNaN(date.getTime())) return typeof value === 'string' ? value : ''
    return d(date, format)
  }

  return {
    locale,
    currentLocale,
    setStorefrontLocale,
    catalogCategoryName,
    catalogProductTypeName,
    orderStatusLabel,
    formatMoney,
    formatDate,
    t,
  }
}
