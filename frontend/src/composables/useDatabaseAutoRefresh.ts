import { onBeforeUnmount, onMounted } from 'vue'

export const DATABASE_SYNCED_EVENT = 'social-hotspot:database-synced'

export function notifyDatabaseSynced() {
  window.dispatchEvent(new CustomEvent(DATABASE_SYNCED_EVENT))
}

export function useDatabaseAutoRefresh(load: () => Promise<unknown>, intervalMs = 5000) {
  let timer: number | undefined
  let running = false

  const refresh = async () => {
    if (running || document.visibilityState === 'hidden') return
    running = true
    try {
      await load()
    } catch {
      // Background refresh keeps the last committed database snapshot on screen.
    } finally {
      running = false
    }
  }

  const handleVisible = () => {
    if (document.visibilityState === 'visible') void refresh()
  }

  onMounted(() => {
    timer = window.setInterval(() => void refresh(), intervalMs)
    window.addEventListener(DATABASE_SYNCED_EVENT, refresh)
    document.addEventListener('visibilitychange', handleVisible)
  })

  onBeforeUnmount(() => {
    if (timer) window.clearInterval(timer)
    window.removeEventListener(DATABASE_SYNCED_EVENT, refresh)
    document.removeEventListener('visibilitychange', handleVisible)
  })

  return { refresh }
}
