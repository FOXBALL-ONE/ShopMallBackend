<script setup lang="ts">
import { CUSTOMER_ANNOUNCEMENT_TYPE_OPTIONS, type CustomerAnnouncementDetail } from '~/types/announcement'
import { isSafeHttpsAnnouncementActionUrl, isSafeInternalAnnouncementActionUrl } from '~/utils/announcementActionUrl'

const route = useRoute()
const announcementApi = useAnnouncementApi()
const announcementClientState = useAnnouncementClientState()
const customerSession = useCustomerSession()
const announcement = ref<CustomerAnnouncementDetail | null>(null)
const isLoading = ref(true)
const errorMessage = ref('')

function typeLabel(type: CustomerAnnouncementDetail['type']) {
  return CUSTOMER_ANNOUNCEMENT_TYPE_OPTIONS.find(option => option.value === type)?.label ?? type
}

function formatDate(value: string | null) {
  if (!value) return 'Open ended'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'long', timeStyle: 'short' }).format(date)
}

function reportSeen(id: number, ownerId = customerSession.userId.value ?? null) {
  const userId = ownerId
  const storedState = announcementClientState.record(id, 'SEEN', userId)
  if (userId !== null) {
    void announcementApi.recordState(id, storedState.state)
      .then(() => announcementClientState.markSynced(id, storedState.state, storedState.lastSeenAt, userId))
      .catch(() => undefined)
  }
}

async function loadAnnouncement() {
  const ownerId = customerSession.userId.value ?? null
  const id = Number(route.params.id)
  announcement.value = null
  errorMessage.value = ''
  isLoading.value = true

  if (!Number.isSafeInteger(id) || id <= 0) {
    errorMessage.value = 'This notice address is invalid.'
    isLoading.value = false
    return
  }

  try {
    const response = await announcementApi.getOne(id)
    announcement.value = response
    reportSeen(response.id, ownerId)
  } catch {
    errorMessage.value = 'This notice is unavailable or is no longer public.'
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  const userId = customerSession.userId.value ?? null
  if (userId !== null) announcementClientState.claimAnonymousStates(userId)
  announcementClientState.load(userId)
  void loadAnnouncement()
})

watch(() => route.params.id, (id, previousId) => {
  if (previousId !== undefined && id !== previousId) void loadAnnouncement()
})

useHead({
  title: computed(() => announcement.value ? `${announcement.value.title} | PELISSA°` : 'Notice | PELISSA°'),
})
</script>

<template>
  <main class="store-page announcement-detail-page">
    <StoreHeader />

    <section class="store-container announcement-detail-shell">
      <NuxtLink class="announcement-detail-back" to="/announcements">
        <UIcon name="i-lucide-arrow-left" /> All notices
      </NuxtLink>

      <div v-if="isLoading" class="announcement-detail-state" aria-live="polite">
        <UIcon name="i-lucide-loader-circle" class="is-spinning" /> Loading notice…
      </div>

      <div v-else-if="errorMessage" class="announcement-detail-state" role="alert">
        <UIcon name="i-lucide-circle-alert" />
        <div>
          <h1>Notice unavailable</h1>
          <p>{{ errorMessage }}</p>
          <NuxtLink to="/announcements">Return to all notices</NuxtLink>
        </div>
      </div>

      <article v-else-if="announcement" class="announcement-detail">
        <header>
          <div class="announcement-detail-meta">
            <span>{{ typeLabel(announcement.type) }}</span>
            <span v-if="announcement.is_read">Previously read</span>
          </div>
          <h1>{{ announcement.title }}</h1>
          <p>{{ announcement.summary }}</p>
        </header>

        <div class="announcement-detail-grid">
          <aside>
            <dl>
              <div>
                <dt>Published</dt>
                <dd>{{ formatDate(announcement.published_at ?? announcement.effective_from) }}</dd>
              </div>
              <div>
                <dt>Effective from</dt>
                <dd>{{ formatDate(announcement.effective_from) }}</dd>
              </div>
              <div>
                <dt>Effective until</dt>
                <dd>{{ formatDate(announcement.effective_until) }}</dd>
              </div>
            </dl>
          </aside>

          <div class="announcement-detail-content">
            <div>{{ announcement.content }}</div>

            <div
              v-if="isSafeInternalAnnouncementActionUrl(announcement.action_url) || isSafeHttpsAnnouncementActionUrl(announcement.action_url)"
              class="announcement-detail-action"
            >
              <NuxtLink v-if="isSafeInternalAnnouncementActionUrl(announcement.action_url)" :to="announcement.action_url || '/'" @click="reportSeen(announcement.id)">
                Continue <UIcon name="i-lucide-arrow-up-right" />
              </NuxtLink>
              <a
                v-else-if="isSafeHttpsAnnouncementActionUrl(announcement.action_url)"
                :href="announcement.action_url || undefined"
                target="_blank"
                rel="noopener noreferrer"
                @click="reportSeen(announcement.id)"
              >
                Open related page <UIcon name="i-lucide-arrow-up-right" />
              </a>
            </div>
          </div>
        </div>
      </article>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.announcement-detail-page {
  min-height: 100vh;
  color: var(--store-ink);
  background: var(--store-paper);
}

.announcement-detail-shell {
  min-height: 620px;
  padding-top: 43px;
  padding-bottom: clamp(90px, 11vw, 155px);
}

.announcement-detail-back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 3px;
  border-bottom: 1px solid currentColor;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .07em;
  text-decoration: none;
  text-transform: uppercase;
}

.announcement-detail-back .iconify { width: 13px; height: 13px; }

.announcement-detail { margin-top: clamp(65px, 8vw, 110px); }

.announcement-detail > header {
  width: min(100%, 980px);
  padding-bottom: clamp(48px, 7vw, 86px);
}

.announcement-detail-meta {
  display: flex;
  gap: 18px;
  color: var(--store-wine);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: .1em;
  text-transform: uppercase;
}

.announcement-detail-meta span + span { color: var(--store-muted); }

.announcement-detail h1,
.announcement-detail-state h1 {
  margin: 22px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(48px, 7.3vw, 98px);
  font-weight: 500;
  letter-spacing: -.035em;
  line-height: .94;
}

.announcement-detail > header > p {
  width: min(100%, 720px);
  margin: 28px 0 0;
  color: var(--store-muted);
  font-size: 15px;
  line-height: 1.75;
}

.announcement-detail-grid {
  display: grid;
  grid-template-columns: minmax(190px, 270px) minmax(0, 760px);
  gap: clamp(45px, 9vw, 135px);
  padding-top: clamp(48px, 7vw, 82px);
  border-top: 1px solid var(--store-ink);
}

.announcement-detail dl { margin: 0; }
.announcement-detail dl > div { padding: 0 0 20px; margin-bottom: 20px; border-bottom: 1px solid var(--store-line); }
.announcement-detail dt { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }
.announcement-detail dd { margin: 8px 0 0; font-size: 11px; line-height: 1.5; }

.announcement-detail-content > div:first-child {
  font-size: 15px;
  line-height: 1.95;
  white-space: pre-wrap;
}

.announcement-detail-action {
  margin-top: 42px;
  padding-top: 28px;
  border-top: 1px solid var(--store-line);
}

.announcement-detail-action a {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 20px;
  border: 1px solid var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
  letter-spacing: .07em;
  text-decoration: none;
  text-transform: uppercase;
}

.announcement-detail-action .iconify { width: 13px; height: 13px; }

.announcement-detail-state {
  min-height: 440px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--store-muted);
}

.announcement-detail-state > .iconify { width: 24px; height: 24px; }
.announcement-detail-state h1 { margin: 0; color: var(--store-ink); font-size: clamp(38px, 5vw, 62px); }
.announcement-detail-state p { margin: 13px 0 20px; }
.announcement-detail-state a { color: var(--store-wine); text-underline-offset: 4px; }

.is-spinning { animation: announcement-spin .8s linear infinite; }
@keyframes announcement-spin { to { transform: rotate(360deg); } }

@media (max-width: 760px) {
  .announcement-detail-grid { grid-template-columns: 1fr; gap: 35px; }
  .announcement-detail dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
  .announcement-detail-state { min-height: 360px; align-items: flex-start; padding-top: 100px; }
}

@media (max-width: 500px) {
  .announcement-detail dl { grid-template-columns: 1fr; }
}
</style>
