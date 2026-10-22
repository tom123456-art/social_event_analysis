import { readSession, clearSession } from '../utils/session'
import { clearGetCache } from './client'

export async function streamAnswer<T>(url: string, body: unknown, onDelta: (text: string) => void, onStatus: (text: string) => void = () => {}): Promise<T> {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 360000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const response = await fetch(`/api${url}`, {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${readSession().token}` },
      body: JSON.stringify(body)
    })
    if (response.status === 401) {
      clearSession(); clearGetCache()
      window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`)
      throw new Error('登录已失效，请重新登录。')
    }
    if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream'))
      throw new Error('无法建立流式连接，请确认后端已更新并重启。')
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = '', event = '', data: string[] = []
    let result: T | undefined
    let completed = false
    const line = (value: string) => {
      if (value === '') {
        if (!data.length) { event = ''; return }
        const payload = JSON.parse(data.join('\n'))
        if (event === 'delta') onDelta(payload.content || '')
        else if (event === 'status') onStatus(payload.message || '')
        else if (event === 'error') throw new Error(payload.message || '生成回答失败，请重试。')
        else if (event === 'done') { result = payload as T; completed = true }
        event = ''; data = []
      } else if (value.startsWith('event:')) event = value.slice(6).trim()
      else if (value.startsWith('data:')) data.push(value.slice(5).trimStart())
    }
    while (!completed) {
      const chunk = await reader.read()
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      let end: number
      while ((end = buffer.indexOf('\n')) >= 0) {
        line(buffer.slice(0, end).replace(/\r$/, '')); buffer = buffer.slice(end + 1)
      }
      if (chunk.done) break
    }
    if (!completed || !result) throw new Error('回答连接提前中断，请重新尝试。')
    clearGetCache()
    return result
  } finally {
    clearTimeout(timeout)
    await reader?.cancel().catch(() => {})
    controller.abort()
  }
}
