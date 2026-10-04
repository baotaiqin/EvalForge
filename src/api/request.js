import axios from 'axios'

// ===== 模式切换 =====
// true: 使用前端Mock数据（无需后端）
// false: 对接真实后端API
export const IS_MOCK = import.meta.env.VITE_USE_MOCK !== 'false'

// 后端 API 地址:
// 1. 优先读取 Vite 环境变量 VITE_API_BASE_URL
// 2. 如果未配置，则默认使用同源地址，适合前后端同域部署或通过代理转发 /api
//
// 本地开发可在项目根目录的 .env.local 中配置:
// VITE_API_BASE_URL=http://localhost:8081
const rawBaseUrl = import.meta.env.VITE_API_BASE_URL || ''

export const BASE_URL = rawBaseUrl.replace(/\/+$/, '')

const axiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 180000 // Agent对话可能较慢（三步调用），超时设为180秒
})

function delay(ms = 300) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

let mockHandlers = {}

export function registerMock(method, url, handler) {
  mockHandlers[`${method.toUpperCase()} ${url}`] = handler
}

export async function request(config) {
  const { method = 'GET', url, data, params, signal } = config
  const [path, queryString = ''] = String(url || '').split('?')
  const resolvedParams = { ...Object.fromEntries(new URLSearchParams(queryString)), ...(params || {}) }

  if (IS_MOCK) {
    await delay(200 + Math.random() * 300)

    // 尝试精确匹配
    const key = `${method.toUpperCase()} ${path}`
    if (mockHandlers[key]) {
      return mockHandlers[key]({ data, params: resolvedParams, url: path })
    }

    // 尝试路径参数匹配（例如 /api/config/environments/:id）
    for (const [pattern, handler] of Object.entries(mockHandlers)) {
      const [pMethod, pUrl] = pattern.split(' ')
      if (pMethod !== method.toUpperCase()) continue

      const regex = new RegExp('^' + pUrl.replace(/:([^/]+)/g, '([^/]+)') + '$')
      const match = path.match(regex)
      if (match) {
        return handler({ data, params: resolvedParams, url: path, pathParams: match.slice(1) })
      }
    }

    console.warn(`[Mock] No handler for ${method} ${url}`)
    return { code: 404, message: 'Not found' }
  }

  // 真实后端请求
  try {
    const response = await axiosInstance.request({
      method,
      url,
      data,
      params,
      signal
    })
    return response.data
  } catch (error) {
    console.error('API Error:', error)
    throw error
  }
}

// 快捷方法（第三个参数 opts 可传 signal 等额外选项）
export const api = {
  get: (url, params, opts) => request({ method: 'GET', url, params, ...opts }),
  post: (url, data, opts) => request({ method: 'POST', url, data, ...opts }),
  put: (url, data, opts) => request({ method: 'PUT', url, data, ...opts }),
  delete: (url, data, opts) => request({ method: 'DELETE', url, data, ...opts })
}

// 文件上传专用方法（使用FormData）
export function uploadFile(url, formData, onProgress) {
  if (IS_MOCK) return request({ method: 'POST', url, data: formData })
  return axiosInstance.post(url, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000, // 文件上传60秒超时
    onUploadProgress: onProgress
  }).then(res => res.data)
}



