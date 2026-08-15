<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import type { CustomerOrder, CustomerProfile } from '~/types/customer-account'
import type {
  CustomerSupportTicketDetail,
  CustomerSupportTicketMessage,
  CustomerSupportTicketSummary,
  SupportServiceType,
  SupportTicketOption,
  SupportTicketPriority,
  SupportTicketStatus
} from '~/types/support-ticket'
import { customerRequestMessage, useCustomerAccountApi } from '~/composables/useCustomerAccountApi'
import { useSupportTicketApi } from '~/composables/useSupportTicketApi'

type TicketFilter = 'ALL' | SupportTicketStatus
type TicketTimelineItem = CustomerSupportTicketMessage & { key: string }

definePageMeta({ middleware: ['customer-auth'] })

const { currentLocale, formatDate, t } = useStorefrontI18n()

useHead(() => ({
  title: t('accountSupport.seoTitle'),
  meta: [{ name: 'description', content: t('accountSupport.seoDescription') }]
}))

const accountApi = useCustomerAccountApi()
const ticketApi = useSupportTicketApi()
const session = useCustomerSession()
const route = useRoute()
const router = useRouter()
const toast = useToast()

const profile = ref<CustomerProfile | null>(null)
const orders = ref<CustomerOrder[]>([])
const tickets = ref<CustomerSupportTicketSummary[]>([])
const serviceOptionValues = ref<SupportServiceType[]>(['PRE_SALES', 'AFTER_SALES'])
const priorityOptionValues = ref<SupportTicketPriority[]>(['LOW', 'MEDIUM', 'HIGH'])
const activeFilter = ref<TicketFilter>('ALL')
const page = ref(1)
const totalPages = ref(1)
const pageSize = 10
const isLoading = ref(true)
const isRefreshing = ref(false)
const requestError = ref('')

const isCreateFormOpen = ref(false)
const isCreating = ref(false)
const createError = ref('')
const quickCreateOrderNo = ref('')
const ticketForm = reactive({
  serviceType: 'PRE_SALES' as SupportServiceType,
  priority: 'LOW' as SupportTicketPriority,
  orderNo: '',
  subject: '',
  content: ''
})

const selectedTicketId = ref<number | null>(null)
const ticketDetail = ref<CustomerSupportTicketDetail | null>(null)
const isDetailLoading = ref(false)
const detailError = ref('')
const messagePage = ref(1)
const isLoadingEarlierMessages = ref(false)
const messageDraft = ref('')
const messageFiles = ref<File[]>([])
const messageFileInput = ref<HTMLInputElement | null>(null)
const messageError = ref('')
const isSendingMessage = ref(false)
const isClosingTicket = ref(false)

const serviceOptions = computed<SupportTicketOption<SupportServiceType>[]>(() => serviceOptionValues.value.map(value => ({ value, label: serviceTypeLabel(value) })))
const priorityOptions = computed<SupportTicketOption<SupportTicketPriority>[]>(() => priorityOptionValues.value.map(value => ({ value, label: priorityLabel(value) })))
const statusFilterOptions = computed<Array<{ value: TicketFilter; label: string }>>(() => [
  { value: 'ALL', label: t('accountSupport.status.ALL') },
  { value: 'OPEN', label: t('accountSupport.status.OPEN') },
  { value: 'IN_PROGRESS', label: t('accountSupport.status.IN_PROGRESS') },
  { value: 'RESOLVED', label: t('accountSupport.status.RESOLVED') },
  { value: 'CLOSED', label: t('accountSupport.status.CLOSED') }
])
const availableOrderOptions = computed(() => orders.value.map(order => order.order_no))
const pageLabel = computed(() => `${page.value} / ${Math.max(totalPages.value, 1)}`)
const resultLabel = computed(() => t('accountSupport.results', tickets.value.length))
const selectedTicketIsClosed = computed(() => ticketDetail.value?.status === 'CLOSED')
const canLoadEarlierMessages = computed(() => Boolean(ticketDetail.value && messagePage.value < Math.max(ticketDetail.value.message_pagination.count, 1)))
const ticketTimeline = computed<TicketTimelineItem[]>(() => {
  if (!ticketDetail.value) return []
  return [{
    id: 0,
    key: 'opening-request',
    sender_type: 'CUSTOMER',
    content: ticketDetail.value.content,
    attachments: [],
    created_at: ticketDetail.value.created_at
  }, ...ticketDetail.value.messages.map(message => ({ ...message, key: `message-${message.id}` }))]
})

function serviceTypeLabel(value: SupportServiceType) {
  return t(`accountSupport.service.${value}`)
}
function priorityLabel(value: SupportTicketPriority) {
  return t(`accountSupport.priority.${value}`)
}
function ticketStatusLabel(value: SupportTicketStatus) {
  return t(`accountSupport.status.${value}`)
}
function ticketStatusTone(value: SupportTicketStatus) {
  if (value === 'CLOSED') return 'muted'
  if (value === 'RESOLVED') return 'success'
  if (value === 'IN_PROGRESS') return 'accent'
  return 'warm'
}
function formatBytes(size: number) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${Math.round(size / 1024)} KB`
  return `${new Intl.NumberFormat(currentLocale.value, { maximumFractionDigits: 1 }).format(size / (1024 * 1024))} MB`
}
function resetTicketForm() {
  ticketForm.serviceType = 'PRE_SALES'
  ticketForm.priority = priorityOptions.value.some(option => option.value === 'LOW') ? 'LOW' : priorityOptions.value[0]?.value || 'LOW'
  ticketForm.orderNo = ''
  ticketForm.subject = ''
  ticketForm.content = ''
  createError.value = ''
}
function closeCreateForm() {
  if (isCreating.value) return
  isCreateFormOpen.value = false
  quickCreateOrderNo.value = ''
  resetTicketForm()
}
function openCreateForm(orderNo = '') {
  resetTicketForm()
  if (orderNo) {
    ticketForm.serviceType = 'AFTER_SALES'
    ticketForm.orderNo = orderNo
  }
  isCreateFormOpen.value = true
}

function setFilter(filter: TicketFilter) {
  if (filter === activeFilter.value || isLoading.value) return
  activeFilter.value = filter
  page.value = 1
  selectedTicketId.value = null
  ticketDetail.value = null
  void loadSupport(false)
}

async function loadSupport(showLoading = true) {
  const userId = await session.requireSignIn()
  if (!userId) {
    isLoading.value = false
    return
  }
  if (showLoading) isLoading.value = true
  else isRefreshing.value = true
  requestError.value = ''

  try {
    profile.value = profile.value || await accountApi.getProfile(userId)
  } catch (error: unknown) {
    requestError.value = customerRequestMessage(error, t('accountSupport.errors.account'))
    isLoading.value = false
    isRefreshing.value = false
    return
  }

  const [ticketsResult, optionsResult, ordersResult] = await Promise.allSettled([
    ticketApi.listTickets({ page: page.value, size: pageSize, status: activeFilter.value === 'ALL' ? undefined : activeFilter.value }),
    ticketApi.getOptions(),
    accountApi.getOrders(1, 100)
  ])

  if (ticketsResult.status === 'fulfilled') {
    tickets.value = ticketsResult.value.list || []
    totalPages.value = Math.max(Number(ticketsResult.value.pagination?.count || 1), 1)
    if (page.value > totalPages.value) {
      page.value = totalPages.value
      await loadSupport(showLoading)
      return
    }
  }
  if (optionsResult.status === 'fulfilled') {
    serviceOptionValues.value = optionsResult.value.service_types.length
      ? optionsResult.value.service_types.map(option => option.value)
      : ['PRE_SALES', 'AFTER_SALES']
    priorityOptionValues.value = optionsResult.value.priorities.length
      ? optionsResult.value.priorities.map(option => option.value)
      : ['LOW', 'MEDIUM', 'HIGH']
    if (priorityOptions.value.some(option => option.value === optionsResult.value.default_priority)) {
      ticketForm.priority = optionsResult.value.default_priority
    }
  }
  if (ordersResult.status === 'fulfilled') orders.value = ordersResult.value.list || []
  if (quickCreateOrderNo.value && !orders.value.some(order => order.order_no === quickCreateOrderNo.value)) {
    try {
      orders.value.unshift(await accountApi.getOrder(quickCreateOrderNo.value))
    } catch (error: unknown) {
      ticketForm.orderNo = ''
      createError.value = customerRequestMessage(error, t('accountSupport.errors.preselect'))
    }
  }

  const failedResults = [ticketsResult, optionsResult, ordersResult].filter(result => result.status === 'rejected')
  if (ticketsResult.status === 'rejected') {
    requestError.value = customerRequestMessage(ticketsResult.reason, t('accountSupport.errors.load'))
  } else if (failedResults.length) {
    requestError.value = t('accountSupport.errors.partial')
  }
  isLoading.value = false
  isRefreshing.value = false
}

async function changePage(nextPage: number) {
  if (nextPage < 1 || nextPage > totalPages.value || nextPage === page.value || isLoading.value) return
  page.value = nextPage
  selectedTicketId.value = null
  ticketDetail.value = null
  await loadSupport()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function openTicket(ticketId: number) {
  if (isDetailLoading.value) return
  if (selectedTicketId.value === ticketId) {
    selectedTicketId.value = null
    ticketDetail.value = null
    detailError.value = ''
    return
  }
  selectedTicketId.value = ticketId
  ticketDetail.value = null
  detailError.value = ''
  messageError.value = ''
  messageDraft.value = ''
  messageFiles.value = []
  messagePage.value = 1
  isDetailLoading.value = true
  try {
    ticketDetail.value = await ticketApi.getTicket(ticketId)
  } catch (error: unknown) {
    detailError.value = customerRequestMessage(error, t('accountSupport.errors.open'))
  } finally {
    isDetailLoading.value = false
  }
}

async function loadEarlierMessages() {
  if (!ticketDetail.value || !canLoadEarlierMessages.value || isLoadingEarlierMessages.value) return
  isLoadingEarlierMessages.value = true
  detailError.value = ''
  try {
    const nextPage = messagePage.value + 1
    const earlier = await ticketApi.getTicket(ticketDetail.value.id, nextPage)
    ticketDetail.value = { ...earlier, messages: [...earlier.messages, ...ticketDetail.value.messages] }
    messagePage.value = nextPage
  } catch (error: unknown) {
    detailError.value = customerRequestMessage(error, t('accountSupport.errors.earlier'))
  } finally {
    isLoadingEarlierMessages.value = false
  }
}

function validateTicketForm() {
  const subject = ticketForm.subject.trim()
  const content = ticketForm.content.trim()
  if (!subject || !content) {
    createError.value = t('accountSupport.errors.required')
    return false
  }
  if (subject.length > 120) {
    createError.value = t('accountSupport.errors.subjectLength')
    return false
  }
  if (content.length > 5000) {
    createError.value = t('accountSupport.errors.messageLength')
    return false
  }
  if (ticketForm.serviceType === 'AFTER_SALES' && !ticketForm.orderNo) {
    createError.value = t('accountSupport.errors.orderRequired')
    return false
  }
  return true
}

async function createTicket() {
  if (isCreating.value || !validateTicketForm()) return
  isCreating.value = true
  createError.value = ''
  try {
    const created = await ticketApi.createTicket({
      service_type: ticketForm.serviceType,
      priority: ticketForm.priority,
      order_no: ticketForm.serviceType === 'AFTER_SALES' ? ticketForm.orderNo : undefined,
      subject: ticketForm.subject.trim(),
      content: ticketForm.content.trim()
    })
    isCreateFormOpen.value = false
    quickCreateOrderNo.value = ''
    resetTicketForm()
    page.value = 1
    activeFilter.value = 'ALL'
    await loadSupport(false)
    await openTicket(created.id)
    toast.add({ title: t('accountSupport.toast.requestSent'), description: t('accountSupport.toast.requestSentCopy'), color: 'success' })
  } catch (error: unknown) {
    createError.value = customerRequestMessage(error, t('accountSupport.errors.create'))
    toast.add({ title: t('accountSupport.toast.requestNotSent'), description: createError.value, color: 'error' })
  } finally {
    isCreating.value = false
  }
}

function onMessageFilesChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = [...messageFiles.value, ...Array.from(input.files || [])]
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0)
  messageError.value = ''
  if (files.length > 10) messageError.value = t('accountSupport.errors.fileCount')
  else if (totalBytes > 50 * 1024 * 1024) messageError.value = t('accountSupport.errors.fileSize')
  else messageFiles.value = files
  input.value = ''
}

function removeMessageFile(index: number) {
  messageFiles.value.splice(index, 1)
  messageError.value = ''
}

async function sendMessage() {
  const ticket = ticketDetail.value
  const content = messageDraft.value.trim()
  if (!ticket || isSendingMessage.value || selectedTicketIsClosed.value) return
  if (!content && !messageFiles.value.length) {
    messageError.value = t('accountSupport.errors.replyRequired')
    return
  }
  if (content.length > 5000) {
    messageError.value = t('accountSupport.errors.replyLength')
    return
  }
  isSendingMessage.value = true
  messageError.value = ''
  try {
    const message = await ticketApi.sendTicketMessage(ticket.id, content || null, messageFiles.value)
    ticketDetail.value = { ...ticket, messages: [...ticket.messages, message], updated_at: message.created_at || ticket.updated_at }
    tickets.value = tickets.value.map(item => item.id === ticket.id ? { ...item, updated_at: message.created_at || item.updated_at } : item)
    messageDraft.value = ''
    messageFiles.value = []
    if (messageFileInput.value) messageFileInput.value.value = ''
    toast.add({ title: t('accountSupport.toast.replySent'), description: t('accountSupport.toast.replySentCopy'), color: 'success' })
  } catch (error: unknown) {
    messageError.value = customerRequestMessage(error, t('accountSupport.errors.reply'))
    toast.add({ title: t('accountSupport.toast.replyNotSent'), description: messageError.value, color: 'error' })
  } finally {
    isSendingMessage.value = false
  }
}

async function closeTicket() {
  const ticket = ticketDetail.value
  if (!ticket || selectedTicketIsClosed.value || isClosingTicket.value) return
  if (import.meta.client && !window.confirm(t('accountSupport.closeConfirm'))) return
  isClosingTicket.value = true
  detailError.value = ''
  try {
    const closed = await ticketApi.closeTicket(ticket.id)
    ticketDetail.value = { ...ticket, status: closed.status, closed_at: closed.closed_at, updated_at: closed.updated_at }
    tickets.value = tickets.value.map(item => item.id === ticket.id ? { ...item, status: closed.status, updated_at: closed.updated_at } : item)
    toast.add({ title: t('accountSupport.toast.requestClosed'), description: t('accountSupport.toast.requestClosedCopy'), color: 'success' })
  } catch (error: unknown) {
    detailError.value = customerRequestMessage(error, t('accountSupport.errors.close'))
    toast.add({ title: t('accountSupport.toast.requestNotClosed'), description: detailError.value, color: 'error' })
  } finally {
    isClosingTicket.value = false
  }
}

onMounted(async () => {
  const createQuery = Array.isArray(route.query.create) ? route.query.create[0] : route.query.create
  const orderQuery = Array.isArray(route.query.order_no) ? route.query.order_no[0] : route.query.order_no
  if (createQuery === '1') {
    quickCreateOrderNo.value = typeof orderQuery === 'string' ? orderQuery.trim() : ''
    openCreateForm(quickCreateOrderNo.value)

    const query = { ...route.query }
    delete query.create
    delete query.order_no
    await router.replace({ path: route.path, query })
  }
  await loadSupport()
})
</script>

<template>
  <CustomerAccountShell
    :eyebrow="t('accountSupport.eyebrow')"
    :title="t('accountSupport.title')"
    :intro="t('accountSupport.intro')"
    :profile="profile"
  >
    <div v-if="isLoading" class="tickets-loading" aria-live="polite">
      <div class="tickets-loading-toolbar" />
      <div v-for="index in 4" :key="index" class="tickets-loading-card" />
    </div>

    <template v-else>
       <div v-if="requestError" class="account-notice account-notice-warning" role="status">
        <UIcon name="i-lucide-info" /><span>{{ requestError }}</span><button type="button" @click="loadSupport(false)">{{ t('accountSupport.refresh') }}</button>
      </div>

      <section class="tickets-toolbar">
        <div>
          <p class="store-eyebrow">{{ t('accountSupport.conversations') }}</p>
          <h2>{{ resultLabel }}</h2>
          <p>{{ t('accountSupport.conversationsCopy') }}</p>
        </div>
        <div class="tickets-toolbar-actions">
          <button class="refresh-button" type="button" :disabled="isRefreshing" @click="loadSupport(false)">
            <UIcon :name="isRefreshing ? 'i-lucide-loader-circle' : 'i-lucide-refresh-cw'" :class="{ 'is-spinning': isRefreshing }" /> {{ isRefreshing ? t('accountSupport.refreshing') : t('accountSupport.refresh') }}
          </button>
          <button class="store-button ticket-create-button" type="button" @click="isCreateFormOpen ? closeCreateForm() : openCreateForm()">
            <UIcon :name="isCreateFormOpen ? 'i-lucide-x' : 'i-lucide-message-square-plus'" /> {{ isCreateFormOpen ? t('accountSupport.cancelRequest') : t('accountSupport.startRequest') }}
          </button>
        </div>
      </section>

      <section v-if="isCreateFormOpen" class="ticket-create-panel" aria-labelledby="ticket-create-heading">
        <div class="ticket-create-heading">
          <div><p class="panel-kicker">{{ t('accountSupport.newRequest') }}</p><h2 id="ticket-create-heading">{{ t('accountSupport.createTitle') }}</h2></div>
          <p>{{ t('accountSupport.createCopy') }}</p>
        </div>
        <form class="ticket-form" @submit.prevent="createTicket">
          <label class="ticket-field"><span>{{ t('accountSupport.helpType') }}</span><select v-model="ticketForm.serviceType"><option v-for="option in serviceOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
          <label class="ticket-field"><span>{{ t('accountSupport.priorityLabel') }}</span><select v-model="ticketForm.priority"><option v-for="option in priorityOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select></label>
          <label v-if="ticketForm.serviceType === 'AFTER_SALES'" class="ticket-field ticket-field-wide"><span>{{ t('accountSupport.orderLabel') }} <em>{{ t('accountSupport.orderRequired') }}</em></span><select v-model="ticketForm.orderNo"><option value="">{{ t('accountSupport.selectOrder') }}</option><option v-for="orderNo in availableOrderOptions" :key="orderNo" :value="orderNo">{{ orderNo }}</option></select><small v-if="!availableOrderOptions.length">{{ t('accountSupport.noOrders') }}</small></label>
          <label class="ticket-field ticket-field-wide"><span>{{ t('accountSupport.subject') }} <em>{{ ticketForm.subject.trim().length }}/120</em></span><input v-model="ticketForm.subject" type="text" maxlength="120" :placeholder="t('accountSupport.subjectPlaceholder')" autocomplete="off"></label>
          <label class="ticket-field ticket-field-wide"><span>{{ t('accountSupport.helpMessage') }} <em>{{ ticketForm.content.trim().length }}/5000</em></span><textarea v-model="ticketForm.content" rows="5" maxlength="5000" :placeholder="t('accountSupport.messagePlaceholder')" /><small>{{ t('accountSupport.attachmentHint') }}</small></label>
          <div v-if="createError" class="ticket-form-error" role="alert"><UIcon name="i-lucide-circle-alert" /> {{ createError }}</div>
          <div class="ticket-form-actions ticket-field-wide"><button class="text-button" type="button" :disabled="isCreating" @click="closeCreateForm">{{ t('accountSupport.cancel') }}</button><button class="store-button" type="submit" :disabled="isCreating"><UIcon :name="isCreating ? 'i-lucide-loader-circle' : 'i-lucide-send'" :class="{ 'is-spinning': isCreating }" /> {{ isCreating ? t('accountSupport.sending') : t('accountSupport.sendRequest') }}</button></div>
        </form>
      </section>

      <nav class="ticket-filters" :aria-label="t('accountSupport.filterLabel')">
        <button v-for="filter in statusFilterOptions" :key="filter.value" type="button" :class="{ active: activeFilter === filter.value }" @click="setFilter(filter.value)">{{ filter.label }}</button>
      </nav>

      <section v-if="!tickets.length" class="tickets-empty">
        <span class="empty-ticket-mark">05</span>
        <div>
          <p class="panel-kicker">{{ t('accountSupport.emptyEyebrow') }}</p>
          <h2>{{ activeFilter === 'ALL' ? t('accountSupport.emptyAllTitle') : t('accountSupport.emptyFilteredTitle') }}</h2>
          <p>{{ activeFilter === 'ALL' ? t('accountSupport.emptyAllCopy') : t('accountSupport.emptyFilteredCopy') }}</p>
          <button v-if="activeFilter === 'ALL'" class="store-button" type="button" @click="openCreateForm()">
            <UIcon name="i-lucide-message-square-plus" /> {{ t('accountSupport.startRequest') }}
          </button>
          <button v-else class="outline-button" type="button" @click="setFilter('ALL')">{{ t('accountSupport.viewAll') }}</button>
        </div>
      </section>

      <section v-else class="ticket-list" :aria-label="t('accountSupport.requestList')">
        <article v-for="ticket in tickets" :key="ticket.id" class="ticket-card" :class="{ 'is-selected': selectedTicketId === ticket.id }">
          <div class="ticket-card-index">{{ String(ticket.id).padStart(2, '0') }}</div>
          <div class="ticket-card-main">
            <div class="ticket-card-topline"><span class="ticket-service"><UIcon :name="ticket.service_type === 'AFTER_SALES' ? 'i-lucide-package-search' : 'i-lucide-sparkles'" /> {{ serviceTypeLabel(ticket.service_type) }}</span><span class="ticket-status" :class="`tone-${ticketStatusTone(ticket.status)}`">{{ ticketStatusLabel(ticket.status) }}</span></div>
            <h3>{{ ticket.subject }}</h3>
            <p v-if="ticket.admin_reply" class="ticket-card-reply"><span>{{ t('accountSupport.latestReply') }}</span>{{ ticket.admin_reply }}</p>
            <p v-else class="ticket-card-meta">{{ ticket.order_no ? t('accountSupport.linkedOrder', { orderNo: ticket.order_no }) : t('accountSupport.noOrder') }} · {{ t('accountSupport.priorityMeta', { priority: priorityLabel(ticket.priority) }) }}</p>
          </div>
          <div class="ticket-card-side"><time :datetime="ticket.updated_at || ticket.created_at || undefined">{{ formatDate(ticket.updated_at || ticket.created_at, 'long') }}</time><button class="ticket-open-button" type="button" :aria-expanded="selectedTicketId === ticket.id" @click="openTicket(ticket.id)">{{ selectedTicketId === ticket.id ? t('accountSupport.close') : t('accountSupport.open') }} <UIcon :name="selectedTicketId === ticket.id ? 'i-lucide-chevron-up' : 'i-lucide-arrow-up-right'" /></button></div>
        </article>
      </section>

      <section v-if="selectedTicketId" class="ticket-detail" aria-live="polite">
        <div v-if="isDetailLoading" class="ticket-detail-loading"><div class="ticket-detail-loading-line wide" /><div class="ticket-detail-loading-message" /><div class="ticket-detail-loading-message short" /></div>
        <div v-else-if="detailError && !ticketDetail" class="ticket-detail-error" role="alert"><UIcon name="i-lucide-circle-alert" /><div><strong>{{ t('accountSupport.openErrorTitle') }}</strong><span>{{ detailError }}</span></div><button class="outline-button" type="button" @click="openTicket(selectedTicketId)">{{ t('accountSupport.tryAgain') }}</button></div>
        <template v-else-if="ticketDetail">
          <header class="ticket-detail-header">
            <div><p class="panel-kicker">{{ t('accountSupport.requestNumber', { number: String(ticketDetail.id).padStart(5, '0') }) }}</p><h2>{{ ticketDetail.subject }}</h2><p>{{ serviceTypeLabel(ticketDetail.service_type) }} <span v-if="ticketDetail.order_no">{{ t('accountSupport.orderMeta', { orderNo: ticketDetail.order_no }) }}</span> · {{ t('accountSupport.priorityMeta', { priority: priorityLabel(ticketDetail.priority) }) }}</p></div>
            <div class="ticket-detail-actions"><span class="ticket-status" :class="`tone-${ticketStatusTone(ticketDetail.status)}`">{{ ticketStatusLabel(ticketDetail.status) }}</span><button v-if="!selectedTicketIsClosed" class="text-button text-button-danger" type="button" :disabled="isClosingTicket" @click="closeTicket"><UIcon :name="isClosingTicket ? 'i-lucide-loader-circle' : 'i-lucide-circle-x'" :class="{ 'is-spinning': isClosingTicket }" /> {{ isClosingTicket ? t('accountSupport.closing') : t('accountSupport.closeRequest') }}</button></div>
          </header>
          <div v-if="detailError" class="ticket-inline-error" role="alert"><UIcon name="i-lucide-circle-alert" /> {{ detailError }}</div>
          <div class="ticket-conversation">
            <button v-if="canLoadEarlierMessages" class="load-earlier-button" type="button" :disabled="isLoadingEarlierMessages" @click="loadEarlierMessages"><UIcon :name="isLoadingEarlierMessages ? 'i-lucide-loader-circle' : 'i-lucide-history'" :class="{ 'is-spinning': isLoadingEarlierMessages }" /> {{ isLoadingEarlierMessages ? t('accountSupport.loading') : t('accountSupport.loadEarlier') }}</button>
            <article v-for="message in ticketTimeline" :key="message.key" class="ticket-message" :class="message.sender_type === 'ADMIN' ? 'from-support' : 'from-customer'">
              <div class="ticket-message-meta"><span>{{ message.sender_type === 'ADMIN' ? t('accountSupport.supportSender') : message.id === 0 ? t('accountSupport.openedByYou') : t('accountSupport.you') }}</span><time :datetime="message.created_at || undefined">{{ formatDate(message.created_at, 'long') }}</time></div>
              <p v-if="message.content">{{ message.content }}</p>
              <ul v-if="message.attachments.length" class="ticket-attachments"><li v-for="attachment in message.attachments" :key="attachment.id"><a :href="attachment.download_url" target="_blank" rel="noopener noreferrer"><UIcon name="i-lucide-paperclip" /><span>{{ attachment.file_name }}</span><small>{{ formatBytes(attachment.size_bytes) }}</small><UIcon name="i-lucide-arrow-up-right" /></a></li></ul>
            </article>
          </div>
          <div v-if="selectedTicketIsClosed" class="ticket-closed-note"><UIcon name="i-lucide-circle-check-big" /><div><strong>{{ t('accountSupport.closedTitle') }}</strong><span>{{ t('accountSupport.closedCopy') }}</span></div></div>
          <form v-else class="ticket-composer" @submit.prevent="sendMessage">
            <label class="ticket-composer-label" for="ticket-reply">{{ t('accountSupport.replyLabel') }}</label>
            <textarea id="ticket-reply" v-model="messageDraft" rows="4" maxlength="5000" :placeholder="t('accountSupport.replyPlaceholder')" :disabled="isSendingMessage" />
            <div class="ticket-composer-bottom"><div><input ref="messageFileInput" class="visually-hidden" type="file" multiple @change="onMessageFilesChange"><button class="attach-button" type="button" :disabled="isSendingMessage" @click="messageFileInput?.click()"><UIcon name="i-lucide-paperclip" /> {{ t('accountSupport.attachFiles') }}</button><span>{{ messageDraft.trim().length }}/5000</span></div><button class="store-button" type="submit" :disabled="isSendingMessage || (!messageDraft.trim() && !messageFiles.length)"><UIcon :name="isSendingMessage ? 'i-lucide-loader-circle' : 'i-lucide-send'" :class="{ 'is-spinning': isSendingMessage }" /> {{ isSendingMessage ? t('accountSupport.sending') : t('accountSupport.sendReply') }}</button></div>
            <ul v-if="messageFiles.length" class="ticket-file-queue"><li v-for="(file, index) in messageFiles" :key="`${file.name}-${file.lastModified}-${index}`"><UIcon name="i-lucide-file" /><span>{{ file.name }}</span><small>{{ formatBytes(file.size) }}</small><button type="button" :disabled="isSendingMessage" :aria-label="t('accountSupport.removeFile', { name: file.name })" @click="removeMessageFile(index)"><UIcon name="i-lucide-x" /></button></li></ul>
            <p v-if="messageError" class="ticket-form-error" role="alert"><UIcon name="i-lucide-circle-alert" /> {{ messageError }}</p>
          </form>
        </template>
      </section>
      <nav v-if="tickets.length && totalPages > 1" class="tickets-pagination" :aria-label="t('accountSupport.pagination')"><button type="button" :disabled="page <= 1" @click="changePage(page - 1)"><UIcon name="i-lucide-arrow-left" /> {{ t('accountSupport.newer') }}</button><span>{{ t('accountSupport.page', { page: pageLabel }) }}</span><button type="button" :disabled="page >= totalPages" @click="changePage(page + 1)">{{ t('accountSupport.older') }} <UIcon name="i-lucide-arrow-right" /></button></nav>
    </template>
  </CustomerAccountShell>
</template>

<style scoped>
.tickets-loading { display: grid; gap: 13px; }
.tickets-loading-toolbar, .tickets-loading-card, .ticket-detail-loading-line, .ticket-detail-loading-message { position: relative; overflow: hidden; background: #eee9e5; }
.tickets-loading-toolbar { min-height: 144px; }
.tickets-loading-card { min-height: 125px; }
.tickets-loading-toolbar::after, .tickets-loading-card::after, .ticket-detail-loading-line::after, .ticket-detail-loading-message::after { position: absolute; inset: 0; content: ''; background: linear-gradient(100deg, transparent 15%, rgba(255,255,255,.68) 49%, transparent 82%); animation: shimmer 1.55s infinite; transform: translateX(-100%); }

.tickets-toolbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 22px; margin-bottom: 18px; padding: 26px 25px 24px; background: var(--store-linen); }
.tickets-toolbar h2, .ticket-create-heading h2, .tickets-empty h2, .ticket-detail-header h2 { margin: 5px 0 0; font-family: 'Playfair Display', Georgia, serif; font-size: clamp(26px, 3vw, 38px); font-weight: 500; letter-spacing: -.035em; line-height: 1.04; }
.tickets-toolbar p:not(.store-eyebrow) { max-width: 590px; margin: 11px 0 0; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.tickets-toolbar-actions { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.refresh-button, .outline-button, .text-button, .attach-button, .load-earlier-button, .ticket-open-button, .tickets-pagination button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; border: 0; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-transform: uppercase; transition: color .2s ease, background .2s ease, border-color .2s ease; }
.refresh-button { min-height: 42px; padding: 0 12px; color: var(--store-ink); background: transparent; }
.refresh-button:hover:not(:disabled), .text-button:hover:not(:disabled), .attach-button:hover:not(:disabled), .ticket-open-button:hover, .load-earlier-button:hover:not(:disabled), .tickets-pagination button:hover:not(:disabled) { color: var(--store-wine); }
.refresh-button .iconify, .store-button .iconify, .ticket-open-button .iconify { width: 14px; height: 14px; }
.ticket-create-button { min-height: 42px; }

.ticket-create-panel { margin-bottom: 18px; padding: 28px 25px 25px; border: 1px solid var(--store-line); background: #fffdfb; }
.ticket-create-heading { display: grid; grid-template-columns: minmax(0, 1fr) minmax(220px, .58fr); align-items: end; gap: 28px; padding-bottom: 23px; border-bottom: 1px solid var(--store-line); }
.ticket-create-heading > p { max-width: 330px; margin: 0; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.panel-kicker { margin: 0; color: var(--store-plum); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .11em; text-transform: uppercase; }
.ticket-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; padding-top: 23px; }
.ticket-field { display: flex; flex-direction: column; gap: 8px; color: var(--store-ink); font-size: 11px; font-weight: 600; }
.ticket-field-wide { grid-column: 1 / -1; }
.ticket-field > span { display: flex; justify-content: space-between; gap: 12px; }
.ticket-field em, .ticket-field small { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; font-style: normal; font-weight: 400; letter-spacing: .04em; line-height: 1.5; }
.ticket-field input, .ticket-field select, .ticket-field textarea, .ticket-composer textarea { width: 100%; box-sizing: border-box; border: 1px solid rgba(36,29,33,.2); border-radius: 0; outline: 0; color: var(--store-ink); background: rgba(255,255,255,.86); font: inherit; font-size: 12px; transition: border-color .2s ease, box-shadow .2s ease; }
.ticket-field input, .ticket-field select { min-height: 43px; padding: 0 11px; }
.ticket-field textarea, .ticket-composer textarea { min-height: 110px; padding: 11px; resize: vertical; line-height: 1.55; }
.ticket-field input:focus, .ticket-field select:focus, .ticket-field textarea:focus, .ticket-composer textarea:focus { border-color: var(--store-wine); box-shadow: 0 0 0 3px rgba(154,64,85,.1); }
.ticket-form-actions { display: flex; align-items: center; justify-content: flex-end; gap: 14px; padding-top: 3px; }
.text-button { min-height: 39px; padding: 0; color: var(--store-muted); background: transparent; }
.text-button-danger { color: #a33e4a; }
.ticket-form-error, .ticket-inline-error { display: flex; align-items: flex-start; gap: 8px; color: #9d3f4b; font-size: 11px; line-height: 1.5; }
.ticket-form-error { grid-column: 1 / -1; margin: -4px 0 0; }
.ticket-form-error .iconify, .ticket-inline-error .iconify { width: 15px; height: 15px; flex: 0 0 auto; }

.ticket-filters { display: flex; align-items: center; gap: 22px; overflow-x: auto; margin-bottom: 14px; border-bottom: 1px solid var(--store-line); }
.ticket-filters button { position: relative; flex: 0 0 auto; padding: 0 0 12px; border: 0; color: var(--store-muted); background: transparent; cursor: pointer; font-family: 'DM Mono', monospace; font-size: 9px; letter-spacing: .06em; text-transform: uppercase; }
.ticket-filters button::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; content: ''; background: transparent; }
.ticket-filters button:hover, .ticket-filters button.active { color: var(--store-ink); }
.ticket-filters button.active::after { background: var(--store-wine); }
</style>

<style scoped>
.ticket-list { display: grid; gap: 10px; }
.ticket-card { display: grid; grid-template-columns: 38px minmax(0, 1fr) minmax(130px, .36fr); gap: 16px; align-items: start; padding: 19px 20px; border: 1px solid var(--store-line); background: rgba(255,255,255,.58); transition: border-color .2s ease, background .2s ease; }
.ticket-card:hover, .ticket-card.is-selected { border-color: rgba(154,64,85,.44); background: #fffdfb; }
.ticket-card-index { color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 29px; line-height: .9; }
.ticket-card-topline { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ticket-service, .ticket-status { display: inline-flex; align-items: center; gap: 6px; font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .07em; text-transform: uppercase; }
.ticket-service { color: var(--store-muted); }
.ticket-service .iconify { width: 13px; height: 13px; color: var(--store-wine); }
.ticket-status { padding: 5px 7px; border: 1px solid currentColor; }
.tone-warm { color: #98663d; background: #fbf1e5; }
.tone-accent { color: #596c82; background: #edf2f7; }
.tone-success { color: #4e654d; background: #edf2e7; }
.tone-muted { color: var(--store-muted); background: #efebea; }
.ticket-card h3 { margin: 13px 0 7px; color: var(--store-ink); font-family: 'Playfair Display', Georgia, serif; font-size: 21px; font-weight: 500; letter-spacing: -.025em; line-height: 1.1; }
.ticket-card-meta, .ticket-card-reply { overflow: hidden; margin: 0; color: var(--store-muted); font-size: 11px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }
.ticket-card-reply span { margin-right: 7px; color: var(--store-plum); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .05em; text-transform: uppercase; }
.ticket-card-side { display: flex; align-items: flex-end; flex-direction: column; gap: 24px; }
.ticket-card-side time { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .04em; text-align: right; }
.ticket-open-button { padding: 0; color: var(--store-ink); background: transparent; }

.tickets-empty { min-height: 255px; display: flex; align-items: center; gap: 24px; padding: 28px; border: 1px dashed var(--store-line); background: rgba(241,232,231,.34); }
.empty-ticket-mark { color: var(--store-blush); font-family: 'Playfair Display', Georgia, serif; font-size: 70px; line-height: 1; }
.tickets-empty p:not(.panel-kicker) { max-width: 470px; margin: 10px 0 19px; color: var(--store-muted); font-size: 12px; line-height: 1.6; }
.outline-button { min-height: 40px; padding: 0 12px; border: 1px solid var(--store-ink); color: var(--store-ink); background: transparent; }
.outline-button:hover:not(:disabled) { color: #fff; background: var(--store-ink); }

.ticket-detail { margin-top: 16px; border: 1px solid var(--store-line); background: #fffdfb; }
.ticket-detail-loading { display: grid; gap: 17px; padding: 26px; }
.ticket-detail-loading-line { width: 65%; height: 30px; }
.ticket-detail-loading-line.wide { width: 75%; }
.ticket-detail-loading-message { width: 68%; height: 100px; }
.ticket-detail-loading-message.short { width: 52%; justify-self: end; }
.ticket-detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding: 26px 25px 23px; border-bottom: 1px solid var(--store-line); background: var(--store-linen); }
.ticket-detail-header h2 { font-size: clamp(25px, 3vw, 35px); }
.ticket-detail-header p:not(.panel-kicker) { margin: 10px 0 0; color: var(--store-muted); font-size: 11px; }
.ticket-detail-actions { display: flex; align-items: flex-end; flex-direction: column; gap: 13px; flex: 0 0 auto; }
.ticket-detail-actions .text-button .iconify { width: 14px; height: 14px; }
.ticket-inline-error { margin: 16px 25px 0; }
.ticket-detail-error { min-height: 180px; display: flex; align-items: center; justify-content: center; gap: 15px; padding: 25px; color: #9d3f4b; text-align: left; }
.ticket-detail-error > .iconify { width: 25px; height: 25px; }
.ticket-detail-error div { display: flex; flex-direction: column; gap: 4px; }
.ticket-detail-error strong { color: var(--store-ink); font-size: 13px; }
.ticket-detail-error span { max-width: 390px; font-size: 11px; line-height: 1.5; }
.ticket-conversation { display: grid; gap: 15px; padding: 24px 25px; }
.load-earlier-button { justify-self: center; min-height: 34px; padding: 0 10px; color: var(--store-muted); background: transparent; }
.load-earlier-button .iconify { width: 13px; height: 13px; }
.ticket-message { max-width: min(83%, 700px); padding: 15px 16px; border: 1px solid var(--store-line); }
.ticket-message.from-customer { justify-self: end; border-color: rgba(154,64,85,.25); background: #f9eeee; }
.ticket-message.from-support { justify-self: start; background: #fff; }
.ticket-message-meta { display: flex; justify-content: space-between; gap: 22px; margin-bottom: 9px; color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .06em; text-transform: uppercase; }
.ticket-message.from-support .ticket-message-meta span { color: var(--store-wine); }
.ticket-message p { margin: 0; color: var(--store-ink); font-size: 12px; line-height: 1.65; white-space: pre-wrap; }
</style>

<style scoped>
.ticket-attachments, .ticket-file-queue { display: grid; gap: 7px; margin: 12px 0 0; padding: 0; list-style: none; }
.ticket-attachments a, .ticket-file-queue li { display: grid; grid-template-columns: 15px minmax(0, 1fr) auto 15px; align-items: center; gap: 7px; min-height: 31px; padding: 0 8px; color: var(--store-ink); border: 1px solid rgba(36,29,33,.12); background: rgba(255,255,255,.55); font-size: 10px; text-decoration: none; }
.ticket-attachments a:hover { border-color: var(--store-wine); color: var(--store-wine); }
.ticket-attachments .iconify, .ticket-file-queue .iconify { width: 13px; height: 13px; }
.ticket-attachments span, .ticket-file-queue span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ticket-attachments small, .ticket-file-queue small { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; }
.ticket-closed-note { display: flex; align-items: flex-start; gap: 11px; margin: 0 25px 25px; padding: 15px; color: #4e654d; border: 1px solid #b8c9ad; background: #edf2e7; }
.ticket-closed-note > .iconify { width: 18px; height: 18px; flex: 0 0 auto; }
.ticket-closed-note div { display: flex; flex-direction: column; gap: 3px; }
.ticket-closed-note strong { font-size: 11px; }
.ticket-closed-note span { font-size: 11px; line-height: 1.5; }
.ticket-composer { padding: 21px 25px 25px; border-top: 1px solid var(--store-line); }
.ticket-composer-label { display: block; margin-bottom: 9px; color: var(--store-ink); font-size: 11px; font-weight: 600; }
.ticket-composer textarea { min-height: 105px; }
.ticket-composer-bottom { display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 10px; }
.ticket-composer-bottom > div { display: flex; align-items: center; gap: 12px; }
.ticket-composer-bottom > div > span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; }
.attach-button { min-height: 35px; padding: 0; color: var(--store-muted); background: transparent; }
.attach-button .iconify { width: 14px; height: 14px; }
.ticket-file-queue li { grid-template-columns: 15px minmax(0, 1fr) auto 22px; }
.ticket-file-queue button { display: inline-flex; align-items: center; justify-content: center; width: 22px; height: 22px; padding: 0; border: 0; color: var(--store-muted); background: transparent; cursor: pointer; }
.ticket-file-queue button:hover:not(:disabled) { color: #9d3f4b; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
.tickets-pagination { display: flex; align-items: center; justify-content: space-between; gap: 15px; margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--store-line); }
.tickets-pagination button { padding: 0; color: var(--store-ink); background: transparent; }
.tickets-pagination button .iconify { width: 14px; height: 14px; }
.tickets-pagination span { color: var(--store-muted); font-family: 'DM Mono', monospace; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }
.tickets-pagination button:disabled, .refresh-button:disabled, .store-button:disabled, .text-button:disabled, .outline-button:disabled, .attach-button:disabled, .load-earlier-button:disabled, .ticket-file-queue button:disabled { cursor: not-allowed; opacity: .45; }
.is-spinning { animation: spin 1s linear infinite; }
@keyframes shimmer { to { transform: translateX(100%); } }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 760px) {
  .tickets-toolbar, .ticket-detail-header { align-items: flex-start; flex-direction: column; }
  .tickets-toolbar { padding: 22px 18px 20px; }
  .tickets-toolbar-actions { width: 100%; justify-content: space-between; }
  .ticket-create-panel { padding: 22px 17px 18px; }
  .ticket-create-heading { grid-template-columns: 1fr; gap: 14px; padding-bottom: 18px; }
  .ticket-form { grid-template-columns: 1fr; gap: 15px; padding-top: 18px; }
  .ticket-field-wide { grid-column: auto; }
  .ticket-card { grid-template-columns: 30px minmax(0, 1fr); gap: 11px; padding: 16px 14px; }
  .ticket-card-side { grid-column: 2; align-items: center; flex-direction: row; justify-content: space-between; gap: 11px; }
  .ticket-card-side time { text-align: left; }
  .ticket-card h3 { font-size: 19px; }
  .tickets-empty { align-items: flex-start; flex-direction: column; padding: 24px 18px; }
  .empty-ticket-mark { font-size: 54px; }
  .ticket-detail-header { padding: 22px 17px 18px; }
  .ticket-detail-actions { width: 100%; align-items: center; flex-direction: row; justify-content: space-between; }
  .ticket-conversation, .ticket-composer { padding-right: 17px; padding-left: 17px; }
  .ticket-message { max-width: 91%; }
  .ticket-closed-note { margin-right: 17px; margin-left: 17px; }
}
@media (max-width: 460px) {
  .tickets-toolbar-actions { align-items: stretch; flex-direction: column-reverse; }
  .tickets-toolbar-actions > * { width: 100%; }
  .ticket-card-topline { align-items: flex-start; flex-direction: column; }
  .ticket-card-reply { white-space: normal; }
  .ticket-composer-bottom { align-items: flex-start; flex-direction: column; }
  .ticket-composer-bottom .store-button { width: 100%; }
  .ticket-detail-error { align-items: flex-start; flex-direction: column; }
}
@media (prefers-reduced-motion: reduce) {
  .tickets-loading-toolbar::after, .tickets-loading-card::after, .ticket-detail-loading-line::after, .ticket-detail-loading-message::after, .is-spinning { animation: none; }
  .ticket-card, .refresh-button, .outline-button, .text-button, .attach-button { transition: none; }
}
</style>
