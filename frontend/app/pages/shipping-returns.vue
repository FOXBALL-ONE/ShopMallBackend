<script setup lang="ts">
const { t } = useStorefrontI18n()

useHead(() => ({
  title: t('shippingReturns.seoTitle'),
  meta: [{ name: 'description', content: t('shippingReturns.seoDescription') }]
}))
</script>

<template>
  <HelpCenterShell
    :eyebrow="t('shippingReturns.eyebrow')"
    :title="t('shippingReturns.title')"
    :intro="t('shippingReturns.intro')"
    index="01"
  >
    <div class="policy-grid">
      <section class="policy-card policy-card-featured">
        <p class="store-eyebrow">{{ t('shippingReturns.shippingEyebrow') }}</p>
        <h2>{{ t('shippingReturns.shippingTitle') }}</h2>
        <p>{{ t('shippingReturns.shippingIntro') }}</p>
        <div class="policy-stat-row">
          <div><strong>3–5</strong><span>{{ t('shippingReturns.standardDays') }}</span></div>
          <div><strong>$79</strong><span>{{ t('shippingReturns.freeThreshold') }}</span></div>
          <div><strong>30</strong><span>{{ t('shippingReturns.returnDays') }}</span></div>
        </div>
      </section>

      <section class="policy-card">
        <span class="policy-card-number">01</span>
        <h3>{{ t('shippingReturns.standardTitle') }}</h3>
        <p>{{ t('shippingReturns.standardCopy') }}</p>
        <span class="policy-meta">{{ t('shippingReturns.standardMeta') }}</span>
      </section>

      <section class="policy-card">
        <span class="policy-card-number">02</span>
        <h3>{{ t('shippingReturns.expressTitle') }}</h3>
        <p>{{ t('shippingReturns.expressCopy') }}</p>
        <span class="policy-meta">{{ t('shippingReturns.expressMeta') }}</span>
      </section>

      <section class="policy-card policy-card-wide">
        <div>
          <p class="store-eyebrow">{{ t('shippingReturns.returnsEyebrow') }}</p>
          <h2>{{ t('shippingReturns.returnsTitle') }}</h2>
        </div>
        <ol class="policy-steps">
          <li><span>01</span><p>{{ t('shippingReturns.returnStepOne') }}</p></li>
          <li><span>02</span><p>{{ t('shippingReturns.returnStepTwo') }}</p></li>
          <li><span>03</span><p>{{ t('shippingReturns.returnStepThree') }}</p></li>
        </ol>
      </section>
    </div>

    <section class="policy-note">
      <UIcon name="i-lucide-circle-help" />
      <p>{{ t('shippingReturns.note') }}</p>
      <NuxtLink class="text-link" to="/contact-us">{{ t('shippingReturns.contactLink') }} <UIcon name="i-lucide-arrow-up-right" /></NuxtLink>
    </section>
  </HelpCenterShell>
</template>

<style scoped>
.policy-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.policy-card { position: relative; min-height: 220px; padding: 28px; border: 1px solid var(--store-line); background: rgba(255,255,255,.62); }
.policy-card-featured { grid-column: 1 / -1; min-height: auto; padding: 35px; background: var(--store-ink); color: #fff; }
.policy-card-featured .store-eyebrow, .policy-card-wide .store-eyebrow { color: var(--store-blush); }
.policy-card h2 { margin: 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(32px, 4vw, 52px); font-weight: 500; letter-spacing: -.035em; line-height: 1; }
.policy-card-featured > p:not(.store-eyebrow) { max-width: 620px; margin: 15px 0 0; color: #c9bfc2; font-size: 13px; line-height: 1.65; }
.policy-stat-row { display: flex; flex-wrap: wrap; gap: 42px; margin-top: 32px; padding-top: 22px; border-top: 1px solid rgba(255,255,255,.2); }
.policy-stat-row div { display: flex; flex-direction: column; gap: 5px; }
.policy-stat-row strong { font-family: 'Playfair Display', Georgia, serif; font-size: 31px; font-weight: 500; }
.policy-stat-row span, .policy-meta { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-transform: uppercase; }
.policy-stat-row span { color: #c9bfc2; }
.policy-card-number { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; }
.policy-card h3 { margin: 28px 0 10px; font-family: 'Playfair Display', Georgia, serif; font-size: 28px; font-weight: 500; letter-spacing: -.03em; }
.policy-card > p:not(.store-eyebrow) { margin: 0 0 24px; color: var(--store-muted); font-size: 12px; line-height: 1.65; }
.policy-card-wide { grid-column: 1 / -1; display: grid; grid-template-columns: minmax(220px, .65fr) minmax(0, 1.35fr); gap: 40px; min-height: auto; background: var(--store-linen); }
.policy-steps { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.policy-steps li { display: grid; grid-template-columns: 35px 1fr; gap: 14px; padding: 0 0 19px; border-bottom: 1px solid var(--store-line); }
.policy-steps li + li { padding-top: 19px; }
.policy-steps li:last-child { padding-bottom: 0; border-bottom: 0; }
.policy-steps span { color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; }
.policy-steps p { margin: 0; color: var(--store-ink); font-size: 12px; line-height: 1.6; }
.policy-note { display: flex; align-items: center; gap: 12px; margin-top: 17px; padding: 17px 20px; border: 1px solid var(--store-line); color: var(--store-muted); background: rgba(241,232,231,.35); font-size: 11px; line-height: 1.5; }
.policy-note > .iconify { flex: 0 0 auto; color: var(--store-wine); }
.policy-note p { flex: 1; margin: 0; }
.text-link { display: inline-flex; align-items: center; gap: 5px; flex: 0 0 auto; color: var(--store-wine); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .05em; text-decoration: none; text-transform: uppercase; }
.text-link:hover { color: var(--store-ink); }
.text-link .iconify { width: 13px; height: 13px; }
@media (max-width: 650px) { .policy-grid, .policy-card-wide { grid-template-columns: 1fr; } .policy-card-featured, .policy-card-wide { grid-column: auto; } .policy-card { padding: 22px; } .policy-note { align-items: flex-start; flex-wrap: wrap; } .policy-note p { flex-basis: calc(100% - 32px); } .policy-note .text-link { margin-left: 32px; } }
</style>
