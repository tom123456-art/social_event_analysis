import { readSession, clearSession } from '../utils/session'
import { clearGetCache } from './client'

// 以 Server-Sent Events 方式向后端发起流式问答请求，逐段接收生成内容并在结束后返回完整结果。
export async function streamAnswer<T>(url: string, body: unknown, onDelta: (text: string) => void, onStatus: (text: string) => void = () => {}): Promise<T> {
  // 创建中止控制器，用于超时或结束时取消请求；默认等待六分钟。
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 360000)
  // 保存流读取器引用，便于结束后释放底层连接。
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    // 携带登录令牌发起 POST 请求，声明接受事件流响应。
    const response = await fetch(`/api${url}`, {
      method: 'POST', signal: controller.signal,
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream', Authorization: `Bearer ${readSession().token}` },
      body: JSON.stringify(body)
    })
    // 会话失效时清理本地登录态并跳转登录页，要求用户重新登录。
    if (response.status === 401) {
      clearSession(); clearGetCache()
      window.location.assign(`/login?redirect=${encodeURIComponent(window.location.pathname)}`)
      throw new Error('登录已失效，请重新登录。')
    }
    // 响应不可用或不是事件流时，提示后端可能未升级或未重启。
    if (!response.ok || !response.body || !response.headers.get('content-type')?.includes('text/event-stream'))
      throw new Error('无法建立流式连接，请确认后端已更新并重启。')
    // 获取流读取器并准备按 UTF-8 解码二进制分片。
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    // buffer 累积未解析的文本；event 保存当前事件名；data 收集当前事件的数据行。
    let buffer = '', event = '', data: string[] = []
    // result 保存 done 事件返回的最终结果；completed 标记是否已收到结束事件。
    let result: T | undefined
    let completed = false
    // 处理一行 SSE 文本：空行触发事件分发，event: 行更新事件名，data: 行追加数据。
    const line = (value: string) => {
      if (value === '') {
        // 空行且无数据时仅重置事件名，避免误解析。
        if (!data.length) { event = ''; return }
        // 将多行 data 合并为完整 JSON 负载并解析。
        const payload = JSON.parse(data.join('\n'))
        // 按事件名分发：增量文本、状态消息、错误、结束。
        if (event === 'delta') onDelta(payload.content || '')
        else if (event === 'status') onStatus(payload.message || '')
        else if (event === 'error') throw new Error(payload.message || '生成回答失败，请重试。')
        else if (event === 'done') { result = payload as T; completed = true }
        // 分发后重置事件名和数据缓冲，准备接收下一事件。
        event = ''; data = []
      // 非空行按 SSE 字段前缀提取事件名和数据，不把字段名本身放入 JSON。
      } else if (value.startsWith('event:')) event = value.slice(6).trim()
      else if (value.startsWith('data:')) data.push(value.slice(5).trimStart())
    }
    // 网络分片不等于事件边界，未凑齐换行的文本留在 buffer 中等待下次读取。
    // 持续读取流直到收到结束事件或流关闭。
    while (!completed) {
      const chunk = await reader.read()
      // 解码当前分片，未读完时保持流模式以处理跨分片字符。
      buffer += decoder.decode(chunk.value, { stream: !chunk.done })
      let end: number
      // 按换行切分缓冲区，逐行交给 line 处理。
      while ((end = buffer.indexOf('\n')) >= 0) {
        line(buffer.slice(0, end).replace(/\r$/, '')); buffer = buffer.slice(end + 1)
      }
      // 流读取完毕则退出循环。
      if (chunk.done) break
    }
    // 未收到结束事件或结果为空时视为连接异常中断。
    if (!completed || !result) throw new Error('回答连接提前中断，请重新尝试。')
    // 成功完成后清理 GET 缓存，确保后续读取到 AI 写入的最新数据。
    clearGetCache()
    return result
  } finally {
    // 无论成功或失败都清理超时定时器、关闭流读取器并中止请求。
    clearTimeout(timeout)
    await reader?.cancel().catch(() => {})
    controller.abort()
  }
}
