<script setup lang="ts">
withDefaults(defineProps<{
  eyebrow: string
  title: string
  intro: string
  index?: string
}>(), { index: '01' })
</script>

<template>
  <main class="store-page help-center-page">
    <StoreHeader />

    <section class="help-masthead">
      <div class="store-container help-masthead-inner">
        <div class="help-masthead-copy">
          <p class="store-eyebrow">{{ eyebrow }}</p>
          <h1>{{ title }}</h1>
          <p>{{ intro }}</p>
        </div>
        <div class="help-masthead-art" aria-hidden="true">
          <span class="help-art-index">{{ index }}</span>
          <span class="help-art-mark">P°</span>
          <div class="help-art-orbit" />
        </div>
      </div>
    </section>

    <section class="store-container help-content">
      <slot />
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.help-masthead {
  padding: 75px 0 70px;
  background: var(--store-linen);
}

.help-masthead-inner {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(260px, .9fr);
  gap: 52px;
  align-items: center;
}

.help-masthead-copy {
  max-width: 650px;
}

.help-masthead-copy h1 {
  max-width: 670px;
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(46px, 6vw, 84px);
  font-weight: 500;
  letter-spacing: -.045em;
  line-height: .96;
}

.help-masthead-copy > p:last-child {
  max-width: 560px;
  margin: 24px 0 0;
  color: var(--store-muted);
  font-size: 14px;
  line-height: 1.7;
}

.help-masthead-art {
  position: relative;
  min-height: 245px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #fff;
  background: linear-gradient(135deg, rgba(154, 64, 85, .92), rgba(36, 29, 33, .95)), url('/lingerie/hero-lace.jpg') center / cover;
}

.help-art-index {
  position: absolute;
  top: 20px;
  left: 22px;
  color: rgba(255,255,255,.72);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .12em;
}

.help-art-mark {
  position: relative;
  z-index: 1;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 86px;
  font-style: italic;
}

.help-art-orbit {
  position: absolute;
  width: 220px;
  height: 220px;
  border: 1px solid rgba(255,255,255,.35);
  border-radius: 50%;
  transform: rotate(-28deg) skewX(-12deg);
}

.help-art-orbit::after {
  position: absolute;
  inset: 18px -25px;
  border: 1px solid rgba(255,255,255,.25);
  border-radius: 50%;
  content: '';
}

.help-content {
  padding-top: 66px;
  padding-bottom: 88px;
}

@media (max-width: 700px) {
  .help-masthead {
    padding: 52px 0 46px;
  }

  .help-masthead-inner {
    grid-template-columns: 1fr;
    gap: 32px;
  }

  .help-masthead-art {
    min-height: 170px;
  }

  .help-art-mark {
    font-size: 64px;
  }

  .help-content {
    padding-top: 42px;
    padding-bottom: 60px;
  }
}
</style>
