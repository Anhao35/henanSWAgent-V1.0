<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockKeyhole } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const route = useRoute(); const router = useRouter()
const password = ref(''); const confirmation = ref(''); const error = ref(''); const loading = ref(false)
async function submit() {
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致'; return }
  loading.value = true; error.value = ''
  try {
    await api.post('/auth/reset-password', { token: String(route.query.token || ''), newPassword: password.value })
    await router.push('/login')
  } catch (reason: any) { error.value = reason.response?.data?.message || '重置失败' }
  finally { loading.value = false }
}
</script>

<template><AuthLayout><div class="auth-card__icon"><LockKeyhole :size="24" /></div><p class="auth-card__eyebrow">SET NEW PASSWORD</p><h2>设置新密码</h2><p class="auth-card__sub">重置后请使用新密码重新登录</p><form class="form-stack" @submit.prevent="submit"><label class="field"><span>新密码</span><input v-model="password" minlength="10" required type="password" autocomplete="new-password" /></label><label class="field"><span>确认新密码</span><input v-model="confirmation" minlength="10" required type="password" autocomplete="new-password" /></label><p v-if="error" class="form-error">{{ error }}</p><button class="primary-action" :disabled="loading">{{ loading ? '正在重置…' : '确认重置' }}</button></form></AuthLayout></template>
