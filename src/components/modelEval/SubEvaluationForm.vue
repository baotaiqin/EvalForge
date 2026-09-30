<template>
  <a-modal
    :open="open"
    :title="editingRecord ? '编辑子评测' : '新建子评测'"
    width="40%"
    centered
    :footer="null"
    :body-style="{ height: '75vh', overflow: 'auto' }"
    @cancel="handleClose"
  >
    <a-form ref="formRef" :model="formState" :rules="rules" layout="vertical">
      <a-form-item label="子评测类型" name="subType">
        <a-select
          v-model:value="formState.subType"
          placeholder="选择或新建子评测类型"
          :popup-match-select-width="false"
        >
          <template #dropdownRender="{ menuNode }">
            <component :is="menuNode" />
            <a-divider style="margin: 4px 0;" />
            <a-space v-if="editingTypeId" style="padding: 4px 8px;">
              <a-input
                v-model:value="editingTypeName"
                placeholder="类型名称"
                size="small"
                @keydown.stop
                @keyup.enter="confirmEditType(editingTypeId)"
                @keyup.esc="editingTypeId = null"
              />
              <a-button type="primary" size="small" @click="confirmEditType(editingTypeId)">保存</a-button>
              <a-button size="small" @click="editingTypeId = null">取消</a-button>
            </a-space>
            <a-space v-else style="padding: 4px 8px;">
              <a-input
                v-model:value="newTypeName"
                placeholder="新建类型名称"
                size="small"
                @keydown.stop
                @keyup.enter="handleCreateType"
              />
              <a-button type="link" size="small" @click="handleCreateType">添加</a-button>
            </a-space>
          </template>
          <a-select-option
            v-for="type in modelEvalStore.subEvalTypes"
            :key="type.id"
            :value="type.id"
          >
            <span style="display: flex; align-items: center; justify-content: space-between;">
              <span>
                {{ type.name }}
                <span v-if="isTypeUsedByOthers(type.id)" style="color: #999; font-size: 12px; margin-left: 4px;">(该分组已配置)</span>
              </span>
              <span @click.stop style="display: flex; align-items: center;">
                <EditOutlined style="margin-left: 8px; color: #999;" @click="startEditType(type)" />
                <a-popconfirm title="确定删除该类型吗？" @confirm="handleDeleteType(type.id)">
                  <DeleteOutlined style="margin-left: 8px; color: #ff4d4f;" />
                </a-popconfirm>
              </span>
            </span>
          </a-select-option>
        </a-select>
      </a-form-item>

      <a-form-item label="被评测对象类型" name="targetType">
        <a-radio-group v-model:value="formState.targetType" @change="formState.targets = []">
          <a-radio-button value="agent">Agent</a-radio-button>
          <a-radio-button value="model">模型</a-radio-button>
        </a-radio-group>
      </a-form-item>

      <a-form-item label="被评测对象" required>
        <AgentSelector
          v-if="formState.targetType === 'agent'"
          :key="editingRecord?.id || 'new-agent'"
          v-model="formState.targets"
        />
        <ModelSelector v-else v-model="formState.targets" />
      </a-form-item>

      <a-form-item label="测试数据集" name="datasetIds">
        <a-select
          v-model:value="formState.datasetIds"
          mode="multiple"
          placeholder="选择数据集（可多选，支持搜索）"
          style="width: 100%;"
          show-search
          :popup-match-select-width="false"
          :filter-option="filterDatasetOption"
        >
          <a-select-option v-for="dataset in sortedDatasetOptions" :key="dataset.id" :value="dataset.id">
            <StarFilled v-if="dataset.starred" style="color: #faad14; margin-right: 4px; font-size: 12px;" />
            {{ dataset.name }}
          </a-select-option>
        </a-select>
        <div style="margin-top: 4px; color: #888; font-size: 12px;">
          选1个数据集应用于所有被评测数据；选多个需一一对应，此处配置一次性固化，之后每次“开始测评”直接复用
        </div>
      </a-form-item>

      <DatasetMatcher
        v-if="formState.datasetIds.length > 1 && formState.targets.length > 1"
        v-model="formState.datasetMapping"
        :targets="formState.targets"
        :datasets="selectedDatasets"
      />

      <a-form-item label="评测方式" v-if="selectedDatasets.some(dataset => dataset.hasExpectedResult)">
        <a-radio-group v-model:value="formState.judgeMode">
          <a-radio-button value="formula">公式评分</a-radio-button>
          <a-radio-button value="llm">大模型评测</a-radio-button>
        </a-radio-group>
      </a-form-item>

      <a-form-item label="评判模型" v-if="formState.judgeMode === 'llm'">
        <a-select
          v-model:value="formState.judgeModelId"
          placeholder="选择用于评测的大模型"
          style="width: 100%;"
          show-search
          :popup-match-select-width="false"
          :options="modelOptions"
        />
      </a-form-item>

      <a-form-item v-if="formState.datasetIds.length > 0">
        <a-alert :message="evalModeHint" :type="evalModeType" show-icon />
      </a-form-item>

      <a-form-item label="备注">
        <a-textarea v-model:value="formState.remark" placeholder="备注（可选）" :rows="3" />
      </a-form-item>

      <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 16px; border-top: 1px solid #f0f0f0; padding-top: 16px;">
        <a-button v-if="editingRecord" @click="copyModalVisible = true">复制到其他分组</a-button>
        <span v-else />
        <a-space>
          <a-button @click="handleClose">取消</a-button>
          <a-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ editingRecord ? '保存修改' : '创建子评测' }}
          </a-button>
        </a-space>
      </div>
    </a-form>
  </a-modal>

  <CopySubEvaluationModal
    v-model:open="copyModalVisible"
    :sub-eval="editingRecord"
    :current-group-id="groupId"
    @success="emit('success')"
  />
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { StarFilled, EditOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { useDatasetStore } from '@/stores/dataset.js'
import { useConfigStore } from '@/stores/config.js'
import { useModelEvalStore } from '@/stores/modelEval.js'
import AgentSelector from '@/components/evaluation/AgentSelector.vue'
import ModelSelector from '@/components/evaluation/ModelSelector.vue'
import DatasetMatcher from '@/components/evaluation/DatasetMatcher.vue'
import CopySubEvaluationModal from './CopySubEvaluationModal.vue'

const props = defineProps({
  open: Boolean,
  groupId: { type: String, required: true },
  editingRecord: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'success'])

const datasetStore = useDatasetStore()
const configStore = useConfigStore()
const modelEvalStore = useModelEvalStore()
const formRef = ref()
const submitting = ref(false)
const newTypeName = ref('')
const editingTypeId = ref(null)
const editingTypeName = ref('')
const copyModalVisible = ref(false)

const formState = reactive({
  subType: undefined,
  targetType: 'agent',
  targets: [],
  datasetIds: [],
  datasetMapping: {},
  remark: '',
  judgeMode: 'formula',
  judgeModelId: null
})

const rules = {
  subType: [{ required: true, message: '请选择子评测类型' }],
  datasetIds: [{ required: true, type: 'array', min: 1, message: '请选择至少一个数据集' }]
}

const selectedDatasets = computed(() =>
  datasetStore.datasets.filter(dataset => formState.datasetIds.includes(dataset.id))
)
const sortedDatasetOptions = computed(() =>
  [...datasetStore.datasets].sort((a, b) => (b.starred ? 1 : 0) - (a.starred ? 1 : 0))
)
const evalModeHint = computed(() => {
  const hasExpected = selectedDatasets.value.some(dataset => dataset.hasExpectedResult)
  const noExpected = selectedDatasets.value.some(dataset => !dataset.hasExpectedResult)
  if (hasExpected && noExpected) return '混合模式：含预期结果的数据自动评分，无预期结果的数据需人工评判'
  if (hasExpected) return '自动评测模式：数据集含预期结果，系统将自动评分'
  return '人工评判模式：数据集无预期结果，需人工对比评判'
})
const evalModeType = computed(() => {
  const hasExpected = selectedDatasets.value.some(dataset => dataset.hasExpectedResult)
  const noExpected = selectedDatasets.value.some(dataset => !dataset.hasExpectedResult)
  if (hasExpected && !noExpected) return 'success'
  if (!hasExpected && noExpected) return 'warning'
  return 'info'
})
const modelOptions = computed(() => configStore.models.map(model => ({
  label: `${model.name} (${model.provider})`,
  value: model.id
})))

function isTypeUsedByOthers(typeId) {
  return modelEvalStore.subEvaluations.some(
    subEval => subEval.subType === typeId && subEval.id !== props.editingRecord?.id
  )
}

watch(() => props.open, visible => {
  if (!visible) return
  editingTypeId.value = null
  editingTypeName.value = ''
  newTypeName.value = ''
  if (props.editingRecord) {
    const record = props.editingRecord
    formState.subType = record.subType
    formState.targetType = record.targetType || 'agent'
    formState.targets = (record.targets || []).map(target => ({ ...target }))
    formState.datasetIds = [...(record.datasetIds || [])]
    formState.datasetMapping = { ...(record.datasetMapping || {}) }
    formState.remark = record.remark || ''
    formState.judgeMode = record.judgeMode || 'formula'
    formState.judgeModelId = record.judgeModelId || null
  } else {
    formState.subType = undefined
    formState.targetType = 'agent'
    formState.targets = []
    formState.datasetIds = []
    formState.datasetMapping = {}
    formState.remark = ''
    formState.judgeMode = 'formula'
    formState.judgeModelId = null
  }
})

onMounted(() => {
  datasetStore.fetchDatasets()
  configStore.fetchEnvironments()
  configStore.fetchModels()
  if (!modelEvalStore.subEvalTypes.length) modelEvalStore.fetchSubEvalTypes()
})

function handleClose() {
  emit('update:open', false)
}

function filterDatasetOption(input, option) {
  const dataset = datasetStore.datasets.find(item => item.id === option.value)
  return dataset?.name?.toLowerCase().includes(input.toLowerCase())
}

async function handleCreateType() {
  const name = newTypeName.value.trim()
  if (!name) return
  try {
    const type = await modelEvalStore.createSubEvalType(name)
    formState.subType = type.id
    newTypeName.value = ''
  } catch (error) {
    message.error(error.message || '创建类型失败')
  }
}

function startEditType(type) {
  editingTypeId.value = type.id
  editingTypeName.value = type.name
}

async function confirmEditType(typeId) {
  if (editingTypeId.value !== typeId) return
  const name = editingTypeName.value.trim()
  editingTypeId.value = null
  if (!name) return
  try {
    await modelEvalStore.updateSubEvalType(typeId, name)
  } catch (error) {
    message.error(error.message || '更新类型失败')
  }
}

async function handleDeleteType(typeId) {
  try {
    await modelEvalStore.deleteSubEvalType(typeId)
    if (formState.subType === typeId) formState.subType = undefined
    if (editingTypeId.value === typeId) editingTypeId.value = null
    message.success('类型已删除')
  } catch (error) {
    message.error(error.message || '删除类型失败')
  }
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (formState.targets.length === 0) {
    message.warning('请至少选择一个被评测对象')
    return
  }
  if (isTypeUsedByOthers(formState.subType)) {
    message.warning('该分组已存在该类型的子评测，请直接编辑现有配置')
    return
  }

  submitting.value = true
  try {
    const hasExpected = selectedDatasets.value.some(dataset => dataset.hasExpectedResult)
    const data = {
      groupId: props.groupId,
      subType: formState.subType,
      targetType: formState.targetType,
      targets: formState.targets.map(target => ({ ...target })),
      datasetIds: [...formState.datasetIds],
      datasetNames: selectedDatasets.value.map(dataset => dataset.name),
      datasetMapping: { ...formState.datasetMapping },
      remark: formState.remark,
      judgeMode: hasExpected ? formState.judgeMode : null,
      judgeModelId: hasExpected && formState.judgeMode === 'llm' ? formState.judgeModelId : null
    }
    if (props.editingRecord) {
      await modelEvalStore.updateSubEvaluation(props.editingRecord.id, data)
      message.success('子评测已更新')
    } else {
      await modelEvalStore.createSubEvaluation(data)
      message.success('子评测已创建')
    }
    handleClose()
    emit('success')
  } catch (error) {
    message.error(error.message || '操作失败')
  } finally {
    submitting.value = false
  }
}
</script>
