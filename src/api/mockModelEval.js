import { registerMock } from './request.js'

const clone = value => JSON.parse(JSON.stringify(value))
const now = () => new Date().toLocaleString('zh-CN', { hour12: false })

let groups = [
  { id: 'group-1', name: '客服模型基准', remark: '客服问答能力与响应质量对比', createdAt: '2026-03-18 09:30:00' },
  { id: 'group-2', name: '退款流程回归', remark: '退款及售后场景回归验证', createdAt: '2026-03-21 14:10:00' }
]
let types = [
  { id: 'subtype-1', name: '客服问答' },
  { id: 'subtype-2', name: '售后处理' },
  { id: 'subtype-3', name: '长文本理解' }
]
let subEvaluations = [
  { id: 'subeval-1', groupId: 'group-1', subType: 'subtype-1', targetType: 'model', targets: [{ id: 'model-1', modelId: 'model-1', modelName: 'GPT-4o', label: 'GPT-4o' }, { id: 'model-4', modelId: 'model-4', modelName: 'DeepSeek-V3', label: 'DeepSeek-V3' }], datasetIds: ['ds-001'], datasetNames: ['客服常见问题集V1'], datasetMapping: {}, remark: '常见问题基准', judgeMode: 'formula', judgeModelId: null, status: 'completed' },
  { id: 'subeval-2', groupId: 'group-1', subType: 'subtype-3', targetType: 'model', targets: [{ id: 'model-2', modelId: 'model-2', modelName: 'Claude-3.5-Sonnet', label: 'Claude-3.5-Sonnet' }, { id: 'model-5', modelId: 'model-5', modelName: 'Qwen-Max', label: 'Qwen-Max' }], datasetIds: ['ds-001'], datasetNames: ['客服常见问题集V1'], datasetMapping: {}, remark: '长文本回答', judgeMode: 'llm', judgeModelId: 'model-1', status: 'completed' },
  { id: 'subeval-3', groupId: 'group-2', subType: 'subtype-2', targetType: 'model', targets: [{ id: 'model-1', modelId: 'model-1', modelName: 'GPT-4o', label: 'GPT-4o' }, { id: 'model-5', modelId: 'model-5', modelName: 'Qwen-Max', label: 'Qwen-Max' }], datasetIds: ['ds-001'], datasetNames: ['客服常见问题集V1'], datasetMapping: {}, remark: '退款流程回归用例', judgeMode: 'formula', judgeModelId: null, status: 'completed' }
]
let subEvalRecords = {
  'subeval-1': [{ id: 'run-1', evalId: 'eval-001', version: 'v1', status: 'completed', accuracy: 0.86, startTime: '2026-03-25 14:30:00', endTime: '2026-03-25 14:36:18' }],
  'subeval-2': [{ id: 'run-2', evalId: 'eval-002', version: 'v1', status: 'completed', accuracy: 0.91, startTime: '2026-03-25 15:00:00', endTime: '2026-03-25 15:08:02' }],
  'subeval-3': [{ id: 'run-3', evalId: 'eval-003', version: 'v1', status: 'completed', accuracy: 0.82, startTime: '2026-03-26 10:00:00', endTime: '2026-03-26 10:05:41' }]
}
let reports = [
  { id: 'report-1', groupId: 'group-1', status: 'completed', createdAt: '2026-03-26 16:20:00', selections: ['subeval-1', 'subeval-2'], overallSummary: 'GPT-4o 在常见客服问题上响应稳定；Claude-3.5-Sonnet 的长文本回答更完整。建议结合线上延迟和成本选择默认模型。', subEvalSummaries: [{ subEvalId: 'subeval-1', subEvalName: '客服问答', subEvalVersion: 'v1', accuracy: 0.86, durationMs: 378000, summary: '两个模型都能覆盖核心操作步骤。GPT-4o 的答案更简洁，DeepSeek-V3 在少数问题中遗漏了时效说明。' }, { subEvalId: 'subeval-2', subEvalName: '长文本理解', subEvalVersion: 'v1', accuracy: 0.91, durationMs: 481000, summary: 'Claude-3.5-Sonnet 的结构完整度较高，Qwen-Max 的关键信息提取表现稳定。' }] },
  { id: 'report-2', groupId: 'group-2', status: 'completed', createdAt: '2026-03-27 11:45:00', selections: ['subeval-3'], overallSummary: '本轮退款流程回归通过率为 82%，复杂退款条件的说明仍有优化空间。', subEvalSummaries: [{ subEvalId: 'subeval-3', subEvalName: '售后处理', subEvalVersion: 'v1', accuracy: 0.82, durationMs: 341000, summary: '模型能够完成大多数标准退款咨询，对例外条款和处理时限的回答需要进一步校准。' }] }
]
let comparisons = []
let nextGroupId = 3
let nextSubEvalId = 4
let nextTypeId = 4
let nextRunId = 4
let nextReportId = 3
let nextComparisonId = 1

const ok = data => ({ code: 0, data })
const findById = (list, id) => list.find(item => String(item.id) === String(id))

// Model groups
registerMock('GET', '/api/model-groups', () => ok(clone(groups)))
registerMock('GET', '/api/model-groups/:id', ({ pathParams }) => {
  const group = findById(groups, pathParams[0])
  return group ? ok(clone(group)) : { code: 404, message: '模型分组不存在' }
})
registerMock('POST', '/api/model-groups', ({ data }) => {
  const group = { ...clone(data), id: `group-${nextGroupId++}`, createdAt: now() }
  groups.unshift(group)
  return ok(clone(group))
})
registerMock('PUT', '/api/model-groups/:id', ({ data, pathParams }) => {
  const group = findById(groups, pathParams[0])
  if (!group) return { code: 404, message: '模型分组不存在' }
  Object.assign(group, clone(data))
  return ok(clone(group))
})
registerMock('DELETE', '/api/model-groups/:id', ({ pathParams }) => {
  const id = pathParams[0]
  groups = groups.filter(item => String(item.id) !== id)
  subEvaluations = subEvaluations.filter(item => String(item.groupId) !== id)
  return ok(null)
})
registerMock('POST', '/api/model-groups/:id/duplicate', ({ pathParams }) => {
  const source = findById(groups, pathParams[0])
  if (!source) return { code: 404, message: '模型分组不存在' }
  const newGroup = { ...clone(source), id: `group-${nextGroupId++}`, name: `${source.name}（副本）`, createdAt: now() }
  groups.unshift(newGroup)
  for (const subEval of subEvaluations.filter(item => String(item.groupId) === String(source.id))) {
    subEvaluations.unshift({ ...clone(subEval), id: `subeval-${nextSubEvalId++}`, groupId: newGroup.id })
  }
  return ok(clone(newGroup))
})
registerMock('GET', '/api/model-groups/:groupId/sub-evaluations', ({ pathParams }) =>
  ok(clone(subEvaluations.filter(item => String(item.groupId) === pathParams[0])))
)

// Sub-evaluations and version history
registerMock('GET', '/api/sub-evaluations/:id', ({ pathParams }) => {
  const item = findById(subEvaluations, pathParams[0])
  return item ? ok(clone(item)) : { code: 404, message: '子评测不存在' }
})
registerMock('POST', '/api/sub-evaluations', ({ data }) => {
  const item = { ...clone(data), id: `subeval-${nextSubEvalId++}`, status: 'pending', createdAt: now() }
  subEvaluations.unshift(item)
  subEvalRecords[item.id] = []
  return ok(clone(item))
})
registerMock('PUT', '/api/sub-evaluations/:id', ({ data, pathParams }) => {
  const item = findById(subEvaluations, pathParams[0])
  if (!item) return { code: 404, message: '子评测不存在' }
  Object.assign(item, clone(data))
  return ok(clone(item))
})
registerMock('DELETE', '/api/sub-evaluations/:id', ({ pathParams }) => {
  const id = pathParams[0]
  subEvaluations = subEvaluations.filter(item => String(item.id) !== id)
  delete subEvalRecords[id]
  return ok(null)
})
registerMock('POST', '/api/sub-evaluations/:id/duplicate', ({ data, pathParams }) => {
  const source = findById(subEvaluations, pathParams[0])
  if (!source) return { code: 404, message: '子评测不存在' }
  const item = { ...clone(source), id: `subeval-${nextSubEvalId++}`, groupId: data?.targetGroupId || source.groupId, status: 'pending' }
  subEvaluations.unshift(item)
  subEvalRecords[item.id] = []
  return ok(clone(item))
})
registerMock('GET', '/api/sub-evaluations/:id/records', ({ pathParams }) => ok(clone(subEvalRecords[pathParams[0]] || [])))
registerMock('DELETE', '/api/sub-evaluations/:id/records/:evalId', ({ pathParams }) => {
  const [subId, evalId] = pathParams
  subEvalRecords[subId] = (subEvalRecords[subId] || []).filter(item => String(item.id) !== evalId && String(item.evalId) !== evalId)
  return ok(null)
})
function runOne(id) {
  const item = findById(subEvaluations, id)
  if (!item) return null
  const history = subEvalRecords[id] || (subEvalRecords[id] = [])
  const accuracy = +(0.72 + Math.random() * 0.25).toFixed(2)
  const timestamp = now()
  const run = { id: `run-${nextRunId++}`, evalId: `eval-mock-${Date.now()}-${nextRunId}`, version: `v${history.length + 1}`, status: 'completed', accuracy, startTime: timestamp, endTime: now() }
  history.unshift(run)
  item.status = 'completed'
  item.accuracy = accuracy
  item.lastRunAt = timestamp
  return run
}
registerMock('POST', '/api/sub-evaluations/:id/run', ({ pathParams }) => {
  const run = runOne(pathParams[0])
  return run ? ok(clone(run)) : { code: 404, message: '子评测不存在' }
})
registerMock('POST', '/api/sub-evaluations/batch-run', ({ data }) => {
  const results = (data?.ids || []).map(id => runOne(id)).filter(Boolean)
  return ok(clone(results))
})

// Shared sub-evaluation types
registerMock('GET', '/api/sub-eval-types', () => ok(clone(types)))
registerMock('POST', '/api/sub-eval-types', ({ data }) => {
  const item = { id: `subtype-${nextTypeId++}`, name: data?.name || '新类型' }
  types.push(item)
  return ok(clone(item))
})
registerMock('PUT', '/api/sub-eval-types/:id', ({ data, pathParams }) => {
  const item = findById(types, pathParams[0])
  if (!item) return { code: 404, message: '子评测类型不存在' }
  item.name = data?.name || item.name
  return ok(clone(item))
})
registerMock('DELETE', '/api/sub-eval-types/:id', ({ pathParams }) => {
  types = types.filter(item => String(item.id) !== pathParams[0])
  return ok(null)
})

// Model reports and report comparisons
registerMock('GET', '/api/model-reports', ({ params }) => {
  const list = params?.groupId ? reports.filter(item => String(item.groupId) === String(params.groupId)) : reports
  return ok(clone(list))
})
registerMock('GET', '/api/model-reports/:id', ({ pathParams }) => {
  const item = findById(reports, pathParams[0])
  return item ? ok(clone(item)) : { code: 404, message: '模型测评报告不存在' }
})
registerMock('POST', '/api/model-reports', ({ data }) => {
  const group = findById(groups, data?.groupId)
  const selections = clone(data?.selections || data?.subEvalIds || [])
  const report = {
    id: `report-${nextReportId++}`, groupId: data?.groupId, status: 'completed', createdAt: now(), selections,
    overallSummary: `${group?.name || '模型分组'}本轮测评已完成。建议结合各子评测得分、响应耗时和业务目标综合判断模型表现。`,
    subEvalSummaries: selections.map(selection => {
      const id = typeof selection === 'string' ? selection : selection.subEvalId || selection.id
      const subEval = findById(subEvaluations, id)
      return { subEvalId: id, subEvalName: subEval ? findById(types, subEval.subType)?.name || subEval.subType : '子评测', subEvalVersion: '最新版本', accuracy: subEval?.accuracy ?? 0.84, durationMs: 120000, summary: '模型能够完成主要测试项，建议继续观察复杂问题上的稳定性。' }
    })
  }
  reports.unshift(report)
  return ok(clone(report))
})
registerMock('DELETE', '/api/model-reports/:id', ({ pathParams }) => {
  reports = reports.filter(item => String(item.id) !== pathParams[0])
  return ok(null)
})
registerMock('GET', '/api/report-comparisons', () => ok(clone(comparisons)))
registerMock('GET', '/api/report-comparisons/:id', ({ pathParams }) => {
  const item = findById(comparisons, pathParams[0])
  return item ? ok(clone(item)) : { code: 404, message: '报告对比不存在' }
})
registerMock('POST', '/api/report-comparisons', ({ data }) => {
  const selected = (data?.reportIds || []).map(id => findById(reports, id)).filter(Boolean)
  const participants = selected.map(report => ({ reportId: report.id, groupId: report.groupId, groupName: findById(groups, report.groupId)?.name || '模型分组', accuracy: report.subEvalSummaries?.[0]?.accuracy ?? 0.84, durationMs: 120000 }))
  const item = {
    id: `comparison-${nextComparisonId++}`, status: 'completed', createdAt: now(), reportIds: selected.map(report => report.id), comparisonMode: data?.comparisonMode || 'performance',
    overallConclusion: selected.length ? `已对 ${selected.length} 份报告进行比较。${participants.map((person, index) => `${index + 1}. ${person.groupName}`).join('\n')}\n可根据业务优先级选择综合表现更合适的模型。` : '暂无可比较报告。',
    subtypeComparisons: [{ subType: 'summary', subTypeName: '综合表现', comparison: '对比各组报告，整体分数较高的模型在标准问题中表现稳定。对于回归场景，请重点查看准确率变化和响应耗时。', participants }]
  }
  comparisons.unshift(item)
  return ok(clone(item))
})
registerMock('DELETE', '/api/report-comparisons/:id', ({ pathParams }) => {
  comparisons = comparisons.filter(item => String(item.id) !== pathParams[0])
  return ok(null)
})
