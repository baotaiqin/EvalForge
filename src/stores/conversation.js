import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, BASE_URL, IS_MOCK } from '@/api/request.js'
import { useConfigStore } from '@/stores/config.js'
import '@/api/conversation.js'

export const useConversationStore = defineStore('conversation', () => {
  const conversations = ref([])
  const loading = ref(false)

  // 用于取消请求的 AbortController
  let abortController = null

  // 登录 token 缓存: "envUrl|email" -> { authorization, apiUsername, timestamp }
  const authTokenCache = {}
  const AUTH_CACHE_TTL = 20 * 60 * 1000 // 20 分钟

  // 会话缓存: targetId -> { dialogId, chatToken }
  const sessionCache = {}

  /**
   * 确保已登录，返回 { authorization, apiUsername }
   */
  async function ensureLogin(envUrl, email, password) {
    const cacheKey = `${envUrl}|${email}`
    const cached = authTokenCache[cacheKey]
    if (cached && Date.now() - cached.timestamp < AUTH_CACHE_TTL) {
      return { authorization: cached.authorization, apiUsername: cached.apiUsername }
    }

    const configStore = useConfigStore()
    const result = await configStore.loginToEnv(envUrl, email, password)
    authTokenCache[cacheKey] = { ...result, timestamp: Date.now() }
    return result
  }

  /**
   * 从历史对话中构建模型的 history 参数
   */
  function buildModelHistory(modelIds) {
    const history = []
    for (const conv of conversations.value) {
      if (conv.mode !== 'model') continue
      const answers = {}
      for (const resp of conv.responses) {
        if (resp.modelId && resp.answer && !resp.loading) {
          answers[resp.modelId] = resp.answer
        }
      }
      if (Object.keys(answers).length > 0) {
        history.push({ question: conv.question, answers })
      }
    }
    return history
  }

  // 发送消息给多个模型（带对话历史）— 非流式
  async function sendToModels(question, modelIds) {
    loading.value = true
    abortController = new AbortController()
    try {
      const history = buildModelHistory(modelIds)
      const res = await api.post('/api/conversation/models',
        { question, modelIds, history: history.length > 0 ? history : undefined },
        { signal: abortController.signal }
      )
      return res.data
    } finally {
      loading.value = false
      abortController = null
    }
  }

  // ========== Agent SSE 流式对话 ==========

  /**
   * 为单个 Agent 建立 SSE 流式连接
   * 对应 SemiMind 前端的 useSendMessageWithSse.send() 逻辑
   *
   * SSE 数据格式(来自 SemiMind chat 接口):
   * 格式A: {"data":{"answer":"增量文本","reference":null,"files":null}}
   *       → answer 逐事件累积拼接, reference/files 直接覆盖
   * 格式B: [{"type":"text","md_content":"..."},{"type":"tool_use","md_content":{...}}]
   *       → 合并所有 chunk, 保持 text/tool_use 交替顺序
   *
   * @param {Object} target Agent 目标参数
   * @param {string} question 用户问题
   * @param {AbortSignal} signal 取消信号
   * @param {Function} onUpdate 实时更新回调 ({answer, dialogId, chatToken, done, responseTime})
   */
  async function streamAgentChat(target, question, signal, onUpdate) {
    const startTime = Date.now()
    if (IS_MOCK) {
      const dialogId = target.dialogId || `mock-dialog-${Date.now()}`
      const chatToken = target.chatToken || `mock-token-${Math.random().toString(36).slice(2, 10)}`
      const answer = `这是 ${target.label || target.agentName || '演示 Agent'} 的本地模拟回答。\n\n针对“${question}”，建议先确认业务规则和适用条件，再按页面提示逐步处理。当前运行在演示模式，连接真实环境后会返回 Agent 的实际回答。`
      onUpdate({ dialogId, chatToken })
      let accumulated = ''
      for (const character of Array.from(answer)) {
        if (signal?.aborted) {
          const error = new Error('已停止回答')
          error.name = 'AbortError'
          throw error
        }
        accumulated += character
        onUpdate({ answer: accumulated })
        await new Promise(resolve => setTimeout(resolve, 8))
      }
      onUpdate({ done: true, responseTime: Date.now() - startTime })
      return
    }
   const url = `${BASE_URL}/api/conversation/agent/stream`

    const response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        envUrl: target.envUrl,
        agentId: target.agentId,
        agentType: target.agentType,
        question,
        dialogId: target.dialogId || null,
        chatToken: target.chatToken || null,
        authorization: target.authorization,
        apiUsername: target.apiUsername,
      }),
      signal,
    })

    if (!response.ok) {
      const errText = await response.text().catch(() => '')
      throw new Error(`HTTP ${response.status}: ${errText.substring(0, 200)}`)
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    // ========== SSE 解析状态 ==========
    // 格式A: answer 累积
    let accumulatedAnswer = ''
    let isAnswerFormat = false

    // 格式B: data 数组 (tool_use + text)
    const parts = []
    let currentTextSegment = ''
    const completedToolIds = new Set()
    const pendingToolIndex = new Map()

    // 图片 URL 映射: "MINIO-{key}" -> "{bucket}-ragimage/{fname}"
    const imgMap = {}

    function flushTextSegment() {
      if (currentTextSegment) {
        parts.push({ type: 'text', content: currentTextSegment })
        currentTextSegment = ''
      }
    }

    /** 根据当前累积状态生成 resp.answer 字符串 */
    function buildCurrentAnswer() {
      // 格式A: 直接返回累积的纯文本
      if (isAnswerFormat) {
        return accumulatedAnswer
      }
      // 格式B: 构建结构化 JSON (与后端 parseDataList 输出格式一致)
      const allParts = [...parts]
      if (currentTextSegment) {
        allParts.push({ type: 'text', content: currentTextSegment })
      }
      if (allParts.length === 0) return ''

      const hasStructured = allParts.some(p => p.type === 'tool_use' || p.type === 'file')
      if (hasStructured) {
        const textContent = allParts
          .filter(p => p.type === 'text')
          .map(p => p.content)
          .join('\n\n')
        return JSON.stringify({ textContent, parts: allParts })
      }
      // 纯文本
      return allParts.map(p => p.content).join('\n\n')
    }

    /** 处理一个 SemiMind SSE 数据块 */
    function processChunk(data) {
      if (!data || !data.trim()) return

      let parsed
      try {
        parsed = JSON.parse(data)
      } catch {
        // 非 JSON: 当作纯文本追加 (避免丢失内容)
        console.warn('[SSE] 非JSON数据，当作文本处理:', data.substring(0, 100))
        isAnswerFormat = true
        accumulatedAnswer += data
        return
      }

      // NDJSON 双重编码: {data: "stringified JSON"} -> 解码后递归处理
      if (parsed && typeof parsed.data === 'string') {
        const innerStr = parsed.data.trim()
        if (innerStr === 'true' || innerStr === '[DONE]') return
        try {
          const inner = JSON.parse(innerStr)
          // 内层 {retcode:0, data:[...]} -> 提取 data 数组
          if (inner && inner.data !== undefined) {
            if (inner.data === true) return
            processChunk(JSON.stringify(inner))
          } else {
            processChunk(innerStr)
          }
        } catch {
          // 内层不是 JSON，当作文本
          isAnswerFormat = true
          accumulatedAnswer += innerStr
        }
        return
      }

      let dataList = null

      // 格式A: {data: {answer: "...", reference: {...}, files:[...]}}
      // 或 SemiMind 流式单对象: {data: {type: "text", md_content: "..."}}
      if (parsed && parsed.data && typeof parsed.data === 'object' && !Array.isArray(parsed.data)) {
        // SemiMind 流式格式: data 是单个 {type, md_content} 对象，包装成数组走格式B逻辑
        if (parsed.data.type !== undefined) {
          dataList = [parsed.data]
        } else {
          const { answer } = parsed.data
          if (answer != null && answer !== '') {
            isAnswerFormat = true
            accumulatedAnswer += String(answer)
          }
          return
        }
      }

      // 格式B: 裸数组 [{type, md_content}] 或 {data: [...]}
      if (!dataList) {
        if (Array.isArray(parsed)) {
          dataList = parsed
        } else if (parsed && Array.isArray(parsed.data)) {
          dataList = parsed.data
        }
      }

      if (dataList) {
        for (const item of dataList) {
          if (!item || typeof item !== 'object') continue
          const type = item.type || ''
          const mdContent = item.md_content

          if (type === 'text') {
            if (mdContent != null) {
              currentTextSegment += String(mdContent)
            }
          } else if (type === 'tool_use' && mdContent && typeof mdContent === 'object') {
            const toolId = mdContent.tool_id || ''
            const status = mdContent.status || ''
            const isCompleted = ['success', 'error', 'failed'].includes(status)

            const toolPart = {
              type: 'tool_use',
              toolName: mdContent.tool_name || 'unknown',
              status,
              useTarget: mdContent.use_target || '',
              content: ''
            }
            const toolRes = mdContent.tool_res
            if (toolRes && typeof toolRes === 'object' && toolRes.content) {
              toolPart.content = toolRes.content
            } else if (typeof toolRes === 'string') {
              toolPart.content = toolRes
            }

            if (toolId && isCompleted) {
              completedToolIds.add(toolId)
              if (pendingToolIndex.has(toolId)) {
                parts[pendingToolIndex.get(toolId)] = toolPart
                pendingToolIndex.delete(toolId)
              } else {
                flushTextSegment()
                parts.push(toolPart)
              }
            } else if (toolId && !completedToolIds.has(toolId)) {
              flushTextSegment()
              pendingToolIndex.set(toolId, parts.length)
              parts.push(toolPart)
            } else if (!toolId) {
              flushTextSegment()
              parts.push(toolPart)
            }
          } else if (type === 'reference') {
            // 提取 elements_url_mapping 构建图片 URL 映射
            const refData = mdContent || item.content
            if (refData && typeof refData === 'object') {
              const chunks = refData.chunks || []
              for (const chunk of chunks) {
                const mapping = chunk.elements_url_mapping
                if (!mapping) continue
                for (const [key, url] of Object.entries(mapping)) {
                  if (!url) continue
                  const dashIdx = url.indexOf('-')
                  if (dashIdx > 0) {
                    const bucket = url.substring(0, dashIdx)
                    const fname = url.substring(dashIdx + 1)
                    imgMap[`MINIO-${key}`] = `${bucket}-ragimage/${fname}`
                  }
                }
              }
            }
          } else if (type === 'file') {
            // 文件附件: 数据在 content 或 md_content 字段
            const fileData = item.content || mdContent
            if (fileData && typeof fileData === 'object') {
              flushTextSegment()
              parts.push({
                type: 'file',
                fileName: fileData.file_name || 'file',
                fileType: fileData.file_type || '',
                fileDownloadUrl: fileData.file_download_url || '',
                docId: fileData.doc_id || ''
              })
            }
          } else if (mdContent != null && type !== 'reference') {
            currentTextSegment += String(mdContent)
          }
        }
      }
    }

    // ===== 主循环: 读取 SSE 流 =====
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // 按双换行分割完整的 SSE 事件块
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
              // SSE 规范: 多行 data 用 \n 连接
              if (eventData) eventData += '\n'
              eventData += trimmed.substring(5)
            }
          }

          if (!eventData) continue

          // 处理不同事件类型
          if (eventName === 'session') {
            try {
              const session = JSON.parse(eventData)
              onUpdate({ dialogId: session.dialogId, chatToken: session.chatToken })
            } catch {}
            continue
          }

          if (eventName === 'error') {
            let errMsg = 'Agent调用失败'
            try {
              errMsg = JSON.parse(eventData).message || errMsg
            } catch {}
            throw new Error(errMsg)
          }

          // message 事件: 处理 SemiMind 原始 SSE 数据
          processChunk(eventData)
          const answer = buildCurrentAnswer()
          if (answer) {
            const update = { answer }
            if (Object.keys(imgMap).length > 0) update.imgMap = imgMap
            onUpdate(update)
          }
        }
      }

      // 处理剩余 buffer
      if (buffer.trim()) {
        for (const line of buffer.split('\n')) {
          const trimmed = line.trim()
          if (trimmed.startsWith('data:')) {
            processChunk(trimmed.substring(5))
          }
        }
        const answer = buildCurrentAnswer()
        if (answer) {
          const update = { answer }
          if (Object.keys(imgMap).length > 0) update.imgMap = imgMap
          onUpdate(update)
        }
      }
    } finally {
      reader.releaseLock()
    }

    // 流完成
    const doneUpdate = { done: true, responseTime: Date.now() - startTime }
    if (Object.keys(imgMap).length > 0) doneUpdate.imgMap = imgMap
    onUpdate(doneUpdate)
  }


  /**
   * 发送消息给多个 Agent（SSE 流式）
   * 为每个 Agent 建立独立的 SSE 连接，并行处理，实时更新 UI
   *
   * @param {string} question 问题
   * @param {Array} targets Agent 目标列表
   * @param {number} convIndex 对话在 conversations 数组中的索引
   */
  async function sendToAgentsStream(question, targets, convIndex) {
    loading.value = true
    abortController = new AbortController()

    const promises = targets.map(async (target, idx) => {
      // 1. 预登录获取 auth token
      let enriched = { ...target }
      if (!enriched.authorization && enriched.envUrl && enriched.userEmail) {
        try {
          const auth = await ensureLogin(enriched.envUrl, enriched.userEmail, enriched.userPassword || enriched.userEmail)
          enriched = { ...enriched, authorization: auth.authorization, apiUsername: auth.apiUsername }
        } catch (e) {
          console.warn('Agent预登录失败:', enriched.envUrl, enriched.userEmail, e.message)
        }
      }
      // 附加缓存的会话信息
      const cached = sessionCache[enriched.id]
      if (cached) {
        enriched = { ...enriched, dialogId: cached.dialogId, chatToken: cached.chatToken }
      }

      // 2. 建立 SSE 流式连接
      try {
        await streamAgentChat(enriched, question, abortController.signal, (update) => {
          const resp = conversations.value[convIndex]?.responses[idx]
          if (!resp) return

          if (update.answer !== undefined) {
            resp.answer = update.answer
          }
          if (update.imgMap) {
            resp.imgMap = update.imgMap
          }
          if (update.dialogId) {
            resp.dialogId = update.dialogId
            // 缓存 session 信息供下次复用
            if (!sessionCache[enriched.id]) sessionCache[enriched.id] = {}
            sessionCache[enriched.id].dialogId = update.dialogId
          }
          if (update.chatToken) {
            resp.chatToken = update.chatToken
            if (!sessionCache[enriched.id]) sessionCache[enriched.id] = {}
            sessionCache[enriched.id].chatToken = update.chatToken
          }
          if (update.done) {
            resp.loading = false
            resp.responseTime = update.responseTime
          }
        })
      } catch (err) {
        const resp = conversations.value[convIndex]?.responses[idx]
        if (!resp) return
        const isAborted = err.name === 'AbortError'
        resp.loading = false
        resp.answer = isAborted ? '已停止回答' : `Agent调用失败: ${err.message}`
      }
    })

    await Promise.allSettled(promises)
    loading.value = false
    abortController = null
  }

  // 重新回答（仅模型，Agent 重新回答已改为前端直接调用 streamAgentChat）
  async function regenerate(question, modelId, extra = {}) {
    const history = buildModelHistory([modelId])
    const res = await api.post('/api/conversation/regenerate', {
      question,
      targetId: modelId,
      ...extra,
      history: history.length > 0 ? history : undefined
    })
    return res.data
  }

  // 停止生成
  function stopGeneration() {
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    loading.value = false
  }

  // 添加一轮对话到历史
  function addConversation(entry) {
    conversations.value.push(entry)
  }

  // 清空历史（同时清空会话缓存）
  function clearConversations() {
    conversations.value = []
    Object.keys(sessionCache).forEach(key => delete sessionCache[key])
  }

  // 清空登录缓存
  function clearAuthCache() {
    Object.keys(authTokenCache).forEach(key => delete authTokenCache[key])
  }

  // 清除指定用户的登录缓存
  function invalidateAuth(envUrl, email) {
    const cacheKey = `${envUrl}|${email}`
    delete authTokenCache[cacheKey]
  }

  return {
    conversations, loading,
    sendToModels, sendToAgentsStream, streamAgentChat, regenerate, stopGeneration,
    addConversation, clearConversations, clearAuthCache, invalidateAuth, ensureLogin
  }
})


