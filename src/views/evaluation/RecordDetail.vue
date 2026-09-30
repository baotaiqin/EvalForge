<template>
  <div class="page-content record-detail-page">
    <a-spin :spinning="detailLoading && !detail">
      <template v-if="detail">
        <div class="page-header">
          <div class="detail-title">
            <a-button type="text" @click="handleBack">
              <template #icon><ArrowLeftOutlined /></template>
              返回
            </a-button>
            <h2>{{ detail.name || '评测详情' }}</h2>
            <a-badge
              :status="EVAL_STATUS_BADGE_MAP[detail.status] || 'default'"
              :text="EVAL_STATUS_MAP[detail.status]?.text || detail.status || '未知状态'"
            />
          </div>
          <a-space wrap>
            <a-button v-if="detail.status === 'running'" danger :loading="stopping" @click="handleStop">
              <template #icon><StopOutlined /></template>
              停止评测
            </a-button>
            <a-button v-if="detail.status !== 'running'" @click="confirmRestart">
              <template #icon><ReloadOutlined /></template>
              重新评测
            </a-button>
            <a-button
              v-if="detail.status !== 'running' && results.length"
              @click="openResumeDialog"
            >
              <template #icon><PlayCircleOutlined /></template>
              断点续跑
            </a-button>
            <a-button
              v-if="detail.status === 'completed'"
              @click="rerunBelowVisible = true"
            >
              <template #icon><ReloadOutlined /></template>
              低分重测
            </a-button>
            <a-button @click="handleExport">
              <template #icon><DownloadOutlined /></template>
              导出结果
            </a-button>
          </a-space>
        </div>

        <a-alert
          v-if="detail.status === 'running'"
          :message="evalStore.streamingProgress || '评测正在执行中，结果会自动刷新。'"
          type="info"
          show-icon
          style="margin-bottom: 16px;"
        />

        <a-card title="评测信息" size="small" style="margin-bottom: 16px;">
          <a-descriptions bordered size="small" :column="{ xs: 1, sm: 2, md: 3 }">
            <a-descriptions-item label="评测名称">{{ detail.name || '--' }}</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-badge
                :status="EVAL_STATUS_BADGE_MAP[detail.status] || 'default'"
                :text="EVAL_STATUS_MAP[detail.status]?.text || detail.status || '--'"
              />
            </a-descriptions-item>
            <a-descriptions-item label="类型">
              {{ EVAL_TARGET_TYPE_MAP[detail.type || detail.targetType]?.text || detail.type || detail.targetType || '--' }}
            </a-descriptions-item>
            <a-descriptions-item label="被评测对象">
              <a-tag v-for="target in targets" :key="target.id">{{ target.label || target.name || target.id }}</a-tag>
              <span v-if="!targets.length">--</span>
            </a-descriptions-item>
            <a-descriptions-item label="数据集">
              <a-tag v-for="name in datasetNames" :key="name">{{ name }}</a-tag>
              <span v-if="!datasetNames.length">--</span>
            </a-descriptions-item>
            <a-descriptions-item label="评分模式">{{ judgeModeLabel }}</a-descriptions-item>
            <a-descriptions-item v-if="detail.judgeModelId" label="评分模型">{{ judgeModelName }}</a-descriptions-item>
            <a-descriptions-item label="开始时间">{{ detail.startTime || '--' }}</a-descriptions-item>
            <a-descriptions-item label="结束时间">{{ detail.endTime || '--' }}</a-descriptions-item>
            <a-descriptions-item label="评测耗时">{{ durationText }}</a-descriptions-item>
            <a-descriptions-item v-if="detail.accuracy != null" label="整体得分">
              {{ formatScore(detail.accuracy) }}
            </a-descriptions-item>
            <a-descriptions-item v-if="detail.remark" label="备注">{{ detail.remark }}</a-descriptions-item>
          </a-descriptions>
        </a-card>

        <EvaluationOverviewTable
          v-if="results.length"
          :targets="targets"
          :results="results"
          @jump="jumpToQuestion"
        />

        <a-card title="逐题对比" size="small" :body-style="{ padding: 0 }">
          <template #extra>
            <span class="result-count">共 {{ results.length }} 题</span>
          </template>
          <CompareColumns
            :targets="targets"
            :results="results"
            :evaluation-status="detail.status"
            @update-score="handleUpdateScore"
            @rerun-question="handleRerunQuestion"
          />
        </a-card>
      </template>

      <a-empty v-else-if="!detailLoading" description="未找到评测记录">
        <a-button type="primary" @click="handleBack">返回评测记录</a-button>
      </a-empty>
    </a-spin>

    <a-modal
      v-model:open="resumeVisible"
      title="断点续跑"
      ok-text="开始续跑"
      cancel-text="取消"
      :confirm-loading="resuming"
      @ok="handleResume"
    >
      <p>从指定题号开始重新执行，之前的题目结果会保留。</p>
      <a-form-item label="起始题号">
        <a-input-number v-model:value="resumeFromIndex" :min="1" :max="Math.max(results.length, 1)" style="width: 160px;" />
        <span class="field-tip">共 {{ results.length }} 题</span>
      </a-form-item>
    </a-modal>

    <a-modal
      v-model:open="rerunBelowVisible"
      title="低分题目重测"
      ok-text="开始重测"
      cancel-text="取消"
      :confirm-loading="rerunningBelow"
      @ok="handleRerunBelowScore"
    >
      <p>重新执行至少一个被测对象最终得分低于阈值的题目。</p>
      <a-form-item label="分数阈值">
        <a-input-number v-model:value="rerunThreshold" :min="0" :max="100" :step="1" style="width: 160px;" />
        <span class="field-tip">分</span>
      </a-form-item>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  StopOutlined,
  ReloadOutlined,
  DownloadOutlined,
  PlayCircleOutlined
} from '@ant-design/icons-vue'
import { useEvaluationStore } from '@/stores/evaluation.js'
import { useConfigStore } from '@/stores/config.js'
import { BASE_URL } from '@/api/request.js'
import {
  EVAL_STATUS_MAP,
  EVAL_STATUS_BADGE_MAP,
  EVAL_TARGET_TYPE_MAP,
  formatDurationRange
} from '@/utils/constants.js'
import EvaluationOverviewTable from '@/components/evaluation/EvaluationOverviewTable.vue'
import CompareColumns from '@/components/evaluation/CompareColumns.vue'

defineOptions({ name: 'RecordDetail' })

const route = useRoute()
const router = useRouter()
const evalStore = useEvaluationStore()
const configStore = useConfigStore()
const detailLoading = ref(false)
const stopping = ref(false)
const resuming = ref(false)
const rerunningBelow = ref(false)
const resumeVisible = ref(false)
const rerunBelowVisible = ref(false)
const resumeFromIndex = ref(1)
const rerunThreshold = ref(60)
let statusSource = null
let refreshInterval = null
let detailRequestToken = 0
let refreshPending = false

const detail = computed(() => {
  const current = evalStore.currentDetail
  return current && String(current.id) === String(route.params.id) ? current : null
})
const targets = computed(() => detail.value?.targets || [])
const results = computed(() => detail.value?.results || [])
const datasetNames = computed(() => {
  if (detail.value?.datasetNames?.length) return detail.value.datasetNames
  return (detail.value?.datasets || []).map(dataset => typeof dataset === 'string' ? dataset : dataset.name).filter(Boolean)
})
const judgeModeLabel = computed(() => {
  if (detail.value?.judgeMode === 'llm') return '模型评分'
  if (detail.value?.judgeMode === 'formula') return '公式评分'
  if (detail.value?.judgeMode === 'manual') return '人工评判'
  return '--'
})
const judgeModelName = computed(() => {
  const model = configStore.models.find(item => item.id === detail.value?.judgeModelId)
  return model ? `${model.name} (${model.provider})` : detail.value?.judgeModelId || '--'
})
const durationText = computed(() => {
  if (!detail.value?.startTime) return '--'
  if (detail.value.endTime) return formatDurationRange(detail.value.startTime, detail.value.endTime)
  if (detail.value.status === 'running') {
    const start = new Date(String(detail.value.startTime).replace(/-/g, '/'))
    if (!Number.isNaN(start.getTime())) {
      const elapsed = Date.now() - start.getTime()
      return elapsed >= 0 ? `${(elapsed / 60000).toFixed(1)} 分钟（进行中）` : '--'
    }
  }
  return '--'
})

function formatScore(value) {
  const score = Number(value)
  if (!Number.isFinite(score)) return '--'
  return `${(score <= 1 ? score * 100 : score).toFixed(1)} 分`
}

async function loadDetail({ quiet = false } = {}) {
  const id = String(route.params.id || '')
  if (!id) return
  const token = ++detailRequestToken
  if (!quiet) detailLoading.value = true
  try {
    const result = await evalStore.fetchDetail(id)
    if (token !== detailRequestToken) {
      if (String(evalStore.currentDetail?.id) !== String(route.params.id)) loadDetail({ quiet: true })
      return
    }
    if (!result) throw new Error('评测记录不存在')
    if (result.status === 'running') startRefreshInterval()
    else stopRefreshInterval()
  } catch (error) {
    if (token === detailRequestToken && !quiet) message.error(error.message || '获取评测详情失败')
  } finally {
    if (token === detailRequestToken && !quiet) detailLoading.value = false
  }
}

function refreshCurrentDetail() {
  if (refreshPending || detail.value?.status !== 'running') return
  refreshPending = true
  loadDetail({ quiet: true }).finally(() => { refreshPending = false })
}

function startRefreshInterval() {
  if (refreshInterval) return
  refreshInterval = setInterval(refreshCurrentDetail, 3000)
}

function stopRefreshInterval() {
  if (refreshInterval) {
    clearInterval(refreshInterval)
    refreshInterval = null
  }
}

function subscribeStatus() {
  if (statusSource || typeof EventSource === 'undefined') return
  statusSource = new EventSource(`${BASE_URL}/api/evaluations/stream/status`)
  const handleStatus = event => {
    try {
      const payload = event?.data ? JSON.parse(event.data) : null
      const eventId = payload?.evalId || payload?.evaluationId || payload?.id
      if (!eventId || String(eventId) === String(route.params.id)) refreshCurrentDetail()
    } catch {
      refreshCurrentDetail()
    }
  }
  statusSource.addEventListener('status', handleStatus)
  statusSource.onmessage = handleStatus
  statusSource.onerror = () => console.warn('评测状态推送连接异常，浏览器将自动重连')
}

function closeStatus() {
  if (statusSource) {
    statusSource.close()
    statusSource = null
  }
  stopRefreshInterval()
}

function handleBack() {
  router.push('/agent-eval/records')
}

function confirmRestart() {
  Modal.confirm({
    title: '重新评测',
    content: '将清空当前评测结果并重新执行，确定继续吗？',
    okText: '重新评测',
    cancelText: '取消',
    onOk: handleRestart
  })
}

function clearResult(result) {
  result.outputs = {}
  result.scores = {}
  result._loading = true
}

function updateRecordStatus(status) {
  const record = evalStore.records.find(item => item.id === detail.value?.id)
  if (record) record.status = status
  if (detail.value) detail.value.status = status
}

async function handleStop() {
  Modal.confirm({
    title: '停止评测',
    content: '确定停止当前评测吗？已完成的题目结果会保留。',
    okText: '停止',
    cancelText: '继续评测',
    onOk: async () => {
      stopping.value = true
      try {
        await evalStore.stopEvaluation(detail.value.id)
        updateRecordStatus('stopped')
        stopRefreshInterval()
        await loadDetail({ quiet: true })
        message.success('评测已停止')
      } catch (error) {
        message.error(error.message || '停止评测失败')
      } finally {
        stopping.value = false
      }
    }
  })
}

async function handleRestart() {
  if (!detail.value) return
  const snapshot = JSON.parse(JSON.stringify(detail.value))
  results.value.forEach(clearResult)
  detail.value.status = 'running'
  detail.value.accuracy = null
  detail.value.endTime = null
  detail.value.startTime = new Date().toLocaleString()
  updateRecordStatus('running')
  startRefreshInterval()
  try {
    await evalStore.restartEvaluation(detail.value.id)
    message.success('已重新启动评测')
  } catch (error) {
    evalStore.currentDetail = snapshot
    updateRecordStatus(snapshot.status)
    stopRefreshInterval()
    message.error(error.message || '重新评测失败')
  }
}

function openResumeDialog() {
  const nextIndex = results.value.findIndex(result =>
    result._loading || targets.value.some(target => !result.outputs?.[target.id])
  )
  resumeFromIndex.value = nextIndex >= 0 ? nextIndex + 1 : 1
  resumeVisible.value = true
}

async function handleResume() {
  if (!detail.value) return
  resuming.value = true
  const fromIndex = Number(resumeFromIndex.value) || 1
  try {
    results.value.slice(fromIndex - 1).forEach(clearResult)
    detail.value.status = 'running'
    detail.value.endTime = null
    updateRecordStatus('running')
    resumeVisible.value = false
    startRefreshInterval()
    await evalStore.resumeEvaluation(detail.value.id, fromIndex)
    message.success(`已从第 ${fromIndex} 题开始续跑`)
  } catch (error) {
    await loadDetail({ quiet: true })
    message.error(error.message || '断点续跑失败')
  } finally {
    resuming.value = false
  }
}

async function handleRerunBelowScore() {
  if (!detail.value) return
  rerunningBelow.value = true
  const threshold = Number(rerunThreshold.value)
  try {
    results.value.forEach(result => {
      const belowThreshold = targets.value.some(target => {
        const score = result.scores?.[target.id]?.final
        return typeof score === 'number' && score * 100 < threshold
      })
      if (belowThreshold) clearResult(result)
    })
    detail.value.status = 'running'
    detail.value.endTime = null
    updateRecordStatus('running')
    rerunBelowVisible.value = false
    startRefreshInterval()
    await evalStore.rerunBelowScore(detail.value.id, threshold)
    message.success('低分题目已提交重测')
  } catch (error) {
    await loadDetail({ quiet: true })
    message.error(error.message || '低分重测失败')
  } finally {
    rerunningBelow.value = false
  }
}

async function handleRerunQuestion(questionIndex) {
  const result = results.value[questionIndex - 1]
  if (result) clearResult(result)
  if (detail.value) detail.value.status = 'running'
  updateRecordStatus('running')
  startRefreshInterval()
  try {
    await evalStore.rerunQuestion(detail.value.id, questionIndex)
    message.success(`第 ${questionIndex} 题已提交重测`)
  } catch (error) {
    await loadDetail({ quiet: true })
    message.error(error.message || '单题重测失败')
  }
}

async function handleUpdateScore(payload) {
  try {
    await evalStore.updateScore(detail.value.id, payload.resultId, {
      targetId: payload.targetId,
      scores: payload.scores,
      remark: payload.remark
    })
    message.success('评分已保存')
  } catch (error) {
    message.error(error.message || '保存评分失败')
  }
}

function jumpToQuestion(index) {
  document.getElementById(`question-row-${index}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

function handleExport() {
  if (!detail.value) return
  const blob = new Blob([JSON.stringify(detail.value, null, 2)], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${detail.value.name || detail.value.id || 'evaluation'}.json`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

watch(() => route.params.id, async () => {
  stopRefreshInterval()
  refreshPending = false
  await loadDetail()
  subscribeStatus()
}, { immediate: true })

onMounted(() => {
  if (!configStore.models.length) configStore.fetchModels()
  subscribeStatus()
})

onUnmounted(() => {
  detailRequestToken += 1
  closeStatus()
})
</script>

<style scoped>
.record-detail-page {
  min-height: calc(100vh - 112px);
}

.detail-title {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.detail-title h2 {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-count,
.field-tip {
  color: #888;
  font-size: 12px;
}

.field-tip {
  margin-left: 8px;
}
</style>
