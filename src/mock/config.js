// 配置中心 Mock 数据

export const mockEnvironments = [
  {
    id: 'env-1',
    name: 'Dev环境',
    url: 'demo-dev.example.invalid:6111',
    description: '开发测试环境，用于日常功能验证',
    agents: [
      { id: 'bot-dev-bot-1', name: 'Dev智能体1客服助手', creator: '演示用户1', type: 'bot' },
      { id: 'bot-dev-bot-2', name: 'Dev智能体2技术支持', creator: '演示用户2', type: 'bot' },
      { id: 'canvas-dev-canvas-1', name: 'Dev工作流1审批流程', creator: '演示用户1', type: 'canvas' }
    ],
    createdAt: '2026-03-01 10:00:00'
  },
  {
    id: 'env-2',
    name: 'Sit环境',
    url: 'demo-sit.example.invalid:7111',
    description: 'SIT集成测试环境',
    agents: [
      { id: 'bot-sit-bot-1', name: 'Sit智能体1客服机器人', creator: '演示用户1', type: 'bot' },
      { id: 'bot-sit-bot-2', name: 'Sit智能体2知识问答', creator: '演示用户2', type: 'bot' }
    ],
    createdAt: '2026-03-05 14:30:00'
  },
  {
    id: 'env-3',
    name: 'Prod环境',
    url: 'demo-prod.example.invalid:8111',
    description: '演示生产环境',
    agents: [
      { id: 'bot-prod-bot-1', name: 'Prod智能体1在线客服员', creator: '演示管理员', type: 'bot' },
      { id: 'canvas-prod-canvas-1', name: 'Prod工作流1发布流程', creator: '演示运维组', type: 'canvas' }
    ],
    createdAt: '2026-03-10 09:00:00'
  }
]

export const mockModels = [
  {
    id: 'model-1',
    name: 'GPT-4o',
    provider: 'OpenAI',
    apiKey: 'demo-key-openai-not-configured',
    baseUrl: 'https://api.openai.com/v1',
    params: { temperature: 0.7, contextLength: 8192, maxInputTokens: 4096, topP: 1.0, sampleCount: 1, extraParams: '' },
    createdAt: '2026-03-01 10:00:00'
  },
  {
    id: 'model-2',
    name: 'Claude-3.5-Sonnet',
    provider: 'Anthropic',
    apiKey: 'demo-key-anthropic-not-configured',
    baseUrl: 'https://api.anthropic.com/v1',
    params: { temperature: 0.5, contextLength: 16384, maxInputTokens: 8192, topP: 0.9, sampleCount: 1, extraParams: '' },
    createdAt: '2026-03-02 11:00:00'
  },
  {
    id: 'model-3',
    name: 'Gemini-Pro',
    provider: 'Google',
    apiKey: 'demo-key-google-not-configured',
    baseUrl: 'https://generativelanguage.googleapis.com/v1',
    params: { temperature: 0.8, contextLength: 8192, maxInputTokens: 4096, topP: 1.0, sampleCount: 1, extraParams: '' },
    createdAt: '2026-03-03 09:30:00'
  },
  {
    id: 'model-4',
    name: 'DeepSeek-V3',
    provider: 'DeepSeek',
    apiKey: 'demo-key-deepseek-not-configured',
    baseUrl: 'https://api.deepseek.com/v1',
    params: { temperature: 0.6, contextLength: 32768, maxInputTokens: 16384, topP: 0.95, sampleCount: 1, extraParams: '{"frequency_penalty": 0.3}' },
    createdAt: '2026-03-05 16:00:00'
  },
  {
    id: 'model-5',
    name: 'Qwen-Max',
    provider: 'Alibaba',
    apiKey: 'demo-key-qwen-not-configured',
    baseUrl: 'https://dashscope.aliyuncs.com/api/v1',
    params: { temperature: 0.7, contextLength: 8192, maxInputTokens: 4096, topP: 0.9, sampleCount: 1, extraParams: '' },
    createdAt: '2026-03-08 10:00:00'
  }
]

export const mockCriteria = {
  similarityThreshold: 0.8,
  dimensions: [
    { key: 'accuracy', label: '准确性', weight: 0.4 },
    { key: 'completeness', label: '完整性', weight: 0.3 },
    { key: 'relevance', label: '相关性', weight: 0.3 }
  ]
}
