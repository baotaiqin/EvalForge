import { registerMock } from './request.js'
import * as XLSX from 'xlsx'
import { mockDatasets } from '@/mock/dataset.js'

let datasets = JSON.parse(JSON.stringify(mockDatasets))
let dsIdCounter = datasets.length + 1

registerMock('GET', '/api/datasets', () => {
  return { code: 0, data: datasets.map(({ items, ...rest }) => ({ ...rest })) }
})

registerMock('GET', '/api/datasets/:id', ({ pathParams }) => {
  const id = pathParams[0]
  const ds = datasets.find(d => d.id === id)
  if (ds) return { code: 0, data: ds }
  return { code: 404, message: '数据集不存在' }
})

registerMock('POST', '/api/datasets', ({ data }) => {
  const newDs = {
    ...data,
    id: `ds-${String(dsIdCounter++).padStart(3, '0')}`,
    itemCount: data.items?.length || 0,
    createdAt: new Date().toLocaleString(),
    updatedAt: new Date().toLocaleString()
  }
  datasets.push(newDs)
  return { code: 0, data: newDs }
})

registerMock('PUT', '/api/datasets/:id', ({ data, pathParams }) => {
  const id = pathParams[0]
  const index = datasets.findIndex(d => d.id === id)
  if (index !== -1) {
    datasets[index] = { ...datasets[index], ...data, updatedAt: new Date().toLocaleString() }
    if (data.items) datasets[index].itemCount = data.items.length
    return { code: 0, data: datasets[index] }
  }
  return { code: 404, message: '数据集不存在' }
})

registerMock('DELETE', '/api/datasets/:id', ({ pathParams }) => {
  const id = pathParams[0]
  datasets = datasets.filter(d => d.id !== id)
  return { code: 0 }
})

// 轻量级浏览器端解析器：让演示模式无需文件解析服务也能导入常用表格数据。
registerMock('POST', '/api/datasets/parse', async ({ data }) => {
  const file = data?.get?.('file')
  if (!file) return { code: 400, message: '请选择要解析的文件' }
  const extension = file.name?.split('.').pop()?.toLowerCase()
  let rows = []

  try {
    if (['jsonl', 'ndjson'].includes(extension)) {
      const text = await file.text()
      rows = text.split(/\r?\n/).filter(line => line.trim()).map(line => JSON.parse(line))
    } else if (extension === 'json') {
      const parsed = JSON.parse(await file.text())
      rows = Array.isArray(parsed) ? parsed : (parsed.items || parsed.samples || parsed.data || [])
    } else if (['xls', 'xlsx'].includes(extension)) {
      const workbook = XLSX.read(await file.arrayBuffer(), { type: 'array' })
      const firstSheet = workbook.Sheets[workbook.SheetNames[0]]
      rows = firstSheet ? XLSX.utils.sheet_to_json(firstSheet, { defval: '' }) : []
    } else {
      return { code: 422, message: '演示模式只解析 JSON、JSONL/NDJSON 和 Excel 文件；Word/ZIP 解析需要后端服务' }
    }
  } catch (error) {
    return { code: 400, message: `文件内容无法解析：${error.message}` }
  }

  const items = rows.map((row, index) => {
    if (typeof row === 'string') return { id: `import-${index + 1}`, question: row, images: [], expectedAnswer: '' }
    const expected = row.expectedAnswer ?? row.expected_answer ?? row.expected ?? row.answer ?? ''
    return {
      ...row,
      id: row.id || `import-${index + 1}`,
      question: String(row.question ?? row.prompt ?? row.input ?? row.query ?? ''),
      images: row.images || [],
      expectedAnswer: typeof expected === 'string' ? expected : JSON.stringify(expected)
    }
  }).filter(item => item.question.trim())

  return {
    code: 0,
    data: {
      items,
      fileName: file.name,
      datasetType: 'text',
      hasMultimodal: items.some(item => item.images?.length || item.files?.length),
      modalities: ['text'],
      schemaVersion: 'v1',
      sourceFormat: extension
    }
  }
})
