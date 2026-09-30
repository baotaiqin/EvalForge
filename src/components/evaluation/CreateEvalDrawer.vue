<template>
  <a-modal
    :open="open"
    :title="editingRecord ? '编辑评测' : '新增评测'"
    width="40%"
    centered
    :footer="null"
    :confirm-loading="submitting"
    :body-style="{ height: '80vh', overflow: 'auto' }"
    @cancel="handleClose"
  >
    <a-form ref="formRef" :model="formState" :rules="rules" layout="vertical">
      <a-form-item label="评测名称" name="nameSuffix">
        <a-input-group compact>
          <a-input v-model:value="namePrefix" disabled style="width: 42%;" />
          <a-input v-model:value="formState.nameSuffix" placeholder="请输入评测名称" style="width: 58%;" />
        </a-input-group>
      </a-form-item>

      <a-form-item label="被评测对象类型" name="targetType">
          <a-radio-group v-model:value="formState.targetType" button-style="solid" @change="handleTargetTypeChange">
          <a-radio-button value="agent">Agent</a-radio-button>
          <a-radio-button value="model">模型</a-radio-button>
        </a-radio-group>
      </a-form-item>

      <a-form-item label="被评测对象" name="targets">
        <AgentSelector
          v-if="formState.targetType === 'agent'"
          :key="editingRecord?.id || 'new-agent'"
          v-model="formState.targets"
        />
        <ModelSelector v-else v-model="formState.targets" />
      </a-form-item>

      <a-form-item label="选择数据集" name="datasetIds">
        <a-select
          v-model:value="formState.datasetIds"
          mode="multiple"
          show-search
          allow-clear
          placeholder="请选择数据集（可多选）"
          :popup-match-select-width="false"
          :filter-option="filterDatasetOption"
          style="width: 100%;"
        >
          <a-select-option v-for="dataset in sortedDatasetOptions" :key="dataset.id" :value="dataset.id">
            <StarFilled v-if="dataset.starred" style="color: #faad14; margin-right: 4px;" />
            {{ dataset.name }}
            <a-tag
              :color="dataset.hasExpectedResult ? 'green' : 'orange'"
              style="margin-left: 8px;"
            >{{ dataset.hasExpectedResult ? '有预期结果' : '无预期结果' }}</a-tag>
          </a-select-option>
        </a-select>
        <div class="field-tip">可选择多个数据集，评测时会合并其中的题目。</div>
      </a-form-item>

      <template v-if="hasExpectedDataset">
        <a-form-item label="评分模式">
          <a-radio-group v-model:value="formState.judgeMode">
            <a-radio value="formula">公式评分</a-radio>
            <a-radio value="llm">模型评分</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="formState.judgeMode === 'llm'" label="评分模型" name="judgeModelId">
          <a-select
            v-model:value="formState.judgeModelId"
            placeholder="请选择评分模型"
            :options="modelOptions"
            style="width: 100%;"
          />
        </a-form-item>
      </template>

      <DatasetMatcher
        v-if="selectedDatasets.length > 1 && formState.targets.length > 1"
        v-model="formState.datasetMapping"
        :targets="formState.targets"
        :datasets="selectedDatasets"
      />

      <a-alert
        v-if="selectedDatasets.length"
        :message="evalModeHint"
        :type="evalModeType"
        show-icon
        style="margin: 16px 0;"
      />

      <a-form-item label="备注" name="remark">
        <a-textarea v-model:value="formState.remark" placeholder="请输入备注（选填）" :rows="3" />
      </a-form-item>

      <div class="drawer-actions">
        <a-button @click="handleClose">取消</a-button>
        <a-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ editingRecord ? '保存' : '确认' }}
        </a-button>
      </div>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { StarFilled } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { useDatasetStore } from '@/stores/dataset.js'
import { useEvaluationStore } from '@/stores/evaluation.js'
import { useConfigStore } from '@/stores/config.js'
import AgentSelector from './AgentSelector.vue'
import ModelSelector from './ModelSelector.vue'
import DatasetMatcher from './DatasetMatcher.vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  editingRecord: { type: Object, default: null }
})
const emit = defineEmits(['update:open', 'success'])

const datasetStore = useDatasetStore()
const evalStore = useEvaluationStore()
const configStore = useConfigStore()
const formRef = ref()
const submitting = ref(false)
const namePrefix = ref('')
const formState = reactive({
  nameSuffix: '',
  targetType: 'agent',
  targets: [],
  datasetIds: [],
  datasetMapping: {},
  remark: '',
  judgeMode: 'formula',
  judgeModelId: null
})
const rules = {
  nameSuffix: [{ required: true, message: '请输入评测名称', trigger: 'blur' }],
  targetType: [{ required: true, message: '请选择被评测对象类型', trigger: 'change' }],
  datasetIds: [{ required: true, type: 'array', min: 1, message: '请选择至少一个数据集', trigger: 'change' }],
  judgeModelId: [{ required: true, message: '请选择评分模型', trigger: 'change' }]
}

const selectedDatasets = computed(() => datasetStore.datasets.filter(dataset =>
  formState.datasetIds.includes(dataset.id)
))
function datasetHasExpectedResult(dataset) {
  if (dataset.hasExpectedResult != null) return Boolean(dataset.hasExpectedResult)
  return (dataset.items || []).some(item => Boolean(item.expectedAnswer || item.expected?.text || item.expected?.file))
}
const sortedDatasetOptions = computed(() => [...datasetStore.datasets].sort((left, right) =>
  Number(Boolean(right.starred)) - Number(Boolean(left.starred))
))
const hasExpectedDataset = computed(() => selectedDatasets.value.some(datasetHasExpectedResult))
const modelOptions = computed(() => configStore.models.map(model => ({
  label: `${model.name} (${model.provider})`,
  value: model.id
})))
const evalModeHint = computed(() => {
  const expectedCount = selectedDatasets.value.filter(datasetHasExpectedResult).length
  if (expectedCount > 0 && expectedCount < selectedDatasets.value.length) {
    return '混合模式：含预期结果的数据自动评分，无预期结果的数据需人工评分'
  }
  if (expectedCount === selectedDatasets.value.length && expectedCount > 0) {
    return '自动评分模式：数据集均含预期结果，系统将自动评分'
  }
  return '人工评判模式：数据集无预期结果，需人工对比评判'
})
const evalModeType = computed(() => {
  const expectedCount = selectedDatasets.value.filter(datasetHasExpectedResult).length
  if (expectedCount > 0 && expectedCount < selectedDatasets.value.length) return 'info'
  return expectedCount ? 'success' : 'warning'
})

watch(() => props.open, visible => {
  if (!visible) return
  namePrefix.value = dayjs().format('YYYYMMDD-HHmmss')
  if (props.editingRecord) {
    const parts = String(props.editingRecord.name || '').split(' ')
    namePrefix.value = parts.shift() || namePrefix.value
    Object.assign(formState, {
      nameSuffix: parts.join(' '),
      targetType: props.editingRecord.type || props.editingRecord.targetType || 'agent',
      targets: [...(props.editingRecord.targets || [])],
      datasetIds: [...(props.editingRecord.datasetIds || [])],
      datasetMapping: { ...(props.editingRecord.datasetMapping || {}) },
      remark: props.editingRecord.remark || '',
      judgeMode: props.editingRecord.judgeMode || 'formula',
      judgeModelId: props.editingRecord.judgeModelId || null
    })
  } else {
    Object.assign(formState, {
      nameSuffix: '',
      targetType: 'agent',
      targets: [],
      datasetIds: [],
      datasetMapping: {},
      remark: '',
      judgeMode: 'formula',
      judgeModelId: null
    })
  }
}, { immediate: true })

function handleTargetTypeChange() {
  formState.targets = []
}

onMounted(() => {
  if (!datasetStore.datasets.length) datasetStore.fetchDatasets()
  if (!configStore.environments.length) configStore.fetchEnvironments()
  if (!configStore.models.length) configStore.fetchModels()
})

function handleClose() {
  emit('update:open', false)
}

function filterDatasetOption(input, option) {
  const dataset = datasetStore.datasets.find(item => item.id === option.value)
  return String(dataset?.name || '').toLowerCase().includes(String(input || '').toLowerCase())
}

async function handleSubmit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  if (!formState.targets.length) {
    message.warning('请选择至少一个被评测对象')
    return
  }

  submitting.value = true
  const data = {
    name: `${namePrefix.value} ${formState.nameSuffix}`.trim(),
    type: formState.targetType,
    targets: formState.targets.map(target => ({ ...target })),
    datasetIds: [...formState.datasetIds],
    datasetNames: selectedDatasets.value.map(dataset => dataset.name),
    datasetMapping: { ...formState.datasetMapping },
    remark: formState.remark,
    judgeMode: hasExpectedDataset.value ? formState.judgeMode : null,
    judgeModelId: hasExpectedDataset.value && formState.judgeMode === 'llm'
      ? formState.judgeModelId
      : null
  }

  try {
    if (props.editingRecord) {
      await evalStore.updateEvaluation(props.editingRecord.id, data)
      message.success('评测信息已保存')
    } else {
      const allItems = selectedDatasets.value.flatMap(dataset => dataset.items || [])
      evalStore.createStreamingEvaluation(data, allItems).catch(error => {
        message.error(`评测执行失败：${error?.message || ''}`)
      })
      message.info('评测已提交，正在后台执行...')
    }
    handleClose()
    emit('success')
  } catch (error) {
    message.error(error?.message || '保存评测失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.field-tip {
  margin-top: 4px;
  color: #999;
  font-size: 12px;
}

.drawer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}
</style>
