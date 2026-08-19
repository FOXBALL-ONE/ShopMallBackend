<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const { t } = useStorefrontI18n()
const {
  countryCode,
  currencyCode,
  countryLabel,
  countryOptions,
  currencyOptions,
  setCountry,
  setCurrency,
} = useStorefrontRegion()

const isOpen = ref(false)
const draftCountry = ref(countryCode.value)
const draftCurrency = ref(currencyCode.value)
const root = ref<HTMLElement | null>(null)

watch([countryCode, currencyCode], () => {
  if (!isOpen.value) {
    draftCountry.value = countryCode.value
    draftCurrency.value = currencyCode.value
  }
})

function openPicker() {
  draftCountry.value = countryCode.value
  draftCurrency.value = currencyCode.value
  isOpen.value = !isOpen.value
}

function applySelection() {
  setCountry(draftCountry.value)
  setCurrency(draftCurrency.value)
  isOpen.value = false
}

function closeOnOutsideClick(event: MouseEvent) {
  if (root.value && !root.value.contains(event.target as Node)) isOpen.value = false
}

function closeOnEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') isOpen.value = false
}

watch(isOpen, async value => {
  if (value) {
    await nextTick()
    document.addEventListener('mousedown', closeOnOutsideClick)
    document.addEventListener('keydown', closeOnEscape)
  } else {
    document.removeEventListener('mousedown', closeOnOutsideClick)
    document.removeEventListener('keydown', closeOnEscape)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', closeOnOutsideClick)
  document.removeEventListener('keydown', closeOnEscape)
})
</script>

<template>
  <div ref="root" class="store-region-switcher">
    <button
      class="store-region-trigger"
      type="button"
      :aria-label="t('common.regionPicker.open')"
      :aria-expanded="isOpen"
      aria-haspopup="dialog"
      @click.stop="openPicker"
    >
      <UIcon name="i-lucide-map-pin" aria-hidden="true" />
      <span>{{ countryLabel }}</span>
      <span aria-hidden="true">/</span>
      <span>{{ currencyCode }}</span>
      <UIcon name="i-lucide-chevron-down" aria-hidden="true" />
    </button>

    <div v-if="isOpen" class="store-region-popover" role="dialog" :aria-label="t('common.regionPicker.title')">
      <div class="store-region-popover-heading">
        <div>
          <strong>{{ t('common.regionPicker.title') }}</strong>
          <span>{{ countryLabel }} / {{ currencyCode }}</span>
        </div>
        <button class="store-region-close" type="button" :aria-label="t('common.actions.close')" @click="isOpen = false">
          <UIcon name="i-lucide-x" aria-hidden="true" />
        </button>
      </div>

      <label class="store-region-field">
        <span>{{ t('common.regionPicker.country') }}</span>
        <select v-model="draftCountry">
          <option v-for="option in countryOptions" :key="option.code" :value="option.code">
            {{ option.code }} — {{ option.label }}
          </option>
        </select>
      </label>

      <label class="store-region-field">
        <span>{{ t('common.regionPicker.currency') }}</span>
        <select v-model="draftCurrency">
          <option v-for="option in currencyOptions" :key="option.code" :value="option.code">
            {{ option.code }} — {{ option.label }}
          </option>
        </select>
      </label>

      <button class="store-region-apply" type="button" @click="applySelection">
        {{ t('common.regionPicker.apply') }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.store-region-switcher {
  position: relative;
  display: inline-flex;
}

.store-region-trigger {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
  font: inherit;
  letter-spacing: inherit;
}

.store-region-trigger:hover,
.store-region-trigger:focus-visible {
  color: var(--store-wine);
}

.store-region-trigger:focus-visible,
.store-region-close:focus-visible,
.store-region-apply:focus-visible,
.store-region-field select:focus-visible {
  outline: 1px solid currentColor;
  outline-offset: 3px;
}

.store-region-trigger > .iconify {
  width: 12px;
  height: 12px;
}

.store-region-trigger > .iconify:last-child {
  width: 11px;
  height: 11px;
  margin-left: 1px;
}

.store-region-popover {
  position: absolute;
  z-index: 20;
  top: calc(100% + 10px);
  left: 0;
  width: min(310px, calc(100vw - 32px));
  padding: 17px;
  border: 1px solid var(--store-line);
  color: var(--store-ink);
  background: #fff;
  box-shadow: var(--store-shadow);
}

.store-region-popover::before {
  position: absolute;
  top: -6px;
  left: 18px;
  width: 10px;
  height: 10px;
  border-top: 1px solid var(--store-line);
  border-left: 1px solid var(--store-line);
  background: #fff;
  content: '';
  transform: rotate(45deg);
}

.store-region-popover-heading {
  position: relative;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 16px;
}

.store-region-popover-heading strong {
  display: block;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 20px;
  font-weight: 500;
}

.store-region-popover-heading span {
  display: block;
  margin-top: 3px;
  color: var(--store-muted);
  font-size: 10px;
}

.store-region-close {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: var(--store-muted);
  background: transparent;
  cursor: pointer;
}

.store-region-close .iconify {
  width: 15px;
  height: 15px;
}

.store-region-field {
  display: block;
  margin-top: 12px;
}

.store-region-field span {
  display: block;
  margin-bottom: 6px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.store-region-field select {
  width: 100%;
  min-height: 38px;
  padding: 0 10px;
  border: 1px solid var(--store-line);
  border-radius: 0;
  color: var(--store-ink);
  background: #fff;
  cursor: pointer;
  font-size: 12px;
}

.store-region-apply {
  width: 100%;
  min-height: 38px;
  margin-top: 16px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  cursor: pointer;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.store-region-apply:hover {
  color: var(--store-ink);
  background: transparent;
}

@media (max-width: 820px) {
  .store-region-popover {
    left: -8px;
  }
}
</style>
