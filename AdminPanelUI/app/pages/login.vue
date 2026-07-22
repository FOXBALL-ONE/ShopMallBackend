<script setup lang="ts">
import { ref, reactive } from 'vue'
import type { FormInst, FormRules } from 'naive-ui'

definePageMeta({ layout: false })

// 字段对齐后端 LoginRequest { identifier, password }；暂不对接 API，仅做表单校验 + 模拟登录
const formRef = ref<FormInst | null>(null)
const loading = ref(false)
const model = reactive({
  identifier: '',
  password: '',
})

const rules: FormRules = {
  identifier: [{ required: true, message: '请输入用户名或邮箱', trigger: ['blur', 'input'] }],
  password: [{ required: true, message: '请输入密码', trigger: ['blur', 'input'] }],
}

async function handleSubmit() {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  // TODO: 对接 POST /api/auth/login，body = { identifier, password }
  await new Promise(resolve => setTimeout(resolve, 600))
  loading.value = false
  await navigateTo('/')
}
</script>

<template>
  <div class="login-page">
    <NCard class="login-card" :bordered="false" size="large">
      <div class="brand">
        <div class="brand-name">
          ShopMall
        </div>
        <div class="brand-sub">
          管理后台，不对外开放
        </div>
      </div>

      <NForm
        ref="formRef"
        :model="model"
        :rules="rules"
        label-placement="top"
        size="large"
        @submit.prevent="handleSubmit"
      >
        <NFormItem label="用户名 / 邮箱" path="identifier">
          <NInput
            v-model:value="model.identifier"
            placeholder="用户名或邮箱"
            clearable
          />
        </NFormItem>

        <NFormItem label="密码" path="password">
          <NInput
            v-model:value="model.password"
            type="password"
            placeholder="密码"
            show-password-on="click"
            @keyup.enter="handleSubmit"
          />
        </NFormItem>

        <NButton
          type="primary"
          block
          size="large"
          :loading="loading"
          @click="handleSubmit"
        >
          登录
        </NButton>
      </NForm>
    </NCard>
  </div>
</template>

<style scoped>
.login-page {
  height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f5f7fa 0%, #e8edf3 100%);
}

.login-card {
  width: 400px;
  max-width: calc(100vw - 32px);
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
  border-radius: 12px;
}

.brand {
  text-align: center;
  margin-bottom: 24px;
}

.brand-name {
  font-size: 28px;
  font-weight: 700;
  color: #18a058;
}

.brand-sub {
  margin-top: 4px;
  font-size: 14px;
  color: #909399;
}
</style>
