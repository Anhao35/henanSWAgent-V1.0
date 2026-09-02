<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, Search, ShieldCheck, UsersRound } from '@lucide/vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
import type { User } from '../types'

const auth = useAuthStore(); const users = ref<User[]>([]); const search = ref(''); const message = ref('')
const filtered = computed(() => { const q = search.value.trim().toLowerCase(); return q ? users.value.filter(u => [u.username,u.displayName,u.email,u.phone,u.organizationName].some(v => v?.toLowerCase().includes(q))) : users.value })
async function load() { const { data } = await api.get<User[]>('/admin/users'); users.value = data }
async function setStatus(user: User, status: string) { const { data } = await api.patch(`/admin/users/${user.id}/status`, { status }); Object.assign(user, data); message.value = status === 'ACTIVE' ? '账号已启用' : '账号状态已更新' }
async function setRole(user: User, role: string) { const { data } = await api.patch(`/admin/users/${user.id}/role`, { role }); Object.assign(user, data); message.value = '用户角色已更新' }
onMounted(load)
</script>

<template><main class="settings-page"><header class="settings-header"><RouterLink to="/workspace"><ArrowLeft :size="18" />返回工作台</RouterLink><div><ShieldCheck /><span><small>HERCERT</small><strong>平台管理</strong></span></div></header><section class="admin-page"><div class="admin-title"><div><span class="admin-icon"><UsersRound /></span><div><h1>用户与权限</h1><p>审核注册申请并管理本组织账号状态</p></div></div><label class="admin-search"><Search :size="16" /><input v-model="search" placeholder="搜索姓名、账号、邮箱或组织" /></label></div><div class="admin-table-wrap"><table class="admin-table"><thead><tr><th>用户</th><th>所属组织</th><th>联系方式</th><th>角色</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="user in filtered" :key="user.id"><td><strong>{{ user.displayName }}</strong><small>@{{ user.username }}</small></td><td>{{ user.organizationName || '未分配' }}</td><td><span>{{ user.email || user.phone || '未填写' }}</span></td><td><select :value="user.role" :disabled="auth.user?.role !== 'SUPER_ADMIN'" @change="setRole(user, ($event.target as HTMLSelectElement).value)"><option value="ANALYST">安全分析员</option><option value="VIEWER">只读用户</option><option value="ORG_ADMIN">组织管理员</option><option value="SUPER_ADMIN">系统管理员</option></select></td><td><span class="status-pill" :class="user.status.toLowerCase()">{{ user.status === 'ACTIVE' ? '正常' : user.status === 'PENDING' ? '待审核' : '已停用' }}</span></td><td><button v-if="user.status !== 'ACTIVE'" @click="setStatus(user,'ACTIVE')">通过</button><button v-else-if="user.id !== auth.user?.id" class="danger" @click="setStatus(user,'DISABLED')">停用</button></td></tr></tbody></table><div v-if="!filtered.length" class="table-empty">没有找到符合条件的用户</div></div><p v-if="message" class="floating-message success">{{ message }}</p></section></main></template>
