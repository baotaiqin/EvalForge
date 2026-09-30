<template>
  <a-modal
    v-model:open="visible"
    :title="title"
    width="800px"
    :footer="null"
    @cancel="handleClose"
  >
    <div v-if="loading" style="text-align: center; padding: 40px;">
      <a-spin tip="加载文件中..." />
    </div>

    <div v-else-if="error" style="padding: 20px; text-align: center;">
      <a-alert type="error" :message="error" show-icon />
      <div v-if="fileUrl" style="margin-top: 16px;">
        <a-button type="primary" @click="openExternal">
          <template #icon><LinkOutlined /></template>
          尝试打开链接
        </a-button>
        <div style="margin-top: 8px; word-break: break-all; color: #999; font-size: 12px;">{{ fileUrl }}</div>
      </div>
    </div>

    <div v-else-if="isExternal" style="padding: 12px 0;">
      <p style="color: #666;">此为外部链接，请点击下方按钮在新窗口中查看。</p>
      <a-button type="primary" @click="openExternal">
        <template #icon><LinkOutlined /></template>
        打开链接
      </a-button>
      <div style="margin-top: 8px; word-break: break-all; color: #999; font-size: 12px;">{{ fileUrl }}</div>
    </div>

    <div v-else-if="isImage" style="text-align: center; padding: 12px 0;">
      <img :src="content" style="max-width: 100%; max-height: 70vh; border-radius: 4px;" />
    </div>

    <div v-else-if="isText" style="padding: 12px 0;">
      <a-textarea
        :value="content"
        :rows="20"
        readonly
        style="font-family: monospace; font-size: 13px; background: #f6f8fa;"
      />
    </div>

    <div v-else style="padding: 20px; text-align: center;">
      <p style="color: #666;">该文件类型暂不支持在线预览</p>
      <a-button type="primary" @click="openExternal">
        <template #icon><DownloadOutlined /></template>
        下载文件
      </a-button>
    </div>
  </a-modal>
</template>

<script setup>
import { ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { LinkOutlined, DownloadOutlined } from '@ant-design/icons-vue'
import { api, BASE_URL } from '@/api/request.js'
import '@/api/mockFiles.js'

const props = defineProps({
  open: Boolean,
  url: { type: String, default: '' },
  fileName: { type: String, default: '' }
})
const emit = defineEmits(['update:open'])

const visible = ref(false)
const loading = ref(false)
const error = ref('')
const isExternal = ref(false)
const isImage = ref(false)
const isText = ref(false)
const content = ref('')
const title = ref('文件预览')
const fileUrl = ref('')

watch(() => props.open, (val) => {
  visible.value = val
  if (val && props.url) {
    loadFile(props.url, props.fileName)
  }
})
watch(visible, (val) => emit('update:open', val))

async function loadFile(rawUrl, name) {
  loading.value = true
  error.value = ''
  isExternal.value = false
  isImage.value = false
  isText.value = false
  content.value = ''

  // 补全相对路径为绝对 URL
  let url = rawUrl
  if (url && !url.startsWith('data:') && !url.startsWith('http://') && !url.startsWith('https://') && !url.startsWith('file://')) {
    if (url.startsWith('/') || !url.includes('://')) {
      url = BASE_URL + (url.startsWith('/') ? '' : '/') + url
    }
  }
  fileUrl.value = url
  title.value = name || '文件预览'

  try {
    if (url.startsWith('http://') || url.startsWith('https://') || url.startsWith('data:')) {
      // 外部 URL / base64：直接判断类型
      isExternal.value = url.startsWith('http://') || url.startsWith('https://')
      if (!isExternal.value) {
        // base64 图片
        isImage.value = true
        content.value = url
      }
      loading.value = false
      return
    }

    // file:// 协议交给浏览器处理
    if (url.startsWith('file://')) {
      isExternal.value = true
      loading.value = false
      return
    }

    // 本地路径：调用后端预览接口
    const res = await api.get('/api/files/preview', { url })
    if (res.code === 0 && res.data) {
      const data = res.data
      if (data.isExternal) {
        isExternal.value = true
      } else if (data.isImage) {
        isImage.value = true
        content.value = data.content
      } else if (data.isText) {
        isText.value = true
        content.value = data.content
      }
      if (data.fileName) title.value = data.fileName
    } else {
      error.value = res.message || '文件加载失败'
    }
  } catch (err) {
    error.value = '文件加载失败：' + (err.message || '网络错误')
  } finally {
    loading.value = false
  }
}

function openExternal() {
  if (!fileUrl.value) return
  window.open(fileUrl.value, '_blank')
}

function handleClose() {
  visible.value = false
}
</script>

