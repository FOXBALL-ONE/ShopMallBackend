<script setup lang="ts">
import {
  CUSTOMER_ANNOUNCEMENT_TYPE_OPTIONS,
  type CustomerAnnouncementHistoryItem,
  type CustomerAnnouncementSummary,
  type CustomerAnnouncementType,
} from '~/types/announcement'

const PAGE_SIZE = 12
const announcementApi = useAnnouncementApi()
const { formatDate: formatLocalizedDate, t } = useStorefrontI18n()
const currentAnnouncements = ref<CustomerAnnouncementSummary[]>([])
const historyItems = ref<CustomerAnnouncementHistoryItem[]>([])
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const isCurrentLoading = ref(true)
const isHistoryLoading = ref(true)
const errorMessage = ref('')
const filters = reactive({
  keyword: '',
  type: '' as CustomerAnnouncementType | '',
  year: '' as number | '',
})

const yearOptions = computed(() => {
  const currentYear = new Date().getFullYear()
  return Array.from({ length: 10 }, (_, index) => currentYear - index)
})

function typeLabel(type: CustomerAnnouncementType) {
  return t(`announcement.types.${type.toLowerCase()}`)
}

function formatDate(value: string | null) {
  return value ? formatLocalizedDate(value, 'long') : t('announcement.page.dateUnavailable')
}

async function loadCurrentAnnouncements() {
  isCurrentLoading.value = true
  try {
    const response = await announcementApi.getCurrent(true, 10)
    currentAnnouncements.value = response.items
  } catch {
    currentAnnouncements.value = []
  } finally {
    isCurrentLoading.value = false
  }
}

async function loadHistory() {
  isHistoryLoading.value = true
  errorMessage.value = ''
  try {
    const response = await announcementApi.getHistory({
      page: page.value,
      size: PAGE_SIZE,
      ...(filters.type ? { type: filters.type } : {}),
      ...(filters.year ? { year: filters.year } : {}),
      ...(filters.keyword.trim() ? { keyword: filters.keyword.trim() } : {}),
    })
    historyItems.value = response.items
    page.value = response.page
    totalPages.value = response.total_pages
    totalElements.value = response.total_elements
  } catch {
    historyItems.value = []
    totalPages.value = 0
    totalElements.value = 0
    errorMessage.value = t('announcement.page.loadFailed')
  } finally {
    isHistoryLoading.value = false
  }
}

function applyFilters() {
  page.value = 0
  void loadHistory()
}

function resetFilters() {
  filters.keyword = ''
  filters.type = ''
  filters.year = ''
  page.value = 0
  void loadHistory()
}

function changePage(nextPage: number) {
  if (nextPage < 0 || nextPage >= totalPages.value || nextPage === page.value) return
  page.value = nextPage
  void loadHistory().then(() => {
    document.querySelector('.announcement-history')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  })
}

onMounted(() => {
  void Promise.all([loadCurrentAnnouncements(), loadHistory()])
})

useHead(() => ({
  title: t('announcement.page.seoTitle'),
  meta: [
    { name: 'description', content: t('announcement.page.seoDescription') },
  ],
}))
</script>

<template>
  <main class="store-page announcement-page">
    <StoreHeader />

    <header class="announcement-hero">
      <div class="store-container">
        <p>{{ t('announcement.page.heroEyebrow') }}</p>
        <h1>{{ t('announcement.page.heroTitleStart') }}<br><em>{{ t('announcement.page.heroTitleEmphasis') }}</em></h1>
        <span>{{ t('announcement.page.heroCopy') }}</span>
      </div>
    </header>

    <section class="store-container announcement-current" aria-labelledby="current-announcements-heading">
      <div class="announcement-section-heading">
        <div>
          <p>{{ t('announcement.page.currentEyebrow') }}</p>
          <h2 id="current-announcements-heading">{{ t('announcement.page.currentTitle') }}</h2>
        </div>
        <span>{{ t('announcement.page.active', { count: currentAnnouncements.length }) }}</span>
      </div>

      <div v-if="isCurrentLoading" class="announcement-state" aria-live="polite">
        <UIcon name="i-lucide-loader-circle" class="is-spinning" /> {{ t('announcement.page.loadingCurrent') }}
      </div>
      <div v-else-if="currentAnnouncements.length" class="announcement-current-grid">
        <NuxtLink
          v-for="announcement in currentAnnouncements"
          :key="announcement.id"
          :to="`/announcements/${announcement.id}`"
          class="announcement-current-card"
          :class="`is-${announcement.type.toLowerCase()}`"
        >
          <div class="announcement-card-meta">
            <span>{{ typeLabel(announcement.type) }}</span>
            <span>{{ formatDate(announcement.published_at ?? announcement.effective_from) }}</span>
          </div>
          <h3>{{ announcement.title }}</h3>
          <p>{{ announcement.summary }}</p>
          <span class="announcement-card-link">{{ t('announcement.page.readNotice') }} <UIcon name="i-lucide-arrow-up-right" /></span>
        </NuxtLink>
      </div>
      <div v-else class="announcement-state">
        <UIcon name="i-lucide-circle-check-big" /> {{ t('announcement.page.noCurrent') }}
      </div>
    </section>

    <section class="announcement-history" aria-labelledby="history-announcements-heading">
      <div class="store-container">
        <div class="announcement-section-heading">
          <div>
            <p>{{ t('announcement.page.archiveEyebrow') }}</p>
            <h2 id="history-announcements-heading">{{ t('announcement.page.archiveTitle') }}</h2>
          </div>
          <span>{{ t('announcement.page.records', totalElements) }}</span>
        </div>

        <form class="announcement-filters" @submit.prevent="applyFilters">
          <label class="announcement-filter-keyword">
            <span>{{ t('announcement.page.searchLabel') }}</span>
            <span class="announcement-input-wrap">
              <UIcon name="i-lucide-search" />
              <input v-model="filters.keyword" type="search" maxlength="120" :placeholder="t('announcement.page.searchPlaceholder')">
            </span>
          </label>
          <label>
            <span>{{ t('announcement.page.type') }}</span>
            <select v-model="filters.type" @change="applyFilters">
              <option value="">{{ t('announcement.page.allTypes') }}</option>
              <option v-for="option in CUSTOMER_ANNOUNCEMENT_TYPE_OPTIONS" :key="option.value" :value="option.value">
                {{ typeLabel(option.value) }}
              </option>
            </select>
          </label>
          <label>
            <span>{{ t('announcement.page.year') }}</span>
            <select v-model="filters.year" @change="applyFilters">
              <option value="">{{ t('announcement.page.allYears') }}</option>
              <option v-for="year in yearOptions" :key="year" :value="year">{{ year }}</option>
            </select>
          </label>
          <button type="submit">{{ t('announcement.page.search') }}</button>
          <button class="announcement-reset" type="button" @click="resetFilters">{{ t('announcement.page.reset') }}</button>
        </form>

        <div v-if="isHistoryLoading" class="announcement-state announcement-state--history" aria-live="polite">
          <UIcon name="i-lucide-loader-circle" class="is-spinning" /> {{ t('announcement.page.loadingHistory') }}
        </div>
        <div v-else-if="errorMessage" class="announcement-state announcement-state--history" role="alert">
          <UIcon name="i-lucide-circle-alert" />
          <span>{{ errorMessage }}</span>
          <button type="button" @click="loadHistory">{{ t('common.actions.retry') }}</button>
        </div>
        <div v-else-if="historyItems.length" class="announcement-history-list">
          <article v-for="announcement in historyItems" :key="announcement.id" class="announcement-history-card">
            <div class="announcement-history-card__date">
              <strong>{{ new Date(announcement.published_at ?? announcement.effective_from).getFullYear() }}</strong>
              <span>{{ formatDate(announcement.published_at ?? announcement.effective_from) }}</span>
            </div>
            <div class="announcement-history-card__copy">
              <div class="announcement-card-meta">
                <span>{{ typeLabel(announcement.type) }}</span>
                <span v-if="announcement.is_read">{{ t('announcement.page.read') }}</span>
              </div>
              <h3>{{ announcement.title }}</h3>
              <p>{{ announcement.summary }}</p>
            </div>
            <NuxtLink :to="`/announcements/${announcement.id}`" :aria-label="t('announcement.page.readAria', { title: announcement.title })">
              <span>{{ t('announcement.page.readNotice') }}</span><UIcon name="i-lucide-arrow-right" />
            </NuxtLink>
          </article>
        </div>
        <div v-else class="announcement-state announcement-state--history">
          <UIcon name="i-lucide-inbox" /> {{ t('announcement.page.noHistory') }}
        </div>

        <nav v-if="totalPages > 1" class="announcement-pagination" :aria-label="t('announcement.page.pagination')">
          <button type="button" :disabled="page === 0" @click="changePage(page - 1)">
            <UIcon name="i-lucide-arrow-left" /> {{ t('announcement.page.previous') }}
          </button>
          <span>{{ t('announcement.page.page', { current: page + 1, total: totalPages }) }}</span>
          <button type="button" :disabled="page >= totalPages - 1" @click="changePage(page + 1)">
            {{ t('announcement.page.next') }} <UIcon name="i-lucide-arrow-right" />
          </button>
        </nav>
      </div>
    </section>

    <StoreFooter />
  </main>
</template>

<style scoped>
.announcement-page {
  min-height: 100vh;
  color: var(--store-ink);
  background: var(--store-paper);
}

.announcement-hero {
  padding: clamp(76px, 10vw, 145px) 0 clamp(68px, 8vw, 118px);
  color: #fff;
  background:
    linear-gradient(105deg, rgba(52, 25, 31, .96), rgba(110, 40, 56, .8)),
    radial-gradient(circle at 82% 16%, rgba(255, 255, 255, .2), transparent 32%);
}

.announcement-hero p,
.announcement-section-heading p {
  margin: 0 0 13px;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  font-weight: 600;
  letter-spacing: .12em;
  text-transform: uppercase;
}

.announcement-hero h1 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(50px, 7.2vw, 104px);
  font-weight: 500;
  letter-spacing: -.035em;
  line-height: .88;
}

.announcement-hero h1 em { font-weight: 400; }

.announcement-hero .store-container > span {
  width: min(100%, 540px);
  display: block;
  margin-top: 31px;
  color: rgba(255, 255, 255, .78);
  font-size: 13px;
  line-height: 1.75;
}

.announcement-current {
  padding-top: clamp(62px, 8vw, 104px);
  padding-bottom: clamp(70px, 9vw, 126px);
}

.announcement-section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 25px;
  padding-bottom: 24px;
  border-bottom: 1px solid var(--store-ink);
}

.announcement-section-heading p { color: var(--store-wine); }

.announcement-section-heading h2 {
  margin: 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(35px, 4.4vw, 57px);
  font-weight: 500;
}

.announcement-section-heading > span {
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.announcement-current-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 1px;
  margin-top: 30px;
  background: var(--store-line);
}

.announcement-current-card {
  min-height: 285px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  padding: clamp(26px, 4vw, 48px);
  color: var(--store-ink);
  background: var(--store-paper);
  text-decoration: none;
  transition: color .2s ease, background .2s ease;
}

.announcement-current-card:hover {
  color: #fff;
  background: var(--store-wine);
}

.announcement-current-card.is-important { box-shadow: inset 4px 0 0 var(--store-wine); }
.announcement-current-card:hover .announcement-card-meta { color: rgba(255, 255, 255, .7); }

.announcement-card-meta {
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: 20px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-transform: uppercase;
  transition: color .2s ease;
}

.announcement-current-card h3,
.announcement-history-card h3 {
  margin: 33px 0 0;
  font-family: 'Playfair Display', Georgia, serif;
  font-size: clamp(27px, 3vw, 40px);
  font-weight: 500;
  line-height: 1.08;
}

.announcement-current-card p,
.announcement-history-card p {
  margin: 17px 0 0;
  color: var(--store-muted);
  font-size: 12px;
  line-height: 1.7;
}

.announcement-current-card:hover p { color: rgba(255, 255, 255, .76); }

.announcement-card-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: auto;
  padding-top: 28px;
  border-bottom: 1px solid currentColor;
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .08em;
  text-transform: uppercase;
}

.announcement-card-link .iconify { width: 12px; height: 12px; }

.announcement-history {
  padding: clamp(70px, 9vw, 120px) 0;
  background: #f3eee8;
}

.announcement-filters {
  display: grid;
  grid-template-columns: minmax(220px, 1.5fr) minmax(150px, .65fr) minmax(130px, .55fr) auto auto;
  align-items: end;
  gap: 10px;
  margin: 28px 0 34px;
}

.announcement-filters label {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
  letter-spacing: .06em;
  text-transform: uppercase;
}

.announcement-filters input,
.announcement-filters select,
.announcement-filters button,
.announcement-state button,
.announcement-pagination button {
  height: 43px;
  border: 1px solid var(--store-line);
  border-radius: 0;
  color: var(--store-ink);
  background: var(--store-paper);
  font-family: 'DM Mono', monospace;
  font-size: 10px;
}

.announcement-filters input,
.announcement-filters select { width: 100%; padding: 0 13px; outline: 0; }

.announcement-input-wrap { position: relative; display: block; }
.announcement-input-wrap .iconify { position: absolute; top: 14px; left: 13px; width: 15px; height: 15px; }
.announcement-input-wrap input { padding-left: 39px; }

.announcement-filters button,
.announcement-state button,
.announcement-pagination button {
  padding: 0 17px;
  border-color: var(--store-ink);
  color: #fff;
  background: var(--store-ink);
  letter-spacing: .06em;
  text-transform: uppercase;
  cursor: pointer;
}

.announcement-filters .announcement-reset {
  color: var(--store-ink);
  background: transparent;
}

.announcement-history-list { border-top: 1px solid var(--store-line); }

.announcement-history-card {
  display: grid;
  grid-template-columns: 145px minmax(0, 1fr) auto;
  align-items: center;
  gap: clamp(22px, 4vw, 58px);
  min-height: 205px;
  padding: 31px 0;
  border-bottom: 1px solid var(--store-line);
}

.announcement-history-card__date {
  display: flex;
  flex-direction: column;
  gap: 5px;
  color: var(--store-muted);
  font-family: 'DM Mono', monospace;
  font-size: 9px;
}

.announcement-history-card__date strong {
  color: var(--store-wine);
  font-family: 'Playfair Display', Georgia, serif;
  font-size: 44px;
  font-weight: 400;
}

.announcement-history-card h3 { margin-top: 19px; font-size: clamp(25px, 3vw, 36px); }

.announcement-history-card > a {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  border: 1px solid var(--store-ink);
  color: var(--store-ink);
  text-decoration: none;
  transition: color .2s ease, background .2s ease;
}

.announcement-history-card > a span { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); }
.announcement-history-card > a:hover { color: #fff; background: var(--store-ink); }
.announcement-history-card > a .iconify { width: 17px; height: 17px; }

.announcement-state {
  min-height: 150px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: var(--store-muted);
  font-size: 12px;
}

.announcement-state--history { min-height: 230px; }
.announcement-state .iconify { width: 18px; height: 18px; }
.announcement-state button { height: 36px; margin-left: 6px; }

.announcement-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 24px;
  margin-top: 38px;
}

.announcement-pagination button { display: inline-flex; align-items: center; gap: 6px; }
.announcement-pagination button:disabled { cursor: not-allowed; opacity: .35; }
.announcement-pagination span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 9px; text-transform: uppercase; }
.announcement-pagination .iconify { width: 13px; height: 13px; }

.is-spinning { animation: announcement-spin .8s linear infinite; }
@keyframes announcement-spin { to { transform: rotate(360deg); } }

@media (max-width: 900px) {
  .announcement-current-grid { grid-template-columns: 1fr; }
  .announcement-filters { grid-template-columns: 1fr 1fr; }
  .announcement-filter-keyword { grid-column: 1 / -1; }
  .announcement-history-card { grid-template-columns: 105px minmax(0, 1fr) auto; }
}

@media (max-width: 620px) {
  .announcement-section-heading { align-items: flex-start; flex-direction: column; }
  .announcement-current-card { min-height: 250px; }
  .announcement-filters { grid-template-columns: 1fr; }
  .announcement-filter-keyword { grid-column: auto; }
  .announcement-history-card { grid-template-columns: 1fr auto; gap: 16px; }
  .announcement-history-card__date { grid-column: 1 / -1; flex-direction: row; align-items: baseline; }
  .announcement-history-card__date strong { font-size: 28px; }
  .announcement-history-card__copy { min-width: 0; }
  .announcement-history-card > a { width: 42px; height: 42px; }
  .announcement-pagination { justify-content: space-between; gap: 8px; }
  .announcement-pagination button { padding: 0 10px; }
}
</style>
