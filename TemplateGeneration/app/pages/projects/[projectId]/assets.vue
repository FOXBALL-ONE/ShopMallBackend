<script setup lang="ts">
import {computed, ref} from 'vue'

definePageMeta({layout: false})

type AssetType = 'ALL' | 'GARMENT' | 'MODEL' | 'REFERENCE'
type Asset = { id: number; type: Exclude<AssetType, 'ALL'>; name: string; code: string; description: string; tags: string[]; authorizationStatus: string | null; file: {id: number; originalName: string; contentType: string; sizeBytes: number; downloadUrl: string}; createdAt: string; updatedAt: string }

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const typeFilter = ref<AssetType>('ALL')
const query = ref('')
const uploadOpen = ref(false)
const toast = ref('')
const loading = ref(true)
const loadError = ref('')
const uploading = ref(false)
const uploadType = ref<'服装' | '模特' | '姿势参考' | '场景参考'>('服装')
const uploadFile = ref<File | null>(null)
const uploadName = ref('')
const uploadCode = ref('')
const uploadDescription = ref('')
const uploadTags = ref('')
const uploadAuthorized = ref(false)

const filters: { value: AssetType; label: string }[] = [
  { value: 'ALL', label: '全部素材' },
  { value: 'GARMENT', label: '服装' },
  { value: 'MODEL', label: '授权模特' },
  { value: 'REFERENCE', label: '视觉参考' },
]

const assets = ref<Asset[]>([])

const visibleAssets = computed(() => {
  const normalized = query.value.trim().toLowerCase()
  return assets.value.filter((asset) => {
    const matchesType = typeFilter.value === 'ALL' || asset.type === typeFilter.value
    const matchesQuery = !normalized || `${asset.name} ${asset.code} ${asset.description}`.toLowerCase().includes(normalized)
    return matchesType && matchesQuery
  })
})

function changeTypeFilter(value: AssetType) {
  typeFilter.value = value
  void loadAssets()
}

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => { if (toast.value === message) toast.value = '' }, 2200)
}

function requestError(error: unknown, fallback: string) {
  const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  return requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? fallback
}

async function loadAssets() {
  loading.value = true
  loadError.value = ''
  try {
    const response = await $fetch<{assets: Asset[]}>(`/api/projects/${encodeURIComponent(projectId.value)}/assets`, {query: {type: typeFilter.value, q: query.value || undefined}})
    assets.value = response.assets
  } catch (error: unknown) {
    loadError.value = requestError(error, '素材加载失败，请重试。')
  } finally {
    loading.value = false
  }
}

function chooseFile(event: Event) {
  uploadFile.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (uploadFile.value && !uploadName.value) uploadName.value = uploadFile.value.name.replace(/\.[^.]+$/, '')
}

function resetUpload() {
  uploadOpen.value = false
  uploadFile.value = null
  uploadName.value = ''
  uploadCode.value = ''
  uploadDescription.value = ''
  uploadTags.value = ''
  uploadAuthorized.value = false
}

async function saveAsset() {
  if (!uploadFile.value) return showToast('请选择要上传的文件。')
  if (!uploadName.value.trim()) return showToast('请输入素材名称。')
  if (!uploadCode.value.trim()) return showToast('请输入素材款号。')
  uploading.value = true
  const body = new FormData()
  body.append('file', uploadFile.value)
  body.append('type', uploadType.value === '服装' ? 'GARMENT' : uploadType.value === '模特' ? 'MODEL' : 'REFERENCE')
  body.append('name', uploadName.value)
  body.append('code', uploadCode.value)
  body.append('description', uploadDescription.value)
  body.append('tags', uploadTags.value)
  body.append('authorized', String(uploadAuthorized.value))
  try {
    const response = await $fetch<{asset: Asset}>(`/api/projects/${encodeURIComponent(projectId.value)}/assets`, {method: 'POST', body})
    assets.value.unshift(response.asset)
    resetUpload()
    showToast('素材已保存到 SQLite 素材库。')
  } catch (error: unknown) {
    showToast(requestError(error, '素材上传失败，请重试。'))
  } finally {
    uploading.value = false
  }
}

async function removeAsset(id: number) {
  if (!window.confirm('删除素材后会同时删除存储文件，确定继续吗？')) return
  try {
    await $fetch(`/api/projects/${encodeURIComponent(projectId.value)}/assets/${id}`, {method: 'DELETE'})
    assets.value = assets.value.filter((asset) => asset.id !== id)
    showToast('素材及其文件已删除。')
  } catch (error: unknown) {
    showToast(requestError(error, '素材删除失败，请重试。'))
  }
}

function assetLabel(type: Asset['type']) {
  return filters.find((filter) => filter.value === type)?.label || '项目素材'
}

function assetSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

await loadAssets()
</script>

<template>
  <div class="workspace-layout">
    <StudioSidebar :project-id="projectId" />

    <section class="main-area">
      <header class="topbar"><div class="project-switcher"><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div><div class="top-actions"><span class="service-state"><i /> 生成服务由平台安全代理</span><button class="icon-button" type="button" aria-label="通知" @click="showToast('暂无新的通知')">⌁</button><button class="new-button" type="button" @click="uploadOpen = true"><span>＋</span> 上传素材</button></div></header>
      <main class="content">
        <section class="heading"><div><p class="eyebrow">ASSET LIBRARY</p><h1>素材库</h1><span>服装、授权模特与视觉参考统一沉淀在项目内。</span></div><button class="dark-button" type="button" @click="uploadOpen = true">＋ 上传素材</button></section>
        <section class="toolbar"><div class="filter-tabs"><button v-for="filter in filters" :key="filter.value" type="button" :class="{ active: typeFilter === filter.value }" @click="changeTypeFilter(filter.value)">{{ filter.label }}</button></div><label class="search-field"><span>⌕</span><input v-model="query" type="search" placeholder="搜索名称、款号或标签" @change="loadAssets"></label></section>
        <section v-if="loading" class="empty-state"><strong>正在加载素材</strong><span>正在从 SQLite 素材库读取当前项目内容。</span></section>
        <section v-else-if="loadError" class="empty-state"><strong>素材加载失败</strong><span>{{ loadError }}</span><button class="dark-button" type="button" @click="loadAssets">重试加载</button></section>
        <section v-else-if="visibleAssets.length" class="asset-grid"><article v-for="asset in visibleAssets" :key="asset.id" class="asset-card"><a class="asset-preview asset-image" :href="asset.file.downloadUrl" target="_blank" rel="noopener"><img v-if="asset.file.contentType.startsWith('image/')" :src="asset.file.downloadUrl" :alt="asset.name"><div v-else class="asset-file-icon">{{ asset.file.contentType === 'application/pdf' ? 'PDF' : 'FILE' }}</div><span class="asset-type">{{ assetLabel(asset.type) }}</span><b>{{ asset.code }}</b></a><div class="asset-details"><small>{{ assetLabel(asset.type) }} · {{ assetSize(asset.file.sizeBytes) }}</small><h2>{{ asset.name }}</h2><p>{{ asset.description || asset.file.originalName }}</p><div><span v-if="asset.authorizationStatus" class="authorization" :class="{ pending: asset.authorizationStatus === '授权待确认' }">{{ asset.authorizationStatus === '已确认授权' ? '✓ ' : '! ' }}{{ asset.authorizationStatus }}</span><button type="button" @click="removeAsset(asset.id)">删除</button></div></div></article></section>
        <section v-else class="empty-state"><strong>还没有符合条件的素材</strong><span>尝试调整筛选条件，或上传服装、模特和参考图。</span><button class="dark-button" type="button" @click="uploadOpen = true">上传第一份素材</button></section>
      </main>
    </section>

    <div v-if="uploadOpen" class="modal-backdrop" @click.self="resetUpload"><form class="modal-card" @submit.prevent="saveAsset"><div class="modal-head"><div><p class="eyebrow">NEW ASSETS</p><h2>上传项目素材</h2></div><button type="button" class="close-button" aria-label="关闭" @click="resetUpload">×</button></div><label>素材类型<select v-model="uploadType"><option>服装</option><option>模特</option><option>姿势参考</option><option>场景参考</option></select></label><label class="drop-zone"><span class="upload-symbol">↑</span><strong>{{ uploadFile?.name || '选择素材文件' }}</strong><input type="file" accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm,application/pdf" @change="chooseFile"><small>图片、视频或 PDF · 单个文件不超过 25MB</small></label><div class="form-grid"><label>内部名称<input v-model="uploadName" placeholder="例如：月光三角杯"></label><label>款号<input v-model="uploadCode" placeholder="NW-2601"></label><label class="wide">描述<input v-model="uploadDescription" placeholder="象牙白 · 法式蕾丝"></label><label class="wide">标签<input v-model="uploadTags" placeholder="新品, 主推款"></label></div><label v-if="uploadType === '模特'" class="check-line"><input v-model="uploadAuthorized" type="checkbox"> 我确认模特素材已获得品牌生产使用授权</label><button class="dark-button modal-submit" type="submit" :disabled="uploading">{{ uploading ? '正在上传…' : '保存到素材库' }}</button></form></div>
    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --muted: #7d776f; --line: #e7e1d8; --paper: #f7f5f0; --gold: #a18455; }
* { box-sizing: border-box; } html, body, #__nuxt { min-height: 100%; margin: 0; } body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; } button, input, select { font: inherit; } button { cursor: pointer; }
.workspace-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }.sidebar { position: sticky; top: 0; z-index: 20; height: 100vh; display: flex; flex-direction: column; padding: 25px 18px; background: #fcfbf8; border-right: 1px solid var(--line); }.brand { display: flex; align-items: center; gap: 11px; padding: 0 8px 24px; color: var(--ink); text-align: left; background: transparent; border: 0; }.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid; border-radius: 50%; font: 18px Georgia, serif; }.brand-copy { display: flex; flex-direction: column; gap: 3px; letter-spacing: .15em; }.brand-copy strong { font: 500 14px Georgia, serif; }.brand-copy small { color: #847e75; font-size: 7px; }.space-card { display: flex; flex-direction: column; gap: 4px; margin-bottom: 18px; padding: 13px; background: #eee9e1; border-radius: 10px; }.space-card span, .space-card small { color: #8d867d; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }.space-card strong { font: 400 12px Georgia, serif; }.nav-list { display: flex; flex-direction: column; gap: 4px; }.nav-label { margin: 0 0 5px; padding: 0 11px; color: #999188; font-size: 9px; font-weight: 700; letter-spacing: .16em; }.nav-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 11px; color: #716c65; text-align: left; background: transparent; border: 0; border-radius: 8px; font-size: 12px; }.nav-item:hover, .nav-item.active { color: var(--ink); background: #eae5dd; }.nav-item.active { font-weight: 600; }.nav-icon { width: 16px; color: #938b81; font-size: 14px; }.nav-item em { display: grid; place-items: center; width: 19px; height: 19px; margin-left: auto; color: #fff; background: #292722; border-radius: 50%; font-size: 9px; font-style: normal; }.sidebar-bottom { margin-top: auto; }.tip-card { display: flex; flex-direction: column; gap: 7px; padding: 16px; background: #eee9e1; border-radius: 11px; }.tip-icon { display: grid; place-items: center; width: 24px; height: 24px; color: #fff; background: #292722; border-radius: 50%; font-size: 11px; }.tip-card strong { font: 400 14px Georgia, serif; }.tip-card p { margin: 0; color: #777169; font-size: 10px; line-height: 1.5; }.tip-card button, .profile button { padding: 0; color: #575149; text-align: left; background: transparent; border: 0; font-size: 10px; }.tip-card button { display: flex; justify-content: space-between; }.profile { display: flex; align-items: center; gap: 9px; margin-top: 16px; padding: 16px 5px 0; border-top: 1px solid var(--line); }.avatar { display: grid; place-items: center; width: 31px; height: 31px; color: #fff; background: #282622; border-radius: 50%; font-size: 9px; }.profile > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }.profile strong { font-size: 10px; }.profile small { color: #8b857d; font-size: 8px; }.profile button { margin-left: auto; font-size: 14px; color: #8b857d; }
.asset-image { display: grid; place-items: center; background: #e6dfd7; text-decoration: none; }.asset-image img { width: 100%; height: 100%; object-fit: cover; }.asset-file-icon { display: grid; place-items: center; width: 84px; height: 84px; color: #fff; background: #292722; border-radius: 14px; font: 18px Georgia, serif; letter-spacing: .08em; }
.main-area { min-width: 0; }.topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.project-switcher { display: flex; flex-direction: column; gap: 2px; }.project-switcher span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.project-switcher strong { font: 500 12px Georgia, serif; }.top-actions { display: flex; align-items: center; gap: 12px; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }.icon-button { display: grid; place-items: center; width: 34px; height: 34px; color: #69635b; background: #fff; border: 1px solid var(--line); border-radius: 50%; font-size: 17px; }.new-button, .dark-button { display: flex; align-items: center; gap: 7px; padding: 10px 14px; color: #fff; background: #1d1c19; border: 0; border-radius: 8px; font-size: 10px; }.new-button span { font-size: 14px; }
.content { max-width: 1500px; margin: 0 auto; padding: 40px 4% 65px; }.heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 32px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.heading > div > span { color: #817b73; font-size: 12px; }.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; margin-bottom: 20px; }.filter-tabs { display: flex; gap: 4px; padding: 4px; background: #ede9e2; border-radius: 9px; }.filter-tabs button { padding: 8px 13px; color: #746e66; background: transparent; border: 0; border-radius: 7px; font-size: 10px; }.filter-tabs button.active { color: #24211e; background: #fff; box-shadow: 0 2px 8px #322a2212; }.search-field { display: flex; align-items: center; gap: 7px; width: 270px; padding: 0 11px; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; }.search-field span { color: #918a81; font-size: 16px; }.search-field input { width: 100%; padding: 10px 0; color: #292622; background: transparent; border: 0; outline: 0; font-size: 10px; }.search-field input::placeholder { color: #aaa299; }.asset-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 15px; }.asset-card { overflow: hidden; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.asset-preview { position: relative; height: 225px; overflow: hidden; }.palette-cream { background: linear-gradient(135deg, #d8cfc3, #f5f0e9); }.palette-charcoal { background: linear-gradient(135deg, #302e2b, #827b73); }.palette-taupe { background: linear-gradient(135deg, #b6a99d, #e0d5c8); }.palette-stone { background: linear-gradient(135deg, #b7b6b1, #e5e1d8); }.palette-sand { background: linear-gradient(135deg, #d2c4b4, #f1e9df); }.palette-black { background: linear-gradient(135deg, #1e1e1c, #5d5750); }.asset-type { position: absolute; top: 11px; left: 11px; z-index: 2; padding: 5px 7px; color: #5e5850; background: #ffffffd1; border-radius: 20px; font-size: 7px; }.palette-charcoal .asset-type, .palette-black .asset-type { color: #ece4d9; background: #322f2ccc; }.asset-preview > b { position: absolute; right: 11px; bottom: 10px; color: #524c45; font-size: 7px; letter-spacing: .13em; font-weight: 400; }.palette-charcoal > b, .palette-black > b { color: #ded5ca; }.asset-shape { position: absolute; inset: 31px 23%; filter: drop-shadow(0 16px 16px #332b2328); }.asset-shape i:first-child { position: absolute; top: 8%; left: 11%; width: 78%; height: 54%; background: #fff9; border-radius: 45% 45% 22% 22%; clip-path: polygon(0 0, 48% 24%, 100% 0, 81% 100%, 50% 72%, 19% 100%); }.asset-shape i:nth-child(2), .asset-shape i:nth-child(3) { position: absolute; top: 14%; width: 1px; height: 83%; background: #5f544844; transform-origin: top; }.asset-shape i:nth-child(2) { left: 45%; transform: rotate(12deg); }.asset-shape i:nth-child(3) { right: 45%; transform: rotate(-12deg); }.asset-shape.garment i:first-child { background: #fff9; }.asset-shape.model i:first-child { width: 58%; left: 21%; height: 70%; background: #8b7d72; border-radius: 48% 48% 15% 15%; clip-path: polygon(28% 0, 72% 0, 87% 25%, 100% 100%, 0 100%, 13% 25%); }.asset-shape.reference i:first-child { width: 90%; left: 5%; height: 62%; top: 19%; background: #544d4699; border-radius: 4px; clip-path: none; }.asset-details { padding: 13px; }.asset-details > small { color: #a18455; font-size: 8px; }.asset-details h2 { margin: 5px 0; font: 400 14px Georgia, serif; }.asset-details p { height: 20px; margin: 0; color: #8a847c; font-size: 8px; }.asset-details > div { display: flex; align-items: center; justify-content: space-between; min-height: 20px; margin-top: 10px; }.asset-details button { padding: 0; color: #9b756d; background: transparent; border: 0; font-size: 8px; }.authorization { padding: 4px 6px; color: #537059; background: #e6eee7; border-radius: 12px; font-size: 8px; }.authorization.pending { color: #8a6947; background: #f2e9dd; }.empty-state { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 70px 20px; color: #898279; border: 1px dashed #d3ccc2; border-radius: 12px; text-align: center; }.empty-state strong { color: #4b4640; font: 400 17px Georgia, serif; }.empty-state span { font-size: 10px; }
.modal-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 20px; background: #1d1b1894; backdrop-filter: blur(4px); }.modal-card { width: min(620px, 100%); max-height: 92vh; overflow: auto; padding: 25px; background: #f8f6f1; border-radius: 14px; box-shadow: 0 30px 90px #00000040; }.modal-head { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }.modal-head h2 { margin: 5px 0; font: 400 25px Georgia, serif; }.close-button { color: #766f67; background: transparent; border: 0; font-size: 24px; }.modal-card label { display: flex; flex-direction: column; gap: 8px; margin-bottom: 17px; color: #59554f; font-size: 11px; }.modal-card input, .modal-card select { width: 100%; padding: 12px 13px; color: #26231f; background: #fff; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; }.drop-zone { align-items: center; padding: 20px; text-align: center; background: #fff; border: 1px dashed #bfb5a8; border-radius: 9px; }.drop-zone strong { font-weight: 500; }.drop-zone input { padding: 5px; border: 0; }.drop-zone small { color: #918a81; font-size: 8px; }.upload-symbol { display: grid; place-items: center; width: 30px; height: 30px; margin-bottom: 2px; color: #fff; background: #292722; border-radius: 50%; font-size: 16px; }.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }.check-line { flex-direction: row !important; align-items: center; }.check-line input { width: auto; }.modal-submit { justify-content: center; width: 100%; margin-top: 4px; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 110; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; font-size: 10px; box-shadow: 0 10px 30px #0002; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 1150px) { .asset-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 800px) { .workspace-layout { display: block; }.sidebar { position: static; flex-direction: row; align-items: center; height: auto; padding: 13px 16px; }.brand { padding: 0; }.space-card, .nav-list, .tip-card, .profile > span:nth-child(2), .profile button { display: none; }.profile { margin: 0 0 0 auto; padding: 0; border: 0; }.topbar { height: 58px; padding: 0 18px; }.service-state, .icon-button { display: none; }.new-button { padding: 9px; font-size: 0; }.new-button span { font-size: 16px; }.content { padding: 30px 16px 55px; }.heading { align-items: flex-start; flex-direction: column; }.heading > .dark-button { width: 100%; justify-content: center; }.toolbar { align-items: stretch; flex-direction: column; }.search-field { width: 100%; }.asset-grid { grid-template-columns: repeat(2, 1fr); } }
@media (max-width: 520px) { .asset-grid { grid-template-columns: 1fr; }.asset-preview { height: 300px; }.form-grid { grid-template-columns: 1fr; }.modal-card { padding: 20px; } }
</style>
