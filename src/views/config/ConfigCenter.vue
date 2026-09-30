<template>
  <div class="page-content">
    <div class="page-header">
      <h2>配置中心</h2>
    </div>

    <a-tabs v-model:activeKey="activeTab" size="large">
      <a-tab-pane key="env" tab="环境管理">
        <EnvManager />
      </a-tab-pane>
      <a-tab-pane key="model" tab="模型管理">
        <ModelManager />
      </a-tab-pane>
      <a-tab-pane key="criteria" tab="权重阈值设置">
        <JudgeCriteriaForm />
      </a-tab-pane>
      <a-tab-pane key="prompt" tab="提示词配置">
        <PromptConfigForm />
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useConfigStore } from '@/stores/config.js'
import EnvManager from '@/components/config/EnvManager.vue'
import ModelManager from '@/components/config/ModelManager.vue'
import JudgeCriteriaForm from '@/components/config/JudgeCriteriaForm.vue'
import PromptConfigForm from '@/components/config/PromptConfigForm.vue'

const configStore = useConfigStore()
const activeTab = ref('env')

onMounted(() => {
  // 并行加载，互不阻塞
  configStore.fetchEnvironments()
  configStore.fetchModels()
})
</script>
