<script setup lang="ts">
import { ref } from 'vue'

const { t } = useStorefrontI18n()
const openIndex = ref(0)

useHead(() => ({
  title: t('faqs.seoTitle'),
  meta: [{ name: 'description', content: t('faqs.seoDescription') }]
}))

const faqs = [1, 2, 3, 4, 5, 6]
</script>

<template>
  <HelpCenterShell
    :eyebrow="t('faqs.eyebrow')"
    :title="t('faqs.title')"
    :intro="t('faqs.intro')"
    index="04"
  >
    <div class="faq-layout">
      <aside class="faq-aside">
        <p class="store-eyebrow">{{ t('faqs.asideEyebrow') }}</p>
        <h2>{{ t('faqs.asideTitle') }}</h2>
        <p>{{ t('faqs.asideCopy') }}</p>
        <NuxtLink class="store-button" to="/contact-us">{{ t('faqs.contactButton') }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
      </aside>

      <section class="faq-list" :aria-label="t('faqs.listLabel')">
        <article v-for="number in faqs" :key="number" class="faq-item" :class="{ open: openIndex === number - 1 }">
          <button type="button" :aria-expanded="openIndex === number - 1" @click="openIndex = openIndex === number - 1 ? -1 : number - 1">
            <span class="faq-number">0{{ number }}</span>
            <span class="faq-question">{{ t(`faqs.q${number}`) }}</span>
            <UIcon :name="openIndex === number - 1 ? 'i-lucide-minus' : 'i-lucide-plus'" />
          </button>
          <div v-show="openIndex === number - 1" class="faq-answer"><p>{{ t(`faqs.a${number}`) }}</p></div>
        </article>
      </section>
    </div>
  </HelpCenterShell>
</template>

<style scoped>
.faq-layout { display: grid; grid-template-columns: minmax(240px, .62fr) minmax(0, 1.38fr); gap: 14px; align-items: start; }
.faq-aside { position: sticky; top: 24px; padding: 31px 27px; color: #fff; background: var(--store-ink); }
.faq-aside .store-eyebrow { color: var(--store-blush); }
.faq-aside h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: 34px; font-weight: 500; letter-spacing: -.035em; line-height: 1.05; }
.faq-aside p:not(.store-eyebrow) { margin: 15px 0 28px; color: #c9bfc2; font-size: 12px; line-height: 1.65; }
.faq-aside .store-button { border-color: var(--store-blush); color: var(--store-ink); background: var(--store-blush); }
.faq-aside .store-button:hover { color: #fff; background: transparent; }
.faq-aside .iconify { width: 14px; height: 14px; }
.faq-list { border-top: 1px solid var(--store-line); }
.faq-item { border-bottom: 1px solid var(--store-line); background: rgba(255,255,255,.45); }
.faq-item.open { background: #fffdfb; }
.faq-item button { width: 100%; display: grid; grid-template-columns: 36px minmax(0,1fr) 20px; gap: 15px; align-items: center; padding: 23px 21px; border: 0; color: var(--store-ink); background: transparent; cursor: pointer; text-align: left; }
.faq-item button:hover { color: var(--store-wine); }
.faq-number { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; }
.faq-question { font-family: 'Playfair Display', Georgia, serif; font-size: 20px; line-height: 1.2; }
.faq-item button > .iconify { justify-self: end; color: var(--store-wine); }
.faq-answer { padding: 0 56px 24px 72px; }
.faq-answer p { max-width: 630px; margin: 0; color: var(--store-muted); font-size: 12px; line-height: 1.7; }
@media (max-width: 720px) { .faq-layout { grid-template-columns: 1fr; } .faq-aside { position: static; } .faq-item button { padding-inline: 14px; grid-template-columns: 28px minmax(0,1fr) 18px; gap: 10px; } .faq-answer { padding: 0 18px 22px 52px; } }
</style>
