<script setup lang="ts">
import { computed, ref } from 'vue'

definePageMeta({ layout: false })

type StepId = 1 | 2 | 3 | 4
type WorkflowVersion = {
  id: number
  name: string
  version: string
  savedAt: string
  garment: string
  model: string
}

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const activeStep = ref<StepId>(1)
const furthestStep = ref<StepId>(1)
const toast = ref('')
const workflowName = ref('NOIR 春夏主视觉')
const selectedGarment = ref('')
const selectedModel = ref('')
const creativePrompt = ref('')
const negativePrompt = ref('')
const aspectRatio = ref('4:5')
const camera = ref('85mm 人像镜头')
const lighting = ref('柔和侧光')
const outputCount = ref(4)
const highDefinition = ref(true)
const faceConsistency = ref(true)

const steps: { id: StepId; title: string; hint: string }[] = [
  { id: 1, title: '素材组合', hint: '服装与授权模特' },
  { id: 2, title: '创意提示', hint: '描述画面与排除项' },
  { id: 3, title: '生成参数', hint: '画幅、镜头与光线' },
  { id: 4, title: '确认保存', hint: '检查并形成版本' },
]

const garments = [
  { id: 'moonlight', name: '月光三角杯文胸', meta: 'NW-2601 · 象牙白法式蕾丝', palette: 'cream' },
  { id: 'midnight', name: '雾黑丝缎套装', meta: 'NW-2602 · 黑色轻薄网纱', palette: 'charcoal' },
]

const models = [
  { id: 'model-a', name: 'Model A · Editorial', meta: 'MODEL-014 · 正面半身', palette: 'taupe' },
  { id: 'model-b', name: 'Mia Zhou · Natural', meta: 'MODEL-021 · 三分之二侧身', palette: 'stone' },
]

const versions = ref<WorkflowVersion[]>([
  { id: 1, name: '晨光主视觉', version: 'IMAGE · V3', savedAt: '今日 09:42', garment: '月光三角杯文胸', model: 'Model A · Editorial' },
  { id: 2, name: '黑色丝缎细节', version: 'IMAGE · V2', savedAt: '昨日 16:18', garment: '雾黑丝缎套装', model: 'Mia Zhou · Natural' },
])

const selectedGarmentData = computed(() => garments.find((item) => item.id === selectedGarment.value))
const selectedModelData = computed(() => models.find((item) => item.id === selectedModel.value))
const isStepOneValid = computed(() => Boolean(selectedGarment.value && selectedModel.value))
const isStepTwoValid = computed(() => creativePrompt.value.trim().length >= 12)
const isStepThreeValid = computed(() => Boolean(aspectRatio.value && camera.value && lighting.value && outputCount.value > 0))
const canContinue = computed(() => activeStep.value === 1 ? isStepOneValid.value : activeStep.value === 2 ? isStepTwoValid.value : activeStep.value === 3 ? isStepThreeValid.value : true)
const reviewItems = computed(() => [
  { label: '服装', value: selectedGarmentData.value?.name || '尚未选择' },
  { label: '模特', value: selectedModelData.value?.name || '尚未选择' },
  { label: '画面描述', value: creativePrompt.value || '尚未填写' },
  { label: '生成设置', value: `${aspectRatio.value} · ${camera.value} · ${lighting.value}` },
])

function showToast(message: string) {
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2400)
}

function stepState(step: StepId) {
  return step < activeStep.value || step <= furthestStep.value && step !== activeStep.value ? 'complete' : step === activeStep.value ? 'active' : 'locked'
}

function goToStep(step: StepId) {
  if (step <= furthestStep.value) {
    activeStep.value = step
    return
  }
  showToast('请按当前步骤完成必填内容后继续')
}

function validateCurrentStep() {
  if (activeStep.value === 1 && !isStepOneValid.value) {
    showToast('请选择一件服装和一位已授权模特')
    return false
  }
  if (activeStep.value === 2 && !isStepTwoValid.value) {
    showToast('画面描述至少需要 12 个字符')
    return false
  }
  if (activeStep.value === 3 && !isStepThreeValid.value) {
    showToast('请完整设置画幅、镜头、光线和生成数量')
    return false
  }
  return true
}

function nextStep() {
  if (!validateCurrentStep()) return
  if (activeStep.value < 4) {
    const next = (activeStep.value + 1) as StepId
    activeStep.value = next
    if (next > furthestStep.value) furthestStep.value = next
    return
  }
  saveVersion()
}

function previousStep() {
  if (activeStep.value > 1) activeStep.value = (activeStep.value - 1) as StepId
}

function saveVersion() {
  if (!validateCurrentStep()) return
  const nextVersion = versions.value.length + 1
  versions.value.unshift({
    id: Date.now(),
    name: workflowName.value.trim() || '未命名工作流',
    version: `IMAGE · V${nextVersion}`,
    savedAt: '刚刚',
    garment: selectedGarmentData.value?.name || '未选择',
    model: selectedModelData.value?.name || '未选择',
  })
  showToast(`已保存“${workflowName.value.trim() || '未命名工作流'}”的新版本`)
}

function loadVersion(version: WorkflowVersion) {
  const garment = garments.find((item) => item.name === version.garment)
  const model = models.find((item) => item.name === version.model)
  workflowName.value = version.name
  selectedGarment.value = garment?.id || ''
  selectedModel.value = model?.id || ''
  activeStep.value = 1
  furthestStep.value = 4
  showToast(`已载入“${version.name}”，可以继续编辑`)
}

function saveTemplate() {
  showToast('已将当前配置另存为工作流模板')
}
</script>

<template>
  <div class="workspace-layout">
    <StudioSidebar :project-id="projectId" />

    <section class="main-area">
      <header class="topbar">
        <div class="project-switcher"><span>品牌工作空间</span><strong>NOIR STUDIO</strong></div>
        <div class="top-actions"><span class="service-state"><i /> 生成服务由平台安全代理</span><button class="icon-button" type="button" aria-label="通知" @click="showToast('暂无新的通知')">⌁</button></div>
      </header>

      <main class="content">
        <section class="heading">
          <div><p class="eyebrow">WORKFLOW BUILDER</p><h1>创建生成工作流</h1><span>每次保存都会形成可追溯、不可覆盖的版本。</span></div>
          <div class="heading-actions"><button class="quiet-button" type="button" @click="saveTemplate">另存模板</button><button class="dark-button" type="button" @click="activeStep = 4; furthestStep = 4">保存新版本</button></div>
        </section>

        <div class="workflow-layout">
          <aside class="workflow-steps" aria-label="工作流步骤">
            <button v-for="step in steps" :key="step.id" type="button" class="step-button" :class="[stepState(step.id), { clickable: step.id <= furthestStep }]" :aria-current="activeStep === step.id ? 'step' : undefined" @click="goToStep(step.id)">
              <span class="step-number"><b v-if="step.id < activeStep || step.id < furthestStep">✓</b><template v-else>{{ step.id }}</template></span>
              <span><strong>{{ step.title }}</strong><small>{{ step.hint }}</small></span>
            </button>
          </aside>

          <section class="editor-panel">
            <div v-if="activeStep === 1" class="step-view">
              <div class="editor-head"><p class="eyebrow">STEP 01</p><h2>组合创作素材</h2><span>至少选择一份服装和一位已授权模特。</span></div>
              <h3 class="field-title">服装 <small>单选</small></h3>
              <div class="choice-grid">
                <button v-for="garment in garments" :key="garment.id" type="button" class="choice-card" :class="[`palette-${garment.palette}`, { selected: selectedGarment === garment.id }]" @click="selectedGarment = garment.id">
                  <span class="asset-shape garment-shape"><i /></span><span class="choice-copy"><strong>{{ garment.name }}</strong><small>{{ garment.meta }}</small></span><span class="select-mark">{{ selectedGarment === garment.id ? '✓' : '选择' }}</span>
                </button>
              </div>
              <h3 class="field-title">模特 <small>需已授权</small></h3>
              <div class="choice-grid">
                <button v-for="model in models" :key="model.id" type="button" class="choice-card" :class="[`palette-${model.palette}`, { selected: selectedModel === model.id }]" @click="selectedModel = model.id">
                  <span class="asset-shape model-shape"><i /></span><span class="choice-copy"><strong>{{ model.name }}</strong><small>{{ model.meta }}</small></span><span class="authorization">已授权</span><span class="select-mark">{{ selectedModel === model.id ? '✓' : '选择' }}</span>
                </button>
              </div>
            </div>

            <div v-else-if="activeStep === 2" class="step-view">
              <div class="editor-head"><p class="eyebrow">STEP 02</p><h2>定义创意方向</h2><span>描述想要的画面，也可以明确需要排除的内容。</span></div>
              <label class="form-label">画面描述 <small>至少 12 个字符</small><textarea v-model="creativePrompt" rows="5" placeholder="例如：极简内衣编辑大片，模特站在米灰色背景前，柔和侧光勾勒蕾丝纹理。" /></label>
              <label class="form-label">排除项 <small>可选</small><textarea v-model="negativePrompt" rows="3" placeholder="例如：过度磨皮、文字水印、复杂背景、手部变形" /></label>
              <div class="prompt-suggestions"><span>快速添加</span><button v-for="suggestion in ['高级时装杂志感', '自然肌理', '克制留白']" :key="suggestion" type="button" @click="creativePrompt = `${creativePrompt}${creativePrompt ? '，' : ''}${suggestion}`">＋ {{ suggestion }}</button></div>
            </div>

            <div v-else-if="activeStep === 3" class="step-view">
              <div class="editor-head"><p class="eyebrow">STEP 03</p><h2>设置生成参数</h2><span>为当前工作流设定稳定、可复用的输出规格。</span></div>
              <div class="form-grid">
                <label class="form-label">画幅比例<select v-model="aspectRatio"><option>4:5</option><option>1:1</option><option>16:9</option><option>9:16</option></select></label>
                <label class="form-label">镜头<select v-model="camera"><option>85mm 人像镜头</option><option>50mm 标准镜头</option><option>35mm 环境人像</option></select></label>
                <label class="form-label">光线<select v-model="lighting"><option>柔和侧光</option><option>正面漫射光</option><option>高对比轮廓光</option></select></label>
                <label class="form-label">生成数量<div class="stepper"><button type="button" aria-label="减少数量" :disabled="outputCount <= 1" @click="outputCount--">−</button><output>{{ outputCount }}</output><button type="button" aria-label="增加数量" :disabled="outputCount >= 12" @click="outputCount++">＋</button></div></label>
              </div>
              <div class="toggle-list"><label class="toggle-row"><span><strong>高清输出</strong><small>保留更多面料和皮肤纹理细节</small></span><input v-model="highDefinition" type="checkbox"><i /></label><label class="toggle-row"><span><strong>模特一致性</strong><small>在多张结果中保持脸部与体态稳定</small></span><input v-model="faceConsistency" type="checkbox"><i /></label></div>
            </div>

            <div v-else class="step-view review-view">
              <div class="editor-head"><p class="eyebrow">STEP 04</p><h2>确认并保存</h2><span>检查当前配置，保存后会生成一个新的不可覆盖版本。</span></div>
              <label class="form-label workflow-name">工作流名称<input v-model="workflowName" maxlength="50" placeholder="例如：NOIR 春夏主视觉" /></label>
              <div class="review-list"><div v-for="item in reviewItems" :key="item.label" class="review-row"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
              <div class="version-note"><span>版本策略</span><strong>保存为 IMAGE · V{{ versions.length + 1 }}</strong><small>旧版本会保留，任何更新都不会覆盖已有生成记录。</small></div>
            </div>

            <footer class="editor-footer"><button class="quiet-button" type="button" :disabled="activeStep === 1" @click="previousStep">上一步</button><span class="step-progress">{{ activeStep }} / 4</span><button class="dark-button" type="button" @click="nextStep">{{ activeStep === 4 ? '保存新版本' : '继续' }} <span>{{ activeStep === 4 ? '✓' : '→' }}</span></button></footer>
          </section>

          <aside class="version-panel"><p class="eyebrow">SAVED WORKFLOWS</p><h2>历史与复用</h2><div v-if="versions.length" class="version-list"><article v-for="version in versions" :key="version.id" class="version-card"><div><strong>{{ version.name }}</strong><small>{{ version.version }} · {{ version.savedAt }}</small></div><button type="button" @click="loadVersion(version)">编辑 <span>→</span></button></article></div><p v-else class="empty-version">还没有保存的工作流</p><button class="panel-link" type="button" @click="showToast('历史版本已全部展示')">查看全部版本 <span>→</span></button></aside>
        </div>
      </main>
    </section>

    <Transition name="toast"><div v-if="toast" class="toast" role="status">{{ toast }}</div></Transition>
  </div>
</template>

<style>
:root { --ink: #24221f; --muted: #7d776f; --line: #e7e1d8; --paper: #f7f5f0; --gold: #a18455; }
* { box-sizing: border-box; }
html, body, #__nuxt { min-height: 100%; margin: 0; }
body { background: var(--paper); color: var(--ink); font-family: Arial, Helvetica, sans-serif; }
button, input, textarea, select { font: inherit; }
button { cursor: pointer; }
button:disabled { cursor: not-allowed; opacity: .45; }
.workspace-layout { min-height: 100vh; display: grid; grid-template-columns: 230px minmax(0, 1fr); background: var(--paper); }
.sidebar { position: sticky; top: 0; z-index: 20; height: 100vh; display: flex; flex-direction: column; padding: 25px 18px; background: #fcfbf8; border-right: 1px solid var(--line); }
.brand { display: flex; align-items: center; gap: 11px; padding: 0 8px 24px; color: var(--ink); text-align: left; background: transparent; border: 0; }
.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border: 1px solid; border-radius: 50%; font: 18px Georgia, serif; }
.brand-copy { display: flex; flex-direction: column; gap: 3px; letter-spacing: .15em; }.brand-copy strong { font: 500 14px Georgia, serif; }.brand-copy small { color: #847e75; font-size: 7px; }
.space-card { display: flex; flex-direction: column; gap: 4px; margin-bottom: 18px; padding: 13px; background: #eee9e1; border-radius: 10px; }.space-card span, .space-card small { color: #8d867d; font-size: 8px; letter-spacing: .1em; text-transform: uppercase; }.space-card strong { font: 400 12px Georgia, serif; }
.nav-list { display: flex; flex-direction: column; gap: 4px; }.nav-label { margin: 0 0 5px; padding: 0 11px; color: #999188; font-size: 9px; font-weight: 700; letter-spacing: .16em; }.nav-item { display: flex; align-items: center; gap: 10px; width: 100%; padding: 10px 11px; color: #716c65; text-align: left; background: transparent; border: 0; border-radius: 8px; font-size: 12px; }.nav-item:hover, .nav-item.active { color: var(--ink); background: #eae5dd; }.nav-item.active { font-weight: 600; }.nav-icon { display: grid; place-items: center; width: 16px; color: #938b81; font-size: 14px; }.nav-item em { display: grid; place-items: center; width: 19px; height: 19px; margin-left: auto; color: #fff; background: #292722; border-radius: 50%; font-size: 9px; font-style: normal; }
.sidebar-bottom { margin-top: auto; }.tip-card { display: flex; flex-direction: column; gap: 7px; padding: 16px; background: #eee9e1; border-radius: 11px; }.tip-icon { display: grid; place-items: center; width: 24px; height: 24px; color: #fff; background: #292722; border-radius: 50%; font-size: 11px; }.tip-card strong { font: 400 14px Georgia, serif; }.tip-card p { margin: 0; color: #777169; font-size: 10px; line-height: 1.5; }.tip-card button, .profile button { padding: 0; color: #575149; text-align: left; background: transparent; border: 0; font-size: 10px; }.tip-card button { display: flex; justify-content: space-between; }.profile { display: flex; align-items: center; gap: 9px; margin-top: 16px; padding: 16px 5px 0; border-top: 1px solid var(--line); }.avatar { display: grid; place-items: center; width: 31px; height: 31px; color: #fff; background: #282622; border-radius: 50%; font-size: 9px; }.profile > span:nth-child(2) { display: flex; flex-direction: column; gap: 2px; }.profile strong { font-size: 10px; }.profile small { color: #8b857d; font-size: 8px; }.profile button { margin-left: auto; font-size: 14px; color: #8b857d; }
.main-area { min-width: 0; }.topbar { position: sticky; top: 0; z-index: 15; display: flex; align-items: center; justify-content: space-between; height: 68px; padding: 0 4%; background: #fcfbf8e6; border-bottom: 1px solid var(--line); backdrop-filter: blur(12px); }.project-switcher { display: flex; flex-direction: column; gap: 2px; }.project-switcher span { color: #8c867d; font-size: 8px; letter-spacing: .12em; text-transform: uppercase; }.project-switcher strong { font: 500 12px Georgia, serif; }.top-actions { display: flex; align-items: center; gap: 12px; }.service-state { display: flex; align-items: center; gap: 6px; color: #68635c; font-size: 9px; }.service-state i { width: 7px; height: 7px; background: #7f9b82; border-radius: 50%; box-shadow: 0 0 0 3px #e6ede6; }.icon-button { display: grid; place-items: center; width: 34px; height: 34px; color: #69635b; background: #fff; border: 1px solid var(--line); border-radius: 50%; font-size: 17px; }
.content { max-width: 1500px; margin: 0 auto; padding: 40px 4% 65px; }.heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 31px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.heading > div > span { color: #817b73; font-size: 12px; }.heading-actions { display: flex; gap: 9px; }
.dark-button, .quiet-button { display: flex; align-items: center; gap: 7px; min-height: 42px; padding: 10px 14px; border-radius: 8px; font-size: 10px; }.dark-button { color: #fff; background: #1d1c19; border: 0; }.quiet-button { color: #5d5750; background: #fff; border: 1px solid var(--line); }.dark-button:hover { background: #37342f; }.quiet-button:hover { border-color: #bfb6aa; }
.workflow-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr) 230px; align-items: start; gap: 15px; }.workflow-steps { display: flex; flex-direction: column; gap: 5px; }.step-button { display: flex; align-items: flex-start; gap: 10px; width: 100%; padding: 11px; color: #8a837a; text-align: left; background: transparent; border: 0; border-radius: 9px; }.step-button.clickable { color: #403c36; }.step-button.active { background: #eae5dd; }.step-number { display: grid; place-items: center; flex: 0 0 auto; width: 23px; height: 23px; border: 1px solid #cfc7bc; border-radius: 50%; color: #70695f; font-size: 10px; }.step-button.active .step-number { color: #fff; background: #292722; border-color: #292722; }.step-button.complete .step-number { color: #fff; background: #8a7659; border-color: #8a7659; }.step-button > span:last-child { display: flex; flex-direction: column; gap: 3px; padding-top: 1px; }.step-button strong { font-size: 11px; font-weight: 600; }.step-button small { color: #918a81; font-size: 8px; }
.editor-panel, .version-panel { background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.editor-panel { min-width: 0; padding: 26px 25px 22px; }.editor-head { border-bottom: 1px solid #eee9e2; padding-bottom: 20px; }.editor-head h2, .version-panel h2 { margin: 5px 0 6px; font: 400 25px Georgia, serif; }.editor-head > span { color: #8a847c; font-size: 10px; }.field-title { display: flex; align-items: center; gap: 8px; margin: 22px 0 10px; font-size: 10px; }.field-title small { color: #a49c91; font-size: 8px; font-weight: 400; }
.choice-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }.choice-card { position: relative; display: flex; align-items: center; gap: 12px; min-height: 79px; overflow: hidden; padding: 10px 11px; color: var(--ink); text-align: left; background: #fcfbf8; border: 1px solid #e5dfd6; border-radius: 9px; }.choice-card:hover, .choice-card.selected { border-color: #a18455; box-shadow: 0 0 0 2px #a1845522; }.choice-card.selected { background: #faf7f1; }.choice-copy { display: flex; flex-direction: column; gap: 5px; min-width: 0; }.choice-copy strong { overflow: hidden; font-size: 10px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.choice-copy small { color: #8f887f; font-size: 8px; }.select-mark { position: absolute; right: 10px; bottom: 9px; color: #9a9288; font-size: 8px; }.choice-card.selected .select-mark { color: #8a7659; font-weight: 700; }.authorization { position: absolute; top: 9px; right: 9px; padding: 3px 5px; color: #537059; background: #e6eee7; border-radius: 12px; font-size: 7px; }.asset-shape { position: relative; display: block; flex: 0 0 53px; height: 57px; overflow: hidden; border-radius: 7px; }.palette-cream { background: linear-gradient(135deg, #d8cfc3, #f5f0e9); }.palette-charcoal { background: linear-gradient(135deg, #302e2b, #827b73); }.palette-taupe { background: linear-gradient(135deg, #b6a99d, #e0d5c8); }.palette-stone { background: linear-gradient(135deg, #b7b6b1, #e5e1d8); }.garment-shape i, .model-shape i { position: absolute; inset: 8px 13px; background: #fff9; border-radius: 45% 45% 22% 22%; clip-path: polygon(0 0, 48% 24%, 100% 0, 81% 100%, 50% 72%, 19% 100%); }.palette-charcoal .garment-shape i { background: #292521d9; }.model-shape i { inset: 5px 17px 2px; background: #8b7d72; border-radius: 48% 48% 15% 15%; clip-path: polygon(28% 0, 72% 0, 87% 25%, 100% 100%, 0 100%, 13% 25%); }
.form-label { display: flex; flex-direction: column; gap: 8px; margin: 20px 0 0; color: #59554f; font-size: 10px; }.form-label small { color: #989087; font-size: 8px; font-weight: 400; }.form-label textarea, .form-label input, .form-label select { width: 100%; padding: 12px 13px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; resize: vertical; font-size: 11px; }.form-label textarea:focus, .form-label input:focus, .form-label select:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.form-label textarea::placeholder, .form-label input::placeholder { color: #aba39a; }.prompt-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-top: 13px; color: #9a9288; font-size: 8px; }.prompt-suggestions button { padding: 6px 8px; color: #756e66; background: #f3efe9; border: 0; border-radius: 14px; font-size: 8px; }.prompt-suggestions button:hover { background: #e9e2d8; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 15px; }.stepper { display: flex; align-items: center; justify-content: space-between; height: 41px; padding: 4px; background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; }.stepper button { display: grid; place-items: center; width: 30px; height: 30px; color: #5b554d; background: #f1ece5; border: 0; border-radius: 6px; }.stepper output { color: #282521; font: 16px Georgia, serif; }.toggle-list { margin-top: 22px; border-top: 1px solid #eee9e2; }.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 15px 0; border-bottom: 1px solid #eee9e2; }.toggle-row > span { display: flex; flex-direction: column; gap: 4px; }.toggle-row strong { font-size: 10px; font-weight: 600; }.toggle-row small { color: #938c83; font-size: 8px; }.toggle-row input { position: absolute; opacity: 0; pointer-events: none; }.toggle-row i { position: relative; display: block; flex: 0 0 auto; width: 35px; height: 20px; background: #d8d1c8; border-radius: 20px; transition: background .2s; }.toggle-row i::after { position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; background: #fff; border-radius: 50%; box-shadow: 0 1px 3px #0002; content: ''; transition: transform .2s; }.toggle-row input:checked + i { background: #807256; }.toggle-row input:checked + i::after { transform: translateX(15px); }
.review-view { min-height: 355px; }.workflow-name { max-width: 500px; }.review-list { margin-top: 25px; border-top: 1px solid #eee9e2; }.review-row { display: grid; grid-template-columns: 100px 1fr; gap: 15px; padding: 13px 0; border-bottom: 1px solid #eee9e2; }.review-row span { color: #958e85; font-size: 9px; }.review-row strong { overflow: hidden; color: #38342e; font-size: 10px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }.version-note { display: flex; flex-direction: column; gap: 5px; margin-top: 22px; padding: 14px; background: #f4efe8; border-radius: 8px; }.version-note span { color: #9a9187; font-size: 8px; }.version-note strong { font: 400 14px Georgia, serif; }.version-note small { color: #817970; font-size: 8px; }
.editor-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 25px; padding-top: 20px; border-top: 1px solid #eee9e2; }.step-progress { color: #a39b91; font: 11px Georgia, serif; }.version-panel { padding: 24px; }.version-panel h2 { margin-bottom: 16px; }.version-list { border-top: 1px solid #e5dfd6; }.version-card { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 14px 0; border-bottom: 1px solid #eee9e2; }.version-card > div { display: flex; flex-direction: column; gap: 5px; min-width: 0; }.version-card strong { overflow: hidden; font-size: 10px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.version-card small { color: #938b81; font-size: 8px; }.version-card button, .panel-link { flex: 0 0 auto; padding: 0; color: #6e665d; background: transparent; border: 0; font-size: 8px; }.version-card button:hover, .panel-link:hover { color: #a18455; }.panel-link { display: flex; justify-content: space-between; width: 100%; margin-top: 18px; padding-top: 15px; border-top: 1px solid #eee9e2; }.empty-version { color: #938b81; font-size: 9px; }
.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 1100px) { .workflow-layout { grid-template-columns: 165px minmax(0, 1fr); }.version-panel { grid-column: 2; }.editor-panel { grid-column: 2; grid-row: 1; } }
@media (max-width: 800px) { .workspace-layout { display: block; }.sidebar { position: static; flex-direction: row; align-items: center; height: auto; padding: 13px 16px; }.brand { padding: 0; }.space-card, .nav-list, .tip-card, .profile > span:nth-child(2), .profile button { display: none; }.profile { margin: 0 0 0 auto; padding: 0; border: 0; }.topbar { height: 58px; padding: 0 18px; }.service-state, .icon-button { display: none; }.content { padding: 30px 16px 55px; }.heading { align-items: flex-start; flex-direction: column; }.heading-actions { width: 100%; }.heading-actions button { flex: 1; justify-content: center; }.workflow-layout { display: flex; flex-direction: column; gap: 14px; }.workflow-steps { display: grid; grid-template-columns: repeat(4, 1fr); width: 100%; }.step-button { flex-direction: column; align-items: center; gap: 6px; padding: 9px 4px; text-align: center; }.step-button > span:last-child { align-items: center; }.step-button small { display: none; }.editor-panel, .version-panel { width: 100%; }.version-panel { order: 3; }.choice-grid { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .content { padding-inline: 13px; }.heading h1 { font-size: 36px; }.editor-panel, .version-panel { padding: 20px 16px; }.form-grid { grid-template-columns: 1fr; }.review-row { grid-template-columns: 80px 1fr; }.workflow-steps { gap: 2px; }.step-button strong { font-size: 9px; }.editor-footer .quiet-button, .editor-footer .dark-button { padding-inline: 10px; } }
</style>
