<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import type { ApiResult } from '~/types/http'

interface ErrorShape {
  data?: ApiResult<unknown>
  response?: { _data?: ApiResult<unknown> }
  statusMessage?: string
  message?: string
}

const { t } = useStorefrontI18n()
const route = useRoute()
const authApi = useCustomerAuthApi()
const toast = useToast()
const form = reactive({ password: '', confirmPassword: '' })
const showPassword = ref(false)
const showConfirmPassword = ref(false)
const isSubmitting = ref(false)
const completed = ref(false)
const formError = ref('')
const token = ref('')
const linkReady = ref(false)

onMounted(() => {
  const fragmentToken = new URLSearchParams(window.location.hash.slice(1)).get('token')?.trim()
  const value = Array.isArray(route.query.token) ? route.query.token[0] : route.query.token
  token.value = fragmentToken ?? (typeof value === 'string' ? value.trim() : '')
  window.history.replaceState(window.history.state, '', route.path)
  linkReady.value = true
})

useHead(() => ({
  title: t('auth.passwordReset.resetSeoTitle'),
  meta: [{ name: 'description', content: t('auth.passwordReset.resetSeoDescription') }]
}))

function getErrorMessage(error: unknown): string {
  const value = error as ErrorShape
  return value.data?.message
    ?? value.response?._data?.message
    ?? value.statusMessage
    ?? value.message
    ?? t('auth.genericError')
}

async function submitReset() {
  formError.value = ''
  if (!token.value) formError.value = t('auth.passwordReset.invalidLink')
  else if (form.password.length < 8 || form.password.length > 72) formError.value = t('auth.passwordReset.passwordLength')
  else if (form.password !== form.confirmPassword) formError.value = t('auth.passwordReset.passwordMismatch')
  if (formError.value) return

  isSubmitting.value = true
  try {
    await authApi.resetPassword(token.value, form.password, form.confirmPassword)
    completed.value = true
    form.password = ''
    form.confirmPassword = ''
    toast.add({ title: t('auth.passwordReset.completedTitle'), description: t('auth.passwordReset.completedDescription'), color: 'success' })
  } catch (error: unknown) {
    formError.value = getErrorMessage(error)
    toast.add({ title: t('auth.passwordReset.resetFailed'), description: formError.value, color: 'error' })
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <main class="recovery-page">
    <header class="recovery-header">
      <NuxtLink class="back-link" to="/login"><UIcon name="i-lucide-arrow-left" /><span>{{ t('auth.passwordReset.signIn') }}</span></NuxtLink>
      <NuxtLink class="brand" to="/">PELISSA<i>°</i></NuxtLink>
      <div class="secure-note"><UIcon name="i-lucide-shield-check" /><span>{{ t('auth.navigation.secureAccount') }}</span></div>
    </header>

    <section class="recovery-layout">
      <aside class="recovery-visual reset-visual" aria-hidden="true">
        <div class="visual-copy"><span>ONE / TIME</span><p>{{ t('auth.passwordReset.visualResetNote') }}</p></div>
      </aside>

      <div class="recovery-content">
        <div class="recovery-frame">
          <template v-if="!completed">
            <p class="eyebrow">{{ t('auth.passwordReset.resetEyebrow') }}</p>
            <h1>{{ t('auth.passwordReset.resetTitleStart') }}<br><em>{{ t('auth.passwordReset.resetTitleEmphasis') }}</em></h1>
            <p class="intro">{{ t('auth.passwordReset.resetIntro') }}</p>

            <form class="recovery-form" @submit.prevent="submitReset">
              <div v-if="formError || (linkReady && !token)" class="error-banner" role="alert">
                <UIcon name="i-lucide-circle-alert" /><span>{{ formError || t('auth.passwordReset.invalidLink') }}</span>
              </div>
              <label class="field">
                <span>{{ t('auth.passwordReset.newPassword') }}</span>
                <div class="field-control">
                  <UIcon name="i-lucide-lock-keyhole" />
                  <input v-model="form.password" :type="showPassword ? 'text' : 'password'" name="new_password" autocomplete="new-password" minlength="8" maxlength="72" :disabled="isSubmitting || !linkReady || !token" required>
                  <button class="password-toggle" type="button" :aria-label="showPassword ? t('auth.password.hide') : t('auth.password.show')" @click="showPassword = !showPassword"><UIcon :name="showPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" /></button>
                </div>
                <small>{{ t('auth.passwordReset.passwordHint') }}</small>
              </label>
              <label class="field">
                <span>{{ t('auth.passwordReset.confirmPassword') }}</span>
                <div class="field-control">
                  <UIcon name="i-lucide-check" />
                  <input v-model="form.confirmPassword" :type="showConfirmPassword ? 'text' : 'password'" name="confirm_password" autocomplete="new-password" minlength="8" maxlength="72" :disabled="isSubmitting || !linkReady || !token" required>
                  <button class="password-toggle" type="button" :aria-label="showConfirmPassword ? t('auth.password.hide') : t('auth.password.show')" @click="showConfirmPassword = !showConfirmPassword"><UIcon :name="showConfirmPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" /></button>
                </div>
              </label>
              <p class="privacy-note"><UIcon name="i-lucide-timer" />{{ t('auth.passwordReset.resetExpiryNote') }}</p>
              <button class="submit-button" type="submit" :disabled="isSubmitting || !linkReady || !token">
                <span v-if="isSubmitting" class="spinner" aria-hidden="true" />
                <span>{{ isSubmitting ? t('auth.passwordReset.resetting') : t('auth.passwordReset.resetSubmit') }}</span>
                <UIcon v-if="!isSubmitting" name="i-lucide-arrow-up-right" />
              </button>
            </form>
          </template>

          <div v-else class="success-state" role="status">
            <div class="status-icon"><UIcon name="i-lucide-badge-check" /></div>
            <p class="eyebrow">{{ t('auth.passwordReset.completeEyebrow') }}</p>
            <h1>{{ t('auth.passwordReset.completeTitleStart') }}<br><em>{{ t('auth.passwordReset.completeTitleEmphasis') }}</em></h1>
            <p class="intro">{{ t('auth.passwordReset.completeIntro') }}</p>
            <NuxtLink class="submit-button" to="/login">{{ t('auth.passwordReset.continueToSignIn') }}<UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
          </div>

          <NuxtLink v-if="!completed" class="signin-link" to="/forgot-password"><UIcon name="i-lucide-rotate-ccw" />{{ t('auth.passwordReset.requestNewLink') }}</NuxtLink>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped src="~/assets/css/password-recovery.css"></style>
