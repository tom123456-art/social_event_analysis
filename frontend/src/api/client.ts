import axios from 'axios'

export const api = axios.create({
  baseURL: '/api',
  timeout: 120000
})

const getCache = new Map<string, Promise<unknown>>()

export function clearGetCache() {
  getCache.clear()
}

api.interceptors.response.use((response) => {
  // A successful write can change data used by any dashboard or admin view.
  if (response.config.method?.toLowerCase() !== 'get') clearGetCache()
  return response
})

function unwrap<T>(response: any): T {
  const body = response.data
  if (body && typeof body.code === 'number' && body.code !== 0) {
    throw new Error(body.message || '接口请求失败')
  }
  return (body?.data ?? body) as T
}

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

export async function postData<T = any>(url: string, data: any, config: Record<string, any> = {}): Promise<T> {
  const response = await api.post(url, data, config)
  return unwrap<T>(response)
}

export async function putData<T = any>(url: string, data: any): Promise<T> {
  const response = await api.put(url, data)
  return unwrap<T>(response)
}

export async function deleteData<T = any>(url: string): Promise<T> {
  const response = await api.delete(url)
  return unwrap<T>(response)
}
