import axios from 'axios'

// 创建统一的 HTTP 客户端，所有前端请求都会使用 /api 前缀。
export const api = axios.create({
  baseURL: '/api',
  timeout: 120000
})

// 缓存相同的 GET 请求，避免同一页面同时重复读取数据库。
const getCache = new Map<string, Promise<unknown>>()

// 数据发生变化后清理缓存，确保下一次读取到最新结果。
export function clearGetCache() {
  getCache.clear()
}

// 从请求异常中提取可展示的中文信息；未知异常统一使用调用方提供的提示。
export function getErrorMessage(error: unknown, fallback: string): string {
  const message = (error as { message?: unknown })?.message
  return typeof message === 'string' && message.trim() ? message : fallback
}

// 统一处理成功响应和网络异常，保证页面收到的是可直接展示的中文提示。
api.interceptors.response.use(
  (response) => {
    // 写入成功后，清除旧缓存，确保页面读取到最新数据。
    if (response.config.method?.toLowerCase() !== 'get') clearGetCache()
    return response
  },
  (error) => {
    if (error.code === 'ECONNABORTED') {
      error.message = '请求超时，请稍后重试。'
    } else if (!error.response) {
      error.message = '无法连接后端服务，请检查服务是否已启动。'
    } else {
      error.message = error.response.data?.message || `请求失败（状态码 ${error.response.status}）。`
    }
    return Promise.reject(error)
  }
)

// 提取后端统一响应中的 data，并把失败响应转换为可显示的中文错误。
function unwrap<T>(response: any): T {
  const body = response.data
  if (body && typeof body.code === 'number' && body.code !== 0) {
    throw new Error(body.message || '接口请求失败，请稍后重试。')
  }
  return (body?.data ?? body) as T
}

// 发起 GET 请求；相同地址会复用请求缓存，避免重复访问数据库。
export async function getData<T = any>(url: string, force = false): Promise<T> {
  if (force) getCache.delete(url)

  let request = getCache.get(url)
  if (!request) {
    request = api.get(url).then(response => unwrap<T>(response))
    getCache.set(url, request)
    request.catch(() => {
      if (getCache.get(url) === request) getCache.delete(url)
    })
  }

  return request as Promise<T>
}

// 提交新增数据，并在成功后清理所有 GET 缓存。
export async function postData<T = any>(url: string, data: any, config: Record<string, any> = {}): Promise<T> {
  const response = await api.post(url, data, config)
  return unwrap<T>(response)
}

// 提交修改数据，并返回后端处理结果。
export async function putData<T = any>(url: string, data: any): Promise<T> {
  const response = await api.put(url, data)
  return unwrap<T>(response)
}

// 删除数据，并返回后端处理结果。
export async function deleteData<T = any>(url: string): Promise<T> {
  const response = await api.delete(url)
  return unwrap<T>(response)
}
