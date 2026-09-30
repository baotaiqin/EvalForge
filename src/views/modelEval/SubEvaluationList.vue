<template>
  <div class="page-content">
    <div class="page-header">
      <div>
        <h2>{{ group?.name || '子评测' }}</h2>
        <div v-if="group?.remark" class="group-remark">{{ group.remark }}</div>
      </div>
      <a-space wrap>
        <a-button
          :disabled="selectedRowKeys.length === 0"
          :loading="batchRunning"
          @click="handleBatchRun"
        >
          批量开始测评<span v-if="selectedRowKeys.length">（{{ selectedRowKeys.length }}）</span>
        </a-button>
        <a-button @click="reportCenterVisible = true">
          <template #icon><HistoryOutlined /></template>
          测评报告
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新建子评测
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="modelEvalStore.subEvaluations"
      :loading="modelEvalStore.loading"
      :row-selection="rowSelection"
      :expanded-row-keys="expandedRowKeys"
      row-key="id"
      :pagination="{ pageSize: 10 }"
      @expand="handleExpand"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'subType'">
          {{ modelEvalStore.subEvalTypeLabel(record.subType) }}
        </template>
        <template v-else-if="column.key === 'targetType'">
          {{ EVAL_TARGET_TYPE_MAP[record.targetType]?.text || record.targetType || '--' }}
        </template>
        <template v-else-if="column.key === 'targets'">
          <a-tooltip :title="(record.targets || []).map(target => target.label).join('\n')">
            <a-tag v-for="target in (record.targets || []).slice(0, 2)" :key="target.id">
              {{ target.label || target.name || target.id }}
            </a-tag>
            <a-tag v-if="(record.targets || []).length > 2">+{{ record.targets.length - 2 }}</a-tag>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'datasets'">
          <a-tag v-for="name in (record.datasetNames || [])" :key="name">{{ name }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space wrap>
            <a-button type="link" size="small" :loading="runningIds.includes(record.id)" @click="handleRun(record)">开始测评</a-button>
            <a @click="openEdit(record)">编辑</a>
            <a @click="openCopy(record)">复制</a>
            <a-popconfirm title="确定删除该子评测吗？" ok-text="删除" cancel-text="取消" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>

      <template #expandedRowRender="{ record }">
        <a-table
          :columns="versionColumns"
          :data-source="modelEvalStore.subEvalRecordsMap[record.id] || []"
          :loading="Boolean(recordsLoading[record.id])"
          row-key="id"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record: version }">
            <template v-if="column.key === 'status'">
              <a-badge
                :status="EVAL_STATUS_BADGE_MAP[version.status] || 'default'"
                :text="EVAL_STATUS_MAP[version.status]?.text || version.status"
              />
            </template>
            <template v-else-if="column.key === 'accuracy'">
              {{ version.accuracy == null ? '--' : `${(version.accuracy * 100).toFixed(1)}%` }}
            </template>
            <template v-else-if="column.key === 'action'">
              <a-space>
                <router-link :to="`/agent-eval/records/${version.evalId || version.id}`">
                  <a-button type="link" size="small">详情</a-button>
                </router-link>
                <a-popconfirm title="确定删除该版本记录吗？" @confirm="handleDeleteVersion(record.id, version.id)">
                  <a style="color: #ff4d4f;">删除</a>
                </a-popconfirm>
              </a-space>
            </template>
          </template>
        </a-table>
        <a-empty
          v-if="!recordsLoading[record.id] && !(modelEvalStore.subEvalRecordsMap[record.id] || []).length"
          description="暂无测评版本"
        />
      </template>
    </a-table>

    <SubEvaluationForm
      v-model:open="formVisible"
      :group-id="groupId"
      :editing-record="editingRecord"
      @success="handleFormSuccess"
    />
    <CopySubEvaluationModal
      v-model:open="copyVisible"
      :sub-eval="copyingSubEval"
      :current-group-id="groupId"
      @success="handleFormSuccess"
    />
    <ReportCenterModal v-model:open="reportCenterVisible" />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { HistoryOutlined, PlusOutlined } from '@ant-design/icons-vue'
import { useModelEvalStore } from '@/stores/modelEval.js'
import { EVAL_STATUS_MAP, EVAL_STATUS_BADGE_MAP, EVAL_TARGET_TYPE_MAP } from '@/utils/constants.js'
import SubEvaluationForm from '@/components/modelEval/SubEvaluationForm.vue'
import CopySubEvaluationModal from '@/components/modelEval/CopySubEvaluationModal.vue'
import ReportCenterModal from '@/components/modelEval/ReportCenterModal.vue'

const route = useRoute()
const modelEvalStore = useModelEvalStore()
const groupId = computed(() => String(route.params.groupId || ''))
const formVisible = ref(false)
const editingRecord = ref(null)
const copyVisible = ref(false)
const copyingSubEval = ref(null)
const reportCenterVisible = ref(false)
const selectedRowKeys = ref([])
const expandedRowKeys = ref([])
const recordsLoading = reactive({})
const runningIds = ref([])
const batchRunning = ref(false)

const group = computed(() => modelEvalStore.groups.find(item => String(item.id) === groupId.value))
const rowSelection = computed(() => ({
  selectedRowKeys: selectedRowKeys.value,
  onChange: keys => { selectedRowKeys.value = keys }
}))

const columns = [
  { title: '子评测类型', key: 'subType', dataIndex: 'subType', width: 150 },
  { title: '被评测对象类型', key: 'targetType', dataIndex: 'targetType', width: 140 },
  { title: '被评测对象', key: 'targets', width: 260 },
  { title: '数据集', key: 'datasets', width: 220 },
  { title: '备注', key: 'remark', dataIndex: 'remark', ellipsis: true },
  { title: '操作', key: 'action', width: 260, fixed: 'right' }
]

const versionColumns = [
  { title: '版本', dataIndex: 'version', key: 'version', width: 90 },
  { title: '状态', dataIndex: 'status', key: 'status', width: 110 },
  { title: '准确率', dataIndex: 'accuracy', key: 'accuracy', width: 120 },
  { title: '开始时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '操作', key: 'action', width: 130 }
]

async function loadGroupData() {
  if (!groupId.value) return
  try {
    await Promise.all([
      modelEvalStore.fetchGroups(),
      modelEvalStore.fetchSubEvaluations(groupId.value),
      modelEvalStore.fetchSubEvalTypes()
    ])
  } catch (error) {
    message.error(error.message || '加载子评测失败')
  }
}

watch(groupId, loadGroupData, { immediate: true })

function openCreate() {
  editingRecord.value = null
  formVisible.value = true
}

function openEdit(record) {
  editingRecord.value = record
  formVisible.value = true
}

function openCopy(record) {
  copyingSubEval.value = record
  copyVisible.value = true
}

async function handleExpand(expanded, record) {
  if (expanded) {
    if (!expandedRowKeys.value.includes(record.id)) expandedRowKeys.value = [...expandedRowKeys.value, record.id]
    if (!modelEvalStore.subEvalRecordsMap[record.id]) {
      recordsLoading[record.id] = true
      try {
        await modelEvalStore.fetchSubEvalRecords(record.id)
      } catch (error) {
        message.error(error.message || '加载版本历史失败')
      } finally {
        recordsLoading[record.id] = false
      }
    }
  } else {
    expandedRowKeys.value = expandedRowKeys.value.filter(id => id !== record.id)
  }
}

async function handleRun(record) {
  runningIds.value = [...runningIds.value, record.id]
  try {
    await modelEvalStore.runSubEvaluation(record.id)
    message.success('已开始测评')
    await Promise.all([
      modelEvalStore.fetchSubEvaluations(groupId.value),
      modelEvalStore.fetchSubEvalRecords(record.id)
    ])
  } catch (error) {
    message.error(error.message || '开始测评失败')
  } finally {
    runningIds.value = runningIds.value.filter(id => id !== record.id)
  }
}

async function handleBatchRun() {
  if (!selectedRowKeys.value.length) return
  batchRunning.value = true
  try {
    await modelEvalStore.batchRunSubEvaluations(selectedRowKeys.value)
    message.success('已提交批量测评')
    selectedRowKeys.value = []
    await modelEvalStore.fetchSubEvaluations(groupId.value)
  } catch (error) {
    message.error(error.message || '批量开始测评失败')
  } finally {
    batchRunning.value = false
  }
}

async function handleDelete(id) {
  try {
    await modelEvalStore.deleteSubEvaluation(id)
    expandedRowKeys.value = expandedRowKeys.value.filter(key => key !== id)
    message.success('子评测已删除')
  } catch (error) {
    message.error(error.message || '删除子评测失败')
  }
}

async function handleDeleteVersion(subEvalId, evalId) {
  try {
    await modelEvalStore.deleteSubEvalRecord(subEvalId, evalId)
    message.success('版本记录已删除')
  } catch (error) {
    message.error(error.message || '删除版本记录失败')
  }
}

async function handleFormSuccess() {
  await modelEvalStore.fetchSubEvaluations(groupId.value)
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
}

.group-remark {
  color: #888;
  font-size: 12px;
  margin-top: 4px;
}
</style>
