<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  Bot, ChevronDown, FileSearch, Globe2, Hash, Link2, LogOut, Menu, MessageSquarePlus,
  PanelLeftClose, Search, Send, Settings, Shield, ShieldCheck, UserRound, X,
} from '@lucide/vue'
import BrandMark from '../components/BrandMark.vue'
import { api, streamRequest } from '../api'
import { useAuthStore } from '../stores/auth'
import type { Conversation, Message } from '../types'

const auth = useAuthStore(); const router = useRouter(); const route = useRoute()
const conversations = ref<Conversation[]>([]); const messages = ref<Message[]>([])
const activeId = ref<string>(''); const draft = ref(''); const loading = ref(false)
const sidebarOpen = ref(true); const accountOpen = ref(false); const search = ref('')
const messageArea = ref<HTMLElement>(); const input = ref<HTMLTextAreaElement>(); const error = ref('')

const filteredConversations = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return keyword ? conversations.value.filter(item => item.title.toLowerCase().includes(keyword)) : conversations.value
})
const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value))
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || '用')

function renderMarkdown(content: string) {
  return DOMPurify.sanitize(marked.parse(content || '') as string)
}
function formatTime(value?: string) {
  if (!value) return ''
  const date = new Date(value)
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(date)
}
async function loadConversations() {
  const { data } = await api.get<Conversation[]>('/conversations')
  conversations.value = data
}
async function selectConversation(id: string, updateRoute = true) {
  activeId.value = id; error.value = ''
  const { data } = await api.get<Message[]>(`/conversations/${id}/messages`)
  messages.value = data
  if (updateRoute && route.params.id !== id) await router.push(`/workspace/chat/${id}`)
  await scrollBottom()
}
async function newConversation() {
  const { data } = await api.post<Conversation>('/conversations', {})
  conversations.value.unshift(data)
  await selectConversation(data.id)
  await nextTick(); input.value?.focus()
}
async function send() {
  const content = draft.value.trim(); if (!content || loading.value) return
  if (!activeId.value) await newConversation()
  draft.value = ''; loading.value = true; error.value = ''
  const userMessage: Message = { role: 'USER', content, status: 'COMPLETED', createdAt: new Date().toISOString() }
  const assistant: Message = { role: 'ASSISTANT', content: '正在连接安全智能体…', status: 'STREAMING' }
  let receivedAnswer = false
  messages.value.push(userMessage, assistant); await scrollBottom()
  try {
    await streamRequest(`/conversations/${activeId.value}/messages/stream`, { message: content }, event => {
      if (event.type === 'append') {
        if (!receivedAnswer) assistant.content = ''
        assistant.content += event.answer || ''
        receivedAnswer = true
      }
      if (event.type === 'replace') { assistant.content = event.answer || ''; receivedAnswer = true }
      if (event.type === 'status' && !receivedAnswer) assistant.content = `## 正在分析\n\n- ${event.message}`
      if (event.type === 'done') { assistant.content = event.answer || assistant.content; assistant.status = 'COMPLETED' }
      if (event.type === 'error') throw new Error(event.error || 'Agent执行失败')
      scrollBottom()
    })
    await loadConversations()
  } catch (reason: any) {
    assistant.status = 'FAILED'; assistant.content = `## 分析失败\n\n${reason.message || 'Agent服务暂不可用'}`; error.value = reason.message
  } finally { loading.value = false; await scrollBottom() }
}
async function removeConversation(id: string) {
  if (!window.confirm('确定删除这条会话吗？')) return
  await api.delete(`/conversations/${id}`)
  conversations.value = conversations.value.filter(item => item.id !== id)
  if (activeId.value === id) { activeId.value = ''; messages.value = []; await router.push('/workspace') }
}
function useQuickQuery(label: string, example: string) {
  draft.value = `${label}：${example}`; nextTick(() => input.value?.focus())
}
async function logout() { await auth.logout(); await router.replace('/login') }
async function scrollBottom() { await nextTick(); messageArea.value?.scrollTo({ top: messageArea.value.scrollHeight, behavior: 'smooth' }) }

onMounted(async () => {
  await loadConversations()
  const routeId = String(route.params.id || '')
  if (routeId) await selectConversation(routeId, false).catch(() => router.replace('/workspace'))
})
watch(() => route.params.id, async id => {
  if (id && id !== activeId.value) await selectConversation(String(id), false)
})
</script>

<template>
  <main class="app-shell">
    <aside class="app-sidebar" :class="{ collapsed: !sidebarOpen }">
      <div class="sidebar-brand"><BrandMark /><div><small>HERCERT</small><strong>省网智能体</strong></div><button class="icon-button sidebar-toggle" @click="sidebarOpen = !sidebarOpen"><PanelLeftClose :size="19" /></button></div>
      <button class="new-chat" @click="newConversation"><MessageSquarePlus :size="18" /><span>新建安全研判</span></button>
      <div class="conversation-search"><Search :size="16" /><input v-model="search" placeholder="搜索会话" /></div>
      <nav class="sidebar-section tools"><p>安全工具</p><button @click="useQuickQuery('IP查询', '请输入待查询IP')"><Globe2 /><span>IP 情报查询</span></button><button @click="useQuickQuery('域名查询', '请输入待查询域名')"><Shield /><span>域名研判</span></button><button @click="useQuickQuery('URL查询', '请输入待查询URL')"><Link2 /><span>URL 检测</span></button><button @click="useQuickQuery('CVE查询', 'CVE-2024-')"><FileSearch /><span>CVE 漏洞查询</span></button><button @click="useQuickQuery('Hash查询', '请输入文件Hash')"><Hash /><span>Hash 威胁查询</span></button></nav>
      <section class="conversation-section"><p>最近会话</p><div class="conversation-list"><article v-for="item in filteredConversations" :key="item.id" :class="{ active: item.id === activeId }" @click="selectConversation(item.id)"><span class="conversation-title">{{ item.title }}</span><small>{{ formatTime(item.lastMessageAt || item.createdAt) }}</small><button title="删除会话" @click.stop="removeConversation(item.id)"><X :size="13" /></button></article><div v-if="!filteredConversations.length" class="sidebar-empty">暂无会话记录</div></div></section>
      <div class="account-zone"><button class="account-button" @click="accountOpen = !accountOpen"><span class="user-avatar"><img v-if="auth.user?.avatarUrl" :src="`${auth.user.avatarUrl}?v=${auth.user.id}`" />{{ auth.user?.avatarUrl ? '' : initials }}</span><span><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.organizationName }}</small></span><ChevronDown :size="16" /></button><div v-if="accountOpen" class="account-menu"><RouterLink to="/settings/profile"><UserRound :size="16" />个人资料</RouterLink><RouterLink to="/settings/profile?tab=security"><Settings :size="16" />账号与安全</RouterLink><RouterLink v-if="['SUPER_ADMIN','ORG_ADMIN'].includes(auth.user?.role || '')" to="/admin/users"><ShieldCheck :size="16" />用户管理</RouterLink><button @click="logout"><LogOut :size="16" />退出登录</button></div></div>
    </aside>

    <section class="workspace-shell">
      <header class="workspace-topbar"><button class="icon-button mobile-menu" @click="sidebarOpen = !sidebarOpen"><Menu /></button><div class="workspace-heading"><span class="online-dot"></span><div><strong>{{ activeConversation?.title || '安全研判工作台' }}</strong><small>网络安全垂域智能体 · 在线</small></div></div><div class="topbar-org"><ShieldCheck :size="16" />河南省教育科研计算机网络中心</div></header>
      <section ref="messageArea" class="message-area">
        <div v-if="!activeId" class="welcome-state"><div class="welcome-orb"><Bot :size="36" /></div><span class="eyebrow"><i></i> 多源威胁情报联动</span><h1>今天需要研判什么？</h1><p>输入IP、域名、URL、文件Hash或CVE编号，我会调用安全工具完成分析并生成结构化报告。</p><div class="starter-grid"><button @click="useQuickQuery('IP查询', '8.8.8.8')"><Globe2 /><span><strong>查询可疑IP</strong><small>信誉、归属与恶意活动</small></span></button><button @click="useQuickQuery('域名查询', 'example.com')"><Shield /><span><strong>研判域名</strong><small>解析、证书与关联样本</small></span></button><button @click="useQuickQuery('CVE查询', 'CVE-2024-3400')"><FileSearch /><span><strong>分析CVE漏洞</strong><small>影响范围与修复建议</small></span></button></div></div>
        <div v-else class="message-thread"><article v-for="(message, index) in messages" :key="message.id || index" class="chat-message" :class="message.role.toLowerCase()"><div class="message-avatar"><Bot v-if="message.role === 'ASSISTANT'" :size="19" /><span v-else>{{ initials }}</span></div><div class="message-content"><div class="message-meta"><strong>{{ message.role === 'ASSISTANT' ? '网络安全垂域智能体' : auth.user?.displayName }}</strong><span v-if="message.status === 'STREAMING'" class="streaming-label">分析中</span><span v-if="message.status === 'FAILED'" class="failed-label">执行失败</span></div><div v-if="message.role === 'ASSISTANT'" class="markdown" v-html="renderMarkdown(message.content)"></div><p v-else>{{ message.content }}</p></div></article></div>
      </section>
      <footer class="composer-zone"><form class="composer-box" @submit.prevent="send"><textarea ref="input" v-model="draft" rows="1" placeholder="输入研判目标，或粘贴安全事件信息…" @keydown.enter.exact.prevent="send"></textarea><div class="composer-footer"><span>Agent将调用内部安全工具，请勿提交超出授权范围的数据</span><button :disabled="loading || !draft.trim()" aria-label="发送"><Send :size="18" /></button></div></form><p v-if="error" class="workspace-error">{{ error }}</p></footer>
    </section>
  </main>
</template>
