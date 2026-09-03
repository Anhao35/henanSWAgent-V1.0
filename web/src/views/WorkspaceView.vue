<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import {
  Bot, Check, ChevronDown, Copy, ExternalLink, FileSearch, FileText, Globe2, Hash, Image as ImageIcon,
  Link2, LogOut, Menu, MessageSquarePlus, PanelLeftClose, Paperclip, RotateCcw,
  Search, Send, Settings, Shield, ShieldCheck, UserRound, X,
} from '@lucide/vue'
import BrandMark from '../components/BrandMark.vue'
import { api, streamRequest } from '../api'
import { useAuthStore } from '../stores/auth'
import type { Attachment, Conversation, Message } from '../types'

const auth = useAuthStore(); const router = useRouter(); const route = useRoute()
const conversations = ref<Conversation[]>([]); const messages = ref<Message[]>([])
const activeId = ref<string>(''); const draft = ref(''); const loading = ref(false)
const sidebarOpen = ref(true); const accountOpen = ref(false); const search = ref('')
const messageArea = ref<HTMLElement>(); const input = ref<HTMLTextAreaElement>(); const error = ref('')
const fileInput = ref<HTMLInputElement>(); const attachments = ref<Attachment[]>([])
const uploading = ref(false); const copiedMessageId = ref('')
type QuickQueryType = 'ip' | 'domain' | 'url' | 'cve' | 'hash'
const quickDialogOpen = ref(false); const quickType = ref<QuickQueryType>('ip'); const quickValue = ref('')
const quickInput = ref<HTMLInputElement>()
const quickQueryMeta: Record<QuickQueryType, { label: string; prompt: string; placeholder: string; hint: string }> = {
  ip: { label: 'IP 查询', prompt: '请输入要查询的 IP 地址', placeholder: '例如：8.8.8.8', hint: 'VT 与微步双源情报' },
  domain: { label: '域名查询', prompt: '请输入要查询的域名', placeholder: '例如：example.com', hint: '解析与信誉快速研判' },
  url: { label: 'URL 检测', prompt: '请输入要检测的完整 URL', placeholder: '例如：https://example.com/path', hint: '恶意链接快速检测' },
  cve: { label: 'CVE 查询', prompt: '请输入 CVE 漏洞编号', placeholder: '例如：CVE-2024-3400', hint: '漏洞影响与修复信息' },
  hash: { label: 'Hash 查询', prompt: '请输入文件 Hash', placeholder: '支持 MD5、SHA-1 或 SHA-256', hint: '样本威胁情报查询' },
}

const filteredConversations = computed(() => {
  const keyword = search.value.trim().toLowerCase()
  return keyword ? conversations.value.filter(item => item.title.toLowerCase().includes(keyword)) : conversations.value
})
const activeConversation = computed(() => conversations.value.find(item => item.id === activeId.value))
const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || '用')
const showWelcome = computed(() => !activeId.value || messages.value.length === 0)
const activeQuickMeta = computed(() => quickQueryMeta[quickType.value])

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
  const content = draft.value.trim(); if ((!content && !attachments.value.length) || loading.value || uploading.value) return
  if (!activeId.value) await newConversation()
  const outgoingAttachments = [...attachments.value]
  draft.value = ''; loading.value = true; error.value = ''
  const userMessage: Message = { role: 'USER', content: content || '请分析上传的附件。', status: 'COMPLETED', createdAt: new Date().toISOString(), attachments: outgoingAttachments }
  const assistant: Message = { role: 'ASSISTANT', content: '正在连接安全智能体…', status: 'STREAMING' }
  let receivedAnswer = false
  messages.value.push(userMessage, assistant); await scrollBottom()
  try {
    await streamRequest(`/conversations/${activeId.value}/messages/stream`, {
      message: content,
      attachmentIds: outgoingAttachments.map(item => item.id),
    }, event => {
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
  } finally { attachments.value = []; loading.value = false; await scrollBottom() }
}

async function addFiles(files: File[]) {
  if (!files.length || uploading.value) return
  const available = 3 - attachments.value.length
  if (available <= 0) { error.value = '每条消息最多上传3个附件'; return }
  if (!activeId.value) await newConversation()
  uploading.value = true; error.value = ''
  try {
    for (const file of files.slice(0, available)) {
      if (file.size > 15 * 1024 * 1024) { error.value = `${file.name} 超过15MB限制`; continue }
      const form = new FormData(); form.append('file', file)
      try {
        const { data } = await api.post<Attachment>(`/conversations/${activeId.value}/attachments`, form)
        attachments.value.push(data)
      } catch (reason: any) {
        error.value = reason.response?.data?.message || `${file.name} 上传失败`
      }
    }
    if (files.length > available && !error.value) error.value = '每条消息最多上传3个附件'
  } finally { uploading.value = false; if (fileInput.value) fileInput.value.value = '' }
}

function chooseFiles(event: Event) {
  const target = event.target as HTMLInputElement
  void addFiles(Array.from(target.files || []))
}

function pasteFiles(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.files || [])
  if (!files.length) return
  event.preventDefault()
  void addFiles(files)
}

async function removeAttachment(attachment: Attachment) {
  if (!activeId.value || loading.value) return
  try {
    await api.delete(`/conversations/${activeId.value}/attachments/${attachment.id}`)
    attachments.value = attachments.value.filter(item => item.id !== attachment.id)
  } catch (reason: any) { error.value = reason.response?.data?.message || '附件移除失败' }
}

async function copyAnswer(message: Message, index: number) {
  await navigator.clipboard.writeText(message.content)
  copiedMessageId.value = message.id || String(index)
  window.setTimeout(() => { copiedMessageId.value = '' }, 1600)
}

async function regenerate(index: number) {
  if (loading.value) return
  for (let cursor = index - 1; cursor >= 0; cursor--) {
    if (messages.value[cursor].role === 'USER') {
      const content = messages.value[cursor].content
      const matched = content.match(/^(IP查询|域名查询|URL查询|CVE查询|Hash查询)[：:]\s*(.+)$/i)
      if (matched) {
        const types: Record<string, QuickQueryType> = { IP查询: 'ip', 域名查询: 'domain', URL查询: 'url', CVE查询: 'cve', Hash查询: 'hash' }
        await executeQuickQuery(types[matched[1]], matched[2])
        return
      }
      draft.value = content
      await send()
      return
    }
  }
}

function openQuickQuery(type: QuickQueryType) {
  if (loading.value) return
  quickType.value = type
  quickValue.value = ''
  quickDialogOpen.value = true
  error.value = ''
  nextTick(() => quickInput.value?.focus())
}

function closeQuickQuery() {
  if (!loading.value) quickDialogOpen.value = false
}

async function submitQuickQuery() {
  const value = quickValue.value.trim()
  if (!value) return
  quickDialogOpen.value = false
  await executeQuickQuery(quickType.value, value)
}

async function executeQuickQuery(type: QuickQueryType, rawValue: string) {
  const value = rawValue.trim()
  if (!value || loading.value) return
  if (!activeId.value) await newConversation()
  const meta = quickQueryMeta[type]
  const userMessage: Message = { role: 'USER', content: `${meta.label.replace(' ', '')}：${value}`, status: 'COMPLETED', createdAt: new Date().toISOString() }
  const assistant: Message = { role: 'ASSISTANT', content: `## ${meta.label}进行中\n\n- 正在直连对应安全子工作流`, status: 'STREAMING' }
  messages.value.push(userMessage, assistant)
  loading.value = true; error.value = ''
  await scrollBottom()
  try {
    await streamRequest(`/conversations/${activeId.value}/queries/stream`, { type, value }, event => {
      if (event.type === 'status') assistant.content = `## ${meta.label}进行中\n\n- ${event.message}`
      if (event.type === 'replace') assistant.content = event.answer || assistant.content
      if (event.type === 'done') { assistant.content = event.answer || assistant.content; assistant.status = 'COMPLETED' }
      if (event.type === 'error') throw new Error(event.error || `${meta.label}失败`)
      scrollBottom()
    })
    await loadConversations()
  } catch (reason: any) {
    assistant.status = 'FAILED'
    assistant.content = `## ${meta.label}失败\n\n${reason.message || '对应子工作流暂不可用'}`
    error.value = reason.message
  } finally { loading.value = false; await scrollBottom() }
}

function formatBytes(value: number) {
  return value < 1024 * 1024 ? `${Math.max(1, Math.round(value / 1024))} KB` : `${(value / 1024 / 1024).toFixed(1)} MB`
}
async function removeConversation(id: string) {
  if (!window.confirm('确定删除这条会话吗？')) return
  await api.delete(`/conversations/${id}`)
  conversations.value = conversations.value.filter(item => item.id !== id)
  if (activeId.value === id) { activeId.value = ''; messages.value = []; await router.push('/workspace') }
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
      <nav class="sidebar-section tools"><p>快捷查询</p><button @click="openQuickQuery('ip')"><Globe2 /><span>IP 情报查询</span></button><button @click="openQuickQuery('domain')"><Shield /><span>域名研判</span></button><button @click="openQuickQuery('url')"><Link2 /><span>URL 检测</span></button><button @click="openQuickQuery('cve')"><FileSearch /><span>CVE 漏洞查询</span></button><button @click="openQuickQuery('hash')"><Hash /><span>Hash 威胁查询</span></button></nav>
      <nav class="sidebar-section resource-links"><p>快捷入口</p><a href="https://sec.ha.edu.cn" target="_blank" rel="noopener noreferrer"><span>河南省教育信息安全检测中心</span><ExternalLink :size="13" /></a><a href="https://www.cnnvd.org.cn/" target="_blank" rel="noopener noreferrer"><span>国家信息安全漏洞库（CNNVD）</span><ExternalLink :size="13" /></a></nav>
      <section class="conversation-section"><p>最近会话</p><div class="conversation-list"><article v-for="item in filteredConversations" :key="item.id" :class="{ active: item.id === activeId }" @click="selectConversation(item.id)"><span class="conversation-title">{{ item.title }}</span><small>{{ formatTime(item.lastMessageAt || item.createdAt) }}</small><button title="删除会话" @click.stop="removeConversation(item.id)"><X :size="13" /></button></article><div v-if="!filteredConversations.length" class="sidebar-empty">暂无会话记录</div></div></section>
      <div class="account-zone"><button class="account-button" @click="accountOpen = !accountOpen"><span class="user-avatar"><img v-if="auth.user?.avatarUrl" :src="`${auth.user.avatarUrl}?v=${auth.user.id}`" />{{ auth.user?.avatarUrl ? '' : initials }}</span><span><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.organizationName }}</small></span><ChevronDown :size="16" /></button><div v-if="accountOpen" class="account-menu"><RouterLink to="/settings/profile"><UserRound :size="16" />个人资料</RouterLink><RouterLink to="/settings/profile?tab=security"><Settings :size="16" />账号与安全</RouterLink><RouterLink v-if="['SUPER_ADMIN','ORG_ADMIN'].includes(auth.user?.role || '')" to="/admin/users"><ShieldCheck :size="16" />用户管理</RouterLink><button @click="logout"><LogOut :size="16" />退出登录</button></div></div>
    </aside>

    <section class="workspace-shell">
      <header class="workspace-topbar"><button class="icon-button mobile-menu" @click="sidebarOpen = !sidebarOpen"><Menu /></button><div class="workspace-heading"><span class="online-dot"></span><div><strong>{{ activeConversation?.title || '安全研判工作台' }}</strong><small>网络安全垂域智能体 · 在线</small></div></div><div class="topbar-org"><ShieldCheck :size="16" />河南省教育科研计算机网络中心</div></header>
      <section ref="messageArea" class="message-area">
        <div v-if="showWelcome" class="welcome-state"><span class="eyebrow"><i></i> 河南教育科研网安全研判空间</span><h1>今天想研判什么？</h1><p>复杂事件可直接交给主 Agent；IOC 指标建议使用下方快捷查询，直接连接对应子工作流。</p><form class="composer-box welcome-composer" @submit.prevent="send"><div v-if="attachments.length" class="pending-attachments"><div v-for="item in attachments" :key="item.id" class="pending-attachment"><ImageIcon v-if="item.contentType.startsWith('image/')" :size="16" /><FileText v-else :size="16" /><span><strong>{{ item.name }}</strong><small>{{ formatBytes(item.sizeBytes) }}</small></span><button type="button" title="移除附件" @click="removeAttachment(item)"><X :size="14" /></button></div></div><textarea ref="input" v-model="draft" rows="1" placeholder="输入研判目标，或粘贴图片和文档…" @paste="pasteFiles" @keydown.enter.exact.prevent="send"></textarea><div class="composer-footer"><input ref="fileInput" class="visually-hidden" type="file" multiple accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.txt,.csv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx" @change="chooseFiles" /><button type="button" class="attach-button" :disabled="uploading || attachments.length >= 3" @click="fileInput?.click()"><Paperclip :size="16" />{{ uploading ? '上传中…' : '附件' }}</button><button class="send-button" :disabled="loading || uploading || (!draft.trim() && !attachments.length)" aria-label="发送"><Send :size="18" /></button></div></form><p v-if="error" class="workspace-error welcome-error">{{ error }}</p><div class="starter-grid"><button @click="openQuickQuery('ip')"><Globe2 /><span><strong>IP 查询</strong><small>VT 与微步双源情报</small></span></button><button @click="openQuickQuery('domain')"><Shield /><span><strong>域名查询</strong><small>解析与信誉研判</small></span></button><button @click="openQuickQuery('url')"><Link2 /><span><strong>URL 检测</strong><small>恶意链接检测</small></span></button><button @click="openQuickQuery('cve')"><FileSearch /><span><strong>CVE 查询</strong><small>漏洞影响与修复</small></span></button><button @click="openQuickQuery('hash')"><Hash /><span><strong>Hash 查询</strong><small>样本威胁情报</small></span></button></div></div>
        <div v-else class="message-thread"><article v-for="(message, index) in messages" :key="message.id || index" class="chat-message" :class="message.role.toLowerCase()"><div class="message-avatar"><Bot v-if="message.role === 'ASSISTANT'" :size="19" /><span v-else>{{ initials }}</span></div><div class="message-content"><div class="message-meta"><strong>{{ message.role === 'ASSISTANT' ? '网络安全垂域智能体' : auth.user?.displayName }}</strong><span v-if="message.status === 'STREAMING'" class="streaming-label">分析中</span><span v-if="message.status === 'FAILED'" class="failed-label">执行失败</span></div><div v-if="message.role === 'ASSISTANT'" class="markdown" v-html="renderMarkdown(message.content)"></div><p v-else>{{ message.content }}</p><div v-if="message.attachments?.length" class="message-attachments"><a v-for="item in message.attachments" :key="item.id" :href="item.downloadUrl" target="_blank"><ImageIcon v-if="item.contentType.startsWith('image/')" :size="15" /><FileText v-else :size="15" /><span>{{ item.name }}</span><small>{{ formatBytes(item.sizeBytes) }}</small></a></div><div v-if="message.role === 'ASSISTANT' && message.status !== 'STREAMING'" class="message-actions"><button type="button" @click="regenerate(index)"><RotateCcw :size="15" />重新生成</button><button type="button" @click="copyAnswer(message, index)"><Check v-if="copiedMessageId === (message.id || String(index))" :size="15" /><Copy v-else :size="15" />{{ copiedMessageId === (message.id || String(index)) ? '已复制' : '复制回答' }}</button></div></div></article></div>
      </section>
      <footer v-if="activeId && messages.length" class="composer-zone"><form class="composer-box" @submit.prevent="send"><div v-if="attachments.length" class="pending-attachments"><div v-for="item in attachments" :key="item.id" class="pending-attachment"><ImageIcon v-if="item.contentType.startsWith('image/')" :size="16" /><FileText v-else :size="16" /><span><strong>{{ item.name }}</strong><small>{{ formatBytes(item.sizeBytes) }}</small></span><button type="button" title="移除附件" @click="removeAttachment(item)"><X :size="14" /></button></div></div><textarea ref="input" v-model="draft" rows="1" placeholder="输入研判目标，或粘贴图片和文档…" @paste="pasteFiles" @keydown.enter.exact.prevent="send"></textarea><div class="composer-footer"><input ref="fileInput" class="visually-hidden" type="file" multiple accept=".png,.jpg,.jpeg,.webp,.gif,.pdf,.txt,.csv,.json,.doc,.docx,.xls,.xlsx,.ppt,.pptx" @change="chooseFiles" /><button type="button" class="attach-button" :disabled="uploading || attachments.length >= 3" @click="fileInput?.click()"><Paperclip :size="16" />{{ uploading ? '上传中…' : '附件' }}</button><button class="send-button" :disabled="loading || uploading || (!draft.trim() && !attachments.length)" aria-label="发送"><Send :size="18" /></button></div></form><p v-if="error" class="workspace-error">{{ error }}</p></footer>
    </section>
    <div v-if="quickDialogOpen" class="quick-dialog-backdrop" @click.self="closeQuickQuery" @keydown.esc="closeQuickQuery"><section class="quick-dialog" role="dialog" aria-modal="true" :aria-label="activeQuickMeta.label"><header><div class="quick-dialog-icon"><Globe2 v-if="quickType === 'ip'" /><Shield v-else-if="quickType === 'domain'" /><Link2 v-else-if="quickType === 'url'" /><FileSearch v-else-if="quickType === 'cve'" /><Hash v-else /></div><div><small>QUICK WORKFLOW</small><h2>{{ activeQuickMeta.label }}</h2></div><button type="button" aria-label="关闭" @click="closeQuickQuery"><X :size="19" /></button></header><p>{{ activeQuickMeta.hint }}，结果将保存到当前会话。</p><form @submit.prevent="submitQuickQuery"><label>{{ activeQuickMeta.prompt }}</label><input ref="quickInput" v-model="quickValue" :placeholder="activeQuickMeta.placeholder" autocomplete="off" /><div class="quick-dialog-actions"><button type="button" @click="closeQuickQuery">取消</button><button type="submit" :disabled="!quickValue.trim() || loading"><Search :size="16" />立即查询</button></div></form></section></div>
  </main>
</template>
