<template>
  <a-modal
    v-model:open="visible"
    title="上传数据集"
    width="960px"
    :confirm-loading="submitting"
    @ok="handleSubmit"
  >
    <a-form ref="formRef" :model="formState" :rules="rules" layout="vertical" style="margin-top: 16px;">
      <a-form-item label="数据集名称" name="name">
        <a-input v-model:value="formState.name" placeholder="请输入数据集名称" />
      </a-form-item>

      <a-form-item label="上传文件">
        <a-upload-dragger
          :file-list="fileList"
          :before-upload="beforeUpload"
          :multiple="false"
          @remove="handleRemove"
          :accept="ACCEPT_FILE_TYPES"
        >
          <p class="ant-upload-drag-icon"><InboxOutlined /></p>
          <p class="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p class="ant-upload-hint">支持 .doc、.docx、.xls、.xlsx、.json、.jsonl、.ndjson、.zip 格式文件</p>
        </a-upload-dragger>
      </a-form-item>

      <!-- 解析状态 -->
      <a-form-item v-if="parsing">
        <div style="text-align: center; padding: 12px;"><a-spin tip="正在解析文件..." /></div>
      </a-form-item>

      <!-- 格式说明 -->
      <a-form-item>
        <a-alert type="info" show-icon>
          <template #message>文件格式说明</template>
          <template #description>
            <div class="format-description">
              <p>Word 数据集：问题和答案需要分成单独的行，建议按模板填写 <strong>问题：</strong> 和 <strong>答案：</strong></p>
              <p>图片问答 ZIP：推荐包含 <strong>samples.json 或 samples.jsonl</strong> 和 <strong>images/</strong> 目录</p>
              <p>文件问答 ZIP：推荐包含 <strong>samples.json 或 samples.jsonl</strong> 和 <strong>files/</strong> 目录</p>
              <p>
                PPT 对比 ZIP：samples.json/samples.jsonl 中每条数据使用
                <strong>{"expected": {"type": "pptx", "file": "expected/xxx.pptx"}}</strong>
                引用标准答案 PPT，并在 zip 内提供 <strong>expected/</strong> 目录存放对应的 .ppt/.pptx 文件
              </p>
              <p>
                HTML 报告对比 ZIP：samples.json/samples.jsonl 中每条数据使用
                <strong>{"expected": {"type": "html", "file": "expected/xxx.html"}}</strong>
                引用标准答案 HTML 报告，并在 zip 内提供 <strong>expected/</strong> 目录存放对应的 .html 文件
              </p>
              <div class="template-downloads">
                <span class="template-download-title">模板下载：</span>
                <a-space wrap>
                  <a-button
                    v-for="template in datasetTemplates"
                    :key="template.href"
                    size="small"
                    :href="template.href"
                    :download="template.fileName"
                  >
                    <template #icon><DownloadOutlined /></template>
                    {{ template.label }}
                  </a-button>
                </a-space>
              </div>
            </div>
          </template>
        </a-alert>
      </a-form-item>

      <!-- 解析预览 -->
      <a-form-item v-if="parsedItems.length > 0" label="解析预览">
        <a-alert :message="parseSummaryMessage" type="success" show-icon style="margin-bottom: 8px;">
          <template #description>
            <div class="parse-summary">
              <a-tag :color="parsedHasPpt ? 'purple' : parsedHasHtml ? 'purple' : parsedHasMultimodal ? 'blue' : 'green'">
                {{ parsedHasPpt ? 'PPT 对比数据集' : (parsedHasHtml ? 'HTML 报告对比数据集' : (parsedHasMultimodal ? '多模态数据集' : '纯文本数据集')) }}
              </a-tag>
              <a-tag v-for="modality in parsedModalities" :key="modality">{{ modality }}</a-tag>
              <a-tag v-if="parsedSourceFormat">{{ parsedSourceFormat }}</a-tag>
              <a-tag v-if="parsedImageCount > 0" color="purple">图片 {{ parsedImageCount }}</a-tag>
              <a-tag v-if="parsedFileCount > 0" color="processing">文件 {{ parsedFileCount }}</a-tag>
              <a-tag v-if="parsedPptCount > 0" color="magenta">标准答案PPT {{ parsedPptCount }}</a-tag>
              <a-tag v-if="parsedHtmlCount > 0" color="purple">标准答案HTML {{ parsedHtmlCount }}</a-tag>
            </div>
          </template>
        </a-alert>

        <a-table
          :columns="previewColumns"
          :data-source="parsedItems"
          :pagination="pagination"
          size="small"
          row-key="id"
          :scroll="{ x: 980, y: 400 }"
          @change="handleTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'question'">
              <div class="question-content">
                <div>{{ getQuestionText(record) }}</div>
                <div v-if="getQuestionImages(record).length" class="preview-image-list">
                  <a-image
                    v-for="(img, i) in getQuestionImages(record)"
                    :key="getResourceIdentity(img) || i"
                    :src="resolveResourceUrl(img)"
                    :width="48"
                    :height="48"
                    style="object-fit: cover; border-radius: 4px;"
                    :fallback="fallbackImg"
                    :preview="{ src: resolveResourceUrl(img) }"
                  />
                </div>
              </div>
            </template>
            <template v-else-if="column.key === 'files'">
              <div v-if="getQuestionFiles(record).length" class="preview-file-column-list">
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
            <template v-else-if="column.key === 'expectedAnswer'">
              <a-tag v-if="hasExpectedPpt(record)" color="magenta">标准答案PPT：{{ record.expected.fileName || record.expected.file || '' }}</a-tag>
              <a-tag v-else-if="hasExpectedHtml(record)" color="purple">标准答案HTML：{{ record.expected.fileName || record.expected.file || '' }}</a-tag>
              <div v-else-if="getExpectedAnswer(record)" class="expected-answer-content" v-html="renderExpectedAnswer(getExpectedAnswer(record))" />
              <span v-else style="color: #999;">无</span>
            </template>
          </template>
        </a-table>
      </a-form-item>

      <a-form-item label="描述" name="description">
        <a-textarea v-model:value="formState.description" placeholder="数据集描述（可选）" :rows="3" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, ref, reactive, watch } from 'vue'
import { message } from 'ant-design-vue'
import { DownloadOutlined, InboxOutlined } from '@ant-design/icons-vue'
import { useDatasetStore } from '@/stores/dataset.js'
import { uploadFile, BASE_URL } from '@/api/request.js'
import { renderMarkdown } from '@/utils/markdown.js'

const ACCEPT_FILE_TYPES = '.doc,.docx,.xls,.xlsx,.json,.jsonl,.ndjson,.zip'
const ACCEPT_FILE_PATTERN = /\.(doc|docx|xls|xlsx|json|jsonl|ndjson|zip)$/i
const ACCEPT_FILE_ERROR_MESSAGE = '仅支持上传 .doc/.docx/.xls/.xlsx/.json/.jsonl/.ndjson/.zip 格式文件'

const datasetTemplates = [
  { label: 'JSON 问答模板', fileName: 'text-dataset-template.json', href: '/templates/datasets/text-dataset-template.json' }
]

function normalizeBaseUrl() {
  if (!BASE_URL) return ''
  return BASE_URL.endsWith('/') ? BASE_URL.slice(0, -1) : BASE_URL
}

function getResourceUrl(resource) {
  if (resource === null || resource === undefined) return ''
  if (typeof resource === 'string') return resource.trim()
  if (typeof resource === 'object') {
    return firstNonBlank(
      resource.url, resource.fileUrl, resource.fileURL,
      resource.imageUrl, resource.imageURL, resource.path,
      resource.filePath, resource.storagePath, resource.source,
      resource.src, resource.href
    )
  }
  return String(resource).trim()
}

function resolveResourceUrl(resource) {
  const rawUrl = getResourceUrl(resource)
  if (!rawUrl) return ''
  const text = String(rawUrl).trim()
  if (!text) return ''
  if (text.startsWith('data:') || text.startsWith('blob:') || text.startsWith('http://') || text.startsWith('https://')) return text
  const baseUrl = normalizeBaseUrl()
  if (text.startsWith('/')) return baseUrl + text
  return `${baseUrl}/${text}`
}

/** 渲染预期回答：将 markdown 图片语法中的相对路径补全为完整 URL */
function renderExpectedAnswer(text) {
  if (!text) return ''
  const baseUrl = normalizeBaseUrl()
  const resolved = String(text).replace(/!\[([^\]]*)\]\((\/api\/[^)]+)\)/g, `![$1](${baseUrl}$2)`)
  return renderMarkdown(resolved)
}

// 图片加载失败时的占位图
const fallbackImg = 'data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iODAiIGhlaWdodD0iODAiIHZpZXdCb3g9IjAgMCA4MCA4MCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48cmVjdCB3aWR0aD0iODAiIGhlaWdodD0iODAiIGZpbGw9IiNmMGYwZjAiLz48dGV4dCB4PSI0MCIgeT0iNDQiIGZvbnQtc2l6ZT0iMTIiIHRleHQtYW5jaG9yPSJtaWRkbGUiIGZpbGw9IiM5OTkiPk5vIEltYWdlPC90ZXh0Pjwvc3ZnPg=='

const props = defineProps({ open: Boolean })
const emit = defineEmits(['update:open', 'success'])
const datasetStore = useDatasetStore()
const visible = ref(false)
const submitting = ref(false)
const parsing = ref(false)
const formRef = ref()
const fileList = ref([])
const parsedItems = ref([])
const parsedFileName = ref('')
const parsedFilePath = ref('')
const parsedDatasetType = ref('text')
const parsedHasMultimodal = ref(false)
const parsedModalities = ref(['text'])
const parsedSchemaVersion = ref('v1')
const parsedSourceFormat = ref('')

watch(() => props.open, (val) => { visible.value = val })
watch(visible, (val) => emit('update:open', val))

const formState = reactive({ name: '', description: '' })
const rules = {
  name: [{ required: true, message: '请输入数据集名称' }]
}

const previewColumns = [
  { title: '序号', key: 'index', width: 60, customRender: ({ index }) => (pagination.current - 1) * pagination.pageSize + index + 1 },
  { title: '用户提问', key: 'question', width: 280 },
  { title: '关联文件', key: 'files', width: 260 },
  { title: '预期回答', key: 'expectedAnswer', width: 380 }
]

const pagination = reactive({ current: 1, pageSize: 5, showSizeChanger: true, pageSizeOptions: ['5', '10', '20', '50'] })

function handleTableChange(page) {
  pagination.current = page.current
  pagination.pageSize = page.pageSize
}

const parsedImageCount = computed(() => parsedItems.value.reduce((count, item) => count + getQuestionImages(item).length, 0))
const parsedFileCount = computed(() => parsedItems.value.reduce((count, item) => count + getQuestionFiles(item).length, 0))
const parsedPptCount = computed(() => parsedItems.value.filter(hasExpectedPpt).length)
const parsedHasPpt = computed(() => parsedPptCount.value > 0)
const parsedHtmlCount = computed(() => parsedItems.value.filter(hasExpectedHtml).length)
const parsedHasHtml = computed(() => parsedHtmlCount.value > 0)
const parseSummaryMessage = computed(() => {
  const typeText = parsedHasMultimodal.value ? '多模态' : '纯文本'
  const imageText = parsedImageCount.value > 0 ? `，包含 ${parsedImageCount.value} 张图片` : ''
  const fileText = parsedFileCount.value > 0 ? `，包含 ${parsedFileCount.value} 个文件` : ''
  return `成功解析 ${parsedItems.value.length} 条数据（${typeText}${imageText}${fileText}）`
})

function beforeUpload(file) {
  if (!ACCEPT_FILE_PATTERN.test(file.name)) {
    message.error(ACCEPT_FILE_ERROR_MESSAGE)
    return false
  }
  fileList.value = [file]
  parseFile(file)
  return false
}

function handleRemove() {
  fileList.value = []
  resetParsedResult()
}

function resetParsedResult() {
  parsedItems.value = []
  parsedFileName.value = ''
  parsedFilePath.value = ''
  parsedDatasetType.value = 'text'
  parsedHasMultimodal.value = false
  parsedModalities.value = ['text']
  parsedSchemaVersion.value = 'v1'
  parsedSourceFormat.value = ''
  pagination.current = 1
}

// 调用后端 API 解析文件
async function parseFile(file) {
  parsing.value = true
  resetParsedResult()
  try {
    const formData = new FormData()
    formData.append('file', file)
    const res = await uploadFile('/api/datasets/parse', formData)
    if (res.code === 0 && res.data) {
      const items = Array.isArray(res.data.items) ? res.data.items : []
      const inferred = inferDatasetMetadata(items)
      const mergedModalities = mergeModalities(res.data.modalities, inferred.modalities)
      const mergedHasMultimodal = Boolean(
        res.data.hasMultimodal || res.data.datasetType === 'multimodal' || inferred.hasMultimodal ||
        mergedModalities.includes('image') || mergedModalities.includes('file')
      )
      parsedItems.value = items
      parsedFileName.value = res.data.fileName || file.name || ''
      parsedFilePath.value = res.data.filePath || ''
      parsedDatasetType.value = mergedHasMultimodal ? 'multimodal' : (res.data.datasetType || 'text')
      parsedHasMultimodal.value = mergedHasMultimodal
      parsedModalities.value = mergedModalities
      parsedSchemaVersion.value = res.data.schemaVersion || 'v1'
      parsedSourceFormat.value = res.data.sourceFormat || getFileExtension(file.name)
      if (items.length) message.success(`解析成功，共识别 ${items.length} 条数据`)
      else message.warning('文件解析结果为空，请检查文件格式是否正确')
    } else {
      message.error(res.message || '文件解析失败')
    }
  } catch (err) {
    console.error('文件解析失败：', err)
    message.error('文件解析失败：' + (err.response?.data?.message || err.message || '网络错误'))
  } finally {
    parsing.value = false
  }
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (fileList.value.length === 0) {
    message.warning('请上传文件')
    return
  }
  if (parsedItems.value.length === 0) {
    message.warning('文件解析结果为空，请检查文件格式')
    return
  }

  const inferred = inferDatasetMetadata(parsedItems.value)
  const finalModalities = mergeModalities(parsedModalities.value, inferred.modalities)
  const finalHasMultimodal = parsedHasMultimodal.value || inferred.hasMultimodal ||
    finalModalities.includes('image') || finalModalities.includes('file')

  submitting.value = true
  try {
    await datasetStore.createDataset({
      name: formState.name,
      // 暂保留 text_qa，避免影响当前后端列表逻辑；多模态/文件问答类型可由 datasetType 与 modalities 扩展。
      type: 'text_qa',
      hasExpectedResult: parsedItems.value.some(item => Boolean(getExpectedAnswer(item)) || hasExpectedPpt(item) || hasExpectedHtml(item)),
      description: formState.description,
      fileName: parsedFileName.value,
      filePath: parsedFilePath.value,
      items: parsedItems.value,
      datasetType: finalHasMultimodal ? 'multimodal' : (parsedDatasetType.value || 'text'),
      hasMultimodal: finalHasMultimodal,
      modalities: finalModalities,
      schemaVersion: parsedSchemaVersion.value,
      sourceFormat: parsedSourceFormat.value
    })
    message.success('数据集上传成功')
    formState.name = ''
    formState.description = ''
    fileList.value = []
    resetParsedResult()
    visible.value = false
    emit('success')
  } catch (err) {
    message.error('创建数据集失败：' + (err.message || '未知错误'))
  } finally {
    submitting.value = false
  }
}

function getQuestionText(record) {
  return firstNonBlank(record?.question, record?.input?.text, record?.prompt, record?.query, record?.text) || '（无文本提问）'
}

function getExpectedAnswer(record) {
  return firstNonBlank(record?.expectedAnswer, record?.expected?.text, record?.answer, record?.expectedResult, record?.expected_result)
}

function getQuestionImages(record) {
  return uniqueResourceList([
    ...toArray(record?.questionImages), ...toArray(record?.question_images),
    ...toArray(record?.images), ...toArray(record?.input?.images)
  ])
}

function getQuestionFiles(record) {
  return uniqueResourceList([
    ...toArray(record?.questionFiles), ...toArray(record?.question_files),
    ...toArray(record?.files), ...toArray(record?.input?.files)
  ])
}

function hasExpectedPpt(record) {
  const type = record?.expected?.type
  return typeof type === 'string' && ['pptx', 'ppt'].includes(type.toLowerCase())
}

function hasExpectedHtml(record) {
  const type = record?.expected?.type
  return typeof type === 'string' && ['html', 'htm'].includes(type.toLowerCase())
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
  if (Array.isArray(value)) return value.filter(item => item !== null && item !== undefined && (typeof item !== 'string' || item.trim() !== ''))
  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return []
    if (text.startsWith('[') && text.endsWith(']')) {
      try { return toArray(JSON.parse(text)) } catch { /* continue parsing */ }
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
      resource.id, resource.uid, resource.url, resource.fileUrl, resource.fileURL,
      resource.imageUrl, resource.imageURL, resource.path, resource.filePath,
      resource.storagePath, resource.name, resource.fileName,
      resource.originalName, resource.originalFilename
    )
    if (identity) return identity
    try { return JSON.stringify(resource) } catch { return String(resource) }
  }
  return String(resource).trim()
}

function getDisplayFileName(file, index) {
  if (file === null || file === undefined) return `文件${index + 1}`
  if (typeof file === 'object') {
    const directName = firstNonBlank(file.name, file.fileName, file.originalName, file.originalFilename, file.displayName)
    if (directName) return normalizeDisplayFileName(directName)
    const pathName = extractFileNameFromPath(getResourceUrl(file))
    return pathName || `文件${index + 1}`
  }
  const pathName = extractFileNameFromPath(file)
  return pathName || `文件${index + 1}`
}

function extractFileNameFromPath(value) {
  if (value === null || value === undefined) return ''
  const raw = String(value).trim()
  if (!raw) return ''
  const withoutQuery = raw.split('?')[0].split('#')[0].replace(/\\/g, '/')
  const slashIndex = withoutQuery.lastIndexOf('/')
  const rawName = slashIndex >= 0 ? withoutQuery.substring(slashIndex + 1) : withoutQuery
  return rawName ? normalizeDisplayFileName(rawName) : ''
}

function normalizeDisplayFileName(name) {
  const decoded = safeDecodeURIComponent(String(name).trim())
  return decoded.replace(/^\d+[_-][a-zA-Z0-9]{6,}_/, '').replace(/^\d+_/, '')
}

function safeDecodeURIComponent(value) {
  try { return decodeURIComponent(value) } catch { return value }
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (value !== null && value !== undefined && String(value).trim() !== '') return String(value).trim()
  }
  return ''
}

function inferDatasetMetadata(items) {
  const modalities = new Set(['text'])
  for (const item of items || []) {
    if (getQuestionImages(item).length) modalities.add('image')
    if (getQuestionFiles(item).length || hasExpectedPpt(item) || hasExpectedHtml(item)) modalities.add('file')
    for (const modality of normalizeModalities(item?.modalities)) modalities.add(modality)
  }
  const modalityList = Array.from(modalities)
  return {
    hasMultimodal: modalityList.includes('image') || modalityList.includes('file'),
    modalities: modalityList
  }
}

function mergeModalities(...values) {
  const result = new Set()
  for (const value of values) {
    for (const modality of normalizeModalities(value)) result.add(modality)
  }
  if (result.size === 0) result.add('text')
  if (!result.has('text')) result.add('text')
  return Array.from(result)
}

function getFileExtension(fileName) {
  if (!fileName) return ''
  const dotIndex = fileName.lastIndexOf('.')
  if (dotIndex < 0 || dotIndex >= fileName.length - 1) return ''
  return fileName.substring(dotIndex + 1).toLowerCase()
}
</script>

<style scoped>
.question-content,
.expected-answer-content {
  max-height: 300px;
  overflow-y: auto;
  word-break: break-word;
  white-space: pre-wrap;
}

.expected-answer-content {
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
}

.expected-answer-content :deep(p) {
  margin: 0 0 6px;
  word-break: break-word;
}

.preview-image-list,
.preview-file-column-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}

.preview-file-column-list {
  max-height: 220px;
  overflow-y: auto;
}

.preview-file-tag {
  max-width: 220px;
  margin-inline-end: 0;
}

.preview-file-link {
  display: inline-block;
  max-width: 190px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.format-description {
  font-size: 12px;
  line-height: 1.8;
}

.format-description p {
  margin: 4px 0;
  color: #666;
}

.format-description p:first-child {
  color: rgba(0, 0, 0, 0.88);
}

.template-downloads {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.template-download-title {
  color: rgba(0, 0, 0, 0.88);
  white-space: nowrap;
}

.parse-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}
</style>

