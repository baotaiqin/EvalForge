import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import * as modelEvalApi from '@/api/modelEval.js'
import '@/api/mockModelEval.js'

export const useModelEvalStore = defineStore('modelEval', () => {
  const groups = ref([])
  const subEvaluations = ref([]) // 当前分组下的子评测列表
  const subEvalTypes = ref([]) // 全局共享的子评测类型列表
  const subEvalRecordsMap = reactive({}) // 按 subEvalId 分组的版本历史，支持列表页多行同时展开
  const modelReports = ref([]) // 全局历史测评报告列表（未按分组过滤，供“历史报告”页按分组展示用）
  const reportComparisons = ref([]) // 全局历史对比记录列表
  const loading = ref(false)

  function subEvalTypeLabel(subType) {
    const type = subEvalTypes.value.find(t => t.id === subType)
    return type ? type.name : subType
  }

  async function fetchGroups() {
    loading.value = true
    try {
      const res = await modelEvalApi.fetchModelGroups()
      groups.value = res.data || []
    } finally {
      loading.value = false
    }
  }

  async function createGroup(data) {
    const res = await modelEvalApi.createModelGroup(data)
    groups.value.unshift(res.data)
    return res.data
  }

  async function updateGroup(id, data) {
    const res = await modelEvalApi.updateModelGroup(id, data)
    const index = groups.value.findIndex(g => g.id === id)
    if (index !== -1) groups.value[index] = res.data
    return res.data
  }

  async function deleteGroup(id) {
    await modelEvalApi.deleteModelGroup(id)
    groups.value = groups.value.filter(g => g.id !== id)
  }

  async function duplicateGroup(id) {
    const res = await modelEvalApi.duplicateModelGroup(id)
    if (res.code !== 0) throw new Error(res.message || '复制分组失败')
    groups.value.unshift(res.data)
    return res.data
  }

  async function fetchSubEvaluations(groupId) {
    loading.value = true
    try {
      const res = await modelEvalApi.fetchSubEvaluationsByGroup(groupId)
      subEvaluations.value = res.data || []
    } finally {
      loading.value = false
    }
  }

  async function createSubEvaluation(data) {
    const res = await modelEvalApi.createSubEvaluation(data)
    if (res.code !== 0) throw new Error(res.message || '创建子评测失败')
    subEvaluations.value.unshift(res.data)
    return res.data
  }

  async function updateSubEvaluation(id, data) {
    const res = await modelEvalApi.updateSubEvaluation(id, data)
    if (res.code !== 0) throw new Error(res.message || '更新子评测失败')
    const index = subEvaluations.value.findIndex(s => s.id === id)
    if (index !== -1) subEvaluations.value[index] = res.data
    return res.data
  }

  async function deleteSubEvaluation(id) {
    await modelEvalApi.deleteSubEvaluation(id)
    subEvaluations.value = subEvaluations.value.filter(s => s.id !== id)
  }

  async function duplicateSubEvaluation(id, targetGroupId) {
    const res = await modelEvalApi.duplicateSubEvaluation(id, targetGroupId)
    if (res.code !== 0) throw new Error(res.message || '复制子评测失败')
    return res.data
  }

  async function fetchSubEvalRecords(id) {
    const res = await modelEvalApi.fetchSubEvaluationRecords(id)
    subEvalRecordsMap[id] = res.data || []
    return subEvalRecordsMap[id]
  }

  async function deleteSubEvalRecord(id, evalId) {
    const res = await modelEvalApi.deleteSubEvaluationRecord(id, evalId)
    if (res.code !== 0) throw new Error(res.message || '删除版本记录失败')
    if (subEvalRecordsMap[id]) {
      subEvalRecordsMap[id] = subEvalRecordsMap[id].filter(r => r.id !== evalId)
    }
  }

  async function runSubEvaluation(id) {
    const res = await modelEvalApi.runSubEvaluation(id)
    if (res.code !== 0) throw new Error(res.message || '开始测评失败')
    return res.data
  }

  async function batchRunSubEvaluations(ids) {
    const res = await modelEvalApi.batchRunSubEvaluations(ids)
    if (res.code !== 0) throw new Error(res.message || '批量开始测评失败')
    return res.data
  }

  async function fetchSubEvalTypes() {
    const res = await modelEvalApi.fetchSubEvalTypes()
    subEvalTypes.value = res.data || []
    return subEvalTypes.value
  }

  async function createSubEvalType(name) {
    const res = await modelEvalApi.createSubEvalType(name)
    if (res.code !== 0) throw new Error(res.message || '创建类型失败')
    subEvalTypes.value.push(res.data)
    return res.data
  }

  async function updateSubEvalType(id, name) {
    const res = await modelEvalApi.updateSubEvalType(id, name)
    if (res.code !== 0) throw new Error(res.message || '更新类型失败')
    const index = subEvalTypes.value.findIndex(t => t.id === id)
    if (index !== -1) subEvalTypes.value[index] = res.data
    return res.data
  }

  async function deleteSubEvalType(id) {
    await modelEvalApi.deleteSubEvalType(id)
    subEvalTypes.value = subEvalTypes.value.filter(t => t.id !== id)
  }

  // ========== 模型报告 ==========

  async function fetchModelReports(groupId) {
    const res = await modelEvalApi.fetchModelReports(groupId)
    modelReports.value = res.data || []
    return modelReports.value
  }

  async function createModelReport(data) {
    const res = await modelEvalApi.createModelReport(data)
    if (res.code !== 0) throw new Error(res.message || '创建报告失败')
    modelReports.value.unshift(res.data)
    return res.data
  }

  async function fetchModelReport(id) {
    const res = await modelEvalApi.fetchModelReport(id)
    if (res.code !== 0) throw new Error(res.message || '获取报告失败')
    return res.data
  }

  async function deleteModelReport(id) {
    await modelEvalApi.deleteModelReport(id)
    modelReports.value = modelReports.value.filter(r => r.id !== id)
  }

  // 报告详情轮询到最新状态后，同步更新历史报告列表里的对应条目，
  // 避免“历史报告”抽屉里的记录停留在生成中的过期快照上
  function updateModelReportInList(report) {
    if (!report) return
    const index = modelReports.value.findIndex(r => r.id === report.id)
    if (index !== -1) {
      modelReports.value[index] = { ...modelReports.value[index], ...report }
    }
  }

  // ====== 模型测评报告对比 ======

  async function fetchReportComparisons() {
    const res = await modelEvalApi.fetchReportComparisons()
    reportComparisons.value = res.data || []
    return reportComparisons.value
  }

  async function createReportComparison(data) {
    const res = await modelEvalApi.createReportComparison(data)
    if (res.code !== 0) throw new Error(res.message || '创建对比失败')
    reportComparisons.value.unshift(res.data)
    return res.data
  }

  async function fetchReportComparison(id) {
    const res = await modelEvalApi.fetchReportComparison(id)
    if (res.code !== 0) throw new Error(res.message || '获取对比结果失败')
    return res.data
  }

  async function deleteReportComparison(id) {
    await modelEvalApi.deleteReportComparison(id)
    reportComparisons.value = reportComparisons.value.filter(c => c.id !== id)
  }

  function updateReportComparisonInList(comparison) {
    if (!comparison) return
    const index = reportComparisons.value.findIndex(c => c.id === comparison.id)
    if (index !== -1) {
      reportComparisons.value[index] = { ...reportComparisons.value[index], ...comparison }
    }
  }

  return {
    groups, subEvaluations, subEvalTypes, subEvalRecordsMap, modelReports, reportComparisons, loading,
    fetchGroups, createGroup, updateGroup, deleteGroup, duplicateGroup,
    fetchSubEvaluations, createSubEvaluation, updateSubEvaluation, deleteSubEvaluation,
    duplicateSubEvaluation,
    fetchSubEvalRecords, deleteSubEvalRecord, runSubEvaluation, batchRunSubEvaluations,
    fetchSubEvalTypes, createSubEvalType, updateSubEvalType, deleteSubEvalType, subEvalTypeLabel,
    fetchModelReports, createModelReport, fetchModelReport, deleteModelReport, updateModelReportInList,
    fetchReportComparisons, createReportComparison, fetchReportComparison, deleteReportComparison, updateReportComparisonInList
  }
})

