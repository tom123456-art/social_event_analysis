import { onActivated, onBeforeUnmount, onDeactivated, onMounted } from 'vue'
import { clearGetCache } from '../api/client'

export const DATABASE_SYNCED_EVENT = 'social-hotspot:database-synced'

export function notifyDatabaseSynced() {
  clearGetCache()
  window.dispatchEvent(new CustomEvent(DATABASE_SYNCED_EVENT))
}

export function useDatabaseAutoRefresh(load: () => Promise<unknown>, intervalMs = 0) {
  let timer: number | undefined
  let running = false

  const refresh = async () => {
    if (running) return
    running = true
    try {
      await load()
    } catch {
      // Keep the last committed database snapshot on screen when refresh fails.
    } finally {
      running = false
    }
  }

  const startPolling = () => {
    if (intervalMs > 0 && !timer) timer = window.setInterval(() => void refresh(), intervalMs)
  }

  const stopPolling = () => {
    if (!timer) return
    window.clearInterval(timer)
    timer = undefined
  }

  onMounted(() => window.addEventListener(DATABASE_SYNCED_EVENT, refresh))
  onActivated(startPolling)
  onDeactivated(stopPolling)
  onBeforeUnmount(() => {
    stopPolling()
    window.removeEventListener(DATABASE_SYNCED_EVENT, refresh)
  })

  return { refresh }
}
