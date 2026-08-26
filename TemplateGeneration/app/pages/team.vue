<script setup lang="ts">
import {ref} from 'vue'

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
        <section class="member-panel" aria-live="polite">
          <div class="member-header"><span>用户</span><span>加入时间</span><span>状态</span></div>
          <p v-if="loading" class="panel-message">正在加载用户…</p>
          <div v-else-if="loadError" class="panel-message load-error"><span>{{ loadError }}</span><button type="button" @click="refreshMembers">重试加载</button></div>
          <p v-else-if="!members.length" class="panel-message">当前还没有已注册用户。</p>
          <article v-for="member in members" v-else :key="member.id" class="member-row">
            <div class="member-identity"><strong>{{ member.username }} <em v-if="member.isCurrent">当前账号</em></strong><small>用户 ID · {{ member.id }}</small></div>
            <span class="member-date">{{ formatDate(member.createdAt) }}</span>
            <span class="member-status">● 已启用</span>
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
.team-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.team-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 32px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.team-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.team-heading > div > span { color: #817b73; font-size: 12px; }.create-button { display: inline-flex; align-items: center; gap: 8px; flex: 0 0 auto; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.create-button:hover { background: #35322d; }.create-button span { font-size: 16px; line-height: 10px; }.create-panel { max-width: 620px; margin-bottom: 24px; padding: 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.create-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 18px; border-bottom: 1px solid #eee9e2; }.create-heading h2 { margin: 6px 0 0; font: 400 25px Georgia, serif; }.create-heading > span { max-width: 230px; color: #817b73; font-size: 10px; line-height: 1.5; }.create-form { display: grid; gap: 17px; margin-top: 3px; }.field { display: flex; flex-direction: column; gap: 7px; margin-top: 17px; color: #59554f; font-size: 10px; }.field input { width: 100%; padding: 11px 12px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 11px; }.field input:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.field input[aria-invalid="true"] { border-color: #b87968; }.field small { margin-top: -2px; color: #938b81; font-size: 9px; }.field .field-error { color: #985d51; }.submit-button { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; margin-top: 4px; padding: 12px; color: #fff; background: #1d1c19; border: 0; border-radius: 9px; font-size: 11px; }.submit-button:hover:not(:disabled) { background: #35322d; }.submit-button:disabled { cursor: wait; opacity: .7; }.spinner { width: 13px; height: 13px; border: 2px solid #ffffff66; border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; }.form-message { padding: 10px 12px; border-radius: 8px; font-size: 10px; line-height: 1.5; }.form-message.error { color: #795b45; background: #f1e8df; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@keyframes spin { to { transform: rotate(360deg); } } @media (max-width: 800px) { .team-layout { display: block; }.team-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.team-content { padding: 30px 16px 55px; }.team-heading { align-items: flex-start; flex-direction: column; }.create-button { align-self: stretch; justify-content: center; }.create-panel { padding: 20px 17px; }.create-heading { flex-direction: column; gap: 8px; }.create-heading > span { max-width: none; }.member-panel { padding: 15px 12px; overflow-x: auto; }.member-header, .member-row { min-width: 570px; grid-template-columns: minmax(210px, 1fr) 150px 120px; } }
</style>
