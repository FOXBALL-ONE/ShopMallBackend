<script setup lang="ts">
useHead({
  title: 'Opening secure payment | Pelissa',
  meta: [{ name: 'robots', content: 'noindex' }]
})
</script>

<template>
  <main class="redirect-page" aria-live="polite">
    <NuxtLink class="redirect-brand" to="/">PELISSA<i>°</i></NuxtLink>
    <div class="redirect-status">
      <span class="redirect-icon"><UIcon name="i-lucide-loader-circle" /></span>
      <p>SECURE CHECKOUT</p>
      <h1>Opening Stripe…</h1>
    </div>
    <span class="redirect-secure"><UIcon name="i-lucide-shield-check" /> Protected payment</span>
  </main>
</template>

<style scoped>
.redirect-page { min-height: 100vh; display: grid; grid-template-rows: auto 1fr auto; place-items: center; box-sizing: border-box; padding: 34px; color: var(--store-ink); background: #fbf7f5; }
.redirect-brand { color: var(--store-ink); font-size: 24px; font-weight: 700; letter-spacing: 0; text-decoration: none; }
.redirect-brand i { color: var(--store-wine); font-family: Georgia, serif; font-style: normal; }
.redirect-status { display: flex; align-items: center; flex-direction: column; text-align: center; }
.redirect-icon { width: 50px; height: 50px; display: grid; place-items: center; margin-bottom: 23px; color: #fff; background: #2d5d50; }
.redirect-icon .iconify { width: 23px; height: 23px; animation: spin .8s linear infinite; }
.redirect-status p { margin: 0 0 9px; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: 0; }
.redirect-status h1 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 38px; font-weight: 500; letter-spacing: 0; }
.redirect-secure { display: inline-flex; align-items: center; gap: 7px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; text-transform: uppercase; }
.redirect-secure .iconify { width: 14px; height: 14px; color: #2d5d50; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
