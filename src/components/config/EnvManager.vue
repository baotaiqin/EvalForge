<template>
  <div>
    <div style="margin-bottom: 16px; display: flex; justify-content: flex-end;">
      <a-space>
        <a-input-search
          v-model:value="envSearchKeyword"
          placeholder="搜索环境名称/URL"
          allow-clear
          style="width: 220px;"
        />
        <a-select
          v-model:value="envPlatformFilter"
          allow-clear
          placeholder="全部平台"
          style="width: 140px;"
        >
          <a-select-option value="semimind">SemiMind</a-select-option>
          <a-select-option value="semiclaw">SemiClaw</a-select-option>
        </a-select>
        <a-button type="primary" @click="openModal()">
          <template #icon><PlusOutlined /></template>
          新增环境
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="filteredEnvironments"
      :loading="configStore.loading"
      row-key="id"
      :pagination="{ pageSize: 10 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'platform'">
          <a-tag :color="isSemiclawRecord(record) ? 'purple' : 'blue'">
            {{ isSemiclawRecord(record) ? 'SemiClaw' : 'SemiMind' }}
          </a-tag>
        </template>
        <template v-else-if="column.key === 'tenantId'">
          <span v-if="isSemiclawRecord(record) && isServiceAccountRecord(record)">后端服务账号</span>
          <span v-else>{{ record.tenantId || record.tenant_id || '--' }}</span>
        </template>
        <template v-else-if="column.key === 'accountRole'">
          <a-tag v-if="isSemiclawRecord(record)" :color="accountRoleTagColor(record)">
            {{ accountRoleLabel(record) }}
          </a-tag>
          <span v-else>--</span>
        </template>
        <template v-else-if="column.key === 'agents'">
          <a-tag color="blue">{{ formatAgentCount(record) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a
              v-if="isSemiclawRecord(record) && isAdminAccountRecord(record)"
              @click="openImportModal(record)"
            >导入数字员工</a>
            <a @click="openModal(record)">编辑</a>
            <a-popconfirm title="确定删除该环境吗？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 新增/编辑环境 -->
    <a-modal
      v-model:open="modalVisible"
      :title="editingEnv ? '编辑环境' : '新增环境'"
      :confirm-loading="submitting"
      width="700px"
      @ok="handleSubmit"
    >
      <a-form
        ref="formRef"
        :model="formState"
        :rules="rules"
        layout="vertical"
        style="margin-top: 16px;"
      >
        <a-form-item label="环境名称" name="name">
          <a-input v-model:value="formState.name" placeholder="请输入环境名称" />
        </a-form-item>

        <a-form-item label="环境URL" name="url">
          <a-select
            v-model:value="formState.url"
            placeholder="请选择环境URL"
            @change="handleUrlChange"
          >
            <a-select-option v-for="env in ENV_URL_OPTIONS" :key="env.url" :value="env.url">
              {{ env.label }}（{{ env.url }}）
            </a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item label="平台" name="platform">
          <a-select
            v-model:value="formState.platform"
            placeholder="请选择平台"
            @change="handlePlatformChange"
          >
            <a-select-option value="semimind">SemiMind</a-select-option>
            <a-select-option value="semiclaw">SemiClaw</a-select-option>
          </a-select>
        </a-form-item>

        <template v-if="isSemiclaw">
          <a-form-item
            label="登录方式"
            name="credentialMode"
            extra="后端服务账号从 application.properties 的 semiclaws.service-accounts 配置中自动获取账号。"
          >
            <a-select
              v-model:value="formState.credentialMode"
              placeholder="请选择 SemiClaw 登录方式"
              @change="handleCredentialModeChange"
            >
              <a-select-option value="service-account">后端服务账号</a-select-option>
              <a-select-option value="personal">个人账号</a-select-option>
            </a-select>
          </a-form-item>

          <a-alert
            v-if="isServiceAccountMode"
            type="info"
            show-icon
            style="margin-bottom: 16px;"
            message="当前使用后端服务账号"
            description="无需填写 Tenant ID、登录账号和登录密码。后端会根据环境 URL 从 application.properties 的 semiclaws.service-accounts 配置中自动获取账号。"
          />

          <template v-if="isPersonalCredentialMode">
            <a-form-item label="Tenant ID" name="tenantId">
              <a-input v-model:value="formState.tenantId" placeholder="请输入 SemiClaw tenantId" />
            </a-form-item>
            <a-form-item label="登录账号" name="username">
              <a-input v-model:value="formState.username" placeholder="请输入 SemiClaw 登录账号" />
            </a-form-item>
            <a-form-item
              label="登录密码"
              name="password"
              :extra="editingEnv ? '编辑时不填写密码，则沿用旧密码；如旧密码不可用，请重新填写。' : ''"
            >
              <a-input-password v-model:value="formState.password" placeholder="请输入 SemiClaw 登录密码" />
            </a-form-item>
          </template>

          <a-form-item
            label="WS 网关 Cookie"
            name="wsCookie"
            :extra="wsCookieExtraText"
          >
            <a-textarea
              v-model:value="formState.wsCookie"
              placeholder="临时方案：SemiClaw WS 网关握手除 ticket 外还依赖 SemiMind 会话 cookie，请从浏览器登录 SemiMind 后复制 Cookie 粘贴到此处，过期后请重新粘贴更新"
              :rows="3"
            />
          </a-form-item>
        </template>

        <template v-if="isSemiclaw">
          <a-form-item
            label="账号角色"
            name="accountRole"
            extra="纯标记字段，用于区分该环境登录账号是管理员还是普通账号，不影响实际登录逻辑。"
          >
            <a-select v-model:value="formState.accountRole" placeholder="未设置" allow-clear>
              <a-select-option value="normal">普通账号</a-select-option>
              <a-select-option value="admin">管理员账号</a-select-option>
            </a-select>
          </a-form-item>
        </template>

        <a-form-item label="描述" name="description">
          <a-textarea v-model:value="formState.description" placeholder="请输入环境描述" :rows="3" />
        </a-form-item>

        <!-- Agent 列表 - 从远程数据库分页读取 -->
        <a-form-item label="Agent列表">
          <div v-if="!formState.url" style="color: #999; padding: 8px 0;">
            请先选择环境URL，系统将自动从对应数据库加载Agent列表
          </div>
          <div v-else-if="isSemiclaw && isServiceAccountMode" style="color: #999; padding: 8px 0;">
            使用后端服务账号加载 SemiClaw Agent 列表
          </div>
          <div v-else-if="isSemiclaw && isPersonalCredentialMode && !formState.tenantId" style="color: #999; padding: 8px 0;">
            请先输入 SemiClaw Tenant ID，再加载Agent列表
          </div>
          <div v-else-if="isSemiclaw && isPersonalCredentialMode && !formState.username" style="color: #999; padding: 8px 0;">
            请先输入 SemiClaw 登录账号，再加载Agent列表
          </div>
          <div v-else-if="isSemiclaw && isPersonalCredentialMode && !canUsePasswordForPersonalMode" style="color: #999; padding: 8px 0;">
            请先输入 SemiClaw 登录密码，再加载Agent列表
          </div>

          <div v-if="canLoadAgents">
            <a-input-search
              v-model:value="agentSearch"
              :placeholder="isSemiclaw ? '搜索Agent名称' : '搜索Agent名称或创建者'"
              style="margin-bottom: 12px;"
              allow-clear
              @search="handleSearch"
              @pressEnter="handleSearch"
            />

            <!-- SemiClaw：只有 Agent，不区分智能体/工作流 -->
            <div v-if="isSemiclaw">
              <div ref="botListRef" class="agent-scroll-list" @scroll="handleBotScroll">
                <a-spin v-if="botLoading && botList.length === 0" style="display: block; text-align: center; padding: 20px;" />
                <div v-else-if="botList.length === 0" style="color: #999; text-align: center; padding: 16px;">
                  {{ agentSearch ? '没有匹配的Agent' : '暂无Agent数据' }}
                </div>
                <template v-else>
                  <div v-for="agent in botList" :key="agent.id" class="agent-item">
                    <div>
                      <div class="agent-name">{{ agent.name }}</div>
                      <div class="agent-creator">{{ agent.roleDescription || agent.agentType || agent.type || 'agent' }}</div>
                    </div>
                    <a-tag color="default">{{ agent.agentType || agent.type || 'agent' }}</a-tag>
                  </div>
                  <div v-if="botList.length < botTotal" style="text-align: center; padding: 8px; color: #999; font-size: 12px;">
                    <a-spin v-if="botLoading" size="small" />
                    <span v-else>滑动加载更多...（{{ botList.length }}/{{ botTotal }}）</span>
                  </div>
                  <div v-else style="text-align: center; padding: 8px; color: #ccc; font-size: 12px;">共 {{ botTotal }} 条</div>
                </template>
              </div>
            </div>

            <!-- SemiMind：保留智能体/工作流 Tab -->
            <a-tabs v-else v-model:activeKey="agentTab" size="small" @change="handleTabChange">
              <a-tab-pane key="bot" :tab="`智能体（${botTotal}）`">
                <div ref="botListRef" class="agent-scroll-list" @scroll="handleBotScroll">
                  <a-spin v-if="botLoading && botList.length === 0" style="display: block; text-align: center; padding: 20px;" />
                  <div v-else-if="botList.length === 0" style="color: #999; text-align: center; padding: 16px;">
                    {{ agentSearch ? '没有匹配的智能体' : '暂无智能体数据' }}
                  </div>
                  <template v-else>
                    <div v-for="bot in botList" :key="bot.id" class="agent-item">
                      <div>
                        <div class="agent-name">{{ bot.name }}</div>
                        <div class="agent-creator">创建者：{{ bot.creator }}</div>
                      </div>
                    </div>
                    <div v-if="botList.length < botTotal" style="text-align: center; padding: 8px; color: #999; font-size: 12px;">
                      <a-spin v-if="botLoading" size="small" />
                      <span v-else>滑动加载更多...（{{ botList.length }}/{{ botTotal }}）</span>
                    </div>
                    <div v-else style="text-align: center; padding: 8px; color: #ccc; font-size: 12px;">共 {{ botTotal }} 条</div>
                  </template>
                </div>
              </a-tab-pane>
              <a-tab-pane key="canvas" :tab="`工作流（${canvasTotal}）`">
                <div ref="canvasListRef" class="agent-scroll-list" @scroll="handleCanvasScroll">
                  <a-spin v-if="canvasLoading && canvasList.length === 0" style="display: block; text-align: center; padding: 20px;" />
                  <div v-else-if="canvasList.length === 0" style="color: #999; text-align: center; padding: 16px;">
                    {{ agentSearch ? '没有匹配的工作流' : '暂无工作流数据' }}
                  </div>
                  <template v-else>
                    <div v-for="canvas in canvasList" :key="canvas.id" class="agent-item">
                      <div>
                        <div class="agent-name">{{ canvas.name }}</div>
                        <div class="agent-creator">创建者：{{ canvas.creator }}</div>
                      </div>
                    </div>
                    <div v-if="canvasList.length < canvasTotal" style="text-align: center; padding: 8px; color: #999; font-size: 12px;">
                      <a-spin v-if="canvasLoading" size="small" />
                      <span v-else>滑动加载更多...（{{ canvasList.length }}/{{ canvasTotal }}）</span>
                    </div>
                    <div v-else style="text-align: center; padding: 8px; color: #ccc; font-size: 12px;">共 {{ canvasTotal }} 条</div>
                  </template>
                </div>
              </a-tab-pane>
            </a-tabs>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 导入数字员工弹窗 -->
    <a-modal
      v-model:open="importModalVisible"
      title="导入数字员工"
      width="640px"
      :footer="null"
      @cancel="resetImportModal"
    >
      <a-alert
        type="warning"
        show-icon
        style="margin-bottom: 16px;"
        message="使用管理员账号登录 SemiClaw 导入数字员工压缩包"
        :description="`目标环境：${importTargetEnv?.name || ''}（${importTargetEnv?.url || ''}）`"
      />
      <a-form layout="vertical">
        <a-form-item label="数字员工压缩包（.tar.gz）" required>
          <a-upload
            :file-list="importFileList"
            :before-upload="handleImportFileSelect"
            :remove="handleImportFileRemove"
            :max-count="1"
            accept=".tar.gz,.tgz"
          >
            <a-button :disabled="!!importResult">
              <template #icon><UploadOutlined /></template>
              选择文件
            </a-button>
          </a-upload>
        </a-form-item>
        <a-form-item
          label="主模型（可选）"
          extra="留空则导入后沿用系统默认模型；下拉列表来自该环境租户的模型池"
        >
          <a-select
            v-model:value="importForm.primaryModelId"
            :options="modelOptions"
            :loading="modelOptionsLoading"
            show-search
            allow-clear
            option-filter-prop="label"
            placeholder="选择主模型"
          />
        </a-form-item>
        <a-form-item label="备选模型（可选）">
          <a-select
            v-model:value="importForm.fallbackModelId"
            :options="modelOptions"
            :loading="modelOptionsLoading"
            show-search
            allow-clear
            option-filter-prop="label"
            placeholder="选择备选模型"
          />
        </a-form-item>
        <a-form-item>
          <a-checkbox v-model:checked="importForm.autoConfigPermission">
            导入后自动设置为团队归属 + 全员可访问
          </a-checkbox>
        </a-form-item>
      </a-form>

      <a-button
        v-if="!importResult"
        type="primary"
        block
        :loading="importSubmitting"
        :disabled="importFileList.length === 0"
        @click="handleImportSubmit"
      >开始导入</a-button>

      <template v-if="importResult">
        <a-divider />
        <a-alert
          :type="importResult.needsModelFix ? 'warning' : 'success'"
          show-icon
          :message="importResult.agent_name"
          :description="importResult.readiness"
          style="margin-bottom: 16px;"
        />
        <a-descriptions :column="1" size="small" bordered style="margin-bottom: 16px;">
          <a-descriptions-item label="Agent ID">{{ importResult.agent_id }}</a-descriptions-item>
          <a-descriptions-item label="当前主模型ID">{{ importResult.agentDetail?.primary_model_id || '-' }}</a-descriptions-item>
          <a-descriptions-item label="当前备选模型ID">{{ importResult.agentDetail?.fallback_model_id || '-' }}</a-descriptions-item>
        </a-descriptions>
        <a-alert
          v-if="importResult.permissionConfigured === true"
          type="success"
          show-icon
          message="已自动设置为团队归属 + 全员可访问"
          style="margin-bottom: 12px;"
        />
        <a-alert
          v-if="importResult.configWarning"
          type="warning"
          show-icon
          :message="importResult.configWarning"
          style="margin-bottom: 12px;"
        />
        <div v-if="importResult.warnings?.length" style="margin-bottom: 12px;">
          <a-alert v-for="(warning, idx) in importResult.warnings" :key="idx" type="warning" :message="warning" style="margin-bottom: 8px;" />
        </div>
        <div v-if="importResult.duplicateSkillFolders?.length" style="margin-bottom: 12px;">
          <a-alert type="warning" show-icon message="检测到本次导入因跨租户冲突产生的重复技能文件夹，请勾选后手动删除">
            <template #description>
              <a-checkbox-group v-model:value="selectedDuplicatePaths" style="display: flex; flex-direction: column;">
                <a-checkbox v-for="folder in importResult.duplicateSkillFolders" :key="folder.path" :value="folder.path">
                  {{ folder.name }}（{{ folder.path }}）
                </a-checkbox>
              </a-checkbox-group>
            </template>
          </a-alert>
          <a-popconfirm
            :title="`确定删除选中的 ${selectedDuplicatePaths.length} 个重复技能文件夹吗？`"
            @confirm="handleDeleteDuplicateSkills"
          >
            <a-button
              danger
              style="margin-top: 8px;"
              :loading="duplicateDeleteSubmitting"
              :disabled="selectedDuplicatePaths.length === 0"
            >删除选中的重复技能</a-button>
          </a-popconfirm>
        </div>
        <a-alert
          v-if="importResult.cleanedSkillFolders?.length"
          type="success"
          show-icon
          :message="`已删除 ${importResult.cleanedSkillFolders.length} 个重复技能文件夹：${importResult.cleanedSkillFolders.join('，')}`"
          style="margin-bottom: 12px;"
        />
        <a-button
          v-if="importResult.needsModelFix"
          type="primary"
          :loading="modelUpdateSubmitting"
          :disabled="!importForm.primaryModelId && !importForm.fallbackModelId"
          @click="handleUpdateModelAfterImport"
        >应用模型配置</a-button>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, UploadOutlined } from '@ant-design/icons-vue'
import { useConfigStore } from '@/stores/config.js'

const configStore = useConfigStore()
const PAGE_SIZE = 20
const CREDENTIAL_MODE_PERSONAL = 'personal'
const CREDENTIAL_MODE_SERVICE_ACCOUNT = 'service-account'

// 固定的环境 URL 选项
const ENV_URL_OPTIONS = [
  { label: 'Dev', url: 'demo-dev.example.invalid:6111', platform: 'semimind' },
  { label: 'Sit', url: 'demo-sit.example.invalid:7111', platform: 'semimind' },
  { label: 'Prod', url: 'demo-prod.example.invalid:8111', platform: 'semimind' },
  { label: 'Semiclaw_prod', url: 'demo-claw-prod.example.invalid:8630', platform: 'semiclaw' },
  { label: 'Semiclaw_sit', url: 'demo-claw-sit.example.invalid:7630', platform: 'semiclaw' }
]

const columns = [
  { title: '环境名称', dataIndex: 'name', key: 'name', width: 150 },
  { title: 'URL', dataIndex: 'url', key: 'url', ellipsis: true },
  { title: '平台', dataIndex: 'platform', key: 'platform', width: 100 },
  { title: 'Tenant ID', dataIndex: 'tenantId', key: 'tenantId', width: 180, ellipsis: true },
  { title: '账号角色', key: 'accountRole', width: 100 },
  { title: '描述', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: 'Agent列表', key: 'agents', width: 160 },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 180 },
  { title: '操作', key: 'action', width: 170, fixed: 'right' }
]

const envSearchKeyword = ref('')
const envPlatformFilter = ref(undefined)
const filteredEnvironments = computed(() => {
  const keyword = envSearchKeyword.value.trim().toLowerCase()
  const platform = envPlatformFilter.value
  return configStore.environments.filter(env => {
    if (platform && resolvePlatformByUrlAndValue(env.url, env.platform) !== platform) return false
    if (keyword) {
      const matched = String(env.name || '').toLowerCase().includes(keyword) ||
        String(env.url || '').toLowerCase().includes(keyword)
      if (!matched) return false
    }
    return true
  })
})

const modalVisible = ref(false)
const submitting = ref(false)
const editingEnv = ref(null)
const formRef = ref()
const formState = reactive({
  name: '',
  url: '',
  description: '',
  platform: 'semimind',
  credentialMode: CREDENTIAL_MODE_PERSONAL,
  tenantId: '',
  username: '',
  password: '',
  wsCookie: '',
  accountRole: undefined
})

const isSemiclaw = computed(() => {
  return normalizePlatform(resolvePlatformByUrlAndValue(formState.url, formState.platform)) === 'semiclaw'
})
const isServiceAccountMode = computed(() => {
  return isSemiclaw.value && normalizeCredentialMode(formState.credentialMode) === CREDENTIAL_MODE_SERVICE_ACCOUNT
})
const isPersonalCredentialMode = computed(() => isSemiclaw.value && !isServiceAccountMode.value)
const hasExistingPassword = computed(() => Boolean(editingEnv.value?.passwordConfigured || editingEnv.value?.hasPassword))
const canUsePasswordForPersonalMode = computed(() => Boolean(formState.password || hasExistingPassword.value))
const hasExistingWsCookie = computed(() => Boolean(editingEnv.value?.hasWsCookie))
const wsCookieExtraText = computed(() => hasExistingWsCookie.value
  ? '当前已保存 Cookie，不填写则沿用旧值；如已过期，请重新粘贴覆盖。'
  : '不填写则 WS 建连不携带该 Cookie，可能因网关鉴权失败导致评测报错。')

const rules = {
  name: [{ required: true, message: '请输入环境名称' }],
  url: [{ required: true, message: '请选择环境URL' }],
  platform: [{ required: true, message: '请选择平台' }],
  credentialMode: [{
    validator: async (_rule, value) => {
      if (isSemiclaw.value && !normalizeCredentialMode(value)) throw new Error('请选择 SemiClaw 登录方式')
    }
  }],
  tenantId: [{
    validator: async (_rule, value) => {
      if (isPersonalCredentialMode.value && !String(value || '').trim()) throw new Error('请输入 tenantId')
    }
  }],
  username: [{
    validator: async (_rule, value) => {
      if (isPersonalCredentialMode.value && !String(value || '').trim()) throw new Error('请输入登录账号')
    }
  }],
  password: [{
    validator: async (_rule, value) => {
      if (isPersonalCredentialMode.value && !value && !hasExistingPassword.value) throw new Error('请输入登录密码')
    }
  }]
}

const canLoadAgents = computed(() => {
  if (!formState.url) return false
  if (!isSemiclaw.value || isServiceAccountMode.value) return true
  return Boolean(
    String(formState.tenantId || '').trim() &&
    String(formState.username || '').trim() &&
    canUsePasswordForPersonalMode.value
  )
})

// Agent 列表 - 服务端分页
const agentSearch = ref('')
const agentTab = ref('bot')
const botList = ref([])
const botTotal = ref(0)
const botLoading = ref(false)
const botListRef = ref()
const canvasList = ref([])
const canvasTotal = ref(0)
const canvasLoading = ref(false)
const canvasListRef = ref()

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

function defaultCredentialModeForPlatform(platform) {
  return normalizePlatform(platform) === 'semiclaw'
    ? CREDENTIAL_MODE_SERVICE_ACCOUNT
    : CREDENTIAL_MODE_PERSONAL
}

function normalizeAccountRole(role) {
  const value = String(role || '').trim().toLowerCase()
  return value === 'admin' || value === 'normal' ? value : undefined
}

function normalizeEnvUrl(url) {
  return String(url || '').trim().replace(/^https?:\/\//i, '').replace(/\/+$/, '')
}

function findEnvOptionByUrl(url) {
  const normalizedUrl = normalizeEnvUrl(url)
  return ENV_URL_OPTIONS.find(item => normalizeEnvUrl(item.url) === normalizedUrl)
}

function resolvePlatformByUrlAndValue(url, platform) {
  const selectedOption = findEnvOptionByUrl(url)
  // 固定环境选项是权威来源，避免 SemiClaw URL 被误保存为 semimind
  if (selectedOption?.platform) return normalizePlatform(selectedOption.platform)
  return normalizePlatform(platform)
}

function isSemiclawRecord(record) {
  return resolvePlatformByUrlAndValue(record?.url, record?.platform) === 'semiclaw'
}

function isServiceAccountRecord(record) {
  const mode = normalizeCredentialMode(record?.credentialMode || record?.credential_mode || record?.loginMode || record?.login_mode)
  if (mode) return mode === CREDENTIAL_MODE_SERVICE_ACCOUNT
  if (!isSemiclawRecord(record)) return false
  return !(
    String(record?.tenantId || record?.tenant_id || '').trim() &&
    String(record?.username || '').trim() &&
    Boolean(record?.passwordConfigured || record?.hasPassword)
  )
}

function formatAgentCount(record) {
  if (isSemiclawRecord(record) && !record?.agentCount) return '动态加载'
  return `${record?.agentCount ?? 0} 个Agent`
}

function accountRoleLabel(record) {
  const role = normalizeAccountRole(record?.accountRole || record?.account_role)
  if (role === 'admin') return '管理员账号'
  if (role === 'normal') return '普通账号'
  return '未设置'
}

function accountRoleTagColor(record) {
  const role = normalizeAccountRole(record?.accountRole || record?.account_role)
  if (role === 'admin') return 'red'
  if (role === 'normal') return 'green'
  return 'default'
}

function isAdminAccountRecord(record) {
  return normalizeAccountRole(record?.accountRole || record?.account_role) === 'admin'
}

function resetAgentList() {
  botList.value = []
  botTotal.value = 0
  canvasList.value = []
  canvasTotal.value = 0
}

function normalizeAgent(agent) {
  const id = agent.id || agent.agentId || agent.agent_id
  const name = agent.name || agent.agentName || agent.agent_name || agent.title || id
  return {
    ...agent,
    id,
    name,
    creator: agent.creator || agent.createdBy || agent.created_by || '',
    agentType: agent.agentType || agent.agent_type || agent.type || 'agent',
    type: agent.type || agent.agentType || agent.agent_type || 'agent',
    roleDescription: agent.roleDescription || agent.role_description || agent.description || '',
    status: agent.status,
    isExpired: agent.isExpired ?? agent.is_expired
  }
}

// 加载指定类型的 Agent（追加模式）
async function loadAgents(type, reset = false) {
  if (!canLoadAgents.value) return
  const requestType = isSemiclaw.value ? 'agent' : type
  const isBotList = isSemiclaw.value || type === 'bot'
  const list = isBotList ? botList : canvasList
  const total = isBotList ? botTotal : canvasTotal
  const loading = isBotList ? botLoading : canvasLoading
  if (loading.value) return
  if (!reset && list.value.length >= total.value && total.value > 0) return

  loading.value = true
  try {
    const offset = reset ? 0 : list.value.length
    const res = await configStore.fetchAgentsPaged(
      currentEnvForRequest.value,
      requestType,
      offset,
      PAGE_SIZE,
      agentSearch.value
    )
    const items = (res.items || []).map(item => normalizeAgent(item)).filter(item => item.id)
    if (reset) list.value = items
    else list.value.push(...items)
    total.value = res.total || items.length || 0
  } catch (err) {
    message.error('加载Agent列表失败：' + (err.message || '请检查环境连接'))
  } finally {
    loading.value = false
  }
}

function resetAndLoadAll() {
  resetAgentList()
  if (!canLoadAgents.value) return
  if (isSemiclaw.value) {
    loadAgents('agent', true)
    return
  }
  loadAgents('bot', true)
  loadAgents('canvas', true)
}

let searchTimer = null
function handleSearch() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => resetAndLoadAll(), 300)
}

watch(agentSearch, value => {
  if (!value && modalVisible.value) resetAndLoadAll()
})

let envChangeTimer = null
watch(
  () => [formState.url, formState.platform, formState.credentialMode, formState.tenantId, formState.username, formState.password],
  () => {
    if (!modalVisible.value) return
    clearTimeout(envChangeTimer)
    envChangeTimer = setTimeout(() => resetAndLoadAll(), 800)
  }
)

function handleTabChange(key) {
  const list = key === 'bot' ? botList : canvasList
  if (list.value.length === 0) loadAgents(key, true)
}

function handleBotScroll(event) {
  const { scrollTop, scrollHeight, clientHeight } = event.target
  if (scrollTop + clientHeight >= scrollHeight - 10) {
    loadAgents(isSemiclaw.value ? 'agent' : 'bot')
  }
}

function handleCanvasScroll(event) {
  if (isSemiclaw.value) return
  const { scrollTop, scrollHeight, clientHeight } = event.target
  if (scrollTop + clientHeight >= scrollHeight - 10) loadAgents('canvas')
}

const currentEnvForRequest = computed(() => {
  const platform = resolvePlatformByUrlAndValue(formState.url, formState.platform)
  const credentialMode = platform === 'semiclaw'
    ? normalizeCredentialMode(formState.credentialMode)
    : CREDENTIAL_MODE_PERSONAL
  const tenantId = platform === 'semiclaw' && credentialMode === CREDENTIAL_MODE_PERSONAL
    ? String(formState.tenantId || '').trim()
    : ''
  const username = platform === 'semiclaw' && credentialMode === CREDENTIAL_MODE_PERSONAL
    ? String(formState.username || '').trim()
    : ''
  const password = platform === 'semiclaw' && credentialMode === CREDENTIAL_MODE_PERSONAL
    ? String(formState.password || '')
    : ''
  return {
    id: editingEnv.value?.id,
    envId: editingEnv.value?.id,
    environmentId: editingEnv.value?.id,
    url: formState.url,
    envUrl: formState.url,
    baseUrl: formState.url,
    platform,
    credentialMode,
    credential_mode: credentialMode,
    loginMode: credentialMode,
    login_mode: credentialMode,
    tenantId,
    tenant_id: tenantId,
    username,
    password
  }
})

// ======== 导入数字员工 ========
const importModalVisible = ref(false)
const importTargetEnv = ref(null)
const importFileList = ref([])
const importForm = reactive({ primaryModelId: '', fallbackModelId: '', autoConfigPermission: true })
const importSubmitting = ref(false)
const modelUpdateSubmitting = ref(false)
const importResult = ref(null)
const modelOptions = ref([])
const modelOptionsLoading = ref(false)
const selectedDuplicatePaths = ref([])
const duplicateDeleteSubmitting = ref(false)

function openImportModal(env) {
  importTargetEnv.value = env
  resetImportModal()
  importModalVisible.value = true
  loadModelOptions(env?.id)
}

function resetImportModal() {
  importFileList.value = []
  importForm.primaryModelId = ''
  importForm.fallbackModelId = ''
  importForm.autoConfigPermission = true
  importResult.value = null
  modelOptions.value = []
  selectedDuplicatePaths.value = []
}

async function loadModelOptions(envId) {
  if (!envId) return
  modelOptionsLoading.value = true
  try {
    const models = await configStore.fetchSemiclawModels(envId)
    modelOptions.value = (models || []).map(model => ({
      value: model.id,
      label: model.model && model.label && model.model !== model.label
        ? `${model.label} (${model.model})`
        : (model.label || model.model || model.id)
    }))
  } catch (err) {
    message.error('获取模型列表失败：' + (err.message || ''))
  } finally {
    modelOptionsLoading.value = false
  }
}

function handleImportFileSelect(file) {
  const name = file.name || ''
  if (!/\.(tar\.gz|tgz)$/i.test(name)) {
    message.error('仅支持 .tar.gz / .tgz 格式文件')
    return false
  }
  importFileList.value = [{
    uid: file.uid || String(Date.now()),
    name: file.name,
    status: 'done',
    originFileObj: file
  }]
  return false
}

function handleImportFileRemove() {
  importFileList.value = []
}

async function handleImportSubmit() {
  if (importFileList.value.length === 0) {
    message.error('请先选择要导入的压缩包')
    return
  }
  importSubmitting.value = true
  try {
    const file = importFileList.value[0].originFileObj
    const result = await configStore.importSemiclawAgent(importTargetEnv.value.id, file, {
      primaryModelId: importForm.primaryModelId,
      fallbackModelId: importForm.fallbackModelId,
      autoConfigPermission: importForm.autoConfigPermission
    })
    importResult.value = result
    selectedDuplicatePaths.value = (result.duplicateSkillFolders || []).map(folder => folder.path)
    message.success('数字员工导入成功')
  } catch (err) {
    message.error('导入失败：' + (err.message || '请检查压缩包格式或稍后重试'))
  } finally {
    importSubmitting.value = false
  }
}

async function handleDeleteDuplicateSkills() {
  if (!selectedDuplicatePaths.value.length) return
  duplicateDeleteSubmitting.value = true
  try {
    const { deleted = [], failed = {} } = await configStore.deleteSemiclawSkillFolders(
      importTargetEnv.value.id,
      selectedDuplicatePaths.value
    )
    importResult.value.duplicateSkillFolders = importResult.value.duplicateSkillFolders.filter(
      folder => !deleted.includes(folder.path)
    )
    importResult.value.cleanedSkillFolders = [...(importResult.value.cleanedSkillFolders || []), ...deleted]
    selectedDuplicatePaths.value = selectedDuplicatePaths.value.filter(path => !deleted.includes(path))
    const failedPaths = Object.keys(failed)
    if (failedPaths.length) message.warning(`部分删除失败：${failedPaths.join('，')}`)
    else message.success('重复技能文件夹已删除')
  } catch (err) {
    message.error('删除失败：' + (err.message || ''))
  } finally {
    duplicateDeleteSubmitting.value = false
  }
}

async function handleUpdateModelAfterImport() {
  if (!importResult.value?.agent_id) return
  modelUpdateSubmitting.value = true
  try {
    const updated = await configStore.updateSemiclawAgentModel(
      importTargetEnv.value.id,
      importResult.value.agent_id,
      {
        primaryModelId: importForm.primaryModelId,
        fallbackModelId: importForm.fallbackModelId
      }
    )
    importResult.value.agentDetail = updated
    importResult.value.needsModelFix = false
    message.success('模型配置已更新')
  } catch (err) {
    message.error('更新模型配置失败：' + (err.message || ''))
  } finally {
    modelUpdateSubmitting.value = false
  }
}

function handleUrlChange(url) {
  const selected = findEnvOptionByUrl(url)
  if (!selected) {
    formState.platform = resolvePlatformByUrlAndValue(url, formState.platform)
    if (!isSemiclaw.value) {
      formState.credentialMode = CREDENTIAL_MODE_PERSONAL
      formState.tenantId = ''
      formState.username = ''
      formState.password = ''
    }
    agentSearch.value = ''
    resetAgentList()
    return
  }

  if (!formState.name) formState.name = selected.label
  formState.platform = resolvePlatformByUrlAndValue(url, selected.platform)
  formState.credentialMode = defaultCredentialModeForPlatform(formState.platform)
  if (!isPersonalCredentialMode.value) {
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
  }
  if (!isSemiclaw.value) {
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
  }
  agentSearch.value = ''
  agentTab.value = 'bot'
  resetAgentList()
}

function handlePlatformChange(platform) {
  formState.platform = resolvePlatformByUrlAndValue(formState.url, platform)
  formState.credentialMode = defaultCredentialModeForPlatform(formState.platform)
  if (!isPersonalCredentialMode.value) {
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
  }
  if (!isSemiclaw.value) {
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
  }
  agentSearch.value = ''
  agentTab.value = 'bot'
  resetAgentList()
}

function handleCredentialModeChange(mode) {
  formState.credentialMode = normalizeCredentialMode(mode) || CREDENTIAL_MODE_SERVICE_ACCOUNT
  if (isServiceAccountMode.value) {
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
  }
  agentSearch.value = ''
  agentTab.value = 'bot'
  resetAgentList()
  setTimeout(() => {
    formRef.value?.clearValidate?.(['tenantId', 'username', 'password'])
    resetAndLoadAll()
  })
}

function resolveCredentialModeFromEnv(env, platform) {
  const explicitMode = normalizeCredentialMode(
    env?.credentialMode || env?.credential_mode || env?.loginMode || env?.login_mode
  )
  if (explicitMode) return explicitMode
  if (normalizePlatform(platform) !== 'semiclaw') return CREDENTIAL_MODE_PERSONAL
  const hasPersonalCredential = Boolean(
    String(env?.tenantId || env?.tenant_id || '').trim() &&
    String(env?.username || '').trim() &&
    Boolean(env?.passwordConfigured || env?.hasPassword || env?.password)
  )
  return hasPersonalCredential ? CREDENTIAL_MODE_PERSONAL : CREDENTIAL_MODE_SERVICE_ACCOUNT
}

function openModal(env = null) {
  editingEnv.value = env
  agentSearch.value = ''
  agentTab.value = 'bot'
  resetAgentList()

  if (env) {
    const platform = resolvePlatformByUrlAndValue(env.url, env.platform)
    const credentialMode = resolveCredentialModeFromEnv(env, platform)
    formState.name = env.name || ''
    formState.url = env.url || ''
    formState.description = env.description || ''
    formState.platform = platform
    formState.credentialMode = credentialMode
    if (credentialMode === CREDENTIAL_MODE_PERSONAL) {
      formState.tenantId = env.tenantId || env.tenant_id || ''
      formState.username = env.username || ''
    } else {
      formState.tenantId = ''
      formState.username = ''
    }
    formState.password = ''
    formState.wsCookie = env.wsCookie || env.ws_cookie || ''
    formState.accountRole = normalizeAccountRole(env.accountRole || env.account_role)
  } else {
    formState.name = ''
    formState.url = ''
    formState.description = ''
    formState.platform = 'semimind'
    formState.credentialMode = CREDENTIAL_MODE_PERSONAL
    formState.tenantId = ''
    formState.username = ''
    formState.password = ''
    formState.wsCookie = ''
    formState.accountRole = undefined
  }

  modalVisible.value = true
  setTimeout(() => {
    formRef.value?.clearValidate?.()
    resetAndLoadAll()
  })
}

async function handleSubmit() {
  formState.platform = resolvePlatformByUrlAndValue(formState.url, formState.platform)
  if (isSemiclaw.value && !normalizeCredentialMode(formState.credentialMode)) {
    formState.credentialMode = CREDENTIAL_MODE_SERVICE_ACCOUNT
  }
  try {
    await formRef.value.validate()
  } catch {
    return
  }

  submitting.value = true
  try {
    const platform = resolvePlatformByUrlAndValue(formState.url, formState.platform)
    const credentialMode = platform === 'semiclaw'
      ? normalizeCredentialMode(formState.credentialMode) || CREDENTIAL_MODE_SERVICE_ACCOUNT
      : CREDENTIAL_MODE_PERSONAL
    const usePersonalCredential = platform === 'semiclaw' && credentialMode === CREDENTIAL_MODE_PERSONAL
    const tenantId = usePersonalCredential ? String(formState.tenantId || '').trim() : ''
    const username = usePersonalCredential ? String(formState.username || '').trim() : ''
    const data = {
      name: String(formState.name || '').trim(),
      url: String(formState.url || '').trim(),
      description: formState.description || '',
      platform,
      credentialMode,
      credential_mode: credentialMode,
      loginMode: credentialMode,
      login_mode: credentialMode,
      tenantId,
      tenant_id: tenantId,
      username
    }

    if (usePersonalCredential) {
      const password = String(formState.password || '')
      if (password) data.password = password
    } else {
      data.password = ''
    }

    if (platform === 'semiclaw') {
      const wsCookie = String(formState.wsCookie || '').trim()
      if (wsCookie) {
        data.wsCookie = wsCookie
        data.ws_cookie = wsCookie
      }
      const accountRole = normalizeAccountRole(formState.accountRole)
      if (accountRole) {
        data.accountRole = accountRole
        data.account_role = accountRole
      }
    }

    if (editingEnv.value) {
      await configStore.updateEnvironment(editingEnv.value.id, data)
      message.success('环境更新成功')
    } else {
      await configStore.createEnvironment(data)
      message.success('环境创建成功')
    }

    // 重新从后端拉取，确认 platform / tenantId / credentialMode 已经保存下来
    await configStore.fetchEnvironments()
    modalVisible.value = false
  } catch (err) {
    message.error(err.message || '保存环境失败')
  } finally {
    submitting.value = false
  }
}

async function handleDelete(id) {
  await configStore.deleteEnvironment(id)
  message.success('环境删除成功')
}
</script>

<style scoped>
.agent-scroll-list {
  max-height: 300px;
  overflow-y: auto;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 4px;
}

.agent-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.2s;
}

.agent-item:last-child {
  border-bottom: none;
}

.agent-item:hover {
  background: #f0f5ff;
}

.agent-name {
  font-size: 14px;
  color: #333;
  font-weight: 500;
}

.agent-creator {
  font-size: 12px;
  color: #999;
  margin-top: 2px;
}
</style>
