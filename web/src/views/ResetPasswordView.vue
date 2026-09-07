<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockKeyhole } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const route = useRoute(); const router = useRouter()
const account = ref(String(route.query.account || ''))
const channel = ref<'EMAIL' | 'PHONE'>(String(route.query.channel || 'EMAIL') === 'PHONE' ? 'PHONE' : 'EMAIL')
const code = ref(String(route.query.code || ''))
const password = ref(''); const confirmation = ref(''); const error = ref(''); const success = ref(''); const loading = ref(false)
async function submit() {
  if (password.value !== confirmation.value) { error.value = '两次输入的密码不一致'; return }
  loading.value = true; error.value = ''
  try {
    await api.post('/auth/reset-password', { account: account.value, channel: channel.value, code: code.value, newPassword: password.value })
    success.value = '密码重置成功，即将返回登录。'
    setTimeout(() => router.push('/login'), 900)
  } catch (reason: any) { error.value = reason.response?.data?.message || '重置失败' }
  finally { loading.value = false }
}
</script>

<template><AuthLayout><div class="auth-card__icon"><LockKeyhole :size="24" /></div><p class="auth-card__eyebrow">SET NEW PASSWORD</p><h2>验证码重置密码</h2><p class="auth-card__sub">验证码仅可使用一次，过期后请重新获取</p><form class="form-stack" @submit.prevent="submit"><label class="field"><span>账号</span><input v-model.trim="account" required autocomplete="username" placeholder="用户名 / 手机号 / 邮箱" /></label><label class="field"><span>验证方式</span><select v-model="channel"><option value="EMAIL">登记邮箱</option><option value="PHONE">登记手机号</option></select></label><label class="field"><span>{{ channel === 'PHONE' ? '4 位短信验证码' : '6 位邮箱验证码' }}</span><input v-model.trim="code" required inputmode="numeric" :maxlength="channel === 'PHONE' ? 4 : 6" placeholder="请输入动态验证码" /></label><label class="field"><span>新密码</span><input v-model="password" minlength="10" required type="password" autocomplete="new-password" /></label><label class="field"><span>确认新密码</span><input v-model="confirmation" minlength="10" required type="password" autocomplete="new-password" /></label><p v-if="error" class="form-error">{{ error }}</p><p v-if="success" class="form-success">{{ success }}</p><button class="primary-action" :disabled="loading || !!success">{{ loading ? '正在重置…' : '确认重置' }}</button></form><p class="auth-switch">没有验证码？<RouterLink to="/forgot-password">重新获取</RouterLink></p></AuthLayout></template>
