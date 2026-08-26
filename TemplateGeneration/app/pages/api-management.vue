<script setup lang="ts">
import {computed, ref, toRaw} from 'vue'

definePageMeta({layout: false})

type ProviderType = 'OpenAI' | 'Anthropic' | '兼容网关'
type ProviderAuth = 'Bearer Token' | 'Custom Header' | '无需认证'
type ProviderModel = { id: number; name: string; selected?: boolean }
type Provider = { id: number; name: string; type: ProviderType; baseUrl: string; protocol: 'HTTPS' | 'HTTP'; auth: ProviderAuth; credentialValue: string; credentialConfigured: boolean; enabled: boolean; modelId: number | null; model: string; models: ProviderModel[]; updatedAt: string }

const providers = ref<Provider[]>([])
const selectedId = ref(0)
const query = ref('')
const filter = ref<'全部' | '已启用' | '已停用'>('全部')
const editing = ref(false)
const reveal = ref(false)
const loading = ref(true)
const loadError = ref('')
const saving = ref(false)
const refreshingModels = ref(false)
const testingModel = ref(false)
const modelTestResult = ref<{ok: boolean; message: string; latencyMs?: number} | null>(null)
const toast = ref('')
const draft = ref<Provider | null>(null)
const selected = computed(() => providers.value.find((item) => item.id === selectedId.value) ?? providers.value[0])
const editingProvider = computed(() => draft.value ?? selected.value)
const emptyProvider: Provider = {id: 0, name: '', type: 'OpenAI', baseUrl: '', protocol: 'HTTPS', auth: 'Bearer Token', credentialValue: '', credentialConfigured: false, enabled: false, modelId: null, model: '', models: [], updatedAt: ''}
const modelProvider = computed<Provider>(() => editingProvider.value ?? emptyProvider)
const selectedModels = computed(() => modelProvider.value.models.filter((model) => model.selected !== false))
const visible = computed(() => providers.value.filter((item) => (filter.value === '全部' || (filter.value === '已启用' ? item.enabled : !item.enabled)) && `${item.name} ${item.type}`.toLowerCase().includes(query.value.toLowerCase())))

function notify(message: string) {
  if (!import.meta.client) return
  toast.value = message
  window.setTimeout(() => { if (toast.value === message) toast.value = '' }, 2200)
}

function errorMessage(error: unknown, fallback: string) {
  const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
  return requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? fallback
}

function replaceProvider(provider: Provider) {
  const index = providers.value.findIndex((item) => item.id === provider.id)
  if (index === -1) providers.value.push(provider)
  else providers.value[index] = provider
  selectedId.value = provider.id
}

async function refreshProviders() {
  loading.value = true
  loadError.value = ''
  try {
    const requestFetch = import.meta.server ? useRequestFetch() : $fetch
    const response = await requestFetch<{providers: Provider[]}>('/api/providers')
    providers.value = response.providers
    if (!providers.value.some((item) => item.id === selectedId.value)) selectedId.value = providers.value[0]?.id ?? 0
  } catch (error: unknown) {
    loadError.value = errorMessage(error, '提供商配置加载失败，请刷新页面重试。')
    notify(loadError.value)
  } finally {
    loading.value = false
  }
}

function choose(item: Provider) {
  selectedId.value = item.id
  cancelEdit()
  reveal.value = false
  modelTestResult.value = null
}

function beginEdit() {
  if (!selected.value) return
  const provider = toRaw(selected.value)
  draft.value = {
    ...provider,
    models: provider.models.map((model) => ({...model})),
  }
  editing.value = true
  reveal.value = false
  modelTestResult.value = null
}

function cancelEdit() {
  draft.value = null
  editing.value = false
  reveal.value = false
}

function toggleModelSelection(model: ProviderModel) {
  if (!editing.value || saving.value) return
  const provider = modelProvider.value
  const currentlySelected = model.selected !== false
  if (currentlySelected && selectedModels.value.length <= 1) {
    notify('模型列表至少需要保留一个模型。')
    return
  }
  model.selected = !currentlySelected
  if (!model.selected && provider.modelId === model.id) {
    const next = selectedModels.value[0]
    provider.modelId = next?.id ?? null
    provider.model = next?.name ?? ''
  }
  modelTestResult.value = null
}

async function testModel() {
  const provider = modelProvider.value
  if (!provider?.id || !provider.model || testingModel.value) return
  testingModel.value = true
  modelTestResult.value = null
  try {
    const response = await $fetch<{ok: boolean; model: string; latency_ms: number}>(`/api/providers/${provider.id}/test`, {
      method: 'POST',
      body: {
        model_id: provider.modelId && provider.modelId > 0 ? provider.modelId : undefined,
        model: provider.model,
        type: provider.type,
        baseUrl: provider.baseUrl,
        auth: provider.auth,
        credentialValue: provider.credentialValue || undefined,
      },
    })
    modelTestResult.value = {ok: true, message: `${response.model} 测活成功，响应耗时 ${response.latency_ms} ms。`, latencyMs: response.latency_ms}
  } catch (error: unknown) {
    modelTestResult.value = {ok: false, message: errorMessage(error, '模型测活失败，请检查连接配置。')}
  } finally {
    testingModel.value = false
  }
}

function changeProtocol() {
  if (!editingProvider.value) return
  editingProvider.value.baseUrl = editingProvider.value.baseUrl.trim().replace(/^https?:\/\//i, `${editingProvider.value.protocol.toLowerCase()}://`)
}

async function add() {
  try {
    const response = await $fetch<{provider: Provider}>('/api/providers', {
      method: 'POST',
      body: {name: '新的模型提供商', type: 'OpenAI', baseUrl: 'https://api.example.com/v1', auth: 'Bearer Token', model: 'gpt-4o-mini', models: ['gpt-4o-mini'], enabled: false},
    })
    replaceProvider(response.provider)
    beginEdit()
    notify('已创建新的提供商配置，请补充连接信息。')
  } catch (error: unknown) {
    notify(errorMessage(error, '提供商创建失败。'))
  }
}

async function save() {
  if (!editingProvider.value || saving.value) return
  if (!editingProvider.value.name.trim()) return notify('请填写提供商名称。')
  if (!editingProvider.value.baseUrl.trim()) return notify('请填写基础路由，例如 https://api.example.com/v1。')

  saving.value = true
  try {
    const response = await $fetch<{provider: Provider}>(`/api/providers/${editingProvider.value.id}`, {
      method: 'PUT',
      body: {
        name: editingProvider.value.name,
        type: editingProvider.value.type,
        baseUrl: editingProvider.value.baseUrl,
        auth: editingProvider.value.auth,
        credentialValue: editingProvider.value.credentialValue || undefined,
        model: editingProvider.value.model,
        modelId: editingProvider.value.modelId && editingProvider.value.modelId > 0 ? editingProvider.value.modelId : undefined,
        models: editingProvider.value.models.filter((model) => model.selected !== false).map((model) => model.name),
        enabled: editingProvider.value.enabled,
      },
    })
    replaceProvider(response.provider)
    cancelEdit()
    notify('提供商配置已保存到 SQLite。')
  } catch (error: unknown) {
    notify(errorMessage(error, '提供商配置保存失败。'))
  } finally {
    saving.value = false
  }
}

async function refreshModels() {
  const provider = editingProvider.value ?? selected.value
  if (!provider || refreshingModels.value) return
  refreshingModels.value = true
  try {
    const response = await $fetch<{provider?: Provider; models?: Array<{name: string}>}>(`/api/providers/${provider.id}/models`, {
      method: 'POST',
      body: editing.value ? {
        type: provider.type,
        baseUrl: provider.baseUrl,
        auth: provider.auth,
        credentialValue: provider.credentialValue || undefined,
      } : undefined,
    })
    if (editingProvider.value && response.models) {
      const selectedName = provider.model
      const models = response.models.map((model, index) => ({id: -(index + 1), name: model.name}))
      provider.models = models.map((model) => ({...model, selected: true}))
      provider.model = models.some((model) => model.name === selectedName) ? selectedName : models[0]?.name ?? ''
      provider.modelId = models.find((model) => model.name === provider.model)?.id ?? null
      modelTestResult.value = null
      notify(`已从 Node.js 服务获取 ${models.length} 个模型，请人工选择后保存配置。`)
    } else if (response.provider) {
      replaceProvider(response.provider)
      notify(`已获取并保存 ${response.provider.models.length} 个模型，模型 ID 已持久化。`)
    }
  } catch (error: unknown) {
    notify(errorMessage(error, '模型列表获取失败，请检查提供商连接配置。'))
  } finally {
    refreshingModels.value = false
  }
}

async function saveModelSelection() {
  const provider = editingProvider.value
  if (!provider || saving.value) return
  if (draft.value?.id === provider.id) {
    const selectedModel = provider.models.find((model) => model.id === provider.modelId)
    if (selectedModel) provider.model = selectedModel.name
    modelTestResult.value = null
    return
  }
  saving.value = true
  modelTestResult.value = null
  try {
    const response = await $fetch<{provider: Provider}>(`/api/providers/${provider.id}/models`, {
      method: 'PUT',
      body: {models: provider.models.filter((model) => model.selected !== false).map((model) => model.name), model_id: provider.modelId},
    })
    if (draft.value?.id === provider.id) {
      draft.value.model = response.provider.model
      draft.value.modelId = response.provider.modelId
      draft.value.models = response.provider.models.map((model) => ({...model}))
    } else {
      replaceProvider(response.provider)
    }
    notify('当前模型及模型目录已持久化到 SQLite。')
  } catch (error: unknown) {
    notify(errorMessage(error, '当前模型保存失败，请重试。'))
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!selected.value || !window.confirm(`确定删除“${selected.value.name}”吗？`)) return
  try {
    const providerUrl = `/api/providers/${selected.value.id}` as string
    await $fetch(providerUrl, {method: 'DELETE'})
    providers.value = providers.value.filter((item) => item.id !== selected.value?.id)
    selectedId.value = providers.value[0]?.id ?? 0
    cancelEdit()
    notify('提供商已删除。')
  } catch (error: unknown) {
    notify(errorMessage(error, '提供商删除失败。'))
  }
}

async function toggle(item: Provider) {
  try {
    const response = await $fetch<{provider: Provider}>(`/api/providers/${item.id}`, {method: 'PATCH', body: {enabled: !item.enabled}})
    replaceProvider(response.provider)
    notify(`${item.name} 已${response.provider.enabled ? '启用' : '停用'}`)
  } catch (error: unknown) {
    notify(errorMessage(error, '提供商状态更新失败。'))
  }
}

function setCurrent() {
  if (!selected.value?.enabled) return notify('请先启用该提供商。')
  notify(`当前模型已切换为 ${selected.value.name} · ${selected.value.model}`)
}

async function addModel() {
  if (!selected.value) return
  beginEdit()
  if (!draft.value) return
  const name = `custom-model-${draft.value.models.length + 1}`
  draft.value.models.push({id: -(draft.value.models.length + 1), name, selected: true})
  draft.value.model = name
  draft.value.modelId = null
  modelTestResult.value = null
  await save()
}

await refreshProviders()
</script>

<template>
  <div class="api-layout"><StudioSidebar /><section class="api-main"><header class="topbar"><div><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div><span class="service-state"><i /> 生成服务由平台安全代理</span></header><main class="content">
    <section class="heading"><div><p class="eyebrow">AI PROVIDER CONTROL</p><h1>API 管理</h1><span>管理模型服务连接，并选择当前工作区正在使用的提供商。</span></div><div class="heading-actions"><button class="quiet" type="button" :disabled="loading" @click="refreshProviders">{{ loading ? '正在刷新…' : '刷新配置' }}</button><button class="dark" type="button" @click="add">＋ 添加提供商</button></div></section>
    <section v-if="selected" class="current"><b>✓</b><span><small>当前模型提供商</small><strong>{{ selected.name }} · {{ selected.model }}</strong></span><button type="button" @click="beginEdit">编辑当前配置 →</button></section>
    <section class="management"><aside class="list-panel"><div class="panel-head"><div><p class="eyebrow">PROVIDERS</p><h2>提供商列表</h2></div><em>{{ providers.length }}</em></div><div class="tools"><input v-model="query" type="search" placeholder="搜索提供商" /><select v-model="filter"><option>全部</option><option>已启用</option><option>已停用</option></select></div><div class="rows"><p v-if="loading" class="empty">正在加载提供商配置…</p><div v-else-if="loadError" class="empty load-error"><span>{{ loadError }}</span><button type="button" @click="refreshProviders">重试加载</button></div><button v-for="item in visible" :key="item.id" class="row" :class="{active:item.id===selectedId}" type="button" @click="choose(item)"><b>{{ item.type === 'Anthropic' ? 'A' : item.type === 'OpenAI' ? 'O' : 'G' }}</b><span><strong>{{ item.name }}</strong><small>{{ item.type }} · {{ item.model }}</small><i :class="{off:!item.enabled}">{{ item.enabled ? '● 已启用' : '● 已停用' }}</i></span><em v-if="item.id===selectedId">当前</em></button><p v-if="!loading && !loadError && !visible.length" class="empty">没有匹配的提供商</p></div><button class="add-link" type="button" @click="add">＋ 添加新的提供商</button></aside>
      <section v-if="selected" class="detail-panel"><div class="panel-head"><div><p class="eyebrow">{{ editing ? 'EDIT PROVIDER' : 'PROVIDER OVERVIEW' }}</p><h2>{{ editing ? '编辑连接' : selected.name }}</h2></div><div class="actions"><button v-if="!editing" class="quiet" type="button" @click="beginEdit">编辑</button><button class="danger" type="button" @click="remove">删除</button></div></div>
        <div v-if="!editing" class="overview"><div><span>提供商类型</span><strong>{{ selected.type }}</strong></div><div><span>连接协议</span><strong>{{ selected.protocol }}</strong></div><div><span>基础路由</span><strong>{{ selected.baseUrl }}</strong></div><div><span>认证方式</span><strong>{{ selected.auth }}</strong></div><div><span>访问密钥</span><strong>{{ selected.credentialConfigured ? '已配置（不会回显）' : '未配置' }}</strong></div><div><span>最近更新</span><strong>{{ selected.updatedAt }}</strong></div></div>
        <form v-else-if="editingProvider" class="form" @submit.prevent="save"><label class="wide"><span>提供商名称</span><input v-model="editingProvider.name" type="text" maxlength="120" /><small>用于团队识别，不影响服务调用。</small></label><label><span>提供商类型</span><select v-model="editingProvider.type"><option>OpenAI</option><option>Anthropic</option><option>兼容网关</option></select><small>可选择 OpenAI 协议类型或 Anthropic 类型。</small></label><label><span>协议</span><select v-model="editingProvider.protocol" @change="changeProtocol"><option>HTTPS</option><option>HTTP</option></select></label><label class="wide"><span>基础路由</span><input v-model="editingProvider.baseUrl" type="url" placeholder="https://api.example.com/v1" /><small>填写完整 HTTP/HTTPS 地址，例如 https://api.example.com/v1。</small></label><label><span>认证方式</span><select v-model="editingProvider.auth"><option>Bearer Token</option><option>Custom Header</option><option>无需认证</option></select></label><label v-if="editingProvider.auth !== '无需认证'"><span>访问密钥</span><div class="secret-field"><input v-model="editingProvider.credentialValue" :type="reveal?'text':'password'" :placeholder="editingProvider.credentialConfigured ? '已保存密钥，留空则保持不变' : '输入服务凭据'" /><button type="button" @click="reveal = !reveal">{{ reveal ? '隐藏' : '显示' }}</button></div><small>服务端只返回是否已配置，不会回显密钥。</small></label><div class="wide form-buttons"><button class="quiet" type="button" @click="cancelEdit">取消</button><button class="dark" type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存配置' }}</button></div></form>
        <div class="models"><div class="model-head"><div><p class="eyebrow">MODEL CATALOG</p><h3>模型与当前选择</h3></div><div class="model-actions"><button type="button" :disabled="refreshingModels" @click="refreshModels">{{ refreshingModels ? '正在获取…' : '获取模型列表' }}</button><button type="button" @click="addModel">＋ 添加模型</button></div></div><p class="model-protocol-note">通过本地 Node.js 服务按 OpenAI 协议 GET /models 读取 data[].id；编辑时可人工勾选后保存。</p><p v-if="!modelProvider.models.length" class="model-empty">暂无已保存模型，请先获取模型列表或添加模型。</p><div v-else class="model-control"><label><span>当前使用模型</span><select v-model="modelProvider.modelId" :disabled="saving || testingModel" @change="saveModelSelection"><option v-for="model in modelProvider.models.filter((item) => item.selected !== false)" :key="model.id" :value="model.id">{{ model.name }} · ID {{ model.id }}</option></select></label><button class="use" :class="{active:selected.enabled}" type="button" @click="setCurrent">{{ selected.enabled ? '当前正在使用' : '先启用后切换' }}</button><button class="test-model" type="button" :disabled="!modelProvider.model || testingModel || saving" @click="testModel">{{ testingModel ? '测活中…' : modelTestResult?.ok ? '重新测活' : '模型测活' }}</button></div><p v-if="editing" class="model-selection-note">已保留 {{ selectedModels.length }} 个模型，取消勾选的模型将在保存时移除。</p><p v-if="modelTestResult" class="model-test-result" :class="{success:modelTestResult.ok, failure:!modelTestResult.ok}" role="status">{{ modelTestResult.message }}</p><div class="model-tags"><button v-for="model in modelProvider.models" :key="model.id" type="button" class="model-tag" :class="{active:model.id===modelProvider.modelId,unselected:model.selected===false}" @click="toggleModelSelection(model)">{{ model.name }} <small>ID {{ model.id }}</small><b v-if="model.id===modelProvider.modelId">当前</b><i>{{ model.selected === false ? '已取消' : editing ? '保留' : '' }}</i></button></div></div>
        <div class="status-line"><span>连接状态</span><strong :class="{disabled:!selected.enabled}">{{ selected.enabled ? '● 已启用' : '● 已停用' }}</strong><button type="button" @click="toggle(selected)">{{ selected.enabled ? '停用连接' : '启用连接' }}</button></div>
      </section>
    </section><p class="note">配置保存在 SQLite，并通过当前登录会话访问；访问密钥不会在列表接口中回显。</p>
  </main></section><Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition></div>
</template>

<style>
.model-actions{display:flex;align-items:center;gap:10px}
.test-model{min-height:38px;padding:9px 12px;color:#546857;background:#e5ede5;border:1px solid #d1dfd1;border-radius:8px;font-size:9px}.test-model:disabled{cursor:wait;opacity:.55}.model-test-result{margin:9px 0 0;padding:9px 11px;border-radius:7px;font-size:9px}.model-test-result.success{color:#4f6654;background:#e8f0e8}.model-test-result.failure{color:#986f66;background:#fff4f1;border:1px solid #ead9d4}
.model-protocol-note{margin:-7px 0 10px;color:#979087;font-size:8px}
.model-selection-note{margin:9px 0 0;color:#89775f;font-size:8px}.model-tag{padding:7px 9px;color:#756d64;background:#f4f0e9;border:0;border-radius:6px;font-size:8px}.model-tag.unselected{opacity:.48;text-decoration:line-through}.model-tag i{margin-left:5px;color:#986f66;font-size:7px;font-style:normal}
.model-actions button:disabled{cursor:wait;opacity:.55}.model-empty{margin:0 0 10px;padding:12px;color:#8f887f;background:#faf8f4;border:1px dashed #e1d9cf;border-radius:7px;font-size:8px}
.secret-field{display:flex;gap:6px}.secret-field input{min-width:0;flex:1}.secret-field button{padding:0 9px;color:#756d64;background:#f4f0e9;border:0;border-radius:7px;font-size:9px}
.heading-actions{display:flex;gap:8px}.dark:disabled,.quiet:disabled{cursor:wait;opacity:.55}.load-error{display:flex;flex-direction:column;align-items:center;gap:9px}.load-error button{padding:6px 10px;color:#665e54;background:#f4f0e9;border:0;border-radius:6px;font-size:9px}
:root{--ink:#24221f;--line:#e7e1d8;--paper:#f7f5f0}*{box-sizing:border-box}html,body,#__nuxt{min-height:100%;margin:0}body{background:var(--paper);color:var(--ink);font-family:Arial,Helvetica,sans-serif}button,input,select{font:inherit;cursor:pointer}.api-layout{min-height:100vh;display:grid;grid-template-columns:230px minmax(0,1fr);background:var(--paper)}.api-main{min-width:0}.topbar{position:sticky;top:0;z-index:15;display:flex;align-items:center;justify-content:space-between;height:68px;padding:0 4%;background:#fcfbf8e6;border-bottom:1px solid var(--line)}.topbar>div{display:flex;flex-direction:column;gap:2px}.topbar span{color:#8c867d;font-size:8px;letter-spacing:.12em;text-transform:uppercase}.topbar strong{font:500 12px Georgia,serif}.service-state{color:#68635c!important;font-size:9px!important;letter-spacing:0!important;text-transform:none!important}.service-state i{display:inline-block;width:7px;height:7px;margin-right:6px;background:#7f9b82;border-radius:50%}.content{max-width:1500px;margin:auto;padding:42px 4% 65px}.heading{display:flex;align-items:flex-end;justify-content:space-between;gap:20px;margin-bottom:24px}.eyebrow{margin:0;color:#8c867d;font-size:10px;font-weight:700;letter-spacing:.17em}.heading h1{margin:7px 0 8px;font:400 clamp(32px,4vw,48px) Georgia,serif}.heading>div>span{color:#817b73;font-size:12px}.dark,.quiet,.danger{display:inline-flex;align-items:center;justify-content:center;min-height:38px;padding:9px 13px;border-radius:8px;font-size:10px}.dark{color:#fff;background:#1d1c19;border:0}.quiet{color:#5d5750;background:#fff;border:1px solid var(--line)}.danger{color:#986f66;background:#fff8f6;border:1px solid #ead9d4}.current{display:flex;align-items:center;gap:12px;margin-bottom:20px;padding:14px 17px;background:#e9eee8;border:1px solid #d5e0d4;border-radius:10px}.current>b{display:grid;place-items:center;width:28px;height:28px;color:#fff;background:#657b68;border-radius:50%}.current span{display:flex;flex-direction:column;gap:4px}.current small{color:#637566;font-size:8px;letter-spacing:.11em}.current strong{font-size:11px}.current button{margin-left:auto;color:#546857;background:transparent;border:0;font-size:9px}.management{display:flex;align-items:flex-start;gap:18px}.list-panel,.detail-panel{padding:21px;background:#fff;border:1px solid #e7e2d9;border-radius:13px}.list-panel{width:37%;min-width:290px}.detail-panel{flex:1;min-width:0}.panel-head,.model-head{display:flex;align-items:flex-end;justify-content:space-between;margin-bottom:15px}.panel-head h2{margin:4px 0 0;font:400 22px Georgia,serif}.panel-head>em{display:grid;place-items:center;width:24px;height:24px;color:#675a4a;background:#f0e9de;border-radius:50%;font-size:9px;font-style:normal}.tools{display:flex;gap:7px;margin-bottom:13px}.tools input,.tools select{min-width:0;padding:9px 10px;background:#fcfbf8;border:1px solid #e2dbd2;border-radius:7px;font-size:9px}.tools input{flex:1}.tools select{width:80px}.rows{display:flex;flex-direction:column;gap:7px}.row{display:flex;align-items:center;gap:9px;width:100%;padding:12px 10px;color:var(--ink);text-align:left;background:#fcfbf8;border:1px solid #ece7df;border-radius:8px}.row:hover,.row.active{background:#f4f0e9;border-color:#c9b99f}.row>b{display:grid;place-items:center;width:30px;height:30px;color:#fff;background:#292722;border-radius:8px;font:400 14px Georgia,serif}.row>span{display:flex;min-width:0;flex-direction:column;gap:3px}.row small{color:#8e877f;font-size:8px}.row i{color:#718271;font-size:8px;font-style:normal}.row i.off{color:#a39b92}.row>em{margin-left:auto;color:#89775f;font-size:8px;font-style:normal}.empty{padding:20px 0;color:#999188;font-size:9px;text-align:center}.add-link{width:100%;margin-top:12px;padding:11px;color:#665e54;background:transparent;border:1px dashed #cfc8bd;border-radius:8px;font-size:9px}.actions{display:flex;gap:7px}.overview{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-bottom:22px}.overview>div{display:flex;flex-direction:column;gap:6px;padding:13px;background:#faf8f4;border:1px solid #eee9e2;border-radius:8px}.overview span,.model-control span{color:#969087;font-size:8px}.overview strong{overflow:hidden;font-size:10px;text-overflow:ellipsis;white-space:nowrap}.form{display:grid;grid-template-columns:1fr 1fr;gap:15px 12px}.form label,.model-control label{display:flex;flex-direction:column;gap:6px;min-width:0}.form label>span,.model-control label>span{color:#4c4741;font-size:9px;font-weight:600}.form input,.form select,.model-control select{width:100%;min-height:38px;padding:9px 11px;background:#fff;border:1px solid #ddd7ce;border-radius:7px;font-size:10px}.form small{color:#979087;font-size:8px}.wide{grid-column:1/-1}.form-buttons{display:flex;justify-content:flex-end;gap:8px}.models{margin-top:23px;padding-top:19px;border-top:1px solid #eee9e2}.model-head h3{margin:4px 0 0;font:400 18px Georgia,serif}.model-head button{padding:0;color:#665e54;background:transparent;border:0;font-size:9px}.model-control{display:flex;align-items:flex-end;gap:9px}.model-control label{flex:1}.use{min-height:38px;padding:9px 12px;color:#fff;background:#657b68;border:0;border-radius:8px;font-size:9px}.use:not(.active){background:#b1aba2}.model-tags{display:flex;flex-wrap:wrap;gap:6px;margin-top:12px}.model-tags span{padding:7px 9px;color:#756d64;background:#f4f0e9;border-radius:6px;font-size:8px}.model-tags span.active{color:#4f6654;background:#e5ede5}.model-tags b{margin-left:5px;font-size:7px}.status-line{display:flex;align-items:center;gap:10px;margin-top:21px;padding-top:15px;border-top:1px solid #eee9e2}.status-line span{color:#969087;font-size:8px}.status-line strong{color:#718271;font-size:9px}.status-line strong.disabled{color:#a39b92}.status-line button{margin-left:auto;padding:0;color:#725f55;background:transparent;border:0;font-size:9px}.note{margin:14px 2px 0;color:#999188;font-size:8px}.toast{position:fixed;right:24px;bottom:24px;z-index:50;padding:11px 15px;color:#fff;background:#292722;border-radius:8px;font-size:10px}@media(max-width:980px){.management{flex-direction:column}.list-panel,.detail-panel{width:100%;min-width:0}}@media(max-width:800px){.api-layout{display:block}.topbar{height:58px;padding:0 18px}.service-state{display:none}.content{padding:30px 16px 55px}.heading{align-items:flex-start;flex-direction:column}.heading .dark{width:100%}.form,.overview{grid-template-columns:1fr}.wide{grid-column:auto}.model-control{align-items:stretch;flex-direction:column}.model-control .use{width:100%}.current button{display:none}.toast{right:16px;bottom:16px;left:16px;text-align:center}}
</style>
