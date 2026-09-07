<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle, ArrowLeft, BookOpenCheck, BrainCircuit, Bug, CheckCircle2, ChevronDown,
  ExternalLink, FileWarning, GitBranch, History, LoaderCircle, LogOut, RotateCcw,
  Settings, ShieldCheck, Sparkles, Target, UserRound,
} from '@lucide/vue'
import BrandMark from '../components/BrandMark.vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'
import type { MitreMappingHistory, MitreMappingResult } from '../types'

const auth = useAuthStore()
const router = useRouter()
const mappingType = ref<'ATTACK' | 'CWE'>('ATTACK')
const description = ref('')
const loading = ref(false)
const error = ref('')
const result = ref<MitreMappingResult>()
const history = ref<MitreMappingHistory[]>([])
const accountOpen = ref(false)
const externalConfirmed = ref(false)

const initials = computed(() => auth.user?.displayName?.slice(0, 1) || auth.user?.username?.slice(0, 1) || '用')
const mappingCount = computed(() => result.value
  ? result.value.attackMappings.length + result.value.cweMappings.length
  : 0)
const examples = {
  ATTACK: '攻击者向财务人员发送带有恶意宏的 Excel 附件。用户启用宏后，PowerShell 从远程服务器下载后门程序，随后攻击者通过该后门执行命令并收集浏览器凭据。',
  CWE: 'Web 应用将用户提交的搜索关键词直接拼接到 HTML 页面中，没有进行输出编码。攻击者可以提交包含 script 标签的内容，使脚本在其他用户浏览器中执行。',
}

function switchType(type: 'ATTACK' | 'CWE') {
  mappingType.value = type
  result.value = undefined
  error.value = ''
}

function useExample() {
  description.value = examples[mappingType.value]
}

async function submit() {
  const text = description.value.trim()
  if (text.length < 20 || loading.value) return
  loading.value = true
  error.value = ''
  result.value = undefined
  try {
    const { data } = await api.post<MitreMappingResult>('/mitre/map', {
      type: mappingType.value,
      description: text,
    }, { timeout: 200_000 })
    result.value = data
    await loadHistory()
  } catch (reason: any) {
    error.value = reason.response?.data?.message || reason.message || 'MITRE 映射失败'
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  const { data } = await api.get<MitreMappingHistory[]>('/mitre/history?limit=30')
  history.value = data
}

async function openHistory(item: MitreMappingHistory) {
  if (item.status !== 'COMPLETED') return
  error.value = ''
  try {
    const { data } = await api.get<MitreMappingResult>(`/mitre/history/${item.id}`)
    mappingType.value = data.type
    result.value = data
  } catch (reason: any) {
    error.value = reason.response?.data?.message || '无法读取映射记录'
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function confidence(value: number) {
  return `${Math.round(value * 100)}%`
}

function confidenceClass(value: number) {
  return value >= .8 ? 'high' : value >= .6 ? 'medium' : 'low'
}

function confidenceWidth(value: number) {
  return `${Math.max(0, Math.min(100, Math.round(value * 100)))}%`
}

function attackUrl(id: string) {
  return `https://attack.mitre.org/techniques/${id.replace('.', '/')}/`
}

function cweUrl(id: string) {
  return `https://cwe.mitre.org/data/definitions/${id.replace(/^CWE-/i, '')}.html`
}

async function logout() {
  await auth.logout()
  await router.replace('/login')
}

onMounted(() => void loadHistory())
</script>

<template>
  <main class="app-shell mitre-page">
    <aside class="app-sidebar mitre-sidebar">
      <div class="sidebar-brand"><BrandMark /><div><small>HERCERT</small><strong>省网智能体</strong></div></div>
      <RouterLink class="new-chat research-back" to="/workspace"><ArrowLeft :size="18" /><span>返回安全研判</span></RouterLink>
      <nav class="mitre-mode-nav">
        <p>映射目标</p>
        <button :class="{ active: mappingType === 'ATTACK' }" @click="switchType('ATTACK')"><Target /><span><strong>ATT&CK 映射</strong><small>攻击行为 → 战术与技术</small></span></button>
        <button :class="{ active: mappingType === 'CWE' }" @click="switchType('CWE')"><Bug /><span><strong>CWE 匹配</strong><small>漏洞描述 → 弱点类别</small></span></button>
      </nav>
      <section class="mitre-history">
        <p><History :size="14" />最近映射</p>
        <div class="mitre-history-list">
          <button v-for="item in history" :key="item.id" :disabled="item.status !== 'COMPLETED'" @click="openHistory(item)">
            <span><i :class="item.type.toLowerCase()">{{ item.type === 'ATTACK' ? 'A' : 'C' }}</i><strong>{{ item.inputPreview }}</strong></span>
            <small>{{ item.status === 'COMPLETED' ? `${item.mappingCount} 项 · ${formatDate(item.createdAt)}` : '执行失败' }}</small>
          </button>
          <div v-if="!history.length" class="sidebar-empty">暂无映射记录</div>
        </div>
      </section>
      <div class="account-zone"><button class="account-button" @click="accountOpen = !accountOpen"><span class="user-avatar"><img v-if="auth.user?.avatarUrl" :src="`${auth.user.avatarUrl}?v=${auth.user.id}`" />{{ auth.user?.avatarUrl ? '' : initials }}</span><span><strong>{{ auth.user?.displayName }}</strong><small>{{ auth.user?.organizationName }}</small></span><ChevronDown :size="16" /></button><div v-if="accountOpen" class="account-menu"><RouterLink to="/settings/profile"><UserRound :size="16" />个人资料</RouterLink><RouterLink to="/settings/profile?tab=security"><Settings :size="16" />账号与安全</RouterLink><button @click="logout"><LogOut :size="16" />退出登录</button></div></div>
    </aside>

    <section class="mitre-main">
      <header class="workspace-topbar"><div class="workspace-heading"><span class="online-dot"></span><div><strong>MITRE 智能映射</strong><small>自然语言威胁描述 → ATT&CK / CWE 结构化结果</small></div></div><div class="topbar-org"><ShieldCheck :size="16" />河南省教育科研计算机网络中心</div></header>
      <div class="mitre-scroll">
        <section class="mitre-hero">
          <span class="eyebrow"><i></i> THREAT KNOWLEDGE MAPPING</span>
          <h1>把攻击描述，转换成可验证的 MITRE 知识</h1>
          <p>系统会提取原文行为和证据，再给出候选映射。模型结果用于辅助研判，关键结论仍应由分析人员复核。</p>
        </section>

        <section class="mitre-input-card">
          <div class="mitre-type-switch">
            <button :class="{ active: mappingType === 'ATTACK' }" @click="switchType('ATTACK')"><GitBranch :size="17" />MITRE ATT&CK</button>
            <button :class="{ active: mappingType === 'CWE' }" @click="switchType('CWE')"><Bug :size="17" />MITRE CWE</button>
          </div>
          <div class="mitre-input-heading"><div><h2>{{ mappingType === 'ATTACK' ? '输入网络攻击或事件描述' : '输入漏洞或软件缺陷描述' }}</h2><p>{{ mappingType === 'ATTACK' ? '描述攻击者做了什么、通过什么方式以及造成什么结果。' : '尽量描述漏洞根因、输入位置、缺失的安全控制和可能影响。' }}</p></div><button class="example-button" @click="useExample"><Sparkles :size="15" />填入示例</button></div>
          <textarea v-model="description" maxlength="30000" :placeholder="mappingType === 'ATTACK' ? '例如：攻击者发送带有恶意宏的附件，用户打开后宏调用 PowerShell 下载后门……' : '例如：应用将未经验证的用户输入直接拼接到 SQL 查询中……'"></textarea>
          <label class="external-model-notice"><input v-model="externalConfirmed" type="checkbox" /><span><strong>外部模型调用确认</strong>输入内容将发送至 DeepSeek API 进行映射，请勿提交未授权外发的敏感数据、口令或密钥。</span></label>
          <div class="mitre-input-footer"><span :class="{ warning: description.length > 28000 }">{{ description.length.toLocaleString() }} / 30,000</span><button :disabled="description.trim().length < 20 || !externalConfirmed || loading" @click="submit"><LoaderCircle v-if="loading" class="spin" :size="18" /><BrainCircuit v-else :size="18" />{{ loading ? '正在分析与校验…' : '开始智能映射' }}</button></div>
        </section>

        <p v-if="error" class="research-alert error mitre-alert"><AlertTriangle :size="15" />{{ error }}</p>

        <section v-if="loading" class="mitre-loading">
          <div class="mitre-loader"><BrainCircuit :size="30" /><i></i></div><h2>正在理解攻击语义</h2><p>提取行为链、生成候选映射，并检查编号格式与原文证据。</p>
        </section>

        <template v-else-if="result">
          <section class="mitre-result-summary">
            <div class="summary-icon"><BookOpenCheck /></div>
            <div><span>映射概览</span><h2>{{ result.summary || '已完成映射分析' }}</h2><p>{{ result.model }} · {{ mappingCount }} 项候选结果<span v-if="result.cached"> · 已复用缓存</span><span v-if="result.promptTokens || result.completionTokens"> · {{ (result.promptTokens || 0) + (result.completionTokens || 0) }} tokens</span></p></div>
          </section>

          <section v-if="result.attackBehaviors.length" class="mitre-section">
            <header><div><span>01</span><h2>攻击行为链</h2></div><p>按照原始描述中的行为顺序提取</p></header>
            <ol class="behavior-flow"><li v-for="(item, index) in result.attackBehaviors" :key="index"><i>{{ index + 1 }}</i><p>{{ item }}</p></li></ol>
          </section>

          <section v-if="result.attackMappings.length" class="mitre-section">
            <header><div><span>{{ result.attackBehaviors.length ? '02' : '01' }}</span><h2>ATT&CK 候选映射</h2></div><p>点击技术编号可前往 MITRE 官方页面核验</p></header>
            <div class="mapping-grid">
              <article v-for="(item, index) in result.attackMappings" :key="`${item.techniqueId}-${index}`" class="mapping-card">
                <div class="mapping-card-top"><div><span class="mapping-kind">{{ item.tacticId || 'ATT&CK' }} · {{ item.tacticName || '战术待核验' }}</span><a v-if="item.identifierFormatValid" :href="attackUrl(item.techniqueId)" target="_blank" rel="noopener noreferrer">{{ item.techniqueId }}<ExternalLink :size="13" /></a><strong v-else>{{ item.techniqueId }}</strong></div><div class="confidence-meter" :class="confidenceClass(item.confidence)"><span class="confidence">置信度 {{ confidence(item.confidence) }}</span><i><b :style="{ width: confidenceWidth(item.confidence) }"></b></i></div></div>
                <h3>{{ item.techniqueName || '技术名称待核验' }}</h3>
                <div class="mapping-evidence"><span><CheckCircle2 v-if="item.evidenceMatched" :size="14" /><AlertTriangle v-else :size="14" />{{ item.evidenceMatched ? '原文证据已匹配' : '证据需人工复核' }}</span><blockquote>{{ item.evidence || '模型未提供原文证据' }}</blockquote></div>
                <p>{{ item.reasoning }}</p>
              </article>
            </div>
          </section>

          <section v-if="result.cweMappings.length" class="mitre-section">
            <header><div><span>01</span><h2>CWE 候选匹配</h2></div><p>区分具体 CVE 与通用软件弱点类别</p></header>
            <div class="mapping-grid">
              <article v-for="(item, index) in result.cweMappings" :key="`${item.cweId}-${index}`" class="mapping-card cwe-card">
                <div class="mapping-card-top"><div><span class="mapping-kind">COMMON WEAKNESS ENUMERATION</span><a v-if="item.identifierFormatValid" :href="cweUrl(item.cweId)" target="_blank" rel="noopener noreferrer">{{ item.cweId }}<ExternalLink :size="13" /></a><strong v-else>{{ item.cweId }}</strong></div><div class="confidence-meter" :class="confidenceClass(item.confidence)"><span class="confidence">置信度 {{ confidence(item.confidence) }}</span><i><b :style="{ width: confidenceWidth(item.confidence) }"></b></i></div></div>
                <h3>{{ item.cweName || '弱点名称待核验' }}</h3>
                <div class="mapping-evidence"><span><CheckCircle2 v-if="item.evidenceMatched" :size="14" /><AlertTriangle v-else :size="14" />{{ item.evidenceMatched ? '原文证据已匹配' : '证据需人工复核' }}</span><blockquote>{{ item.evidence || '模型未提供原文证据' }}</blockquote></div>
                <p>{{ item.reasoning }}</p>
              </article>
            </div>
          </section>

          <section v-if="result.unmappedObservations.length" class="mitre-unmapped"><FileWarning :size="20" /><div><h3>暂不映射的观察</h3><ul><li v-for="item in result.unmappedObservations" :key="item">{{ item }}</li></ul></div></section>
          <section v-if="mappingCount === 0" class="mitre-no-mapping"><AlertTriangle :size="34" /><h2>没有足够证据形成可靠映射</h2><p>请补充攻击步骤、漏洞根因或具体行为后重试。系统不会为了填满结果而强行猜测编号。</p></section>
        </template>

        <section v-else class="mitre-empty-state"><div><GitBranch :size="40" /></div><h2>从一段自然语言描述开始</h2><p>支持中文或英文。建议提供完整上下文，而不是只输入“存在漏洞”或一个 CVE 编号。</p><button @click="useExample"><RotateCcw :size="15" />加载{{ mappingType === 'ATTACK' ? '攻击链' : '漏洞' }}示例</button></section>
      </div>
    </section>
  </main>
</template>
