<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'

const props = defineProps<{ runId: string }>()
const emit = defineEmits<{ snapshot: [value: any] }>()
const events = ref<any[]>([]), status = ref('RUNNING'), opened = ref(false), error = ref('')
const startedAt = ref(''), now = ref(Date.now()), stopping = ref(false), latencyMs = ref<number | null>(null)
let sequence = 0, disposed = false, polling: ReturnType<typeof setTimeout> | undefined
const clock = setInterval(() => { now.value = Date.now() }, 1000)
const active = computed(() => ['RUNNING', 'CANCEL_REQUESTED'].includes(status.value))
const nodes = computed(() => {
  const map = new Map<string, any>()
  for (const event of events.value) map.set(event.nodeExecutionId, event)
  return [...map.values()]
})
const latest = computed(() => events.value.at(-1))
const seconds = computed(() => startedAt.value ? Math.max(0, Math.floor((now.value - Date.parse(startedAt.value)) / 1000)) : 0)
const age = computed(() => latest.value ? Math.max(0, Math.floor((now.value - Date.parse(latest.value.timestamp)) / 1000)) : 0)
const labels: Record<string, string> = { RUNNING: '执行中', COMPLETED: '已完成', PARTIAL: '部分成功', WARNING: '需关注', FAILED: '失败', INTERRUPTED: '服务重启，运行已中断', CANCELLED: '已停止等待', CANCEL_REQUESTED: '正在请求停止' }
const statusTone = computed(() => status.value === 'COMPLETED' ? 'success' : ['FAILED', 'INTERRUPTED'].includes(status.value) ? 'danger' : ['PARTIAL', 'WARNING'].includes(status.value) ? 'warning' : 'active')
async function poll() {
  try {
    const { data } = await api.get(`/runs/${props.runId}`, { params: { after: sequence } })
    if (disposed) return
    events.value.push(...data.events); sequence = data.nextSequence
    status.value = data.status; startedAt.value = data.startedAt; latencyMs.value = data.latencyMs; error.value = ''
    emit('snapshot', data)
    if (active.value || data.hasMore) polling = setTimeout(poll, data.hasMore ? 30 : 2000)
  } catch (reason: any) {
    if (disposed) return
    error.value = reason.response?.status === 404 ? '记录不存在或无权访问' : '进度连接暂时中断，正在重新连接（不会重新执行任务）'
    if (![401, 403, 404].includes(reason.response?.status)) polling = setTimeout(poll, 5000)
  }
}
async function stop() {
  stopping.value = true
  try { await api.post(`/runs/${props.runId}/stop`); status.value = 'CANCEL_REQUESTED' }
  catch (reason: any) { error.value = reason.response?.data?.message || '停止请求未成功' }
  finally { stopping.value = false }
}
onMounted(poll)
onBeforeUnmount(() => { disposed = true; clearInterval(clock); if (polling) clearTimeout(polling) })
</script>

<template>
  <section class="run-progress" aria-live="polite">
    <div class="run-progress-heading">
      <button type="button" :aria-expanded="opened" @click="opened = !opened"><span :class="['run-indicator', { active }]" /><span><strong>{{ latest?.displayName || '等待运行信息' }}</strong><small>{{ opened ? '收起执行过程' : '查看执行过程' }}</small></span></button>
      <button v-if="active" class="run-stop" :disabled="stopping || status === 'CANCEL_REQUESTED'" @click="stop">{{ status === 'CANCEL_REQUESTED' ? '请求停止中' : '停止任务' }}</button>
    </div>
    <div class="run-summary"><span class="run-status" :class="statusTone">{{ labels[status] || status }}</span><span v-if="active">已运行 {{ seconds }} 秒</span><span v-if="active">最近进展 {{ age }} 秒前</span><span v-else-if="latencyMs != null">总耗时 {{ (latencyMs / 1000).toFixed(1) }} 秒</span></div>
    <p v-if="active && age > 30" class="run-notice">暂未收到新的上游进展，仍在等待；页面在线不代表第三方查询已有新结果。</p>
    <p v-if="error" class="run-notice">{{ error }}</p>
    <ol v-if="opened"><li v-for="node in nodes" :key="node.nodeExecutionId" :class="node.status.toLowerCase()"><span>{{ node.status === 'COMPLETED' ? '✓' : node.status === 'RUNNING' && active ? '◌' : '·' }}</span><div><strong>{{ node.displayName }}</strong><small>{{ node.status === 'RUNNING' && !active ? '未收到该节点结束事件' : labels[node.status] || node.status }}<template v-if="node.elapsedMs != null"> · {{ (node.elapsedMs / 1000).toFixed(1) }} 秒</template></small></div></li></ol>
    <small v-if="opened" class="run-reference">运行编号 {{ runId }} · 仅展示真实事件；子工作流未透传的内部步骤不作推测。</small>
  </section>
</template>

<style scoped>
.run-progress{border:1px solid #cfe0ef;background:linear-gradient(120deg,#f3f8fd,#fbfdff);border-radius:13px;padding:14px 16px;margin:8px 0 18px;font-size:13px;color:#315578}
.run-progress-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.run-progress button{border:0;background:none;color:inherit;cursor:pointer;text-align:left}.run-progress-heading>button:first-child{display:flex;align-items:center;gap:10px;min-height:36px}.run-progress-heading>button:first-child>span:nth-child(2){display:grid;gap:2px}.run-progress-heading strong{font-size:13px}.run-progress small{font-size:12px;color:#687f96}.run-indicator{width:8px;height:8px;flex:none;border-radius:50%;background:#38a780;box-shadow:0 0 0 4px rgba(56,167,128,.1)}.run-indicator.active{background:#318ad0;box-shadow:0 0 0 4px rgba(49,138,208,.11);animation:breathe 1.4s infinite}.run-stop{min-height:34px;white-space:nowrap;border:1px solid #b6cde3!important;border-radius:8px;padding:5px 10px}.run-summary{display:flex;align-items:center;gap:8px 13px;margin:9px 0 0;padding-left:18px;flex-wrap:wrap;color:#70859a;font-size:12px}.run-status{padding:3px 8px;border-radius:999px;font-weight:700}.run-status.success{color:#176e52;background:#e7f6ef}.run-status.active{color:#216da2;background:#e8f3fb}.run-status.warning{color:#8a601e;background:#fff4d8}.run-status.danger{color:#a43d4c;background:#ffedf0}.run-progress ol{position:relative;list-style:none;padding:2px 0 0 14px;margin:15px 0;max-height:280px;overflow:auto}.run-progress ol::before{content:"";position:absolute;left:25px;top:12px;bottom:12px;width:1px;background:#ceddea}.run-progress li{position:relative;display:flex;gap:11px;margin:0;padding:7px 0}.run-progress li>span{z-index:1;width:23px;height:23px;display:grid;place-items:center;border:1px solid #c7d9e8;border-radius:50%;color:#477da7;background:#fff;font-size:11px}.run-progress li.completed>span{border-color:#a8d5c3;color:#187858;background:#edf8f3}.run-progress li>div{display:grid;gap:2px;padding-top:1px}.run-progress li small{display:block}.run-notice{margin:9px 0 0;padding:8px 10px;border-radius:8px;color:#865f20;background:#fff8e8}.run-reference{display:block;overflow-wrap:anywhere;padding-top:9px;border-top:1px solid #dce7f0}@keyframes breathe{50%{opacity:.35}}@media(prefers-reduced-motion:reduce){.run-indicator.active{animation:none}}
</style>
