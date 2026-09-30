import { api } from './request.js'

// ====== 模型分组 ======

export function fetchModelGroups() {
  return api.get('/api/model-groups')
}

export function fetchModelGroup(id) {
  return api.get(`/api/model-groups/${id}`)
}

export function createModelGroup(data) {
  return api.post('/api/model-groups', data)
}

export function updateModelGroup(id, data) {
  return api.put(`/api/model-groups/${id}`, data)
}

export function deleteModelGroup(id) {
  return api.delete(`/api/model-groups/${id}`)
}

export function duplicateModelGroup(id) {
  return api.post(`/api/model-groups/${id}/duplicate`)
}

export function fetchSubEvaluationsByGroup(groupId) {
  return api.get(`/api/model-groups/${groupId}/sub-evaluations`)
}

// ====== 子评测 ======

export function fetchSubEvaluation(id) {
  return api.get(`/api/sub-evaluations/${id}`)
}

export function createSubEvaluation(data) {
  return api.post('/api/sub-evaluations', data)
}

export function updateSubEvaluation(id, data) {
  return api.put(`/api/sub-evaluations/${id}`, data)
}

export function deleteSubEvaluation(id) {
  return api.delete(`/api/sub-evaluations/${id}`)
}

export function duplicateSubEvaluation(id, targetGroupId) {
  return api.post(`/api/sub-evaluations/${id}/duplicate`, { targetGroupId })
}

export function fetchSubEvaluationRecords(id) {
  return api.get(`/api/sub-evaluations/${id}/records`)
}

export function deleteSubEvaluationRecord(id, evalId) {
  return api.delete(`/api/sub-evaluations/${id}/records/${evalId}`)
}

export function runSubEvaluation(id) {
  return api.post(`/api/sub-evaluations/${id}/run`)
}

export function batchRunSubEvaluations(ids) {
  return api.post('/api/sub-evaluations/batch-run', { ids })
}

// ====== 子评测类型 ======

export function fetchSubEvalTypes() {
  return api.get('/api/sub-eval-types')
}

export function createSubEvalType(name) {
  return api.post('/api/sub-eval-types', { name })
}

export function updateSubEvalType(id, name) {
  return api.put(`/api/sub-eval-types/${id}`, { name })
}

export function deleteSubEvalType(id) {
  return api.delete(`/api/sub-eval-types/${id}`)
}

// ====== 模型测评报告 ======

export function fetchModelReports(groupId) {
  return api.get('/api/model-reports', groupId ? { groupId } : {})
}

export function fetchModelReport(id) {
  return api.get(`/api/model-reports/${id}`)
}

export function createModelReport(data) {
  return api.post('/api/model-reports', data)
}

export function deleteModelReport(id) {
  return api.delete(`/api/model-reports/${id}`)
}

// ====== 模型测评报告对比 ======

export function fetchReportComparisons() {
  return api.get('/api/report-comparisons')
}

export function fetchReportComparison(id) {
  return api.get(`/api/report-comparisons/${id}`)
}

export function createReportComparison(data) {
  return api.post('/api/report-comparisons', data)
}

export function deleteReportComparison(id) {
  return api.delete(`/api/report-comparisons/${id}`)
}

