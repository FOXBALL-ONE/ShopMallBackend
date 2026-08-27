<script setup lang="ts">
import { computed, ref, watch } from 'vue'

definePageMeta({ layout: false })

type StepId = 1 | 2 | 3 | 4
type WorkflowVersion = {
  id: number
  name: string
  version: string
  savedAt: string
  garment: string
  model: string
  definition: WorkflowDefinition
}
type WorkflowReference = { assetId: number | null; role: string; instruction: string }
type WorkflowDefinition = { garmentAssetId: number; modelAssetId: number; creativePrompt: string; negativePrompt: string; aspectRatio: string; camera: string; lighting: string; outputCount: number; highDefinition: boolean; faceConsistency: boolean; referenceImages: WorkflowReference[] }
type WorkflowResponse = { id: number; name: string; version: number; versionLabel: string; savedAt: string; createdAt: string; definition: WorkflowDefinition }
type LibraryAsset = { id: number; type: 'GARMENT' | 'MODEL' | 'REFERENCE'; name: string; code: string; description: string; authorizationStatus: string | null; file: {downloadUrl: string; contentType: string} }
type MaterialContext = { typeLabel: string; rolePlaceholder: string; roleHint: string; instructionPlaceholder: string; instructionHint: string; status: string }

const route = useRoute()
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const { activeProjectId } = useProjectWorkspace(projectId)
const activeStep = ref<StepId>(1)
const furthestStep = ref<StepId>(1)
const toast = ref('')
const workflowName = ref('NOIR 春夏主视觉')
const selectedGarment = ref('')
const selectedModel = ref('')
const materialInputs = ref<WorkflowReference[]>([])
const creativePrompt = ref('')
const negativePrompt = ref('')
const aspectRatio = ref('4:5')
const camera = ref('85mm 人像镜头')
const lighting = ref('柔和侧光')
const outputCount = ref(4)
const highDefinition = ref(true)
const faceConsistency = ref(true)
const loadingWorkflows = ref(true)
const savingWorkflow = ref(false)
const readingWorkflowId = ref<number | null>(null)
const workflowError = ref('')
const historyOpen = ref(false)
const lastSavedVersion = ref<WorkflowResponse | null>(null)
const draftTouched = ref(false)
let suppressDraftTracking = false

const steps: { id: StepId; title: string; hint: string }[] = [
  { id: 1, title: '素材组合', hint: '列表自由配置素材' },
  { id: 2, title: '创意提示', hint: '描述画面与排除项' },
  { id: 3, title: '生成参数', hint: '画幅、镜头与光线' },
  { id: 4, title: '确认保存', hint: '检查并形成版本' },
]

const libraryAssets = ref<LibraryAsset[]>([])
const garments = computed(() => libraryAssets.value.filter((asset) => asset.type === 'GARMENT').map((asset) => ({id: String(asset.id), name: asset.name, meta: `${asset.code} · ${asset.description}`, palette: 'cream', imageUrl: asset.file.downloadUrl, contentType: asset.file.contentType})))
const models = computed(() => libraryAssets.value.filter((asset) => asset.type === 'MODEL' && asset.authorizationStatus === '已确认授权').map((asset) => ({id: String(asset.id), name: asset.name, meta: `${asset.code} · ${asset.description}`, palette: 'taupe', imageUrl: asset.file.downloadUrl, contentType: asset.file.contentType})))
const imageAssets = computed(() => libraryAssets.value.filter((asset) => ['image/png', 'image/jpeg', 'image/webp'].includes(asset.file.contentType)))

const versions = ref<WorkflowVersion[]>([])
let workspaceLoadVersion = 0

const selectedGarmentData = computed(() => garments.value.find((item) => item.id === selectedGarment.value))
const selectedModelData = computed(() => models.value.find((item) => item.id === selectedModel.value))
const materialRows = computed(() => materialInputs.value.map((reference, index) => {
  const asset = imageAssets.value.find((item) => item.id === reference.assetId)
  let context: MaterialContext = {typeLabel: '待选择素材', rolePlaceholder: '例如 garment、model、scene', roleHint: '选择素材后会根据素材类型给出填写建议。', instructionPlaceholder: '选择素材后填写这张图需要保留或强调的内容。', instructionHint: '最多 500 个字符；该要求会与对应素材一并发送给模型。', status: '尚未选择素材'}
  if (asset?.type === 'GARMENT') context = {typeLabel: '服装素材', rolePlaceholder: '例如 garment、texture', roleHint: '建议使用 garment、texture 等角色，强调材质、颜色或剪裁。', instructionPlaceholder: '例如：保留面料纹理、颜色和剪裁，不改变服装结构。', instructionHint: '描述需要保持的服装细节，最多 500 个字符。', status: '可作为工作流服装主素材'}
  if (asset?.type === 'MODEL') context = {typeLabel: '模特素材', rolePlaceholder: '例如 model、pose', roleHint: '建议说明人物、姿势或脸部的一致性要求。', instructionPlaceholder: '例如：保持脸部、体态和站姿稳定。', instructionHint: asset.authorizationStatus === '已确认授权' ? '描述人物一致性要求，最多 500 个字符。' : `当前授权状态：${asset.authorizationStatus || '未确认'}；确认授权后才能作为主模特。`, status: asset.authorizationStatus === '已确认授权' ? '已确认授权，可作为工作流模特主素材' : `授权状态：${asset.authorizationStatus || '未确认'}`}
  if (asset?.type === 'REFERENCE') context = {typeLabel: '通用参考素材', rolePlaceholder: '例如 scene、pose、lighting', roleHint: '可用于场景、姿势、光线或氛围参考。', instructionPlaceholder: '例如：参考背景构图和光线方向，但不要复制文字。', instructionHint: '描述场景、姿势、光线或氛围需要参考的内容，最多 500 个字符。', status: '可作为场景、姿势或氛围参考'}
  return {reference, index, asset, context}
}))
const hasValidMaterials = computed(() => materialInputs.value.length >= 2
  && materialInputs.value.length <= 8
  && new Set(materialInputs.value.map((reference) => reference.assetId)).size === materialInputs.value.length
  && materialInputs.value.every((reference) => Number.isSafeInteger(reference.assetId) && reference.assetId! > 0 && imageAssets.value.some((asset) => asset.id === reference.assetId) && reference.role.trim().length > 0 && reference.role.trim().length <= 40 && reference.instruction.trim().length <= 500))
const isStepOneValid = computed(() => Boolean(selectedGarmentData.value && selectedModelData.value && hasValidMaterials.value))
const isStepTwoValid = computed(() => creativePrompt.value.trim().length >= 12)
const isStepThreeValid = computed(() => Boolean(aspectRatio.value && camera.value && lighting.value && outputCount.value > 0))
const canContinue = computed(() => activeStep.value === 1 ? isStepOneValid.value : activeStep.value === 2 ? isStepTwoValid.value : activeStep.value === 3 ? isStepThreeValid.value : true)
const draftStatus = computed(() => savingWorkflow.value ? '正在保存' : lastSavedVersion.value && !draftTouched.value ? `已保存 ${lastSavedVersion.value.versionLabel}` : draftTouched.value ? '有未保存修改' : '准备创建版本')
const reviewItems = computed(() => [
  { label: '服装', value: selectedGarmentData.value?.name || '尚未选择' },
  { label: '模特', value: selectedModelData.value?.name || '尚未选择' },
  { label: '画面描述', value: creativePrompt.value || '尚未填写' },
  { label: '生成设置', value: `${aspectRatio.value} · ${camera.value} · ${lighting.value} · ${outputCount.value} 张` },
  { label: '输出选项', value: `${highDefinition.value ? '高清' : '标准'} · ${faceConsistency.value ? '保持模特一致性' : '不保持模特一致性'}` },
  { label: '素材组合', value: `${materialInputs.value.length} 项 · ${materialInputs.value.map((reference) => imageAssets.value.find((asset) => asset.id === reference.assetId)?.name || '未选择').join('、') || '未配置'}` },
])

function showToast(message: string) {
  if (!import.meta.client) return
  toast.value = message
  window.setTimeout(() => {
    if (toast.value === message) toast.value = ''
  }, 2400)
}

function syncSelectedAssets() {
  const selectedAssets = materialInputs.value
    .map((reference) => imageAssets.value.find((asset) => asset.id === reference.assetId))
    .filter((asset): asset is LibraryAsset => Boolean(asset))
  selectedGarment.value = String(selectedAssets.find((asset) => asset.type === 'GARMENT')?.id || '')
  selectedModel.value = String(selectedAssets.find((asset) => asset.type === 'MODEL' && asset.authorizationStatus === '已确认授权')?.id || '')
}

function addReference() {
  if (materialInputs.value.length >= 8) return
  const selectedIds = new Set(materialInputs.value.map((reference) => reference.assetId))
  const asset = imageAssets.value.find((item) => !selectedIds.has(item.id))
  materialInputs.value.push({assetId: asset?.id ?? null, role: 'reference', instruction: ''})
}

function removeReference(index: number) {
  materialInputs.value.splice(index, 1)
  syncSelectedAssets()
}

function seedMaterialInputs() {
  const defaults: WorkflowReference[] = []
  const garment = imageAssets.value.find((asset) => asset.type === 'GARMENT')
  const model = imageAssets.value.find((asset) => asset.type === 'MODEL' && asset.authorizationStatus === '已确认授权')
  if (garment) defaults.push({assetId: garment.id, role: 'garment', instruction: '严格保留服装颜色、材质和剪裁。'})
  if (model) defaults.push({assetId: model.id, role: 'model', instruction: '参考人物脸部、体态和站姿。'})
  materialInputs.value = defaults
  syncSelectedAssets()
}

function markDraftTouched() {
  if (!savingWorkflow.value && !suppressDraftTracking) draftTouched.value = true
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

function firstInvalidStep(): StepId | null {
  if (!isStepOneValid.value) return 1
  if (!isStepTwoValid.value) return 2
  if (!isStepThreeValid.value) return 3
  if (!workflowName.value.trim()) return 4
  return null
}

function openSaveReview() {
  const invalidStep = firstInvalidStep()
  if (invalidStep) {
    activeStep.value = invalidStep
    if (invalidStep < 4) furthestStep.value = Math.max(furthestStep.value, invalidStep) as StepId
    showToast(invalidStep === 1 ? (!selectedGarmentData.value || !selectedModelData.value ? '请在素材组合列表中添加一件服装和一位已授权模特' : '请完善素材组合列表中的素材、角色和对应文字要求') : invalidStep === 2 ? '画面描述至少需要 12 个字符' : invalidStep === 3 ? '请完整设置画幅、镜头、光线和生成数量' : '请输入工作流名称')
    return
  }
  activeStep.value = 4
  furthestStep.value = 4
}

function validateCurrentStep() {
  if (activeStep.value === 1 && !isStepOneValid.value) {
    showToast(!selectedGarmentData.value || !selectedModelData.value ? '请在素材组合列表中添加一件服装和一位已授权模特' : '请完善素材组合列表中的素材、角色和对应文字要求')
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

function workflowToVersion(workflow: WorkflowResponse): WorkflowVersion {
  const garment = garments.value.find((item) => item.id === String(workflow.definition.garmentAssetId))
  const model = models.value.find((item) => item.id === String(workflow.definition.modelAssetId))
  return {id: workflow.id, name: workflow.name, version: workflow.versionLabel, savedAt: workflow.savedAt, garment: garment?.name || '未选择', model: model?.name || '未选择', definition: workflow.definition}
}

async function saveVersion() {
  const invalidStep = firstInvalidStep()
  if (invalidStep) {
    activeStep.value = invalidStep
    showToast(invalidStep === 1 ? (!selectedGarmentData.value || !selectedModelData.value ? '请在素材组合列表中添加一件服装和一位已授权模特' : '请完善素材组合列表中的素材、角色和对应文字要求') : invalidStep === 2 ? '画面描述至少需要 12 个字符' : invalidStep === 3 ? '请完整设置画幅、镜头、光线和生成数量' : '请输入工作流名称')
    return
  }
  savingWorkflow.value = true
  const requestProjectId = activeProjectId.value
  try {
    const response = await $fetch<{workflow: WorkflowResponse}>(`/api/projects/${encodeURIComponent(requestProjectId)}/workflows`, {
      method: 'POST',
      body: {
        name: workflowName.value,
        garment_asset_id: selectedGarment.value,
        model_asset_id: selectedModel.value,
        creative_prompt: creativePrompt.value,
        negative_prompt: negativePrompt.value,
        aspect_ratio: aspectRatio.value,
        camera: camera.value,
        lighting: lighting.value,
        output_count: outputCount.value,
        high_definition: highDefinition.value,
        face_consistency: faceConsistency.value,
        reference_images: materialInputs.value.map((reference) => ({asset_id: reference.assetId, role: reference.role.trim(), instruction: reference.instruction.trim()})),
      },
    })
    if (activeProjectId.value !== requestProjectId) return
    versions.value.unshift(workflowToVersion(response.workflow))
    lastSavedVersion.value = response.workflow
    draftTouched.value = false
    activeStep.value = 1
    furthestStep.value = 1
    showToast(`已保存“${response.workflow.name}”的 ${response.workflow.versionLabel} 版本`)
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    if (activeProjectId.value === requestProjectId) showToast(requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '工作流保存失败，请重试')
  } finally {
    savingWorkflow.value = false
  }
}

async function loadVersion(version: WorkflowVersion) {
  if (readingWorkflowId.value) return
  readingWorkflowId.value = version.id
  const requestProjectId = activeProjectId.value
  suppressDraftTracking = true
  try {
    const response = await $fetch<{workflow: WorkflowResponse}>(`/api/projects/${encodeURIComponent(requestProjectId)}/workflows/${version.id}`)
    if (activeProjectId.value !== requestProjectId) return
    const loaded = response.workflow
    const garment = garments.value.find((item) => item.id === String(loaded.definition.garmentAssetId))
    const model = models.value.find((item) => item.id === String(loaded.definition.modelAssetId))
    workflowName.value = loaded.name
    selectedGarment.value = garment?.id || ''
    selectedModel.value = model?.id || ''
    creativePrompt.value = loaded.definition.creativePrompt
    negativePrompt.value = loaded.definition.negativePrompt
    aspectRatio.value = loaded.definition.aspectRatio
    camera.value = loaded.definition.camera
    lighting.value = loaded.definition.lighting
    outputCount.value = loaded.definition.outputCount
    highDefinition.value = loaded.definition.highDefinition
    faceConsistency.value = loaded.definition.faceConsistency
    materialInputs.value = (loaded.definition.referenceImages || []).map((reference) => ({assetId: reference.assetId, role: reference.role, instruction: reference.instruction}))
    if (!materialInputs.value.length) {
      if (loaded.definition.garmentAssetId) materialInputs.value.push({assetId: loaded.definition.garmentAssetId, role: 'garment', instruction: '严格保留服装颜色、材质和剪裁。'})
      if (loaded.definition.modelAssetId) materialInputs.value.push({assetId: loaded.definition.modelAssetId, role: 'model', instruction: '参考人物脸部、体态和站姿。'})
    }
    syncSelectedAssets()
    lastSavedVersion.value = loaded
    draftTouched.value = false
    activeStep.value = 1
    furthestStep.value = 4
    showToast(`已载入“${loaded.name}”，可以继续编辑`)
  } catch (error: unknown) {
    const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
    if (activeProjectId.value === requestProjectId) showToast(requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '工作流读取失败，请重试')
  } finally {
    suppressDraftTracking = false
    readingWorkflowId.value = null
  }
}

function startNewDraft() {
  if (draftTouched.value && !window.confirm('当前草稿有未保存修改，确定放弃并新建吗？')) return
  resetWorkflowDraft()
  lastSavedVersion.value = null
  draftTouched.value = false
  showToast('已新建工作流草稿')
}

function goToGeneration() {
  navigateTo(`/projects/${encodeURIComponent(activeProjectId.value)}/generate`)
}

function resetWorkflowDraft() {
  workflowName.value = '新建工作流'
  selectedGarment.value = ''
  selectedModel.value = ''
  creativePrompt.value = ''
  negativePrompt.value = ''
  aspectRatio.value = '4:5'
  camera.value = '85mm 人像镜头'
  lighting.value = '柔和侧光'
  outputCount.value = 4
  highDefinition.value = true
  faceConsistency.value = true
  materialInputs.value = []
  activeStep.value = 1
  furthestStep.value = 1
  lastSavedVersion.value = null
  draftTouched.value = false
}

const requestFetch = import.meta.server ? useRequestFetch() : $fetch
const initialLoadVersion = ++workspaceLoadVersion
const initialLoadProjectId = activeProjectId.value
try {
  const [assetResponse, workflowResponse] = await Promise.all([
    requestFetch<{assets: LibraryAsset[]}>(`/api/projects/${encodeURIComponent(initialLoadProjectId)}/assets`),
    requestFetch<{workflows: WorkflowResponse[]}>(`/api/projects/${encodeURIComponent(initialLoadProjectId)}/workflows`),
  ])
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) {
    libraryAssets.value = assetResponse.assets
    versions.value = workflowResponse.workflows.map(workflowToVersion)
    selectedGarment.value = garments.value[0]?.id ?? ''
    selectedModel.value = models.value[0]?.id ?? ''
    seedMaterialInputs()
  }
} catch (error: unknown) {
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) {
    workflowError.value = '工作流或素材库暂时无法加载，请重试。'
    showToast(workflowError.value)
  }
} finally {
  if (initialLoadVersion === workspaceLoadVersion && activeProjectId.value === initialLoadProjectId) loadingWorkflows.value = false
}

watch(activeProjectId, (nextProjectId, previousProjectId) => {
  if (!previousProjectId || nextProjectId === previousProjectId) return
  const requestVersion = ++workspaceLoadVersion
  const requestProjectId = nextProjectId
  suppressDraftTracking = true
  resetWorkflowDraft()
  workflowError.value = ''
  loadingWorkflows.value = true
  void (async () => {
    try {
      const [assetResponse, workflowResponse] = await Promise.all([
        $fetch<{assets: LibraryAsset[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/assets`),
        $fetch<{workflows: WorkflowResponse[]}>(`/api/projects/${encodeURIComponent(requestProjectId)}/workflows`),
      ])
      if (requestVersion !== workspaceLoadVersion || activeProjectId.value !== requestProjectId) return
      libraryAssets.value = assetResponse.assets
      versions.value = workflowResponse.workflows.map(workflowToVersion)
      selectedGarment.value = garments.value[0]?.id ?? ''
      selectedModel.value = models.value[0]?.id ?? ''
      seedMaterialInputs()
    } catch (error: unknown) {
      if (requestVersion !== workspaceLoadVersion || activeProjectId.value !== requestProjectId) return
      const requestError = error as {data?: {statusMessage?: string; message?: string}; statusMessage?: string; message?: string}
      workflowError.value = requestError.data?.statusMessage ?? requestError.data?.message ?? requestError.statusMessage ?? requestError.message ?? '工作流或素材库暂时无法加载，请重试。'
    } finally {
      if (requestVersion === workspaceLoadVersion) suppressDraftTracking = false
      if (requestVersion === workspaceLoadVersion && activeProjectId.value === requestProjectId) loadingWorkflows.value = false
    }
  })()
})

watch(materialInputs, syncSelectedAssets, {deep: true, flush: 'sync'})
watch([workflowName, selectedGarment, selectedModel, creativePrompt, negativePrompt, aspectRatio, camera, lighting, outputCount, highDefinition, faceConsistency, materialInputs], markDraftTouched, {deep: true, flush: 'sync'})
</script>

<template>
  <div class="workspace-layout">
    <StudioSidebar :project-id="activeProjectId" />

    <section class="main-area">
      <StudioTopbar :project-id="activeProjectId">
        <span class="service-state"><i /> 生成服务由平台安全代理</span>
        <button class="icon-button" type="button" aria-label="通知" @click="showToast('暂无新的通知')">⌁</button>
      </StudioTopbar>

      <main class="content">
        <section class="heading">
          <div><p class="eyebrow">WORKFLOW BUILDER</p><h1>创建生成工作流</h1><span>每次保存都会形成可追溯、不可覆盖的版本。</span></div>
          <div class="heading-actions"><span class="draft-status" :class="{ dirty: draftTouched, saved: lastSavedVersion && !draftTouched }"><i />{{ draftStatus }}</span><button v-if="lastSavedVersion && !draftTouched" class="quiet-button" type="button" @click="goToGeneration">去提交生成</button><button class="quiet-button" type="button" :disabled="savingWorkflow || loadingWorkflows" @click="startNewDraft">新建草稿</button><button class="dark-button" type="button" :disabled="savingWorkflow || loadingWorkflows" @click="openSaveReview">{{ savingWorkflow ? '正在保存…' : '提交工作流版本' }}</button></div>
        </section>
        <p v-if="workflowError" class="load-error" role="alert">{{ workflowError }}</p>

        <div class="workflow-layout">
          <aside class="workflow-steps" aria-label="工作流步骤">
            <button v-for="step in steps" :key="step.id" type="button" class="step-button" :class="[stepState(step.id), { clickable: step.id <= furthestStep }]" :aria-current="activeStep === step.id ? 'step' : undefined" @click="goToStep(step.id)">
              <span class="step-number"><b v-if="step.id < activeStep || step.id < furthestStep">✓</b><template v-else>{{ step.id }}</template></span>
              <span><strong>{{ step.title }}</strong><small>{{ step.hint }}</small></span>
            </button>
          </aside>

          <section class="editor-panel">
            <div v-if="activeStep === 1" class="step-view">
              <div class="editor-head"><p class="eyebrow">STEP 01</p><h2>配置素材组合</h2><span>每个列表项都可以自由选择图片素材，并填写对应角色和文字要求。</span></div>
              <section class="material-section" aria-label="素材组合列表">
                <div class="material-heading"><div><strong>素材组合列表</strong><span>至少包含一件服装和一位已确认授权的模特，最多配置 8 项。</span></div><button type="button" :disabled="materialInputs.length >= 8 || !imageAssets.length" @click="addReference">添加素材</button></div>
                <div v-if="materialInputs.length" class="material-list">
                  <article v-for="row in materialRows" :key="`${row.index}-${row.reference.assetId}`" class="material-item">
                    <div class="material-item-head"><strong>素材 {{ row.index + 1 }}</strong><span>{{ row.context.typeLabel }}</span><button type="button" :aria-label="`移除素材 ${row.index + 1}`" @click="removeReference(row.index)">移除</button></div>
                    <label class="form-label">选择素材<select v-model="row.reference.assetId"><option :value="null">请选择图片素材</option><option v-for="asset in imageAssets" :key="asset.id" :value="asset.id">{{ asset.name }} · {{ asset.code }}</option></select><small>仅支持 PNG、JPEG 或 WebP 图片，且不能重复选择。</small></label>
                    <div class="material-preview" :class="{ empty: !row.asset }">
                      <div class="material-preview-image"><img v-if="row.asset" :src="row.asset.file.downloadUrl" :alt="row.asset.name" loading="lazy"><span v-else>选择素材后预览</span></div>
                      <div class="material-preview-copy"><strong>{{ row.asset?.name || '尚未选择素材' }}</strong><small>{{ row.asset ? `${row.context.typeLabel} · ${row.asset.code}` : row.context.status }}</small><span>{{ row.asset?.description || row.context.status }}</span></div>
                    </div>
                    <label class="form-label">参考角色 <small>{{ row.context.roleHint }}</small><input v-model="row.reference.role" type="text" maxlength="40" :placeholder="row.context.rolePlaceholder"><small>请输入 1-40 个字符，用于说明该图片的参考角色。</small></label>
                    <label class="form-label">对应文字要求 <small>{{ row.context.instructionHint }}</small><textarea v-model="row.reference.instruction" maxlength="500" rows="3" :placeholder="row.context.instructionPlaceholder" /><small>最多 500 个字符；该要求会与对应参考图一并发送给模型。</small></label>
                  </article>
                </div>
                <p v-else class="empty-version">当前还没有素材组合，请点击“添加素材”开始配置。</p>
                <p v-if="materialInputs.length >= 8" class="form-hint">每个工作流最多支持 8 项素材组合。</p>
                <p v-else-if="materialInputs.length && !hasValidMaterials" class="load-error reference-error">请为每项选择不同的图片素材，并填写 1-40 个字符的参考角色；文字要求最多 500 个字符。</p>
                <p v-else-if="!imageAssets.length" class="load-error reference-error">当前项目没有可用图片素材，请先到素材库上传图片。</p>
              </section>
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
              <label class="form-label workflow-name">工作流名称 <small>必填，最多 120 个字符</small><input v-model="workflowName" maxlength="120" placeholder="例如：NOIR 春夏主视觉" /><small>保存后会创建新的不可覆盖版本，旧版本和已生成结果不会被修改。</small></label>
              <div class="review-list"><div v-for="item in reviewItems" :key="item.label" class="review-row"><span>{{ item.label }}</span><strong>{{ item.value }}</strong></div></div>
              <div class="version-note"><span>版本策略</span><strong>保存为 IMAGE · V{{ versions.length + 1 }}</strong><small>旧版本会保留，任何更新都不会覆盖已有生成记录。</small><div class="generation-handoff"><span>保存后可在生成任务页选择提供商、模型和批次数量，参考图要求会随本版本自动带入。</span><button type="button" @click="goToGeneration">前往生成任务</button></div></div>
            </div>

            <footer class="editor-footer"><button class="quiet-button" type="button" :disabled="activeStep === 1" @click="previousStep">上一步</button><span class="step-progress">{{ activeStep }} / 4</span><button class="dark-button" type="button" :disabled="savingWorkflow || loadingWorkflows" @click="activeStep === 4 ? saveVersion() : nextStep()">{{ activeStep === 4 ? (savingWorkflow ? '正在保存…' : '确认并提交') : '继续' }} <span>{{ activeStep === 4 ? '✓' : '→' }}</span></button></footer>
          </section>

          <aside class="version-panel"><p class="eyebrow">SAVED WORKFLOWS</p><h2>历史与复用</h2><p v-if="loadingWorkflows" class="empty-version">正在读取已保存工作流…</p><div v-else-if="versions.length" class="version-list"><article v-for="version in versions" :key="version.id" class="version-card"><div><strong>{{ version.name }}</strong><small>{{ version.version }} · {{ version.savedAt }}</small></div><button type="button" :disabled="readingWorkflowId !== null" @click="loadVersion(version)">{{ readingWorkflowId === version.id ? '读取中…' : '读取' }} <span>→</span></button></article></div><p v-else class="empty-version">还没有保存的工作流</p><button class="panel-link" type="button" :disabled="loadingWorkflows" @click="historyOpen = true">查看全部版本 <span>→</span></button></aside>
        </div>
      </main>
    </section>

    <div v-if="historyOpen" class="modal-backdrop" @click.self="historyOpen = false">
      <section class="history-modal" role="dialog" aria-modal="true" aria-labelledby="history-modal-title">
        <header class="history-modal-head">
          <div><p class="eyebrow">WORKFLOW HISTORY</p><h2 id="history-modal-title">历史工作流</h2><span>查看每个不可覆盖版本的配置，并载入任意版本继续编辑。</span></div>
          <button class="close-button" type="button" aria-label="关闭历史工作流" @click="historyOpen = false">×</button>
        </header>

        <p v-if="!versions.length" class="history-empty">当前项目还没有保存的工作流版本。</p>
        <div v-else class="history-list">
          <article v-for="version in versions" :key="version.id" class="history-record">
            <div class="history-record-head">
              <div><strong>{{ version.name }}</strong><small>{{ version.version }} · 保存于 {{ version.savedAt }}</small></div>
              <button class="history-load-button" type="button" :disabled="readingWorkflowId !== null" @click="historyOpen = false; loadVersion(version)">{{ readingWorkflowId === version.id ? '读取中…' : '读取并编辑' }} <span>→</span></button>
            </div>
            <dl class="history-meta">
              <div><dt>服装</dt><dd>{{ version.garment }}</dd></div>
              <div><dt>模特</dt><dd>{{ version.model }}</dd></div>
              <div><dt>画幅比例</dt><dd>{{ version.definition.aspectRatio }}</dd></div>
              <div><dt>镜头 / 光线</dt><dd>{{ version.definition.camera }} · {{ version.definition.lighting }}</dd></div>
              <div><dt>生成数量</dt><dd>{{ version.definition.outputCount }} 张</dd></div>
              <div><dt>输出选项</dt><dd>{{ version.definition.highDefinition ? '高清输出' : '标准输出' }} · {{ version.definition.faceConsistency ? '保持模特一致性' : '不保持模特一致性' }}</dd></div>
            </dl>
            <div class="history-prompt"><span>画面描述</span><p>{{ version.definition.creativePrompt || '未填写' }}</p></div>
            <div v-if="version.definition.negativePrompt" class="history-prompt"><span>排除项</span><p>{{ version.definition.negativePrompt }}</p></div>
            <div v-if="version.definition.referenceImages?.length" class="history-prompt"><span>参考图要求</span><p v-for="reference in version.definition.referenceImages" :key="`${reference.assetId}-${reference.role}`">{{ reference.role }} · {{ reference.instruction || '未填写文字要求' }}</p></div>
          </article>
        </div>
      </section>
    </div>

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
.content { max-width: 1500px; margin: 0 auto; padding: 40px 4% 65px; }.heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; margin-bottom: 31px; }.eyebrow { margin: 0; color: #8c867d; font-size: 10px; font-weight: 700; letter-spacing: .17em; text-transform: uppercase; }.heading h1 { margin: 7px 0 8px; font: 400 clamp(32px, 4vw, 48px) Georgia, serif; letter-spacing: -.035em; }.heading > div > span { color: #817b73; font-size: 12px; }.heading-actions { display: flex; align-items: center; gap: 9px; }.draft-status { display: inline-flex; align-items: center; gap: 6px; margin-right: 3px; color: #8c857d; font-size: 9px; white-space: nowrap; }.draft-status i { width: 6px; height: 6px; background: #b4aca1; border-radius: 50%; }.draft-status.dirty { color: #8a6947; }.draft-status.dirty i { background: #b38351; }.draft-status.saved { color: #537059; }.draft-status.saved i { background: #78907a; }
.dark-button, .quiet-button { display: flex; align-items: center; gap: 7px; min-height: 42px; padding: 10px 14px; border-radius: 8px; font-size: 10px; }.dark-button { color: #fff; background: #1d1c19; border: 0; }.quiet-button { color: #5d5750; background: #fff; border: 1px solid var(--line); }.dark-button:hover { background: #37342f; }.quiet-button:hover { border-color: #bfb6aa; }
.workflow-layout { display: grid; grid-template-columns: 190px minmax(0, 1fr) 230px; align-items: start; gap: 15px; }.workflow-steps { display: flex; flex-direction: column; gap: 5px; }.step-button { display: flex; align-items: flex-start; gap: 10px; width: 100%; padding: 11px; color: #8a837a; text-align: left; background: transparent; border: 0; border-radius: 9px; }.step-button.clickable { color: #403c36; }.step-button.active { background: #eae5dd; }.step-number { display: grid; place-items: center; flex: 0 0 auto; width: 23px; height: 23px; border: 1px solid #cfc7bc; border-radius: 50%; color: #70695f; font-size: 10px; }.step-button.active .step-number { color: #fff; background: #292722; border-color: #292722; }.step-button.complete .step-number { color: #fff; background: #8a7659; border-color: #8a7659; }.step-button > span:last-child { display: flex; flex-direction: column; gap: 3px; padding-top: 1px; }.step-button strong { font-size: 11px; font-weight: 600; }.step-button small { color: #918a81; font-size: 8px; }
.editor-panel, .version-panel { background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; }.editor-panel { min-width: 0; padding: 26px 25px 22px; }.editor-head { border-bottom: 1px solid #eee9e2; padding-bottom: 20px; }.editor-head h2, .version-panel h2 { margin: 5px 0 6px; font: 400 25px Georgia, serif; }.editor-head > span { color: #8a847c; font-size: 10px; }.field-title { display: flex; align-items: center; gap: 8px; margin: 22px 0 10px; font-size: 10px; }.field-title small { color: #a49c91; font-size: 8px; font-weight: 400; }
.choice-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 9px; }.choice-card { position: relative; display: flex; align-items: center; gap: 12px; min-height: 79px; overflow: hidden; padding: 10px 11px; color: var(--ink); text-align: left; background: #fcfbf8; border: 1px solid #e5dfd6; border-radius: 9px; }.choice-card:hover, .choice-card.selected { border-color: #a18455; box-shadow: 0 0 0 2px #a1845522; }.choice-card.selected { background: #faf7f1; }.choice-copy { display: flex; flex-direction: column; gap: 5px; min-width: 0; }.choice-copy strong { overflow: hidden; font-size: 10px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.choice-copy small { color: #8f887f; font-size: 8px; }.select-mark { position: absolute; right: 10px; bottom: 9px; color: #9a9288; font-size: 8px; }.choice-card.selected .select-mark { color: #8a7659; font-weight: 700; }.authorization { position: absolute; top: 9px; right: 9px; padding: 3px 5px; color: #537059; background: #e6eee7; border-radius: 12px; font-size: 7px; }.asset-shape { position: relative; display: block; flex: 0 0 53px; height: 57px; overflow: hidden; border-radius: 7px; }.asset-shape img { display: block; width: 100%; height: 100%; object-fit: cover; }.palette-cream { background: linear-gradient(135deg, #d8cfc3, #f5f0e9); }.palette-charcoal { background: linear-gradient(135deg, #302e2b, #827b73); }.palette-taupe { background: linear-gradient(135deg, #b6a99d, #e0d5c8); }.palette-stone { background: linear-gradient(135deg, #b7b6b1, #e5e1d8); }.garment-shape i, .model-shape i { position: absolute; inset: 8px 13px; background: #fff9; border-radius: 45% 45% 22% 22%; clip-path: polygon(0 0, 48% 24%, 100% 0, 81% 100%, 50% 72%, 19% 100%); }.palette-charcoal .garment-shape i { background: #292521d9; }.model-shape i { inset: 5px 17px 2px; background: #8b7d72; border-radius: 48% 48% 15% 15%; clip-path: polygon(28% 0, 72% 0, 87% 25%, 100% 100%, 0 100%, 13% 25%); }
.material-section { margin-top: 23px; padding-top: 18px; border-top: 1px solid #eee9e2; }.material-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }.material-heading > div { display: flex; flex-direction: column; gap: 4px; }.material-heading strong { font-size: 10px; }.material-heading span { color: #8d867e; font-size: 8px; line-height: 1.45; }.material-heading button, .material-item-head button { flex: 0 0 auto; padding: 0; color: #8b684f; background: transparent; border: 0; font-size: 8px; }.material-list { border-top: 1px solid #eee9e2; }.material-item { padding: 13px 0; border-bottom: 1px solid #eee9e2; }.material-item-head { display: flex; align-items: center; gap: 8px; }.material-item-head strong { font-size: 9px; }.material-item-head > span { margin-right: auto; padding: 3px 6px; color: #817970; background: #f3efe9; border-radius: 10px; font-size: 7px; }.material-item-head button:hover { color: #5f4935; }.material-item .form-label { margin-top: 12px; }.material-item .form-label small { margin-top: -2px; }.material-preview { display: flex; align-items: center; gap: 12px; margin-top: 12px; padding: 10px; background: #faf8f4; border: 1px solid #e8e2da; border-radius: 8px; }.material-preview.empty { background: #fcfbf8; border-style: dashed; }.material-preview-image { display: grid; place-items: center; flex: 0 0 92px; width: 92px; height: 78px; overflow: hidden; color: #a49c91; background: #f0ece6; border-radius: 6px; font-size: 8px; text-align: center; }.material-preview-image img { display: block; width: 100%; height: 100%; object-fit: cover; }.material-preview-copy { display: flex; flex-direction: column; gap: 4px; min-width: 0; }.material-preview-copy strong { overflow: hidden; color: #37332e; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.material-preview-copy small { color: #8d867e; font-size: 8px; }.material-preview-copy > span { overflow: hidden; color: #817970; font-size: 8px; line-height: 1.45; text-overflow: ellipsis; }.reference-error { margin: 12px 0 0; }
.form-label { display: flex; flex-direction: column; gap: 8px; margin: 20px 0 0; color: #59554f; font-size: 10px; }.form-label small { color: #989087; font-size: 8px; font-weight: 400; }.form-label textarea, .form-label input, .form-label select { width: 100%; padding: 12px 13px; color: var(--ink); background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; outline: 0; resize: vertical; font-size: 11px; }.form-label textarea:focus, .form-label input:focus, .form-label select:focus { border-color: #9c835f; box-shadow: 0 0 0 3px #a1845518; }.form-label textarea::placeholder, .form-label input::placeholder { color: #aba39a; }.prompt-suggestions { display: flex; flex-wrap: wrap; align-items: center; gap: 7px; margin-top: 13px; color: #9a9288; font-size: 8px; }.prompt-suggestions button { padding: 6px 8px; color: #756e66; background: #f3efe9; border: 0; border-radius: 14px; font-size: 8px; }.prompt-suggestions button:hover { background: #e9e2d8; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 15px; }.stepper { display: flex; align-items: center; justify-content: space-between; height: 41px; padding: 4px; background: #fcfbf8; border: 1px solid #ddd7ce; border-radius: 8px; }.stepper button { display: grid; place-items: center; width: 30px; height: 30px; color: #5b554d; background: #f1ece5; border: 0; border-radius: 6px; }.stepper output { color: #282521; font: 16px Georgia, serif; }.toggle-list { margin-top: 22px; border-top: 1px solid #eee9e2; }.toggle-row { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 15px 0; border-bottom: 1px solid #eee9e2; }.toggle-row > span { display: flex; flex-direction: column; gap: 4px; }.toggle-row strong { font-size: 10px; font-weight: 600; }.toggle-row small { color: #938c83; font-size: 8px; }.toggle-row input { position: absolute; opacity: 0; pointer-events: none; }.toggle-row i { position: relative; display: block; flex: 0 0 auto; width: 35px; height: 20px; background: #d8d1c8; border-radius: 20px; transition: background .2s; }.toggle-row i::after { position: absolute; top: 3px; left: 3px; width: 14px; height: 14px; background: #fff; border-radius: 50%; box-shadow: 0 1px 3px #0002; content: ''; transition: transform .2s; }.toggle-row input:checked + i { background: #807256; }.toggle-row input:checked + i::after { transform: translateX(15px); }
.review-view { min-height: 355px; }.workflow-name { max-width: 500px; }.review-list { margin-top: 25px; border-top: 1px solid #eee9e2; }.review-row { display: grid; grid-template-columns: 100px 1fr; gap: 15px; padding: 13px 0; border-bottom: 1px solid #eee9e2; }.review-row span { color: #958e85; font-size: 9px; }.review-row strong { overflow: hidden; color: #38342e; font-size: 10px; font-weight: 500; text-overflow: ellipsis; white-space: nowrap; }.version-note { display: flex; flex-direction: column; gap: 5px; margin-top: 22px; padding: 14px; background: #f4efe8; border-radius: 8px; }.version-note > span { color: #9a9187; font-size: 8px; }.version-note strong { font: 400 14px Georgia, serif; }.version-note small { color: #817970; font-size: 8px; }.generation-handoff { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; padding-top: 9px; border-top: 1px solid #e4dcd1; }.generation-handoff span { color: #817970; font-size: 8px; line-height: 1.4; }.generation-handoff button { flex: 0 0 auto; padding: 7px 9px; color: #fff; background: #292722; border: 0; border-radius: 6px; font-size: 8px; }
.editor-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 25px; padding-top: 20px; border-top: 1px solid #eee9e2; }.step-progress { color: #a39b91; font: 11px Georgia, serif; }.version-panel { padding: 24px; }.version-panel h2 { margin-bottom: 16px; }.version-list { border-top: 1px solid #e5dfd6; }.version-card { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; padding: 14px 0; border-bottom: 1px solid #eee9e2; }.version-card > div { display: flex; flex-direction: column; gap: 5px; min-width: 0; }.version-card strong { overflow: hidden; font-size: 10px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.version-card small { color: #938b81; font-size: 8px; }.version-card button, .panel-link { flex: 0 0 auto; padding: 0; color: #6e665d; background: transparent; border: 0; font-size: 8px; }.version-card button:hover, .panel-link:hover { color: #a18455; }.panel-link { display: flex; justify-content: space-between; width: 100%; margin-top: 18px; padding-top: 15px; border-top: 1px solid #eee9e2; }.empty-version { color: #938b81; font-size: 9px; }
.modal-backdrop { position: fixed; inset: 0; z-index: 100; display: grid; place-items: center; padding: 24px; background: #24221fcc; }
.history-modal { width: min(760px, 100%); max-height: min(820px, calc(100vh - 48px)); overflow: auto; padding: 26px; background: #fff; border: 1px solid #e5dfd6; border-radius: 12px; box-shadow: 0 24px 70px #0004; }
.history-modal-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; padding-bottom: 20px; border-bottom: 1px solid #eee9e2; }.history-modal-head h2 { margin: 5px 0 6px; font: 400 26px Georgia, serif; }.history-modal-head span { color: #8a847c; font-size: 10px; line-height: 1.5; }.close-button { display: grid; place-items: center; flex: 0 0 auto; width: 30px; height: 30px; color: #6c655d; background: #f5f1eb; border: 0; border-radius: 50%; font-size: 18px; line-height: 1; }.close-button:hover { color: var(--ink); background: #ebe4da; }
.history-list { display: flex; flex-direction: column; gap: 14px; padding-top: 18px; }.history-record { padding: 17px; background: #fcfbf8; border: 1px solid #e8e2da; border-radius: 9px; }.history-record-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 15px; padding-bottom: 14px; border-bottom: 1px solid #eee9e2; }.history-record-head > div { display: flex; flex-direction: column; gap: 5px; min-width: 0; }.history-record-head strong { color: #302c27; font-size: 12px; }.history-record-head small { color: #938b81; font-size: 8px; }.history-load-button { flex: 0 0 auto; padding: 0; color: #8a6c4c; background: transparent; border: 0; font-size: 9px; }.history-load-button:hover { color: #5f4935; }.history-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px 18px; margin: 15px 0 0; }.history-meta div { min-width: 0; }.history-meta dt, .history-prompt span { color: #9a9288; font-size: 8px; }.history-meta dd { margin: 4px 0 0; overflow: hidden; color: #4a443d; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.history-prompt { margin-top: 14px; padding-top: 12px; border-top: 1px solid #eee9e2; }.history-prompt p { margin: 5px 0 0; color: #4a443d; font-size: 9px; line-height: 1.6; white-space: pre-wrap; }.history-empty { padding: 28px 0 8px; color: #938b81; font-size: 10px; text-align: center; }
.load-error { margin: -16px 0 22px; padding: 10px 12px; color: #875d4c; background: #f8ece7; border: 1px solid #edd7ce; border-radius: 8px; font-size: 10px; }.toast { position: fixed; right: 24px; bottom: 24px; z-index: 50; padding: 11px 15px; color: #fff; background: #292722; border-radius: 8px; box-shadow: 0 10px 30px #0002; font-size: 10px; }.toast-enter-active, .toast-leave-active { transition: opacity .2s, transform .2s; }.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(8px); }
@media (max-width: 1100px) { .workflow-layout { grid-template-columns: 165px minmax(0, 1fr); }.version-panel { grid-column: 2; }.editor-panel { grid-column: 2; grid-row: 1; } }
@media (max-width: 800px) { .workspace-layout { display: block; }.sidebar { position: static; flex-direction: row; align-items: center; height: auto; padding: 13px 16px; }.brand { padding: 0; }.space-card, .nav-list, .tip-card, .profile > span:nth-child(2), .profile button { display: none; }.profile { margin: 0 0 0 auto; padding: 0; border: 0; }.topbar { height: 58px; padding: 0 18px; }.service-state, .icon-button { display: none; }.content { padding: 30px 16px 55px; }.heading { align-items: flex-start; flex-direction: column; }.heading-actions { width: 100%; flex-wrap: wrap; }.draft-status { flex: 0 0 100%; margin-bottom: 2px; }.heading-actions button { flex: 1; justify-content: center; min-width: 0; }.workflow-layout { display: flex; flex-direction: column; gap: 14px; }.workflow-steps { display: grid; grid-template-columns: repeat(4, 1fr); width: 100%; }.step-button { flex-direction: column; align-items: center; gap: 6px; padding: 9px 4px; text-align: center; }.step-button > span:last-child { align-items: center; }.step-button small { display: none; }.editor-panel, .version-panel { width: 100%; }.version-panel { order: 3; }.choice-grid { grid-template-columns: 1fr; }.material-heading { flex-direction: column; }.material-heading button { align-self: flex-start; }.material-preview { align-items: flex-start; }.material-preview-image { flex-basis: 82px; width: 82px; height: 70px; }.generation-handoff { align-items: stretch; flex-direction: column; }.generation-handoff button { align-self: flex-start; } }
 @media (max-width: 800px) { .modal-backdrop { align-items: end; padding: 0; }.history-modal { max-height: calc(100vh - 20px); padding: 21px 17px; border-radius: 12px 12px 0 0; }.history-meta { grid-template-columns: 1fr; }.history-record-head { flex-direction: column; }.history-load-button { align-self: flex-start; } }
 @media (max-width: 480px) { .content { padding-inline: 13px; }.heading h1 { font-size: 36px; }.editor-panel, .version-panel { padding: 20px 16px; }.form-grid { grid-template-columns: 1fr; }.review-row { grid-template-columns: 80px 1fr; }.workflow-steps { gap: 2px; }.step-button strong { font-size: 9px; }.editor-footer .quiet-button, .editor-footer .dark-button { padding-inline: 10px; } }
</style>
