<script setup lang="ts">
import {reactive, ref} from 'vue'

definePageMeta({layout: false})

const {user, refresh} = useAuthUser()
await refresh()

const form = reactive({currentPassword: '', newPassword: '', confirmPassword: ''})
const submitted = ref(false)
const saving = ref(false)
const message = ref('')
const errorMessage = ref('')

async function changePassword() {
  submitted.value = true
  message.value = ''
  errorMessage.value = ''
  if (!form.currentPassword || !form.newPassword || !form.confirmPassword) return
  if (form.newPassword.length < 8 || form.newPassword !== form.confirmPassword) return

  saving.value = true
  try {
    await $fetch('/api/auth/password', {method: 'PUT', body: {current_password: form.currentPassword, new_password: form.newPassword}})
    form.currentPassword = ''
    form.newPassword = ''
    form.confirmPassword = ''
    submitted.value = false
    message.value = '密码已更新，下次登录请使用新密码。'
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    errorMessage.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '密码更新失败，请稍后重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="account-layout">
    <StudioSidebar />
    <section class="account-main">
      <StudioTopbar><NuxtLink class="account-back" to="/dashboard">返回概览 <span>↗</span></NuxtLink></StudioTopbar>
      <main class="account-content">
        <section class="account-heading"><p class="eyebrow">ACCOUNT &amp; SECURITY</p><h1>账户安全</h1><span>管理 {{ user?.username }} 的登录凭据。</span></section>
        <section class="account-panel">
          <div class="panel-heading"><div><p class="eyebrow">PASSWORD</p><h2>修改密码</h2></div><span class="secure-mark">● 安全连接</span></div>
          <form @submit.prevent="changePassword">
            <label class="field"><span>当前密码</span><input v-model="form.currentPassword" type="password" autocomplete="current-password" :aria-invalid="submitted && !form.currentPassword"><small v-if="submitted && !form.currentPassword">请输入当前密码。</small></label>
            <label class="field"><span>新密码</span><input v-model="form.newPassword" type="password" autocomplete="new-password" minlength="8" :aria-invalid="submitted && form.newPassword.length < 8"><small>新密码至少 8 个字符。</small><small v-if="submitted && form.newPassword.length < 8">新密码长度必须至少为 8 个字符。</small></label>
            <label class="field"><span>确认新密码</span><input v-model="form.confirmPassword" type="password" autocomplete="new-password" :aria-invalid="submitted && form.confirmPassword !== form.newPassword"><small v-if="submitted && !form.confirmPassword">请再次输入新密码。</small><small v-else-if="submitted && form.confirmPassword !== form.newPassword">两次输入的新密码不一致。</small></label>
            <div v-if="errorMessage" class="form-message error" role="alert">{{ errorMessage }}</div>
            <div v-if="message" class="form-message success" role="status">{{ message }}</div>
            <button class="submit-button" type="submit" :disabled="saving"><span v-if="saving" class="spinner" aria-hidden="true" />{{ saving ? '正在保存…' : '保存新密码' }}</button>
          </form>
        </section>
      </main>
    </section>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; --gold: #a18455; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button, input { font: inherit; } button { cursor: pointer; }
.account-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.account-main { min-width: 0; }.account-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.account-topbar > div { display: flex; flex-direction: column; gap: 2px; }.account-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.account-topbar strong { font: 500 12px Georgia, serif; }.account-topbar a { color: #6e665d; font-size: 10px; text-decoration: none; }.account-topbar a:hover { color: var(--gold); }
.account-content { max-width: 880px; margin: 0 auto; padding: 42px 4% 65px; }.account-heading { margin-bottom: 32px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.account-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.account-heading > span { color: #817b73; font-size: 12px; }
.account-panel { max-width: 620px; padding: 26px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 20px; border-bottom: 1px solid #eee9e2; }.panel-heading h2 { margin: 6px 0 0; font: 400 25px Georgia, serif; }.secure-mark { color: #5d7963; font-size: 9px; white-space: nowrap; }.field { display: flex; flex-direction: column; gap: 8px; margin-top: 20px; color: #59554f; font-size: 10px; }.field input { width: 100%; padding: 12px 13px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; font-size: 11px; }.field input:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.field input[aria-invalid="true"] { border-color: #b87968; }.field small { margin-top: -3px; color: #938b81; font-size: 9px; }.field small + small { color: #985d51; }
.submit-button { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; margin-top: 25px; padding: 13px; color: #fff; background: #1d1c19; border: 0; border-radius: 9px; font-size: 11px; }.submit-button:hover:not(:disabled) { background: #35322d; }.submit-button:disabled { cursor: wait; opacity: .7; }.spinner { width: 13px; height: 13px; border: 2px solid #ffffff66; border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; }.form-message { margin-top: 18px; padding: 10px 12px; border-radius: 8px; font-size: 10px; line-height: 1.5; }.form-message.error { color: #795b45; background: #f1e8df; }.form-message.success { color: #4f6e56; background: #e8f0e8; }
@keyframes spin { to { transform: rotate(360deg); } } @media (max-width: 800px) { .account-layout { display: block; }.account-topbar { height: 58px; padding: 0 18px; }.account-content { padding: 30px 16px 55px; }.account-panel { padding: 21px 18px; } }
</style>
