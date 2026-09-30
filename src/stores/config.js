import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api, uploadFile } from '@/api/request.js'
import '@/api/config.js' // 注册 mock handlers

export const useConfigStore = defineStore('config', () => {
  const environments = ref([])
  const models = ref([])
  const criteria = ref({ similarityThreshold: 0.8, dimensions: [] })
  const loading = ref(false)
  const modelLoading = ref(false)

  const CREDENTIAL_MODE_PERSONAL = 'personal'
  const CREDENTIAL_MODE_SERVICE_ACCOUNT = 'service-account'

  // ========== 通用工具方法 ==========

  function normalizePlatform(platform) {
    return String(platform || 'semimind').trim().toLowerCase()
  }

  function normalizeCredentialMode(mode) {
    const value = String(mode || '').trim().toLowerCase().replace(/_/g, '-')

    if (
      value === 'service' ||
      value === 'service-account' ||
      value === 'serviceaccount' ||
      value === 'backend' ||
      value === 'backend-config' ||
      value === 'config'
    ) {
      return CREDENTIAL_MODE_SERVICE_ACCOUNT
    }

    if (
      value === 'personal' ||
      value === 'environment' ||
      value === 'env' ||
      value === 'manual'
    ) {
      return CREDENTIAL_MODE_PERSONAL
    }

    return ''
  }

  function resolveCredentialMode(env = {}, platform = '') {
    const explicitMode = normalizeCredentialMode(
      env.credentialMode ||
      env.credential_mode ||
      env.loginMode ||
      env.login_mode
    )

    if (explicitMode) {
      return explicitMode
    }

    if (normalizePlatform(platform || env.platform) !== 'semiclaw') {
      return CREDENTIAL_MODE_PERSONAL
    }

    const hasPersonalCredential = Boolean(
      String(env.tenantId || env.tenant_id || '').trim() &&
      String(env.username || '').trim() &&
      Boolean(env.password || env.passwordConfigured || env.hasPassword)
    )

    return hasPersonalCredential
      ? CREDENTIAL_MODE_PERSONAL
      : CREDENTIAL_MODE_SERVICE_ACCOUNT
  }

  function normalizeEnvironment(env = {}) {
    const platform = normalizePlatform(env.platform)
    const credentialMode = resolveCredentialMode(env, platform)

    return {
      ...env,
      platform,
      credentialMode,
      credential_mode: credentialMode,
      loginMode: credentialMode,
      login_mode: credentialMode,
      tenantId: env.tenantId || env.tenant_id || '',
      tenant_id: env.tenantId || env.tenant_id || '',
      username: env.username || '',
      password: env.password || '',
      passwordConfigured: Boolean(env.passwordConfigured || env.hasPassword || env.password),
      hasPassword: Boolean(env.hasPassword || env.passwordConfigured || env.password)
    }
  }

  function normalizeEnvironmentList(list) {
    if (!Array.isArray(list)) return []
    return list.map(env => normalizeEnvironment(env))
  }

  function buildEnvParams(envOrUrl, options = {}) {
    const { includeCredentials = false } = options

    if (typeof envOrUrl === 'string') {
      return {
        envUrl: envOrUrl,
        platform: 'semimind',
        tenantId: ''
      }
    }

    const platform = normalizePlatform(envOrUrl?.platform)
    const credentialMode = resolveCredentialMode(envOrUrl, platform)

    const params = {
      id: envOrUrl?.id,
      envId: envOrUrl?.envId || envOrUrl?.environmentId || envOrUrl?.id,
      environmentId: envOrUrl?.environmentId || envOrUrl?.envId || envOrUrl?.id,
      envUrl: envOrUrl?.envUrl || envOrUrl?.url || envOrUrl?.baseUrl,
      baseUrl: envOrUrl?.baseUrl || envOrUrl?.envUrl || envOrUrl?.url,
      url: envOrUrl?.url || envOrUrl?.envUrl || envOrUrl?.baseUrl,
      platform,
      credentialMode,
      credential_mode: credentialMode,
      loginMode: credentialMode,
      login_mode: credentialMode,
      tenantId: envOrUrl?.tenantId || envOrUrl?.tenant_id || '',
      tenant_id: envOrUrl?.tenantId || envOrUrl?.tenant_id || ''
    }

    if (includeCredentials) {
      params.username = envOrUrl?.username || ''
      params.password = envOrUrl?.password || ''
    }

    return params
  }

  function buildSemiclawParams(envOrUrl) {
    const params = buildEnvParams(envOrUrl, { includeCredentials: true })

    if (normalizePlatform(params.platform) !== 'semiclaw') {
      throw new Error('当前环境不是 SemiClaw 环境')
    }

    const baseUrl = params.baseUrl || params.envUrl || params.url
    if (!baseUrl) {
      throw new Error('SemiClaw 环境缺少 url')
    }

    const credentialMode = resolveCredentialMode(
      {
        ...envOrUrl,
        credentialMode: params.credentialMode,
        credential_mode: params.credential_mode,
        loginMode: params.loginMode,
        login_mode: params.login_mode,
        tenantId: params.tenantId,
        tenant_id: params.tenant_id,
        username: params.username,
        password: params.password
      },
      'semiclaw'
    )

    /**
     * 服务账号模式:
     * 不再要求 tenantId / username / password.
     * 后端会根据 baseUrl 从 application.properties 的 semiclaws.service-accounts.* 读取。
     */
    if (credentialMode === CREDENTIAL_MODE_SERVICE_ACCOUNT) {
      return {
        ...params,
        envUrl: baseUrl,
        baseUrl,
        url: baseUrl,
        tenantId: '',
        tenant_id: '',
        username: '',
        password: '',
        credentialMode,
        credential_mode: credentialMode,
        loginMode: credentialMode,
        login_mode: credentialMode
      }
    }

    /**
     * 个人账号模式:
     * 仍然校验 tenantId / username.
     * password 可以为空，因为编辑已保存环境时，后端会根据 envId 或 baseUrl 从数据库取旧密码。
     */
    if (!params.tenantId) {
      throw new Error('SemiClaw 环境缺少 tenantId')
    }

    if (!params.username) {
      throw new Error('SemiClaw 环境缺少 username')
    }

    return {
      ...params,
      envUrl: baseUrl,
      baseUrl,
      url: baseUrl,
      credentialMode,
      credential_mode: credentialMode,
      loginMode: credentialMode,
      login_mode: credentialMode
    }
  }

  function normalizePagedAgents(data) {
    if (Array.isArray(data)) {
      return {
        items: data,
        total: data.length
      }
    }

    if (Array.isArray(data?.items)) {
      return {
        items: data.items,
        total: data.total ?? data.items.length
      }
    }

    if (Array.isArray(data?.agents)) {
      return {
        items: data.agents,
        total: data.total ?? data.agents.length
      }
    }

    if (Array.isArray(data?.data)) {
      return {
        items: data.data,
        total: data.total ?? data.data.length
      }
    }

    if (Array.isArray(data?.data?.items)) {
      return {
        items: data.data.items,
        total: data.data.total ?? data.data.items.length
      }
    }

    if (Array.isArray(data?.data?.agents)) {
      return {
        items: data.data.agents,
        total: data.data.total ?? data.data.agents.length
      }
    }

    return {
      items: [],
      total: 0
    }
  }

  // ========== 环境管理 ==========

  async function fetchEnvironments() {
    loading.value = true
    try {
      const res = await api.get('/api/config/environments')
      environments.value = normalizeEnvironmentList(res.data)
    } finally {
      loading.value = false
    }
  }

  async function createEnvironment(data) {
    const payload = normalizeEnvironment(data)
    const res = await api.post('/api/config/environments', payload)
    const env = normalizeEnvironment(res.data || payload)

    environments.value.push(env)

    return env
  }

  async function updateEnvironment(id, data) {
    const payload = normalizeEnvironment(data)
    const res = await api.put(`/api/config/environments/${id}`, payload)
    const env = normalizeEnvironment(res.data || payload)

    const index = environments.value.findIndex(e => e.id === id)
    if (index !== -1) {
      environments.value[index] = env
    }

    return env
  }

  async function deleteEnvironment(id) {
    await api.delete(`/api/config/environments/${id}`)
    environments.value = environments.value.filter(e => e.id !== id)
  }

  // ========== 模型管理 ==========

  async function fetchModels() {
    modelLoading.value = true
    try {
      const res = await api.get('/api/config/models')
      models.value = res.data
    } finally {
      modelLoading.value = false
    }
  }

  async function createModel(data) {
    const res = await api.post('/api/config/models', data)
    models.value.push(res.data)
    return res.data
  }

  async function updateModel(id, data) {
    const res = await api.put(`/api/config/models/${id}`, data)
    const index = models.value.findIndex(m => m.id === id)
    if (index !== -1) models.value[index] = res.data
    return res.data
  }

  async function deleteModel(id) {
    await api.delete(`/api/config/models/${id}`)
    models.value = models.value.filter(m => m.id !== id)
  }

  async function testModelConnection(data) {
    return await api.post('/api/config/models/test', data)
  }

  // ========== 环境 Agent 列表：从远程数据库加载 ==========

  async function fetchAgentsByEnvUrl(envUrl) {
    const res = await api.get('/api/config/environments/agents', { envUrl })

    if (res.code !== 0) {
      throw new Error(res.message || '获取 Agent 列表失败')
    }

    return res.data // { bots: [...], canvases: [...] }
  }

  // ========== 分页查询 Agent 列表 ==========

  async function fetchAgentsPaged(envOrUrl, type = 'bot', offset = 0, limit = 20, keyword = '') {
    const platform = typeof envOrUrl === 'string'
      ? 'semimind'
      : normalizePlatform(envOrUrl?.platform)

    // SemiClaw 不走原来的 SemiMind 分页接口，避免把账号密码放到 GET query 里
    if (platform === 'semiclaw') {
      return await fetchSemiclawAgents(envOrUrl, offset, limit, keyword)
    }

    const params = {
      ...buildEnvParams(envOrUrl),
      type,
      offset,
      limit
    }

    if (keyword) params.keyword = keyword

    const res = await api.get('/api/config/environments/agents/paged', params)

    if (res.code !== 0) {
      throw new Error(res.message || '获取 Agent 列表失败')
    }

    return res.data // { items: [...], total: N }
  }

  // ========== SemiClaw Agent 列表 ==========

  async function fetchSemiclawAgents(envOrUrl, offset = 0, limit = 200, keyword = '') {
    const params = buildSemiclawParams(envOrUrl)

    const payload = {
      id: params.id,
      envId: params.envId,
      environmentId: params.environmentId,

      // 后端 SemiclawApiService 参数叫 baseUrl，前端环境字段叫 url/envUrl
      baseUrl: params.baseUrl,
      envUrl: params.envUrl,
      url: params.url,

      platform: 'semiclaw',

      credentialMode: params.credentialMode,
      credential_mode: params.credential_mode,
      loginMode: params.loginMode,
      login_mode: params.login_mode,

      tenantId: params.tenantId,
      tenant_id: params.tenant_id,
      username: params.username,
      password: params.password,

      offset,
      limit
    }

    if (keyword) payload.keyword = keyword

    const res = await api.post('/api/config/environments/semiclaw/agents', payload)

    if (res.code !== 0) {
      throw new Error(res.message || '获取 Semiclaw Agent 列表失败')
    }

    return normalizePagedAgents(res.data)
  }

  // ========== 查询环境用户列表 ==========

  async function fetchUsersByEnvUrl(envOrUrl, offset = 0, limit = 50, keyword = '') {
    const params = {
      ...buildEnvParams(envOrUrl),
      offset,
      limit
    }

    if (keyword) params.keyword = keyword

    const res = await api.get('/api/config/environments/users', params)

    if (res.code !== 0) {
      throw new Error(res.message || '获取用户列表失败')
    }

    return res.data // { users: [...], total: N }
  }

  // ========== 按用户查询 Agent ==========

  async function fetchAgentsByUser(envOrUrl, userId) {
    const params = buildEnvParams(envOrUrl)

    const res = await api.get(`/api/config/environments/users/${userId}/agents`, params)

    if (res.code !== 0) {
      throw new Error(res.message || '获取用户 Agent 列表失败')
    }

    return res.data // { bots: [...], canvases: [...] }
  }

  // ========== 评测账号 ==========

  async function fetchTestAccount(envOrUrl) {
    const params = buildEnvParams(envOrUrl)

    const res = await api.get('/api/config/environments/test-account', params)

    if (res.code !== 0) {
      return null
    }

    return res.data // { id, nickname, email, password }
  }

  // ========== 登录到环境 ==========

  async function loginToEnv(envUrl, email, password) {
    const res = await api.post('/api/config/environments/login', { envUrl, email, password })

    if (res.code !== 0) {
      throw new Error(res.message || '登录失败')
    }

    return res.data // { authorization, apiUsername }
  }

  // ========== 评判标准 ==========

  async function fetchCriteria() {
    const res = await api.get('/api/config/criteria')
    criteria.value = res.data
  }

  async function updateCriteria(data) {
    const res = await api.put('/api/config/criteria', data)
    criteria.value = res.data
  }

  // ========== SemiClaw 数字员工导入(需管理员账号环境) ==========

  async function fetchSemiclawModels(envId) {
    const res = await api.get(`/api/config/environments/${envId}/semiclaw/models`)

    if (res.code !== 0) {
      throw new Error(res.message || '获取 Semiclaw 模型列表失败')
    }

    return res.data // [{ id, model, label, ... }]
  }

  async function importSemiclawAgent(envId, file, options = {}) {
    const formData = new FormData()
    formData.append('file', file)

    const query = new URLSearchParams()
    if (options.primaryModelId) query.append('primaryModelId', options.primaryModelId)
    if (options.fallbackModelId) query.append('fallbackModelId', options.fallbackModelId)
    if (options.autoConfigPermission === false) query.append('autoConfigPermission', 'false')

    const queryString = query.toString()
    const url = `/api/config/environments/${envId}/semiclaw/agents/import${queryString ? '?' + queryString : ''}`

    const res = await uploadFile(url, formData)

    if (res.code !== 0) {
      throw new Error(res.message || '导入数字员工失败')
    }

    return res.data
  }

  async function updateSemiclawAgentModel(envId, agentId, options = {}) {
    const res = await api.post(`/api/config/environments/${envId}/semiclaw/agents/${agentId}/model`, {
      primaryModelId: options.primaryModelId,
      fallbackModelId: options.fallbackModelId
    })

    if (res.code !== 0) {
      throw new Error(res.message || '更新 Agent 模型配置失败')
    }

    return res.data
  }

  async function deleteSemiclawSkillFolders(envId, paths) {
    const res = await api.post(`/api/config/environments/${envId}/semiclaw/skills/delete`, { paths })

    if (res.code !== 0) {
      throw new Error(res.message || '删除技能文件夹失败')
    }

    return res.data // { deleted: [path, ...], failed: { path: reason, ... } }
  }

  return {
    environments,
    models,
    criteria,
    loading,
    modelLoading,

    fetchEnvironments,
    createEnvironment,
    updateEnvironment,
    deleteEnvironment,

    fetchAgentsByEnvUrl,
    fetchAgentsPaged,
    fetchSemiclawAgents,
    fetchUsersByEnvUrl,
    fetchAgentsByUser,
    fetchTestAccount,
    loginToEnv,

    fetchModels,
    createModel,
    updateModel,
    deleteModel,
    testModelConnection,

    fetchCriteria,
    updateCriteria,

    importSemiclawAgent,
    updateSemiclawAgentModel,
    fetchSemiclawModels,
    deleteSemiclawSkillFolders
  }
})
