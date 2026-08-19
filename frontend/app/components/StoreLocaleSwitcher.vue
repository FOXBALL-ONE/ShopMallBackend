<script setup lang="ts">
const { currentLocale, setStorefrontLocale, t } = useStorefrontI18n()
const selectId = useId()

async function changeLocale(event: Event) {
  const target = event.target as HTMLSelectElement
  await setStorefrontLocale(target.value)
}
</script>

<template>
  <label class="store-locale-switcher" :for="selectId">
    <UIcon name="i-lucide-languages" aria-hidden="true" />
    <span class="store-sr-only">{{ t('locale.selectorLabel') }}</span>
    <select
      :id="selectId"
      :value="currentLocale"
      :aria-label="t('locale.selectorLabel')"
      @change="changeLocale"
    >
      <option v-for="option in STOREFRONT_LOCALE_OPTIONS" :key="option.code" :value="option.code">
        {{ option.label }}
      </option>
    </select>
    <UIcon class="store-locale-switcher-chevron" name="i-lucide-chevron-down" aria-hidden="true" />
  </label>
</template>

<style scoped>
.store-locale-switcher {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: inherit;
}

.store-locale-switcher > .iconify {
  width: 12px;
  height: 12px;
  flex: 0 0 auto;
}

.store-locale-switcher select {
  max-width: 100px;
  padding: 0 15px 0 0;
  border: 0;
  outline: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
  letter-spacing: inherit;
  appearance: none;
}

.store-locale-switcher select:focus-visible {
  outline: 1px solid currentColor;
  outline-offset: 3px;
}

.store-locale-switcher-chevron {
  position: absolute;
  right: 0;
  pointer-events: none;
}

@media (max-width: 520px) {
  .store-locale-switcher select {
    max-width: 76px;
  }
}
</style>
