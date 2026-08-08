<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import type { ApiResult } from '~/types/http'

type AuthMode = 'login' | 'register'

interface LoginResponse {
  access_token: string
  expires_in: number
  user_id: number
  user_info: {
    username: string
    email: string
    first_name: string
    last_name: string
    avatar: string | null
    locale: string | null
    currency: string | null
    role: string
  }
}

interface RegistrationPayload {
  email: string
  username: string
  password: string
  verification_code: string
  first_name?: string
  last_name?: string
  marketing_consent: boolean
}

interface RegistrationResponse {
  id: number
  email: string
  username: string
  first_name: string
  last_name: string
  email_verified: boolean
  marketing_consent: boolean
  role: string
  status: string
  created_at: string | null
}

interface ErrorShape {
  data?: ApiResult<unknown>
  response?: { _data?: ApiResult<unknown> }
  statusMessage?: string
  message?: string
}

useHead({
  title: 'Sign in or join | LUNE',
  meta: [{
    name: 'description',
    content: 'Sign in to your Lune account or create a new account.'
  }]
})

const route = useRoute()
const router = useRouter()
const toast = useToast()
const http = useHttp()

const mode = ref<AuthMode>(route.query.mode === 'register' ? 'register' : 'login')
const isSubmitting = ref(false)
const isSendingCode = ref(false)
const showLoginPassword = ref(false)
const showRegisterPassword = ref(false)
const showConfirmPassword = ref(false)
const formError = ref('')
const verificationSent = ref(false)
const codeCooldown = ref(0)

const loginForm = reactive({ identifier: '', password: '' })
const registerForm = reactive({
  firstName: '',
  lastName: '',
  email: '',
  username: '',
  password: '',
  confirmPassword: '',
  verificationCode: '',
  marketingConsent: false,
  acceptedTerms: false
})

const redirectTarget = computed(() => {
  const redirect = Array.isArray(route.query.redirect) ? route.query.redirect[0] : route.query.redirect

  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')
    ? redirect
    : '/'
})

const codeButtonLabel = computed(() => {
  if (isSendingCode.value) return 'Sending...'
  if (codeCooldown.value > 0) return `Resend in ${codeCooldown.value}s`
  return verificationSent.value ? 'Send again' : 'Send code'
})

const emailPattern = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
let cooldownTimer: ReturnType<typeof setInterval> | null = null

function switchMode(nextMode: AuthMode) {
  if (isSubmitting.value || mode.value === nextMode) return

  mode.value = nextMode
  formError.value = ''
  const query = { ...route.query }
  if (nextMode === 'register') query.mode = 'register'
  else delete query.mode
  void router.replace({ query })
}

function getErrorMessage(error: unknown): string {
  const value = error as ErrorShape

  return value.data?.message
    ?? value.response?._data?.message
    ?? value.statusMessage
    ?? value.message
    ?? 'Something went wrong. Please try again.'
}

function showRequestError(title: string, error: unknown) {
  const message = getErrorMessage(error)
  formError.value = message
  toast.add({ title, description: message, color: 'error' })
}

function startCodeCooldown() {
  if (cooldownTimer) clearInterval(cooldownTimer)

  codeCooldown.value = 60
  cooldownTimer = setInterval(() => {
    codeCooldown.value -= 1
    if (codeCooldown.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
  }, 1000)
}

function authenticate(identifier: string, password: string) {
  return http.post<LoginResponse, URLSearchParams>('/auth/login', formBody({
    identifier: identifier.trim(),
    password
  }), {
    payloadMode: 'json',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
  })
}

function formBody(parameters: Record<string, string | boolean | undefined>) {
  const body = new URLSearchParams()
  for (const [name, value] of Object.entries(parameters)) {
    if (value !== undefined) body.set(name, String(value))
  }
  return body
}

async function sendVerificationCode() {
  const email = registerForm.email.trim()
  formError.value = ''

  if (!emailPattern.test(email)) {
    formError.value = 'Enter a valid email address before requesting a code.'
    return
  }
  if (isSendingCode.value || codeCooldown.value > 0) return

  isSendingCode.value = true
  try {
    const response = await http.postRaw<unknown, URLSearchParams>('/auth/verification-code', formBody({ email }), {
      payloadMode: 'json',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
    })
    verificationSent.value = true
    startCodeCooldown()
    toast.add({
      title: 'Verification code sent',
      description: response.message || 'Check your inbox. The code is valid for five minutes.',
      color: 'success'
    })
  } catch (error: unknown) {
    showRequestError('Unable to send the code', error)
  } finally {
    isSendingCode.value = false
  }
}

async function submitLogin() {
  formError.value = ''
  if (!loginForm.identifier.trim() || !loginForm.password) {
    formError.value = 'Enter your email or username and password.'
    return
  }

  isSubmitting.value = true
  try {
    const session = await authenticate(loginForm.identifier, loginForm.password)
    const displayName = session.user_info.first_name.trim() || session.user_info.username
    toast.add({ title: `Welcome back, ${displayName}`, description: 'Your secure session is ready.', color: 'success' })
    await router.replace(redirectTarget.value)
  } catch (error: unknown) {
    showRequestError('Sign in failed', error)
  } finally {
    isSubmitting.value = false
  }
}

async function submitRegistration() {
  formError.value = ''
  const email = registerForm.email.trim()
  const username = registerForm.username.trim()
  const firstName = registerForm.firstName.trim()
  const lastName = registerForm.lastName.trim()

  if (!emailPattern.test(email)) formError.value = 'Enter a valid email address.'
  else if (username.length < 3 || username.length > 50) formError.value = 'Username must be between 3 and 50 characters.'
  else if (registerForm.password.length < 8 || registerForm.password.length > 72) formError.value = 'Password must be between 8 and 72 characters.'
  else if (registerForm.password !== registerForm.confirmPassword) formError.value = 'The passwords do not match.'
  else if (!/^\d{6}$/.test(registerForm.verificationCode.trim())) formError.value = 'Enter the 6-digit verification code from your email.'
  else if (firstName.length > 50 || lastName.length > 50) formError.value = 'First and last names can contain up to 50 characters.'
  else if (!registerForm.acceptedTerms) formError.value = 'Please accept the Terms of Use and Privacy Policy.'
  if (formError.value) return

  const payload: RegistrationPayload = {
    email,
    username,
    password: registerForm.password,
    verification_code: registerForm.verificationCode.trim(),
    marketing_consent: registerForm.marketingConsent
  }
  if (firstName) payload.first_name = firstName
  if (lastName) payload.last_name = lastName

  isSubmitting.value = true
  try {
    await http.post<RegistrationResponse, URLSearchParams>('/users/Register', formBody({ ...payload }), {
      payloadMode: 'json',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' }
    })
    try {
      const session = await authenticate(username, registerForm.password)
      const displayName = session.user_info.first_name.trim() || session.user_info.username
      toast.add({
        title: `Welcome to LUNE, ${displayName}`,
        description: 'Your account is verified and your secure session has started.',
        color: 'success'
      })
      await router.replace(redirectTarget.value)
    } catch (error: unknown) {
      mode.value = 'login'
      const query = { ...route.query }
      delete query.mode
      void router.replace({ query }).catch(() => undefined)
      loginForm.identifier = username
      loginForm.password = ''
      formError.value = `Your account was created, but automatic sign in failed. ${getErrorMessage(error)}`
      toast.add({ title: 'Account created', description: 'Please sign in with your new credentials.', color: 'warning' })
    }
  } catch (error: unknown) {
    showRequestError('Account creation failed', error)
  } finally {
    isSubmitting.value = false
  }
}

onBeforeUnmount(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})
</script>

<template>
  <main class="auth-page">
    <div class="announcement-bar">
      <span>Complimentary shipping on orders over $79</span>
      <NuxtLink to="/">
        Return to shop
        <UIcon name="i-lucide-arrow-up-right" />
      </NuxtLink>
    </div>

    <header class="auth-header">
      <NuxtLink class="back-link" to="/" aria-label="Return to the Lune home page">
        <UIcon name="i-lucide-arrow-left" />
        <span>Shop</span>
      </NuxtLink>

      <NuxtLink class="brand" to="/" aria-label="Lune home">
        <span>LUNE</span><i>°</i>
      </NuxtLink>

      <div class="secure-note">
        <UIcon name="i-lucide-shield-check" />
        <span>Secure account</span>
      </div>
    </header>

    <section class="auth-layout">
      <aside class="story-panel" aria-label="The Lune membership story">
        <div class="story-shade" />
        <div class="story-number">L / 01</div>

        <div class="story-content">
          <p class="eyebrow">THE LUNE CIRCLE</p>
          <h1>Made for every<br><em>version of you.</em></h1>
          <p class="story-copy">
            Save the pieces you love, move through checkout with ease, and receive a more personal Lune experience.
          </p>

          <div class="story-benefits">
            <div>
              <span>01</span>
              <p><strong>Your edit</strong>Wishlist and personal recommendations</p>
            </div>
            <div>
              <span>02</span>
              <p><strong>Easy returns</strong>Orders and returns in one quiet place</p>
            </div>
            <div>
              <span>03</span>
              <p><strong>First access</strong>New collections, before everyone else</p>
            </div>
          </div>
        </div>
      </aside>

      <div class="form-panel">
        <div class="form-frame">
          <div class="mode-switch" role="tablist" aria-label="Choose account action">
            <button
              type="button"
              role="tab"
              :aria-selected="mode === 'login'"
              :class="{ active: mode === 'login' }"
              @click="switchMode('login')"
            >
              Sign in
            </button>
            <button
              type="button"
              role="tab"
              :aria-selected="mode === 'register'"
              :class="{ active: mode === 'register' }"
              @click="switchMode('register')"
            >
              Create account
            </button>
          </div>

          <Transition name="form-shift" mode="out-in">
            <form v-if="mode === 'login'" key="login" class="auth-form" @submit.prevent="submitLogin">
              <div class="form-heading">
                <p class="eyebrow">WELCOME BACK</p>
                <h2>Your space,<br><em>waiting.</em></h2>
                <p>Sign in with the username or email you used to join Lune.</p>
              </div>

              <div v-if="formError" class="error-banner" role="alert">
                <UIcon name="i-lucide-circle-alert" />
                <span>{{ formError }}</span>
              </div>

              <label class="field">
                <span>Email or username</span>
                <div class="field-control">
                  <UIcon name="i-lucide-user-round" />
                  <input
                    v-model="loginForm.identifier"
                    type="text"
                    name="identifier"
                    autocomplete="username"
                    placeholder="you@example.com"
                    :disabled="isSubmitting"
                    required
                  >
                </div>
              </label>

              <label class="field">
                <span>Password</span>
                <div class="field-control">
                  <UIcon name="i-lucide-lock-keyhole" />
                  <input
                    v-model="loginForm.password"
                    :type="showLoginPassword ? 'text' : 'password'"
                    name="password"
                    autocomplete="current-password"
                    placeholder="Your password"
                    :disabled="isSubmitting"
                    required
                  >
                  <button
                    class="password-toggle"
                    type="button"
                    :aria-label="showLoginPassword ? 'Hide password' : 'Show password'"
                    @click="showLoginPassword = !showLoginPassword"
                  >
                    <UIcon :name="showLoginPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" />
                  </button>
                </div>
              </label>

              <div class="session-note">
                <UIcon name="i-lucide-refresh-cw" />
                <span>Your secure session renews quietly for up to 7 days.</span>
              </div>

              <button class="submit-button" type="submit" :disabled="isSubmitting">
                <span v-if="isSubmitting" class="spinner" aria-hidden="true" />
                <span>{{ isSubmitting ? 'Signing in...' : 'Enter your account' }}</span>
                <UIcon v-if="!isSubmitting" name="i-lucide-arrow-up-right" />
              </button>

              <p class="mode-prompt">
                New to Lune?
                <button type="button" @click="switchMode('register')">Create an account</button>
              </p>
            </form>

            <form v-else key="register" class="auth-form register-form" @submit.prevent="submitRegistration">
              <div class="form-heading compact">
                <p class="eyebrow">JOIN THE CIRCLE</p>
                <h2>A little more<br><em>personal.</em></h2>
                <p>Create your account. We will verify your email before signing you in.</p>
              </div>

              <div v-if="formError" class="error-banner" role="alert">
                <UIcon name="i-lucide-circle-alert" />
                <span>{{ formError }}</span>
              </div>

              <div class="field-row">
                <label class="field">
                  <span>First name <small>Optional</small></span>
                  <div class="field-control">
                    <input
                      v-model="registerForm.firstName"
                      type="text"
                      name="first_name"
                      autocomplete="given-name"
                      maxlength="50"
                      placeholder="Ada"
                      :disabled="isSubmitting"
                    >
                  </div>
                </label>

                <label class="field">
                  <span>Last name <small>Optional</small></span>
                  <div class="field-control">
                    <input
                      v-model="registerForm.lastName"
                      type="text"
                      name="last_name"
                      autocomplete="family-name"
                      maxlength="50"
                      placeholder="Lovelace"
                      :disabled="isSubmitting"
                    >
                  </div>
                </label>
              </div>

              <label class="field">
                <span>Email address</span>
                <div class="field-control">
                  <UIcon name="i-lucide-mail" />
                  <input
                    v-model="registerForm.email"
                    type="email"
                    name="email"
                    autocomplete="email"
                    placeholder="you@example.com"
                    :disabled="isSubmitting"
                    required
                  >
                </div>
              </label>

              <label class="field">
                <span>Username</span>
                <div class="field-control">
                  <UIcon name="i-lucide-at-sign" />
                  <input
                    v-model="registerForm.username"
                    type="text"
                    name="username"
                    autocomplete="username"
                    minlength="3"
                    maxlength="50"
                    placeholder="Choose a username"
                    :disabled="isSubmitting"
                    required
                  >
                </div>
              </label>

              <div class="field-row">
                <label class="field">
                  <span>Password</span>
                  <div class="field-control">
                    <input
                      v-model="registerForm.password"
                      :type="showRegisterPassword ? 'text' : 'password'"
                      name="new_password"
                      autocomplete="new-password"
                      minlength="8"
                      maxlength="72"
                      placeholder="8–72 characters"
                      :disabled="isSubmitting"
                      required
                    >
                    <button
                      class="password-toggle"
                      type="button"
                      :aria-label="showRegisterPassword ? 'Hide password' : 'Show password'"
                      @click="showRegisterPassword = !showRegisterPassword"
                    >
                      <UIcon :name="showRegisterPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" />
                    </button>
                  </div>
                </label>

                <label class="field">
                  <span>Confirm password</span>
                  <div class="field-control">
                    <input
                      v-model="registerForm.confirmPassword"
                      :type="showConfirmPassword ? 'text' : 'password'"
                      name="confirm_password"
                      autocomplete="new-password"
                      minlength="8"
                      maxlength="72"
                      placeholder="Repeat password"
                      :disabled="isSubmitting"
                      required
                    >
                    <button
                      class="password-toggle"
                      type="button"
                      :aria-label="showConfirmPassword ? 'Hide password' : 'Show password'"
                      @click="showConfirmPassword = !showConfirmPassword"
                    >
                      <UIcon :name="showConfirmPassword ? 'i-lucide-eye-off' : 'i-lucide-eye'" />
                    </button>
                  </div>
                </label>
              </div>

              <label class="field">
                <span>Email verification code</span>
                <div class="field-control code-control">
                  <UIcon name="i-lucide-badge-check" />
                  <input
                    v-model="registerForm.verificationCode"
                    type="text"
                    name="verification_code"
                    autocomplete="one-time-code"
                    inputmode="numeric"
                    maxlength="6"
                    pattern="[0-9]{6}"
                    placeholder="6-digit code"
                    :disabled="isSubmitting"
                    required
                  >
                  <button
                    class="code-button"
                    type="button"
                    :disabled="isSendingCode || codeCooldown > 0 || isSubmitting"
                    @click="sendVerificationCode"
                  >
                    {{ codeButtonLabel }}
                  </button>
                </div>
                <small class="field-hint">The code expires after 5 minutes and can be requested once per minute.</small>
              </label>

              <label class="choice-row required-choice">
                <input v-model="registerForm.acceptedTerms" type="checkbox" :disabled="isSubmitting">
                <span class="choice-box"><UIcon name="i-lucide-check" /></span>
                <span>I agree to the Terms of Use and Privacy Policy.</span>
              </label>

              <label class="choice-row">
                <input v-model="registerForm.marketingConsent" type="checkbox" :disabled="isSubmitting">
                <span class="choice-box"><UIcon name="i-lucide-check" /></span>
                <span>Send me thoughtful notes about new collections and private offers. <small>Optional</small></span>
              </label>

              <button class="submit-button" type="submit" :disabled="isSubmitting">
                <span v-if="isSubmitting" class="spinner" aria-hidden="true" />
                <span>{{ isSubmitting ? 'Creating your account...' : 'Create my account' }}</span>
                <UIcon v-if="!isSubmitting" name="i-lucide-arrow-up-right" />
              </button>

              <p class="mode-prompt">
                Already part of Lune?
                <button type="button" @click="switchMode('login')">Sign in</button>
              </p>
            </form>
          </Transition>

          <div class="form-footer">
            <span><UIcon name="i-lucide-lock-keyhole" /> Encrypted sign in</span>
            <span>Need help? hello@lune.com</span>
          </div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Mono:wght@400;500&family=DM+Sans:opsz,wght@9..40,400;9..40,500;9..40,600;9..40,700&family=Playfair+Display:ital,wght@0,500;0,600;1,500;1,600&display=swap');

:global(*) { box-sizing: border-box; }
:global(body) { margin: 0; background: #fbf7f5; color: #241d21; font-family: 'DM Sans', Arial, sans-serif; }
:global(button), :global(input) { font: inherit; }
:global(button) { color: inherit; }

.auth-page {
  --ink: #241d21;
  --off-white: #fbf7f5;
  --linen: #f1e8e7;
  --coral: #9a4055;
  --coral-dark: #753043;
  --sea: #75636a;
  --line: rgba(36, 29, 33, .18);
  min-width: 320px;
  min-height: 100svh;
  overflow: hidden;
  background: var(--off-white);
  color: var(--ink);
}

.announcement-bar {
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 21px;
  padding: 7px 24px;
  background: var(--coral);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .035em;
  text-transform: uppercase;
}

.announcement-bar a {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.announcement-bar .iconify { width: 12px; height: 12px; }

.auth-header {
  position: relative;
  z-index: 5;
  height: 82px;
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  padding: 0 clamp(20px, 4vw, 64px);
  border-bottom: 1px solid var(--line);
  background: var(--off-white);
}

.brand {
  display: inline-flex;
  align-items: flex-start;
  color: var(--ink);
  font-size: 31px;
  font-weight: 700;
  letter-spacing: .145em;
  line-height: 1;
  text-decoration: none;
}

.brand i {
  margin: -3px 0 0 3px;
  color: var(--coral);
  font-family: Georgia, serif;
  font-size: 21px;
  font-style: normal;
}

.back-link,
.secure-note {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--sea);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .07em;
  text-transform: uppercase;
}

.back-link { width: fit-content; color: var(--ink); text-decoration: none; }
.back-link .iconify, .secure-note .iconify { width: 16px; height: 16px; stroke-width: 1.5; }
.secure-note { justify-self: end; }

.auth-layout {
  min-height: calc(100svh - 118px);
  display: grid;
  grid-template-columns: minmax(420px, 1.04fr) minmax(540px, .96fr);
}

.story-panel {
  position: relative;
  z-index: 0;
  min-height: 720px;
  overflow: hidden;
  display: flex;
  align-items: flex-end;
  padding: clamp(38px, 5vw, 76px);
  isolation: isolate;
  background: #513942 url('/lingerie/hero-lace.jpg') center 42% / cover no-repeat;
  color: #fff;
}

.story-panel::after {
  position: absolute;
  inset: 22px;
  z-index: -1;
  border: 1px solid rgba(255, 255, 255, .34);
  content: '';
  pointer-events: none;
}

.story-shade {
  position: absolute;
  inset: 0;
  z-index: -2;
  background:
    linear-gradient(180deg, rgba(35, 19, 25, .05) 18%, rgba(31, 15, 22, .74) 100%),
    linear-gradient(90deg, rgba(30, 15, 22, .38), transparent 66%);
}

.story-number {
  position: absolute;
  top: 47px;
  right: 48px;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .12em;
}

.story-content { width: min(100%, 650px); }

.eyebrow {
  margin: 0 0 14px;
  color: var(--coral);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .14em;
  line-height: 1.3;
  text-transform: uppercase;
}

.story-content > .eyebrow { color: #f2d5da; }

.story-content h1,
.form-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-weight: 500;
  letter-spacing: -.025em;
}

.story-content h1 { font-size: clamp(48px, 5vw, 76px); line-height: .98; }
.story-content h1 em, .form-heading h2 em { font-weight: 500; }

.story-copy {
  max-width: 530px;
  margin: 25px 0 34px;
  color: rgba(255, 255, 255, .82);
  font-size: 14px;
  line-height: 1.75;
}

.story-benefits {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid rgba(255, 255, 255, .32);
}

.story-benefits > div {
  min-height: 104px;
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 13px;
  padding: 19px 18px 10px 0;
}

.story-benefits > div + div { padding-left: 18px; border-left: 1px solid rgba(255, 255, 255, .24); }
.story-benefits span { color: #f2d5da; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; }

.story-benefits p {
  margin: 0;
  color: rgba(255, 255, 255, .7);
  font-size: 11px;
  line-height: 1.55;
}

.story-benefits strong {
  display: block;
  margin-bottom: 4px;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .02em;
}

.form-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(46px, 5vw, 82px) clamp(30px, 6vw, 96px);
  background:
    linear-gradient(rgba(36, 29, 33, .045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(36, 29, 33, .045) 1px, transparent 1px),
    var(--off-white);
  background-size: 32px 32px;
}

.form-frame { width: min(100%, 570px); }

.mode-switch {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  margin-bottom: clamp(42px, 5vw, 68px);
  border-bottom: 1px solid var(--line);
}

.mode-switch button {
  position: relative;
  min-height: 43px;
  padding: 0 12px 13px;
  border: 0;
  background: transparent;
  color: var(--sea);
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .09em;
  text-transform: uppercase;
}

.mode-switch button::after {
  position: absolute;
  right: 0;
  bottom: -1px;
  left: 0;
  height: 2px;
  background: var(--ink);
  content: '';
  opacity: 0;
  transform: scaleX(.45);
  transition: .25s ease;
}

.mode-switch button.active { color: var(--ink); }
.mode-switch button.active::after { opacity: 1; transform: scaleX(1); }

.auth-form { display: grid; gap: 20px; }
.register-form { gap: 16px; }
.form-heading { margin-bottom: 8px; }
.form-heading.compact { margin-bottom: 2px; }
.form-heading h2 { font-size: clamp(42px, 4vw, 59px); line-height: 1; }
.form-heading.compact h2 { font-size: clamp(39px, 3.4vw, 52px); }

.form-heading > p:last-child {
  max-width: 470px;
  margin: 19px 0 0;
  color: var(--sea);
  font-size: 13px;
  line-height: 1.65;
}

.error-banner {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px 14px;
  border-left: 2px solid var(--coral);
  background: rgba(154, 64, 85, .08);
  color: var(--coral-dark);
  font-size: 12px;
  line-height: 1.5;
}

.error-banner .iconify { flex: 0 0 auto; width: 16px; height: 16px; margin-top: 1px; }
.field-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.field { display: grid; gap: 8px; }

.field > span {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--ink);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 500;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.field > span small, .choice-row small {
  color: var(--sea);
  font-size: 8px;
  font-weight: 400;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.field-control {
  min-height: 51px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 0 15px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, .7);
  transition: border-color .18s ease, box-shadow .18s ease, background .18s ease;
}

.field-control:focus-within {
  border-color: var(--ink);
  background: #fff;
  box-shadow: 4px 4px 0 rgba(154, 64, 85, .12);
}

.field-control > .iconify {
  flex: 0 0 auto;
  width: 17px;
  height: 17px;
  color: var(--sea);
  stroke-width: 1.5;
}

.field-control input {
  width: 100%;
  min-width: 0;
  height: 49px;
  padding: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--ink);
  font-size: 13px;
}

.field-control input::placeholder { color: #a3979c; }
.field-control input:disabled { cursor: not-allowed; opacity: .65; }

.password-toggle {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: 31px;
  height: 31px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--sea);
  cursor: pointer;
}

.password-toggle .iconify { width: 16px; height: 16px; stroke-width: 1.5; }
.code-control { padding-right: 7px; }

.code-button {
  flex: 0 0 auto;
  min-width: 105px;
  min-height: 37px;
  padding: 0 12px;
  border: 0;
  background: var(--linen);
  color: var(--coral-dark);
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  font-weight: 500;
  letter-spacing: .05em;
  text-transform: uppercase;
}

.code-button:hover:not(:disabled) { color: #fff; background: var(--coral); }
.code-button:disabled { cursor: not-allowed; opacity: .55; }
.field-hint { color: var(--sea); font-size: 10px; line-height: 1.45; }

.session-note {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--sea);
  font-size: 11px;
}

.session-note .iconify { width: 14px; height: 14px; color: var(--coral); }

.choice-row {
  position: relative;
  display: grid;
  grid-template-columns: 17px 1fr;
  align-items: start;
  gap: 9px;
  color: var(--sea);
  cursor: pointer;
  font-size: 10px;
  line-height: 1.5;
}

.choice-row input { position: absolute; width: 1px; height: 1px; opacity: 0; }

.choice-box {
  width: 17px;
  height: 17px;
  display: grid;
  place-items: center;
  margin-top: 1px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, .7);
}

.choice-box .iconify { width: 12px; height: 12px; opacity: 0; }
.choice-row input:checked + .choice-box { border-color: var(--coral); background: var(--coral); color: #fff; }
.choice-row input:checked + .choice-box .iconify { opacity: 1; }
.choice-row input:focus-visible + .choice-box { outline: 2px solid var(--coral); outline-offset: 2px; }
.required-choice { margin-top: 2px; }

.submit-button {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 11px;
  margin-top: 2px;
  padding: 0 20px;
  border: 1px solid var(--ink);
  background: var(--ink);
  color: #fff;
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .07em;
  text-transform: uppercase;
  transition: .2s ease;
}

.submit-button:hover:not(:disabled) { color: var(--ink); background: transparent; }
.submit-button:disabled { cursor: wait; opacity: .72; }
.submit-button .iconify { width: 15px; height: 15px; }

.spinner {
  width: 15px;
  height: 15px;
  border: 1px solid rgba(255, 255, 255, .45);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin .7s linear infinite;
}

.mode-prompt { margin: 1px 0 0; color: var(--sea); font-size: 11px; text-align: center; }

.mode-prompt button {
  padding: 0;
  border: 0;
  border-bottom: 1px solid currentColor;
  background: transparent;
  color: var(--coral-dark);
  cursor: pointer;
}

.form-footer {
  display: flex;
  justify-content: space-between;
  gap: 18px;
  margin-top: 40px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--sea);
  font-family: 'DM Mono', monospace;
  font-size: 8px;
  letter-spacing: .04em;
  text-transform: uppercase;
}

.form-footer span { display: inline-flex; align-items: center; gap: 6px; }
.form-footer .iconify { width: 12px; height: 12px; }

.form-shift-enter-active, .form-shift-leave-active { transition: opacity .18s ease, transform .18s ease; }
.form-shift-enter-from { opacity: 0; transform: translateX(12px); }
.form-shift-leave-to { opacity: 0; transform: translateX(-12px); }

@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 1080px) {
  .auth-layout { grid-template-columns: minmax(360px, .86fr) minmax(500px, 1.14fr); }
  .story-benefits { grid-template-columns: 1fr; }
  .story-benefits > div { min-height: auto; padding: 12px 0; }
  .story-benefits > div + div { padding-left: 0; border-top: 1px solid rgba(255, 255, 255, .2); border-left: 0; }
  .form-panel { padding-inline: clamp(28px, 5vw, 62px); }
}

@media (max-width: 860px) {
  .auth-layout { display: flex; flex-direction: column; }
  .story-panel { min-height: 430px; padding: 54px 40px 42px; background-position: center 38%; }
  .story-content { width: min(100%, 650px); }
  .story-content h1 { font-size: 56px; }
  .story-copy { margin-bottom: 0; }
  .story-benefits { display: none; }
  .form-panel { padding: 58px 32px 68px; }
  .mode-switch { margin-bottom: 48px; }
}

@media (max-width: 560px) {
  .announcement-bar { justify-content: space-between; gap: 8px; padding-inline: 14px; font-size: 8px; }
  .announcement-bar span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .announcement-bar a { flex: 0 0 auto; }
  .auth-header { height: 68px; padding: 0 17px; }
  .brand { font-size: 25px; }
  .brand i { font-size: 17px; }
  .back-link span, .secure-note span { display: none; }
  .back-link .iconify, .secure-note .iconify { width: 18px; height: 18px; }
  .story-panel { min-height: 355px; padding: 43px 25px 34px; }
  .story-panel::after { inset: 12px; }
  .story-number { top: 29px; right: 29px; }
  .story-content h1 { font-size: clamp(43px, 13vw, 57px); }
  .story-copy { max-width: 410px; font-size: 12px; line-height: 1.65; }
  .form-panel { padding: 46px 18px 54px; background-size: 24px 24px; }
  .mode-switch { margin-bottom: 40px; }
  .form-heading h2, .form-heading.compact h2 { font-size: 42px; }
  .field-row { grid-template-columns: 1fr; }
  .auth-form, .register-form { gap: 17px; }
  .form-footer { align-items: flex-start; flex-direction: column; margin-top: 32px; }
}

@media (max-width: 390px) {
  .story-panel { min-height: 330px; }
  .story-copy { max-width: 300px; }
  .code-control { display: grid; grid-template-columns: auto minmax(0, 1fr); height: auto; padding: 0 7px 7px 15px; }
  .code-control > .iconify, .code-control input { grid-row: 1; }
  .code-button { grid-column: 1 / -1; width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  .form-shift-enter-active, .form-shift-leave-active, .submit-button, .field-control, .mode-switch button::after { transition: none; }
  .spinner { animation-duration: 1.4s; }
}
</style>
