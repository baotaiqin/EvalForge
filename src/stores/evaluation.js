import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { api, BASE_URL, IS_MOCK } from '@/api/request.js'
import '@/api/evaluation.js'
import { SCORE_DIMENSIONS, PPT_SCORE_DIMENSIONS } from '@/utils/constants.js'

export const useEvaluationStore = defineStore('evaluation', () => {
  const records = ref([])
  const currentDetail = ref(null)
  const loading = ref(false)
  const streamingProgress = ref('') // 流式评测进度提示

  async function fetchRecords() {
    loading.value = true
    try {
      const res = await api.get('/api/evaluations')
      records.value = res.data
    } finally {
      loading.value = false
    }
  }

  async function createEvaluation(form) {
    const res = await api.post('/api/evaluations', form)
    records.value.unshift(res.data)
    return res.data
  }

  /**
   * SSE 流式创建评测 — 提交后通过事件流逐题推送结果
   * @param {Object} form 评测表单（targets, datasetIds, items 等）
   * @param {Array} allItems 所有题目列表（用于初始化 results 骨架）
   * @returns {Promise} 流式完成后 resolve
   */

  async function createStreamingEvaluation(form, allItems) {
    loading.value = true
    streamingProgress.value = '正在初始化评测...'

    if (IS_MOCK) {
      try {
        const evalId = `eval-demo-${Date.now()}`
        const startTime = new Date().toLocaleString('zh-CN', { hour12: false })
        const results = (allItems || []).map((item, index) => ({
          id: `r-${item.id || `q-${index + 1}`}`,
          questionId: item.id || `q-${index + 1}`,
          question: item.question || '',
          questionImages: item.images || [],
          expectedAnswer: item.expectedAnswer || '',
          outputs: {},
          scores: {},
          hasExpected: Boolean(item.expectedAnswer),
          judgeMode: item.expectedAnswer ? 'auto' : 'manual',
          _loading: true
        }))
        currentDetail.value = { ...form, id: evalId, status: 'running', startTime, results, targets: form.targets || [] }
        records.value.unshift({ ...form, id: evalId, status: 'running', startTime, endTime: null, accuracy: null })

        for (let questionIndex = 0; questionIndex < results.length; questionIndex += 1) {
          const result = results[questionIndex]
          for (const target of currentDetail.value.targets) {
            const answer = result.expectedAnswer || `围绕“${result.question}”，建议先确认具体场景，再按业务规则给出分步骤说明。`
            result.outputs[target.id] = { answer, responseTime: 650 + Math.floor(Math.random() * 900) }
          }
          const score = +(0.72 + Math.random() * 0.25).toFixed(2)
          for (const target of currentDetail.value.targets) {
            result.scores[target.id] = { auto: { accuracy: score, completeness: score, relevance: score }, manual: null, final: score }
          }
          result._loading = false
          streamingProgress.value = `已完成 ${questionIndex + 1}/${results.length} 题`
          await new Promise(resolve => setTimeout(resolve, 90))
        }

        const values = results.flatMap(result => Object.values(result.scores).map(score => score.final)).filter(Number.isFinite)
        const accuracy = values.length ? +(values.reduce((sum, value) => sum + value, 0) / values.length).toFixed(2) : 0
        const endTime = new Date().toLocaleString('zh-CN', { hour12: false })
        currentDetail.value.status = 'completed'
        currentDetail.value.accuracy = accuracy
        currentDetail.value.endTime = endTime
        const record = records.value.find(item => item.id === evalId)
        if (record) Object.assign(record, { status: 'completed', accuracy, endTime })
        return currentDetail.value
      } finally {
        loading.value = false
        streamingProgress.value = ''
      }
    }
    loading.value = true
    streamingProgress.value = '正在初始化评测...'

    const url = `${BASE_URL}/api/evaluations/stream`
    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form)
    })

    if (!response.ok) {
      loading.value = false
      const errText = await response.text().catch(() => '')
      throw new Error(`HTTP ${response.status}: ${errText.substring(0, 200)}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        while (buffer.includes('\n\n')) {
          const idx = buffer.indexOf('\n\n')
          const eventBlock = buffer.substring(0, idx)
          buffer = buffer.substring(idx + 2)

          let eventName = 'message'
          let eventData = ''
          for (const line of eventBlock.split('\n')) {
            const trimmed = line.trim()
            if (trimmed.startsWith('event:')) {
              eventName = trimmed.substring(6).trim()
            } else if (trimmed.startsWith('data:')) {
              if (eventData) eventData += '\n'
              eventData += trimmed.substring(5)
            }
          }
          if (!eventData) continue

          let parsed
          try { parsed = JSON.parse(eventData) } catch { continue }

          if (eventName === 'init') {
            // 初始化 currentDetail 骨架
            const targets = form.targets || []
            const results = allItems.map((item, i) => ({
              id: 'r-' + (item.id || ('q-' + (i + 1))),
              questionId: item.id || ('q-' + (i + 1)),
              question: item.question,
              questionImages: item.images || [],
              expectedAnswer: item.expectedAnswer || '',
              outputs: {}, // 逐步填充
              scores: {},
              hasExpected: !!item.expectedAnswer,
              judgeMode: item.expectedAnswer ? 'auto' : 'manual',
              _loading: true // 标记正在加载
            }))
            currentDetail.value = {
              ...form,
              id: parsed.evalId,
              status: 'running',
              results,
              targets,
              judgeMode: form.judgeMode,
              judgeModelId: form.judgeModelId
            }
            // 同步到 records 列表
            records.value.unshift({
              ...form,
              id: parsed.evalId,
              status: 'running',
              startTime: new Date().toLocaleString()
            })
          }

          if (eventName === 'progress') {
            streamingProgress.value = parsed.message || ''
          }

          if (eventName === 'result' && currentDetail.value) {
            const { questionIndex, targetId, answer, responseTime, imgMap } = parsed
            const result = currentDetail.value.results[questionIndex]
            if (result) {
              result.outputs[targetId] = { answer, responseTime, imgMap }
            }
          }

          if (eventName === 'score' && currentDetail.value) {
            const { questionIndex, scores } = parsed
            const result = currentDetail.value.results[questionIndex]
            if (result) {
              result.scores = scores
              result._loading = false
            }
          }

          if (eventName === 'complete' && currentDetail.value) {
            currentDetail.value.status = 'completed'
            currentDetail.value.accuracy = parsed.accuracy
            currentDetail.value.endTime = parsed.endTime
            // 更新 records 列表
            const record = records.value.find(r => r.id === parsed.evalId)
            if (record) {
              record.status = 'completed'
              record.accuracy = parsed.accuracy
              record.endTime = parsed.endTime
            }
          }

          if (eventName === 'error') {
            throw new Error(parsed.message || '评测执行失败')
          }
        }
      }
    } finally {
      reader.releaseLock()
      loading.value = false
      streamingProgress.value = ''
    }
  }

  async function fetchDetail(id) {
    loading.value = true
    try {
      const res = await api.get(`/api/evaluations/${id}`)
      currentDetail.value = res.data
      return res.data
    } finally {
      loading.value = false
    }
  }

  /**
   * 终止一条正在执行(running)的评测记录，已完成的题目结果保留
   */
  async function stopEvaluation(id) {
    const res = await api.post(`/api/evaluations/${id}/stop`)
    return res.data
  }

  /**
   * 重新测评：复用同一条记录 id，清空已有题目结果后重新发起一轮评测。
   * 注意：不用响应体里的 data 整体覆盖 currentDetail，这样响应接口内部触发异步执行
   * 任务之前捕获的快照（results 恒为空），若覆盖会把执行任务已经通过 SSE 写入
   * currentDetail 的早期进度冲掉。调用方需在触发本方法前自行清空本地状态，
   * 后续更新完全依赖 SSE 事件。
   */
  async function restartEvaluation(id) {
    const res = await api.post(`/api/evaluations/${id}/restart`)
    if (res.code !== 0) throw new Error(res.message || '重新测评失败')
    return res.data
  }

  /**
   * 断点续跑：从指定题号(1-based)开始重新执行到末尾，之前题目结果保留不变。
   * @param {string} id 评测记录id
   * @param {number} fromIndex 起始题号(1-based，即用户看到的“第几题”)
   */
  async function resumeEvaluation(id, fromIndex) {
    const res = await api.post(`/api/evaluations/${id}/resume`, { fromIndex })
    if (res.code !== 0) throw new Error(res.message || '断点续跑失败')
    return res.data
  }

  /**
   * 单题重测：只重新执行指定题号(1-based)，其余题目结果保留不变。
   * @param {string} id 评测记录id
   * @param {number} questionIndex 题号(1-based)
   */
  async function rerunQuestion(id, questionIndex) {
    const res = await api.post(`/api/evaluations/${id}/rerun-question`, { questionIndex })
    if (res.code !== 0) throw new Error(res.message || '单题重测失败')
    return res.data
  }

  /**
   * 低于分数重跑：批量重新执行所有存在某个被测对象最终得分低于阈值(0-100实际分数)的题目，
   * 其余题目结果保持不变。仅已完成的记录支持。
   */
  async function rerunBelowScore(id, threshold) {
    const res = await api.post(`/api/evaluations/${id}/rerun-below-score`, { threshold })
    if (res.code !== 0) throw new Error(res.message || '低分数量重跑失败')
    return res.data
  }

  async function updateEvaluation(id, data) {
    const res = await api.put(`/api/evaluations/${id}`, data)
    const index = records.value.findIndex(r => r.id === id)
    if (index !== -1) records.value[index] = { ...records.value[index], ...res.data }
    return res.data
  }

  async function deleteRecord(id) {
    await api.delete(`/api/evaluations/${id}`)
    records.value = records.value.filter(r => r.id !== id)
  }

  async function updateScore(evalId, resultId, scoreData) {
    const final = calculateFinalScore(scoreData.scores)
    const res = await api.put(`/api/evaluations/${evalId}/results/${resultId}/score`, { ...scoreData, final })

    // 本地更新 currentDetail 中对应结果的分数
    if (currentDetail.value && currentDetail.value.id === evalId) {
      const result = currentDetail.value.results.find(r => r.id === resultId)
      if (result) {
        if (!result.scores) result.scores = {}
        if (!result.scores[scoreData.targetId]) result.scores[scoreData.targetId] = {}
        result.scores[scoreData.targetId].manual = scoreData.scores
        result.scores[scoreData.targetId].final = final
        result.scores[scoreData.targetId].remark = scoreData.remark
      }
    }
    return res.data
  }

  // 按 scores 实际存在的维度 key 判断是 PPT 对比评测还是文本评测，用对应权重加权求和
  function calculateFinalScore(scores) {
    if (!scores) return 0
    const isPpt = PPT_SCORE_DIMENSIONS.some(dim => scores[dim.key] !== undefined)
    const dimensions = isPpt ? PPT_SCORE_DIMENSIONS : SCORE_DIMENSIONS
    const total = dimensions.reduce((sum, dim) => sum + (scores[dim.key] || 0) * dim.weight, 0)
    return +total.toFixed(2)
  }

  // 计算当前详情的各 target 准确率
  const accuracySummary = computed(() => {
    if (!currentDetail.value?.results?.length) return {}
    const summary = {}
    const targets = currentDetail.value.targets || []

    targets.forEach(target => {
      const validResults = currentDetail.value.results.filter(r => r.scores[target.id])
      if (validResults.length === 0) {
        summary[target.id] = { label: target.label, accuracy: 0, avgScore: 0 }
        return
      }
      const totalScore = validResults.reduce((sum, r) => sum + (r.scores[target.id]?.final || 0), 0)
      summary[target.id] = {
        label: target.label,
        accuracy: +(totalScore / validResults.length).toFixed(2),
        avgScore: +((totalScore / validResults.length) * 100).toFixed(1)
      }
    })
    return summary
  })

  return {
    records, currentDetail, loading, streamingProgress,
    fetchRecords, createEvaluation, createStreamingEvaluation,
    fetchDetail, updateEvaluation, deleteRecord, updateScore,
    stopEvaluation, restartEvaluation, resumeEvaluation, rerunQuestion, rerunBelowScore,
    accuracySummary
  }
})

