<script setup lang="ts">
const message = useMessage()

const showSuccessMessage = () => {
  message.success('Naive UI 已成功加载')
}
</script>

<template>
  <n-message-provider>
    <div class="min-h-screen bg-slate-50 px-6 py-12 text-slate-900">
      <div class="mx-auto max-w-3xl">
        <n-card title="Naive UI 安装测试" size="large">
          <n-space vertical size="large">
            <n-alert type="success" title="组件渲染成功">
              如果你能看到这张卡片、按钮和下拉选择框，说明 nuxtjs-naive-ui 已正常工作。
            </n-alert>

            <n-space align="center">
              <n-button type="primary" @click="showSuccessMessage">
                测试消息提示
              </n-button>
              <n-button secondary>
                次要按钮
              </n-button>
              <n-tag type="info">Naive UI</n-tag>
            </n-space>

            <n-select
              :options="[
                { label: '组件自动导入', value: 'auto-import' },
                { label: 'Nuxt SSR 支持', value: 'ssr' },
                { label: '主题与样式', value: 'theme' },
              ]"
              placeholder="选择一个测试项"
            />
          </n-space>
        </n-card>
      </div>
    </div>
  </n-message-provider>
</template>
