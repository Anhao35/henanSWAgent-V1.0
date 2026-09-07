<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { ArrowLeft, FileText, Download, ShieldCheck, Save } from '@lucide/vue'
import BrandMark from '../components/BrandMark.vue'
import { api } from '../api'
import { useAuthStore } from '../stores/auth'

const route = useRoute(), auth = useAuthStore()
const templates = ref<any[]>([]), reports = ref<any[]>([]), selected = ref<any>()
const templateId = ref('builtin-detailed-v1'), title = ref('安全研判报告'), content = ref(''), error = ref(''), busy = ref(false)
const runId = ref(String(route.query.runId || '')), editing = ref(false), templatePanel = ref(false)
const templateName = ref('详细技术报告'), templateKey = ref('detailed')
const sections = ref(['scope', 'answer', 'evidence', 'limitations'])
const sectionNames: Record<string, string> = { scope: '任务范围', answer: '原始分析结果', evidence: '证据快照（必须）', remediation: '处置核验清单', limitations: '局限与复核（必须）' }
const preview = computed(() => DOMPurify.sanitize(marked.parse(content.value || '') as string))
const latestTemplates = computed(() => templates.value.filter((item, index, all) => all.findIndex(other => other.key === item.key) === index))
async function load() { const [a, b] = await Promise.all([api.get('/reports/templates'), api.get('/reports')]); templates.value = a.data; reports.value = b.data }
async function open(id: string) {
  try { const { data } = await api.get(`/reports/${id}`); selected.value = data; title.value = data.title; content.value = data.content; editing.value = false; error.value = '' }
  catch (e: any) { error.value = e.response?.data?.message || '无法读取报告' }
}
async function execute(action: () => Promise<any>) {
  busy.value = true; error.value = ''
  try { const { data } = await action(); await load(); if (data.id && data.content) await open(data.id) }
  catch (e: any) { error.value = e.response?.data?.message || '操作失败，请稍后重试' }
  finally { busy.value = false }
}
function generate() { void execute(() => api.post('/reports', { runId: runId.value, templateId: templateId.value, title: title.value })) }
function save(status: string) { void execute(() => api.post(`/reports/${selected.value.id}/versions`, { title: title.value, content: content.value, status })) }
function publish() { void execute(() => api.post('/reports/templates', { key: templateKey.value, name: templateName.value, sections: sections.value })) }
onMounted(() => load().catch(() => { error.value = '无法加载报告中心，请检查登录或后端服务' }))
</script>

<template>
  <main class="app-shell report-center">
    <aside class="app-sidebar">
      <div class="sidebar-brand"><BrandMark /><div><small>HERCERT</small><strong>证据与报告</strong></div></div>
      <RouterLink class="new-chat" to="/workspace"><ArrowLeft :size="18" />返回工作台</RouterLink>
      <div class="report-sidebar-caption">我的报告版本 · {{ reports.length }}</div>
      <nav class="report-history"><button v-for="report in reports" :key="report.id" :class="{ active: selected?.id === report.id }" @click="open(report.id)"><strong>{{ report.title }}</strong><small>V{{ report.version }} · {{ report.status === 'FINAL' ? '已确认' : '草稿' }} · {{ report.templateName }}</small></button><p v-if="!reports.length">暂无报告。完成研判后，点击回答下方“生成报告”。</p></nav>
      <button v-if="auth.user?.role === 'SUPER_ADMIN'" class="new-chat" @click="templatePanel = !templatePanel"><ShieldCheck :size="17" />模板版本管理</button>
    </aside>
    <section class="workspace-shell report-main">
      <header class="workspace-topbar"><RouterLink class="topbar-back" to="/workspace"><ArrowLeft :size="18" />工作台</RouterLink><div class="workspace-heading"><FileText :size="22" /><div><strong>报告中心</strong><small>复用真实证据 · 版本独立保存 · 不重新查询外部情报</small></div></div></header>
      <div class="report-body">
        <section class="report-intro"><span class="eyebrow">EVIDENCE → REPORT</span><h1>让每一份结论，都有据可查</h1><p>报告是证据的呈现方式，不是新的事实来源。保留原始分析、固定证据快照，再由人工复核。</p></section>
        <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
        <section v-if="runId" class="report-card report-generation"><h2>从本次运行生成草稿</h2><small>运行编号 {{ runId }}</small><label>报告标题<input v-model="title" maxlength="160" /></label><label>报告模板<select v-model="templateId"><option v-for="t in latestTemplates" :key="t.id" :value="t.id">{{ t.name }} · V{{ t.version }}</option></select></label><button class="primary-button" :disabled="busy || !title.trim()" @click="generate">{{ busy ? '处理中…' : '使用现有证据生成草稿' }}</button></section>
        <section v-if="templatePanel && auth.user?.role === 'SUPER_ADMIN'" class="report-card"><h2>发布新的模板版本</h2><p>不修改旧版本，不允许模板改变风险计算规则。证据与局限章节始终保留。</p><label>模板系列<select v-model="templateKey"><option value="detailed">详细技术报告</option><option value="brief">快速研判简报</option><option value="vulnerability">漏洞处置报告</option></select></label><label>显示名称<input v-model="templateName" maxlength="120" /></label><div class="report-sections"><label v-for="(name, key) in sectionNames" :key="key"><input v-model="sections" type="checkbox" :value="key" :disabled="['evidence', 'limitations'].includes(key)" />{{ name }}</label></div><button class="primary-button" :disabled="busy || !templateName.trim()" @click="publish">发布新版本（保留历史）</button></section>
        <section v-if="selected" class="report-card report-document"><header><div><h2>{{ selected.title }}</h2><small>报告 V{{ selected.version }} · {{ selected.status === 'FINAL' ? '人工确认版' : '待复核草稿' }} · {{ selected.templateName }} V{{ selected.templateVersion }}</small></div><button @click="editing = !editing">{{ editing ? '预览' : '编辑新版本' }}</button></header><div class="report-export"><a :href="`/api/reports/${selected.id}/export?format=md`"><Download :size="15" />Markdown</a><a :href="`/api/reports/${selected.id}/export?format=html`"><Download :size="15" />可打印 HTML</a><a :href="`/api/reports/${selected.id}/export?format=json`"><Download :size="15" />证据快照 JSON</a></div><p class="report-limit">HTML 下载后可用浏览器打印为 PDF。所有导出均绑定当前报告版本；没有提供独立的 Word 排版引擎。</p><template v-if="editing"><label>标题<input v-model="title" maxlength="160" /></label><textarea v-model="content" class="report-editor" maxlength="200000" /><div class="report-export"><button :disabled="busy" @click="save('DRAFT')"><Save :size="15" />另存草稿版本</button><button :disabled="busy" @click="save('FINAL')"><ShieldCheck :size="15" />确认并保存新版本</button></div></template><div v-else class="markdown" v-html="preview"></div><footer><strong>证据完整性指纹</strong><code>{{ selected.snapshotSha256 }}</code><small>指纹用于识别快照是否变化，不代表内容本身必然正确。原始证据不会随正文编辑改变。</small></footer></section>
        <section v-if="!selected && !runId" class="report-card report-empty"><FileText :size="40" /><h2>从一次完成的研判开始</h2><p>在聊天回答下方选择“生成报告”，或在左侧打开已有版本。旧会话没有结构化运行记录时，不会自动补造证据。</p><RouterLink to="/workspace">返回安全研判工作台 →</RouterLink></section>
      </div>
    </section>
  </main>
</template>

<style scoped>
.primary-button {
  min-height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 0;
  border-radius: 10px;
  padding: 11px 18px;
  color: #fff;
  background: linear-gradient(110deg, #17518f, #3292c2);
  box-shadow: 0 8px 20px rgba(40, 91, 146, .16);
  font-weight: 700;
}
.primary-button:disabled { opacity: .55; }
.report-main { min-height: 0; overflow: hidden; }
.report-body { width: min(100%, 1120px); margin: 0 auto; padding: 38px 32px 80px; overflow-y: auto; }
.report-intro { position: relative; padding: 6px 0 4px 18px; }
.report-intro::before { content: ""; position: absolute; left: 0; top: 7px; bottom: 6px; width: 3px; border-radius: 4px; background: linear-gradient(#339dd1, #d7a94a); }
.report-intro h1 { margin: 12px 0 8px; color: #102e58; font-size: clamp(28px, 3vw, 36px); letter-spacing: -.035em; }
.report-intro p, .report-card p { color: #536d87; line-height: 1.75; }
.report-card { margin: 24px 0; padding: 26px; border: 1px solid #d5e2ee; border-radius: 18px; background: rgba(255, 255, 255, .94); box-shadow: 0 12px 38px rgba(22, 62, 112, .07); }
.report-card h2 { margin-top: 0; color: #17385e; font-size: 19px; }
.report-card label { display: block; margin: 16px 0; color: #355475; font-size: 13px; font-weight: 650; }
.report-card input:not([type=checkbox]), .report-card select { width: 100%; min-height: 42px; display: block; margin-top: 7px; border: 1px solid #c8d9e8; border-radius: 9px; padding: 10px 12px; color: #173653; background: #fbfdff; outline: 0; }
.report-card input:focus, .report-card select:focus, .report-editor:focus { border-color: #4999ca; box-shadow: 0 0 0 4px rgba(50, 155, 208, .09); }
.report-card small { color: #657e96; font-size: 12px; }
.report-history { flex: 1; overflow: auto; padding: 8px 0; }
.report-history button { width: 100%; min-height: 55px; display: block; border: 1px solid transparent; border-radius: 10px; padding: 11px 12px; color: #e1ecfc; text-align: left; background: transparent; }
.report-history button:hover, .report-history button.active { border-color: rgba(151, 202, 239, .12); background: rgba(255, 255, 255, .09); }
.report-history button.active { box-shadow: inset 3px 0 #55bce7; }
.report-history strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.report-history small { display: block; margin-top: 5px; color: #9db8d5; font-size: 11px; }
.report-sidebar-caption { padding: 20px 8px 5px; color: #9db6d1; font-size: 12px; }
.report-history p { padding: 12px; color: #aac0d9; font-size: 12px; line-height: 1.75; }
.report-document { padding: 0; overflow: hidden; }
.report-document > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 23px 26px 18px; border-bottom: 1px solid #e0e9f1; background: #fbfdff; }
.report-document > header h2 { margin-bottom: 6px; }
.report-document > .markdown, .report-document > label, .report-document > .report-editor, .report-document > footer { margin-left: 28px; margin-right: 28px; }
.report-document > .markdown { max-width: 820px; padding: 8px 0 22px; font-size: 14px; line-height: 1.8; }
.report-export { display: flex; flex-wrap: wrap; gap: 9px; margin: 18px 28px; }
.report-export a, .report-export button, .report-document header button { min-height: 38px; display: inline-flex; align-items: center; gap: 7px; border: 1px solid #c9ddeb; border-radius: 9px; padding: 8px 11px; color: #236fa5; background: #f5faff; font-size: 12px; font-weight: 650; }
.report-export a:hover, .report-export button:hover, .report-document header button:hover { border-color: #82badd; background: #eaf5fc; }
.report-limit { margin: 0 28px !important; padding: 10px 12px; border-radius: 9px; background: #f5f8fb; font-size: 12px; }
.report-editor { width: calc(100% - 56px); min-height: 55vh; border: 1px solid #c6dae9; border-radius: 10px; padding: 16px; outline: 0; font: 14px/1.8 ui-monospace, SFMono-Regular, Consolas, monospace; }
.report-document footer { margin-top: 30px; padding: 20px 0 26px; border-top: 1px solid #e1ebf5; font-size: 12px; }
.report-document code { display: block; overflow-wrap: anywhere; padding: 10px 0; color: #346d98; }
.report-empty { padding: 64px 24px; text-align: center; }
.report-sections { display: flex; flex-wrap: wrap; gap: 10px 18px; }
.report-sections label { min-height: 36px; display: flex; align-items: center; margin: 0; }
.report-sections input { margin-right: 7px; }
.error-banner { border-left: 3px solid #c44d5c; border-radius: 9px; padding: 12px 14px; color: #a72f43; background: #fff0f2; }
@media (max-width: 720px) {
  .report-body { padding: 22px 13px 60px; }
  .report-center > .app-sidebar { display: none; }
  .report-main .workspace-heading small { display: none; }
  .report-intro h1 { font-size: 25px; }
  .report-card { padding: 19px 15px; border-radius: 14px; }
  .report-document { padding: 0; }
  .report-document > header { align-items: flex-start; flex-direction: column; padding: 18px 16px; }
  .report-document > .markdown, .report-document > label, .report-document > .report-editor, .report-document > footer { margin-left: 16px; margin-right: 16px; }
  .report-export { margin-left: 16px; margin-right: 16px; }
  .report-limit { margin-left: 16px !important; margin-right: 16px !important; }
  .report-editor { width: calc(100% - 32px); }
}
</style>
