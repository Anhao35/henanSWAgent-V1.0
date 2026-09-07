<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, BookOpen, Bookmark, BookmarkCheck, Bot, CalendarDays, ChevronDown, Database, ExternalLink,
  FileText, GraduationCap, History, LibraryBig, LoaderCircle, LogOut, Search, Settings,
  ShieldCheck, Sparkles, Trash2, UserRound,
} from '@lucide/vue'
import BrandMark from '../components/BrandMark.vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
import type { ResearchHistory, ResearchItem, ResearchSearchResponse } from '../types'

const auth = useAuthStore()
const router = useRouter()
const query = ref('')
const type = ref('ALL')
const source = ref('ALL')
const yearFrom = ref('')
const yearTo = ref('')
const openAccessOnly = ref(false)
const loading = ref(false)
const error = ref('')
const response = ref<ResearchSearchResponse>()
const history = ref<ResearchHistory[]>([])
const saved = ref<ResearchItem[]>([])
const activeTab = ref<'search' | 'history' | 'saved'>('search')
const accountOpen = ref(false)
const availableSources = ref(new Set<string>(['ARXIV', 'CROSSREF']))

const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || '用')
const savedKeys = computed(() => new Set(saved.value.map(item => `${item.source}|${item.sourceId}`)))
const suggestions = ['大语言模型在网络安全事件研判中的应用', 'APT 攻击检测与威胁情报融合', '教育行业数据泄露风险治理', '零信任架构安全研究']
const sourceLabels: Record<string, string> = {
  OPENALEX: 'OpenAlex', CROSSREF: 'Crossref', ARXIV: 'arXiv', OPEN_LIBRARY: 'Open Library',
  SEMANTIC_SCHOLAR: 'Semantic Scholar',
}
const enabledSourceList = computed(() => Array.from(availableSources.value)
  .filter(code => sourceLabels[code])
  .sort((left, right) => ['OPENALEX', 'SEMANTIC_SCHOLAR', 'ARXIV', 'CROSSREF', 'OPEN_LIBRARY'].indexOf(left)
    - ['OPENALEX', 'SEMANTIC_SCHOLAR', 'ARXIV', 'CROSSREF', 'OPEN_LIBRARY'].indexOf(right)))

function sourceName(value: string) {
  return value.split(',').map(item => sourceLabels[item] || item).join(' · ')
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function pickSuggestion(value: string) {
  query.value = value
  void searchResearch()
}

async function searchResearch(value?: string) {
  if (value) query.value = value
  if (query.value.trim().length < 2 || loading.value) return
  loading.value = true
  error.value = ''
  activeTab.value = 'search'
  try {
    const { data } = await api.post<ResearchSearchResponse>('/research/search', {
      query: query.value.trim(),
      type: type.value,
      sources: source.value === 'ALL' ? [] : [source.value],
      yearFrom: yearFrom.value ? Number(yearFrom.value) : null,
      yearTo: yearTo.value ? Number(yearTo.value) : null,
      openAccessOnly: openAccessOnly.value,
      limit: 24,
    }, { timeout: 45_000 })
    response.value = data
    await loadHistory()
  } catch (reason: any) {
    error.value = reason.response?.data?.message || reason.message || '资料检索失败'
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  const { data } = await api.get<ResearchHistory[]>('/research/history?limit=40')
  history.value = data
}

async function loadSaved() {
  const { data } = await api.get<ResearchItem[]>('/research/saved')
  saved.value = data
}

async function saveItem(item: ResearchItem) {
  if (savedKeys.value.has(`${item.source}|${item.sourceId}`)) return
  try {
    await api.post('/research/saved', item)
    await loadSaved()
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '收藏失败'
  }
}

async function removeSaved(item: ResearchItem) {
  if (!item.id) return
  await api.delete(`/research/saved/${item.id}`)
  await loadSaved()
}

function withoutWebLinks(value?: string) {
  return value
    ?.replace(/https?:\/\/[^\s<>"'，。；、)）\]}]+/gi, '（来源链接已保留在资料检索页）')
    .replace(/\bwww\.[^\s<>"'，。；、)）\]}]+/gi, '（来源链接已保留在资料检索页）')
    .trim() || ''
}

function academicIdentifier(value?: string) {
  const identifier = value?.trim()
  if (!identifier) return ''
  const withoutDoiUrl = identifier.replace(/^https?:\/\/(?:dx\.)?doi\.org\//i, '').replace(/^doi:\s*/i, '')
  return /^https?:\/\//i.test(withoutDoiUrl) ? '' : withoutDoiUrl
}

async function askAgent(item: ResearchItem) {
  const identifier = academicIdentifier(item.doi)
  const details = [
    '这是一项学术资料解读任务，请仅围绕论文或图书内容作答，不要执行网络安全 IOC 查询。',
    '请明确区分资料原文信息与推断，并说明当前结论是否仅基于题录和摘要：',
    `题名：${withoutWebLinks(item.title)}`,
    item.authors?.length ? `作者：${withoutWebLinks(item.authors.join('、'))}` : '',
    item.publicationYear ? `年份：${item.publicationYear}` : '',
    identifier ? `DOI/ISBN 标识符：${identifier}` : '',
    item.abstractText ? `摘要：${item.abstractText}` : '',
    item.sourceUrl ? `来源链接（引用，不是检测目标）：${item.sourceUrl}` : '',
    '当前提供的是题录与摘要，不代表已获取全文。请基于实际提供的内容解读，缺失细节请说明。',
  ].filter(Boolean).join('\n')
  await router.push({ path: '/workspace', query: { prompt: details, taskMode: 'READ' } })
}

function switchTab(tab: 'search' | 'history' | 'saved') {
  activeTab.value = tab
  if (tab === 'history') void loadHistory()
  if (tab === 'saved') void loadSaved()
}

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

onMounted(async () => {
  const sourceRequest = api.get<Array<{ code: string; available: boolean }>>('/research/sources').then(({ data }) => {
    availableSources.value = new Set(data.filter(item => item.available).map(item => item.code))
  })
  await Promise.all([loadHistory(), loadSaved(), sourceRequest])
})
</script>

<template>
  <main class="app-shell research-page">
    <aside class="app-sidebar research-sidebar">
      <div class="sidebar-brand"><BrandMark /><div><small>HERCERT</small><strong>省网智能体</strong></div></div>
      <RouterLink class="new-chat research-back" to="/workspace"><ArrowLeft :size="18" /><span>返回安全研判</span></RouterLink>
      <nav class="research-nav">
        <button :class="{ active: activeTab === 'search' }" @click="switchTab('search')"><Search /><span>资料检索</span></button>
        <button :class="{ active: activeTab === 'history' }" @click="switchTab('history')"><History /><span>检索记录</span><small>{{ history.length }}</small></button>
        <button :class="{ active: activeTab === 'saved' }" @click="switchTab('saved')"><Bookmark /><span>我的收藏</span><small>{{ saved.length }}</small></button>
      </nav>
      <section class="research-source-note">
        <strong><ShieldCheck :size="15" /> 数据源说明</strong>
        <p>当前只调用公开或免费额度接口，不抓取收费数据库页面。</p>
        <div><i></i>公开源在线</div>
      </section>
      <nav class="sidebar-section resource-links research-links"><p>机构与扩展检索</p>
        <a :href="`https://scholar.google.com/scholar?q=${encodeURIComponent(query)}`" target="_blank" rel="noopener noreferrer"><span>Google Scholar</span><ExternalLink :size="13" /></a>
        <a href="https://findlib.zzu.edu.cn/" target="_blank" rel="noopener noreferrer"><span>郑州大学图书馆</span><ExternalLink :size="13" /></a>
        <a href="https://www.cnki.net/" target="_blank" rel="noopener noreferrer"><span>中国知网（需授权）</span><ExternalLink :size="13" /></a>
        <a href="https://www.wanfangdata.com.cn/" target="_blank" rel="noopener noreferrer"><span>万方数据（需授权）</span><ExternalLink :size="13" /></a>
      </nav>
      <div class="account-zone"><button class="account-button" @click="accountOpen = !accountOpen"><span class="user-avatar"><img v-if="auth.user?.avatarUrl" :src="`${auth.user.avatarUrl}?v=${auth.user.id}`" />{{ auth.user?.avatarUrl ? '' : initials }}</span><span><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.organizationName }}</small></span><ChevronDown :size="16" /></button><div v-if="accountOpen" class="account-menu"><RouterLink to="/settings/profile"><UserRound :size="16" />个人资料</RouterLink><RouterLink to="/settings/profile?tab=security"><Settings :size="16" />账号与安全</RouterLink><button @click="logout"><LogOut :size="16" />退出登录</button></div></div>
    </aside>

    <section class="research-main campus-research">
      <header class="workspace-topbar"><div class="workspace-heading"><span class="online-dot"></span><div><strong>学术资料检索</strong><small>公开学术资源聚合 · 用户检索记录已隔离</small></div></div><div class="topbar-org"><ShieldCheck :size="16" />河南省教育科研计算机网络中心</div></header>
      <div class="research-scroll">
        <section class="research-hero">
          <div class="research-campus-art" aria-hidden="true"><img src="/assets/zzu-campus-lineart.png" alt="" /><i></i></div>
          <div class="research-identity-row">
            <span class="research-campus-signature"><span><BrandMark /></span><b>郑州大学学术资源发现</b><small>ZZU RESEARCH DISCOVERY</small></span>
            <span class="research-live"><i></i>{{ enabledSourceList.length }} 个公开数据源在线</span>
          </div>
          <span class="eyebrow"><i></i> 求是 · 担当 · 开放求证</span>
          <h1>从一个研究问题，<span>找到可信资料</span></h1>
          <p>不知道 DOI 和作者也可以检索。系统并行连接公开论文与图书数据源，聚合题录、摘要、引用与开放全文，并保留真实出处。</p>
          <div class="research-source-strip"><Database :size="14" /><span>已接入</span><b v-for="code in enabledSourceList" :key="code"><i></i>{{ sourceLabels[code] }}</b></div>
          <form class="research-search-box" @submit.prevent="searchResearch()"><Search :size="21" /><input v-model="query" placeholder="例如：查找近五年大语言模型用于网络安全事件研判的研究" autofocus /><button :disabled="loading || query.trim().length < 2"><LoaderCircle v-if="loading" class="spin" :size="18" /><Search v-else :size="18" />{{ loading ? '检索中' : '开始检索' }}</button></form>
          <div class="research-suggestions"><Sparkles :size="15" /><span>试试：</span><button v-for="item in suggestions" :key="item" @click="pickSuggestion(item)">{{ item }}</button></div>
          <div class="research-filters">
            <label>资料类型<select v-model="type"><option value="ALL">论文与图书</option><option value="PAPER">仅论文</option><option value="BOOK">仅图书</option></select></label>
            <label>数据来源<select v-model="source"><option value="ALL">全部已启用来源</option><option value="OPENALEX" :disabled="!availableSources.has('OPENALEX')">OpenAlex{{ availableSources.has('OPENALEX') ? '' : '（需免费 Key）' }}</option><option value="SEMANTIC_SCHOLAR" :disabled="!availableSources.has('SEMANTIC_SCHOLAR')">Semantic Scholar{{ availableSources.has('SEMANTIC_SCHOLAR') ? '' : '（需免费 Key）' }}</option><option value="ARXIV">arXiv</option><option value="CROSSREF">Crossref</option><option value="OPEN_LIBRARY" :disabled="!availableSources.has('OPEN_LIBRARY')">Open Library{{ availableSources.has('OPEN_LIBRARY') ? '' : '（当前网络未启用）' }}</option></select></label>
            <label>起始年份<input v-model="yearFrom" type="number" min="1900" :max="new Date().getFullYear()" placeholder="不限" /></label>
            <label>结束年份<input v-model="yearTo" type="number" min="1900" :max="new Date().getFullYear()" placeholder="不限" /></label>
            <label class="oa-filter"><input v-model="openAccessOnly" type="checkbox" /><span>仅开放获取</span></label>
          </div>
        </section>

        <p v-if="error" class="research-alert error">{{ error }}</p>
        <template v-if="activeTab === 'search'">
          <div v-if="response" class="research-summary"><div><strong>找到 {{ response.total }} 条去重结果</strong><span>耗时 {{ (response.durationMs / 1000).toFixed(1) }} 秒<span v-if="response.cached"> · 来自缓存</span></span></div><p v-for="warning in response.warnings" :key="warning">{{ warning }}</p></div>
          <section v-if="response?.items.length" class="research-results">
            <article v-for="(item, index) in response.items" :key="`${item.source}-${item.sourceId}`" class="research-card" :style="{ '--reveal-delay': `${Math.min(index, 12) * 45}ms` }">
              <div class="research-rank"><FileText v-if="item.itemType === 'PAPER'" :size="18" /><BookOpen v-else :size="18" /></div>
              <div class="research-card-body"><div class="research-badges"><span>{{ sourceName(item.source) }}</span><span>{{ item.itemType === 'PAPER' ? '论文' : '图书' }}</span><span v-if="item.openAccessUrl" class="open-access">开放获取</span></div><h2>{{ item.title }}</h2><p class="research-authors">{{ item.authors?.slice(0, 6).join('、') || '作者信息暂缺' }}</p><div class="research-meta"><span v-if="item.publicationYear"><CalendarDays :size="14" />{{ item.publicationYear }}</span><span v-if="item.venue"><GraduationCap :size="14" />{{ item.venue }}</span><span v-if="item.citationCount !== undefined && item.citationCount !== null">被引 {{ item.citationCount }}</span><span v-if="item.doi">DOI/ISBN {{ item.doi }}</span></div><p v-if="item.abstractText" class="research-abstract">{{ item.abstractText }}</p><div class="research-card-actions"><a v-if="item.openAccessUrl || item.sourceUrl" :href="item.openAccessUrl || item.sourceUrl" target="_blank" rel="noopener noreferrer"><ExternalLink :size="15" />{{ item.openAccessUrl ? '查看开放全文' : '查看来源' }}</a><button @click="askAgent(item)"><Bot :size="15" />交给智能体</button><button :disabled="savedKeys.has(`${item.source}|${item.sourceId}`)" @click="saveItem(item)"><BookmarkCheck v-if="savedKeys.has(`${item.source}|${item.sourceId}`)" :size="15" /><Bookmark v-else :size="15" />{{ savedKeys.has(`${item.source}|${item.sourceId}`) ? '已收藏' : '收藏' }}</button></div></div>
            </article>
          </section>
          <section v-else-if="!loading" class="research-empty"><LibraryBig :size="42" /><h2>输入一个主题开始检索</h2><p>第一版已支持公开论文、预印本、元数据与图书资源，不需要任何付费 API Key。</p></section>
        </template>

        <section v-else-if="activeTab === 'history'" class="research-list-panel"><header><div><h2>检索记录</h2><p>这些记录仅属于当前登录账号。</p></div><History :size="24" /></header><button v-for="item in history" :key="item.id" class="history-row" @click="searchResearch(item.query)"><span><strong>{{ item.query }}</strong><small>{{ sourceName(item.sources || '') }}</small></span><span><strong>{{ item.resultCount }} 条</strong><small>{{ formatDate(item.createdAt) }}</small></span><Search :size="16" /></button><div v-if="!history.length" class="research-empty compact">暂无检索记录</div></section>

        <section v-else class="research-list-panel"><header><div><h2>我的收藏</h2><p>收藏的论文与图书可随时交给智能体继续分析。</p></div><Bookmark :size="24" /></header><article v-for="item in saved" :key="item.id" class="saved-row"><div><span>{{ sourceName(item.source) }}</span><h3>{{ item.title }}</h3><p>{{ item.authors?.join('、') }}<template v-if="item.publicationYear"> · {{ item.publicationYear }}</template></p></div><div><a v-if="item.openAccessUrl || item.sourceUrl" :href="item.openAccessUrl || item.sourceUrl" target="_blank"><ExternalLink :size="15" /></a><button title="交给智能体" @click="askAgent(item)"><Bot :size="15" /></button><button title="取消收藏" @click="removeSaved(item)"><Trash2 :size="15" /></button></div></article><div v-if="!saved.length" class="research-empty compact">还没有收藏资料</div></section>
      </div>
    </section>
  </main>
</template>
