<template>
  <div>
    <div style="margin-bottom: 16px; display: flex; justify-content: flex-end;">
      <a-space>
        <a-input-search
          v-model:value="modelSearchKeyword"
          placeholder="搜索模型名称/提供商"
          allow-clear
          style="width: 220px;"
        />
        <a-button type="primary" @click="openModal()">
          <template #icon><PlusOutlined /></template>
          新增模型
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="filteredModels"
      :loading="configStore.modelLoading"
      row-key="id"
      :pagination="{ pageSize: 10 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'apiKey'">
          {{ maskApiKey(record.apiKey) }}
        </template>
        <template v-else-if="column.key === 'baseUrl'">
          <a-typography-text :ellipsis="{ tooltip: record.baseUrl }" style="max-width: 220px;">
            {{ record.baseUrl }}
          </a-typography-text>
        </template>
        <template v-else-if="column.key === 'params'">
          <a-space wrap size="small">
            <a-tag>温度: {{ record.params?.temperature }}</a-tag>
            <a-tag>上下文: {{ record.params?.contextLength }}</a-tag>
            <a-tag>topP: {{ record.params?.topP }}</a-tag>
            <a-tag>最大输入Token: {{ record.params?.maxInputTokens }}</a-tag>
            <a-tag>取样: {{ record.params?.sampleCount }}</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="openModal(record)">编辑</a>
            <a @click="handleTest(record)">
              <a-spin v-if="testingId === record.id" size="small" />
              <span v-else>测试</span>
            </a>
            <a-popconfirm title="确定删除该模型吗？" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalVisible"
      :title="editingModel ? '编辑模型' : '新增模型'"
      :confirm-loading="submitting"
      width="700px"
      @ok="handleSubmit"
    >
      <a-form ref="formRef" :model="formState" :rules="rules" layout="vertical">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="模型名称" name="name">
              <a-input v-model:value="formState.name" placeholder="例如：GPT-4o" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="提供商" name="provider">
              <a-select v-model:value="formState.provider" placeholder="请选择提供商">
                <a-select-option v-for="provider in MODEL_PROVIDERS" :key="provider" :value="provider">
                  {{ provider }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="API Key" name="apiKey">
          <a-input-password v-model:value="formState.apiKey" placeholder="请输入 API Key" />
        </a-form-item>
        <a-form-item label="Base URL" name="baseUrl">
          <a-input v-model:value="formState.baseUrl" placeholder="例如：https://api.openai.com/v1" />
        </a-form-item>

        <a-divider>模型参数</a-divider>
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="Temperature">
              <a-input-number v-model:value="formState.params.temperature" :min="0" :max="2" :step="0.1" style="width: 100%;" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="Top P">
              <a-input-number v-model:value="formState.params.topP" :min="0" :max="1" :step="0.05" style="width: 100%;" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="取样数量">
              <a-input-number v-model:value="formState.params.sampleCount" :min="0" :max="99" :step="1" :precision="0" style="width: 100%;" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="模型上下文长度">
              <a-input-number v-model:value="formState.params.contextLength" :min="0" :step="256" style="width: 100%;" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="用户输入最大Token">
              <a-input-number v-model:value="formState.params.maxInputTokens" :min="0" :step="256" style="width: 100%;" />
            </a-form-item>
          </a-col>
          <a-col :span="24">
            <a-form-item label="额外参数（JSON）">
              <a-textarea
                v-model:value="formState.params.extraParams"
                :status="extraParamsError ? 'error' : ''"
                placeholder="请输入JSON格式字符串，例如：{&quot;frequency_penalty&quot;: 0.5, &quot;presence_penalty&quot;: 0.3}"
                :rows="3"
                @blur="validateExtraParams"
              />
              <div v-if="extraParamsError" style="color: #ff4d4f; font-size: 12px; margin-top: 4px;">
                {{ extraParamsError }}
              </div>
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useConfigStore } from '@/stores/config.js'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { MODEL_PROVIDERS } from '@/utils/constants.js'

const configStore = useConfigStore()
const modelSearchKeyword = ref('')
const modalVisible = ref(false)
const submitting = ref(false)
const testingId = ref(null)
const editingModel = ref(null)
const extraParamsError = ref('')
const formRef = ref(null)

const columns = [
  { title: '模型名称', dataIndex: 'name', key: 'name', width: 160 },
  { title: '提供商', dataIndex: 'provider', key: 'provider', width: 120 },
  { title: 'API Key', dataIndex: 'apiKey', key: 'apiKey', width: 160 },
  { title: 'Base URL', dataIndex: 'baseUrl', key: 'baseUrl', ellipsis: true },
  { title: '参数配置', key: 'params', width: 360 },
  { title: '操作', key: 'action', fixed: 'right', width: 150 }
]

const defaultParams = {
  temperature: 0.7,
  contextLength: 4096,
  maxInputTokens: 4096,
  topP: 1.0,
  sampleCount: 1,
  extraParams: ''
}

const formState = reactive({
  name: '',
  provider: undefined,
  apiKey: '',
  baseUrl: '',
  params: { ...defaultParams }
})

const rules = {
  name: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
  provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
  apiKey: [{ required: true, message: '请输入 API Key', trigger: 'blur' }],
  baseUrl: [{ required: true, message: '请输入 Base URL', trigger: 'blur' }]
}

const filteredModels = computed(() => {
  const keyword = modelSearchKeyword.value.trim().toLowerCase()
  if (!keyword) return configStore.models
  return configStore.models.filter(model =>
    `${model.name || ''} ${model.provider || ''}`.toLowerCase().includes(keyword)
  )
})

onMounted(() => configStore.fetchModels())

function maskApiKey(key) {
  if (!key) return ''
  if (key.length <= 8) return '****'
  return `${key.slice(0, 4)}****${key.slice(-4)}`
}

function validateExtraParams() {
  const text = formState.params.extraParams.trim()
  if (!text) {
    extraParamsError.value = ''
    return true
  }
  try {
    JSON.parse(text)
    extraParamsError.value = ''
    return true
  } catch {
    extraParamsError.value = 'JSON格式不正确，请检查输入'
    return false
  }
}

function openModal(model = null) {
  editingModel.value = model
  extraParamsError.value = ''
  if (model) {
    formState.name = model.name || ''
    formState.provider = model.provider
    formState.apiKey = model.apiKey || ''
    formState.baseUrl = model.baseUrl || ''
    formState.params = { ...defaultParams, ...(model.params || {}) }
    if (typeof formState.params.extraParams !== 'string') {
      formState.params.extraParams = JSON.stringify(formState.params.extraParams || {}, null, 2)
    }
  } else {
    formState.name = ''
    formState.provider = undefined
    formState.apiKey = 'EMPTY'
    formState.baseUrl = ''
    formState.params = { ...defaultParams }
  }
  modalVisible.value = true
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (Number(formState.params.maxInputTokens) > Number(formState.params.contextLength)) {
    message.error('用户输入最大Token不能大于模型上下文长度')
    return
  }
  if (!validateExtraParams()) return

  submitting.value = true
  try {
    const payload = {
      name: formState.name,
      provider: formState.provider,
      apiKey: formState.apiKey,
      baseUrl: formState.baseUrl,
      params: {
        ...formState.params,
        extraParams: formState.params.extraParams.trim()
          ? JSON.parse(formState.params.extraParams)
          : {}
      }
    }
    if (editingModel.value) {
      await configStore.updateModel(editingModel.value.id, payload)
      message.success('模型更新成功')
    } else {
      await configStore.createModel(payload)
      message.success('模型创建成功')
    }
    modalVisible.value = false
  } finally {
    submitting.value = false
  }
}

async function handleDelete(id) {
  await configStore.deleteModel(id)
  message.success('模型删除成功')
}

async function handleTest(record) {
  testingId.value = record.id
  try {
    const res = await configStore.testModelConnection({
      name: record.name,
      apiKey: record.apiKey,
      baseUrl: record.baseUrl
    })
    if (res.code === 0) message.success(`连接成功：${record.name}`)
    else message.error(`连接失败：${res.message || '未知错误'}`)
  } catch (err) {
    message.error(`连接失败：${err?.message || '网络异常'}`)
  } finally {
    testingId.value = null
  }
}
</script>
