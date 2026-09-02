<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, UserPlus } from '@lucide/vue'
import AuthLayout from '../components/AuthLayout.vue'
import { api } from '../api'

const router = useRouter()
const loading = ref(false)
const error = ref('')
const success = ref('')
const organizations = ref<Array<{ code: string; name: string }>>([])
const form = reactive({ username: '', displayName: '', password: '', email: '', phone: '', organizationCode: 'HERCERT' })

onMounted(async () => {
  const { data } = await api.get('/organizations/public')
  organizations.value = data
})

async function submit() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.post('/auth/register', form)
    success.value = data.requiresApproval ? '申请已提交，请等待组织管理员审核。' : '注册成功，即将前往登录。'
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
    <p class="auth-card__sub">账号仅用于授权范围内的安全分析工作</p>
    <form class="form-stack compact" @submit.prevent="submit">
      <div class="field-grid">
        <label class="field"><span>登录用户名</span><input v-model.trim="form.username" required minlength="3" placeholder="字母、数字或下划线" /></label>
        <label class="field"><span>姓名 / 显示名称</span><input v-model.trim="form.displayName" required placeholder="请输入姓名" /></label>
      </div>
      <label class="field"><span>所属组织</span><select v-model="form.organizationCode"><option v-for="org in organizations" :key="org.code" :value="org.code">{{ org.name }}</option></select></label>
      <div class="field-grid">
        <label class="field"><span>邮箱</span><input v-model.trim="form.email" type="email" placeholder="用于找回密码" /></label>
        <label class="field"><span>手机号</span><input v-model.trim="form.phone" inputmode="tel" placeholder="邮箱或手机号至少一项" /></label>
      </div>
      <label class="field"><span>登录密码</span><input v-model="form.password" required minlength="10" type="password" autocomplete="new-password" placeholder="至少10位，建议混合大小写、数字和符号" /></label>
      <p v-if="error" class="form-error">{{ error }}</p><p v-if="success" class="form-success">{{ success }}</p>
      <button class="primary-action" :disabled="loading || !!success">{{ loading ? '正在提交…' : '提交注册申请' }}<ArrowRight :size="18" /></button>
    </form>
    <p class="auth-switch">已有账号？<RouterLink to="/login">返回登录</RouterLink></p>
  </AuthLayout>
</template>
