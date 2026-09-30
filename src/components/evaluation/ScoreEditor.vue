<template>
  <div class="score-editor">
    <div v-if="!editing" class="score-display" @click="startEdit">
      <div class="score-tags">
        <a-tag
          v-for="dimension in displayDimensions"
          :key="dimension.key"
          :color="scoreColor(dimension.value)"
        >
          {{ dimension.label }}: {{ formatScore(dimension.value) }}
        </a-tag>
      </div>
      <div class="final-score">
        综合：<span :style="{ color: scoreColorHex(finalScore), fontWeight: 'bold' }">
          {{ formatScore(finalScore) }}
        </span>
      </div>
      <div v-if="scores?.manual" class="manual-badge">
        <a-tag color="purple">已人工复判</a-tag>
      </div>
      <div v-if="scores?.analysis" class="analysis-text">
        <a-tag color="blue">AI评测</a-tag>
        {{ scores.analysis }}
      </div>
      <div v-if="scores?.reason" class="analysis-text">
        <a-tag color="blue">评分说明</a-tag>
        {{ scores.reason }}
      </div>
      <div v-if="scores?.differences?.length" class="differences-text">
        <div v-for="(difference, index) in scores.differences" :key="index" class="diff-item">
          {{ difference }}
        </div>
      </div>
      <div v-if="scores?.remark" class="remark-text">备注：{{ scores.remark }}</div>
      <a style="font-size: 12px; margin-top: 4px; display: block;">修改评分</a>
    </div>

    <div v-else class="score-form">
      <div v-for="dimension in editDimensions" :key="dimension.key" class="score-row">
        <span class="dim-label">{{ dimension.label }}:</span>
        <a-slider
          :value="localScores[dimension.key]"
          :min="0"
          :max="1"
          :step="0.05"
          style="flex: 1; margin: 0 12px;"
          @update:value="value => localScores[dimension.key] = value"
        />
        <a-input-number
          :value="localScores[dimension.key]"
          :min="0"
          :max="1"
          :step="0.05"
          size="small"
          style="width: 70px;"
          @update:value="value => localScores[dimension.key] = value"
        />
      </div>
      <div style="margin-top: 8px;">
        <a-input v-model:value="localRemark" placeholder="添加备注" size="small" />
      </div>
      <div style="margin-top: 8px; text-align: right;">
        <a-space>
          <a-button size="small" @click="editing = false">取消</a-button>
          <a-button size="small" type="primary" @click="saveScore">保存</a-button>
        </a-space>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import {
  SCORE_DIMENSIONS,
  PPT_SCORE_DIMENSIONS,
  HTML_SCORE_DIMENSIONS,
  getScoreTagColor as scoreColor,
  getScoreHexColor as scoreColorHex
} from '@/utils/constants.js'

const props = defineProps({
  scores: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['save'])

const editing = ref(false)
const localScores = reactive({})
const localRemark = ref('')

const displayScores = computed(() => props.scores?.manual || props.scores?.auto || {})
const DIMENSION_LABELS = Object.fromEntries(
  [...SCORE_DIMENSIONS, ...PPT_SCORE_DIMENSIONS, ...HTML_SCORE_DIMENSIONS]
    .map(dimension => [dimension.key, dimension.label])
)
const displayDimensions = computed(() => Object.keys(displayScores.value)
  .filter(key => displayScores.value[key] != null)
  .map(key => ({ key, label: DIMENSION_LABELS[key] || key, value: displayScores.value[key] }))
)
const editDimensions = computed(() => displayDimensions.value.length
  ? displayDimensions.value.map(({ key, label }) => ({ key, label }))
  : SCORE_DIMENSIONS
)
const finalScore = computed(() => props.scores?.final || 0)

function startEdit() {
  const scores = displayScores.value
  Object.keys(localScores).forEach(key => delete localScores[key])
  editDimensions.value.forEach(dimension => {
    localScores[dimension.key] = scores[dimension.key] || 0
  })
  localRemark.value = props.scores?.remark || ''
  editing.value = true
}

function saveScore() {
  emit('save', {
    scores: { ...localScores },
    remark: localRemark.value
  })
  editing.value = false
}

function formatScore(value) {
  if (value == null) return '--'
  return `${(value * 100).toFixed(0)}%`
}
</script>

<style scoped>
.score-editor {
  margin-top: 8px;
}

.score-display {
  cursor: pointer;
  padding: 4px 0;
}

.score-display:hover {
  background: #f5f5f5;
  border-radius: 4px;
}

.score-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 4px;
}

.final-score {
  font-size: 13px;
  margin: 4px 0;
}

.remark-text {
  font-size: 12px;
  color: #888;
  margin-top: 4px;
}

.analysis-text {
  font-size: 12px;
  color: #555;
  margin-top: 6px;
  line-height: 1.5;
  background: #e6f7ff;
  padding: 6px 8px;
  border-radius: 4px;
}

.differences-text {
  margin-top: 4px;
}

.diff-item {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
}

.score-form {
  background: #fafafa;
  padding: 12px;
  border-radius: 6px;
  border: 1px solid #e8e8e8;
}

.score-row {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}

.dim-label {
  width: 60px;
  font-size: 12px;
  color: #555;
}
</style>
