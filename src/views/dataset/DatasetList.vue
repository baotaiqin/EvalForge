<template>
  <div class="page-content">
    <div class="page-header">
      <h2>数据集管理</h2>
      <a-space>
        <a-input-search
          v-model:value="searchKeyword"
          placeholder="搜索数据集名称/描述"
          allow-clear
          style="width: 240px;"
        />
        <a-button type="primary" @click="uploadVisible = true">
          <template #icon><UploadOutlined /></template>
          上传数据集
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="filteredDatasets"
      :loading="datasetStore.loading"
      row-key="id"
      :pagination="{ pageSize: 10 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a-tooltip :title="record.starred ? '取消重点数据集' : '标记为重点数据集'">
            <StarFilled
              v-if="record.starred"
              class="star-icon star-active"
              @click="handleToggleStar(record)"
            />
            <StarOutlined v-else class="star-icon" @click="handleToggleStar(record)" />
          </a-tooltip>
          {{ record.name }}
        </template>

        <template v-else-if="column.key === 'datasetType'">
          <a-tag :color="typeTagColor(record)">{{ typeTagLabel(record) }}</a-tag>
        </template>

        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="handlePreview(record)">预览</a>
            <a @click="handleEdit(record)">编辑</a>
            <a-popconfirm
              title="确定删除该数据集吗？"
              ok-text="删除"
              cancel-text="取消"
              @confirm="handleDelete(record.id)"
            >
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <UploadDataset v-model:open="uploadVisible" @success="datasetStore.fetchDatasets()" />
    <DatasetPreview v-model:open="previewVisible" :dataset="currentDataset" />
    <DatasetEditor
      v-model:open="editVisible"
      :dataset="currentDataset"
      @success="datasetStore.fetchDatasets()"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { StarOutlined, StarFilled, UploadOutlined } from '@ant-design/icons-vue'
import { useDatasetStore } from '@/stores/dataset.js'
import UploadDataset from '@/components/dataset/UploadDataset.vue'
import DatasetPreview from '@/components/dataset/DatasetPreview.vue'
import DatasetEditor from '@/components/dataset/DatasetEditor.vue'

const datasetStore = useDatasetStore()
const uploadVisible = ref(false)
const previewVisible = ref(false)
const editVisible = ref(false)
const currentDataset = ref(null)
const searchKeyword = ref('')

const filteredDatasets = computed(() => {
  const keyword = searchKeyword.value.trim().toLowerCase()
  if (!keyword) return datasetStore.datasets
  return datasetStore.datasets.filter(dataset =>
    String(dataset.name || '').toLowerCase().includes(keyword) ||
    String(dataset.description || '').toLowerCase().includes(keyword)
  )
})

const columns = [
  { title: '数据集名称', dataIndex: 'name', key: 'name' },
  { title: '类型', dataIndex: 'datasetType', key: 'datasetType', width: 110 },
  { title: '数据条数', dataIndex: 'itemCount', key: 'itemCount', width: 100 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 180 },
  { title: '操作', key: 'action', width: 160, fixed: 'right' }
]

function hasExpectedType(record, types) {
  if (types.some(type => record?.expectedType === type || record?.expected_type === type)) return true
  return Array.isArray(record?.items) && record.items.some(item => {
    const type = String(item?.expected?.type || '').toLowerCase()
    return types.includes(type)
  })
}

function typeTagLabel(record) {
  if (record?.hasPptExpected || hasExpectedType(record, ['ppt', 'pptx'])) return 'PPT 对比'
  if (record?.hasHtmlExpected || hasExpectedType(record, ['html', 'htm'])) return 'HTML 报告对比'
  if (record?.hasMultimodal || record?.datasetType === 'multimodal') return '多模态'
  return '文本'
}

function typeTagColor(record) {
  if (record?.hasPptExpected || hasExpectedType(record, ['ppt', 'pptx'])) return 'purple'
  if (record?.hasHtmlExpected || hasExpectedType(record, ['html', 'htm'])) return 'purple'
  if (record?.hasMultimodal || record?.datasetType === 'multimodal') return 'blue'
  return 'green'
}

async function handlePreview(record) {
  try {
    currentDataset.value = await datasetStore.fetchDatasetDetail(record.id)
    previewVisible.value = true
  } catch (error) {
    message.error(error.message || '获取数据集详情失败')
  }
}

async function handleEdit(record) {
  try {
    currentDataset.value = await datasetStore.fetchDatasetDetail(record.id)
    editVisible.value = true
  } catch (error) {
    message.error(error.message || '获取数据集详情失败')
  }
}

async function handleDelete(id) {
  try {
    await datasetStore.deleteDataset(id)
    message.success('数据集删除成功')
  } catch (error) {
    message.error(error.message || '删除数据集失败')
  }
}

async function handleToggleStar(record) {
  const starred = !record.starred
  try {
    await datasetStore.updateDataset(record.id, { starred })
    const dataset = datasetStore.datasets.find(item => item.id === record.id)
    if (dataset) dataset.starred = starred
    message.success(starred ? '已标记为重点数据集' : '已取消重点标记')
  } catch (error) {
    message.error(error.message || '更新数据集标记失败')
  }
}

onMounted(() => {
  datasetStore.fetchDatasets()
})
</script>

<style scoped>
.star-icon {
  cursor: pointer;
  margin-right: 6px;
  color: #d9d9d9;
  font-size: 14px;
  vertical-align: -1px;
}

.star-icon:hover,
.star-active {
  color: #faad14;
}
</style>
