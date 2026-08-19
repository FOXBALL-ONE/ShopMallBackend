<script setup lang="ts">
type LegalSection = {
  title: string
  paragraphs?: string[]
  bullets?: string[]
}

const props = defineProps<{
  document: 'privacy' | 'terms'
}>()

const { t, tm } = useI18n()
const prefix = computed(() => `legal.${props.document}`)
const sections = computed(() => tm(`${prefix.value}.sections`) as unknown as LegalSection[])
const activeSection = ref(1)

let sectionObserver: IntersectionObserver | undefined

onMounted(() => {
  const sectionElements = Array.from(document.querySelectorAll<HTMLElement>('.legal-section[id]'))

  sectionObserver = new IntersectionObserver(
    entries => {
      const visibleEntries = entries.filter(entry => entry.isIntersecting)
      if (!visibleEntries.length) return

      const nextSection = visibleEntries
        .sort((left, right) => left.boundingClientRect.top - right.boundingClientRect.top)[0]
        ?.target.id
        .replace('legal-section-', '')

      if (nextSection) activeSection.value = Number(nextSection)
    },
    { rootMargin: '-18% 0px -68% 0px', threshold: 0 },
  )

  sectionElements.forEach(section => sectionObserver?.observe(section))
})

onBeforeUnmount(() => sectionObserver?.disconnect())

useHead(() => ({
  title: t(`${prefix.value}.seoTitle`),
  titleTemplate: title => title,
  meta: [
    { name: 'description', content: t(`${prefix.value}.seoDescription`) },
  ],
}))
</script>

<template>
  <main class="store-page legal-page" :class="`legal-page--${props.document}`">
    <StoreHeader />

    <header class="legal-heading">
      <div class="store-container legal-heading-inner">
        <p class="legal-heading-label">{{ t(`${prefix}.eyebrow`) }}</p>
        <h1>{{ t(`${prefix}.title`) }}</h1>
        <p class="legal-intro">{{ t(`${prefix}.intro`) }}</p>
        <p class="legal-meta">
          <UIcon name="i-lucide-calendar-days" aria-hidden="true" />
          <span>{{ t(`${prefix}.updated`) }}</span>
        </p>
      </div>
    </header>

    <div class="store-container legal-layout">
      <aside class="legal-contents" :aria-label="t('legal.contentsLabel')">
        <p class="legal-contents-title">{{ t('legal.contents') }}</p>
        <ol>
          <li v-for="(section, index) in sections" :key="section.title">
            <a
              :href="`#legal-section-${index + 1}`"
              :aria-current="activeSection === index + 1 ? 'location' : undefined"
              @click="activeSection = index + 1"
            >
              <span aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
              {{ section.title }}
            </a>
          </li>
        </ol>
      </aside>

      <article class="legal-copy">
        <p class="legal-notice">
          <UIcon name="i-lucide-info" aria-hidden="true" />
          <span>{{ t(`${prefix}.notice`) }}</span>
        </p>

        <section
          v-for="(section, index) in sections"
          :id="`legal-section-${index + 1}`"
          :key="section.title"
          class="legal-section"
        >
          <p class="legal-section-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</p>
          <div class="legal-section-copy">
            <h2>{{ section.title }}</h2>
            <p v-for="paragraph in section.paragraphs || []" :key="paragraph">{{ paragraph }}</p>
            <ul v-if="section.bullets?.length">
              <li v-for="bullet in section.bullets" :key="bullet">{{ bullet }}</li>
            </ul>
          </div>
        </section>

        <section class="legal-contact" :aria-labelledby="`${props.document}-support-title`">
          <div>
            <p class="legal-contact-label">{{ t('legal.questionsEyebrow') }}</p>
            <h2 :id="`${props.document}-support-title`">{{ t('legal.questionsTitle') }}</h2>
            <p>{{ t('legal.questionsCopy') }}</p>
          </div>
          <NuxtLink class="store-button" to="/account/support-tickets">
            {{ t('legal.contactSupport') }}
            <UIcon name="i-lucide-arrow-up-right" aria-hidden="true" />
          </NuxtLink>
        </section>
      </article>
    </div>

    <StoreFooter />
  </main>
</template>

<style scoped>
.legal-page {
  min-height: 100vh;
  color: var(--store-ink);
  background: var(--store-paper);
}

.legal-heading {
  border-top: 1px solid var(--store-line);
  border-bottom: 1px solid var(--store-line);
  background: var(--store-linen);
}

.legal-heading-inner {
  padding-top: clamp(42px, 6vw, 74px);
  padding-bottom: clamp(40px, 5vw, 64px);
}

.legal-heading-label,
.legal-contents-title,
.legal-contact-label {
  margin: 0;
  color: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: .09em;
  line-height: 1.4;
  text-transform: uppercase;
}

.legal-heading h1 {
  max-width: 760px;
  margin: 13px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(38px, 5.1vw, 68px);
  font-weight: 500;
  letter-spacing: -.045em;
  line-height: .98;
}

.legal-intro {
  max-width: 665px;
  margin: 22px 0 0;
  color: var(--store-muted);
  font-size: 15px;
  line-height: 1.75;
}

.legal-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 25px 0 0;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .035em;
  line-height: 1.4;
  text-transform: uppercase;
}

.legal-meta .iconify {
  width: 14px;
  height: 14px;
  color: var(--store-wine);
}

.legal-layout {
  display: grid;
  grid-template-columns: minmax(184px, 214px) minmax(0, 760px);
  justify-content: center;
  gap: clamp(22px, 2.7vw, 40px);
  padding-top: clamp(44px, 5.5vw, 74px);
  padding-bottom: clamp(70px, 9vw, 130px);
}

.legal-contents {
  position: sticky;
  top: 22px;
  align-self: start;
  max-height: calc(100vh - 44px);
  padding: 15px 12px 15px 15px;
  overflow-y: auto;
  border: 1px solid var(--store-line);
  background: color-mix(in srgb, var(--store-linen) 68%, var(--store-paper));
  scrollbar-color: var(--store-blush) transparent;
  scrollbar-width: thin;
}

.legal-contents ol {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin: 12px 0 0;
  padding: 0;
  border-top: 1px solid var(--store-line);
  list-style: none;
}

.legal-contents li {
  border-bottom: 1px solid var(--store-line);
}

.legal-contents a {
  display: grid;
  grid-template-columns: 25px minmax(0, 1fr);
  gap: 8px;
  padding: 10px 0;
  color: var(--store-muted);
  font-size: 12px;
  line-height: 1.4;
  text-decoration: none;
  transition: color .18s ease, padding-left .18s ease;
}

.legal-contents a > span {
  color: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  line-height: 1.75;
}

.legal-contents a:hover,
.legal-contents a:focus-visible,
.legal-contents a[aria-current='location'] {
  padding-left: 5px;
  color: var(--store-ink);
}

.legal-contents a[aria-current='location'] {
  font-weight: 600;
}

.legal-copy {
  min-width: 0;
  padding-top: 2px;
}

.legal-notice {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  margin: 0 0 46px;
  padding: 16px 19px;
  border-left: 2px solid var(--store-wine);
  background: color-mix(in srgb, var(--store-linen) 52%, transparent);
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.7;
}

.legal-notice .iconify {
  width: 15px;
  height: 15px;
  flex-shrink: 0;
  margin-top: 3px;
  color: var(--store-wine);
}

.legal-section {
  display: grid;
  grid-template-columns: 39px minmax(0, 1fr);
  gap: 17px;
  padding: 0 0 42px;
  margin-bottom: 42px;
  border-bottom: 1px solid var(--store-line);
  scroll-margin-top: 24px;
}

.legal-section-number {
  margin: 5px 0 0;
  color: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .04em;
  line-height: 1.5;
}

.legal-section h2 {
  margin: 0 0 14px;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(26px, 2.3vw, 32px);
  font-weight: 500;
  letter-spacing: -.025em;
  line-height: 1.12;
}

.legal-section p,
.legal-section li {
  color: var(--store-muted);
  font-size: 14px;
  line-height: 1.85;
}

.legal-section p {
  margin: 0 0 13px;
}

.legal-section p:last-child {
  margin-bottom: 0;
}

.legal-section ul {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 15px 0 0;
  padding-left: 20px;
}

.legal-section li::marker {
  color: var(--store-wine);
}

.legal-contact {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: clamp(26px, 3.2vw, 36px);
  border: 1px solid var(--store-line);
  background: var(--store-linen);
}

.legal-contact-label {
  margin-bottom: 11px;
}

.legal-contact h2 {
  margin: 0 0 9px;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(25px, 2.4vw, 31px);
  font-weight: 500;
  letter-spacing: -.02em;
  line-height: 1.1;
}

.legal-contact p:last-child {
  max-width: 475px;
  margin: 0;
  color: var(--store-muted);
  font-size: 13px;
  line-height: 1.7;
}

.legal-contact .store-button {
  flex-shrink: 0;
}

.legal-contact .iconify {
  width: 15px;
  height: 15px;
}

@media (max-width: 820px) {
  .legal-layout {
    display: block;
  }

  .legal-contents {
    position: static;
    max-height: none;
    margin-bottom: 36px;
    padding: 0;
    overflow: hidden;
    border-inline: 0;
  }

  .legal-contents ol {
    display: flex;
    gap: 0;
    margin-top: 11px;
    overflow-x: auto;
    overscroll-behavior-inline: contain;
    scroll-snap-type: inline proximity;
    scrollbar-color: var(--store-blush) transparent;
    scrollbar-width: thin;
  }

  .legal-contents li {
    flex: 0 0 auto;
    border-bottom: 0;
  }

  .legal-contents a {
    grid-template-columns: 22px auto;
    gap: 6px;
    padding: 12px 15px;
    border-right: 1px solid var(--store-line);
    scroll-snap-align: start;
    white-space: nowrap;
  }

  .legal-contents li:last-child a {
    border-right: 0;
  }

  .legal-contact {
    align-items: flex-start;
    flex-direction: column;
  }

  .legal-contact .store-button {
    width: 100%;
  }
}

@media (max-width: 520px) {
  .legal-heading-inner {
    padding-top: 38px;
    padding-bottom: 36px;
  }

  .legal-intro {
    font-size: 14px;
  }

  .legal-layout {
    padding-top: 38px;
  }

  .legal-section {
    grid-template-columns: 29px minmax(0, 1fr);
    gap: 10px;
    padding-bottom: 32px;
    margin-bottom: 32px;
  }

  .legal-section p,
  .legal-section li {
    font-size: 13px;
  }

  .legal-contact {
    padding: 24px;
  }
}
</style>
