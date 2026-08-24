<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import type {
  CustomerAddress,
  CustomerAddressInput,
  CustomerProfile,
  CustomerProfileUpdateInput
} from '~/types/customer-account'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import { customerInitials, formatAddressLine } from '~/utils/customer-display'
import { getIsoCountryOptions, isIsoCountryCode } from '~/utils/iso-countries'

definePageMeta({ middleware: ['customer-auth'] })

const { currentLocale, formatDate, setStorefrontLocale, t } = useStorefrontI18n()

useHead(() => ({
  title: t('accountProfile.seoTitle'),
  meta: [{ name: 'description', content: t('accountProfile.seoDescription') }]
}))

const api = useCustomerAccountApi()
const authApi = useCustomerAuthApi()
const session = useCustomerSession()
const toast = useToast()

const profile = ref<CustomerProfile | null>(null)
const addresses = ref<CustomerAddress[]>([])
const isLoading = ref(true)
const isSavingProfile = ref(false)
const isSavingAddress = ref(false)
const deletingAddressId = ref<string | null>(null)
const profileError = ref('')
const addressError = ref('')
const addressFormOpen = ref(false)
const editingAddressId = ref<string | null>(null)
const isSendingEmailCode = ref(false)
const isVerifyingEmail = ref(false)
const emailVerificationSent = ref(false)
const emailVerificationCode = ref('')
const emailVerificationError = ref('')
const emailCodeCooldown = ref(0)
const emailCodeInput = ref<HTMLInputElement | null>(null)
let emailCodeCooldownEndsAt = 0
let emailCodeCooldownTimer: ReturnType<typeof setInterval> | null = null

const isEmailCodeComplete = computed(() => /^\d{6}$/.test(emailVerificationCode.value.trim()))

const emailCodeButtonLabel = computed(() => {
  if (isSendingEmailCode.value) return t('accountProfile.emailVerification.sending')
  if (emailCodeCooldown.value > 0) return t('accountProfile.emailVerification.resendIn', { seconds: emailCodeCooldown.value })
  return emailVerificationSent.value
    ? t('accountProfile.emailVerification.sendAgain')
    : t('accountProfile.emailVerification.sendCode')
})

const profileForm = reactive({
  firstName: '',
  lastName: '',
  phone: '',
  birthday: '',
  locale: 'en-US',
  currency: 'USD',
  avatar: '',
  marketingConsent: false
})

const addressForm = reactive({
  label: '',
  name: '',
  phone: '',
  company: '',
  countryCode: 'US',
  stateOrProvince: '',
  city: '',
  district: '',
  postalCode: '',
  addressLine1: '',
  addressLine2: '',
  isDefault: false,
  deliveryInstructions: ''
})

const displayName = computed(() => {
  const fullName = [profile.value?.first_name, profile.value?.last_name].filter(Boolean).join(' ').trim()
  return fullName || profile.value?.username || t('accountProfile.memberFallback')
})
const initials = computed(() => customerInitials(profile.value?.first_name, profile.value?.last_name, 'P'))
const editingLabel = computed(() => editingAddressId.value ? t('accountProfile.editAddress') : t('accountProfile.addAddress'))
const countryOptions = computed(() => getIsoCountryOptions(currentLocale.value))
const countryCodeModel = computed({
  get: () => addressForm.countryCode,
  set: (value: string) => {
    addressForm.countryCode = value.replace(/[^a-z]/gi, '').slice(0, 2).toUpperCase()
  }
})
const filteredCountryOptions = computed(() => {
  const query = countryCodeModel.value.trim()
  if (!query) return countryOptions.value
  return countryOptions.value.filter(country => country.code.startsWith(query))
})
const countryCodeMenuUi = {
  base: 'country-code-input',
  trailing: 'country-code-trailing',
  content: 'country-code-content',
  viewport: 'country-code-viewport',
  group: 'country-code-group',
  item: 'country-code-item',
  empty: 'country-code-empty-wrap'
}
const addressPhonePattern = /^\+[1-9]\d{7,14}$/

function syncProfileForm(value: CustomerProfile) {
  profileForm.firstName = value.first_name || ''
  profileForm.lastName = value.last_name || ''
  profileForm.phone = value.phone || ''
  profileForm.birthday = value.birthday || ''
  profileForm.locale = normalizeStorefrontLocale(value.locale)
  profileForm.currency = value.currency || 'USD'
  profileForm.avatar = value.avatar || ''
  profileForm.marketingConsent = Boolean(value.marketing_consent)
}

function resetAddressForm() {
  Object.assign(addressForm, {
    label: '',
    name: `${profile.value?.first_name || ''} ${profile.value?.last_name || ''}`.trim(),
    phone: profile.value?.phone || '',
    company: '',
    countryCode: 'US',
    stateOrProvince: '',
    city: '',
    district: '',
    postalCode: '',
    addressLine1: '',
    addressLine2: '',
    isDefault: addresses.value.length === 0,
    deliveryInstructions: ''
  })
}

function openNewAddress() {
  editingAddressId.value = null
  resetAddressForm()
  addressError.value = ''
  addressFormOpen.value = true
}

function openEditAddress(address: CustomerAddress) {
  editingAddressId.value = address.id
  Object.assign(addressForm, {
    label: address.label || '',
    name: address.name,
    phone: address.phone,
    company: address.company || '',
    countryCode: address.country_code,
    stateOrProvince: address.state_or_province || '',
    city: address.city,
    district: address.district || '',
    postalCode: address.postal_code || '',
    addressLine1: address.address_line1,
    addressLine2: address.address_line2 || '',
    isDefault: address.is_default,
    deliveryInstructions: address.delivery_instructions || ''
  })
  addressError.value = ''
  addressFormOpen.value = true
}

function closeAddressForm() {
  if (isSavingAddress.value) return
  addressFormOpen.value = false
  editingAddressId.value = null
  addressError.value = ''
}

function buildProfileInput(): CustomerProfileUpdateInput {
  const current = profile.value
  const input: CustomerProfileUpdateInput = {
    first_name: profileForm.firstName.trim(),
    last_name: profileForm.lastName.trim(),
    phone: profileForm.phone.trim() || undefined,
    avatar: profileForm.avatar.trim() || undefined,
    locale: profileForm.locale.trim() || undefined,
    currency: profileForm.currency.trim().toUpperCase() || undefined,
    birthday: profileForm.birthday || undefined,
    // The backend treats omitted optional measurements as null. Preserve the
    // current values while this page edits the identity preferences.
    bust: current?.bust ?? null,
    waist: current?.waist ?? null,
    hip: current?.hip ?? null,
    torso: current?.torso ?? null,
    bra_size: current?.bra_size || undefined,
    cup_size: current?.cup_size || undefined,
    weight: current?.weight ?? null,
    weight_unit: current?.weight_unit || undefined,
    height: current?.height ?? null,
    length_unit: current?.length_unit || undefined,
    marketing_consent: profileForm.marketingConsent
  }
  return input
}

function validateProfile() {
  if (profileForm.phone.trim() && !addressPhonePattern.test(profileForm.phone.trim())) {
    profileError.value = t('accountProfile.errors.phone')
    return false
  }
  if (profileForm.currency.trim().length !== 3) {
    profileError.value = t('accountProfile.errors.currency')
    return false
  }
  return true
}

async function saveProfile() {
  const userId = session.userId.value
  profileError.value = ''
  if (!userId || !validateProfile()) return

  isSavingProfile.value = true
  try {
    const updated = await api.updateProfile(userId, buildProfileInput())
    if (profile.value) {
      profile.value = {
        ...profile.value,
        ...updated,
        first_name: updated.first_name,
        last_name: updated.last_name,
        phone: updated.phone,
        avatar: updated.avatar,
        locale: updated.locale,
        currency: updated.currency,
        birthday: updated.birthday,
        marketing_consent: updated.marketing_consent
      }
    }
    if (isStorefrontLocale(updated.locale)) await setStorefrontLocale(updated.locale)
    toast.add({ title: t('accountProfile.toast.profileSaved'), description: t('accountProfile.toast.profileSavedCopy'), color: 'success' })
  } catch (error: unknown) {
    profileError.value = customerRequestMessage(error, t('accountProfile.errors.saveProfile'))
    toast.add({ title: t('accountProfile.toast.profileNotSaved'), description: profileError.value, color: 'error' })
  } finally {
    isSavingProfile.value = false
  }
}

function startEmailCodeCooldown() {
  if (emailCodeCooldownTimer) clearInterval(emailCodeCooldownTimer)
  emailCodeCooldownEndsAt = Date.now() + 60_000
  emailCodeCooldown.value = 60
  emailCodeCooldownTimer = setInterval(() => {
    emailCodeCooldown.value = Math.max(0, Math.ceil((emailCodeCooldownEndsAt - Date.now()) / 1000))
    if (emailCodeCooldown.value <= 0 && emailCodeCooldownTimer) {
      clearInterval(emailCodeCooldownTimer)
      emailCodeCooldownTimer = null
    }
  }, 250)
}

function completeEmailVerification() {
  if (profile.value) profile.value.email_verified = true
  emailVerificationCode.value = ''
  emailVerificationSent.value = false
  emailVerificationError.value = ''
  emailCodeCooldown.value = 0
  emailCodeCooldownEndsAt = 0
  if (emailCodeCooldownTimer) {
    clearInterval(emailCodeCooldownTimer)
    emailCodeCooldownTimer = null
  }
}

function updateEmailVerificationCode(event: Event) {
  const input = event.target as HTMLInputElement
  const normalized = input.value.replace(/\D/g, '').slice(0, 6)
  input.value = normalized
  emailVerificationCode.value = normalized
  emailVerificationError.value = ''
}

async function sendEmailVerificationCode() {
  if (isSendingEmailCode.value || emailCodeCooldown.value > 0 || profile.value?.email_verified) return
  emailVerificationError.value = ''
  isSendingEmailCode.value = true
  try {
    const response = await authApi.sendEmailVerificationCode()
    if (response.data.email_verified) {
      completeEmailVerification()
      toast.add({
        title: t('accountProfile.emailVerification.verified'),
        description: t('accountProfile.emailVerification.verifiedCopy'),
        color: 'success'
      })
      return
    }
    emailVerificationSent.value = true
    startEmailCodeCooldown()
    await nextTick()
    emailCodeInput.value?.focus()
    toast.add({
      title: t('accountProfile.emailVerification.codeSent'),
      description: t('accountProfile.emailVerification.codeSentCopy', { email: profile.value?.email || '' }),
      color: 'success'
    })
  } catch (error: unknown) {
    emailVerificationError.value = customerRequestMessage(error, t('accountProfile.errors.sendEmailCode'))
    toast.add({ title: t('accountProfile.emailVerification.codeNotSent'), description: emailVerificationError.value, color: 'error' })
  } finally {
    isSendingEmailCode.value = false
  }
}

async function verifyEmail() {
  emailVerificationError.value = ''
  const code = emailVerificationCode.value.trim()
  if (!isEmailCodeComplete.value) {
    emailVerificationError.value = t('accountProfile.errors.emailCode')
    return
  }
  if (isVerifyingEmail.value || profile.value?.email_verified) return

  isVerifyingEmail.value = true
  try {
    await authApi.verifyEmail(code)
    completeEmailVerification()
    toast.add({
      title: t('accountProfile.emailVerification.verified'),
      description: t('accountProfile.emailVerification.verifiedCopy'),
      color: 'success'
    })
  } catch (error: unknown) {
    emailVerificationError.value = customerRequestMessage(error, t('accountProfile.errors.verifyEmail'))
    toast.add({ title: t('accountProfile.emailVerification.notVerified'), description: emailVerificationError.value, color: 'error' })
  } finally {
    isVerifyingEmail.value = false
  }
}

function buildAddressInput(): CustomerAddressInput {
  return {
    label: addressForm.label.trim() || undefined,
    name: addressForm.name.trim(),
    phone: addressForm.phone.trim(),
    company: addressForm.company.trim() || undefined,
    country_code: addressForm.countryCode.trim().toUpperCase(),
    state_or_province: addressForm.stateOrProvince.trim() || undefined,
    city: addressForm.city.trim(),
    district: addressForm.district.trim() || undefined,
    postal_code: addressForm.postalCode.trim() || undefined,
    address_line1: addressForm.addressLine1.trim(),
    address_line2: addressForm.addressLine2.trim() || undefined,
    is_default: addressForm.isDefault,
    delivery_instructions: addressForm.deliveryInstructions.trim() || undefined
  }
}

function validateAddress() {
  const input = buildAddressInput()
  if (!input.name || !input.city || !input.address_line1) {
    addressError.value = t('accountProfile.errors.addressRequired')
    return false
  }
  if (!addressPhonePattern.test(input.phone)) {
    addressError.value = t('accountProfile.errors.addressPhone')
    return false
  }
  if (!isIsoCountryCode(input.country_code)) {
    addressError.value = t('accountProfile.errors.countryCode')
    return false
  }
  return true
}

async function saveAddress() {
  addressError.value = ''
  if (!validateAddress()) return

  const wasEditing = Boolean(editingAddressId.value)
  isSavingAddress.value = true
  try {
    const input = buildAddressInput()
    if (editingAddressId.value) {
      await api.updateAddress(editingAddressId.value, input)
    } else {
      await api.createAddress(input)
    }
    await loadAddresses()
    isSavingAddress.value = false
    closeAddressForm()
    toast.add({
      title: wasEditing ? t('accountProfile.toast.addressUpdated') : t('accountProfile.toast.addressSaved'),
      description: t('accountProfile.toast.addressSavedCopy'),
      color: 'success'
    })
  } catch (error: unknown) {
    addressError.value = customerRequestMessage(error, t('accountProfile.errors.saveAddress'))
    toast.add({ title: t('accountProfile.toast.addressNotSaved'), description: addressError.value, color: 'error' })
  } finally {
    isSavingAddress.value = false
  }
}

async function removeAddress(address: CustomerAddress) {
  if (deletingAddressId.value) return
  if (import.meta.client && !window.confirm(t('accountProfile.removeConfirm', { label: address.label || t('accountProfile.savedAddress') }))) return

  deletingAddressId.value = address.id
  addressError.value = ''
  try {
    await api.deleteAddress(address.id)
    addresses.value = addresses.value.filter(item => item.id !== address.id)
    toast.add({ title: t('accountProfile.toast.addressRemoved'), description: t('accountProfile.toast.addressRemovedCopy'), color: 'success' })
  } catch (error: unknown) {
    addressError.value = customerRequestMessage(error, t('accountProfile.errors.removeAddress'))
    toast.add({ title: t('accountProfile.toast.addressNotRemoved'), description: addressError.value, color: 'error' })
  } finally {
    deletingAddressId.value = null
  }
}

async function loadAddresses() {
  const result = await api.getAddresses()
  addresses.value = result.list || []
}

async function loadProfilePage() {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }

  isLoading.value = true
  profileError.value = ''
  addressError.value = ''
  try {
    const [profileResult, addressResult] = await Promise.all([api.getProfile(userId), api.getAddresses()])
    profile.value = profileResult
    syncProfileForm(profileResult)
    if (isStorefrontLocale(profileResult.locale)) await setStorefrontLocale(profileResult.locale)
    addresses.value = addressResult.list || []
  } catch (error: unknown) {
    profileError.value = customerRequestMessage(error, t('accountProfile.errors.load'))
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  void loadProfilePage()
})

onBeforeUnmount(() => {
  if (emailCodeCooldownTimer) {
    clearInterval(emailCodeCooldownTimer)
    emailCodeCooldownTimer = null
  }
})
</script>
<template>
  <CustomerAccountShell
    :eyebrow="t('accountProfile.eyebrow')"
    :title="t('accountProfile.title')"
    :intro="t('accountProfile.intro')"
    :profile="profile"
  >
    <div v-if="isLoading" class="profile-loading" aria-live="polite">
      <div class="profile-skeleton profile-skeleton-wide" />
      <div class="profile-skeleton-grid">
        <div v-for="index in 4" :key="index" class="profile-skeleton" />
      </div>
      <div class="profile-skeleton profile-skeleton-panel" />
    </div>

    <template v-else>
      <div v-if="profileError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" />
        <span>{{ profileError }}</span>
        <button type="button" @click="loadProfilePage">{{ t('accountProfile.refresh') }}</button>
      </div>

      <section v-if="profile" class="profile-intro-panel">
        <div class="profile-intro-avatar">
          <img v-if="profile.avatar" :src="profile.avatar" :alt="displayName">
          <span v-else>{{ initials }}</span>
        </div>
        <div>
          <p class="store-eyebrow">{{ t('accountProfile.identityEyebrow') }}</p>
          <h2>{{ displayName }}</h2>
          <p>{{ t('accountProfile.memberSince', { date: profile.created_at ? formatDate(profile.created_at) : t('accountProfile.today'), username: profile.username }) }}</p>
        </div>
        <div class="profile-intro-email">
          <span>{{ t('accountProfile.email') }}</span>
          <strong>{{ profile.email }}</strong>
          <small :class="profile.email_verified ? 'is-verified' : 'is-unverified'">
            <UIcon :name="profile.email_verified ? 'i-lucide-badge-check' : 'i-lucide-circle-alert'" />
            {{ profile.email_verified ? t('accountProfile.verifiedEmail') : t('accountProfile.unverifiedEmail') }}
          </small>
          <div v-if="!profile.email_verified" class="email-verification">
            <p>{{ t('accountProfile.emailVerification.copy') }}</p>
            <div class="email-verification-actions">
              <input
                ref="emailCodeInput"
                :value="emailVerificationCode"
                type="text"
                inputmode="numeric"
                autocomplete="one-time-code"
                maxlength="6"
                :placeholder="t('accountProfile.emailVerification.codePlaceholder')"
                :aria-label="t('accountProfile.emailVerification.codeLabel')"
                :aria-invalid="Boolean(emailVerificationError)"
                aria-describedby="email-verification-help email-verification-feedback"
                @input="updateEmailVerificationCode"
                @keyup.enter="verifyEmail"
              >
              <button type="button" class="email-code-button" :disabled="isSendingEmailCode || emailCodeCooldown > 0" @click="sendEmailVerificationCode">
                <UIcon :name="isSendingEmailCode ? 'i-lucide-loader-circle' : 'i-lucide-mail'" :class="{ 'is-spinning': isSendingEmailCode }" />
                {{ emailCodeButtonLabel }}
              </button>
              <button type="button" class="email-verify-button" :disabled="isVerifyingEmail || isSendingEmailCode || !isEmailCodeComplete" :title="t('accountProfile.emailVerification.verify')" @click="verifyEmail">
                <UIcon :name="isVerifyingEmail ? 'i-lucide-loader-circle' : 'i-lucide-check'" :class="{ 'is-spinning': isVerifyingEmail }" />
                <span>{{ t('accountProfile.emailVerification.verify') }}</span>
              </button>
            </div>
            <span id="email-verification-help" class="email-verification-help">
              {{ emailVerificationSent ? t('accountProfile.emailVerification.sentHint') : t('accountProfile.emailVerification.sendHint') }}
            </span>
            <span id="email-verification-feedback" class="email-verification-error" role="alert" aria-live="polite">{{ emailVerificationError }}</span>
          </div>
        </div>
      </section>

      <section class="account-panel profile-panel">
        <div class="panel-heading-row">
          <div>
            <p class="panel-kicker">{{ t('accountProfile.profileKicker') }}</p>
            <h2>{{ t('accountProfile.personalDetails') }}</h2>
            <p class="panel-description">{{ t('accountProfile.personalDescription') }}</p>
          </div>
          <span class="panel-index">{{ t('accountProfile.editIndex') }}</span>
        </div>

        <form class="profile-form" @submit.prevent="saveProfile">
          <div class="form-section-label"><span>{{ t('accountProfile.identity') }}</span><i /></div>
          <div class="form-grid form-grid-two">
            <label class="field-label">
              <span>{{ t('accountProfile.firstName') }}</span>
              <input v-model="profileForm.firstName" type="text" maxlength="50" autocomplete="given-name" :placeholder="t('accountProfile.firstNamePlaceholder')">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.lastName') }}</span>
              <input v-model="profileForm.lastName" type="text" maxlength="50" autocomplete="family-name" :placeholder="t('accountProfile.lastNamePlaceholder')">
            </label>
            <label class="field-label field-span-two">
              <span>{{ t('accountProfile.email') }} <small>{{ profile?.email_verified ? t('accountProfile.verifiedAccountEmail') : t('accountProfile.accountEmail') }}</small></span>
              <div class="input-with-icon">
                <input :value="profile?.email || ''" type="email" readonly aria-readonly="true">
                <UIcon name="i-lucide-lock-keyhole" />
              </div>
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.username') }}</span>
              <input :value="profile?.username || ''" type="text" readonly aria-readonly="true">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.phone') }} <small>{{ t('accountProfile.internationalFormat') }}</small></span>
              <input v-model="profileForm.phone" type="tel" maxlength="16" autocomplete="tel" placeholder="+14155550123">
            </label>
          </div>

          <div class="form-section-label"><span>{{ t('accountProfile.preferences') }}</span><i /></div>
          <div class="form-grid form-grid-three">
            <label class="field-label">
              <span>{{ t('accountProfile.birthday') }}</span>
              <input v-model="profileForm.birthday" type="date" autocomplete="bday">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.language') }}</span>
              <select v-model="profileForm.locale">
                <option v-for="option in STOREFRONT_LOCALE_OPTIONS" :key="option.code" :value="option.code">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.currency') }}</span>
              <select v-model="profileForm.currency">
                <option value="USD">USD · $</option>
                <option value="CNY">CNY · ¥</option>
                <option value="EUR">EUR · €</option>
                <option value="GBP">GBP · £</option>
              </select>
            </label>
            <label class="field-label field-span-three">
              <span>{{ t('accountProfile.avatarUrl') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="profileForm.avatar" type="url" maxlength="512" placeholder="https://…">
            </label>
          </div>

          <label class="consent-row">
            <input v-model="profileForm.marketingConsent" type="checkbox">
            <span class="consent-check"><UIcon name="i-lucide-check" /></span>
            <span><strong>{{ t('accountProfile.marketingTitle') }}</strong><small>{{ t('accountProfile.marketingCopy') }}</small></span>
          </label>

          <div v-if="profileError" class="inline-error"><UIcon name="i-lucide-circle-alert" /> {{ profileError }}</div>
          <div class="form-actions">
            <span class="form-hint">{{ t('accountProfile.changesHint') }}</span>
            <button class="store-button" type="submit" :disabled="isSavingProfile">
              <UIcon :name="isSavingProfile ? 'i-lucide-loader-circle' : 'i-lucide-save'" :class="{ 'is-spinning': isSavingProfile }" />
              {{ isSavingProfile ? t('accountProfile.saving') : t('accountProfile.saveDetails') }}
            </button>
          </div>
        </form>
      </section>

      <section id="addresses" class="account-panel address-panel">
        <div class="panel-heading-row">
          <div>
            <p class="panel-kicker">{{ t('accountProfile.deliveryKicker') }}</p>
            <h2>{{ t('accountProfile.savedAddressesTitle') }}</h2>
            <p class="panel-description">{{ t('accountProfile.savedAddressesCopy') }}</p>
          </div>
          <button class="outline-button" type="button" @click="addressFormOpen ? closeAddressForm() : openNewAddress()">
            <UIcon :name="addressFormOpen ? 'i-lucide-x' : 'i-lucide-plus'" />
            {{ addressFormOpen ? t('accountProfile.close') : t('accountProfile.addAddressButton') }}
          </button>
        </div>

        <div v-if="addressError && !addressFormOpen" class="inline-error"><UIcon name="i-lucide-circle-alert" /> {{ addressError }}</div>

        <div v-if="addressFormOpen" class="address-form-wrap">
          <div class="address-form-heading">
            <div>
              <span class="panel-kicker">{{ editingAddressId ? t('accountProfile.editingSavedAddress') : t('accountProfile.newSavedAddress') }}</span>
              <h3>{{ editingLabel }}</h3>
            </div>
            <span class="address-form-mark">P°</span>
          </div>
          <form class="address-form" @submit.prevent="saveAddress">
            <label class="field-label">
              <span>{{ t('accountProfile.label') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="addressForm.label" type="text" maxlength="30" :placeholder="t('accountProfile.labelPlaceholder')">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.recipientName') }}</span>
              <input v-model="addressForm.name" type="text" maxlength="100" autocomplete="name" required>
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.phone') }} <small>E.164</small></span>
              <input v-model="addressForm.phone" type="tel" maxlength="16" autocomplete="tel" placeholder="+14155550123" required>
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.company') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="addressForm.company" type="text" maxlength="100" autocomplete="organization">
            </label>
            <div class="field-label country-code-field">
              <label for="address-country-code">
                <span>{{ t('accountProfile.countryCode') }}</span>
                <small>ISO 3166-1</small>
              </label>
              <UInputMenu
                id="address-country-code"
                v-model="countryCodeModel"
                mode="autocomplete"
                :items="filteredCountryOptions"
                :ui="countryCodeMenuUi"
                value-key="code"
                label-key="label"
                ignore-filter
                open-on-click
                open-on-focus
                autocomplete="country"
                maxlength="2"
                :placeholder="t('accountProfile.typeCode')"
                required
                variant="none"
                class="country-code-menu"
              >
                <template #trailing="{ open }">
                  <UIcon name="i-lucide-chevron-down" class="country-code-chevron" :class="{ 'is-open': open }" />
                </template>
                <template #content-top>
                  <div class="country-code-menu-heading">
                    <span>{{ t('accountProfile.countryRegion') }}</span>
                    <strong>{{ filteredCountryOptions.length }}</strong>
                  </div>
                </template>
                <template #item="{ item }">
                  <span class="country-option-code">{{ item.code }}</span>
                  <span class="country-option-copy">
                    <strong>{{ item.name }}</strong>
                    <small>{{ t('accountProfile.isoAlpha2') }}</small>
                  </span>
                  <UIcon v-if="item.code === countryCodeModel" name="i-lucide-check" class="country-option-check" />
                </template>
                <template #empty>
                  <span class="country-code-empty-icon"><UIcon name="i-lucide-search-x" /></span>
                  <strong>{{ t('accountProfile.noMatchingCode') }}</strong>
                  <small>{{ t('accountProfile.noMatchingCodeCopy') }}</small>
                </template>
              </UInputMenu>
            </div>
            <label class="field-label">
              <span>{{ t('accountProfile.stateProvince') }}</span>
              <input v-model="addressForm.stateOrProvince" type="text" maxlength="100" autocomplete="address-level1">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.city') }}</span>
              <input v-model="addressForm.city" type="text" maxlength="100" autocomplete="address-level2" required>
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.district') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="addressForm.district" type="text" maxlength="100">
            </label>
            <label class="field-label">
              <span>{{ t('accountProfile.postalCode') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="addressForm.postalCode" type="text" maxlength="20" autocomplete="postal-code">
            </label>
            <label class="field-label field-span-two">
              <span>{{ t('accountProfile.addressLine1') }}</span>
              <input v-model="addressForm.addressLine1" type="text" maxlength="255" autocomplete="address-line1" required>
            </label>
            <label class="field-label field-span-two">
              <span>{{ t('accountProfile.addressLine2') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <input v-model="addressForm.addressLine2" type="text" maxlength="255" autocomplete="address-line2">
            </label>
            <label class="field-label field-span-two">
              <span>{{ t('accountProfile.deliveryInstructions') }} <small>{{ t('accountProfile.optional') }}</small></span>
              <textarea v-model="addressForm.deliveryInstructions" maxlength="500" rows="2" :placeholder="t('accountProfile.deliveryInstructionsPlaceholder')" />
            </label>
            <label class="consent-row address-default-row">
              <input v-model="addressForm.isDefault" type="checkbox">
              <span class="consent-check"><UIcon name="i-lucide-check" /></span>
              <span><strong>{{ t('accountProfile.makeDefault') }}</strong><small>{{ t('accountProfile.makeDefaultCopy') }}</small></span>
            </label>
            <div v-if="addressError" class="inline-error field-span-two"><UIcon name="i-lucide-circle-alert" /> {{ addressError }}</div>
            <div class="form-actions field-span-two">
              <button class="text-button" type="button" :disabled="isSavingAddress" @click="closeAddressForm">{{ t('accountProfile.cancel') }}</button>
              <button class="store-button" type="submit" :disabled="isSavingAddress">
                <UIcon :name="isSavingAddress ? 'i-lucide-loader-circle' : 'i-lucide-check'" :class="{ 'is-spinning': isSavingAddress }" />
                {{ isSavingAddress ? t('accountProfile.saving') : editingAddressId ? t('accountProfile.updateAddress') : t('accountProfile.saveAddress') }}
              </button>
            </div>
          </form>
        </div>

        <div v-if="addresses.length" class="address-grid">
          <article v-for="address in addresses" :key="address.id" class="address-card" :class="{ 'is-default': address.is_default }">
            <div class="address-card-top">
              <span class="address-label">{{ address.label || t('accountProfile.savedAddress') }}</span>
              <span v-if="address.is_default" class="default-badge"><UIcon name="i-lucide-star" /> {{ t('accountProfile.default') }}</span>
            </div>
            <h3>{{ address.name }}</h3>
            <p>{{ address.phone }}</p>
            <p class="address-line">{{ formatAddressLine(address) }}</p>
            <p v-if="address.delivery_instructions" class="address-note"><UIcon name="i-lucide-message-circle" /> {{ address.delivery_instructions }}</p>
            <div class="address-card-actions">
              <button class="text-button" type="button" @click="openEditAddress(address)"><UIcon name="i-lucide-pencil" /> {{ t('accountProfile.edit') }}</button>
              <button class="text-button text-button-danger" type="button" :disabled="deletingAddressId === address.id" @click="removeAddress(address)">
                <UIcon :name="deletingAddressId === address.id ? 'i-lucide-loader-circle' : 'i-lucide-trash-2'" :class="{ 'is-spinning': deletingAddressId === address.id }" />
                {{ deletingAddressId === address.id ? t('accountProfile.removing') : t('accountProfile.remove') }}
              </button>
            </div>
          </article>
        </div>
        <div v-else-if="!addressFormOpen" class="empty-addresses">
          <span class="empty-mark">02</span>
          <div>
            <h3>{{ t('accountProfile.emptyTitle') }}</h3>
            <p>{{ t('accountProfile.emptyCopy') }}</p>
          </div>
          <button class="outline-button" type="button" @click="openNewAddress"><UIcon name="i-lucide-plus" /> {{ t('accountProfile.addFirstAddress') }}</button>
        </div>
      </section>
    </template>
  </CustomerAccountShell>
</template>
<style scoped>
.profile-loading {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.profile-skeleton {
  position: relative;
  min-height: 84px;
  overflow: hidden;
  border: 1px solid rgba(36, 29, 33, .08);
  background: rgba(255, 255, 255, .52);
}

.profile-skeleton::after,
.profile-skeleton-wide::after,
.profile-skeleton-panel::after {
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, .62), transparent);
  content: '';
  animation: profile-shimmer 1.5s infinite;
}

.profile-skeleton-wide { min-height: 122px; }
.profile-skeleton-panel { min-height: 430px; }
.profile-skeleton-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }

@keyframes profile-shimmer {
  from { transform: translateX(-100%); }
  to { transform: translateX(100%); }
}

.account-notice {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  padding: 12px 14px;
  border: 1px solid var(--store-line);
  color: var(--store-muted);
  background: rgba(255, 255, 255, .58);
  font-size: 12px;
  line-height: 1.5;
}

.account-notice > .iconify { flex: 0 0 auto; width: 16px; height: 16px; color: var(--store-wine); }
.account-notice span { flex: 1; }
.account-notice button { padding: 0; border: 0; color: var(--store-wine-dark); background: none; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }

.profile-intro-panel {
  min-height: 113px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 17px;
  margin-bottom: 20px;
  padding: 20px 22px;
  border: 1px solid var(--store-line);
  background: rgba(255, 255, 255, .64);
}

.profile-intro-avatar {
  width: 68px;
  height: 68px;
  display: grid;
  place-items: center;
  overflow: hidden;
  border-radius: 50%;
  color: #fff;
  background: var(--store-wine);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 25px;
}

.profile-intro-avatar img { width: 100%; height: 100%; object-fit: cover; }
.profile-intro-panel h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 29px; font-weight: 500; letter-spacing: -.03em; line-height: 1; }
.profile-intro-panel p:not(.store-eyebrow) { margin: 8px 0 0; color: var(--store-muted); font-size: 11px; }
.profile-intro-email { min-width: 190px; padding-left: 20px; border-left: 1px solid var(--store-line); }
.profile-intro-email > span,
.profile-intro-email > small { display: block; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .08em; text-transform: uppercase; }
.profile-intro-email > span { color: var(--store-muted); }
.profile-intro-email strong { display: block; overflow: hidden; margin: 8px 0; text-overflow: ellipsis; font-size: 12px; font-weight: 600; }
.profile-intro-email small { display: inline-flex; align-items: center; gap: 5px; }
.profile-intro-email small .iconify { width: 12px; height: 12px; }
.is-verified { color: #66816a; }
.is-unverified { color: var(--store-wine); }
.email-verification { width: min(330px, 100%); margin-top: 13px; padding-top: 12px; border-top: 1px solid var(--store-line); }
.profile-intro-panel .email-verification > p { margin: 0 0 9px; color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.email-verification-actions { display: grid; grid-template-columns: minmax(82px, .72fr) minmax(126px, 1.28fr); gap: 7px; }
.email-verification-actions input { width: 100%; min-width: 0; min-height: 36px; box-sizing: border-box; padding: 0 9px; border: 1px solid rgba(36, 29, 33, .19); border-radius: 0; outline: 0; color: var(--store-ink); background: rgba(251, 247, 245, .82); font-family: 'DM Mono', monospace; font-size: 11px; letter-spacing: 0; }
.email-verification-actions input:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154, 64, 85, .1); background: #fff; }
.email-code-button,
.email-verify-button { min-height: 36px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 9px; border: 1px solid var(--store-wine); border-radius: 0; color: var(--store-wine); background: transparent; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: 0; text-transform: uppercase; }
.email-verify-button { grid-column: 1 / -1; color: #fff; background: var(--store-wine); }
.email-code-button:disabled,
.email-verify-button:disabled { cursor: not-allowed; opacity: .55; }
.email-code-button:disabled:has(.is-spinning),
.email-verify-button:disabled:has(.is-spinning) { cursor: wait; }
.email-code-button .iconify,
.email-verify-button .iconify { width: 13px; height: 13px; flex: 0 0 auto; }
.email-verification-help,
.email-verification-error { min-height: 15px; display: block; margin-top: 7px; font-size: 9px; line-height: 1.45; }
.email-verification-help { color: var(--store-muted); }
.email-verification-error { color: var(--store-wine); }

.account-panel {
  margin-bottom: 20px;
  padding: 26px 27px 28px;
  border: 1px solid var(--store-line);
  background: rgba(255, 255, 255, .66);
}

.panel-heading-row { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 27px; }
.panel-kicker { margin: 0 0 8px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .1em; text-transform: uppercase; }
.panel-heading-row h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(28px, 3vw, 39px); font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.panel-description { max-width: 520px; margin: 10px 0 0; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.panel-index { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }

.profile-form,
.address-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px 16px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 17px 16px; grid-column: 1 / -1; }
.form-grid-three { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.form-grid-two { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.form-section-label { grid-column: 1 / -1; display: flex; align-items: center; gap: 12px; margin-top: 2px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }
.form-section-label i { height: 1px; flex: 1; background: var(--store-line); }
.field-span-two { grid-column: span 2; }
.field-span-three { grid-column: 1 / -1; }
.field-label { min-width: 0; display: flex; flex-direction: column; gap: 8px; color: var(--store-ink); font-size: 11px; font-weight: 600; }
.field-label > span,
.field-label > label { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.field-label small { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 7px; font-weight: 400; letter-spacing: .05em; text-transform: uppercase; }
.field-label input,
.field-label select,
.field-label textarea { width: 100%; min-height: 43px; box-sizing: border-box; border: 1px solid rgba(36, 29, 33, .19); border-radius: 0; outline: 0; color: var(--store-ink); background: rgba(251, 247, 245, .82); font-size: 12px; transition: border-color .2s ease, box-shadow .2s ease, background .2s ease; }
.field-label input,
.field-label select { padding: 0 12px; }
.field-label textarea { min-height: 74px; padding: 11px 12px; resize: vertical; line-height: 1.5; }
.field-label input::placeholder,
.field-label textarea::placeholder { color: #aaa0a4; }
.field-label input:focus,
.field-label select:focus,
.field-label textarea:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154, 64, 85, .1); background: #fff; }
.field-label input[readonly] { color: var(--store-muted); background: rgba(232, 224, 224, .48); cursor: not-allowed; }
.country-code-field { position: relative; }
.country-code-menu { position: relative; width: 100%; }
.country-code-menu :deep(.country-code-input) { width: 100%; min-height: 43px; box-sizing: border-box; padding: 0 38px 0 12px; border: 1px solid rgba(36, 29, 33, .19); border-radius: 0; outline: 0; color: var(--store-ink); background: rgba(251, 247, 245, .82); box-shadow: none; font-family: inherit; font-size: 12px; font-weight: 400; letter-spacing: normal; text-transform: uppercase; transition: border-color .2s ease, box-shadow .2s ease, background .2s ease; }
.country-code-menu :deep(.country-code-input:focus) { border-color: var(--store-wine); background: #fff; box-shadow: 0 0 0 3px rgba(154, 64, 85, .1); }
.country-code-menu :deep(.country-code-input::placeholder) { color: #aaa0a4; font-family: inherit; font-size: 12px; font-weight: 400; letter-spacing: normal; text-transform: none; }
.country-code-menu :deep(.country-code-trailing) { inset-inline-end: 0; padding-inline-end: 12px; }
.country-code-chevron { width: 16px; height: 16px; color: var(--store-muted); transition: color .2s ease, transform .2s ease; }
.country-code-chevron.is-open { color: var(--store-wine); transform: rotate(180deg); }
:global(.country-code-content) { z-index: 90; max-height: min(340px, var(--reka-combobox-content-available-height, 340px)); border: 1px solid rgba(36, 29, 33, .16); border-radius: 0; background: #fffaf8; box-shadow: 0 18px 45px rgba(47, 31, 37, .16), 0 4px 12px rgba(47, 31, 37, .08); }
:global(.country-code-menu-heading) { display: flex; align-items: center; justify-content: space-between; padding: 11px 13px 9px; border-bottom: 1px solid rgba(36, 29, 33, .09); color: var(--store-muted); background: rgba(241, 232, 231, .5); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .09em; text-transform: uppercase; }
:global(.country-code-menu-heading strong) { min-width: 24px; padding: 2px 5px; color: var(--store-wine); background: rgba(154, 64, 85, .1); font-size: 8px; font-weight: 500; text-align: center; }
:global(.country-code-viewport) { padding: 6px; scroll-padding-block: 6px; }
:global(.country-code-group) { padding: 0; }
:global(.country-code-item) { min-height: 51px; align-items: center; gap: 11px; padding: 7px 9px; border-bottom: 1px solid rgba(36, 29, 33, .055); border-radius: 0; color: var(--store-ink); cursor: pointer; }
:global(.country-code-item:last-child) { border-bottom: 0; }
:global(.country-code-item[data-highlighted]) { color: var(--store-wine); }
:global(.country-code-item[data-highlighted]::before) { inset: 0; border-radius: 0; background: rgba(154, 64, 85, .085); }
:global(.country-option-code) { min-width: 42px; padding: 7px 8px; border: 1px solid rgba(154, 64, 85, .22); color: var(--store-wine); background: rgba(255, 255, 255, .72); font-family: 'DM Mono', monospace; font-size: 11px; font-weight: 600; letter-spacing: .12em; text-align: center; }
:global(.country-option-copy) { min-width: 0; display: flex; flex: 1; flex-direction: column; gap: 2px; }
:global(.country-option-copy strong) { overflow: hidden; font-family: 'Playfair Display', Georgia, serif; font-size: 14px; font-weight: 500; letter-spacing: -.01em; text-overflow: ellipsis; white-space: nowrap; }
:global(.country-option-copy small) { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 7px; font-weight: 400; letter-spacing: .06em; text-transform: uppercase; }
:global(.country-option-check) { width: 15px; height: 15px; flex: 0 0 auto; color: var(--store-wine); }
:global(.country-code-empty-wrap) { display: flex; min-height: 130px; align-items: center; justify-content: center; flex-direction: column; gap: 5px; padding: 18px; color: var(--store-muted); text-align: center; }
:global(.country-code-empty-wrap strong) { color: var(--store-ink); font-family: 'Playfair Display', Georgia, serif; font-size: 16px; font-weight: 500; }
:global(.country-code-empty-wrap small) { font-size: 9px; line-height: 1.5; }
:global(.country-code-empty-icon) { width: 32px; height: 32px; display: grid; place-items: center; margin-bottom: 3px; border: 1px solid rgba(154, 64, 85, .2); color: var(--store-wine); background: rgba(154, 64, 85, .06); }
:global(.country-code-empty-icon .iconify) { width: 15px; height: 15px; }
.input-with-icon { position: relative; }
.input-with-icon input { padding-right: 38px; }
.input-with-icon .iconify { position: absolute; top: 50%; right: 13px; width: 14px; height: 14px; color: var(--store-muted); transform: translateY(-50%); }

.consent-row { grid-column: 1 / -1; display: flex; align-items: flex-start; gap: 10px; margin-top: 3px; cursor: pointer; }
.consent-row input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.consent-check { width: 18px; height: 18px; flex: 0 0 auto; display: grid; place-items: center; border: 1px solid var(--store-muted); color: transparent; background: transparent; transition: color .2s ease, background .2s ease, border-color .2s ease; }
.consent-row input:checked + .consent-check { border-color: var(--store-wine); color: #fff; background: var(--store-wine); }
.consent-check .iconify { width: 12px; height: 12px; }
.consent-row > span:last-child { display: flex; flex-direction: column; gap: 3px; }
.consent-row strong { font-size: 11px; font-weight: 600; }
.consent-row small { color: var(--store-muted); font-size: 10px; line-height: 1.5; }

.form-actions { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 4px; padding-top: 19px; border-top: 1px solid var(--store-line); }
.form-hint { color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.form-actions .store-button { min-width: 152px; }
.store-button:disabled,
.outline-button:disabled,
.text-button:disabled { cursor: wait; opacity: .55; }
.is-spinning { animation: profile-spin .8s linear infinite; }
@keyframes profile-spin { to { transform: rotate(360deg); } }

.outline-button,
.text-button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--store-ink); color: var(--store-ink); background: transparent; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-decoration: none; text-transform: uppercase; transition: color .2s ease, background .2s ease, border-color .2s ease; }
.outline-button { min-height: 39px; padding: 0 13px; }
.outline-button:hover:not(:disabled) { color: #fff; background: var(--store-ink); }
.outline-button .iconify,
.text-button .iconify { width: 14px; height: 14px; }
.text-button { padding: 0; border: 0; color: var(--store-muted); background: none; }
.text-button:hover:not(:disabled) { color: var(--store-wine); }
.text-button-danger:hover:not(:disabled) { color: #a33e4a; }
.inline-error { grid-column: 1 / -1; display: flex; align-items: flex-start; gap: 7px; color: #9a4055; font-size: 11px; line-height: 1.5; }
.inline-error .iconify { width: 15px; height: 15px; flex: 0 0 auto; }

.address-form-wrap { margin-bottom: 25px; padding: 21px 20px 22px; border: 1px solid rgba(154, 64, 85, .35); background: rgba(241, 232, 231, .46); }
.address-form-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; margin-bottom: 22px; }
.address-form-heading h3 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 27px; font-weight: 500; letter-spacing: -.03em; }
.address-form-mark { color: var(--store-wine); font-family: 'Playfair Display', Georgia, serif; font-size: 32px; }
.address-form { gap: 15px; }
.address-default-row { margin-top: 1px; }

.address-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 15px; }
.address-card { position: relative; min-height: 208px; display: flex; flex-direction: column; padding: 19px 19px 16px; border: 1px solid var(--store-line); background: rgba(255, 255, 255, .58); }
.address-card.is-default { border-color: rgba(154, 64, 85, .65); }
.address-card-top { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 17px; }
.address-label { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }
.default-badge { display: inline-flex; align-items: center; gap: 4px; color: #66816a; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; text-transform: uppercase; }
.default-badge .iconify { width: 12px; height: 12px; }
.address-card h3 { margin: 17px 0 5px; font-family: 'Playfair Display', Georgia, serif; font-size: 24px; font-weight: 500; letter-spacing: -.025em; line-height: 1; }
.address-card > p { margin: 0; color: var(--store-muted); font-size: 11px; }
.address-card .address-line { max-width: 340px; margin-top: 13px; color: var(--store-ink); font-size: 11px; line-height: 1.55; }
.address-note { display: flex; align-items: flex-start; gap: 6px; margin-top: 11px !important; font-size: 10px !important; font-style: italic; }
.address-note .iconify { width: 13px; height: 13px; flex: 0 0 auto; color: var(--store-wine); }
.address-card-actions { display: flex; gap: 16px; margin-top: auto; padding-top: 17px; }

.empty-addresses { min-height: 125px; display: flex; align-items: center; gap: 18px; padding: 20px; border: 1px dashed var(--store-line); background: rgba(241, 232, 231, .3); }
.empty-mark { color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 44px; line-height: 1; }
.empty-addresses h3 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 24px; font-weight: 500; }
.empty-addresses p { max-width: 390px; margin: 7px 0 0; color: var(--store-muted); font-size: 11px; line-height: 1.55; }
.empty-addresses .outline-button { margin-left: auto; flex: 0 0 auto; }

@media (max-width: 760px) {
  .profile-intro-panel { grid-template-columns: auto minmax(0, 1fr); }
  .profile-intro-email { grid-column: 1 / -1; padding: 14px 0 0; border-top: 1px solid var(--store-line); border-left: 0; }
  .profile-intro-email strong { display: inline-block; margin: 0 10px 0 0; }
  .profile-intro-email small { display: inline-flex; }
  .email-verification { width: min(430px, 100%); }
  .account-panel { padding: 22px 18px 23px; }
  .panel-heading-row { flex-direction: column; margin-bottom: 22px; }
  .panel-index { order: -1; }
  .form-grid-three { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .field-span-three { grid-column: 1 / -1; }
  .address-grid { grid-template-columns: 1fr; }
  .empty-addresses { align-items: flex-start; flex-wrap: wrap; }
  .empty-addresses .outline-button { margin-left: 62px; }
}

@media (max-width: 520px) {
  .profile-skeleton-grid,
  .form-grid,
  .profile-form,
  .address-form { grid-template-columns: 1fr; }
  .field-span-two,
  .field-span-three,
  .form-section-label,
  .consent-row,
  .form-actions,
  .inline-error { grid-column: 1; }
  .profile-intro-panel { padding: 17px; }
  .profile-intro-avatar { width: 54px; height: 54px; font-size: 21px; }
  .profile-intro-panel h2 { font-size: 24px; }
  .email-verification-actions { grid-template-columns: 1fr; }
  .email-verify-button { grid-column: 1; }
  .form-actions { align-items: stretch; flex-direction: column; }
  .form-actions .store-button { width: 100%; }
  .empty-addresses .outline-button { margin-left: 0; width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  .profile-skeleton::after,
  .profile-skeleton-wide::after,
  .profile-skeleton-panel::after,
  .is-spinning { animation: none; }
}
</style>
