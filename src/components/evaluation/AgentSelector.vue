<template>
  <div>
    <!-- 选择区域：每行独占，避免窄屏下 select 横向挤压 -->
    <div style="margin-bottom: 12px; display: flex; flex-direction: column; gap: 8px;">
      <!-- 第一行：选择环境 -->
      <div style="display: flex; align-items: center; gap: 8px;">
        <span style="white-space: nowrap;">选择环境：</span>
        <a-select
          v-model:value="selectedEnv"
          placeholder="选择环境"
          style="flex: 1;"
          show-search
          popup-class-name="wide-select-dropdown"
          :filter-option="filterEnvOption"
          @change="handleEnvChange"
        >
          <a-select-option v-for="env in environments" :key="env.id" :value="env.id">
            {{ env.name }}
            <a-tag style="margin-left: 4px;">{{ platformLabel(env.platform) }}</a-tag>
            <a-tag
              v-if="isSemiclawRecord(env)"
              :color="accountRoleTagColor(env)"
              style="margin-left: 4px;"
            >{{ accountRoleLabel(env) }}</a-tag>
          </a-select-option>
        </a-select>
      </div>

      <!-- 第二行：选择用户（独占一整行）。SemiClaw 环境不需要选用户，此时禁用并非隐藏该行，避免行数变化导致弹窗大小跳动 -->
      <div style="display: flex; align-items: center; gap: 8px;">
        <span style="white-space: nowrap;">选择用户：</span>
        <a-select
          v-model:value="selectedUser"
          :placeholder="isSemiclawEnv ? '该环境无需选择用户' : '搜索或选择用户'"
          style="flex: 1;"
          :disabled="isSemiclawEnv || !selectedEnv"
          show-search
          popup-class-name="wide-select-dropdown"
          :filter-option="false"
          @search="handleUserSearch"
          @change="handleUserChange"
          @popupScroll="handleUserPopupScroll"
          :loading="userLoading"
          allow-clear
        >
          <!-- 评测账号置顶 -->
          <a-select-option
            v-if="testAccount && testAccount.id"
            :value="testAccount.id"
            class="test-account-option"
          >
            <span style="font-weight: 500;">{{ testAccount.email }}</span>
            <a-tag color="green" style="margin-left: 4px; font-size: 11px;">推荐</a-tag>
          </a-select-option>

          <!-- 分隔线 -->
          <a-select-option
            v-if="testAccount && testAccount.id && displayedUsers.length > 0"
            disabled
            value="__divider__"
          >
            <a-divider style="margin: 4px 0;" />
          </a-select-option>

          <!-- 普通用户列表 -->
          <a-select-option v-for="user in displayedUsers" :key="user.id" :value="user.id">
            {{ user.email }}
          </a-select-option>

          <!-- 加载更多提示 -->
          <a-select-option v-if="userLoading" disabled value="__loading__">
            <a-spin size="small" /> 加载中...
          </a-select-option>
          <a-select-option v-else-if="userList.length < userTotal && userList.length > 0" disabled value="__more__">
            滑动加载更多...（{{ userList.length }}/{{ userTotal }}）
          </a-select-option>
        </a-select>
      </div>

      <!-- 第三行：选择 Agent（独占一整行） -->
      <div style="display: flex; align-items: center; gap: 8px;">
        <span style="white-space: nowrap;">选择Agent：</span>
        <a-select
          v-model:value="selectedAgents"
          mode="multiple"
          placeholder="选择Agent（可多选）"
          style="flex: 1;"
          :disabled="!selectedEnv || (!isSemiclawEnv && !selectedUser)"
          show-search
          :options="agentOptions"
          :filter-option="filterAgentOption"
          :max-tag-count="3"
          :max-tag-placeholder="omitted => `+${omitted.length}个...`"
          :loading="agentLoading"
          :virtual="true"
          :list-height="256"
          @change="autoAddSelection"
        />
      </div>
    </div>

    <!-- 评测账号推荐提示：仅 SemiMind 显示 -->
    <a-alert
      v-if="!isSemiclawEnv && testAccount && selectedEnv"
      type="info"
      show-icon
      style="margin-bottom: 8px;"
    >
      <template #message>
        由于会模拟用户登录操作获取 authentic，推荐使用评测账号
        <a-tag color="green">{{ testAccount.email }}</a-tag>
      </template>
    </a-alert>

    <!-- SemiClaw 服务账号提示 -->
    <a-alert
      v-if="isSemiclawEnv && currentEnv && isServiceAccountEnv(currentEnv)"
      type="info"
      show-icon
      style="margin-bottom: 8px;"
      message="当前 SemiClaw 环境使用后端服务账号"
      description="创建评测时不会向前端 target 写入 tenantId、username、password，后端会根据 envUrl 从 application.properties 的 semiclaws.service-accounts 配置中解析账号。"
    />

    <!-- 已选列表 -->
    <div v-if="modelValue.length" style="margin-top: 8px;">
      <a-tag
        v-for="(item, index) in modelValue"
        :key="item.id || `${item.envId}-${item.agentId}-${index}`"
        closable
        color="blue"
        style="margin-bottom: 4px;"
        @close="removeSelection(index)"
      >
        {{ item.label }}
      </a-tag>
    </div>
    <div v-else style="color: #999; font-size: 12px; margin-top: 4px;">
      请选择环境和 Agent；SemiMind 环境还需要选择用户
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { Modal, message } from 'ant-design-vue'
import { useConfigStore } from '@/stores/config.js'
import { api } from '@/api/request.js'

const props = defineProps({
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const configStore = useConfigStore()
const environments = computed(() => configStore.environments)

const CREDENTIAL_MODE_PERSONAL = 'personal'
const CREDENTIAL_MODE_SERVICE_ACCOUNT = 'service-account'

// 环境选择
const selectedEnv = ref(undefined)
const currentEnv = computed(() => environments.value.find(env => env.id === selectedEnv.value))
const currentPlatform = computed(() => normalizePlatform(currentEnv.value?.platform))
const isSemiclawEnv = computed(() => currentPlatform.value === 'semiclaw')

// 用户选择：SemiMind 使用
const selectedUser = ref(undefined)
const userList = ref([])
const userTotal = ref(0)
const userLoading = ref(false)
const userSearchKeyword = ref('')
const USER_PAGE_SIZE = 50

// 评测账号：SemiMind 使用
const testAccount = ref(null)

// Agent 选择
const selectedAgents = ref([])
const userAgentList = ref([])
const agentLoading = ref(false)

const displayedUsers = computed(() => {
  if (!testAccount.value?.id) return userList.value
  return userList.value.filter(user => user.id !== testAccount.value.id)
})

// Agent 选项：排除已添加的 Agent
const agentOptions = computed(() => {
  return userAgentList.value
    .filter(agent => {
      const env = currentEnv.value
      if (!env || !agent.id) return false
      const id = isSemiclawEnv.value
        ? `${env.id}-${agent.id}`
        : `${env.id}-${selectedUser.value}-${agent.id}`
      return !props.modelValue.some(item => item.id === id)
    })
    .map(agent => ({ label: agent.name, value: agent.id }))
})

onMounted(async () => {
  if (!configStore.environments.length) {
    try {
      await configStore.fetchEnvironments()
    } catch (err) {
      message.error('加载环境列表失败：' + (err.message || ''))
    }
  }
})

onBeforeUnmount(() => {
  if (userSearchTimer) clearTimeout(userSearchTimer)
})

watch(() => props.modelValue, (value) => {
  // 父组件清空选择时同步清除当前临时选择。
  if (!value?.length) selectedAgents.value = []
}, { deep: true })

// ========== 工具方法 ==========
function normalizePlatform(platform) {
  return String(platform || 'semimind').trim().toLowerCase()
}

function normalizeCredentialMode(mode) {
  const value = String(mode || '').trim().toLowerCase().replace(/_/g, '-')
  if (['service', 'service-account', 'serviceaccount', 'backend', 'backend-config', 'config'].includes(value)) {
    return CREDENTIAL_MODE_SERVICE_ACCOUNT
  }
  if (['personal', 'environment', 'env', 'manual'].includes(value)) return CREDENTIAL_MODE_PERSONAL
  return ''
}

function platformLabel(platform) {
  return normalizePlatform(platform) === 'semiclaw' ? 'SemiClaw' : 'SemiMind'
}

function isSemiclawRecord(env) {
  return normalizePlatform(env?.platform) === 'semiclaw'
}

function getTenantId(env) {
  return env?.tenantId || env?.tenant_id || ''
}

function getCredentialMode(env) {
  const explicitMode = normalizeCredentialMode(
    env?.credentialMode || env?.credential_mode || env?.loginMode || env?.login_mode
  )
  if (explicitMode) return explicitMode
  if (!isSemiclawRecord(env)) return CREDENTIAL_MODE_PERSONAL
  const hasPersonalCredential = Boolean(
    String(getTenantId(env) || '').trim() &&
    String(env?.username || '').trim() &&
    Boolean(env?.password || env?.passwordConfigured || env?.hasPassword)
  )
  return hasPersonalCredential ? CREDENTIAL_MODE_PERSONAL : CREDENTIAL_MODE_SERVICE_ACCOUNT
}

function isServiceAccountEnv(env) {
  return isSemiclawRecord(env) && getCredentialMode(env) === CREDENTIAL_MODE_SERVICE_ACCOUNT
}

function isPersonalCredentialEnv(env) {
  return isSemiclawRecord(env) && getCredentialMode(env) === CREDENTIAL_MODE_PERSONAL
}

// 账号角色标签与配置中心保持一致；服务账号/个人账号是登录凭证来源，不与管理员/普通账号混用。
function normalizeAccountRole(role) {
  const value = String(role || '').trim().toLowerCase()
  return value === 'admin' || value === 'normal' ? value : ''
}

function accountRoleLabel(env) {
  const role = normalizeAccountRole(env?.accountRole || env?.account_role)
  if (role === 'admin') return '管理员账号'
  if (role === 'normal') return '普通账号'
  return '未设置'
}

function accountRoleTagColor(env) {
  const role = normalizeAccountRole(env?.accountRole || env?.account_role)
  if (role === 'admin') return 'red'
  if (role === 'normal') return 'green'
  return 'default'
}

function getUserDisplayName(user) {
  return user?.nickname || user?.email || user?.id || ''
}

function filterEnvOption(input, option) {
  const env = environments.value.find(item => item.id === option.value)
  return env?.name?.toLowerCase().includes(String(input || '').toLowerCase())
}

function filterAgentOption(input, option) {
  return String(option.label || '').toLowerCase().includes(String(input || '').toLowerCase())
}

function normalizeDialogId(agent) {
  return agent?.dialogId || agent?.dialog_id || agent?.semimindDialogId ||
    agent?.semimind_dialog_id || agent?.conversationId || agent?.conversation_dialog_id || ''
}

function normalizeAgent(agent) {
  const id = agent?.id || agent?.agentId || agent?.agent_id
  const name = agent?.name || agent?.agentName || agent?.agent_name || agent?.title || id
  const dialogId = normalizeDialogId(agent)
  return {
    ...agent,
    id,
    name,
    dialogId,
    dialog_id: dialogId,
    semimindDialogId: dialogId,
    semimind_dialog_id: dialogId,
    creator: agent?.creator || agent?.createdBy || agent?.created_by || '',
    type: agent?.type || agent?.agentType || agent?.agent_type || 'agent',
    agentType: agent?.agentType || agent?.agent_type || agent?.type || 'agent',
    roleDescription: agent?.roleDescription || agent?.role_description || agent?.description || '',
    status: agent?.status,
    isExpired: agent?.isExpired ?? agent?.is_expired
  }
}

function extractAgentList(res) {
  if (Array.isArray(res)) return res
  if (Array.isArray(res?.data)) return res.data
  if (Array.isArray(res?.items)) return res.items
  if (Array.isArray(res?.agents)) return res.agents
  if (Array.isArray(res?.bots)) return res.bots
  if (Array.isArray(res?.canvases)) return res.canvases
  if (Array.isArray(res?.data?.items)) return res.data.items
  if (Array.isArray(res?.data?.agents)) return res.data.agents
  if (Array.isArray(res?.data?.bots)) return res.data.bots
  if (Array.isArray(res?.data?.canvases)) return res.data.canvases
  return []
}

function getSemiclawAgentRequestBody(env) {
  const credentialMode = getCredentialMode(env)
  const basePayload = {
    envId: env?.id,
    environmentId: env?.id,
    id: env?.id,
    baseUrl: env?.url,
    envUrl: env?.url,
    url: env?.url,
    platform: 'semiclaw',
    credentialMode,
    credential_mode: credentialMode,
    loginMode: credentialMode,
    login_mode: credentialMode,
    offset: 0,
    limit: 200,
    keyword: ''
  }

  if (credentialMode === CREDENTIAL_MODE_SERVICE_ACCOUNT) {
    return {
      ...basePayload,
      tenantId: '',
      tenant_id: '',
      username: '',
      password: '',
      pwd: ''
    }
  }

  return {
    ...basePayload,
    tenantId: getTenantId(env),
    tenant_id: getTenantId(env),
    username: env?.username || '',
    password: env?.password || '',
    pwd: env?.password || ''
  }
}

// ========== 环境变化 ==========
async function handleEnvChange() {
  resetSelectionState()
  const env = currentEnv.value
  if (!env?.url) return

  if (isSemiclawEnv.value) {
    await loadSemiclawAgents(env)
    return
  }

  await Promise.all([
    loadUsers(env.url, true),
    loadTestAccount(env.url)
  ])
}

function resetSelectionState() {
  selectedUser.value = undefined
  selectedAgents.value = []
  userList.value = []
  userTotal.value = 0
  userAgentList.value = []
  testAccount.value = null
  userSearchKeyword.value = ''
}

// ========== SemiMind 用户加载 ==========
async function loadUsers(envUrl, reset = true) {
  if (reset) {
    userList.value = []
    userTotal.value = 0
  }
  userLoading.value = true
  try {
    const offset = reset ? 0 : userList.value.length
    const res = await configStore.fetchUsersByEnvUrl(envUrl, offset, USER_PAGE_SIZE, userSearchKeyword.value)
    if (reset) userList.value = res.users || []
    else userList.value = [...userList.value, ...(res.users || [])]
    userTotal.value = res.total || 0
  } catch (err) {
    message.error('获取用户列表失败：' + (err.message || '请检查环境连接'))
  } finally {
    userLoading.value = false
  }
}

async function loadTestAccount(envUrl) {
  try {
    testAccount.value = await configStore.fetchTestAccount(envUrl)
  } catch {
    testAccount.value = null
  }
}

// 用户搜索：SemiMind 使用
let userSearchTimer = null
function handleUserSearch(value) {
  if (isSemiclawEnv.value) return
  userSearchKeyword.value = value
  if (userSearchTimer) clearTimeout(userSearchTimer)
  userSearchTimer = setTimeout(() => {
    const env = currentEnv.value
    if (env?.url) loadUsers(env.url, true)
  }, 300)
}

// 用户下拉滚动到底部自动加载下一页：SemiMind 使用
function handleUserPopupScroll(event) {
  if (isSemiclawEnv.value) return
  const { scrollTop, scrollHeight, clientHeight } = event.target
  if (scrollTop + clientHeight >= scrollHeight - 20 && userList.value.length < userTotal.value && !userLoading.value) {
    const env = currentEnv.value
    if (env?.url) loadUsers(env.url, false)
  }
}

// 用户变更：SemiMind 使用
async function handleUserChange(userId) {
  if (isSemiclawEnv.value) return
  selectedAgents.value = []
  userAgentList.value = []

  if (!userId || userId === '__divider__' || userId === '__loading__' || userId === '__more__') {
    selectedUser.value = undefined
    return
  }

  const isTestAccount = testAccount.value && testAccount.value.id === userId
  if (!isTestAccount) {
    const confirmed = await showNonTestAccountWarning(userId)
    if (!confirmed) {
      selectedUser.value = undefined
      return
    }
  }

  const env = currentEnv.value
  if (!env?.url) return
  agentLoading.value = true
  try {
    const res = await configStore.fetchAgentsByUser(env.url, userId)
    userAgentList.value = [
      ...(res.bots || []).map(agent => normalizeAgent({ ...agent, type: 'bot' })),
      ...(res.canvases || []).map(agent => normalizeAgent({ ...agent, type: 'canvas' }))
    ].filter(agent => agent.id)
    if (!userAgentList.value.length) message.info('该用户暂时无 Agent')
  } catch (err) {
    message.error('加载 Agent 列表失败：' + (err.message || ''))
  } finally {
    agentLoading.value = false
  }
}

function showNonTestAccountWarning(userId) {
  const user = userList.value.find(item => item.id === userId)
  const displayName = getUserDisplayName(user) || userId
  return new Promise(resolve => {
    Modal.confirm({
      title: '评测账号警告',
      content: `确定要使用非评测账号“${displayName}”下的 Agent 进行评测吗？该操作会使得 SemiMind 平台已登录账号登出，请谨慎！`,
      okText: '确认使用',
      cancelText: '取消',
      okType: 'danger',
      onOk: () => resolve(true),
      onCancel: () => resolve(false)
    })
  })
}

// ========== SemiClaw Agent 加载 ==========
async function loadSemiclawAgents(env) {
  if (!env?.id || !env?.url) {
    message.warning('当前 SemiClaw 环境缺少环境标识，请先到环境管理里检查配置')
    return
  }
  agentLoading.value = true
  try {
    const res = await fetchSemiclawAgentsFromStore(env)
    const rawList = extractAgentList(res)
    userAgentList.value = rawList
      .map(agent => normalizeAgent({
        ...agent,
        type: agent.agent_type || agent.agentType || agent.type || 'agent',
        agentType: agent.agent_type || agent.agentType || agent.type || 'agent'
      }))
      .filter(agent => agent.id)
    if (!userAgentList.value.length) message.info('当前 SemiClaw 环境暂无 Agent')
  } catch (err) {
    message.error('加载 SemiClaw Agent 列表失败：' + (err.message || '请检查环境连接'))
  } finally {
    agentLoading.value = false
  }
}

async function fetchSemiclawAgentsFromStore(env) {
  const payload = getSemiclawAgentRequestBody(env)
  const res = await api.post('/api/config/environments/semiclaw/agents', payload)
  if (res && res.code !== undefined && res.code !== 0) {
    throw new Error(res.message || 'SemiClaw Agent 列表接口返回失败')
  }
  return res
}

// ========== 添加 / 删除选择 ==========
// 选择 Agent 后自动添加
function autoAddSelection() {
  if (!selectedAgents.value.length || !selectedEnv.value) return
  if (isSemiclawEnv.value || selectedUser.value) addSelection()
}

function addSelection() {
  const env = currentEnv.value
  if (!env) return

  const platform = normalizePlatform(env.platform)
  const credentialMode = platform === 'semiclaw'
    ? getCredentialMode(env)
    : CREDENTIAL_MODE_PERSONAL
  const tenantId = platform === 'semiclaw' && credentialMode === CREDENTIAL_MODE_PERSONAL
    ? getTenantId(env)
    : ''
  let user = null

  if (platform !== 'semiclaw') {
    user = userList.value.find(item => item.id === selectedUser.value)
    if (!user && testAccount.value?.id === selectedUser.value) user = testAccount.value
    if (!user) return
  }

  const newItems = []
  for (const agentId of selectedAgents.value) {
    const agent = userAgentList.value.find(item => item.id === agentId)
    if (!agent) continue

    const agentDialogId = normalizeDialogId(agent)
    const id = platform === 'semiclaw'
      ? `${env.id}-${agent.id}`
      : `${env.id}-${user.id}-${agent.id}`
    if (props.modelValue.some(item => item.id === id)) continue

    const item = {
      id,
      platform,
      targetPlatform: platform,
      credentialMode,
      credential_mode: credentialMode,
      loginMode: credentialMode,
      login_mode: credentialMode,

      // 环境信息
      envId: env.id,
      envName: env.name,
      envUrl: env.url,
      baseUrl: env.url,
      url: env.url,

      // Agent 信息
      agentId: agent.id,
      agent_id: agent.id,
      agentName: agent.name,
      agent_name: agent.name,
      agentType: agent.agentType || agent.type || 'agent',

      // SemiMind 多态字段使用统一 dialog_id，并同时提供兼容命名
      dialogId: agentDialogId,
      dialog_id: agentDialogId,
      semimindDialogId: agentDialogId,
      semimind_dialog_id: agentDialogId,
      name: agent.name,
      type: agent.agentType || agent.type || 'agent'
    }

    if (platform === 'semiclaw') {
      item.tenantId = tenantId
      item.tenant_id = tenantId
      if (credentialMode === CREDENTIAL_MODE_PERSONAL) {
        item.username = env.username || ''
        // 编辑已保存环境时密码可能不会返回，由后端通过 envId/baseUrl 获取。
        if (env.password) item.password = env.password
      }
      item.label = `${env.name} - ${agent.name}`
    } else {
      const userDisplayName = getUserDisplayName(user)
      item.userId = user.id
      item.userName = userDisplayName
      item.userEmail = user.email
      item.userPassword = testAccount.value?.id === user.id ? testAccount.value.password : undefined
      item.label = `${env.name} - ${userDisplayName} - ${agent.name}`
    }

    newItems.push(item)
  }

  if (newItems.length) emit('update:modelValue', [...props.modelValue, ...newItems])
  selectedAgents.value = []
}

function removeSelection(index) {
  const newList = [...props.modelValue]
  newList.splice(index, 1)
  emit('update:modelValue', newList)
}
</script>

<style>
/* 下拉选项内容不再省略截断：popup-match-select-width=false 只解决弹窗整体宽度受输入框限制的问题，ant-design-vue 默认还会给每个选项加单行省略样式，这里针对性放开，允许按内容自然宽度展示完整文字。非 scoped：下拉列表以 teleport 挂载到 body 之外，scoped 样式选择器无法命中。 */
.wide-select-dropdown .ant-select-item-option-content {
  white-space: nowrap;
  overflow: visible;
  text-overflow: unset;
}
</style>
