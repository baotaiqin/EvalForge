<template>
  <a-modal
    :open="open"
    title="复制子评测到其他分组"
    :confirm-loading="submitting"
    @ok="handleCopy"
    @cancel="handleClose"
  >
    <a-form layout="vertical">
      <a-form-item label="目标分组" required>
        <a-select v-model:value="targetGroupId" placeholder="请选择目标分组" style="width: 100%;">
          <a-select-option v-for="group in availableGroups" :key="group.id" :value="group.id">
            {{ group.name }}
          </a-select-option>
        </a-select>
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { useModelEvalStore } from '@/stores/modelEval.js'

const props = defineProps({
  open: Boolean,
  subEval: { type: Object, default: null },
  currentGroupId: { type: String, required: true }
})
const emit = defineEmits(['update:open', 'success'])
const modelEvalStore = useModelEvalStore()
const targetGroupId = ref()
const submitting = ref(false)
const availableGroups = computed(() =>
  modelEvalStore.groups.filter(group => group.id !== props.currentGroupId)
)

watch(() => props.open, visible => {
  if (visible && !modelEvalStore.groups.length) modelEvalStore.fetchGroups()
  targetGroupId.value = undefined
})

function handleClose() {
  emit('update:open', false)
}

async function handleCopy() {
  if (!props.subEval?.id || !targetGroupId.value) {
    message.warning('请选择目标分组')
    return
  }
  submitting.value = true
  try {
    await modelEvalStore.duplicateSubEvaluation(props.subEval.id, targetGroupId.value)
    message.success('子评测已复制')
    handleClose()
    emit('success')
  } catch (error) {
    message.error(error.message || '复制子评测失败')
  } finally {
    submitting.value = false
  }
}
</script>
