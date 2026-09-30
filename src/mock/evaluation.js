// 评测记录 Mock 数据
import { mockDatasets } from './dataset.js'

// 生成模拟的评测结果
function generateMockResults(targets, datasetId) {
  const dataset = mockDatasets.find(d => d.id === datasetId)
  if (!dataset) return []

  return dataset.items.map(item => {
    const outputs = {}
    const scores = {}

    targets.forEach(target => {
      const tid = target.id
      // 模拟各target的输出
      outputs[tid] = {
        answer: generateVariantAnswer(item.expectedAnswer || item.question),
        responseTime: Math.floor(800 + Math.random() * 2000)
      }

      // 模拟评分
      const accuracy = +(0.6 + Math.random() * 0.4).toFixed(2)
      const completeness = +(0.5 + Math.random() * 0.5).toFixed(2)
      const relevance = +(0.65 + Math.random() * 0.35).toFixed(2)
      const finalScore = +(accuracy * 0.4 + completeness * 0.3 + relevance * 0.3).toFixed(2)

      scores[tid] = {
        auto: { accuracy, completeness, relevance },
        manual: null,
        final: finalScore
      }
    })

    return {
      id: `r-${item.id}`,
      questionId: item.id,
      question: item.question,
      questionImages: item.images || [],
      expectedAnswer: item.expectedAnswer,
      outputs,
      scores,
      hasExpected: !!item.expectedAnswer,
      judgeMode: item.expectedAnswer ? 'auto' : 'manual'
    }
  })
}

function generateVariantAnswer(base) {
  if (!base) {
    const fallbacks = [
      '根据您的问题，我来为您解答。这是一个关于系统功能使用的常见问题，建议您参考帮助文档了解详细操作步骤。',
      '您好，感谢您的提问。关于这个问题，您可以通过以下方式解决：首先确认您的操作环境，然后按照系统提示逐步操作即可。',
      '这个问题涉及到系统的核心功能模块，建议您先检查相关配置是否正确，如果问题持续存在，请联系技术支持。'
    ]
    return fallbacks[Math.floor(Math.random() * fallbacks.length)]
  }
  // 对预期答案做一些变体
  const variants = [
    base,
    base.replace(/。/g, '，').slice(0, -1) + '。',
    '根据系统规则，' + base,
    base.split('，').reverse().join('，'),
    base.replace(/点击/g, '选择').replace(/进入/g, '打开')
  ]
  return variants[Math.floor(Math.random() * variants.length)]
}

export const mockEvaluations = [
  {
    id: 'eval-001',
    name: '20260325-143000 客服Agent回归测试',
    type: 'agent',
    status: 'completed',
    startTime: '2026-03-25 14:30:00',
    endTime: '2026-03-25 14:35:22',
    targets: [
      { id: 't1', envId: 'env-1', envName: '测试环境', agentId: 'agent-1', agentName: '客服Agent', label: '测试环境 - 客服Agent' },
      { id: 't2', envId: 'env-2', envName: '预发环境', agentId: 'agent-1', agentName: '客服Agent', label: '预发环境 - 客服Agent' }
    ],
    datasetIds: ['ds-001'],
    datasetNames: ['客服常见问题集V1'],
    accuracy: 0.85,
    remark: '每周回归测试',
    get results() {
      return generateMockResults(this.targets, 'ds-001')
    }
  },
  {
    id: 'eval-002',
    name: '20260324-100000 多模型对比评测',
    type: 'model',
    status: 'completed',
    startTime: '2026-03-24 10:00:00',
    endTime: '2026-03-24 10:12:45',
    targets: [
      { id: 't3', modelId: 'model-1', modelName: 'GPT-4o', label: 'GPT-4o' },
      { id: 't4', modelId: 'model-2', modelName: 'Claude-3.5-Sonnet', label: 'Claude-3.5-Sonnet' },
      { id: 't5', modelId: 'model-4', modelName: 'DeepSeek-V3', label: 'DeepSeek-V3' }
    ],
    datasetIds: ['ds-001'],
    datasetNames: ['客服常见问题集V1'],
    accuracy: 0.78,
    remark: '对比主流大模型表现',
    get results() {
      return generateMockResults(this.targets, 'ds-001')
    }
  },
  {
    id: 'eval-003',
    name: '20260323-160000 通用对话人工评测',
    type: 'model',
    status: 'completed',
    startTime: '2026-03-23 16:00:00',
    endTime: '2026-03-23 16:08:33',
    targets: [
      { id: 't6', modelId: 'model-1', modelName: 'GPT-4o', label: 'GPT-4o' },
      { id: 't7', modelId: 'model-5', modelName: 'Qwen-Max', label: 'Qwen-Max' }
    ],
    datasetIds: ['ds-004'],
    datasetNames: ['通用对话能力测试'],
    accuracy: null,
    remark: '无预期结果，需人工评判',
    get results() {
      return generateMockResults(this.targets, 'ds-004')
    }
  },
  {
    id: 'eval-004',
    name: '20260322-090000 文档理解能力测试',
    type: 'agent',
    status: 'running',
    startTime: '2026-03-22 09:00:00',
    endTime: null,
    targets: [
      { id: 't8', envId: 'env-1', envName: '测试环境', agentId: 'agent-3', agentName: '技术支持Agent', label: '测试环境 - 技术支持Agent' }
    ],
    datasetIds: ['ds-003'],
    datasetNames: ['文档理解评测集'],
    accuracy: null,
    remark: '评测中...',
    results: []
  },
  {
    id: 'eval-005',
    name: '20260321-140000 产品识别Agent评测',
    type: 'agent',
    status: 'failed',
    startTime: '2026-03-21 14:00:00',
    endTime: '2026-03-21 14:02:10',
    targets: [
      { id: 't9', envId: 'env-3', envName: '生产环境', agentId: 'agent-4', agentName: '数据分析Agent', label: '生产环境 - 数据分析Agent' }
    ],
    datasetIds: ['ds-002'],
    datasetNames: ['产品识别测试集'],
    accuracy: null,
    remark: 'Agent连接超时，评测失败',
    results: []
  }
]
