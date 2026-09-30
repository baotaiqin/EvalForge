<template>
  <div class="page-content conversation-page">
    <div class="page-header">
      <h2>会话</h2>
      <a-space>
        <a-radio-group v-model:value="chatMode" button-style="solid">
          <a-radio-button value="model">多模型问答</a-radio-button>
          <a-radio-button value="agent">多Agent问答</a-radio-button>
        </a-radio-group>
        <a-button @click="handleClear" :disabled="conversationStore.conversations.length === 0">
          <template #icon><ClearOutlined /></template>
          清空会话
        </a-button>
      </a-space>
    </div>

    <!-- 选择入口 -->
    <a-card size="small" style="margin-bottom: 16px;">
      <div v-if="chatMode === 'model'">
        <div style="margin-bottom: 8px; font-weight: 500;">选择模型（可多选）：</div>
        <a-select
          v-model:value="selectedModelIds"
          mode="multiple"
          placeholder="请选择要对话的模型"
          style="width: 100%;"
          :options="modelOptions"
          show-search
          :filter-option="filterModelOption"
        />
      </div>

      <div v-else>
        <div style="margin-bottom: 8px; font-weight: 500;">选择Agent（可多选）：</div>
        <div class="agent-select-row">
          <a-select
            v-model:value="selectedEnvId"
            placeholder="选择环境"
            style="width: 160px;"
            show-search
            :filter-option="filterEnvOption"
            @change="handleConvEnvChange"
          >
            <a-select-option v-for="env in configStore.environments" :key="env.id" :value="env.id">
              {{ env.name }}
            </a-select-option>
          </a-select>
          <a-select
            v-model:value="selectedUserId"
            placeholder="选择用户"
            style="width: 220px;"
            :disabled="!selectedEnvId"
            show-search
            :filter-option="false"
            @search="handleConvUserSearch"
            @change="handleConvUserChange"
            @popupScroll="handleConvUserPopupScroll"
            :loading="convUserLoading"
            allow-clear
          >
            <!-- 评测账号置顶 -->
            <a-select-option
              v-if="convTestAccount && convTestAccount.id"
              :value="convTestAccount.id"
            >
              <span style="font-weight: 500;">{{ convTestAccount.email }}</span>
              <a-tag color="green" style="margin-left: 4px; font-size: 11px;">推荐</a-tag>
            </a-select-option>
            <a-select-option
              v-if="convTestAccount && convTestAccount.id && convDisplayedUsers.length"
              disabled
              value="__divider__"
            >
              <a-divider style="margin: 4px 0;" />
            </a-select-option>
            <a-select-option v-for="user in convDisplayedUsers" :key="user.id" :value="user.id">
              {{ user.email }}
            </a-select-option>
            <a-select-option v-if="convUserLoading" disabled value="__loading__">
              <a-spin size="small" /> 加载中...
            </a-select-option>
          </a-select>
          <a-select
            v-model:value="selectedAgentIds"
            mode="multiple"
            placeholder="选择Agent（可多选）"
            style="flex: 1; min-width: 200px;"
            :disabled="!selectedUserId"
            show-search
            :options="agentOptions"
            :filter-option="filterAgentOption"
            :max-tag-count="3"
            :max-tag-placeholder="omitted => `+${omitted.length}...`"
            :loading="convAgentLoading"
            virtual
            :list-height="256"
          />
        </div>
        <!-- 评测账号推荐提示 -->
        <a-alert
          v-if="convTestAccount && selectedEnvId"
          type="info"
          show-icon
          style="margin-top: 8px;"
        >
          <template #message>
            由于会模拟用户登录操作获取 authentic，推荐使用评测账号
            <a-tag color="green">{{ convTestAccount.email }}</a-tag>
          </template>
        </a-alert>
        <!-- 已选的Agent标签 -->
        <div v-if="selectedAgentTargets.length" style="margin-top: 8px;">
          <a-tag
            v-for="target in selectedAgentTargets"
            :key="target.id"
            color="blue"
            closable
            style="margin-bottom: 4px;"
            @close="removeAgentTarget(target.id)"
          >
            {{ target.label }}
          </a-tag>
        </div>
      </div>
    </a-card>

    <!-- 对话历史 -->
    <div class="conversation-area" ref="conversationAreaRef">
      <div v-if="conversationStore.conversations.length === 0" class="conversation-empty">
        <MessageOutlined style="font-size: 48px; margin-bottom: 16px;" />
        <div>选择{{ chatMode === 'model' ? '模型' : 'Agent' }}后开始对话</div>
      </div>
      <div
        v-for="(conv, convIndex) in conversationStore.conversations"
        :key="convIndex"
        class="conversation-round"
      >
        <!-- 用户提问 -->
        <div class="user-message">
          <div class="message-avatar user-avatar"><UserOutlined /></div>
          <div class="message-content user-content">{{ conv.question }}</div>
        </div>

        <!-- 回答卡片列表 -->
        <div class="response-cards">
          <div
            v-for="(resp, respIndex) in conv.responses"
            :key="respIndex"
            class="response-card"
          >
            <div class="card-header">
              <span class="card-title">{{ resp.label || resp.modelName }}</span>
              <a-tag v-if="resp.responseTime" size="small">{{ resp.responseTime }}ms</a-tag>
              <a-tag v-if="resp.loading" color="processing" size="small">回答中...</a-tag>
            </div>
            <div class="card-body">
              <!-- 仅等待连接时显示 spinner，流式数据到达后切换为内容渲染 -->
              <a-spin v-if="resp.loading && !resp.answer" />
              <template v-else>
                <!-- 工具调用 + 文本回答按原始顺序渲染 -->
                <template v-for="(part, pIdx) in parseStructured(resp.answer).parts" :key="pIdx">
                  <a-collapse v-if="part.type === 'tool_use'" size="small" style="margin-bottom: 8px;">
                    <a-collapse-panel :header="`${part.toolName}${part.useTarget ? ' - ' + part.useTarget : ''}`">
                      <template #extra>
                        <a-tag
                          :color="part.status === 'success' ? 'green' : part.status === 'pending' ? 'processing' : 'default'"
                          size="small"
                        >{{ part.status }}</a-tag>
                      </template>
                      <div class="tool-content">{{ part.content || '无返回内容' }}</div>
                    </a-collapse-panel>
                  </a-collapse>
                  <div
                    v-else-if="part.type === 'text'"
                    class="answer-text"
                    v-html="renderMarkdown(part.content, resp.envUrl, resp.imgMap)"
                  />
                  <!-- 文件附件 -->
                  <div v-else-if="part.type === 'file'" class="file-item" style="margin: 4px 0;">
                    <a-button size="small" @click="handleFileDownload(part.fileName, part.fileDownloadUrl, resp.envUrl)">
                      <template #icon><FileOutlined /></template>
                      {{ part.fileName || '下载文件' }}
                      <a-tag v-if="part.fileType" size="small" style="margin-left: 4px;">{{ part.fileType }}</a-tag>
                    </a-button>
                  </div>
                  <!-- reference 类型：跳过 -->
                </template>
                <!-- 流式输出光标 -->
                <span v-if="resp.loading && resp.answer" class="streaming-cursor">▍</span>
                <div v-if="!resp.loading && parseStructured(resp.answer).parts.length === 0" class="answer-text">无输出</div>
              </template>
            </div>
            <div class="card-footer" v-if="!resp.loading">
              <a-space>
                <a-button type="text" size="small" @click="handleCopy(parseStructured(resp.answer).textContent)">
                  <template #icon><CopyOutlined /></template>
                  复制
                </a-button>
                <a-button
                  type="text"
                  size="small"
                  :loading="resp.regenerating"
                  @click="handleRegenerate(convIndex, respIndex)"
                >
                  <template #icon><ReloadOutlined /></template>
                  重新回答
                </a-button>
              </a-space>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <a-input-group compact style="display: flex;">
        <a-textarea
          v-model:value="inputQuestion"
          placeholder="输入您的问题..."
          :auto-size="{ minRows: 1, maxRows: 4 }"
          style="flex: 1;"
          @pressEnter="handleInputEnter"
        />
        <a-button
          v-if="conversationStore.loading"
          danger
          @click="handleStop"
          style="height: auto;"
        >
          <template #icon><StopOutlined /></template>
          停止
        </a-button>
        <a-button v-else type="primary" :disabled="!canSend" @click="handleSend" style="height: auto;">
          <template #icon><SendOutlined /></template>
          发送
        </a-button>
      </a-input-group>
      <div style="margin-top: 4px; font-size: 12px; color: #999;">
        按 Enter 发送，Shift+Enter 换行
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, h, watch } from 'vue'
import { message, Modal, Input } from 'ant-design-vue'
import {
  ClearOutlined,
  MessageOutlined,
  UserOutlined,
  CopyOutlined,
  ReloadOutlined,
  SendOutlined,
  StopOutlined,
  FileOutlined
} from '@ant-design/icons-vue'
import { useConversationStore } from '@/stores/conversation.js'
import { useConfigStore } from '@/stores/config.js'
import { renderMarkdown } from '@/utils/markdown.js'

const conversationStore = useConversationStore()
const configStore = useConfigStore()
const chatMode = ref('model')
const inputQuestion = ref('')
const conversationAreaRef = ref(null)

// 模型选择
const selectedModelIds = ref([])

// Agent 选择
const selectedEnvId = ref(undefined)
const selectedUserId = ref(undefined)
const selectedAgentIds = ref([])
const convUserList = ref([])
const convUserTotal = ref(0)
const convUserLoading = ref(false)
const convUserSearchKeyword = ref('')
const convTestAccount = ref(null)
const convAgentList = ref([])
const convAgentLoading = ref(false)
const convUserPassword = ref(undefined)
const CONV_USER_PAGE_SIZE = 50
let convUserRequestToken = 0
let convAgentRequestToken = 0
const regenerateControllers = new Set()

const modelOptions = computed(() => configStore.models.map(model => ({
  label: `${model.name} (${model.provider})`,
  value: model.id
})))

// 评测账号从普通用户选项中过滤，避免重复显示
const convDisplayedUsers = computed(() => {
  if (!convTestAccount.value?.id) return convUserList.value
  return convUserList.value.filter(user => user.id !== convTestAccount.value.id)
})

const agentOptions = computed(() => convAgentList.value.map(agent => ({
  label: agent.name,
  value: agent.id
})))

const selectedAgentTargets = computed(() => {
  if (!selectedEnvId.value || !selectedUserId.value) return []
  const env = configStore.environments.find(item => item.id === selectedEnvId.value)
  if (!env) return []

  let user = convUserList.value.find(item => item.id === selectedUserId.value)
  if (!user && convTestAccount.value?.id === selectedUserId.value) user = convTestAccount.value
  if (!user) return []

  return selectedAgentIds.value.map(agentId => {
    const agent = convAgentList.value.find(item => item.id === agentId)
    return {
      id: `${env.id}|${user.id}|${agentId}`,
      envId: env.id,
      envName: env.name,
      envUrl: env.url || env.envUrl,
      userId: user.id,
      userName: user.nickname,
      userEmail: user.email,
      userPassword: convTestAccount.value?.id === user.id
        ? convTestAccount.value.password
        : convUserPassword.value,
      agentId,
      agentName: agent?.name || agentId,
      agentType: agent?.type || 'bot',
      label: `${env.name} - ${user.nickname || user.email} - ${agent?.name || agentId}`
    }
  })
})

const canSend = computed(() => {
  const hasTarget = chatMode.value === 'model'
    ? selectedModelIds.value.length > 0
    : selectedAgentTargets.value.length > 0
  return Boolean(inputQuestion.value.trim()) && hasTarget && !conversationStore.loading
})

function filterModelOption(input, option) {
  return String(option?.label || '').toLowerCase().includes(String(input || '').toLowerCase())
}

function filterEnvOption(input, option) {
  const env = configStore.environments.find(item => item.id === option.value)
  return String(env?.name || '').toLowerCase().includes(String(input || '').toLowerCase())
}

function filterAgentOption(input, option) {
  return String(option?.label || '').toLowerCase().includes(String(input || '').toLowerCase())
}

async function handleConvEnvChange(envId) {
  const token = ++convUserRequestToken
  convAgentRequestToken += 1
  convAgentLoading.value = false
  convUserLoading.value = false
  selectedUserId.value = undefined
  selectedAgentIds.value = []
  convUserList.value = []
  convUserTotal.value = 0
  convAgentList.value = []
  convTestAccount.value = null
  convUserPassword.value = undefined
  convUserSearchKeyword.value = ''
  if (!envId) return

  const env = configStore.environments.find(item => item.id === envId)
  if (!env) return

  convUserLoading.value = true
  try {
    const [account, result] = await Promise.all([
      configStore.fetchTestAccount(env).catch(() => null),
      configStore.fetchUsersByEnvUrl(env, 0, CONV_USER_PAGE_SIZE, '')
    ])
    if (token !== convUserRequestToken) return
    convTestAccount.value = account
    convUserList.value = result?.users || []
    convUserTotal.value = result?.total || 0
  } catch (error) {
    if (token === convUserRequestToken) message.error(error.message || '加载用户列表失败')
  } finally {
    if (token === convUserRequestToken) convUserLoading.value = false
  }
}

async function loadConvUsers(reset = false) {
  const env = configStore.environments.find(item => item.id === selectedEnvId.value)
  if (!env || convUserLoading.value) return
  const offset = reset ? 0 : convUserList.value.length
  if (!reset && offset >= convUserTotal.value) return

  const token = ++convUserRequestToken
  convUserLoading.value = true
  try {
    const result = await configStore.fetchUsersByEnvUrl(
      env,
      offset,
      CONV_USER_PAGE_SIZE,
      convUserSearchKeyword.value
    )
    if (token !== convUserRequestToken) return
    const users = result?.users || []
    convUserList.value = reset ? users : [...convUserList.value, ...users]
    convUserTotal.value = result?.total || 0
  } catch (error) {
    if (token === convUserRequestToken) message.error(error.message || '加载用户列表失败')
  } finally {
    if (token === convUserRequestToken) convUserLoading.value = false
  }
}

function handleConvUserSearch(keyword) {
  convUserRequestToken += 1
  convUserLoading.value = false
  convUserSearchKeyword.value = keyword || ''
  convUserList.value = []
  convUserTotal.value = 0
  loadConvUsers(true)
}

function handleConvUserPopupScroll(event) {
  const target = event?.target
  if (target && target.scrollTop + target.clientHeight >= target.scrollHeight - 24) {
    loadConvUsers(false)
  }
}

async function handleConvUserChange(userId) {
  const requestToken = ++convAgentRequestToken
  selectedAgentIds.value = []
  convAgentList.value = []
  convAgentLoading.value = false
  convUserPassword.value = undefined
  if (!userId || userId === '__divider__' || userId === '__loading__') return

  const env = configStore.environments.find(item => item.id === selectedEnvId.value)
  if (!env) return
  if (convTestAccount.value?.id === userId) {
    convUserPassword.value = convTestAccount.value.password
  }

  convAgentLoading.value = true
  try {
    const result = await configStore.fetchAgentsByUser(env, userId)
    if (requestToken !== convAgentRequestToken) return
    convAgentList.value = [
      ...(result?.bots || []).map(agent => ({ ...agent, type: agent.type || 'bot' })),
      ...(result?.canvases || []).map(agent => ({ ...agent, type: agent.type || 'canvas' }))
    ]
  } catch (error) {
    if (requestToken === convAgentRequestToken) message.error(error.message || '加载Agent列表失败')
  } finally {
    if (requestToken === convAgentRequestToken) convAgentLoading.value = false
  }
}

function removeAgentTarget(targetId) {
  selectedAgentIds.value = selectedAgentIds.value.filter(id => `${selectedEnvId.value}|${selectedUserId.value}|${id}` !== targetId)
}

function parseStructured(answer) {
  if (!answer) return { textContent: '', parts: [] }
  if (typeof answer !== 'string') {
    const textContent = String(answer)
    return { textContent, parts: [{ type: 'text', content: textContent }] }
  }
  try {
    const parsed = JSON.parse(answer)
    if (parsed && Array.isArray(parsed.parts)) {
      return {
        textContent: parsed.textContent || parsed.parts.filter(part => part.type === 'text').map(part => part.content).join('\n\n'),
        parts: parsed.parts
      }
    }
  } catch {
    // Plain text answers are rendered as Markdown.
  }
  return { textContent: answer, parts: [{ type: 'text', content: answer }] }
}

async function handleCopy(text) {
  try {
    await navigator.clipboard.writeText(text || '')
    message.success('已复制到剪贴板')
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text || ''
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    textarea.remove()
    message.success('已复制到剪贴板')
  }
}

function handleClear() {
  Modal.confirm({
    title: '清空会话',
    content: '确定清空全部会话记录吗？',
    okText: '清空',
    cancelText: '取消',
    onOk: () => {
      handleStop()
      conversationStore.clearConversations()
    }
  })
}

function handleStop() {
  conversationStore.stopGeneration()
  for (const controller of regenerateControllers) controller.abort()
  regenerateControllers.clear()
}

function handleSend() {
  if (conversationStore.loading) return
  const question = inputQuestion.value.trim()
  if (!question) return
  if (chatMode.value === 'model' && !selectedModelIds.value.length) {
    message.warning('请先选择至少一个模型')
    return
  }
  if (chatMode.value === 'agent' && !selectedAgentTargets.value.length) {
    message.warning('请先选择至少一个Agent')
    return
  }

  const needsPassword = chatMode.value === 'agent' && selectedAgentTargets.value.some(
    target => target.userPassword == null && target.userId !== convTestAccount.value?.id
  )
  if (needsPassword) {
    let password = ''
    Modal.confirm({
      title: '输入用户密码',
      content: h(Input.Password, {
        placeholder: '请输入所选用户的登录密码',
        onChange: event => { password = event.target.value }
      }),
      okText: '继续',
      cancelText: '取消',
      onOk: async () => {
        if (!password) {
          message.warning('请输入用户密码')
          throw new Error('需要用户密码')
        }
        convUserPassword.value = password
        await sendConversation(question)
      }
    })
    return
  }
  sendConversation(question)
}

function handleInputEnter(event) {
  if (event?.shiftKey) return
  event?.preventDefault?.()
  handleSend()
}

async function sendConversation(question) {
  inputQuestion.value = ''
  const targets = chatMode.value === 'model'
    ? selectedModelIds.value.map(modelId => {
      const model = configStore.models.find(item => item.id === modelId)
      return {
        modelId,
        modelName: model?.name || modelId,
        label: `${model?.name || modelId} (${model?.provider || ''})`,
        answer: '',
        loading: true
      }
    })
    : selectedAgentTargets.value.map(target => ({
      ...target,
      target: { ...target },
      modelName: target.agentName,
      label: target.label,
      answer: '',
      loading: true
    }))

  const convIndex = conversationStore.conversations.length
  conversationStore.addConversation({ mode: chatMode.value, question, responses: targets })
  await nextTick()
  scrollConversationToBottom()

  if (chatMode.value === 'model') {
    try {
      const result = await conversationStore.sendToModels(question, targets.map(target => target.modelId))
      const responses = Array.isArray(result) ? result : (result?.responses || [])
      const currentResponses = conversationStore.conversations[convIndex]?.responses || []
      for (let index = 0; index < currentResponses.length; index += 1) {
        Object.assign(currentResponses[index], responses[index] || {}, { loading: false })
      }
    } catch (error) {
      const currentResponses = conversationStore.conversations[convIndex]?.responses || []
      currentResponses.forEach(response => {
        response.loading = false
        response.answer = `请求失败：${error.message || '未知错误'}`
      })
      message.error(error.message || '模型请求失败')
    }
  } else {
    await conversationStore.sendToAgentsStream(question, targets, convIndex)
  }
  await nextTick()
  scrollConversationToBottom()
}

async function handleRegenerate(convIndex, respIndex) {
  const conv = conversationStore.conversations[convIndex]
  const resp = conv?.responses?.[respIndex]
  if (!conv || !resp || resp.regenerating) return
  resp.regenerating = true
  let controller = null
  try {
    if (conv.mode === 'model') {
      const data = await conversationStore.regenerate(conv.question, resp.modelId, {
        dialogId: resp.dialogId,
        chatToken: resp.chatToken
      })
      resp.answer = data.answer || ''
      resp.responseTime = data.responseTime
      resp.dialogId = data.dialogId
      resp.chatToken = data.chatToken
    } else {
      controller = new AbortController()
      regenerateControllers.add(controller)
      conversationStore.loading = true
      const target = {
        ...(resp.target || resp),
        dialogId: resp.dialogId || resp.target?.dialogId,
        chatToken: resp.chatToken || resp.target?.chatToken
      }
      if (!target.authorization && target.envUrl && target.userEmail) {
        const auth = await conversationStore.ensureLogin(
          target.envUrl,
          target.userEmail,
          target.userPassword || target.userEmail
        )
        target.authorization = auth.authorization
        target.apiUsername = auth.apiUsername
      }
      resp.answer = ''
      resp.loading = true
      await conversationStore.streamAgentChat(target, conv.question, controller.signal, update => {
        if (update.answer !== undefined) resp.answer = update.answer
        if (update.dialogId) resp.dialogId = update.dialogId
        if (update.chatToken) resp.chatToken = update.chatToken
        if (update.imgMap) resp.imgMap = update.imgMap
        if (update.done) {
          resp.responseTime = update.responseTime
          resp.loading = false
        }
      })
      conversationStore.loading = false
    }
  } catch (error) {
    resp.loading = false
    if (error.name !== 'AbortError') {
      resp.answer = `Agent调用失败: ${error.message || '未知错误'}`
      message.error(error.message || '重新回答失败')
    }
  } finally {
    if (controller) regenerateControllers.delete(controller)
    if (conv.mode === 'agent') conversationStore.loading = false
    resp.loading = false
    resp.regenerating = false
    await nextTick()
    scrollConversationToBottom()
  }
}

function handleFileDownload(fileName, fileDownloadUrl, envUrl) {
  if (!fileDownloadUrl) {
    message.warning('文件下载地址不可用')
    return
  }
  let url = fileDownloadUrl
  if (!/^https?:\/\//i.test(url) && envUrl) {
    const normalizedEnv = /^https?:\/\//i.test(envUrl) ? envUrl : `http://${envUrl}`
    url = new URL(url, normalizedEnv).toString()
  }
  const link = document.createElement('a')
  link.href = url
  link.download = fileName || ''
  link.target = '_blank'
  link.rel = 'noopener noreferrer'
  document.body.appendChild(link)
  link.click()
  link.remove()
}

function scrollConversationToBottom() {
  const area = conversationAreaRef.value
  if (area) area.scrollTop = area.scrollHeight
}

watch(
  () => conversationStore.conversations.map(conv => conv.responses.map(response => `${response.answer || ''}:${response.loading}`).join('|')).join('||'),
  async () => {
    await nextTick()
    scrollConversationToBottom()
  }
)

onMounted(() => {
  if (!configStore.environments.length) configStore.fetchEnvironments()
  if (!configStore.models.length) configStore.fetchModels()
})
</script>

<style scoped>
.conversation-page {
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  height: calc(100vh - 112px);
  min-height: 0;
  overflow: hidden;
}

.page-content.conversation-page {
  min-height: 0;
}

.agent-select-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  flex-wrap: wrap;
}

.conversation-area {
  flex: 1;
  min-height: 180px;
  overflow-y: auto;
  padding: 8px 4px 16px;
  background: #f7f8fa;
  border-radius: 6px;
}

.conversation-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 180px;
  color: #999;
}

.conversation-round {
  margin-bottom: 20px;
}

.user-message {
  display: flex;
  justify-content: flex-end;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
}

.message-avatar {
  display: flex;
  flex: 0 0 32px;
  width: 32px;
  height: 32px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.user-avatar {
  color: #fff;
  background: #1677ff;
}

.message-content {
  max-width: 80%;
  padding: 10px 14px;
  border-radius: 8px;
  white-space: pre-wrap;
  word-break: break-word;
}

.user-content {
  color: #262626;
  background: #e6f4ff;
}

.response-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 360px), 1fr));
  gap: 12px;
  margin-left: 42px;
}

.response-card {
  min-width: 0;
  overflow: hidden;
  background: #fff;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
}

.card-header,
.card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #fafafa;
}

.card-header {
  min-height: 40px;
  border-bottom: 1px solid #f0f0f0;
}

.card-title {
  overflow: hidden;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-body {
  min-height: 64px;
  padding: 12px;
  overflow-wrap: anywhere;
}

.card-footer {
  justify-content: flex-end;
  border-top: 1px solid #f0f0f0;
}

.answer-text :deep(p) {
  margin-bottom: 8px;
}

.answer-text :deep(img) {
  max-width: 100%;
  max-height: 280px;
  object-fit: contain;
}

.tool-content {
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f5f5f5;
  padding: 8px;
  border-radius: 4px;
}

.streaming-cursor {
  color: #1677ff;
  animation: blink 1s step-end infinite;
}

.input-area {
  padding-top: 12px;
}

@keyframes blink {
  50% { opacity: 0; }
}
</style>
