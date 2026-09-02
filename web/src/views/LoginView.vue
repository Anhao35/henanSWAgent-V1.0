<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockKeyhole, UserRound, ArrowRight, ShieldCheck } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { useAuthStore } from '../stores/auth'

const login = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

async function submit() {
  loading.value = true
  error.value = ''
  try {
    await auth.login(login.value, password.value)
    await router.replace(String(route.query.redirect || '/workspace'))
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '登录失败，请检查账号和密码'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card__icon"><ShieldCheck :size="24" /></div>
    <p class="auth-card__eyebrow">WELCOME BACK</p>
    <h2>登录安全工作台</h2>
    <p class="auth-card__sub">使用中心账号进入您的专属研判空间</p>
    <form class="form-stack" @submit.prevent="submit">
      <label class="field"><span>账号</span><div class="input-wrap"><UserRound :size="18" /><input v-model.trim="login" required autocomplete="username" placeholder="用户名 / 手机号 / 邮箱" /></div></label>
      <label class="field"><span>密码</span><div class="input-wrap"><LockKeyhole :size="18" /><input v-model="password" required type="password" autocomplete="current-password" placeholder="请输入登录密码" /></div></label>
      <div class="form-row"><span></span><RouterLink to="/forgot-password">忘记密码？</RouterLink></div>
      <p v-if="error" class="form-error">{{ error }}</p>
      <button class="primary-action" :disabled="loading">{{ loading ? '正在验证…' : '登录' }}<ArrowRight :size="18" /></button>
    </form>
    <p class="auth-switch">还没有账号？<RouterLink to="/register">申请注册</RouterLink></p>
  </AuthLayout>
</template>
