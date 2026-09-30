import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/request.js'
import '@/api/dataset.js'

export const useDatasetStore = defineStore('dataset', () => {
  const datasets = ref([])
  const currentDataset = ref(null)
  const loading = ref(false)

  async function fetchDatasets() {
    loading.value = true
    try {
      const res = await api.get('/api/datasets')
      if (res.code === 0) {
        datasets.value = res.data || []
      }
    } finally {
      loading.value = false
    }
  }

  async function fetchDatasetDetail(id) {
    const res = await api.get(`/api/datasets/${id}`)
    if (res.code !== 0) throw new Error(res.message || '获取数据集详情失败')
    currentDataset.value = res.data
    return res.data
  }

  async function createDataset(data) {
    const res = await api.post('/api/datasets', data)
    if (res.code !== 0) throw new Error(res.message || '创建数据集失败')
    datasets.value.push(res.data)
    return res.data
  }

  async function updateDataset(id, data) {
    const res = await api.put(`/api/datasets/${id}`, data)
    if (res.code !== 0) throw new Error(res.message || '更新数据集失败')
    const index = datasets.value.findIndex(d => d.id === id)
    if (index !== -1) datasets.value[index] = { ...datasets.value[index], ...res.data }
    return res.data
  }

  async function deleteDataset(id) {
    const res = await api.delete(`/api/datasets/${id}`)
    if (res.code !== 0) throw new Error(res.message || '删除数据集失败')
    datasets.value = datasets.value.filter(d => d.id !== id)
  }

  return {
    datasets, currentDataset, loading,
    fetchDatasets, fetchDatasetDetail, createDataset, updateDataset, deleteDataset
  }
})
