<template>
  <div class="page-content">
    <div class="page-header">
      <h2>模型分组</h2>
      <a-space>
        <a-button @click="reportCenterVisible = true">
          <template #icon><HistoryOutlined /></template>
          报告
        </a-button>
        <a-button type="primary" @click="openCreate">
          <template #icon><PlusOutlined /></template>
          新建分组
        </a-button>
      </a-space>
    </div>

    <a-table
      :columns="columns"
      :data-source="modelEvalStore.groups"
      :loading="modelEvalStore.loading"
      row-key="id"
      :pagination="{ pageSize: 10 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <a @click="router.push(`/model-eval/groups/${record.id}`)">{{ record.name }}</a>
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a @click="openEdit(record)">编辑</a>
            <a-popconfirm
              title="确定复制该模型分组吗？将生成一份包含全部子评测配置的新分组。"
              ok-text="复制"
              cancel-text="取消"
              @confirm="handleDuplicate(record.id)"
            >
              <a>复制</a>
            </a-popconfirm>
            <a-popconfirm title="确定删除该模型分组吗？" ok-text="删除" cancel-text="取消" @confirm="handleDelete(record.id)">
              <a style="color: #ff4d4f;">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="formVisible"
      :title="editingGroup ? '编辑分组' : '新建分组'"
      cancel-text="取消"
      ok-text="保存"
      @ok="handleSubmit"
    >
      <a-form :model="formState" layout="vertical">
        <a-form-item label="分组名称（模型组）" required>
          <a-input v-model:value="formState.name" placeholder="请输入分组名称" />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model:value="formState.remark" placeholder="备注（可选）" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>

    <ReportCenterModal v-model:open="reportCenterVisible" />
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { PlusOutlined, HistoryOutlined } from '@ant-design/icons-vue'
import { useModelEvalStore } from '@/stores/modelEval.js'
import ReportCenterModal from '@/components/modelEval/ReportCenterModal.vue'

const router = useRouter()
const modelEvalStore = useModelEvalStore()
const formVisible = ref(false)
const editingGroup = ref(null)
const formState = reactive({ name: '', remark: '' })
const reportCenterVisible = ref(false)

const columns = [
  { title: '分组名称', key: 'name', dataIndex: 'name' },
  { title: '备注', key: 'remark', dataIndex: 'remark', ellipsis: true },
  { title: '创建时间', key: 'createdAt', dataIndex: 'createdAt', width: 200 },
  { title: '操作', key: 'action', width: 180 }
]

onMounted(() => {
  modelEvalStore.fetchGroups().catch(error => message.error(error.message || '加载模型分组失败'))
})

function openCreate() {
  editingGroup.value = null
  formState.name = ''
  formState.remark = ''
  formVisible.value = true
}

function openEdit(record) {
  editingGroup.value = record
  formState.name = record.name || ''
  formState.remark = record.remark || ''
  formVisible.value = true
}

async function handleSubmit() {
  const name = formState.name.trim()
  if (!name) {
    message.warning('请输入分组名称')
    return
  }
  const data = { name, remark: formState.remark }
  try {
    if (editingGroup.value) {
      await modelEvalStore.updateGroup(editingGroup.value.id, data)
      message.success('分组已更新')
    } else {
      await modelEvalStore.createGroup(data)
      message.success('分组已创建')
    }
    formVisible.value = false
  } catch (error) {
    message.error(error.message || '保存分组失败')
  }
}

async function handleDelete(id) {
  try {
    await modelEvalStore.deleteGroup(id)
    message.success('分组已删除')
  } catch (error) {
    message.error(error.message || '删除分组失败')
  }
}

async function handleDuplicate(id) {
  try {
    const newGroup = await modelEvalStore.duplicateGroup(id)
    message.success(`已复制为新分组：${newGroup.name}`)
    router.push(`/model-eval/groups/${newGroup.id}`)
  } catch (error) {
    message.error(error.message || '复制分组失败')
  }
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}
</style>
