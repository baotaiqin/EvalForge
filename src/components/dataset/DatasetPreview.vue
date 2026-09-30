<template>
  <!-- 外层数据集详情预览 -->
  <a-modal
    v-model:open="visible"
    :title="`数据集预览 - ${dataset?.name || ''}`"
    width="90vw"
    :style="{ maxWidth: '1600px' }"
    :footer="null"
  >
    <div v-if="dataset" style="margin-top: 16px;">
      <!-- 数据集基础信息描述 -->
      <a-descriptions
        :column="summaryColumn"
        bordered
        size="small"
        class="dataset-summary"
      >
        <a-descriptions-item label="数据条数">
          {{ dataset.itemCount ?? resolvedItems.length }}
        </a-descriptions-item>
        <a-descriptions-item label="包含预期结果">
          <a-tag :color="dataset.hasExpectedResult ? 'green' : 'orange'">
            {{ dataset.hasExpectedResult ? '是' : '否' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="数据集类型">
          <a-tag :color="isMultimodalDataset ? 'blue' : 'green'">
            {{ isMultimodalDataset ? '多模态' : '纯文本' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="图片数量">
          <a-tag v-if="totalImageCount > 0" color="purple">{{ totalImageCount }}</a-tag>
          <span v-else>-</span>
        </a-descriptions-item>
        <a-descriptions-item label="文件数量">
          <a-tag v-if="totalFileCount > 0" color="processing">{{ totalFileCount }}</a-tag>
          <span v-else>-</span>
        </a-descriptions-item>
      </a-descriptions>

      <!-- 数据表格 -->
      <a-table
        :columns="previewColumns"
        :data-source="resolvedItems"
        row-key="id"
        :pagination="pagination"
        size="small"
        :scroll="{ x: 1200, y: 500 }"
        @change="handleTableChange"
      >
        <template #bodyCell="{ column, record }">
          <!-- 用户提问 -->
          <template v-if="column.key === 'question'">
            <div class="question-content">
              <div>{{ getQuestionText(record) }}</div>
              <div v-if="getQuestionImages(record).length" class="preview-image-list">
                <a-image
                  v-for="(img, i) in getQuestionImages(record)"
                  :key="getResourceIdentity(img) || i"
                  :src="resolveResourceUrl(img)"
                  :width="60"
                  :height="60"
                  style="object-fit: cover; border-radius: 4px;"
                  :fallback="fallbackImg"
                  :preview="{ src: resolveResourceUrl(img) }"
                />
              </div>
            </div>
          </template>

          <!-- 关联文件 -->
          <template v-else-if="column.key === 'files'">
            <div v-if="getQuestionFiles(record).length" class="preview-file-list">
              <a-tag
                v-for="(file, i) in getQuestionFiles(record)"
                :key="getResourceIdentity(file) || i"
                class="preview-file-tag"
              >
                <a
                  v-if="resolveResourceUrl(file)"
                  :href="resolveResourceUrl(file)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="preview-file-link"
                  :title="resolveResourceUrl(file)"
                >{{ getDisplayFileName(file, i) }}</a>
                <span v-else>{{ getDisplayFileName(file, i) }}</span>
              </a-tag>
            </div>
            <span v-else style="color: #999;">-</span>
          </template>

          <!-- 预期回答 -->
          <template v-else-if="column.key === 'expectedAnswer'">
            <a-tag v-if="hasExpectedPpt(record)" color="magenta">
              标准答案PPT：{{ record.expected.fileName || record.expected.file || '' }}
            </a-tag>
            <a-tag v-else-if="hasExpectedHtml(record)" color="purple">
              标准答案HTML：{{ record.expected.fileName || record.expected.file || '' }}
            </a-tag>
            <div
              v-else-if="getExpectedAnswer(record)"
              class="expected-answer-content"
              v-html="renderAnswer(getExpectedAnswer(record))"
            />
            <span v-else style="color: #999;">无预期结果</span>
          </template>
        </template>
      </a-table>
    </div>
    <a-empty v-else description="暂无数据集信息" />
  </a-modal>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { BASE_URL } from '@/api/request.js'
import { renderMarkdown } from '@/utils/markdown.js'

const props = defineProps({
  open: Boolean,
  dataset: { type: Object, default: null }
})
const emit = defineEmits(['update:open'])

const visible = ref(false)
const pagination = reactive({
  current: 1,
  pageSize: 5,
  showSizeChanger: true,
  pageSizeOptions: ['5', '10', '20', '50']
})

watch(() => props.open, (value) => { visible.value = value })
watch(visible, (value) => emit('update:open', value))
watch(() => props.dataset, () => { pagination.current = 1 })

const summaryColumn = {
  xs: 1,
  sm: 2,
  md: 3,
  lg: 4,
  xl: 5,
  xxl: 5
}

const previewColumns = [
  {
    title: '序号',
    key: 'index',
    width: 60,
    customRender: ({ index }) => (pagination.current - 1) * pagination.pageSize + index + 1
  },
  { title: '用户提问', key: 'question', width: 320 },
  { title: '关联文件', key: 'files', width: 300 },
  { title: '预期回答', key: 'expectedAnswer', width: 520 }
]

const resolvedItems = computed(() => Array.isArray(props.dataset?.items) ? props.dataset.items : [])

const resolvedModalities = computed(() => {
  const modalities = new Set()

  // 仍保留旧数据推断逻辑，用于判断数据集类型，不在预览弹窗中展示模态字段。
  for (const modality of normalizeModalities(props.dataset?.modalities)) modalities.add(modality)
  for (const item of resolvedItems.value) {
    if (getQuestionImages(item).length > 0) modalities.add('image')
    if (getQuestionFiles(item).length > 0 || hasExpectedPpt(item) || hasExpectedHtml(item)) modalities.add('file')
    for (const modality of normalizeModalities(item?.modalities)) modalities.add(modality)
  }

  if (modalities.size === 0) modalities.add('text')
  if (!modalities.has('text')) modalities.add('text')
  return Array.from(modalities)
})

const isMultimodalDataset = computed(() => {
  if (props.dataset?.hasMultimodal === true) return true
  if (props.dataset?.datasetType === 'multimodal') return true
  return resolvedModalities.value.some(modality => modality === 'image' || modality === 'file')
})

const totalImageCount = computed(() => resolvedItems.value.reduce((count, item) => count + getQuestionImages(item).length, 0))
const totalFileCount = computed(() => resolvedItems.value.reduce((count, item) => count + getQuestionFiles(item).length, 0))

function handleTableChange(page) {
  pagination.current = page.current
  pagination.pageSize = page.pageSize
}

function normalizeBaseUrl() {
  if (!BASE_URL) return ''
  return BASE_URL.endsWith('/') ? BASE_URL.slice(0, -1) : BASE_URL
}

function getResourceUrl(resource) {
  if (resource === null || resource === undefined) return ''
  if (typeof resource === 'string') return resource.trim()
  if (typeof resource === 'object') {
    return firstNonBlank(
      resource.url,
      resource.fileUrl,
      resource.fileURL,
      resource.imageUrl,
      resource.imageURL,
      resource.path,
      resource.filePath,
      resource.storagePath,
      resource.source,
      resource.src,
      resource.href
    )
  }
  return String(resource).trim()
}

function resolveResourceUrl(resource) {
  const rawUrl = getResourceUrl(resource)
  if (!rawUrl) return ''

  const text = String(rawUrl).trim()
  if (!text) return ''
  if (text.startsWith('data:') || text.startsWith('blob:') || text.startsWith('http://') || text.startsWith('https://')) {
    return text
  }

  const baseUrl = normalizeBaseUrl()
  if (text.startsWith('/')) return baseUrl + text
  return `${baseUrl}/${text}`
}

/** 渲染预期答案：将 markdown 图片相对路径补全为完整 URL */
function renderAnswer(text) {
  if (!text) return ''
  const baseUrl = normalizeBaseUrl()
  const resolved = String(text).replace(/!\[([^\]]*)\]\((\/api\/[^)]+)\)/g, `![$1](${baseUrl}$2)`)
  return renderMarkdown(resolved)
}

const fallbackImg = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iODAiIGhlaWdodD0iODAiIHZpZXdCb3g9IjAgMCA4MCA4MCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iODAiIGhlaWdodD0iODAiIGZpbGw9IiNmMGYwZjAiLz48dGV4dCB4PSI0MCIgeT0iNDQiIGZvbnQtc2l6ZT0iMTIiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5OTkiPk5vIEltYWdlPC90ZXh0Pjwvc3ZnPg=='

function getQuestionText(record) {
  return firstNonBlank(
    record?.question,
    record?.input?.text,
    record?.prompt,
    record?.query,
    record?.text
  ) || '（无文本提问）'
}

function getExpectedAnswer(record) {
  return firstNonBlank(
    record?.expectedAnswer,
    record?.expected?.text,
    record?.answer,
    record?.expectedResult,
    record?.expected_result
  )
}

// PPT 对比评测的标准答案：item.expected = { type: 'pptx'/'ppt', ... }
function hasExpectedPpt(record) {
  const type = record?.expected?.type
  return typeof type === 'string' && ['pptx', 'ppt'].includes(type.toLowerCase())
}

// HTML 报告对比评测的标准答案：item.expected = { type: 'html'/'htm', ... }
function hasExpectedHtml(record) {
  const type = record?.expected?.type
  return typeof type === 'string' && ['html', 'htm'].includes(type.toLowerCase())
}

function getQuestionImages(record) {
  return uniqueResourceList([
    ...toArray(record?.questionImages),
    ...toArray(record?.question_images),
    ...toArray(record?.images),
    ...toArray(record?.input?.images)
  ])
}

function getQuestionFiles(record) {
  return uniqueResourceList([
    ...toArray(record?.questionFiles),
    ...toArray(record?.question_files),
    ...toArray(record?.files),
    ...toArray(record?.input?.files)
  ])
}

function normalizeModalities(value) {
  const result = new Set()
  for (const item of toArray(value)) {
    const normalized = normalizeModality(item)
    if (normalized) result.add(normalized)
  }
  return Array.from(result)
}

function normalizeModality(value) {
  if (value === null || value === undefined) return ''
  const text = String(value).trim().toLowerCase()
  if (!text) return ''
  if (text.includes('image') || text.includes('img') || text.includes('picture')) return 'image'
  if (['file', 'document', 'doc', 'pdf', 'xls', 'xlsx', 'csv', 'zip'].some(word => text.includes(word))) return 'file'
  if (text.includes('text')) return 'text'
  return 'text'
}

function toArray(value) {
  if (value === null || value === undefined) return []
  if (Array.isArray(value)) {
    return value.filter(item => item !== null && item !== undefined && (typeof item !== 'string' || item.trim() !== ''))
  }
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return []
    if (text.startsWith('[') && text.endsWith(']')) {
      try {
        const parsed = JSON.parse(text)
        return toArray(parsed)
      } catch {
        // Treat an invalid JSON array string as a normal value below.
      }
    }
    if (text.includes(',')) return text.split(',').map(item => item.trim()).filter(Boolean)
    return [value]
  }
  return [value]
}

function uniqueResourceList(list) {
  const result = []
  const seen = new Set()
  for (const item of list || []) {
    if (item === null || item === undefined) continue
    const key = getResourceIdentity(item)
    if (!key || seen.has(key)) continue
    seen.add(key)
    result.push(item)
  }
  return result
}

function getResourceIdentity(resource) {
  if (resource === null || resource === undefined) return ''
  if (typeof resource === 'string') return resource.trim()
  if (typeof resource === 'object') {
    const identity = firstNonBlank(
      resource.id,
      resource.uid,
      resource.url,
      resource.fileUrl,
      resource.fileURL,
      resource.imageUrl,
      resource.imageURL,
      resource.path,
      resource.filePath,
      resource.storagePath,
      resource.name,
      resource.fileName,
      resource.originalName,
      resource.originalFilename
    )
    if (identity) return identity
    try {
      return JSON.stringify(resource)
    } catch {
      return String(resource)
    }
  }
  return String(resource).trim()
}

function getDisplayFileName(file, index) {
  if (file === null || file === undefined) return `文件${index + 1}`
  if (typeof file === 'object') {
    const directName = firstNonBlank(
      file.name,
      file.fileName,
      file.originalName,
      file.originalFilename,
      file.displayName
    )
    if (directName) return normalizeDisplayFileName(directName)
    const pathName = extractFileNameFromPath(getResourceUrl(file))
    if (pathName) return pathName
    return `文件${index + 1}`
  }
  const pathName = extractFileNameFromPath(file)
  return pathName || `文件${index + 1}`
}

function extractFileNameFromPath(value) {
  if (value === null || value === undefined) return ''
  const raw = String(value).trim()
  if (!raw) return ''
  const withoutQuery = raw.split('?')[0].split('#')[0].replace(/\\/g, '/')
  const lastSlashIndex = withoutQuery.lastIndexOf('/')
  const rawName = lastSlashIndex >= 0 ? withoutQuery.substring(lastSlashIndex + 1) : withoutQuery
  if (!rawName) return ''
  return normalizeDisplayFileName(rawName)
}

function normalizeDisplayFileName(name) {
  const decoded = safeDecodeURIComponent(String(name).trim())
  // 后端上传文件名常见格式：时间戳_uuid_CP_Test_Data.csv
  return decoded
    .replace(/^\d+_[a-zA-Z0-9_-]{6,}_/, '')
    .replace(/^\d+_/, '')
}

function safeDecodeURIComponent(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (value !== null && value !== undefined && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return ''
}
</script>

<style scoped>
.dataset-summary {
  margin-bottom: 16px;
}

.dataset-summary :deep(.ant-descriptions-item-label) {
  width: 110px;
  white-space: nowrap;
  padding: 10px 14px;
}

.dataset-summary :deep(.ant-descriptions-item-content) {
  min-width: 64px;
  padding: 10px 14px;
}

.question-content {
  max-height: 300px;
  overflow: auto;
  word-break: break-word;
  white-space: pre-wrap;
}

.preview-image-list,
.preview-file-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}

.preview-file-list {
  gap: 6px;
  max-height: 260px;
  overflow-y: auto;
}

.preview-file-tag {
  max-width: 260px;
  margin-inline-end: 0;
}

.preview-file-link {
  display: inline-block;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.expected-answer-content {
  max-height: 300px;
  overflow: auto;
  word-break: break-word;
  white-space: normal;
  line-height: 1.6;
}

.expected-answer-content :deep(img) {
  display: block;
  max-width: 100%;
  max-height: 200px;
  object-fit: contain;
  border-radius: 4px;
  margin: 6px 0;
  cursor: pointer;
}

.expected-answer-content :deep(p) {
  margin: 0 0 6px;
  word-break: break-word;
}
</style>
