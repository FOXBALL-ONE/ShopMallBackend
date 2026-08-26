<script setup lang="ts">
import {ref} from 'vue'

definePageMeta({layout: false})

type Member = {id: number; username: string; createdAt: string; updatedAt: string; isCurrent: boolean}

const toast = ref('')
const members = ref<Member[]>([])
const loading = ref(true)
const loadError = ref('')

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

await refreshMembers()
</script>

<template>
  <div class="team-layout">
    <StudioSidebar />
    <section class="team-main">
      <header class="team-topbar"><div><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div><span class="service-state"><i /> 生成服务由平台安全代理</span></header>
      <main class="team-content">
        <section class="team-heading"><p class="eyebrow">TEAM &amp; ACCESS</p><h1>团队成员</h1><span>已接入系统用户数据，共 {{ members.length }} 个账号；角色与邀请能力将在后续版本开放。</span></section>
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
.team-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.team-heading { margin-bottom: 32px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.team-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.team-heading > span { color: #817b73; font-size: 12px; }.member-panel { padding: 22px 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.member-header, .member-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px 140px; align-items: center; gap: 18px; }.member-header { padding: 0 15px 15px; color: #9b805b; border-bottom: 1px solid #eee9e2; font-size: 8px; }.member-row { padding: 14px 15px; border-bottom: 1px solid #eee9e2; }.member-row:last-child { border-bottom: 0; }.member-identity { display: flex; flex-direction: column; gap: 4px; }.member-identity strong { font-size: 10px; }.member-identity strong em { margin-left: 5px; padding: 3px 5px; color: #6b604d; background: #f2ece2; border-radius: 10px; font-size: 7px; font-style: normal; font-weight: 400; }.member-identity small { color: #8f887f; font-size: 8px; }.member-date { color: #625d55; font-size: 9px; }.member-status { color: #5d7963; font-size: 9px; }.panel-message { display: flex; align-items: center; justify-content: space-between; gap: 15px; min-height: 90px; margin: 0; padding: 18px 15px; color: #938b81; font-size: 10px; }.panel-message.load-error { color: #875d4c; background: #f8ece7; border-radius: 8px; }.panel-message button { padding: 7px 10px; color: #6e665d; background: #fff; border: 1px solid #ddd7ce; border-radius: 7px; font-size: 9px; }.panel-message button:hover { border-color: #a18455; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 800px) { .team-layout { display: block; }.team-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.team-content { padding: 30px 16px 55px; }.member-panel { padding: 15px 12px; overflow-x: auto; }.member-header, .member-row { min-width: 570px; grid-template-columns: minmax(210px, 1fr) 150px 120px; } }
</style>
