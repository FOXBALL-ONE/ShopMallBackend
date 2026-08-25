<script setup lang="ts">
import { ref } from 'vue'

definePageMeta({ layout: false })

type Role = '管理员' | '制作人员' | '审核人员'
type Member = { id: number; name: string; email: string; role: Role; enabled: boolean }

const toast = ref('')
const members = ref<Member[]>([
  { id: 1, name: 'Lin Chen', email: 'admin@atelier.local', role: '管理员', enabled: true },
  { id: 2, name: 'Mia Zhou', email: 'studio@atelier.local', role: '制作人员', enabled: true },
  { id: 3, name: 'Evelyn Xu', email: 'review@atelier.local', role: '审核人员', enabled: true },
])

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2200)
}

function updateRole(member: Member, role: Role) {
  member.role = role
  showToast(`已更新 ${member.name} 的角色`)
}

function updateStatus(member: Member, enabled: boolean) {
  member.enabled = enabled
  showToast(`${member.name} 已${enabled ? '启用' : '停用'}`)
}
</script>

<template>
  <div class="team-layout">
    <StudioSidebar />
    <section class="team-main">
      <header class="team-topbar"><div><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div><span class="service-state"><i /> 生成服务由平台安全代理</span></header>
      <main class="team-content">
        <section class="team-heading"><p class="eyebrow">TEAM &amp; ACCESS</p><h1>团队成员</h1><span>管理员统一维护品牌内部角色与账号状态。</span></section>
        <section class="member-panel"><div class="member-header"><span>成员</span><span>角色</span><span>状态</span></div><article v-for="member in members" :key="member.id" class="member-row"><div class="member-identity"><strong>{{ member.name }}</strong><small>{{ member.email }}</small></div><label><span class="sr-only">{{ member.name }} 角色</span><select :value="member.role" @change="updateRole(member, ($event.target as HTMLSelectElement).value as Role)"><option>管理员</option><option>制作人员</option><option>审核人员</option></select></label><label><span class="sr-only">{{ member.name }} 状态</span><select :value="member.enabled ? '启用' : '停用'" @change="updateStatus(member, ($event.target as HTMLSelectElement).value === '启用')"><option>启用</option><option>停用</option></select></label></article></section>
      </main>
    </section>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button, select { font: inherit; }
.team-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.team-main { min-width: 0; }.team-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.team-topbar > div { display: flex; flex-direction: column; gap: 2px; }.team-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.team-topbar strong { font: 500 12px Georgia, serif; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }
.team-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.team-heading { margin-bottom: 32px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.team-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.team-heading > span { color: #817b73; font-size: 12px; }.member-panel { padding: 22px 24px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.member-header, .member-row { display: grid; grid-template-columns: minmax(0, 1fr) 180px 140px; align-items: center; gap: 18px; }.member-header { padding: 0 15px 15px; color: #9b805b; border-bottom: 1px solid #eee9e2; font-size: 8px; }.member-header span:nth-child(2), .member-header span:nth-child(3) { text-align: left; }.member-row { padding: 14px 15px; border-bottom: 1px solid #eee9e2; }.member-row:last-child { border-bottom: 0; }.member-identity { display: flex; flex-direction: column; gap: 4px; }.member-identity strong { font-size: 10px; }.member-identity small { color: #8f887f; font-size: 8px; }.member-row label select { width: 100%; padding: 9px 11px; color: #332f2a; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 9px; }.member-row label select:focus { border-color: #9d8766; box-shadow: 0 0 0 3px #9d87661a; }.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 800px) { .team-layout { display: block; }.team-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.team-content { padding: 30px 16px 55px; }.member-panel { padding: 15px 12px; overflow-x: auto; }.member-header, .member-row { min-width: 570px; grid-template-columns: minmax(210px, 1fr) 150px 120px; } }
</style>
