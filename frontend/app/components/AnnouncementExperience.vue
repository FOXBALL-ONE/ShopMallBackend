<script setup lang="ts">
import type {
  CustomerAnnouncementType,
  CustomerAnnouncementUserState,
  CustomerAutoShowAnnouncement,
} from '~/types/announcement'
import {
  isSafeHttpsAnnouncementActionUrl,
  isSafeInternalAnnouncementActionUrl,
} from '~/utils/announcementActionUrl'

const announcementApi = useAnnouncementApi()
const announcementCenter = useAnnouncementCenter()
const announcementClientState = useAnnouncementClientState()
const customerSession = useCustomerSession()
const route = useRoute()
const { formatDate, t } = useStorefrontI18n()
const bannerAnnouncement = announcementCenter.leadingAnnouncement
const currentAnnouncementCount = announcementCenter.currentCount
const modalAnnouncement = ref<CustomerAutoShowAnnouncement | null>(null)
const isModalOpen = ref(false)
const modalElement = ref<HTMLElement | null>(null)
let previouslyFocusedElement: HTMLElement | null = null

function typeLabel(type: CustomerAnnouncementType) {
  const key = {
    GENERAL: 'announcement.types.general',
    IMPORTANT: 'announcement.types.important',
    MAINTENANCE: 'announcement.types.maintenance',
    PROMOTION: 'announcement.types.promotion',
  }[type]
  return t(key)
}

function isLocallyEligible(announcement: CustomerAutoShowAnnouncement, userId: number | null) {
  const state = announcementClientState.get(announcement.id, userId)

  if (announcement.auto_show_mode === 'ONCE_PER_ANNOUNCEMENT') {
    return !state?.lastSeenAt
  }
  if (announcement.auto_show_mode === 'ONCE_PER_BROWSER_SESSION') {
    return !announcementClientState.wasShownThisSession(announcement.id, userId)
  }
  if (announcement.auto_show_mode === 'COOLDOWN') {
    if (!state?.lastSeenAt) return true
    const lastSeenAt = Date.parse(state.lastSeenAt)
    if (Number.isNaN(lastSeenAt)) return true
    const cooldownHours = announcement.auto_show_cooldown_hours ?? 0
    return lastSeenAt + cooldownHours * 60 * 60 * 1000 <= Date.now()
  }
  return true
}

function rememberAutoShow(announcement: CustomerAutoShowAnnouncement, userId: number | null) {
  if (announcement.auto_show_mode === 'ONCE_PER_BROWSER_SESSION') {
    announcementClientState.rememberShownThisSession(announcement.id, userId)
  }
}

function recordState(id: number, state: CustomerAnnouncementUserState) {
  const userId = customerSession.userId.value ?? null
  const storedState = announcementClientState.record(id, state, userId)
  if (userId !== null) {
    void announcementApi.recordState(id, storedState.state)
      .then(() => announcementClientState.markSynced(id, storedState.state, storedState.lastSeenAt, userId))
      .catch(() => undefined)
  }
}

async function synchronizeLocalStates(userId: number | null) {
  if (userId === null) return

  await Promise.allSettled(
    announcementClientState.pendingEntries(userId)
      .map(([id, storedState]) => ({ id: Number(id), storedState }))
      .filter(item => Number.isSafeInteger(item.id) && item.id > 0)
      .map(item => announcementApi.recordState(item.id, item.storedState.state)
        .then(() => announcementClientState.markSynced(
          item.id,
          item.storedState.state,
          item.storedState.lastSeenAt,
          userId,
        )),
      ),
  )
}

async function loadAutoShowAnnouncement(userId = customerSession.userId.value ?? null) {
  const excludedIds = new Set<number>()

  while (true) {
    const candidate = await announcementApi.getAutoShow([...excludedIds])
    if (userId !== (customerSession.userId.value ?? null) || !candidate) return
    if (excludedIds.has(candidate.id)) return

    excludedIds.add(candidate.id)
    if (!isLocallyEligible(candidate, userId)) continue

    rememberAutoShow(candidate, userId)
    modalAnnouncement.value = candidate
    isModalOpen.value = true
    return
  }
}

function dismissModal() {
  if (modalAnnouncement.value) recordState(modalAnnouncement.value.id, 'DISMISSED')
  isModalOpen.value = false
}

function handleModalKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    dismissModal()
    return
  }
  if (event.key !== 'Tab' || !modalElement.value) return

  const focusableElements = Array.from(
    modalElement.value.querySelectorAll<HTMLElement>(
      'button:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])',
    ),
  )
  const firstElement = focusableElements[0]
  const lastElement = focusableElements.at(-1)
  if (!firstElement || !lastElement) {
    event.preventDefault()
    modalElement.value.focus()
    return
  }

  if (event.shiftKey && (document.activeElement === firstElement || document.activeElement === modalElement.value)) {
    event.preventDefault()
    lastElement.focus()
  } else if (!event.shiftKey && document.activeElement === lastElement) {
    event.preventDefault()
    firstElement.focus()
  }
}

function acknowledgeModal() {
  if (modalAnnouncement.value) recordState(modalAnnouncement.value.id, 'ACKNOWLEDGED')
  isModalOpen.value = false
}

async function viewDetails() {
  const announcement = modalAnnouncement.value
  if (!announcement) return

  recordState(announcement.id, 'SEEN')
  isModalOpen.value = false
  await navigateTo(`/announcements/${announcement.id}`)
}

async function openAction() {
  const announcement = modalAnnouncement.value
  if (!announcement?.action_url) return

  const url = announcement.action_url
  if (!isSafeInternalAnnouncementActionUrl(url) && !isSafeHttpsAnnouncementActionUrl(url)) return
  recordState(announcement.id, 'SEEN')
  isModalOpen.value = false
  if (isSafeInternalAnnouncementActionUrl(url)) {
    await navigateTo(url)
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

watch(isModalOpen, async open => {
  if (open) {
    previouslyFocusedElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    modalElement.value?.focus()
    return
  }

  previouslyFocusedElement?.focus()
  previouslyFocusedElement = null
})

watch(() => customerSession.userId.value, userId => {
  const ownerId = userId ?? null
  if (userId !== null) announcementClientState.claimAnonymousStates(userId)
  announcementClientState.load(ownerId)
  isModalOpen.value = false
  modalAnnouncement.value = null
  void synchronizeLocalStates(ownerId)
  void announcementCenter.refreshCurrentAnnouncements(true).catch(() => undefined)
  void loadAutoShowAnnouncement(ownerId).catch(() => undefined)
})

watch(() => route.fullPath, () => {
  void announcementCenter.refreshCurrentAnnouncements().catch(() => undefined)
})

onMounted(() => {
  const userId = customerSession.userId.value ?? null
  if (userId !== null) announcementClientState.claimAnonymousStates(userId)
  announcementClientState.load(userId)
  void synchronizeLocalStates(userId)
  void announcementCenter.refreshCurrentAnnouncements().catch(() => undefined)
  void loadAutoShowAnnouncement(userId).catch(() => undefined)
})
</script>

<template>
  <aside v-if="bannerAnnouncement" class="customer-announcement-banner" :aria-label="t('announcement.website')">
    <div class="customer-announcement-banner__content">
      <span class="customer-announcement-banner__type">{{ typeLabel(bannerAnnouncement.type) }}</span>
      <NuxtLink :to="`/announcements/${bannerAnnouncement.id}`" class="customer-announcement-banner__title">
        {{ bannerAnnouncement.title }}
      </NuxtLink>
      <span v-if="bannerAnnouncement.summary" class="customer-announcement-banner__summary">
        {{ bannerAnnouncement.summary }}
      </span>
      <NuxtLink class="customer-announcement-banner__more" to="/announcements">
        {{ t('announcement.allNotices', { count: currentAnnouncementCount >= 50 ? '50+' : currentAnnouncementCount }) }}
        <UIcon name="i-lucide-arrow-up-right" />
      </NuxtLink>
    </div>
  </aside>

  <Teleport to="body">
    <div
      v-if="isModalOpen && modalAnnouncement"
      class="announcement-modal-backdrop"
      role="presentation"
      @click.self="dismissModal"
    >
      <section
        ref="modalElement"
        class="announcement-modal"
        role="dialog"
        aria-modal="true"
        tabindex="-1"
        :aria-labelledby="`announcement-title-${modalAnnouncement.id}`"
        @keydown="handleModalKeydown"
      >
        <button class="announcement-modal__close" type="button" :aria-label="t('announcement.close')" @click="dismissModal">
          <UIcon name="i-lucide-x" />
        </button>
        <p class="announcement-modal__eyebrow">
          <UIcon name="i-lucide-megaphone" />
          {{ typeLabel(modalAnnouncement.type) }}
        </p>
        <h2 :id="`announcement-title-${modalAnnouncement.id}`">{{ modalAnnouncement.title }}</h2>
        <p v-if="modalAnnouncement.summary" class="announcement-modal__summary">{{ modalAnnouncement.summary }}</p>
        <div class="announcement-modal__content">{{ modalAnnouncement.content }}</div>
        <p class="announcement-modal__date">
          {{ t('announcement.published', { date: formatDate(modalAnnouncement.published_at ?? modalAnnouncement.effective_from) }) }}
        </p>
        <div class="announcement-modal__actions">
          <button class="announcement-modal__secondary" type="button" @click="viewDetails">{{ t('common.actions.viewDetails') }}</button>
          <button
            v-if="modalAnnouncement.type === 'IMPORTANT'"
            class="announcement-modal__secondary"
            type="button"
            @click="acknowledgeModal"
          >
            {{ t('announcement.understand') }}
          </button>
          <button v-if="modalAnnouncement.action_url" class="announcement-modal__primary" type="button" @click="openAction">
            {{ t('common.actions.continue') }} <UIcon name="i-lucide-arrow-up-right" />
          </button>
          <button v-else class="announcement-modal__primary" type="button" @click="dismissModal">{{ t('common.actions.close') }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<style scoped>
.customer-announcement-banner {
  position: relative;
  z-index: 50;
  border-bottom: 1px solid rgba(255, 255, 255, .24);
  color: #fff;
  background: var(--store-wine, #6e2838);
}

.customer-announcement-banner__content {
  width: min(100% - 36px, 1440px);
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin: 0 auto;
  padding: 7px 0;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: .055em;
  text-transform: uppercase;
}

.customer-announcement-banner__type {
  opacity: .72;
}

.customer-announcement-banner__title {
  max-width: min(46vw, 580px);
  overflow: hidden;
  color: inherit;
  font-weight: 700;
  text-decoration: none;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.customer-announcement-banner__summary {
  max-width: min(35vw, 420px);
  overflow: hidden;
  opacity: .82;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.customer-announcement-banner__more {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.customer-announcement-banner__more .iconify {
  width: 12px;
  height: 12px;
}

.announcement-modal-backdrop {
  position: fixed;
  z-index: 200;
  inset: 0;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(33, 22, 24, .57);
}

.announcement-modal {
  position: relative;
  width: min(100%, 620px);
  max-height: min(720px, calc(100vh - 48px));
  overflow-y: auto;
  padding: clamp(30px, 5vw, 52px);
  border: 1px solid var(--store-ink, #271e1f);
  color: var(--store-ink, #271e1f);
  background: var(--store-paper, #fcfaf6);
  box-shadow: 0 28px 80px rgba(30, 16, 20, .34);
}

.announcement-modal__close {
  position: absolute;
  top: 15px;
  right: 15px;
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  padding: 0;
  border: 0;
  color: inherit;
  background: transparent;
  cursor: pointer;
}

.announcement-modal__close .iconify { width: 19px; height: 19px; }

.announcement-modal__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 0 0 14px;
  color: var(--store-wine, #6e2838);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.announcement-modal__eyebrow .iconify { width: 15px; height: 15px; }

.announcement-modal h2 {
  max-width: 510px;
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(30px, 4vw, 46px);
  font-weight: 500;
  line-height: 1.04;
}

.announcement-modal__summary {
  margin: 18px 0 0;
  color: var(--store-muted, #756c68);
  font-size: 15px;
  line-height: 1.65;
}

.announcement-modal__content {
  margin-top: 23px;
  padding-top: 22px;
  border-top: 1px solid var(--store-line, #ddd5cf);
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.announcement-modal__date {
  margin: 19px 0 0;
  color: var(--store-muted, #756c68);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .04em;
}

.announcement-modal__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
  margin-top: 28px;
}

.announcement-modal__actions button {
  min-height: 40px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 16px;
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  font-weight: 600;
  letter-spacing: .065em;
  text-transform: uppercase;
  cursor: pointer;
}

.announcement-modal__primary {
  border: 1px solid var(--store-ink, #271e1f);
  color: #fff;
  background: var(--store-ink, #271e1f);
}

.announcement-modal__secondary {
  border: 1px solid var(--store-line, #ddd5cf);
  color: inherit;
  background: transparent;
}

.announcement-modal__primary .iconify { width: 13px; height: 13px; }

@media (max-width: 820px) {
  .customer-announcement-banner__content {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    justify-content: stretch;
    gap: 12px;
  }

  .customer-announcement-banner__type,
  .customer-announcement-banner__summary { display: none; }

  .customer-announcement-banner__title {
    min-width: 0;
    max-width: none;
  }

  .customer-announcement-banner__more { white-space: nowrap; }

  .announcement-modal-backdrop { padding: 13px; }
  .announcement-modal { max-height: calc(100vh - 26px); padding: 35px 25px 27px; }
  .announcement-modal__actions > button { flex: 1 1 auto; }
}
</style>
