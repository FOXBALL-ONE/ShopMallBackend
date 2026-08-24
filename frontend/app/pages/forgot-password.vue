<script setup lang="ts">
import { ref } from 'vue'
import type { ApiResult } from '~/types/http'

interface ErrorShape {
  data?: ApiResult<unknown>
  response?: { _data?: ApiResult<unknown> }
  statusMessage?: string
  message?: string
}

const { t } = useStorefrontI18n()
const authApi = useCustomerAuthApi()
const toast = useToast()
const email = ref('')
const isSubmitting = ref(false)
const submitted = ref(false)
const formError = ref('')
const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

useHead(() => ({
  title: t('auth.passwordReset.requestSeoTitle'),
  meta: [{ name: 'description', content: t('auth.passwordReset.requestSeoDescription') }]
}))

function getErrorMessage(error: unknown): string {
  const value = error as ErrorShape
  return value.data?.message
    ?? value.response?._data?.message
    ?? value.statusMessage
    ?? value.message
    ?? t('auth.genericError')
}

async function submitRequest() {
  const normalizedEmail = email.value.trim()
  formError.value = ''
  if (!emailPattern.test(normalizedEmail)) {
    formError.value = t('auth.passwordReset.validEmail')
    return
  }

  isSubmitting.value = true
  try {
    await authApi.requestPasswordReset(normalizedEmail)
    submitted.value = true
    toast.add({
      title: t('auth.passwordReset.requestedTitle'),
      description: t('auth.passwordReset.requestedDescription'),
      color: 'success'
    })
  } catch (error: unknown) {
    formError.value = getErrorMessage(error)
    toast.add({ title: t('auth.passwordReset.requestFailed'), description: formError.value, color: 'error' })
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="recovery-page">
    <header class="recovery-header">
      <NuxtLink class="back-link" to="/login" :aria-label="t('auth.passwordReset.backToSignIn')">
        <UIcon name="i-lucide-arrow-left" />
        <span>{{ t('auth.passwordReset.signIn') }}</span>
      </NuxtLink>
      <NuxtLink class="brand" to="/">PELISSA<i>°</i></NuxtLink>
      <div class="secure-note"><UIcon name="i-lucide-shield-check" /><span>{{ t('auth.navigation.secureAccount') }}</span></div>
    </header>

    <section class="recovery-layout">
      <aside class="recovery-visual" aria-hidden="true">
        <div class="visual-copy">
          <span>05:00</span>
          <p>{{ t('auth.passwordReset.visualNote') }}</p>
        </div>
      </aside>

      <div class="recovery-content">
        <div class="recovery-frame">
          <template v-if="!submitted">
            <p class="eyebrow">{{ t('auth.passwordReset.requestEyebrow') }}</p>
            <h1>{{ t('auth.passwordReset.requestTitleStart') }}<br><em>{{ t('auth.passwordReset.requestTitleEmphasis') }}</em></h1>
            <p class="intro">{{ t('auth.passwordReset.requestIntro') }}</p>

            <form class="recovery-form" @submit.prevent="submitRequest">
              <div v-if="formError" class="error-banner" role="alert">
                <UIcon name="i-lucide-circle-alert" /><span>{{ formError }}</span>
              </div>
              <label class="field">
                <span>{{ t('auth.passwordReset.email') }}</span>
                <div class="field-control">
                  <UIcon name="i-lucide-mail" />
                  <input v-model="email" type="email" name="email" autocomplete="email" placeholder="[REDACTED]" :disabled="isSubmitting" required>
                </div>
              </label>
              <p class="privacy-note"><UIcon name="i-lucide-lock-keyhole" />{{ t('auth.passwordReset.privacyNote') }}</p>
              <button class="submit-button" type="submit" :disabled="isSubmitting">
                <span v-if="isSubmitting" class="spinner" aria-hidden="true" />
                <span>{{ isSubmitting ? t('auth.passwordReset.sending') : t('auth.passwordReset.sendLink') }}</span>
                <UIcon v-if="!isSubmitting" name="i-lucide-send" />
              </button>
            </form>
          </template>

          <div v-else class="success-state" role="status">
            <div class="status-icon"><UIcon name="i-lucide-mail-check" /></div>
            <p class="eyebrow">{{ t('auth.passwordReset.checkEmailEyebrow') }}</p>
            <h1>{{ t('auth.passwordReset.checkEmailTitleStart') }}<br><em>{{ t('auth.passwordReset.checkEmailTitleEmphasis') }}</em></h1>
            <p class="intro">{{ t('auth.passwordReset.checkEmailIntro', { email: email.trim() }) }}</p>
            <div class="expiry-note"><UIcon name="i-lucide-timer" /><span>{{ t('auth.passwordReset.expiryNote') }}</span></div>
            <button class="text-button" type="button" @click="submitted = false">{{ t('auth.passwordReset.useAnotherEmail') }}</button>
          </div>

          <NuxtLink class="signin-link" to="/login"><UIcon name="i-lucide-arrow-left" />{{ t('auth.passwordReset.backToSignIn') }}</NuxtLink>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped src="~/assets/css/password-recovery.css"></style>
