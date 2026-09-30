<template>
  <div class="page-content">
    <div class="page-header">
      <h2>评测记录</h2>
      <a-button type="primary" @click="drawerVisible = true">
        <template #icon><PlusOutlined /></template>
        新增评测
      </a-button>
    </div>

    <RecordFilterBar @search="handleFilterSearch" />
    <EvalTable
      :filters="activeFilters"
      @edit="handleEdit"
      @export="handleExport"
    />

    <CreateEvalDrawer
      :open="drawerVisible"
      :editing-record="editingRecord"
      @update:open="value => { drawerVisible = value; if (!value) editingRecord = null }"
      @success="handleSuccess"
    />
  </div>
</template>

<script setup>
import { onActivated, onDeactivated, onMounted, onUnmounted, ref } from 'vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { useEvaluationStore } from '@/stores/evaluation.js'
import { BASE_URL } from '@/api/request.js'
import EvalTable from '@/components/evaluation/EvalTable.vue'
import CreateEvalDrawer from '@/components/evaluation/CreateEvalDrawer.vue'
import RecordFilterBar from '@/components/evaluation/RecordFilterBar.vue'

defineOptions({ name: 'RecordList' })

const evalStore = useEvaluationStore()
const drawerVisible = ref(false)
const editingRecord = ref(null)
const activeFilters = ref(null)
let statusSource = null
let refreshTimer = null

function scheduleRefresh() {
  clearTimeout(refreshTimer)
  refreshTimer = setTimeout(() => {
    evalStore.fetchRecords().catch(error => {
      console.warn('刷新评测记录失败:', error?.message || error)
    })
  }, 300)
}

function subscribeStatus() {
  if (statusSource || typeof EventSource === 'undefined') return
  statusSource = new EventSource(`${BASE_URL}/api/evaluations/stream/status`)
  statusSource.addEventListener('status', scheduleRefresh)
  statusSource.onmessage = scheduleRefresh
  statusSource.onopen = scheduleRefresh
  statusSource.onerror = () => {
    console.warn('评测状态推送连接异常，浏览器将自动重连')
  }
}

function unsubscribeStatus() {
  if (statusSource) {
    statusSource.close()
    statusSource = null
  }
  clearTimeout(refreshTimer)
  refreshTimer = null
}

function handleFilterSearch(filters) {
  activeFilters.value = filters
}

function handleEdit(record) {
  editingRecord.value = record
  drawerVisible.value = true
}

function handleExport() {
  message.info('导出功能请在评测详情页使用')
}

function handleSuccess() {
  evalStore.fetchRecords()
}

function loadRecords() {
  evalStore.fetchRecords().catch(error => {
    message.error(error?.message || '获取评测记录失败')
  })
  subscribeStatus()
}

onMounted(loadRecords)
onUnmounted(unsubscribeStatus)
onDeactivated(unsubscribeStatus)
onActivated(() => {
  if (!statusSource) loadRecords()
})
</script>
