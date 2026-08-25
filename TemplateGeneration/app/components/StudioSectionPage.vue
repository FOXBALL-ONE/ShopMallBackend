<script setup lang="ts">
const props = defineProps<{
  eyebrow: string
  title: string
  description: string
  projectId?: string
  items: { label: string; detail: string; status: string }[]
}>()

const statusClass = (status: string) => status === '已完成' ? 'complete' : status === '待处理' ? 'pending' : 'running'
</script>

<template>
  <div class="studio-section-layout">
    <StudioSidebar :project-id="props.projectId" />
    <section class="studio-section-main">
      <header class="studio-section-top"><div><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div><span class="studio-service"><i /> 生成服务由平台安全代理</span></header>
      <main class="studio-section-content">
        <section class="studio-section-heading"><div><p>{{ eyebrow }}</p><h1>{{ title }}</h1><span>{{ description }}</span></div><NuxtLink class="studio-section-action" :to="`/projects/${projectId || 'prj_noir'}/workflows`">新建工作流 <span>＋</span></NuxtLink></section>
        <section class="studio-section-panel"><div class="studio-section-panel-head"><span>项目动态</span><small>实时同步</small></div><article v-for="item in items" :key="item.label" class="studio-section-row"><i :class="statusClass(item.status)" /><div><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></div><em>{{ item.status }}</em></article></section>
      </main>
    </section>
  </div>
</template>

<style scoped>
.studio-section-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: #f7f5f0; color: #24221f; font-family: Arial, Helvetica, sans-serif; }.studio-section-main { min-width: 0; }.studio-section-top { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid #e7e1d8; backdrop-filter: blur(12px); }.studio-section-top > div { display: flex; flex-direction: column; gap: 2px; }.studio-section-top span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.studio-section-top strong { font: 500 12px Georgia, serif; }.studio-service { display: flex; align-items: center; gap: 6px; color: #68635c !important; letter-spacing: 0 !important; text-transform: none !important; font-size: 9px !important; }.studio-service i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }.studio-section-content { max-width: 1080px; margin: 0 auto; padding: 42px 4% 65px; }.studio-section-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 32px; }.studio-section-heading p { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; }.studio-section-heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; }.studio-section-heading > div > span { color: #817b73; font-size: 12px; }.studio-section-action { display: flex; align-items: center; gap: 7px; padding: 11px 14px; color: #fff; background: #1d1c19; border-radius: 8px; font-size: 10px; text-decoration: none; }.studio-section-panel { padding: 22px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.studio-section-panel-head { display: flex; justify-content: space-between; padding-bottom: 15px; border-bottom: 1px solid #eee9e2; }.studio-section-panel-head span { font: 400 19px Georgia, serif; }.studio-section-panel-head small { color: #8f887f; font-size: 9px; }.studio-section-row { display: flex; align-items: center; gap: 11px; padding: 16px 0; border-bottom: 1px solid #eee9e2; }.studio-section-row > i { flex: 0 0 auto; width: 8px; height: 8px; background: #9a9288; border-radius: 50%; }.studio-section-row > i.running { background: #7f9b82; }.studio-section-row > i.complete { background: #687e6b; }.studio-section-row > i.pending { background: #a18455; }.studio-section-row > div { display: flex; flex-direction: column; gap: 4px; }.studio-section-row strong { font-size: 11px; }.studio-section-row small { color: #8e867d; font-size: 9px; }.studio-section-row em { margin-left: auto; color: #8a7659; font-size: 9px; font-style: normal; }
@media (max-width: 800px) { .studio-section-layout { display: block; }.studio-section-top { height: 58px; padding: 0 18px; }.studio-service { display: none; }.studio-section-content { padding: 30px 16px 55px; }.studio-section-heading { align-items: flex-start; flex-direction: column; }.studio-section-action { width: 100%; justify-content: center; } }
</style>
