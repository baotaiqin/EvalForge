<template>
  <div>
    <a-card title="文本类评分维度权重">
      <a-form layout="vertical">
        <div
          v-for="(dim, index) in localCriteria.dimensions"
          :key="dim.key"
          style="margin-bottom: 16px;"
        >
          <a-row :gutter="16" align="middle">
            <a-col :span="4">
              <a-tag color="blue" style="font-size: 14px; padding: 4px 12px;">{{ dim.label }}</a-tag>
            </a-col>
            <a-col :span="14">
              <a-slider v-model:value="localCriteria.dimensions[index].weight" :min="0" :max="1" :step="0.05" />
            </a-col>
            <a-col :span="4">
              <a-input-number
                v-model:value="localCriteria.dimensions[index].weight"
                :min="0"
                :max="1"
                :step="0.05"
                style="width: 100%;"
              />
            </a-col>
            <a-col :span="2">
              <a-button
                danger
                size="small"
                @click="removeDimension(index)"
                :disabled="localCriteria.dimensions.length <= 1"
              >
                <template #icon><DeleteOutlined /></template>
              </a-button>
            </a-col>
          </a-row>
        </div>
        <a-button type="dashed" block @click="addDimension" style="margin-bottom: 16px;">
          <template #icon><PlusOutlined /></template>
          添加评分维度
        </a-button>
        <div style="margin-bottom: 16px;">
          <a-alert
            :type="weightSum === 1 ? 'success' : 'warning'"
            :message="`当前权重总和：${weightSum.toFixed(2)}${weightSum !== 1 ? '（建议调整为 1.0）' : ' ✓'}`"
            show-icon
          />
        </div>
        <div style="text-align: right;">
          <a-button type="primary" @click="handleSave" :loading="saving">保存配置</a-button>
        </div>
      </a-form>
    </a-card>

    <a-card title="PPT 对比评分维度权重" style="margin-top: 24px;">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="维度固定为「内容/结构/视觉」，对应评分算法不同，仅支持调整权重，不支持新增或删除维度"
      />
      <a-form layout="vertical">
        <div
          v-for="(dim, index) in localCriteria.pptDimensions"
          :key="dim.key"
          style="margin-bottom: 16px;"
        >
          <a-row :gutter="16" align="middle">
            <a-col :span="4">
              <a-tag color="purple" style="font-size: 14px; padding: 4px 12px;">{{ dim.label }}</a-tag>
            </a-col>
            <a-col :span="16">
              <a-slider v-model:value="localCriteria.pptDimensions[index].weight" :min="0" :max="1" :step="0.05" />
            </a-col>
            <a-col :span="4">
              <a-input-number
                v-model:value="localCriteria.pptDimensions[index].weight"
                :min="0"
                :max="1"
                :step="0.05"
                style="width: 100%;"
              />
            </a-col>
          </a-row>
        </div>
        <div style="margin-bottom: 16px;">
          <a-alert
            :type="pptWeightSum === 1 ? 'success' : 'warning'"
            :message="`当前权重总和：${pptWeightSum.toFixed(2)}${pptWeightSum !== 1 ? '（建议调整为 1.0）' : ' ✓'}`"
            show-icon
          />
        </div>
        <div style="text-align: right;">
          <a-button type="primary" @click="handleSavePptDimensions" :loading="savingPptDimensions">保存配置</a-button>
        </div>
      </a-form>
    </a-card>

    <a-card title="HTML 报告对比评分维度权重" style="margin-top: 24px;">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="维度固定为「内容/结构/视觉」，对应评分算法不同，仅支持调整权重，不支持新增或删除维度"
      />
      <a-form layout="vertical">
        <div
          v-for="(dim, index) in localCriteria.htmlDimensions"
          :key="dim.key"
          style="margin-bottom: 16px;"
        >
          <a-row :gutter="16" align="middle">
            <a-col :span="4">
              <a-tag color="cyan" style="font-size: 14px; padding: 4px 12px;">{{ dim.label }}</a-tag>
            </a-col>
            <a-col :span="16">
              <a-slider v-model:value="localCriteria.htmlDimensions[index].weight" :min="0" :max="1" :step="0.05" />
            </a-col>
            <a-col :span="4">
              <a-input-number
                v-model:value="localCriteria.htmlDimensions[index].weight"
                :min="0"
                :max="1"
                :step="0.05"
                style="width: 100%;"
              />
            </a-col>
          </a-row>
        </div>
        <div style="margin-bottom: 16px;">
          <a-alert
            :type="htmlWeightSum === 1 ? 'success' : 'warning'"
            :message="`当前权重总和：${htmlWeightSum.toFixed(2)}${htmlWeightSum !== 1 ? '（建议调整为 1.0）' : ' ✓'}`"
            show-icon
          />
        </div>
        <div style="text-align: right;">
          <a-button type="primary" @click="handleSaveHtmlDimensions" :loading="savingHtmlDimensions">保存配置</a-button>
        </div>
      </a-form>
    </a-card>

    <a-card title="SemiMind 多模态会话轮换设置" style="margin-top: 24px;">
      <a-alert
        type="info"
        show-icon
        style="margin-bottom: 16px;"
        message="带图片/文件的题目在 SemiMind 平台上会以真实对话形式发起，同一对话窗口累计消息过多可能导致上下文超限。此处设置每题累计达到该数量后自动切换新的对话窗口；重新测评/断点续跑/单题重测/低于分数重跑等每次独立触发的执行也都会从新窗口开始。"
      />
      <a-form layout="vertical">
        <a-form-item label="每个对话窗口最多题数">
          <a-input-number
            v-model:value="localCriteria.semimindWindowSize"
            :min="1"
            :step="1"
            style="width: 200px;"
          />
        </a-form-item>
        <div style="text-align: right;">
          <a-button type="primary" @click="handleSaveSemimindWindowSize" :loading="savingSemimindWindowSize">保存配置</a-button>
        </div>
      </a-form>
    </a-card>

    <!-- 添加维度弹窗 -->
    <a-modal
      v-model:open="addDimModalVisible"
      title="添加评分维度"
      @ok="confirmAddDimension"
    >
      <a-form layout="vertical" style="margin-top: 16px;">
        <a-form-item label="维度标识（英文）">
          <a-input v-model:value="newDimension.key" placeholder="例如：fluency" />
        </a-form-item>
        <a-form-item label="维度名称（中文）">
          <a-input v-model:value="newDimension.label" placeholder="例如：流畅性" />
        </a-form-item>
        <a-form-item label="权重">
          <a-input-number v-model:value="newDimension.weight" :min="0" :max="1" :step="0.05" style="width: 100%;" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useConfigStore } from '@/stores/config.js'
import { message } from 'ant-design-vue'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons-vue'
import { SCORE_DIMENSIONS, PPT_SCORE_DIMENSIONS, HTML_SCORE_DIMENSIONS } from '@/utils/constants.js'

const configStore = useConfigStore()
const saving = ref(false)
const savingPptDimensions = ref(false)
const savingHtmlDimensions = ref(false)
const savingSemimindWindowSize = ref(false)
const addDimModalVisible = ref(false)

const localCriteria = reactive({
  dimensions: [],
  pptDimensions: [],
  htmlDimensions: [],
  semimindWindowSize: 50
})

const newDimension = reactive({
  key: '',
  label: '',
  weight: 0.1
})

const weightSum = computed(() => localCriteria.dimensions.reduce((sum, dim) => sum + Number(dim.weight || 0), 0))
const pptWeightSum = computed(() => localCriteria.pptDimensions.reduce((sum, dim) => sum + Number(dim.weight || 0), 0))
const htmlWeightSum = computed(() => localCriteria.htmlDimensions.reduce((sum, dim) => sum + Number(dim.weight || 0), 0))

onMounted(async () => {
  try {
    await configStore.fetchCriteria()
    const criteria = configStore.criteria || {}
    localCriteria.dimensions = JSON.parse(JSON.stringify(criteria.dimensions || SCORE_DIMENSIONS))
    localCriteria.pptDimensions = JSON.parse(JSON.stringify(criteria.pptDimensions || PPT_SCORE_DIMENSIONS))
    localCriteria.htmlDimensions = JSON.parse(JSON.stringify(criteria.htmlDimensions || HTML_SCORE_DIMENSIONS))
    localCriteria.semimindWindowSize = criteria.semimindWindowSize || 50
  } catch (err) {
    message.error('加载评判标准失败：' + (err.message || ''))
  }
})

function addDimension() {
  newDimension.key = ''
  newDimension.label = ''
  newDimension.weight = 0.1
  addDimModalVisible.value = true
}

function confirmAddDimension() {
  if (!newDimension.key || !newDimension.label) {
    message.warning('请填写维度标识和名称')
    return
  }
  if (localCriteria.dimensions.some(dim => dim.key === newDimension.key)) {
    message.warning('维度标识已存在')
    return
  }
  localCriteria.dimensions.push({ ...newDimension })
  addDimModalVisible.value = false
}

function removeDimension(index) {
  localCriteria.dimensions.splice(index, 1)
}

async function handleSave() {
  saving.value = true
  try {
    await configStore.updateCriteria({ dimensions: localCriteria.dimensions })
    message.success('评判标准配置已保存')
  } finally {
    saving.value = false
  }
}

async function handleSavePptDimensions() {
  savingPptDimensions.value = true
  try {
    await configStore.updateCriteria({ pptDimensions: localCriteria.pptDimensions })
    message.success('PPT 评分权重已保存')
  } finally {
    savingPptDimensions.value = false
  }
}

async function handleSaveHtmlDimensions() {
  savingHtmlDimensions.value = true
  try {
    await configStore.updateCriteria({ htmlDimensions: localCriteria.htmlDimensions })
    message.success('HTML 评分权重已保存')
  } finally {
    savingHtmlDimensions.value = false
  }
}

async function handleSaveSemimindWindowSize() {
  savingSemimindWindowSize.value = true
  try {
    await configStore.updateCriteria({ semimindWindowSize: localCriteria.semimindWindowSize })
    message.success('SemiMind 会话轮换设置已保存')
  } finally {
    savingSemimindWindowSize.value = false
  }
}
</script>
