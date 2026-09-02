<script setup lang="ts">
import { ref } from 'vue'
import { KeyRound } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const account = ref('')
const loading = ref(false)
const message = ref('')
const devToken = ref('')
async function submit() {
  loading.value = true
  const { data } = await api.post('/auth/forgot-password', { account: account.value }).finally(() => loading.value = false)
  message.value = data.message
  devToken.value = data.devResetToken || ''
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card__icon"><KeyRound :size="24" /></div>
    <p class="auth-card__eyebrow">ACCOUNT RECOVERY</p><h2>找回密码</h2>
    <p class="auth-card__sub">输入用户名、手机号或邮箱，我们会发送重置指引</p>
    <form class="form-stack" @submit.prevent="submit">
      <label class="field"><span>账号</span><input v-model.trim="account" required placeholder="用户名 / 手机号 / 邮箱" /></label>
      <p v-if="message" class="form-success">{{ message }}</p>
      <RouterLink v-if="devToken" class="dev-link" :to="`/reset-password?token=${devToken}`">开发环境：直接进入重置页面</RouterLink>
      <button class="primary-action" :disabled="loading">{{ loading ? '正在提交…' : '发送重置指引' }}</button>
    </form>
    <p class="auth-switch"><RouterLink to="/login">返回登录</RouterLink></p>
  </AuthLayout>
</template>
