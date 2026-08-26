<script setup lang="ts">
import { ref } from 'vue'

definePageMeta({ layout: false })

const filter = ref('ALL')
const toast = ref('')
const filters = [
  { value: 'ALL', label: '全部' },
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2200)
}
</script>

<template>
  <div class="review-layout">
    <StudioSidebar />
    <section class="review-main">
      <StudioTopbar><span class="service-state"><i /> 生成服务由平台安全代理</span></StudioTopbar>
      <main class="review-content">
        <section class="review-heading"><div><p class="eyebrow">TEAM REVIEW</p><h1>审核中心</h1><span>集中判断、评分与留下可追溯批注。</span></div><button class="dark-button" type="button" @click="showToast('当前没有可导出的审核结果')">下载导出包</button></section>
        <div class="result-toolbar"><div class="filter-tabs"><button v-for="item in filters" :key="item.value" type="button" :class="{ active: filter === item.value }" @click="filter = item.value">{{ item.label }}</button></div><span>已选 0/2 · 选择两项进行并排对比</span></div>
        <section class="empty-state"><strong>当前没有结果</strong><span>真实生成完成后会自动进入这里。</span></section>
      </main>
    </section>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --line: #e7e1d8; --paper: #f7f5f0; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button { font: inherit; cursor: pointer; }
.review-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.review-main { min-width: 0; }.review-topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.review-topbar > div { display: flex; flex-direction: column; gap: 2px; }.review-topbar > div > span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.review-topbar strong { font: 500 12px Georgia, serif; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }
.review-content { max-width: 1500px; margin: 0 auto; padding: 42px 4% 65px; }.review-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 31px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.review-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.review-heading > div > span { color: #817b73; font-size: 12px; }.dark-button { display: flex; align-items: center; gap: 7px; min-height: 42px; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }
.result-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 20px; }.filter-tabs { display: flex; gap: 4px; padding: 4px; background: #ede9e2; border-radius: 9px; }.filter-tabs button { padding: 8px 13px; color: #746e66; background: transparent; border: 0; border-radius: 7px; font-size: 10px; }.filter-tabs button.active { color: #24211e; background: #fff; box-shadow: 0 2px 8px #322a2212; }.result-toolbar > span { color: #878078; font-size: 9px; }.empty-state { display: flex; flex-direction: column; align-items: center; gap: 9px; padding: 70px 20px; color: #898279; border: 1px dashed #d3ccc2; border-radius: 12px; text-align: center; }.empty-state strong { color: #4b4640; font: 400 17px Georgia, serif; }.empty-state span { font-size: 10px; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 800px) { .review-layout { display: block; }.review-topbar { height: 58px; padding: 0 18px; }.service-state { display: none; }.review-content { padding: 30px 16px 55px; }.review-heading { align-items: flex-start; flex-direction: column; }.review-heading .dark-button { width: 100%; justify-content: center; }.result-toolbar { align-items: stretch; flex-direction: column; }.filter-tabs { overflow-x: auto; } }
</style>
