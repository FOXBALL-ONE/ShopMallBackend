<script setup lang="ts">
import { computed, reactive, ref } from 'vue'

const { t } = useStorefrontI18n()
const session = useCustomerSession()
const form = reactive({ name: '', email: '', topic: 'order', message: '' })
const submitted = ref(false)
const formError = ref('')

useHead(() => ({
  title: t('contact.seoTitle'),
  meta: [{ name: 'description', content: t('contact.seoDescription') }]
}))

const mailtoHref = computed(() => {
  const subject = encodeURIComponent(`${t('contact.mailSubjectPrefix')}${form.topic}`)
  const body = encodeURIComponent(`${t('contact.mailName')}: ${form.name}\n${t('contact.mailEmail')}: ${form.email}\n\n${form.message}`)
  return `mailto:support@pelissa.com?subject=${subject}&body=${body}`
})

function submitContact() {
  formError.value = ''
  if (!form.name.trim() || !form.email.trim() || !form.message.trim()) {
    formError.value = t('contact.required')
    submitted.value = false
    return
  }
  if (!/^\S+@\S+\.\S+$/.test(form.email.trim())) {
    formError.value = t('contact.emailFormat')
    submitted.value = false
    return
  }
  submitted.value = true
}
</script>

<template>
  <HelpCenterShell
    :eyebrow="t('contact.eyebrow')"
    :title="t('contact.title')"
    :intro="t('contact.intro')"
    index="03"
  >
    <div class="contact-layout">
      <section class="contact-form-card">
        <p class="store-eyebrow">{{ t('contact.formEyebrow') }}</p>
        <h2>{{ t('contact.formTitle') }}</h2>
        <p class="contact-copy">{{ t('contact.formCopy') }}</p>
        <form class="contact-form" @submit.prevent="submitContact">
          <div class="field-row">
            <label><span>{{ t('contact.name') }}</span><input v-model="form.name" type="text" autocomplete="name"></label>
            <label><span>{{ t('contact.email') }}</span><input v-model="form.email" type="email" autocomplete="email"></label>
          </div>
          <label><span>{{ t('contact.topic') }}</span><select v-model="form.topic"><option value="order">{{ t('contact.topicOrder') }}</option><option value="delivery">{{ t('contact.topicDelivery') }}</option><option value="returns">{{ t('contact.topicReturns') }}</option><option value="product">{{ t('contact.topicProduct') }}</option><option value="other">{{ t('contact.topicOther') }}</option></select></label>
          <label><span>{{ t('contact.message') }}</span><textarea v-model="form.message" rows="6" :placeholder="t('contact.messagePlaceholder')" /></label>
          <p v-if="formError" class="form-error" role="alert"><UIcon name="i-lucide-circle-alert" />{{ formError }}</p>
          <div v-if="submitted" class="form-success" role="status"><UIcon name="i-lucide-check-circle-2" /><span>{{ t('contact.success') }}</span><a :href="mailtoHref">{{ t('contact.openEmail') }}</a></div>
          <button v-else class="store-button" type="submit"><UIcon name="i-lucide-send" /> {{ t('contact.submit') }}</button>
        </form>
      </section>

      <aside class="contact-details">
        <p class="store-eyebrow">{{ t('contact.detailsEyebrow') }}</p>
        <div class="contact-detail"><UIcon name="i-lucide-mail" /><div><strong>{{ t('contact.emailLabel') }}</strong><a href="mailto:support@pelissa.com">support@pelissa.com</a></div></div>
        <div class="contact-detail"><UIcon name="i-lucide-clock-3" /><div><strong>{{ t('contact.hoursLabel') }}</strong><span>{{ t('contact.hoursValue') }}</span></div></div>
        <div class="contact-detail"><UIcon name="i-lucide-message-circle" /><div><strong>{{ t('contact.accountLabel') }}</strong><span>{{ t('contact.accountValue') }}</span></div></div>
        <NuxtLink v-if="session.isAuthenticated.value" class="contact-account-link" to="/account/support-tickets">{{ t('contact.openSupport') }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
        <NuxtLink v-else class="contact-account-link" to="/login">{{ t('contact.signInSupport') }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </aside>
    </div>
  </HelpCenterShell>
</template>

<style scoped>
.contact-layout { display: grid; grid-template-columns: minmax(0, 1.25fr) minmax(255px, .75fr); gap: 14px; }
.contact-form-card { padding: 35px; border: 1px solid var(--store-line); background: #fffdfb; }
.contact-form-card h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(35px, 4vw, 54px); font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.contact-copy { max-width: 540px; margin: 15px 0 28px; color: var(--store-muted); font-size: 13px; line-height: 1.65; }
.contact-form { display: grid; gap: 16px; }
.field-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.contact-form label { display: grid; gap: 7px; color: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .07em; text-transform: uppercase; }
.contact-form input, .contact-form select, .contact-form textarea { width: 100%; box-sizing: border-box; padding: 12px; border: 1px solid var(--store-line); border-radius: 0; color: var(--store-ink); background: var(--store-paper); font-family: 'DM Sans', Arial, sans-serif; font-size: 12px; letter-spacing: 0; text-transform: none; }
.contact-form textarea { resize: vertical; line-height: 1.55; }
.contact-form input:focus, .contact-form select:focus, .contact-form textarea:focus { outline: 0; border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.1); }
.form-error { display: flex; align-items: center; gap: 7px; margin: -4px 0 0; color: #9d3f4b; font-family: 'DM Sans', Arial, sans-serif; font-size: 11px; letter-spacing: 0; text-transform: none; }
.form-error .iconify, .form-success .iconify { width: 15px; height: 15px; flex: 0 0 auto; }
.form-success { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 13px; color: #49654d; background: #edf2e9; font-family: 'DM Sans', Arial, sans-serif; font-size: 11px; letter-spacing: 0; text-transform: none; }
.form-success a { color: #49654d; font-weight: 600; }
.contact-details { padding: 34px 28px; color: #fff; background: var(--store-ink); }
.contact-details > .store-eyebrow { color: var(--store-blush); }
.contact-detail { display: flex; gap: 12px; padding: 19px 0; border-bottom: 1px solid rgba(255,255,255,.18); }
.contact-detail > .iconify { width: 18px; height: 18px; flex: 0 0 auto; color: var(--store-blush); }
.contact-detail div { display: flex; flex-direction: column; gap: 5px; }
.contact-detail strong { font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .07em; text-transform: uppercase; }
.contact-detail span, .contact-detail a { color: #c9bfc2; font-size: 11px; line-height: 1.5; text-decoration: none; }
.contact-detail a:hover { color: #fff; }
.contact-account-link { display: inline-flex; align-items: center; gap: 6px; margin-top: 28px; color: var(--store-blush); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .05em; text-decoration: none; text-transform: uppercase; }
.contact-account-link .iconify { width: 13px; height: 13px; }
@media (max-width: 760px) { .contact-layout, .field-row { grid-template-columns: 1fr; } .contact-form-card { padding: 25px 20px; } }
</style>
