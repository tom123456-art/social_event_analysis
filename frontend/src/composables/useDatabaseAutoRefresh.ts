import { onActivated, onBeforeUnmount, onDeactivated, onMounted } from 'vue'
import { clearGetCache } from '../api/client'

// 页面之间共享的数据库快照更新事件名称。
export const DATABASE_SYNCED_EVENT = 'social-hotspot:database-synced'

// 数据写入完成后清缓存并通知其他页面读取新快照。
export function notifyDatabaseSynced() {
  // 使通知后的首次 GET 请求绕过旧快照缓存；派发同一窗口内的数据库更新事件。
  clearGetCache()
  window.dispatchEvent(new CustomEvent(DATABASE_SYNCED_EVENT))
}

// 注册数据库更新监听，并按可选间隔为激活页面轮询。
export function useDatabaseAutoRefresh(load: () => Promise<unknown>, intervalMs = 0) {
  // 保存轮询定时器编号，未启动时为空；防止前一次加载尚未结束时重复进入。
  let timer: number | undefined
  let running = false

  // 对外提供具有并发保护的刷新函数。
  const refresh = async () => {
    // 复用正在进行的加载周期，忽略重复触发。
    if (running) return
    // 标记本次刷新开始。
    running = true
    try {
      // 调用页面提供的数据读取函数。
      await load()
    } catch {
      // 自动刷新失败时保留屏幕上最后一次成功加载的快照。
    } finally {
      // 成功或失败都释放并发保护标记。
      running = false
    }
  }

  // 只有配置正间隔且尚无定时器时才启动轮询。
  const startPolling = () => {
    if (intervalMs > 0 && !timer) timer = window.setInterval(() => void refresh(), intervalMs)
  }

  // 停止轮询并清理定时器引用。
  const stopPolling = () => {
    // 尚未启动轮询时无需清理。
    if (!timer) return
    // 清除浏览器定时器；允许下次激活页面时重新创建定时器。
    window.clearInterval(timer)
    timer = undefined
  }

  // 组件挂载后监听数据库更新通知；keep-alive 页面激活时开始轮询；keep-alive 页面离开时停止轮询。
  onMounted(() => window.addEventListener(DATABASE_SYNCED_EVENT, refresh))
  onActivated(startPolling)
  onDeactivated(stopPolling)
  // 组件永久销毁时释放全部监听资源。
  onBeforeUnmount(() => {
    // 清除未结束的轮询定时器；删除数据库更新事件监听，避免重复触发和组件泄露。
    stopPolling()
    window.removeEventListener(DATABASE_SYNCED_EVENT, refresh)
  })

  // 暴露并发安全的刷新入口供页面主动调用。
  return { refresh }
}
