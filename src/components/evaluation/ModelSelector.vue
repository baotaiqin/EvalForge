<template>
  <div>
    <a-select
      v-model:value="selected"
      mode="multiple"
      placeholder="选择模型（可多选）"
      style="width: 100%;"
      :options="modelOptions"
      @change="handleChange"
    />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useConfigStore } from '@/stores/config.js'

const props = defineProps({
  modelValue: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const configStore = useConfigStore()
const selected = ref([])
const modelOptions = computed(() => configStore.models.map(model => ({
  label: `${model.name} (${model.provider})`,
  value: model.id
})))

watch(() => props.modelValue, value => {
  selected.value = (value || []).map(item => item.modelId || item.id)
}, { immediate: true, deep: true })

function handleChange(ids) {
  const targets = ids.map(id => {
    const model = configStore.models.find(item => item.id === id)
    if (!model) return null
    return {
      id: model.id,
      modelId: model.id,
      modelName: model.name,
      modelProvider: model.provider,
      label: model.name
    }
  }).filter(Boolean)
  emit('update:modelValue', targets)
}
</script>
