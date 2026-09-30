import { registerMock } from './request.js'
import { mockEvaluations } from '@/mock/evaluation.js'

let evaluations = JSON.parse(JSON.stringify(mockEvaluations))
let evalIdCounter = evaluations.length + 1

registerMock('GET', '/api/evaluations', () => {
  // 返回列表不包含详细 results
  const list = evaluations.map(({ results, ...rest }) => rest)
  return { code: 0, data: list }
})

registerMock('GET', '/api/evaluations/:id', ({ pathParams }) => {
  const id = pathParams[0]
  // 重新从 mockEvaluations 获取含 getter 的原始数据
  const mockOriginal = mockEvaluations.find(e => e.id === id)
  const stored = evaluations.find(e => e.id === id)
  if (stored) {
    // 如果有人工复测的结果用 stored，否则用 mock 生成的
    const data = {
      ...stored,
      results: stored._manualResults || (mockOriginal ? mockOriginal.results : [])
    }
    return { code: 0, data }
  }
  return { code: 404, message: '评测记录不存在' }
})

registerMock('POST', '/api/evaluations', ({ data }) => {
  const now = new Date()
  const newEval = {
    ...data,
    id: `eval-${String(evalIdCounter++).padStart(3, '0')}`,
    status: 'running',
    startTime: now.toLocaleString(),
    endTime: null,
    accuracy: null,
    results: []
  }
  evaluations.unshift(newEval)

  // 模拟3秒后完成评测
  setTimeout(() => {
    const index = evaluations.findIndex(e => e.id === newEval.id)
    if (index !== -1) {
      evaluations[index].status = 'completed'
      evaluations[index].endTime = new Date().toLocaleString()
      evaluations[index].accuracy = +(0.7 + Math.random() * 0.25).toFixed(2)
    }
  }, 3000)

  return { code: 0, data: newEval }
})

registerMock('PUT', '/api/evaluations/:id', ({ data, pathParams }) => {
  const id = pathParams[0]
  const index = evaluations.findIndex(e => e.id === id)
  if (index !== -1) {
    evaluations[index] = { ...evaluations[index], ...data }
    return { code: 0, data: evaluations[index] }
  }
  return { code: 404, message: '评测记录不存在' }
})

registerMock('DELETE', '/api/evaluations/:id', ({ pathParams }) => {
  const id = pathParams[0]
  evaluations = evaluations.filter(e => e.id !== id)
  return { code: 0 }
})

// 人工复测打分
registerMock('PUT', '/api/evaluations/:evalId/results/:resultId/score', ({ data, pathParams }) => {
  const [evalId, resultId] = pathParams
  const evalRecord = evaluations.find(e => e.id === evalId)
  if (!evalRecord) return { code: 404, message: '评测记录不存在' }

  // 以原结果为底保存人工评分，详情接口继续返回自动评分和人工评分。
  if (!evalRecord._manualResults) {
    evalRecord._manualResults = JSON.parse(JSON.stringify(evalRecord.results || []))
  }
  const result = evalRecord._manualResults.find(item => String(item.id) === String(resultId))
  if (result && data?.targetId) {
    if (!result.scores) result.scores = {}
    result.scores[data.targetId] = {
      ...(result.scores[data.targetId] || {}),
      manual: data.scores,
      final: data.final,
      remark: data.remark
    }
  }

  return { code: 0, data: { evalId, resultId, ...data } }
})

registerMock('POST', '/api/evaluations/:id/stop', ({ pathParams }) => {
  const record = evaluations.find(item => String(item.id) === pathParams[0])
  if (!record) return { code: 404, message: '评测记录不存在' }
  record.status = 'stopped'
  record.endTime = new Date().toLocaleString()
  return { code: 0, data: record }
})
registerMock('POST', '/api/evaluations/:id/restart', ({ pathParams }) => {
  const record = evaluations.find(item => String(item.id) === pathParams[0])
  if (!record) return { code: 404, message: '评测记录不存在' }
  record.status = 'running'
  record.startTime = new Date().toLocaleString()
  record.endTime = null
  return { code: 0, data: record }
})
registerMock('POST', '/api/evaluations/:id/resume', ({ pathParams }) => {
  const record = evaluations.find(item => String(item.id) === pathParams[0])
  if (!record) return { code: 404, message: '评测记录不存在' }
  record.status = 'running'
  return { code: 0, data: record }
})
registerMock('POST', '/api/evaluations/:id/rerun-question', ({ pathParams, data }) => {
  const record = evaluations.find(item => String(item.id) === pathParams[0])
  if (!record) return { code: 404, message: '评测记录不存在' }
  return { code: 0, data: { ...record, rerunQuestionIndex: data?.questionIndex } }
})
registerMock('POST', '/api/evaluations/:id/rerun-below-score', ({ pathParams, data }) => {
  const record = evaluations.find(item => String(item.id) === pathParams[0])
  if (!record) return { code: 404, message: '评测记录不存在' }
  return { code: 0, data: { ...record, threshold: data?.threshold } }
})

