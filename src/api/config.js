import { registerMock } from './request.js'
import { mockEnvironments, mockModels, mockCriteria } from '@/mock/config.js'

// 使用深拷贝模拟独立的数据存储
let environments = JSON.parse(JSON.stringify(mockEnvironments))
let models = JSON.parse(JSON.stringify(mockModels))
let criteria = JSON.parse(JSON.stringify(mockCriteria))

let envIdCounter = environments.length + 1
let modelIdCounter = models.length + 1

// ========== 环境管理 ==========

registerMock('GET', '/api/config/environments', () => {
  return { code: 0, data: environments }
})

registerMock('POST', '/api/config/environments', ({ data }) => {
  const newEnv = {
    ...data,
    id: `env-${envIdCounter++}`,
    agents: data.agents || [],
    createdAt: new Date().toLocaleString()
  }
  environments.push(newEnv)
  return { code: 0, data: newEnv }
})

registerMock('PUT', '/api/config/environments/:id', ({ data, pathParams }) => {
  const id = pathParams[0]
  const index = environments.findIndex(e => e.id === id)
  if (index !== -1) {
    environments[index] = { ...environments[index], ...data }
    return { code: 0, data: environments[index] }
  }
  return { code: 404, message: '环境不存在' }
})

registerMock('DELETE', '/api/config/environments/:id', ({ pathParams }) => {
  const id = pathParams[0]
  environments = environments.filter(e => e.id !== id)
  return { code: 0 }
})

// ========== 环境Agent列表(模拟从远程数据库加载) ==========

// 模拟不同环境的数据库agent数据
const mockEnvAgents = {
  'demo-dev.example.invalid:6111': { // Dev
    bots: Array.from({ length: 35 }, (_, i) => ({
      id: `dev-bot-${i + 1}`,
      name: `Dev智能体${i + 1}${['客服助手', '技术支持', '销售顾问', '数据分析', '文档助手'][i % 5]}`,
      creator: ['演示用户1', '演示用户2', '演示用户3', '演示用户4', '演示用户5'][i % 5]
    })),
    canvases: Array.from({ length: 20 }, (_, i) => ({
      id: `dev-canvas-${i + 1}`,
      name: `Dev工作流${i + 1}${['审批流程', '数据处理', '报告生成', '质量检查', '自动化测试'][i % 5]}`,
      creator: ['演示用户1', '演示用户2', '演示用户3', '演示用户4', '演示用户5'][i % 5]
    }))
  },
  'demo-sit.example.invalid:7111': { // Sit
    bots: Array.from({ length: 28 }, (_, i) => ({
      id: `sit-bot-${i + 1}`,
      name: `Sit智能体${i + 1}${['客服机器人', '知识问答', '任务调度', '风控检测', '内容审核'][i % 5]}`,
      creator: ['演示用户1', '演示用户2', '演示用户3', '演示用户4', '演示用户5'][i % 5]
    })),
    canvases: Array.from({ length: 15 }, (_, i) => ({
      id: `sit-canvas-${i + 1}`,
      name: `Sit工作流${i + 1}${['部署流程', '数据迁移', '监控告警', '日志分析', '性能测试'][i % 5]}`,
      creator: ['演示用户1', '演示用户2', '演示用户3', '演示用户4', '演示用户5'][i % 5]
    }))
  },
  'demo-prod.example.invalid:8111': { // Prod
    bots: Array.from({ length: 42 }, (_, i) => ({
      id: `prod-bot-${i + 1}`,
      name: `Prod智能体${i + 1}${['在线客服', '智能推荐', '语音助手', '图像识别', '文本生成'][i % 5]}`,
      creator: ['管理员', '运维组', '产品组', '算法组', '测试组'][i % 5]
    })),
    canvases: Array.from({ length: 25 }, (_, i) => ({
      id: `prod-canvas-${i + 1}`,
      name: `Prod工作流${i + 1}${['发布流程', '回滚流程', '巡检流程', '备份流程', '扩容流程'][i % 5]}`,
      creator: ['管理员', '运维组', '产品组', '算法组', '测试组'][i % 5]
    }))
  }
}

registerMock('GET', '/api/config/environments/agents', ({ params }) => {
  const envUrl = params?.envUrl
  const data = mockEnvAgents[envUrl]
  if (data) {
    return { code: 0, data }
  }
  return { code: 404, message: '未找到该环境的数据库配置' }
})

// ========== 环境用户列表(模拟从远程数据库加载) ==========

const mockEnvUsers = {
  'demo-dev.example.invalid:6111': Array.from({ length: 80 }, (_, i) => ({
    id: `dev-user-${i + 1}`,
    nickname: i === 0 ? '演示评测账号' : `Dev用户${i}`,
    email: i === 0 ? 'demo-dev@example.invalid' : `dev-user-${i}@example.invalid`
  })),
  'demo-sit.example.invalid:7111': Array.from({ length: 60 }, (_, i) => ({
    id: `sit-user-${i + 1}`,
    nickname: i === 0 ? '演示评测账号' : `Sit用户${i}`,
    email: i === 0 ? 'demo-sit@example.invalid' : `sit-user-${i}@example.invalid`
  })),
  'demo-prod.example.invalid:8111': Array.from({ length: 100 }, (_, i) => ({
    id: `prod-user-${i + 1}`,
    nickname: i === 0 ? '演示评测账号' : `Prod用户${i}`,
    email: i === 0 ? 'demo-prod@example.invalid' : `prod-user-${i}@example.invalid`
  }))
}

registerMock('GET', '/api/config/environments/users', ({ params }) => {
  const envUrl = params?.envUrl
  const offset = parseInt(params?.offset || '0')
  const limit = parseInt(params?.limit || '50')
  const keyword = params?.keyword || ''

  let users = mockEnvUsers[envUrl]
  if (!users) {
    return { code: 404, message: '未找到该环境的用户数据' }
  }

  if (keyword) {
    const kw = keyword.toLowerCase()
    users = users.filter(u =>
      u.nickname.toLowerCase().includes(kw) || u.email.toLowerCase().includes(kw)
    )
  }

  return {
    code: 0,
    data: {
      users: users.slice(offset, offset + limit),
      total: users.length
    }
  }
})

// 模拟按用户查询Agent（为每个用户生成几个Agent）
registerMock('GET', '/api/config/environments/users/:userId/agents', ({ params, pathParams }) => {
  const userId = pathParams[0]
  const envUrl = params?.envUrl
  const envPrefix = envUrl?.includes('6111') ? 'Dev' : envUrl?.includes('7111') ? 'Sit' : 'Prod'

  const bots = Array.from({ length: 2 + Math.floor(Math.random() * 4) }, (_, i) => ({
    id: `${userId}-bot-${i + 1}`,
    name: `${envPrefix}智能体${i + 1}-${['客服', '技术', '销售', '数据'][i % 4]}`,
    creator: userId,
    type: 'bot'
  }))

  const canvases = Array.from({ length: Math.floor(Math.random() * 3) }, (_, i) => ({
    id: `${userId}-canvas-${i + 1}`,
    name: `${envPrefix}工作流${i + 1}-${['审批', '处理', '生成'][i % 3]}`,
    creator: userId,
    type: 'canvas'
  }))

  return { code: 0, data: { bots, canvases } }
})

// 评测账号配置
registerMock('GET', '/api/config/environments/test-account', ({ params }) => {
  const envUrl = params?.envUrl
  const users = mockEnvUsers[envUrl]
  if (!users) {
    return { code: 404, message: '未找到该环境的评测账号' }
  }
  const testUser = users.find(u => u.nickname === '演示评测账号')
  return {
    code: 0,
    data: {
      id: testUser?.id,
      nickname: '演示评测账号',
      email: testUser?.email,
      password: 'demo-password'
    }
  }
})

// 登录接口
registerMock('POST', '/api/config/environments/login', ({ data }) => {
  return {
    code: 0,
    data: {
      authorization: 'mock-auth-token-' + Date.now(),
      apiUsername: data?.email || 'mock-user'
    }
  }
})

// ========== 模型管理 ==========

registerMock('GET', '/api/config/models', () => {
  return { code: 0, data: models }
})

registerMock('POST', '/api/config/models', ({ data }) => {
  const newModel = {
    ...data,
    id: `model-${modelIdCounter++}`,
    createdAt: new Date().toLocaleString()
  }
  models.push(newModel)
  return { code: 0, data: newModel }
})

registerMock('PUT', '/api/config/models/:id', ({ data, pathParams }) => {
  const id = pathParams[0]
  const index = models.findIndex(m => m.id === id)
  if (index !== -1) {
    models[index] = { ...models[index], ...data }
    return { code: 0, data: models[index] }
  }
  return { code: 404, message: '模型不存在' }
})

registerMock('DELETE', '/api/config/models/:id', ({ pathParams }) => {
  const id = pathParams[0]
  models = models.filter(m => m.id !== id)
  return { code: 0 }
})

// ========== 评判标准 ==========

registerMock('GET', '/api/config/criteria', () => {
  return { code: 0, data: criteria }
})

registerMock('PUT', '/api/config/criteria', ({ data }) => {
  criteria = { ...criteria, ...data }
  return { code: 0, data: criteria }
})

// 演示环境补充接口
registerMock('GET', '/api/config/environments/agents/paged', ({ params }) => {
  const envUrl = params?.envUrl || params?.url
  const source = mockEnvAgents[envUrl]
  if (!source) return { code: 404, message: '未找到该环境的 Agent 数据' }
  const type = params?.type === 'canvas' ? 'canvases' : 'bots'
  const keyword = String(params?.keyword || '').toLowerCase()
  const offset = Number(params?.offset || 0)
  const limit = Number(params?.limit || 20)
  const filtered = source[type].filter(agent => !keyword || `${agent.name} ${agent.creator}`.toLowerCase().includes(keyword))
  return { code: 0, data: { items: filtered.slice(offset, offset + limit), total: filtered.length } }
})
registerMock('POST', '/api/config/models/test', () => ({ code: 0, data: { success: true, message: '模型配置可用（演示结果）' } }))
registerMock('GET', '/api/config/environments/:envId/semiclaw/models', () => ({ code: 0, data: [
  { id: 'model-1', model: 'gpt-4o', label: 'GPT-4o' },
  { id: 'model-4', model: 'deepseek-chat', label: 'DeepSeek-V3' }
] }))
registerMock('POST', '/api/config/environments/semiclaw/agents', ({ data }) => {
  const offset = Number(data?.offset || 0)
  const limit = Number(data?.limit || 200)
  const keyword = String(data?.keyword || '').toLowerCase()
  const items = Array.from({ length: 24 }, (_, index) => ({
    id: `demo-agent-${index + 1}`,
    name: `演示数字员工${index + 1}`,
    type: 'agent',
    description: '用于本地界面演示的数字员工',
    status: 'active'
  })).filter(agent => !keyword || agent.name.toLowerCase().includes(keyword))
  return { code: 0, data: { items: items.slice(offset, offset + limit), total: items.length } }
})
registerMock('POST', '/api/config/environments/:envId/semiclaw/agents/:agentId/model', ({ data, pathParams }) => ({ code: 0, data: { envId: pathParams[0], agentId: pathParams[1], ...data } }))
registerMock('POST', '/api/config/environments/:envId/semiclaw/skills/delete', ({ data }) => ({ code: 0, data: { deleted: data?.paths || [], failed: {} } }))
registerMock('POST', '/api/config/environments/:envId/semiclaw/agents/import', ({ pathParams }) => ({ code: 0, data: { id: `demo-agent-import-${Date.now()}`, envId: pathParams[0], name: '导入的演示数字员工' } }))
