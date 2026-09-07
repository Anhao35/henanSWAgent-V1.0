<script setup lang="ts">
import { SlidersHorizontal } from '@lucide/vue'

defineProps<{ modelValue: string, disabled?: boolean }>()
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<template>
  <label class="task-mode" title="选择本轮任务的研判方式">
    <SlidersHorizontal :size="15" aria-hidden="true" />
    <span>任务模式</span>
    <select :value="modelValue" :disabled="disabled" aria-label="任务模式" @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
      <option value="AUTO">自动判断 · 自动检索知识库</option>
      <option value="SECURITY">安全研判 · 联动情报</option>
      <option value="READ">资料解读 · 不查 IOC</option>
      <option value="EXPLAIN">仅解释 · 不查 IOC</option>
    </select>
  </label>
</template>

<style scoped>
.task-mode {
  min-width: 0;
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 0 4px;
  padding-left: 9px;
  border: 1px solid #d2e1ed;
  border-radius: 9px;
  color: #53708c;
  background: #f6fafd;
  font-size: 12px;
}
.task-mode > span { white-space: nowrap; font-weight: 650; }
.task-mode svg { flex: none; color: #2f83b9; }
.task-mode select {
  min-width: 0;
  max-width: 205px;
  height: 34px;
  border: 0;
  border-left: 1px solid #dce7f0;
  padding: 0 25px 0 9px;
  outline: 0;
  color: #244b73;
  background: transparent;
  font-size: 12px;
}
.task-mode:focus-within {
  border-color: #4a9dce;
  box-shadow: 0 0 0 3px rgba(50, 155, 208, .1);
}
@media (max-width: 620px) {
  .task-mode { padding-left: 7px; margin: 0; }
  .task-mode > span { display: none; }
  .task-mode select { max-width: 155px; padding-left: 6px; font-size: 11px; }
}
@media (max-width: 390px) {
  .task-mode svg { display: none; }
  .task-mode select { max-width: 138px; }
}
</style>
