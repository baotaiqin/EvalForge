<template>
  <div ref="wrapperRef" class="eval-table-wrapper">
    <a-table
      :columns="columns"
      :data-source="filteredRecords"
      :loading="evalStore.loading"
      row-key="id"
      :scroll="{ x: 1500 }"
      :pagination="pagination"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'rowIndex'">
          {{ (pagination.current - 1) * pagination.pageSize + index + 1 }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-badge
            :status="EVAL_STATUS_BADGE_MAP[record.status] || 'default'"
            :text="EVAL_STATUS_MAP[record.status]?.text || record.status"
          />
        </template>
        <template v-else-if="column.key === 'type'">
          {{ EVAL_TARGET_TYPE_MAP[record.type]?.text || record.type }}
        </template>
        <template v-else-if="column.key === 'targets'">
          <a-tooltip :title="(record.targets || []).map(target => target.label).join('\n')">
            <div class="targets-cell">
              <a-tag v-for="target in (record.targets || []).slice(0, 2)" :key="target.id">
                {{ target.label }}
              </a-tag>
              <a-tag v-if="(record.targets || []).length > 2">
                +{{ record.targets.length - 2 }}
              </a-tag>
            </div>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'datasets'">
          <a-tag v-for="name in (record.datasetNames || [])" :key="name">{{ name }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="emit('edit', record)">编辑</a>
            <a-popconfirm title="确定删除这条评测记录吗？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
            <a @click="emit('export', record)">导出</a>
            <router-link :to="`/agent-eval/records/${record.id}`">
              <a-button type="link" size="small">详情</a-button>
            </router-link>
          </a-space>
        </template>
      </template>
    </a-table>
    <div
      v-if="resizingColumn"
      class="resize-guide-line"
      :style="{ left: `${resizeGuideLeft}px` }"
    />
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import dayjs from 'dayjs'
import { message } from 'ant-design-vue'
import { useEvaluationStore } from '@/stores/evaluation.js'
import {
  EVAL_STATUS_BADGE_MAP,
  EVAL_STATUS_MAP,
  EVAL_TARGET_TYPE_MAP
} from '@/utils/constants.js'

const emit = defineEmits(['edit', 'export'])
const props = defineProps({
  filters: { type: Object, default: null }
})

const evalStore = useEvaluationStore()
const wrapperRef = ref(null)
const pagination = reactive({
  current: 1,
  pageSize: 10,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 条`
})

const filteredRecords = computed(() => {
  const filters = props.filters || {}
  const nameKeyword = String(filters.name || '').toLowerCase()
  return evalStore.records.filter(record => {
    if (nameKeyword && !String(record.name || '').toLowerCase().includes(nameKeyword)) return false
    if (filters.status && record.status !== filters.status) return false
    if (filters.type && record.type !== filters.type) return false

    if (filters.targetLabels?.length) {
      const labels = (record.targets || []).map(target => target.label)
      if (!filters.targetLabels.every(label => labels.includes(label))) return false
    }
    if (filters.datasetNames?.length) {
      const names = record.datasetNames || []
      if (!filters.datasetNames.every(name => names.includes(name))) return false
    }
    if (filters.timeRange?.length === 2 && record.startTime) {
      const start = dayjs(record.startTime)
      const from = filters.timeRange[0]
      const to = filters.timeRange[1]
      if (start.isValid() && from && start.isBefore(from, 'second')) return false
      if (start.isValid() && to && start.isAfter(to, 'second')) return false
    }
    return true
  })
})

const columns = reactive([
  { title: '序号', key: 'rowIndex', width: 70, fixed: 'left' },
  { title: '评测名称', dataIndex: 'name', key: 'name', width: 280, ellipsis: true },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100 },
  { title: '类型', dataIndex: 'type', key: 'type', width: 90 },
  { title: '被评测对象', key: 'targets', width: 260 },
  { title: '数据集', key: 'datasets', width: 180 },
  { title: '开始时间', dataIndex: 'startTime', key: 'startTime', width: 180 },
  { title: '结束时间', dataIndex: 'endTime', key: 'endTime', width: 180 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' }
])

const MIN_COLUMN_WIDTH = 60
const resizingColumn = ref(null)
const resizeGuideLeft = ref(0)
let resizingStartX = 0
let resizingStartWidth = 0

watch(() => props.filters, () => {
  pagination.current = 1
}, { deep: true })

watch(filteredRecords, records => {
  const pageCount = Math.max(1, Math.ceil(records.length / pagination.pageSize))
  if (pagination.current > pageCount) pagination.current = pageCount
})

function handleTableChange(pager) {
  pagination.current = pager.current
  pagination.pageSize = pager.pageSize
}

function headerCellProps(column) {
  return {
    class: 'resizable-header-cell',
    onMousedown: event => {
      if (column.fixed || !column.width) return
      const cell = event.currentTarget
      if (cell.offsetWidth - event.offsetX <= 6) startResize(column, event)
    }
  }
}

function startResize(column, event) {
  resizingColumn.value = column
  resizingStartX = event.clientX
  resizingStartWidth = column.width
  updateGuideLeft(event.clientX)
  window.addEventListener('mousemove', handleResizeMove)
  window.addEventListener('mouseup', stopResize)
  event.preventDefault()
}

function updateGuideLeft(clientX) {
  const rect = wrapperRef.value?.getBoundingClientRect()
  if (rect) resizeGuideLeft.value = clientX - rect.left
}

function handleResizeMove(event) {
  if (!resizingColumn.value) return
  const width = Math.max(MIN_COLUMN_WIDTH, resizingStartWidth + event.clientX - resizingStartX)
  resizingColumn.value.width = width
  updateGuideLeft(event.clientX)
}

function stopResize() {
  resizingColumn.value = null
  window.removeEventListener('mousemove', handleResizeMove)
  window.removeEventListener('mouseup', stopResize)
}

async function handleDelete(id) {
  try {
    await evalStore.deleteRecord(id)
    message.success('评测记录已删除')
  } catch (error) {
    message.error(error?.message || '删除评测记录失败')
  }
}

onBeforeUnmount(stopResize)

columns.forEach(column => {
  column.customHeaderCell = () => headerCellProps(column)
})
</script>

<style scoped>
.eval-table-wrapper {
  position: relative;
}

.targets-cell {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.targets-cell :deep(.ant-tag) {
  margin-inline-end: 0;
}

:deep(.resizable-header-cell) {
  position: relative;
}

:deep(.resizable-header-cell)::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 6px;
  height: 100%;
  cursor: col-resize;
}

.resize-guide-line {
  position: absolute;
  top: 0;
  bottom: 0;
  width: 2px;
  background: #1677ff;
  pointer-events: none;
  z-index: 10;
}
</style>
