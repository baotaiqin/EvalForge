<template>
  <a-modal
    :open="open"
    :width="isFullscreen ? '100vw' : '1100px'"
    :centered="!isFullscreen"
    :wrap-class-name="isFullscreen ? 'qa-report-modal-fullscreen' : ''"
    :footer="null"
    :body-style="isFullscreen ? { height: 'calc(100vh - 110px)', overflow: 'auto' } : { height: '72vh', overflow: 'auto' }"
    @cancel="handleClose"
  >
    <template #title>
      <div style="display: flex; align-items: center; justify-content: space-between; padding-right: 40px;">
        <span>
          <span v-if="view !== 'list'" style="cursor: pointer; margin-right: 8px;" @click="backToList">
            <ArrowLeftOutlined />
          </span>
          {{ modalTitle }}
        </span>
        <a-space>
          <a-tooltip title="导出PDF">
            <DownloadOutlined
              v-if="canExportCurrentView"
              style="cursor: pointer; font-size: 16px;"
              @click="handleExportPdf"
            />
          </a-tooltip>
          <a-tooltip :title="isFullscreen ? '退出全屏' : '全屏'">
            <FullscreenExitOutlined
              v-if="isFullscreen"
              style="cursor: pointer; font-size: 16px;"
              @click="toggleFullscreen"
            />
            <FullscreenOutlined
              v-else
              style="cursor: pointer; font-size: 16px;"
              @click="toggleFullscreen"
            />
          </a-tooltip>
        </a-space>
      </div>
    </template>

    <!-- 列表视图：历史报告 / 对比报告两个 Tab -->
    <template v-if="view === 'list'">
      <a-tabs v-model:activeKey="activeTab">
        <a-tab-pane key="history" tab="历史报告">
          <a-alert
            type="info"
            show-icon
            style="margin-bottom: 12px;"
            message="请先前往「模型分组」下对目标模型分组新建测试报告，再回到此处勾选多份报告发起对比分析。"
          />
          <div style="margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center;">
            <span style="color: #666; font-size: 13px;">仅选择已完成的报告，至少选择两份后可发起对比分析</span>
            <a-button
              type="primary"
              size="small"
              :disabled="selectedReportIds.length < 2"
              @click="handleStartCompare"
            >对比已选（{{ selectedReportIds.length }}）</a-button>
          </div>
          <div v-if="loading" style="text-align: center; padding: 40px 0;">
            <a-spin />
          </div>
          <template v-else>
            <div v-for="group in groupedReports" :key="group.groupId" style="margin-bottom: 20px;">
              <div style="font-weight: 600; font-size: 14px; margin-bottom: 6px; padding-bottom: 4px; border-bottom: 1px solid #e8e8e8;">
                {{ group.groupName }}
              </div>
              <a-list :data-source="group.reports" size="small">
                <template #renderItem="{ item }">
                  <a-list-item>
                    <template #actions>
                      <a-badge :status="reportBadgeStatus(item.status)" :text="reportStatusText(item.status)" />
                      <a @click="openReportDetail(item.id)">详情</a>
                      <a-popconfirm title="确定删除该报告吗？" @confirm="handleDeleteReport(item.id)">
                        <a style="color: #ff4d4f;">删除</a>
                      </a-popconfirm>
                    </template>
                    <a-checkbox
                      :checked="selectedReportIds.includes(item.id)"
                      :disabled="item.status !== 'completed'"
                      @change="event => toggleSelect(item.id, event.target.checked)"
                      style="margin-right: 8px;"
                    />
                    <a-list-item-meta>
                      <template #title>
                        <span style="font-size: 13px;">{{ item.createdAt }}</span>
                      </template>
                      <template #description>涉及 {{ (item.selections || []).length }} 个子评测</template>
                    </a-list-item-meta>
                  </a-list-item>
                </template>
              </a-list>
            </div>
            <a-empty v-if="groupedReports.length === 0" description="暂无历史报告" />
          </template>
        </a-tab-pane>

        <a-tab-pane key="comparison" tab="对比报告">
          <div v-if="loading" style="text-align: center; padding: 40px 0;">
            <a-spin />
          </div>
          <template v-else>
            <a-list v-if="modelEvalStore.reportComparisons.length" :data-source="modelEvalStore.reportComparisons" size="small">
              <template #renderItem="{ item }">
                <a-list-item>
                  <template #actions>
                    <a-badge :status="comparisonBadgeStatus(item.status)" :text="comparisonStatusText(item.status)" />
                    <a @click="openComparisonDetail(item.id)">详情</a>
                    <a-popconfirm title="确定删除该对比记录吗？" @confirm="handleDeleteComparison(item.id)">
                      <a style="color: #ff4d4f;">删除</a>
                    </a-popconfirm>
                  </template>
                  <a-list-item-meta>
                    <template #title>
                      <span style="font-size: 13px;">{{ item.createdAt }}</span>
                      <a-tag :color="item.comparisonMode === 'regression' ? 'orange' : 'blue'" style="margin-left: 4px;">
                        {{ comparisonModeText(item.comparisonMode) }}
                      </a-tag>
                      <span style="color: #999; font-weight: normal;">{{ comparisonParticipantsText(item) }}</span>
                    </template>
                    <template #description>涉及 {{ (item.reportIds || []).length }} 份报告</template>
                  </a-list-item-meta>
                </a-list-item>
              </template>
            </a-list>
            <a-empty v-if="!loading && modelEvalStore.reportComparisons.length === 0" description="暂无对比记录" />
          </template>
        </a-tab-pane>
      </a-tabs>
    </template>

    <!-- 报告详情视图 -->
    <template v-else-if="view === 'reportDetail'">
      <div v-if="reportDetailLoading" style="text-align: center; padding: 40px 0;">
        <a-spin />
      </div>
      <div v-else-if="!viewingReport">
        <a-empty description="报告不存在" />
      </div>
      <div v-else ref="reportDetailRef">
        <a-alert
          v-if="viewingReport.status === 'generating'"
          type="info"
          show-icon
          message="报告生成中，请稍候..."
          style="margin-bottom: 16px;"
        />
        <a-alert
          v-else-if="viewingReport.status === 'failed'"
          type="error"
          show-icon
          :message="`报告生成失败：${viewingReport.errorMessage || '未知错误'}`"
          style="margin-bottom: 16px;"
        />
        <a-card
          v-if="viewingReport.overallSummary"
          title="模型总评价"
          size="small"
          style="margin-bottom: 16px; background: #f0f5ff; border-color: #adc6ff;"
        >
          <div style="white-space: pre-wrap;">{{ viewingReport.overallSummary }}</div>
        </a-card>
        <a-card
          v-for="item in viewingReport.subEvalSummaries || []"
          :key="item.subEvalId"
          size="small"
          style="margin-bottom: 12px; background: #fffbe6; border-color: #ffe58f;"
        >
          <template #title>
            {{ item.subEvalName }}
            <span v-if="item.subEvalVersion" style="color: #999; font-weight: normal; font-size: 12px;">{{ item.subEvalVersion }}</span>
          </template>
          <template #extra>
            <a-tag v-if="item.accuracy != null" :color="getScoreTagColor(item.accuracy)">
              {{ (item.accuracy * 100).toFixed(1) }}分
            </a-tag>
            <a-tag v-if="item.durationMs != null">{{ formatDurationMinutes(item.durationMs) }}</a-tag>
          </template>
          <div style="white-space: pre-wrap;">{{ item.summary }}</div>
        </a-card>
      </div>
    </template>

    <!-- 发起报告对比视图 -->
    <template v-else-if="view === 'compareForm'">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="将按各报告共同覆盖的子评测类型进行交叉对比，并给出总体结论与排名。"
      />
      <div style="margin-bottom: 16px;">
        <div style="font-weight: 500; margin-bottom: 8px;">已选报告：</div>
        <a-tag v-for="item in selectedReportInfos" :key="item.id" style="margin-bottom: 6px;">
          {{ item.groupName }}（{{ item.createdAt }}）
        </a-tag>
      </div>
      <a-form layout="vertical">
        <a-form-item label="对比场景" required>
          <a-radio-group v-model:value="compareMode">
            <a-radio-button value="performance">模型性能比较</a-radio-button>
            <a-radio-button value="regression">模型回归验证</a-radio-button>
          </a-radio-group>
          <div style="color: #999; font-size: 12px; margin-top: 4px;">
            {{ compareMode === 'regression'
              ? '适用于同一模型不同批次/时间点的测评结果对比，判断结果是否一致、差异体现在哪里。'
              : '适用于不同模型之间横向比较，评述综合表现最优的模型。' }}
          </div>
        </a-form-item>
        <a-form-item label="评审模型" required>
          <a-select
            v-model:value="compareJudgeModelId"
            placeholder="选择用于生成对比分析的大模型"
            style="width: 100%;"
            show-search
            :popup-match-select-width="false"
            :options="modelOptions"
          />
        </a-form-item>
      </a-form>
      <div style="text-align: right;">
        <a-button style="margin-right: 8px;" @click="backToList">取消</a-button>
        <a-button type="primary" :loading="compareSubmitting" @click="handleSubmitCompare">确定</a-button>
      </div>
    </template>

    <!-- 对比详情视图 -->
    <template v-else-if="view === 'comparisonDetail'">
      <div v-if="comparisonDetailLoading" style="text-align: center; padding: 40px 0;">
        <a-spin />
      </div>
      <div v-else-if="!viewingComparison">
        <a-empty description="对比记录不存在" />
      </div>
      <div v-else ref="comparisonDetailRef">
        <a-alert
          v-if="viewingComparison.status === 'generating'"
          type="info"
          show-icon
          message="对比分析生成中，请稍候..."
          style="margin-bottom: 16px;"
        />
        <a-alert
          v-else-if="viewingComparison.status === 'failed'"
          type="error"
          show-icon
          :message="`对比生成失败：${viewingComparison.errorMessage || '未知错误'}`"
          style="margin-bottom: 16px;"
        />
        <div style="margin-bottom: 16px; color: #666; font-size: 13px;">
          <a-tag :color="viewingComparison.comparisonMode === 'regression' ? 'orange' : 'blue'">
            {{ comparisonModeText(viewingComparison.comparisonMode) }}
          </a-tag>
          对比对象：
          <a-tag v-for="info in comparisonDetailParticipants" :key="info.id" style="margin-bottom: 4px;">
            {{ info.groupName }}（{{ info.createdAt }}）
          </a-tag>
        </div>
        <a-card
          v-if="viewingComparison.overallConclusion"
          title="总结论与排名"
          size="small"
          style="margin-bottom: 16px; background: #f0f5ff; border-color: #adc6ff;"
        >
          <div style="white-space: pre-wrap;">{{ viewingComparison.overallConclusion }}</div>
        </a-card>
        <a-card
          v-for="item in viewingComparison.subtypeComparisons || []"
          :key="item.subType"
          size="small"
          style="margin-bottom: 12px; background: #fffbe6; border-color: #ffe58f;"
        >
          <template #title>{{ item.subTypeName }}</template>
          <template #extra>
            <a-tag
              v-for="participant in item.participants || []"
              :key="participant.reportId"
              :color="getScoreTagColor(participant.accuracy)"
              style="margin-bottom: 4px;"
            >
              {{ participant.groupName }}{{ participant.accuracy != null ? `：${(participant.accuracy * 100).toFixed(1)}分` : '' }}
              {{ participant.durationMs != null ? `（${formatDurationMinutes(participant.durationMs)}）` : '' }}
            </a-tag>
          </template>
          <div style="white-space: pre-wrap;">{{ item.comparison }}</div>
        </a-card>
      </div>
    </template>
  </a-modal>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import {
  ArrowLeftOutlined,
  FullscreenOutlined,
  FullscreenExitOutlined,
  DownloadOutlined
} from '@ant-design/icons-vue'
import { exportElementToPdf } from '@/utils/reportExport.js'
import { useConfigStore } from '@/stores/config.js'
import { useModelEvalStore } from '@/stores/modelEval.js'
import { MODEL_REPORT_STATUS_MAP, getScoreTagColor, formatDurationMinutes } from '@/utils/constants.js'

const props = defineProps({
  open: { type: Boolean, default: false }
})
const emit = defineEmits(['update:open'])

const configStore = useConfigStore()
const modelEvalStore = useModelEvalStore()
const view = ref('list')
const activeTab = ref('history')
const loading = ref(false)
const selectedReportIds = ref([])
const isFullscreen = ref(false)
const reportDetailRef = ref(null)
const comparisonDetailRef = ref(null)

const viewingReportId = ref(null)
const viewingReport = ref(null)
const reportDetailLoading = ref(false)
let reportPollTimer = null
let reportRequestToken = 0

const compareReportIds = ref([])
const compareJudgeModelId = ref(null)
const compareMode = ref('performance')
const compareSubmitting = ref(false)

const viewingComparisonId = ref(null)
const viewingComparison = ref(null)
const comparisonDetailLoading = ref(false)
let comparisonPollTimer = null
let comparisonRequestToken = 0

const modalTitle = computed(() => {
  if (view.value === 'reportDetail') return '报告详情'
  if (view.value === 'compareForm') return '对比历史报告'
  if (view.value === 'comparisonDetail') return '对比分析详情'
  return '报告'
})

const canExportCurrentView = computed(() => {
  if (view.value === 'reportDetail') return viewingReport.value?.status === 'completed'
  if (view.value === 'comparisonDetail') return viewingComparison.value?.status === 'completed'
  return false
})

const modelOptions = computed(() => configStore.models.map(model => ({
  label: `${model.name} (${model.provider})`,
  value: model.id
})))

const groupedReports = computed(() => {
  const groupMap = {}
  for (const report of modelEvalStore.modelReports) {
    const groupId = report.groupId || '__unknown__'
    if (!groupMap[groupId]) {
      const group = modelEvalStore.groups.find(item => item.id === groupId)
      groupMap[groupId] = {
        groupId,
        groupName: group ? group.name : '未知分组',
        reports: []
      }
    }
    groupMap[groupId].reports.push(report)
  }
  return Object.values(groupMap)
})

function resolveReportInfos(reportIds) {
  return (reportIds || []).map(id => {
    const report = modelEvalStore.modelReports.find(item => item.id === id)
    const group = report ? modelEvalStore.groups.find(item => item.id === report.groupId) : null
    return {
      id,
      groupName: group ? group.name : '未知分组',
      createdAt: report ? report.createdAt : ''
    }
  })
}

function comparisonParticipantsText(item) {
  return resolveReportInfos(item.reportIds)
    .map(info => `${info.groupName}(${info.createdAt})`)
    .join(' vs ')
}

const selectedReportInfos = computed(() => resolveReportInfos(compareReportIds.value))
const comparisonDetailParticipants = computed(() =>
  resolveReportInfos(viewingComparison.value?.reportIds)
)

watch(() => props.open, async visible => {
  if (!visible) {
    // 关闭时仅停止轮询，保留当前视图，重新打开时继续查看当前详情。
    stopReportPolling()
    stopComparisonPolling()
    return
  }

  loading.value = true
  try {
    await Promise.all([
      modelEvalStore.fetchModelReports(),
      modelEvalStore.fetchGroups(),
      modelEvalStore.fetchReportComparisons()
    ])
  } catch (error) {
    message.error(error.message || '加载报告列表失败')
  } finally {
    loading.value = false
  }

  if (view.value === 'reportDetail' && viewingReportId.value) {
    reportRequestToken += 1
    loadReportDetail(viewingReportId.value, reportRequestToken)
  } else if (view.value === 'comparisonDetail' && viewingComparisonId.value) {
    comparisonRequestToken += 1
    loadComparisonDetail(viewingComparisonId.value, comparisonRequestToken)
  }
}, { immediate: true })

function reportBadgeStatus(status) {
  return MODEL_REPORT_STATUS_MAP[status]?.badge || 'default'
}

function reportStatusText(status) {
  return MODEL_REPORT_STATUS_MAP[status]?.text || status
}

const comparisonBadgeStatus = reportBadgeStatus
const comparisonStatusText = reportStatusText

function comparisonModeText(mode) {
  return mode === 'regression' ? '回归验证' : '性能比较'
}

function toggleFullscreen() {
  isFullscreen.value = !isFullscreen.value
}

function handleClose() {
  isFullscreen.value = false
  emit('update:open', false)
}

function backToList() {
  stopReportPolling()
  stopComparisonPolling()
  activeTab.value = view.value === 'comparisonDetail' ? 'comparison' : 'history'
  view.value = 'list'
  viewingReportId.value = null
  viewingReport.value = null
  viewingComparisonId.value = null
  viewingComparison.value = null
  reportDetailLoading.value = false
  comparisonDetailLoading.value = false
}

function toggleSelect(id, checked) {
  if (checked) {
    if (!selectedReportIds.value.includes(id)) selectedReportIds.value.push(id)
  } else {
    selectedReportIds.value = selectedReportIds.value.filter(reportId => reportId !== id)
  }
}

function handleStartCompare() {
  if (selectedReportIds.value.length < 2) {
    message.warning('请至少选择两份已完成的报告')
    return
  }
  compareReportIds.value = [...selectedReportIds.value]
  compareJudgeModelId.value = null
  compareMode.value = 'performance'
  configStore.fetchModels()
  view.value = 'compareForm'
}

async function handleSubmitCompare() {
  if (compareReportIds.value.length < 2) {
    message.warning('请至少选择两份历史报告')
    return
  }
  if (!compareJudgeModelId.value) {
    message.warning('请选择评审模型')
    return
  }

  compareSubmitting.value = true
  try {
    const comparison = await modelEvalStore.createReportComparison({
      judgeModelId: compareJudgeModelId.value,
      reportIds: [...compareReportIds.value],
      comparisonMode: compareMode.value
    })
    message.success('对比已开始生成，请稍后查看')
    selectedReportIds.value = []
    await openComparisonDetail(comparison.id)
  } catch (error) {
    message.error(error?.message || '创建报告对比失败')
  } finally {
    compareSubmitting.value = false
  }
}

async function handleDeleteReport(id) {
  try {
    await modelEvalStore.deleteModelReport(id)
    selectedReportIds.value = selectedReportIds.value.filter(reportId => reportId !== id)
    message.success('报告已删除')
  } catch (error) {
    message.error(error?.message || '删除报告失败')
  }
}

async function handleDeleteComparison(id) {
  try {
    await modelEvalStore.deleteReportComparison(id)
    message.success('对比记录已删除')
  } catch (error) {
    message.error(error?.message || '删除对比记录失败')
  }
}

function stopReportPolling() {
  if (reportPollTimer) clearTimeout(reportPollTimer)
  reportPollTimer = null
}

async function loadReportDetail(reportId, token) {
  try {
    const data = await modelEvalStore.fetchModelReport(reportId)
    if (token !== reportRequestToken) return
    viewingReport.value = data
    modelEvalStore.updateModelReportInList(data)
  } catch (error) {
    if (token === reportRequestToken && reportDetailLoading.value) {
      message.error(error.message || '获取报告详情失败')
    }
  } finally {
    if (token === reportRequestToken) reportDetailLoading.value = false
  }
  if (props.open && token === reportRequestToken && view.value === 'reportDetail' && viewingReport.value?.status === 'generating') {
    reportPollTimer = setTimeout(() => loadReportDetail(reportId, token), 3000)
  }
}

function openReportDetail(reportId) {
  stopComparisonPolling()
  stopReportPolling()
  reportRequestToken += 1
  viewingReportId.value = reportId
  viewingReport.value = null
  reportDetailLoading.value = true
  view.value = 'reportDetail'
  loadReportDetail(reportId, reportRequestToken)
}

function stopComparisonPolling() {
  if (comparisonPollTimer) clearTimeout(comparisonPollTimer)
  comparisonPollTimer = null
}

async function loadComparisonDetail(comparisonId, token) {
  try {
    const data = await modelEvalStore.fetchReportComparison(comparisonId)
    if (token !== comparisonRequestToken) return
    viewingComparison.value = data
    modelEvalStore.updateReportComparisonInList(data)
  } catch (error) {
    if (token === comparisonRequestToken && comparisonDetailLoading.value) {
      message.error(error.message || '获取对比结果失败')
    }
  } finally {
    if (token === comparisonRequestToken) comparisonDetailLoading.value = false
  }
  if (props.open && token === comparisonRequestToken && view.value === 'comparisonDetail' && viewingComparison.value?.status === 'generating') {
    comparisonPollTimer = setTimeout(() => loadComparisonDetail(comparisonId, token), 3000)
  }
}

function openComparisonDetail(comparisonId) {
  stopReportPolling()
  stopComparisonPolling()
  comparisonRequestToken += 1
  viewingComparisonId.value = comparisonId
  viewingComparison.value = null
  comparisonDetailLoading.value = true
  view.value = 'comparisonDetail'
  loadComparisonDetail(comparisonId, comparisonRequestToken)
}

async function handleExportPdf() {
  if (view.value === 'reportDetail') {
    const group = modelEvalStore.groups.find(item => item.id === viewingReport.value?.groupId)
    try {
      await exportElementToPdf(
        reportDetailRef.value,
        `模型测评报告_${group ? group.name : viewingReport.value?.id}.pdf`
      )
    } catch (error) {
      message.error(`导出PDF失败：${error.message || '未知错误'}`)
    }
  } else if (view.value === 'comparisonDetail') {
    try {
      await exportElementToPdf(
        comparisonDetailRef.value,
        `模型对比报告_${viewingComparison.value?.createdAt || viewingComparison.value?.id}.pdf`
      )
    } catch (error) {
      message.error(`导出PDF失败：${error.message || '未知错误'}`)
    }
  }
}

onUnmounted(() => {
  stopReportPolling()
  stopComparisonPolling()
})
</script>

<style>
/* 全屏模式下让弹窗占满整个视口；未使用 scoped 是因为 a-modal 会 teleport 到 body 下。 */
.qa-report-modal-fullscreen .ant-modal {
  top: 0;
  margin: 0;
  max-width: 100vw;
  padding-bottom: 0;
}

.qa-report-modal-fullscreen .ant-modal-content {
  height: 100vh;
  border-radius: 0;
  display: flex;
  flex-direction: column;
}

.qa-report-modal-fullscreen .ant-modal-body {
  flex: 1;
  overflow: hidden;
}
</style>
