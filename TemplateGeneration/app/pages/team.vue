<script setup lang="ts">
import {computed, ref} from 'vue'

definePageMeta({layout: false})

type Member = {id: number; username: string; createdAt: string; updatedAt: string; isCurrent: boolean}
const USERNAME_MIN_LENGTH = 3
const USERNAME_MAX_LENGTH = 64
const PASSWORD_MIN_LENGTH = 8
const PASSWORD_MAX_LENGTH = 72

const toast = ref('')
const members = ref<Member[]>([])
const loading = ref(true)
const loadError = ref('')
const createOpen = ref(false)
const createSaving = ref(false)
const createSubmitted = ref(false)
const createError = ref('')
const createForm = ref({username: '', password: '', confirmPassword: ''})
const memberQuery = ref('')

const filteredMembers = computed(() => {
  const keyword = memberQuery.value.trim().toLowerCase()
  if (!keyword) return members.value
  return members.value.filter((member) => member.username.toLowerCase().includes(keyword) || String(member.id).includes(keyword))
})
const currentMember = computed(() => members.value.find((member) => member.isCurrent) ?? null)
const latestMember = computed(() => members.value.reduce<Member | null>((latest, member) => {
  if (!latest) return member
  return new Date(member.createdAt).getTime() > new Date(latest.createdAt).getTime() ? member : latest
}, null))

function showToast(message: string) {
  if (!import.meta.client) return
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2200)
}

function formatDate(value: string) {
  return value.replace('T', ' ').slice(0, 16)
}

function memberInitials(username: string) {
  return Array.from(username.trim()).slice(0, 2).join('').toUpperCase()
}

async function refreshMembers() {
  loading.value = true
  loadError.value = ''
  try {
    const requestFetch = import.meta.server ? useRequestFetch() : $fetch
    const response = await requestFetch<{users: Member[]}>('/api/users')
    members.value = response.users
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    loadError.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '团队成员加载失败，请重试。'
    showToast(loadError.value)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  createOpen.value = true
  createSubmitted.value = false
  createError.value = ''
}

function closeCreate() {
  if (createSaving.value) return
  createOpen.value = false
  createSubmitted.value = false
  createError.value = ''
  createForm.value = {username: '', password: '', confirmPassword: ''}
}

function validateCreateForm() {
  const username = createForm.value.username.trim()
  const password = createForm.value.password
  return username.length >= USERNAME_MIN_LENGTH
    && username.length <= USERNAME_MAX_LENGTH
    && password.length >= PASSWORD_MIN_LENGTH
    && password.length <= PASSWORD_MAX_LENGTH
    && password === createForm.value.confirmPassword
}

async function submitCreate() {
  createSubmitted.value = true
  createError.value = ''
  if (!validateCreateForm()) return

  createSaving.value = true
  try {
    await $fetch('/api/users', {
      method: 'POST',
      body: {
        username: createForm.value.username.trim(),
        password: createForm.value.password,
      },
    })
    createSaving.value = false
    closeCreate()
    await refreshMembers()
    showToast('用户已创建')
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    createError.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '创建用户失败，请稍后重试。'
  } finally {
    createSaving.value = false
  }
}

await refreshMembers()
</script>

<template>
  <div class="team-layout">
    <StudioSidebar />
    <section class="team-main">
      <StudioTopbar><span class="service-state"><i /> 生成服务由平台安全代理</span></StudioTopbar>
      <main class="team-content">
        <section class="team-heading"><div><p class="eyebrow">TEAM &amp; ACCESS</p><h1>团队成员</h1><span>已接入系统用户数据，共 {{ members.length }} 个账号。</span></div><button class="create-button" type="button" @click="createOpen ? closeCreate() : openCreate()">{{ createOpen ? '取消创建' : '创建用户' }} <span aria-hidden="true">{{ createOpen ? '×' : '＋' }}</span></button></section>
        <section v-if="createOpen" class="create-panel" aria-labelledby="create-user-title">
          <div class="create-heading"><div><p class="eyebrow">NEW MEMBER</p><h2 id="create-user-title">创建用户</h2></div><span>创建后即可使用用户名和密码登录工作台。</span></div>
          <form class="create-form" @submit.prevent="submitCreate">
            <label class="field"><span>用户名</span><input v-model="createForm.username" type="text" autocomplete="username" :maxlength="USERNAME_MAX_LENGTH" :aria-invalid="createSubmitted && (createForm.username.trim().length < USERNAME_MIN_LENGTH || createForm.username.trim().length > USERNAME_MAX_LENGTH || !createForm.username.trim())" /><small>长度为 {{ USERNAME_MIN_LENGTH }}-{{ USERNAME_MAX_LENGTH }} 个字符。</small><small v-if="createSubmitted && (!createForm.username.trim() || createForm.username.trim().length < USERNAME_MIN_LENGTH || createForm.username.trim().length > USERNAME_MAX_LENGTH)" class="field-error">用户名长度必须为 {{ USERNAME_MIN_LENGTH }}-{{ USERNAME_MAX_LENGTH }} 个字符。</small></label>
            <label class="field"><span>密码</span><input v-model="createForm.password" type="password" autocomplete="new-password" :maxlength="PASSWORD_MAX_LENGTH" :aria-invalid="createSubmitted && (createForm.password.length < PASSWORD_MIN_LENGTH || createForm.password.length > PASSWORD_MAX_LENGTH)" /><small>长度为 {{ PASSWORD_MIN_LENGTH }}-{{ PASSWORD_MAX_LENGTH }} 个字符。</small><small v-if="createSubmitted && (createForm.password.length < PASSWORD_MIN_LENGTH || createForm.password.length > PASSWORD_MAX_LENGTH)" class="field-error">密码长度必须为 {{ PASSWORD_MIN_LENGTH }}-{{ PASSWORD_MAX_LENGTH }} 个字符。</small></label>
            <label class="field"><span>确认密码</span><input v-model="createForm.confirmPassword" type="password" autocomplete="new-password" :maxlength="PASSWORD_MAX_LENGTH" :aria-invalid="createSubmitted && createForm.confirmPassword !== createForm.password" /><small v-if="createSubmitted && createForm.confirmPassword !== createForm.password" class="field-error">两次输入的密码必须完全一致。</small></label>
            <div v-if="createError" class="form-message error" role="alert">{{ createError }}</div>
            <button class="submit-button" type="submit" :disabled="createSaving"><span v-if="createSaving" class="spinner" aria-hidden="true" />{{ createSaving ? '正在创建…' : '创建用户' }}</button>
          </form>
        </section>
        <section class="member-overview" aria-label="成员统计">
          <article><span>账号总数</span><strong>{{ members.length }}</strong><small>已接入工作区</small></article>
          <article><span>当前账号</span><strong class="overview-name">{{ currentMember?.username || '-' }}</strong><small>正在使用的登录账号</small></article>
          <article><span>最近加入</span><strong class="overview-name">{{ latestMember?.username || '-' }}</strong><small>{{ latestMember ? formatDate(latestMember.createdAt) : '暂无记录' }}</small></article>
        </section>
        <section class="member-panel" aria-live="polite">
          <div class="member-panel-head"><div><p class="eyebrow">DIRECTORY</p><h2>成员目录</h2></div><div class="member-tools"><span class="member-count">{{ memberQuery ? `匹配 ${filteredMembers.length} 位` : `共 ${members.length} 位` }}</span><label class="search-field"><span class="sr-only">搜索成员</span><input v-model="memberQuery" type="search" placeholder="搜索用户名或 ID" aria-label="搜索用户名或 ID" /><button v-if="memberQuery" type="button" aria-label="清除搜索" @click="memberQuery = ''">×</button></label></div></div>
          <div class="member-header"><span>成员</span><span>加入时间</span><span>账号</span></div>
          <p v-if="loading" class="panel-message">正在加载用户…</p>
          <div v-else-if="loadError" class="panel-message load-error"><span>{{ loadError }}</span><button type="button" @click="refreshMembers">重试加载</button></div>
          <p v-else-if="!members.length" class="panel-message">当前还没有已注册用户。</p>
          <div v-else-if="!filteredMembers.length" class="panel-message"><span>没有匹配的成员。</span><button type="button" @click="memberQuery = ''">清除搜索</button></div>
          <article v-for="member in filteredMembers" v-else :key="member.id" class="member-row">
            <div class="member-identity"><span class="member-avatar" aria-hidden="true">{{ memberInitials(member.username) }}</span><div><strong>{{ member.username }} <em v-if="member.isCurrent">当前账号</em></strong><small>用户 ID · {{ member.id }}</small></div></div>
            <div class="member-date"><span>加入于</span><strong>{{ formatDate(member.createdAt) }}</strong></div>
            <span class="member-status" :class="{ current: member.isCurrent }"><i />{{ member.isCurrent ? '当前账号' : '工作区成员' }}</span>
          </article>
        </section>
      </main>
    </section>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button { font: inherit; cursor: pointer; }
.team-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.team-main { min-width: 0; }.team-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.team-topbar > div { display: flex; flex-direction: column; gap: 2px; }.team-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.team-topbar strong { font: 500 12px Georgia, serif; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }
.team-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.team-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 29px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.team-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.team-heading > div > span { color: #817b73; font-size: 12px; }.create-button { display: inline-flex; align-items: center; gap: 8px; flex: 0 0 auto; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.create-button:hover { background: #35322d; }.create-button span { font-size: 16px; line-height: 10px; }.member-overview { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 24px; }.member-overview article { min-width: 0; padding: 17px 18px; background: #eee9e1; border: 1px solid #e5dfd6; border-radius: 10px; }.member-overview article > span { display: block; color: #81786d; font-size: 9px; letter-spacing: .08em; text-transform: uppercase; }.member-overview strong { display: block; margin-top: 10px; color: #2e2b26; font: 400 27px Georgia, serif; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.member-overview .overview-name { font-size: 18px; }.member-overview small { display: block; margin-top: 6px; color: #8e867d; font-size: 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.create-panel { max-width: 620px; margin-bottom: 24px; padding: 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.create-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 18px; border-bottom: 1px solid #eee9e2; }.create-heading h2 { margin: 6px 0 0; font: 400 25px Georgia, serif; }.create-heading > span { max-width: 230px; color: #817b73; font-size: 10px; line-height: 1.5; }.create-form { display: grid; gap: 17px; margin-top: 3px; }.field { display: flex; flex-direction: column; gap: 7px; margin-top: 17px; color: #59554f; font-size: 10px; }.field input { width: 100%; padding: 11px 12px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 11px; }.field input:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.field input[aria-invalid="true"] { border-color: #b87968; }.field small { margin-top: -2px; color: #938b81; font-size: 9px; }.field .field-error { color: #985d51; }.submit-button { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; margin-top: 4px; padding: 12px; color: #fff; background: #1d1c19; border: 0; border-radius: 9px; font-size: 11px; }.submit-button:hover:not(:disabled) { background: #35322d; }.submit-button:disabled { cursor: wait; opacity: .7; }.spinner { width: 13px; height: 13px; border: 2px solid #ffffff66; border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; }.form-message { padding: 10px 12px; border-radius: 8px; font-size: 10px; line-height: 1.5; }.form-message.error { color: #795b45; background: #f1e8df; }
.member-panel { padding: 22px 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.member-panel-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; padding-bottom: 19px; border-bottom: 1px solid #eee9e2; }.member-panel-head h2 { margin: 6px 0 0; font: 400 25px Georgia, serif; }.member-tools { display: flex; align-items: center; gap: 13px; }.member-count { color: #918980; font-size: 9px; white-space: nowrap; }.search-field { position: relative; display: block; width: min(235px, 30vw); }.search-field input { width: 100%; padding: 9px 30px 9px 12px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 7px; outline: 0; font-size: 10px; }.search-field input:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.search-field button { position: absolute; top: 50%; right: 8px; width: 18px; height: 18px; padding: 0; color: #8e867d; background: transparent; border: 0; transform: translateY(-50%); font-size: 16px; line-height: 16px; }.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }.member-header, .member-row { display: grid; grid-template-columns: minmax(0, 1fr) 220px 140px; align-items: center; gap: 18px; }.member-header { padding: 16px 15px 10px; color: #9b805b; font-size: 8px; letter-spacing: .05em; text-transform: uppercase; }.member-row { min-height: 76px; padding: 13px 15px; border-bottom: 1px solid #eee9e2; }.member-row:last-child { border-bottom: 0; }.member-identity { display: flex; align-items: center; gap: 11px; min-width: 0; }.member-avatar { display: grid; place-items: center; width: 34px; height: 34px; flex: 0 0 auto; color: #6d5c47; background: #f2ece2; border: 1px solid #e6dac8; border-radius: 50%; font-size: 10px; font-weight: 700; }.member-identity > div { min-width: 0; }.member-identity strong { display: block; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.member-identity strong em { margin-left: 5px; padding: 3px 6px; color: #6b604d; background: #f2ece2; border-radius: 10px; font-size: 7px; font-style: normal; font-weight: 400; }.member-identity small { display: block; margin-top: 5px; color: #8f887f; font-size: 8px; }.member-date { display: flex; flex-direction: column; gap: 4px; }.member-date span { color: #a0988e; font-size: 8px; }.member-date strong { color: #625d55; font-size: 9px; font-weight: 400; }.member-status { display: inline-flex; align-items: center; gap: 7px; color: #81786e; font-size: 9px; white-space: nowrap; }.member-status i { width: 7px; height: 7px; background: #bcb5ac; border-radius: 50%; }.member-status.current { color: #5d7963; }.member-status.current i { background: #7f9b82; box-shadow: 0 0 0 3px #e6ede6; }.panel-message { display: flex; align-items: center; justify-content: space-between; gap: 15px; min-height: 90px; margin: 0; padding: 18px 15px; color: #938b81; font-size: 10px; }.panel-message.load-error { color: #875d4c; background: #f8ece7; border-radius: 8px; }.panel-message button { padding: 7px 10px; color: #6e665d; background: #fff; border: 1px solid #ddd7ce; border-radius: 7px; font-size: 9px; }.panel-message button:hover { border-color: #a18455; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@keyframes spin { to { transform: rotate(360deg); } } @media (max-width: 800px) { .team-layout { display: block; }.team-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.team-content { padding: 30px 16px 55px; }.team-heading { align-items: flex-start; flex-direction: column; }.create-button { align-self: stretch; justify-content: center; }.member-overview { grid-template-columns: 1fr; gap: 8px; }.member-overview article { padding: 14px 16px; }.member-overview strong { margin-top: 7px; }.create-panel { padding: 20px 17px; }.create-heading { flex-direction: column; gap: 8px; }.create-heading > span { max-width: none; }.member-panel { padding: 17px 14px; }.member-panel-head { align-items: flex-start; flex-direction: column; gap: 13px; }.member-tools { justify-content: space-between; width: 100%; }.search-field { width: min(240px, 65vw); }.member-header { display: none; }.member-row { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 12px; min-height: 0; padding: 16px 4px; }.member-identity { flex: 1 1 220px; }.member-date { flex: 1 1 150px; }.member-status { flex: 0 0 auto; margin-left: 45px; } }
</style>
