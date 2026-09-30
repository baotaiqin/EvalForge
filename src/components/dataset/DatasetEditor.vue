<template>
  <a-modal
    v-model:open="visible"
    :title="`编辑数据集 - ${dataset?.name || ''}`"
    width="1000px"
    @ok="handleSave"
    :confirm-loading="saving"
  >
    <div v-if="localItems.length" style="margin-top: 16px; max-height: 60vh; overflow-y: auto;">
      <div
        v-for="(item, index) in localItems"
        :key="item.id"
        style="border: 1px solid #f0f0f0; border-radius: 8px; padding: 16px; margin-bottom: 12px;"
      >
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
          <a-tag color="blue">第 {{ index + 1 }} 条</a-tag>
          <a-popconfirm title="确定删除这条数据吗？" @confirm="localItems.splice(index, 1)">
            <a-button danger size="small" type="text">
              <template #icon><DeleteOutlined /></template>
            </a-button>
          </a-popconfirm>
        </div>
        <a-form layout="vertical">
          <a-form-item label="用户提问">
            <a-textarea v-model:value="item.question" :rows="2" />
          </a-form-item>

          <!-- 图片编辑区域 -->
          <a-form-item label="图片">
            <div style="display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px;">
              <div
                v-for="(img, imgIndex) in item.images"
                :key="imgIndex"
                style="position: relative; width: 80px; height: 80px; border: 1px solid #d9d9d9; border-radius: 4px; overflow: hidden; background: #f0f0f0;"
              >
                <img :src="resolveImageUrl(img)" style="width: 100%; height: 100%; object-fit: cover; position: relative; z-index: 1;" />
                <a-button
                  type="text"
                  danger
                  size="small"
                  style="position: absolute; top: 0; right: 0; min-width: auto; padding: 0 4px; background: rgba(255,255,255,0.8);"
                  @click="item.images.splice(imgIndex, 1)"
                >
                  <template #icon><DeleteOutlined /></template>
                </a-button>
              </div>
            </div>
            <a-upload
              :file-list="[]"
              :before-upload="(file) => handleImageUpload(file, item)"
              accept=".jpg,.jpeg,.png,.gif,.bmp"
            >
              <a-button size="small">
                <template #icon><PlusOutlined /></template>
                添加图片
              </a-button>
            </a-upload>
          </a-form-item>

          <a-form-item label="预期回答">
            <a-textarea v-model:value="item.expectedAnswer" :rows="3" />
          </a-form-item>

          <!-- 标准答案PPT（用于PPT生成类Agent评测，与“预期回答”二选一） -->
          <a-form-item label="标准答案PPT（可选）">
            <div v-if="item.expected?.type === 'pptx'" style="margin-bottom: 8px;">
              <a-tag color="green">{{ item.expected.fileName }}</a-tag>
              <a-button type="text" danger size="small" @click="item.expected = null">
                <template #icon><DeleteOutlined /></template>
              </a-button>
            </div>
            <a-upload
              :file-list="[]"
              :before-upload="(file) => handlePptUpload(file, item)"
              accept=".ppt,.pptx"
            >
              <a-button size="small">
                <template #icon><PlusOutlined /></template>
                上传标准答案PPT
              </a-button>
            </a-upload>
          </a-form-item>
        </a-form>
      </div>
    </div>

    <a-button type="dashed" block @click="addItem">
      <template #icon><PlusOutlined /></template>
      添加数据项
    </a-button>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { useDatasetStore } from '@/stores/dataset.js'
import { BASE_URL } from '@/api/request.js'

function resolveImageUrl(img) {
  if (!img) return ''
  if (img.startsWith('data:') || img.startsWith('http')) return img
  return BASE_URL + img
}

const props = defineProps({
  open: Boolean,
  dataset: Object
})
const emit = defineEmits(['update:open', 'success'])

const datasetStore = useDatasetStore()
const visible = ref(false)
const saving = ref(false)
const localItems = ref([])

watch(() => props.open, (val) => {
  visible.value = val
  if (val && props.dataset?.items) {
    localItems.value = JSON.parse(JSON.stringify(props.dataset.items))
  } else if (val) {
    localItems.value = []
  }
})
watch(visible, (val) => emit('update:open', val))

function addItem() {
  localItems.value.push({
    id: `q-new-${Date.now()}`,
    question: '',
    images: [],
    expectedAnswer: ''
  })
}

// 图片上传处理（mock模式下使用base64预览）
function handleImageUpload(file, item) {
  const isImage = /\.(jpg|jpeg|png|gif|bmp)$/i.test(file.name)
  if (!isImage) {
    message.error('只支持上传图片格式文件')
    return false
  }
  const reader = new FileReader()
  reader.onload = (event) => {
    if (!item.images) item.images = []
    item.images.push(event.target.result)
  }
  reader.readAsDataURL(file)
  return false // 阻止自动上传
}

// 标准答案PPT上传处理：整个PPT文件以base64内嵌进 item.expected
function handlePptUpload(file, item) {
  const isPpt = /\.(ppt|pptx)$/i.test(file.name)
  if (!isPpt) {
    message.error('只支持上传 .ppt/.pptx 格式文件')
    return false
  }
  const reader = new FileReader()
  reader.onload = (event) => {
    item.expected = {
      type: 'pptx',
      fileName: file.name,
      data: event.target.result
    }
  }
  reader.readAsDataURL(file)
  return false
}

async function handleSave() {
  const emptyItems = localItems.value.filter(item => !item.question.trim())
  if (emptyItems.length) {
    message.warning('请填写所有问题内容')
    return
  }
  saving.value = true
  try {
    await datasetStore.updateDataset(props.dataset.id, { items: localItems.value })
    message.success('数据集更新成功')
    visible.value = false
    emit('success')
  } finally {
    saving.value = false
  }
}
</script>
