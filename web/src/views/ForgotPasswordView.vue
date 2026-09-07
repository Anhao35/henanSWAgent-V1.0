<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { KeyRound, Mail, MessageSquareText } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const router = useRouter()
const account = ref('')
const channel = ref<'EMAIL' | 'PHONE'>('EMAIL')
const loading = ref(false)
const message = ref('')
const error = ref('')
const devCode = ref('')

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.post('/auth/forgot-password', { account: account.value, channel: channel.value })
    message.value = data.message
    devCode.value = data.devCode || ''
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '验证码发送失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function continueReset() {
  router.push({ path: '/reset-password', query: { account: account.value, channel: channel.value, ...(devCode.value ? { code: devCode.value } : {}) } })
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card__icon"><KeyRound :size="24" /></div>
    <p class="auth-card__eyebrow">ACCOUNT RECOVERY</p><h2>找回密码</h2>
    <p class="auth-card__sub">使用账号登记的邮箱或手机号接收动态验证码</p>
    <form class="form-stack" @submit.prevent="submit">
      <div class="verification-tabs" role="tablist" aria-label="找回验证方式">
        <button type="button" :class="{ active: channel === 'EMAIL' }" @click="channel = 'EMAIL'"><Mail :size="16" />邮箱验证</button>
        <button type="button" :class="{ active: channel === 'PHONE' }" @click="channel = 'PHONE'"><MessageSquareText :size="16" />手机验证</button>
      </div>
      <label class="field"><span>账号</span><input v-model.trim="account" required autocomplete="username" placeholder="用户名 / 手机号 / 邮箱" /></label>
      <p v-if="message" class="form-success">{{ message }}</p>
      <p v-if="devCode" class="dev-code">本地联调验证码：<strong>{{ devCode }}</strong></p>
      <p v-if="error" class="form-error">{{ error }}</p>
      <button v-if="!message" class="primary-action" :disabled="loading">{{ loading ? '正在发送…' : '发送动态验证码' }}</button>
      <button v-else type="button" class="primary-action" @click="continueReset">填写验证码并重置密码</button>
    </form>
    <p class="auth-switch"><RouterLink to="/login">返回登录</RouterLink></p>
  </AuthLayout>
</template>
