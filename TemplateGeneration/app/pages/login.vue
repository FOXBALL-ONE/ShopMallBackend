<script setup lang="ts">
import { reactive, ref } from 'vue'

definePageMeta({ layout: false })

const loading = ref(false)
const showPassword = ref(false)
const errorMessage = ref('')
const submitted = ref(false)
const route = useRoute()

const form = reactive({
  username: '',
  password: '',
})

async function handleSubmit() {
  submitted.value = true
  errorMessage.value = ''
  if (!form.username || !form.password) return

  loading.value = true
  try {
    await $fetch('/api/auth/login', {
      method: 'POST',
      body: {username: form.username, password: form.password},
    })
    const redirect = typeof route.query.redirect === 'string' && route.query.redirect.startsWith('/')
      ? route.query.redirect
      : '/dashboard'
    await navigateTo(redirect)
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    errorMessage.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '登录失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-visual" aria-label="ATELIER 品牌介绍">
      <div class="visual-noise" aria-hidden="true" />
      <div class="login-brand">
        <span class="brand-mark">A</span>
        <div>
          <strong>ATELIER</strong>
          <small>AI FASHION STUDIO</small>
        </div>
      </div>

      <div class="login-copy">
        <p class="eyebrow">AI FASHION WORKSTATION</p>
        <h1>从灵感到成片，<br>在同一个工作空间。</h1>
        <p class="copy-detail">品牌素材、生成任务与团队审核有序流转。</p>
      </div>

      <div class="orbit" aria-hidden="true">
        <i /><i /><i />
        <span class="orbit-label">COLLECTION / 26</span>
      </div>

      <div class="visual-footer">
        <span>PRIVATE WORKSPACE</span>
        <span>EST. 2026</span>
      </div>
    </section>

    <section class="login-panel">
      <form class="login-form" @submit.prevent="handleSubmit">
        <p class="eyebrow panel-eyebrow">INTERNAL ACCESS</p>
        <h2>登录工作站</h2>
        <p class="form-lead">使用品牌内部账号继续。</p>

        <div v-if="errorMessage" class="form-message" role="status">{{ errorMessage }}</div>

        <label class="field">
          <span>用户名</span>
          <input
            v-model="form.username"
            type="text"
            autocomplete="username"
            placeholder="输入用户名"
            minlength="3"
            maxlength="64"
            :aria-invalid="submitted && !form.username"
          >
          <small>用户名长度为 3-64 个字符。</small>
          <small v-if="submitted && !form.username">请输入用户名。</small>
        </label>

        <label class="field">
          <span>密码</span>
          <div class="password-wrap">
            <input
              v-model="form.password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="输入密码"
              :aria-invalid="submitted && !form.password"
            >
            <button
              type="button"
              class="password-toggle"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              {{ showPassword ? '隐藏' : '显示' }}
            </button>
          </div>
          <small v-if="submitted && !form.password">请输入密码。</small>
        </label>

        <button class="submit-button" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner" aria-hidden="true" />
          {{ loading ? '正在进入…' : '进入工作站' }}
        </button>

        <p class="security-note"><span class="status-dot" />账号由部署环境初始化，仅限受邀团队成员访问</p>
      </form>
    </section>
  </main>
</template>

<style>
:root {
  --ink: #24221f;
  --muted: #7d776f;
  --line: #ded8cf;
  --paper: #f6f2eb;
  --gold: #a58c68;
}

* { box-sizing: border-box; }
html, body, #__nuxt { min-height: 100%; margin: 0; }
body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; }
button, input { font: inherit; }
button { cursor: pointer; }

.login-page { min-height: 100vh; display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(420px, .85fr); background: var(--paper); }
.login-visual { position: relative; display: flex; flex-direction: column; justify-content: space-between; overflow: hidden; padding: 42px 6vw; color: #fff; background: #24221f; isolation: isolate; }
.visual-noise { position: absolute; inset: 0; z-index: -1; opacity: .12; pointer-events: none; background-image: radial-gradient(#fff 0.5px, transparent .5px); background-size: 7px 7px; mask-image: linear-gradient(135deg, transparent 10%, #000 90%); }
.login-brand { display: flex; align-items: center; gap: 12px; letter-spacing: .18em; }
.brand-mark { display: grid; place-items: center; width: 36px; height: 36px; border: 1px solid #ded6ca; border-radius: 50%; font: 20px Georgia, serif; }
.login-brand div { display: flex; flex-direction: column; gap: 4px; }
.login-brand strong { font-size: 14px; font-weight: 600; }
.login-brand small { color: #aaa198; font-size: 8px; letter-spacing: .13em; }
.login-copy { z-index: 1; position: relative; margin: auto 0; padding: 70px 0; }
.eyebrow { margin: 0; color: var(--gold); font-size: 10px; font-weight: 700; letter-spacing: .22em; text-transform: uppercase; }
.login-copy h1 { margin: 18px 0; font: 400 clamp(38px, 5vw, 68px)/1.12 Georgia, serif; letter-spacing: -.045em; }
.copy-detail { margin: 0; color: #c9c2b8; font-size: 13px; }
.orbit { position: absolute; right: -160px; bottom: -130px; width: 500px; height: 500px; border: 1px solid #5b554d; border-radius: 50%; }
.orbit i { position: absolute; inset: 65px; border: 1px solid #5b554d; border-radius: 50%; }
.orbit i:nth-child(2) { inset: 130px; }
.orbit i:nth-child(3) { inset: 195px; border: 0; background: var(--gold); box-shadow: 0 0 0 16px #a58c681a; }
.orbit-label { position: absolute; top: 50%; left: 2%; color: #91877a; font-size: 8px; letter-spacing: .16em; transform: rotate(-18deg); }
.visual-footer { display: flex; justify-content: space-between; color: #837a70; font-size: 8px; letter-spacing: .16em; }

.login-panel { display: grid; place-items: center; padding: 40px; }
.login-form { width: min(390px, 100%); }
.panel-eyebrow { color: #8d8377; }
.login-form h2 { margin: 9px 0; font: 400 38px Georgia, serif; letter-spacing: -.025em; }
.form-lead { margin: 0 0 32px; color: var(--muted); font-size: 13px; }
.field { display: flex; flex-direction: column; gap: 8px; margin-bottom: 18px; color: #59554f; font-size: 11px; }
.field input { width: 100%; padding: 13px; color: #26231f; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: none; transition: border-color .2s, box-shadow .2s; }
.field input::placeholder { color: #b4ada4; }
.field input:focus { border-color: #9d8766; box-shadow: 0 0 0 3px #9d87661a; }
.field input[aria-invalid="true"] { border-color: #b87968; }
.field small { margin-top: -3px; color: #985d51; font-size: 10px; }
.password-wrap { position: relative; }
.password-wrap input { padding-right: 58px; }
.password-toggle { position: absolute; top: 50%; right: 10px; padding: 3px; color: #8b8379; background: transparent; border: 0; font-size: 10px; transform: translateY(-50%); }
.password-toggle:hover { color: var(--ink); }
.submit-button { display: flex; align-items: center; justify-content: center; gap: 8px; width: 100%; padding: 13px; color: #fff; background: #1d1c19; border: 0; border-radius: 9px; font-size: 12px; transition: background .2s, transform .2s; }
.submit-button:hover:not(:disabled) { background: #35322d; transform: translateY(-1px); }
.submit-button:disabled { cursor: wait; opacity: .7; }
.spinner { width: 13px; height: 13px; border: 2px solid #ffffff66; border-top-color: #fff; border-radius: 50%; animation: spin .7s linear infinite; }
.form-message { margin: 0 0 17px; padding: 10px 12px; color: #795b45; background: #f1e8df; border-radius: 8px; font-size: 11px; line-height: 1.5; }
.security-note { display: flex; align-items: center; gap: 7px; margin: 28px 0 0; color: #a39b91; font-size: 10px; }
.status-dot { width: 6px; height: 6px; background: #7f9c84; border-radius: 50%; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 800px) {
  .login-page { grid-template-columns: 1fr; }
  .login-visual { min-height: 320px; padding: 30px 8vw; }
  .login-copy { padding: 44px 0 55px; }
  .login-copy h1 { font-size: clamp(36px, 10vw, 52px); }
  .orbit { width: 330px; height: 330px; right: -120px; bottom: -145px; }
  .orbit i { inset: 45px; }
  .orbit i:nth-child(2) { inset: 90px; }
  .orbit i:nth-child(3) { inset: 135px; }
  .login-panel { padding: 44px 24px 54px; }
}
@media (max-width: 480px) {
  .login-visual { min-height: 285px; }
  .login-copy { padding: 32px 0 42px; }
  .login-copy h1 { margin: 14px 0; font-size: 38px; }
  .copy-detail { font-size: 12px; }
  .visual-footer { font-size: 7px; }
  .login-panel { padding: 36px 20px 48px; }
  .login-form h2 { font-size: 34px; }
}
</style>
