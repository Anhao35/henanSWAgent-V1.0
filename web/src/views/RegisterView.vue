<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Mail, MessageSquareText, Send, UserPlus } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const sendingCode = ref(false)
const error = ref('')
const success = ref('')
const codeNotice = ref('')
const devCode = ref('')
const countdown = ref(0)
const organizations = ref<Array<{ code: string; name: string }>>([])
const form = reactive({ username: '', displayName: '', password: '', email: '', phone: '', organizationCode: 'HERCERT', verificationChannel: 'EMAIL', verificationCode: '' })
const target = computed(() => form.verificationChannel === 'EMAIL' ? form.email : form.phone)
let timer: number | undefined

onMounted(async () => {
  const { data } = await api.get('/organizations/public')
  organizations.value = data
})
onBeforeUnmount(() => window.clearInterval(timer))

function setChannel(channel: 'EMAIL' | 'PHONE') {
  form.verificationChannel = channel
  form.verificationCode = ''
  codeNotice.value = ''
  devCode.value = ''
}

function startCountdown(seconds: number) {
  countdown.value = seconds
  window.clearInterval(timer)
  timer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) window.clearInterval(timer)
  }, 1000)
}

async function sendCode() {
  if (!target.value.trim()) {
    error.value = form.verificationChannel === 'EMAIL' ? '请先填写邮箱' : '请先填写手机号'
    return
  }
  sendingCode.value = true
  error.value = ''
  try {
    const { data } = await api.post('/auth/verification-codes', { purpose: 'REGISTER', channel: form.verificationChannel, target: target.value })
    codeNotice.value = `${data.message}${data.maskedTarget ? `：${data.maskedTarget}` : ''}`
    devCode.value = data.devCode || ''
    startCountdown(60)
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '验证码发送失败，请稍后重试'
  } finally {
    sendingCode.value = false
  }
}

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const payload = { ...form }
    if (form.verificationChannel === 'EMAIL') payload.phone = ''
    else payload.email = ''
    const { data } = await api.post('/auth/register', payload)
    success.value = data.requiresApproval ? '注册与验证已完成，请等待组织管理员审核。' : '注册成功，即将前往登录。'
    if (!data.requiresApproval) setTimeout(() => router.push('/login'), 900)
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '注册失败，请检查填写内容'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout>
    <div class="auth-card__icon"><UserPlus :size="24" /></div>
    <p class="auth-card__eyebrow">CREATE ACCOUNT</p>
    <h2>申请平台账号</h2>
    <p class="auth-card__sub">选择邮箱或手机号完成动态验证码验证</p>
    <form class="form-stack compact" @submit.prevent="submit">
      <div class="verification-tabs" role="tablist" aria-label="注册验证方式">
        <button type="button" :class="{ active: form.verificationChannel === 'EMAIL' }" @click="setChannel('EMAIL')"><Mail :size="16" />邮箱注册</button>
        <button type="button" :class="{ active: form.verificationChannel === 'PHONE' }" @click="setChannel('PHONE')"><MessageSquareText :size="16" />手机号注册</button>
      </div>
      <div class="field-grid">
        <label class="field"><span>登录用户名</span><input v-model.trim="form.username" required minlength="3" autocomplete="username" placeholder="字母、数字或下划线" /></label>
        <label class="field"><span>姓名 / 显示名称</span><input v-model.trim="form.displayName" required placeholder="请输入姓名" /></label>
      </div>
      <label class="field"><span>所属组织</span><select v-model="form.organizationCode"><option v-for="org in organizations" :key="org.code" :value="org.code">{{ org.name }}</option></select></label>
      <label v-if="form.verificationChannel === 'EMAIL'" class="field"><span>邮箱</span><input v-model.trim="form.email" type="email" required autocomplete="email" placeholder="name@example.com" /></label>
      <label v-else class="field"><span>手机号</span><input v-model.trim="form.phone" inputmode="numeric" required autocomplete="tel" maxlength="11" placeholder="请输入登记手机号" /></label>
      <label class="field"><span>{{ form.verificationChannel === 'EMAIL' ? '6 位邮箱验证码' : '4 位短信验证码' }}</span><span class="code-input-row"><input v-model.trim="form.verificationCode" inputmode="numeric" required :maxlength="form.verificationChannel === 'EMAIL' ? 6 : 4" placeholder="请输入动态验证码" /><button type="button" class="send-code-button" :disabled="sendingCode || countdown > 0" @click="sendCode"><Send :size="15" />{{ countdown > 0 ? `${countdown}s` : sendingCode ? '发送中' : '获取验证码' }}</button></span></label>
      <p v-if="codeNotice" class="form-success">{{ codeNotice }}</p>
      <p v-if="devCode" class="dev-code">本地联调验证码：<strong>{{ devCode }}</strong><button type="button" @click="form.verificationCode = devCode">自动填入</button></p>
      <label class="field"><span>登录密码</span><input v-model="form.password" required minlength="10" type="password" autocomplete="new-password" placeholder="至少10位，建议混合大小写、数字和符号" /></label>
      <p v-if="error" class="form-error">{{ error }}</p><p v-if="success" class="form-success">{{ success }}</p>
      <button class="primary-action" :disabled="loading || !!success">{{ loading ? '正在提交…' : '提交注册申请' }}<ArrowRight :size="18" /></button>
    </form>
    <p class="auth-switch">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
  </AuthLayout>
</template>
