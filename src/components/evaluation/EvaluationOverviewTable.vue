<template>
  <a-card title="评测概览" style="margin-bottom: 24px;">
    <a-table
      :columns="columns"
      :data-source="rows"
      :pagination="rows.length > 10 ? { pageSize: 10 } : false"
      row-key="index"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'index'">
          <a class="question-link" @click="jumpTo(record.index)">{{ record.index }}</a>
        </template>
        <template v-else-if="column.key === 'question'">
          <a class="question-link" @click="jumpTo(record.index)">{{ record.question }}</a>
        </template>
        <template v-else-if="props.targets.some(target => target.id === column.key)">
          <span
            v-if="record.scores[column.key] != null"
            :style="{ color: getScoreHexColor(record.scores[column.key] / 100), fontWeight: 600 }"
          >{{ record.scores[column.key].toFixed(1) }} 分</span>
          <span v-else style="color: #bbb;">--</span>
        </template>
      </template>
    </a-table>
  </a-card>
</template>

<script setup>
import { computed } from 'vue'
import { getScoreHexColor } from '@/utils/constants.js'

const props = defineProps({
  targets: { type: Array, default: () => [] },
  results: { type: Array, default: () => [] }
})
const emit = defineEmits(['jump'])

const columns = computed(() => [
  { title: '题号', key: 'index', width: 70 },
  { title: '问题', key: 'question', ellipsis: true },
  ...props.targets.map(target => ({ title: target.label, key: target.id, width: 120 }))
])

const rows = computed(() => props.results.map((result, index) => {
  const scores = {}
  for (const target of props.targets) {
    const final = result.scores?.[target.id]?.final
    scores[target.id] = typeof final === 'number' ? final * 100 : null
  }
  return {
    index: index + 1,
    question: result.question || result.input?.text || result.prompt || result.query || '(无文本提问)',
    scores
  }
}))

function jumpTo(index) {
  emit('jump', index)
}
</script>

<style scoped>
.question-link {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
