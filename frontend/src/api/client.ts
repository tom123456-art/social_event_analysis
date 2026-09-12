import axios from 'axios'

export const api = axios.create({
  baseURL: '/api',
  timeout: 120000
})

function unwrap<T>(response: any): T {
  const body = response.data
  if (body && typeof body.code === 'number' && body.code !== 0) {
    throw new Error(body.message || '接口请求失败')
  }
  return (body?.data ?? body) as T
}

export async function getData<T = any>(url: string): Promise<T> {
  const response = await api.get(url)
  return unwrap<T>(response)
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
