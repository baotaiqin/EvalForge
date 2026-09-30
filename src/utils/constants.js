// 状态枚举
export const EVAL_STATUS = {
  PENDING: 'pending',
  RUNNING: 'running',
  COMPLETED: 'completed',
  FAILED: 'failed',
  STOPPED: 'stopped'
}

export const EVAL_STATUS_MAP = {
  pending: { text: '待执行', color: 'gold' },
  running: { text: '执行中', color: 'blue' },
  completed: { text: '已完成', color: 'green' },
  failed: { text: '失败', color: 'red' },
  stopped: { text: '已终止', color: 'orange' }
}

// a-badge 组件专用的 status 关键字映射（与 EVAL_STATUS_MAP.color 是给 a-tag 用的调色不同）
export const EVAL_STATUS_BADGE_MAP = {
  pending: 'default',
  running: 'processing',
  completed: 'success',
  failed: 'error',
  stopped: 'warning'
}

// 被评测数据类型
export const EVAL_TARGET_TYPE = {
  AGENT: 'agent',
  MODEL: 'model'
}

export const EVAL_TARGET_TYPE_MAP = {
  agent: { text: 'Agent' },
  model: { text: '模型' }
}

// 数据集类型
export const EVAL_DATASET_TYPE = {
  TEXT_QA: 'text_qa',
  IMAGE_TEXT: 'image_text',
  IMAGE_TEXT_EXPECTED: 'image_text_expected'
}

export const EVAL_DATASET_TYPE_MAP = {
  text_qa: { text: '纯文本问答' },
  image_text: { text: '图片-文本' },
  image_text_expected: { text: '图片-文本-预期结果' }
}

// 评分维度
export const SCORE_DIMENSIONS = [
  { key: 'accuracy', label: '准确性', weight: 0.4 },
  { key: 'completeness', label: '完整性', weight: 0.3 },
  { key: 'relevance', label: '相关性', weight: 0.3 }
]

// PPT 对比评测的评分维度（对应后端 PptScoringService 的 content/structure/visual）
export const PPT_SCORE_DIMENSIONS = [
  { key: 'content', label: '内容', weight: 0.5 },
  { key: 'structure', label: '结构', weight: 0.3 },
  { key: 'visual', label: '视觉', weight: 0.2 }
]

// HTML 报告对比评测的评分维度（对应后端 HtmlScoringService 的 content/structure/visual）
// key 与 PPT_SCORE_DIMENSIONS 相同，label 保持一致以避免 ScoreEditor 按 key 合并维度实时产生歧义
export const HTML_SCORE_DIMENSIONS = [
  { key: 'content', label: '内容', weight: 0.4 },
  { key: 'structure', label: '结构', weight: 0.4 },
  { key: 'visual', label: '视觉', weight: 0.2 }
]

// 模型测评报告状态（对应后端 ModelReportStoreService 的 STATUS 常量）
export const MODEL_REPORT_STATUS_MAP = {
  generating: { text: '生成中', badge: 'processing' },
  completed: { text: '已完成', badge: 'success' },
  failed: { text: '失败', badge: 'error' }
}

// 模型提供商
export const MODEL_PROVIDERS = [
  'OpenAI',
  'Anthropic',
  'Google',
  'Baidu',
  'Alibaba',
  'Zhipu',
  'Moonshot',
  'Deepseek',
  '其他'
]

// 分值告警颜色（入参 val 为 0-1 分值），供各评分分类展示统一复用
export function getScoreTagColor(val) {
  if (val == null) return 'default'
  if (val >= 0.8) return 'green'
  if (val >= 0.6) return 'orange'
  return 'red'
}

export function getScoreHexColor(val) {
  if (val == null) return '#999'
  if (val >= 0.8) return '#52c41a'
  if (val >= 0.6) return '#faad14'
  return '#ff4d4f'
}

// 耗时格式化：入参为毫秒，统一换算为分钟展示（保留1位小数）
// 供应商耗时(CompareColumn)统一换算为分钟展示（ReportDetail/子评测报告耗时(ModelReportDetail/ReportCenterModal)统一复用）
// 不再使用秒(ms)形式展示
export function formatDurationMinutes(ms) {
  const num = Number(ms)
  if (ms == null || !Number.isFinite(num) || num < 0) return '--'
  return (num / 60000).toFixed(1) + ' 分钟'
}

// 按 startTime/endTime（后端 "yyyy-MM-dd HH:mm:ss" 格式字符串）计算耗时并格式化为分钟展示；
// 缺少任一时间点或结果早于开始时间（数据异常）时返回 '--'
export function formatDurationRange(startTime, endTime) {
  if (!startTime || !endTime) return '--'
  const start = new Date(startTime.replace(/-/g, '/'))
  const end = new Date(endTime.replace(/-/g, '/'))
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) return '--'
  const diff = end.getTime() - start.getTime()
  if (diff < 0) return '--'
  return formatDurationMinutes(diff)
}
