<script setup lang="ts">
const route = useRoute()
const section = computed(() => String(route.params.section || 'generate'))
const projectId = computed(() => String(route.params.projectId || 'prj_noir'))
const { activeProjectId } = useProjectWorkspace(projectId)

const content = computed(() => section.value === 'results'
  ? {
      eyebrow: 'RESULT CENTER', title: '结果中心', description: '集中查看已完成的生成结果与可追溯版本。',
      items: [
        { label: 'NOIR 春夏主视觉', detail: 'IMAGE · V3 · 4 张结果待筛选', status: '待处理' },
        { label: '晨光主视觉', detail: 'IMAGE · V3 · 已归档到项目素材', status: '已完成' },
        { label: '黑色丝缎细节', detail: 'IMAGE · V2 · 可继续复用', status: '已完成' },
      ],
    }
  : {
      eyebrow: 'GENERATION QUEUE', title: '生成任务', description: '跟踪当前项目的生成队列、状态和工作流版本。',
      items: [
        { label: 'NOIR 春夏主视觉', detail: 'IMAGE · V4 · 4 张高分辨率画面', status: '运行中' },
        { label: '黑色丝缎细节', detail: 'IMAGE · V2 · 4 张结果已生成', status: '已完成' },
        { label: '自然光半身图', detail: 'IMAGE · V1 · 等待队列资源', status: '待处理' },
      ],
    })
</script>

<template>
  <StudioSectionPage :project-id="activeProjectId" v-bind="content" />
</template>
