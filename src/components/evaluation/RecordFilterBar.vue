<template>
  <a-form layout="inline" :model="formState" class="record-filter-bar">
    <div class="filter-row">
      <a-form-item label="评测名称">
        <a-input v-model:value="formState.name" placeholder="搜索评测名称" allow-clear style="width: 180px;" />
      </a-form-item>
      <a-form-item label="状态">
        <a-select v-model:value="formState.status" allow-clear placeholder="全部状态" style="width: 140px;">
          <a-select-option v-for="(item, key) in EVAL_STATUS_MAP" :key="key" :value="key">
            {{ item.text }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="类型">
        <a-select v-model:value="formState.type" allow-clear placeholder="全部类型" style="width: 120px;">
          <a-select-option v-for="(item, key) in EVAL_TARGET_TYPE_MAP" :key="key" :value="key">
            {{ item.text }}
          </a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="被评测对象">
        <a-select
          v-model:value="formState.targetLabels"
          mode="multiple"
          allow-clear
          show-search
          placeholder="全部"
          style="width: 500px;"
          :options="targetOptions"
          max-tag-count="responsive"
        />
      </a-form-item>
    </div>
    <div class="filter-row">
      <a-form-item label="数据集">
        <a-select
          v-model:value="formState.datasetNames"
          mode="multiple"
          allow-clear
          show-search
          placeholder="全部"
          style="width: 220px;"
          :options="datasetOptions"
          max-tag-count="responsive"
        />
      </a-form-item>
      <a-form-item label="开始时间">
        <a-range-picker
          v-model:value="formState.timeRange"
          show-time
          format="YYYY-MM-DD HH:mm:ss"
          style="width: 360px;"
        />
      </a-form-item>
      <a-form-item>
        <a-space>
          <a-button type="primary" @click="handleSearch">查询</a-button>
          <a-button @click="handleReset">重置</a-button>
        </a-space>
      </a-form-item>
    </div>
  </a-form>
</template>

<script setup>
import { computed, reactive } from 'vue'
import { useEvaluationStore } from '@/stores/evaluation.js'
import { EVAL_STATUS_MAP, EVAL_TARGET_TYPE_MAP } from '@/utils/constants.js'

const emit = defineEmits(['search'])
const evalStore = useEvaluationStore()

function defaultFilters() {
  return {
    name: '',
    status: undefined,
    type: undefined,
    targetLabels: [],
    datasetNames: [],
    timeRange: []
  }
}

const formState = reactive(defaultFilters())
const optionSets = computed(() => {
  const labels = new Set()
  const names = new Set()
  evalStore.records.forEach(record => {
    ;(record.targets || []).forEach(target => target.label && labels.add(target.label))
    ;(record.datasetNames || []).forEach(name => name && names.add(name))
  })
  return { labels: [...labels], names: [...names] }
})
const targetOptions = computed(() => optionSets.value.labels.map(label => ({ label, value: label })))
const datasetOptions = computed(() => optionSets.value.names.map(name => ({ label: name, value: name })))

function handleSearch() {
  emit('search', {
    ...formState,
    targetLabels: [...formState.targetLabels],
    datasetNames: [...formState.datasetNames],
    timeRange: [...formState.timeRange]
  })
}

function handleReset() {
  Object.assign(formState, defaultFilters())
  handleSearch()
}
</script>

<style scoped>
.record-filter-bar {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  row-gap: 12px;
}

.filter-row + .filter-row {
  margin-top: 4px;
}
</style>
