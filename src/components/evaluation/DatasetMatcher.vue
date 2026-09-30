<template>
  <div v-if="showMatcher">
    <a-alert
      message="请为每个被评测对象指定对应的数据集"
      type="info"
      show-icon
      style="margin-bottom: 12px;"
    />
    <a-table
      :columns="columns"
      :data-source="targets"
      row-key="id"
      :pagination="false"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'dataset'">
          <a-select
            :value="localMapping[record.id]"
            placeholder="选择数据集"
            style="width: 100%;"
            @update:value="value => updateMapping(record.id, value)"
          >
            <a-select-option v-for="dataset in datasets" :key="dataset.id" :value="dataset.id">
              {{ dataset.name }}
            </a-select-option>
          </a-select>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  targets: { type: Array, default: () => [] },
  datasets: { type: Array, default: () => [] },
  modelValue: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue'])

const localMapping = ref({})
const showMatcher = computed(() => props.datasets.length > 1 && props.targets.length > 1)
const columns = [
  { title: '被评测对象', dataIndex: 'label', key: 'label' },
  { title: '对应数据集', key: 'dataset', width: 300 }
]

watch(() => props.modelValue, value => {
  localMapping.value = { ...(value || {}) }
}, { immediate: true, deep: true })

function updateMapping(targetId, datasetId) {
  localMapping.value[targetId] = datasetId
  emit('update:modelValue', { ...localMapping.value })
}
</script>
