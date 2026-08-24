<script setup lang="ts">
import { computed, ref } from 'vue'

const { t } = useStorefrontI18n()
const session = useCustomerSession()
const router = useRouter()
const orderNo = ref('')
const lookupError = ref('')

useHead(() => ({
  title: t('orderLookup.seoTitle'),
  meta: [{ name: 'description', content: t('orderLookup.seoDescription') }]
}))

const isAuthenticated = computed(() => session.isAuthenticated.value)

async function submitLookup() {
  const value = orderNo.value.trim()
  lookupError.value = ''
  if (!value) {
    lookupError.value = t('orderLookup.required')
    return
  }
  if (!/^[A-Za-z0-9][A-Za-z0-9-]{3,39}$/.test(value)) {
    lookupError.value = t('orderLookup.format')
    return
  }
  if (!isAuthenticated.value) {
    await router.push({ path: '/login', query: { redirect: `/orders/${encodeURIComponent(value)}` } })
    return
  }
  await router.push(`/orders/${encodeURIComponent(value)}`)
}
</script>

<template>
  <HelpCenterShell
    :eyebrow="t('orderLookup.eyebrow')"
    :title="t('orderLookup.title')"
    :intro="t('orderLookup.intro')"
    index="02"
  >
    <div class="lookup-layout">
      <section class="lookup-panel">
        <p class="store-eyebrow">{{ t('orderLookup.formEyebrow') }}</p>
        <h2>{{ t('orderLookup.formTitle') }}</h2>
        <p class="lookup-copy">{{ t('orderLookup.formCopy') }}</p>
        <form class="lookup-form" @submit.prevent="submitLookup">
          <label for="order-number">{{ t('orderLookup.orderNumber') }}</label>
          <div class="lookup-input-row">
            <input id="order-number" v-model="orderNo" type="text" autocomplete="off" :placeholder="t('orderLookup.placeholder')" :aria-invalid="Boolean(lookupError)">
            <button class="store-button" type="submit"><UIcon name="i-lucide-arrow-right" /> {{ t('orderLookup.submit') }}</button>
          </div>
          <p class="field-hint">{{ t('orderLookup.hint') }}</p>
          <p v-if="lookupError" class="form-error" role="alert"><UIcon name="i-lucide-circle-alert" />{{ lookupError }}</p>
        </form>
        <p v-if="!isAuthenticated" class="lookup-signin"><UIcon name="i-lucide-lock-keyhole" /> {{ t('orderLookup.signInNote') }}</p>
      </section>

      <aside class="lookup-side">
        <div class="lookup-side-mark">02</div>
        <h3>{{ t('orderLookup.sideTitle') }}</h3>
        <p>{{ t('orderLookup.sideCopy') }}</p>
        <NuxtLink class="text-link" to="/account/orders">{{ t('orderLookup.viewOrders') }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </aside>
    </div>

    <div class="lookup-help-grid">
      <article><UIcon name="i-lucide-mail-check" /><h3>{{ t('orderLookup.tipOneTitle') }}</h3><p>{{ t('orderLookup.tipOneCopy') }}</p></article>
      <article><UIcon name="i-lucide-package-search" /><h3>{{ t('orderLookup.tipTwoTitle') }}</h3><p>{{ t('orderLookup.tipTwoCopy') }}</p></article>
      <article><UIcon name="i-lucide-message-circle-question" /><h3>{{ t('orderLookup.tipThreeTitle') }}</h3><p>{{ t('orderLookup.tipThreeCopy') }}</p></article>
    </div>
  </HelpCenterShell>
</template>

<style scoped>
.lookup-layout { display: grid; grid-template-columns: minmax(0, 1.45fr) minmax(240px, .55fr); gap: 14px; }
.lookup-panel { padding: 36px; border: 1px solid var(--store-line); background: #fffdfb; }
.lookup-panel h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(34px, 4vw, 53px); font-weight: 500; letter-spacing: -.04em; line-height: 1; }
.lookup-copy { max-width: 600px; margin: 15px 0 30px; color: var(--store-muted); font-size: 13px; line-height: 1.65; }
.lookup-form label { display: block; margin-bottom: 8px; color: var(--store-ink); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }
.lookup-input-row { display: flex; gap: 10px; }
.lookup-input-row input { min-width: 0; flex: 1; height: 48px; padding: 0 14px; border: 1px solid var(--store-line); color: var(--store-ink); background: var(--store-paper); font-family: 'DM Mono', monospace; font-size: 12px; letter-spacing: .05em; }
.lookup-input-row input:focus { outline: 0; border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.11); }
.lookup-input-row .store-button { flex: 0 0 auto; }
.field-hint { margin: 10px 0 0; color: var(--store-muted); font-size: 10px; line-height: 1.5; }
.form-error { display: flex; align-items: center; gap: 6px; margin: 10px 0 0; color: #9d3f4b; font-size: 11px; }
.form-error .iconify { width: 14px; height: 14px; }
.lookup-signin { display: flex; align-items: center; gap: 7px; margin: 25px 0 0; padding-top: 17px; border-top: 1px solid var(--store-line); color: var(--store-muted); font-size: 10px; }
.lookup-signin .iconify { color: var(--store-wine); }
.lookup-side { padding: 32px 27px; color: #fff; background: var(--store-ink); }
.lookup-side-mark { color: var(--store-blush); font-family: 'DM Mono', monospace; font-size: 10px; letter-spacing: .1em; }
.lookup-side h3 { margin: 55px 0 13px; font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; line-height: 1.05; }
.lookup-side p { margin: 0 0 28px; color: #c9bfc2; font-size: 12px; line-height: 1.65; }
.lookup-side .text-link { color: var(--store-blush); }
.lookup-help-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-top: 14px; }
.lookup-help-grid article { padding: 24px; border: 1px solid var(--store-line); background: rgba(241,232,231,.35); }
.lookup-help-grid .iconify { width: 22px; height: 22px; color: var(--store-wine); }
.lookup-help-grid h3 { margin: 24px 0 8px; font-family: 'Playfair Display', Georgia, serif; font-size: 22px; font-weight: 500; }
.lookup-help-grid p { margin: 0; color: var(--store-muted); font-size: 11px; line-height: 1.6; }
@media (max-width: 760px) { .lookup-layout, .lookup-help-grid { grid-template-columns: 1fr; } .lookup-panel { padding: 25px 20px; } .lookup-side h3 { margin-top: 35px; } }
@media (max-width: 520px) { .lookup-input-row { flex-direction: column; } .lookup-input-row .store-button { width: 100%; } }
</style>
