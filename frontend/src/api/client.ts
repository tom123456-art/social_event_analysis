// 使用 Axios 管理 HTTP 请求及统一拦截器。
import axios from 'axios'
// 过滤无法直接展示的第三方错误信息。
import { chineseMessage } from '../utils/userText'

// 创建统一的 HTTP 客户端，所有前端请求都会使用 /api 前缀。
export const api = axios.create({
  // 所有请求使用后端统一接口前缀。
  baseURL: '/api',
  // 允许 ETL 等长任务请求等待最多两分钟。
  timeout: 120000
})

// 在发送请求前附加本地登录令牌。
api.interceptors.request.use(config => {
  // 读取当前浏览器保存的会话令牌。
  const token = localStorage.getItem('social_token')
  // 存在令牌时使用 Bearer 认证格式。
  if (token) config.headers.Authorization = `Bearer ${token}`
  // 将补充后的配置交回 Axios。
  return config
})

// 缓存相同的 GET 请求，避免同一页面同时重复读取数据库。
const GET_CACHE_TTL_MS = 15_000
// 同时保存进行中的请求 Promise 和缓存到期时间。
const getCache = new Map<string, { request: Promise<unknown>; expiresAt: number }>()

// 数据发生变化后清理缓存，确保下一次读取到最新结果。
export function clearGetCache() {
  // 清空所有地址的缓存，请求下次访问时重新发起。
  getCache.clear()
}

// 从请求异常中提取可展示的中文信息；未知异常统一使用调用方提供的提示。
export function getErrorMessage(error: unknown, fallback: string): string {
  // 为未知异常声明可安全探测的消息结构。
  const detail = error as { message?: unknown; response?: { data?: { message?: unknown } } }
  // 优先后端消息，其次异常消息，最后采用调用方中文备用提示。
  return chineseMessage(detail?.response?.data?.message, chineseMessage(detail?.message, fallback))
}

// 统一处理成功响应和网络异常，保证页面收到的是可直接展示的中文提示。
api.interceptors.response.use(
  (response) => {
    // 写入成功后，清除旧缓存，确保页面读取到最新数据。
    if (response.config.method?.toLowerCase() !== 'get') clearGetCache()
    // 保留完整响应，由业务请求函数统一解包。
    return response
  },
  (error) => {
    // 非登录注册请求收到未认证响应时清理过期会话。
    if (error.response?.status === 401 && !['/auth/login', '/auth/register'].includes(error.config?.url)) {
      // 删除令牌，防止后续请求继续发送失效凭据。
      localStorage.removeItem('social_token')
      // 删除旧用户展示信息。
      localStorage.removeItem('social_user')
      // 删除旧角色标记，避免路由继续按管理员身份放行。
      localStorage.removeItem('social_role')
      // 退出旧会话时一并清理其请求缓存。
      clearGetCache()
      // 跳转登录页，并保存当前页面作为登录后回跳地址。
      if (window.location.pathname !== '/login') window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`)
    }
    // 将请求超时转为用户可读提示。
    if (error.code === 'ECONNABORTED') {
      error.message = '请求超时，请稍后重试。'
    // 未收到响应通常意味着后端不可达或网络失败。
    } else if (!error.response) {
      error.message = '无法连接后端服务，请检查服务是否已启动。'
    } else {
      const hints: Record<number, string> = { 400: '请求参数不正确，请检查后重试。', 401: '登录已失效，请重新登录。', 403: '没有执行此操作的权限。', 404: '请求的服务或数据不存在。', 413: '上传文件过大，请减小文件后重试。', 500: '后端处理失败，请稍后重试。', 502: '后端服务暂时不可用，请稍后重试。', 503: '服务暂时不可用，请稍后重试。', 504: '后端处理超时，请稍后重试。' }
      // 优先保留合法中文业务消息，否则按 HTTP 状态使用备用提示。
      error.message = chineseMessage(error.response.data?.message, hints[error.response.status] || '请求失败，请稍后重试。')
      // 同步修正响应体，确保不同异常读取方式获得相同提示。
      if (error.response.data && typeof error.response.data === 'object') error.response.data.message = error.message
    }
    // 将已规范化的异常继续交给业务调用方。
    return Promise.reject(error)
  }
)

// 提取后端统一响应中的 data，并把失败响应转换为可显示的中文错误。
function unwrap<T>(response: any): T {
  // 读取 HTTP 响应中的业务响应体。
  const body = response.data
  // 业务响应码非零时转换为异常，而不是误当成功数据返回。
  if (body && typeof body.code === 'number' && body.code !== 0) {
    throw new Error(chineseMessage(body.message, '接口请求失败，请稍后重试。'))
  }
  // 兼容统一包装响应和未包装的原始响应体。
  return (body?.data ?? body) as T
}

// 发起 GET 请求；相同地址会复用请求缓存，避免重复访问数据库。
export async function getData<T = any>(url: string, force = false): Promise<T> {
  // 强制刷新时先丢弃当前地址缓存。
  if (force) getCache.delete(url)

  // 按完整 URL 查找相同参数的请求。
  const cached = getCache.get(url)
  // 缓存未过期时复用同一个 Promise，包括尚未完成的请求。
  if (cached && cached.expiresAt > Date.now()) return cached.request as Promise<T>
  // 删除过期条目后重新发起请求。
  if (cached) getCache.delete(url)

  // 发起 GET 请求并提取业务数据。
  const request = api.get(url).then(response => unwrap<T>(response))
  // 立即缓存 Promise，合并同时到达的同地址请求。
  getCache.set(url, { request, expiresAt: Date.now() + GET_CACHE_TTL_MS })
  // 失败请求不能作为缓存继续复用。
  request.catch(() => {
    // 只删除当前失败请求，不误删其后创建的新请求。
    if (getCache.get(url)?.request === request) getCache.delete(url)
  })
  // 返回当前请求或其后续解析结果。
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
