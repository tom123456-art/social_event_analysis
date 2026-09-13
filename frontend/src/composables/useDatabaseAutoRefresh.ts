import { onActivated, onBeforeUnmount, onDeactivated } from 'vue'

export const DATABASE_SYNCED_EVENT = 'social-hotspot:database-synced'

export function notifyDatabaseSynced() {
  window.dispatchEvent(new CustomEvent(DATABASE_SYNCED_EVENT))
}

export function useDatabaseAutoRefresh(load: () => Promise<unknown>, intervalMs = 5000) {
  let timer: number | undefined
  let running = false
  let activatedOnce = false

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

  const start = () => {
    timer = window.setInterval(() => void refresh(), intervalMs)
    window.addEventListener(DATABASE_SYNCED_EVENT, refresh)
    document.addEventListener('visibilitychange', handleVisible)
  }

  const stop = () => {
    if (timer) {
      window.clearInterval(timer)
      timer = undefined
    }
    window.removeEventListener(DATABASE_SYNCED_EVENT, refresh)
    document.removeEventListener('visibilitychange', handleVisible)
  }

  onActivated(() => {
    start()
    // Initial data is loaded by the view. Refresh in the background on later returns.
    if (activatedOnce) void refresh()
    activatedOnce = true
  })

  onDeactivated(stop)
  onBeforeUnmount(stop)

  return { refresh }
}
