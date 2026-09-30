<template>
  <div class="compare-columns">
    <a-pagination
      v-if="results.length > 0"
      class="compare-pagination"
      v-model:current="currentPage"
      v-model:page-size="pageSize"
      :total="results.length"
      :page-size-options="['10', '20', '50']"
      show-size-changer
      :show-total="total => `共 ${total} 题`"
    />

    <div class="compare-table-wrapper">
      <table class="compare-table">
        <thead>
          <tr>
            <th class="question-col">问题</th>
            <th v-for="target in targets" :key="target.id" class="target-col">
              {{ target.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="{ result, index } in pagedItems"
            :id="`question-row-${index + 1}`"
            :key="result.id || result.questionId || index"
            :class="{ 'alt-row': index % 2 === 1 }"
          >
            <td class="question-col">
              <div class="question-number-row">
                <div class="question-number">#{{ index + 1 }}</div>
                <a-popconfirm
                  v-if="evaluationStatus !== 'running'"
                  title="确定重新测评该题吗？将覆盖该题已有结果，其余题目不受影响。"
                  @confirm="emit('rerunQuestion', index + 1)"
                >
                  <a class="rerun-question-link">重测该题</a>
                </a-popconfirm>
                <a-tooltip v-else title="评测正在执行中，暂无法重新测评该题">
                  <span class="rerun-question-link rerun-question-disabled">重测该题</span>
                </a-tooltip>
              </div>

              <div class="question-text">{{ getQuestionText(result) }}</div>

              <div v-if="getModalities(result).length" class="question-modalities">
                <a-tag
                  v-for="modality in getModalities(result)"
                  :key="modality"
                  :color="getModalityColor(modality)"
                >
                  {{ modality }}
                </a-tag>
              </div>

              <div v-if="getQuestionImages(result).length" class="question-images">
                <a-image
                  v-for="(img, i) in getQuestionImages(result)"
                  :key="getResourceIdentity(img) || i"
                  :src="resolveResourceUrl(img)"
                  width="60"
                  height="60"
                  style="object-fit: cover; border-radius: 4px; margin-right: 4px;"
                  :preview="{ src: resolveResourceUrl(img) }"
                />
              </div>

              <div v-if="getQuestionFiles(result).length" class="question-files">
                <div class="question-files-title">
                  <FileOutlined />
                  <span>关联文件</span>
                </div>
                <div class="question-file-tags">
                  <a-tag
                    v-for="(file, i) in getQuestionFiles(result)"
                    :key="getResourceIdentity(file) || i"
                    class="question-file-tag"
                  >
                    <a
                      v-if="resolveResourceUrl(file)"
                      :href="resolveResourceUrl(file)"
                      target="_blank"
                      rel="noopener noreferrer"
                      :title="resolveResourceUrl(file)"
                    >{{ getDisplayFileName(file, i) }}</a>
                    <span v-else>{{ getDisplayFileName(file, i) }}</span>
                  </a-tag>
                </div>
              </div>

              <div v-if="isPptExpected(result)" class="question-files">
                <div class="question-files-title">
                  <FileOutlined />
                  <span>标准答案PPT</span>
                </div>
                <a-tag color="green">{{ result.expected?.fileName || '标准答案.pptx' }}</a-tag>
              </div>

              <div v-if="isHtmlExpected(result)" class="question-files">
                <div class="question-files-title">
                  <FileOutlined />
                  <span>标准答案HTML</span>
                </div>
                <a-tag color="green">{{ result.expected?.fileName || '标准答案.html' }}</a-tag>
              </div>
            </td>

            <td v-for="target in targets" :key="target.id" class="target-col">
              <div v-if="!result.outputs?.[target.id]" style="text-align: center; padding: 20px 0;">
                <template v-if="getStepPhaseIndex(result, target) < 0">
                  <a-spin />
                  <div class="step-tracker">
                    <template v-for="(phase, i) in STEP_PHASES" :key="phase.key">
                      <span class="step-item" :class="`step-${getStepStatus(result, target, i)}`">
                        <CheckCircleFilled v-if="getStepStatus(result, target, i) === 'done'" class="step-icon" />
                        <LoadingOutlined v-else-if="getStepStatus(result, target, i) === 'active'" class="step-icon" />
                        <span v-else class="step-icon step-icon-pending"></span>
                        {{ phase.label }}
                      </span>
                      <span v-if="i < STEP_PHASES.length - 1" class="step-arrow">›</span>
                    </template>
                  </div>
                </template>
                <span v-else style="color: #999;">等待回答</span>
              </div>

              <template v-else>
                <template
                  v-for="(part, pIdx) in parseAnswer(result.outputs?.[target.id]?.answer).parts"
                  :key="pIdx"
                >
                  <a-collapse
                    v-if="part.type === 'tool_use'"
                    size="small"
                    style="margin-bottom: 8px;"
                  >
                    <a-collapse-panel :header="`${part.toolName}${part.useTarget ? ' → ' + part.useTarget : ''}`">
                      <template #extra>
                        <a-tag
                          :color="part.status === 'success' ? 'green' : part.status === 'pending' ? 'processing' : 'default'"
                          size="small"
                        >{{ part.status || 'unknown' }}</a-tag>
                      </template>
                      <div class="tool-content">{{ part.content || '无返回内容' }}</div>
                    </a-collapse-panel>
                  </a-collapse>

                  <div
                    v-else-if="part.type === 'text'"
                    class="answer-text markdown-body"
                    v-html="renderMarkdown(part.content, target.envUrl, result.outputs?.[target.id]?.imgMap)"
                  ></div>

                  <div v-else-if="part.type === 'file'" class="file-item">
                    <a-button
                      size="small"
                      @click="downloadFile(part.fileName, part.fileDownloadUrl, target.envUrl)"
                    >
                      <template #icon><FileOutlined /></template>
                      {{ part.fileName || '下载文件' }}
                    </a-button>
                    <a-tag v-if="part.fileType" size="small" style="margin-left: 4px;">{{ part.fileType }}</a-tag>
                  </div>
                </template>

                <div
                  v-if="parseAnswer(result.outputs?.[target.id]?.answer).parts.length === 0"
                  class="answer-text"
                >无输出</div>

                <div class="response-time">
                  <a-tag v-if="result.outputs?.[target.id]?.responseTime">
                    {{ formatDurationMinutes(result.outputs[target.id].responseTime) }}
                  </a-tag>
                </div>

                <div v-if="result.outputs?.[target.id]?.generatedFileUrl" class="file-item">
                  <a-button
                    size="small"
                    @click="downloadFile(result.outputs[target.id].generatedFileName, result.outputs[target.id].generatedFileUrl, target.envUrl)"
                  >
                    <template #icon><FileOutlined /></template>
                    {{ result.outputs[target.id].generatedFileName || '下载生成的文件' }}
                  </a-button>
                  <a-tag color="blue" size="small" style="margin-left: 4px;">
                    {{ isGeneratedHtmlFile(result.outputs[target.id].generatedFileName) ? '生成HTML' : '生成PPT' }}
                  </a-tag>
                </div>

                <DiffViewer
                  v-if="hasExpectedAnswer(result) && !isPptExpected(result) && !isHtmlExpected(result)"
                  :expected="result.expectedAnswer"
                  :actual="parseAnswer(result.outputs?.[target.id]?.answer).textContent || ''"
                  style="margin-top: 8px;"
                />

                <div v-if="shouldShowManualReview(result, target)" class="manual-review-score">
                  <a-tag color="orange">{{ getManualReviewLabel(result, target) }}</a-tag>
                  <span class="manual-review-desc">无参考答案，不参与自动评分</span>
                </div>
                <ScoreEditor
                  v-else
                  :scores="result.scores?.[target.id]"
                  @save="data => emit('updateScore', { resultId: result.id, targetId: target.id, ...data })"
                />
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <a-pagination
      v-if="results.length > 0"
      class="compare-pagination compare-pagination-bottom"
      v-model:current="currentPage"
      v-model:page-size="pageSize"
      :total="results.length"
      :page-size-options="['10', '20', '50']"
      show-size-changer
      :show-total="total => `共 ${total} 题`"
    />
  </div>
</template>

<script setup>
import { ref, computed, nextTick, watch } from 'vue'
import { FileOutlined, CheckCircleFilled, LoadingOutlined } from '@ant-design/icons-vue'
import DiffViewer from './DiffViewer.vue'
import ScoreEditor from './ScoreEditor.vue'
import { BASE_URL } from '@/api/request.js'
import { renderMarkdown } from '@/utils/markdown.js'
import { formatDurationMinutes } from '@/utils/constants.js'

const props = defineProps({
  targets: { type: Array, default: () => [] },
  results: { type: Array, default: () => [] },
  // 评测记录的当前状态；running 时禁用单题重测，避免与正在执行的任务冲突。
  evaluationStatus: { type: String, default: '' }
})

const emit = defineEmits(['updateScore', 'rerunQuestion'])

const pageSize = ref(10)
const currentPage = ref(1)

const pagedItems = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return props.results
    .map((result, index) => ({ result, index }))
    .slice(start, start + pageSize.value)
})

watch(() => props.results.length, length => {
  if (length === 0) {
    currentPage.value = 1
    return
  }
  const lastPage = Math.max(1, Math.ceil(length / pageSize.value))
  if (currentPage.value > lastPage) currentPage.value = lastPage
})

watch(pageSize, () => {
  currentPage.value = 1
})

const STEP_PHASES = [
  { key: 'calling', label: '调用中' },
  { key: 'answered', label: '已应答' },
  { key: 'scoring', label: '评分中' },
  { key: 'scored', label: '已评分' }
]

function normalizeBaseUrl() {
  if (!BASE_URL) return ''
  return BASE_URL.endsWith('/') ? BASE_URL.slice(0, -1) : BASE_URL
}

function resolveImageUrl(img) {
  return resolveResourceUrl(img)
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

  if (
    text.startsWith('data:') ||
    text.startsWith('blob:') ||
    text.startsWith('http://') ||
    text.startsWith('https://')
  ) {
    return text
  }

  const baseUrl = normalizeBaseUrl()
  if (text.startsWith('/')) return baseUrl + text
  return `${baseUrl}/${text}`
}

/** Download a generated or referenced file, joining relative file URLs with the environment URL. */
function downloadFile(fileName, fileDownloadUrl, envUrl) {
  if (!fileDownloadUrl) return

  const url = buildDownloadUrl(fileDownloadUrl, envUrl)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || 'download'
  link.target = '_blank'
  link.rel = 'noopener noreferrer'
  link.click()
}

function buildDownloadUrl(fileDownloadUrl, envUrl) {
  if (!fileDownloadUrl) return ''

  const rawUrl = String(fileDownloadUrl).trim()
  if (rawUrl.startsWith('http://') || rawUrl.startsWith('https://') || rawUrl.startsWith('data:') || rawUrl.startsWith('blob:')) {
    return rawUrl
  }
  if (!envUrl) return rawUrl

  const normalizedEnvUrl = String(envUrl).startsWith('http')
    ? String(envUrl).replace(/\/$/, '')
    : `http://${String(envUrl).replace(/\/$/, '')}`

  return rawUrl.startsWith('/')
    ? `${normalizedEnvUrl}${rawUrl}`
    : `${normalizedEnvUrl}/${rawUrl}`
}

/** Normalize answers from current and legacy response formats while keeping part order. */
function parseAnswer(value) {
  const empty = { textContent: '', parts: [] }
  if (value === null || value === undefined) return empty

  const text = typeof value === 'string' ? value : safeJsonStringify(value)
  if (!text) return empty

  const trimmed = text.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return { textContent: text, parts: [{ type: 'text', content: text }] }
  }

  try {
    const json = typeof value === 'object' ? value : JSON.parse(trimmed)

    // Current normalized shape: { textContent, parts: [...] }
    if (json && Array.isArray(json.parts)) {
      return {
        textContent: firstNonBlank(json.textContent, json.text, json.content, ''),
        parts: json.parts.map(normalizeAnswerPart).filter(Boolean)
      }
    }

    // Older shape: toolOutputs plus a textContent field.
    if (json && Array.isArray(json.toolOutputs) && json.textContent !== undefined) {
      const parts = json.toolOutputs.map(tool => normalizeAnswerPart({ type: 'tool_use', ...tool })).filter(Boolean)
      if (json.textContent) parts.push({ type: 'text', content: String(json.textContent) })
      return { textContent: String(json.textContent || ''), parts }
    }

    // Raw API shape: { retcode: 0, data: [...] }.
    if (json && Array.isArray(json.data)) return parseDataArray(json.data)

    // Some providers return the parts array directly.
    if (Array.isArray(json)) return parseDataArray(json)

    // A nested response may put the result array under data.data.
    if (json?.data && typeof json.data === 'object' && Array.isArray(json.data.data)) {
      return parseDataArray(json.data.data)
    }

    // Object form used by some SDK responses.
    if (json?.data && typeof json.data === 'object' && !Array.isArray(json.data)) {
      const data = json.data
      const content = firstNonBlank(data.md_content, data.answer, data.content, data.text, '')
      if (content) return { textContent: content, parts: [{ type: 'text', content }] }
    }
  } catch {
    // Non-JSON response bodies are shown as plain text below.
  }

  return { textContent: text, parts: [{ type: 'text', content: text }] }
}

function parseDataArray(dataList) {
  const parts = []
  const textParts = []

  for (const item of dataList || []) {
    if (!item || typeof item !== 'object') continue

    if (item.type === 'tool_use' || item.type === 'tool_call' || item.tool_name || item.toolName) {
      parts.push(normalizeAnswerPart({ type: 'tool_use', ...item }))
      continue
    }

    if (item.type === 'file' || item.fileDownloadUrl || item.file_download_url) {
      const filePart = normalizeAnswerPart({ type: 'file', ...item })
      if (filePart) parts.push(filePart)
      continue
    }

    const content = firstNonBlank(item.textContent, item.md_content, item.content, item.text, item.answer)
    if (content) {
      const part = normalizeAnswerPart({ type: 'text', content })
      parts.push(part)
      textParts.push(part.content)
    }
  }

  return { textContent: textParts.join('\n\n'), parts: parts.filter(Boolean) }
}

function normalizeAnswerPart(part) {
  if (!part || typeof part !== 'object') return null

  const type = String(part.type || part.partType || part.kind || '').toLowerCase()

  if (type === 'tool_use' || type === 'tool_call' || type === 'tool') {
    const detail = part.md_content && typeof part.md_content === 'object' ? part.md_content : part
    const toolResult = detail.tool_res ?? detail.mc_tool_res ?? detail.toolResult ?? detail.result ?? detail.content
    let content = ''
    if (toolResult && typeof toolResult === 'object') {
      content = firstNonBlank(toolResult.content, toolResult.text, safeJsonStringify(toolResult), '')
    } else {
      content = String(toolResult ?? '')
    }
    return {
      type: 'tool_use',
      toolName: detail.toolName || detail.tool_name || detail.name || '工具调用',
      useTarget: detail.useTarget || detail.use_target || detail.target || '',
      status: detail.status || detail.tool_status || 'unknown',
      content
    }
  }

  if (type === 'file' || part.fileDownloadUrl || part.file_download_url) {
    return {
      type: 'file',
      fileName: part.fileName || part.file_name || part.name || '',
      fileDownloadUrl: part.fileDownloadUrl || part.file_download_url || part.url || '',
      fileType: part.fileType || part.file_type || part.mimeType || ''
    }
  }

  const content = firstNonBlank(part.content, part.textContent, part.md_content, part.text, '')
  if (content) return { type: 'text', content: String(content) }
  return null
}

function safeJsonStringify(value) {
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function getQuestionText(result) {
  return firstNonBlank(
    result?.question,
    result?.input?.text,
    result?.prompt,
    result?.query,
    result?.text,
    '（无文本提问）'
  )
}

function getQuestionImages(result) {
  return uniqueResourceList([
    ...toArray(result?.questionImages),
    ...toArray(result?.question_images),
    ...toArray(result?.images),
    ...toArray(result?.input?.images)
  ])
}

function getQuestionFiles(result) {
  const files = [
    ...toArray(result?.questionFiles),
    ...toArray(result?.question_files),
    ...toArray(result?.files),
    ...toArray(result?.input?.files)
  ]

  const outputs = result?.outputs
  if (outputs && typeof outputs === 'object') {
    for (const output of Object.values(outputs)) {
      if (!output || typeof output !== 'object') continue
      files.push(...toArray(output.questionFiles), ...toArray(output.files))
    }
  }

  return uniqueResourceList(files)
}

function getModalities(result) {
  const modalities = new Set()
  addModalities(modalities, result?.modalities)

  if (getQuestionImages(result).length > 0) modalities.add('image')
  if (getQuestionFiles(result).length > 0) modalities.add('file')

  if (modalities.size === 0) modalities.add('text')
  if (!modalities.has('text')) modalities.add('text')
  return Array.from(modalities)
}

function addModalities(targetSet, value) {
  for (const item of toArray(value)) {
    const normalized = normalizeModality(item)
    if (normalized) targetSet.add(normalized)
  }
}

function normalizeModality(value) {
  if (value === null || value === undefined) return ''
  const text = String(value).trim().toLowerCase()
  if (!text) return ''

  if (text.includes('image') || text.includes('img') || text.includes('picture')) return 'image'
  if (text.includes('file') || text.includes('document') || text.includes('doc') || text.includes('pdf') || text.includes('xls') || text.includes('csv') || text.includes('zip')) return 'file'
  if (text.includes('text')) return 'text'
  return text
}

function getModalityColor(modality) {
  if (modality === 'image') return 'purple'
  if (modality === 'file') return 'processing'
  if (modality === 'text') return 'green'
  return 'default'
}

function getStepPhaseIndex(result, target) {
  const phase = result?.steps?.[target?.id]?.phase
  if (!phase) return -1
  return STEP_PHASES.findIndex(item => item.key === phase)
}

function getStepStatus(result, target, index) {
  const currentIndex = getStepPhaseIndex(result, target)
  if (currentIndex < 0) return 'pending'
  if (index < currentIndex) return 'done'
  if (index === currentIndex) return 'active'
  return 'pending'
}

function hasExpectedAnswer(result) {
  if (result?.hasExpected === true || result?.hasExpectedAnswer === true) return true
  if (result?.hasExpected === false || result?.hasExpectedAnswer === false) return false
  return hasMeaningfulText(result?.expectedAnswer)
}

function isPptExpected(result) {
  const type = String(result?.expectedType || result?.expected?.type || '').toLowerCase()
  return type === 'ppt' || type === 'pptx' || type.includes('powerpoint')
}

function isHtmlExpected(result) {
  const type = String(result?.expectedType || result?.expected?.type || '').toLowerCase()
  return type === 'html' || type.includes('html')
}

function isGeneratedHtmlFile(fileName) {
  const lower = String(fileName || '').toLowerCase()
  return lower.endsWith('.html') || lower.endsWith('.htm')
}

function shouldShowManualReview(result, target) {
  const score = getTargetScore(result, target)

  if (hasAnyScoreValue(score)) return false
  if (isManualReviewStatus(result) || isManualReviewStatus(score)) return true

  return !hasExpectedAnswer(result)
}

function getManualReviewLabel(result, target) {
  const score = getTargetScore(result, target)
  return firstNonBlank(
    score?.scoreDisplay,
    score?.display,
    result?.scoreDisplay,
    result?.display,
    '待人工评分'
  )
}

function getTargetScore(result, target) {
  if (!result || !target) return null
  return result.scores?.[target.id] || null
}

function isManualReviewStatus(value) {
  if (!value || typeof value !== 'object') return false

  const status = firstNonBlank(
    value.scoreStatus,
    value.score_status,
    value.status,
    value.judgeMode,
    value.judge_mode
  )

  if (!status) return value.manualReviewRequired === true || value.autoScoreAvailable === false

  const normalized = String(status).trim().toLowerCase()
  return normalized === 'manual_review' ||
    normalized === 'manual' ||
    normalized === 'pending_manual' ||
    normalized === 'no_reference' ||
    normalized === 'not_applicable'
}

function hasAnyScoreValue(score) {
  if (!score || typeof score !== 'object') return false
  return isFiniteNumberLike(score.final) ||
    hasMetricScore(score.auto) ||
    hasMetricScore(score.manual)
}

function hasMetricScore(value) {
  if (!value || typeof value !== 'object') return false
  return isFiniteNumberLike(value.accuracy) ||
    isFiniteNumberLike(value.completeness) ||
    isFiniteNumberLike(value.relevance) ||
    isFiniteNumberLike(value.final)
}

function isFiniteNumberLike(value) {
  if (value === null || value === undefined || value === '') return false
  return Number.isFinite(Number(value))
}

function getDisplayFileName(file, index) {
  if (file === null || file === undefined) return `文件${index + 1}`

  if (typeof file === 'object') {
    const directName = firstNonBlank(
      file.name,
      file.fileName,
      file.originalName,
      file.originalFilename,
      file.displayName,
      file.filename,
      file.saved_filename
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
  const rawName = lastSlashIndex >= 0
    ? withoutQuery.substring(lastSlashIndex + 1)
    : withoutQuery

  if (!rawName) return ''
  return normalizeDisplayFileName(rawName)
}

function normalizeDisplayFileName(name) {
  const decoded = safeDecodeURIComponent(String(name).trim())

  // 后端上传文件名常带日期/随机串，列表中保留可读的原始文件名。
  return decoded
    .replace(/^\d+[_-][a-zA-Z0-9_-]{6,64}_/, '')
    .replace(/^\d+[_-]/, '')
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
      resource.originalFilename,
      resource.filename,
      resource.saved_filename
    )

    if (identity) return String(identity)
    return safeJsonStringify(resource)
  }

  return String(resource).trim()
}

function toArray(value) {
  if (value === null || value === undefined) return []

  if (Array.isArray(value)) {
    return value.filter(item => item !== null && item !== undefined && (typeof item !== 'string' || item.trim()))
  }

  if (typeof value === 'string') {
    const text = value.trim()
    if (!text) return []

    if ((text.startsWith('[') && text.endsWith(']')) || (text.startsWith('{') && text.endsWith('}'))) {
      try {
        return toArray(JSON.parse(text))
      } catch {
        // Continue treating a non-JSON value as a delimited string.
      }
    }

    if (text.includes(',')) {
      return text.split(',').map(item => item.trim()).filter(Boolean)
    }
    if (text.includes('\n')) {
      return text.split(/\r?\n/).map(item => item.trim()).filter(Boolean)
    }
    return [text]
  }

  return [value]
}

function hasMeaningfulText(value) {
  if (value === null || value === undefined) return false
  const text = String(value).trim()
  if (!text) return false

  const normalized = text.toLowerCase()
  return normalized !== 'null' &&
    normalized !== 'undefined' &&
    normalized !== 'none' &&
    text !== '无' &&
    text !== '无参考答案'
}

function firstNonBlank(...values) {
  for (const value of values) {
    if (value !== null && value !== undefined && String(value).trim() !== '') {
      return value
    }
  }
  return ''
}

function safeDecodeURIComponent(value) {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

async function jumpToQuestion(index) {
  const numericIndex = Number(index)
  if (!Number.isFinite(numericIndex) || numericIndex < 0 || numericIndex >= props.results.length) return

  const targetPage = Math.ceil((numericIndex + 1) / pageSize.value)
  if (targetPage !== currentPage.value) {
    currentPage.value = targetPage
    await nextTick()
  }

  const element = document.getElementById(`question-row-${numericIndex + 1}`)
  if (element) element.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

defineExpose({ jumpToQuestion })
</script>

<style scoped>
.compare-columns {
  overflow-x: auto;
}

.compare-pagination {
  display: flex;
  justify-content: flex-end;
}

.compare-pagination-top {
  margin-bottom: 12px;
}

.compare-pagination-bottom {
  margin-top: 16px;
}

.compare-table-wrapper {
  min-width: 100%;
}

.compare-table {
  width: 100%;
  border-collapse: collapse;
  border: 1px solid #e8e8e8;
}

.compare-table th {
  background: #fafafa;
  padding: 12px 16px;
  text-align: left;
  font-weight: 600;
  border: 1px solid #e8e8e8;
  position: sticky;
  top: 0;
  z-index: 1;
}

.compare-table td {
  padding: 12px 16px;
  border: 1px solid #e8e8e8;
  vertical-align: top;
}

.question-col {
  min-width: 320px;
  max-width: 420px;
}

.target-col {
  min-width: 320px;
  max-width: 420px;
}

.question-number-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.question-number {
  color: #1054b9;
  font-weight: bold;
  font-size: 13px;
  margin-bottom: 4px;
}

.rerun-question-link {
  font-size: 12px;
  white-space: nowrap;
}

.rerun-question-disabled {
  color: #bfbfbf;
  cursor: not-allowed;
}

.question-text {
  font-size: 14px;
  line-height: 1.6;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}

.question-modalities {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.question-images {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 8px;
}

.question-files {
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed #e5e5e5;
}

.question-files-title {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #666;
  font-size: 12px;
  margin-bottom: 6px;
}

.question-file-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.question-file-tag {
  max-width: 240px;
  margin-inline-end: 0;
}

.question-file-tag a,
.question-file-tag span {
  display: inline-block;
  max-width: 210px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}

.answer-text {
  font-size: 13px;
  line-height: 1.6;
  color: #444;
  max-height: 300px;
  overflow-y: auto;
}

.answer-text :deep(p) {
  margin: 0 0 6px;
}

.answer-text :deep(p:last-child) {
  margin-bottom: 0;
}

.answer-text :deep(pre) {
  background: #f6f8fa;
  padding: 8px;
  border-radius: 4px;
  overflow-x: auto;
  font-size: 12px;
}

.answer-text :deep(code) {
  background: #f0f0f0;
  padding: 1px 3px;
  border-radius: 3px;
  font-size: 12px;
}

.answer-text :deep(pre code) {
  background: none;
  padding: 0;
}

.answer-text :deep(table) {
  border-collapse: collapse;
  width: 100%;
  margin: 6px 0;
}

.answer-text :deep(th),
.answer-text :deep(td) {
  border: 1px solid #ddd;
  padding: 4px 8px;
  text-align: left;
  font-size: 12px;
}

.answer-text :deep(th) {
  background: #f5f5f5;
}

.answer-text :deep(ul),
.answer-text :deep(ol) {
  padding-left: 18px;
  margin: 4px 0;
}

.answer-text :deep(blockquote) {
  border-left: 3px solid #ddd;
  padding-left: 10px;
  color: #666;
  margin: 6px 0;
}

.answer-text :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  margin: 4px 0;
}

.answer-text :deep(h1),
.answer-text :deep(h2),
.answer-text :deep(h3) {
  margin: 8px 0 4px;
  font-size: 14px;
}

.response-time {
  margin-top: 4px;
}

.step-tracker {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 12px;
}

.step-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
}

.step-item.step-done {
  color: #52c41a;
}

.step-item.step-active {
  color: #1054b9;
  font-weight: 600;
}

.step-item.step-pending {
  color: #bbb;
}

.step-icon {
  font-size: 12px;
}

.step-icon-pending {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ddd;
  display: inline-block;
}

.step-arrow {
  color: #ccc;
  font-size: 12px;
}

.manual-review-score {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.manual-review-desc {
  font-size: 12px;
  color: #888;
}

.alt-row {
  background: #fafafa;
}

.tool-content {
  font-size: 12px;
  color: #666;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 200px;
  overflow-y: auto;
  background: #f9f9f9;
  padding: 8px;
  border-radius: 4px;
}

.file-item {
  margin: 6px 0;
  padding: 6px 8px;
  background: #f6f6fa;
  border-radius: 4px;
  border: 1px solid #e8e8e8;
}
</style>
