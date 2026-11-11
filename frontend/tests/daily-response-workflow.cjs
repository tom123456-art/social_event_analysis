const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { chromium } = require('playwright')

async function main() {
  const base = process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5175'
  const live = Boolean(process.env.REVIEW_PASSWORD)
  const output = path.resolve(__dirname, '../../runtime/daily-response')
  fs.mkdirSync(output, { recursive: true })
  const platforms = ['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER']
  const days = Array.from({ length: 7 }, (_, i) => ({ date: `2026-09-${24 + i}`, platforms: platforms.map((platform, j) => ({
    platform, status: i === 2 ? 'no_data' : (i === 3 && j === 4 ? 'below_threshold' : 'activated'),
    lead_category: i % 2 ? '科技' : '体育', lead_categories: [i % 2 ? '科技' : '体育'],
    first_hot_time: `2026-09-${24 + i}T09:${String(15 + j * 7).padStart(2, '0')}:00`, delay_minutes: j * 7
  })) }))
  const summary = { window_start: '2026-09-24T09:15:00', window_end: '2026-09-30T09:43:00', lead_platform: platforms[0], lead_category: '体育', first_hot_time: days[0].platforms[0].first_hot_time }
  const dashboard = { categoryDailyResponses: days, categoryPropagationSummary: summary, categoryPropagationTimeline: days[0].platforms,
    recentPlatformTimeline: platforms.map((platform, i) => ({ platform, hot_score: 100 - i * 10 })), categoryPropagationLinks: [], platformDailyHeat: [] }
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  try {
    for (const [label, viewport] of [['desktop', { width: 1600, height: 1100 }], ['mobile', { width: 390, height: 844 }]]) {
      const context = await browser.newContext({ viewport })
      let token = 'a'.repeat(64)
      if (live) {
        const login = await context.request.post(base + '/api/auth/login', { data: { username: process.env.REVIEW_USER || 'admin', password: process.env.REVIEW_PASSWORD } })
        const body = await login.json()
        assert.equal(body.code, 0, 'login failed')
        token = body.data.token
        assert.ok(token, 'missing auth token')
      }
      await context.addInitScript(token => { localStorage.setItem('social_token', token); localStorage.setItem('social_user', 'admin') }, token)
      const page = await context.newPage(), errors = [], evidenceUrls = []
      page.on('pageerror', error => errors.push(error.message))
      page.on('request', request => { if (new URL(request.url()).pathname === '/api/analysis/propagation-evidence') evidenceUrls.push(new URL(request.url())) })
      if (!live) await context.route('**/api/**', async route => {
        const url = new URL(route.request().url())
        if (!url.pathname.startsWith('/api/')) return route.continue()
        let data = []
        if (url.pathname === '/api/analysis/platform-spread') data = dashboard
        if (url.pathname === '/api/analysis/propagation-evidence') {
          const date = url.searchParams.get('date') || '2026-09-24'
          data = { items: [], total: 0, analysis: { summary: { window_start: date + 'T00:00:00', window_end: date + 'T23:59:00' }, hourly: [], activations: [] } }
        }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
      })
      const responsePromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/platform-spread')
      await page.goto(base + '/platform')
      const response = await responsePromise
      const actual = (await response.json()).data
      const actualDays = actual.categoryDailyResponses
      assert.equal(actualDays.length, 7, 'response must contain seven days')
      const table = page.getByRole('table', { name: '近7天每日类别响应' })
      await table.waitFor()
      assert.equal(await table.locator('tbody tr').count(), 7)
      assert.equal(await table.locator('tbody td').count(), 35)
      for (const day of actualDays) assert.equal(await table.locator(`time[datetime="${day.date}"]`).count(), 1)
      const active = actualDays.filter(day => day.platforms.some(row => row.status === 'activated'))
      for (const day of [active[0], active[active.length - 1]]) {
        const row = day.platforms.find(row => row.status === 'activated')
        const trigger = table.locator('button').filter({ hasText: row.lead_category }).and(page.locator(`[aria-label^="${day.date} "]`)).first()
        const evidencePromise = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/propagation-evidence')
        await trigger.click()
        const evidence = (await (await evidencePromise).json()).data
        const url = evidenceUrls[evidenceUrls.length - 1]
        assert.equal(url.searchParams.get('date'), day.date)
        assert.equal(url.searchParams.get('platform'), row.platform)
        assert.equal(url.searchParams.get('hour'), row.first_hot_time)
        if (live) assert.ok(evidence.total > 0 && evidence.items.length > 0, 'activated minute must have source news')
        assert.ok((evidence.analysis.hourly || []).every(point => String(point.time_bucket).startsWith(day.date)), 'calculation leaked across days')
        assert.ok((evidence.items || []).every(item => String(item.publish_time).startsWith(day.date)), 'source news leaked across days')
        const dialog = page.getByRole('dialog')
        await dialog.getByText(`统计日期：${day.date}`, { exact: false }).waitFor()
        const allMinutes = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/propagation-evidence' && !new URL(r.url()).searchParams.has('hour'))
        await dialog.getByRole('button', { name: '当天全部分钟' }).click()
        await allMinutes
        assert.equal(evidenceUrls[evidenceUrls.length - 1].searchParams.get('date'), day.date, 'reset must retain date')
        await dialog.locator('.el-loading-mask').waitFor({ state: 'hidden' })
        await page.screenshot({ path: path.join(output, `${label}-${live ? 'live' : 'fixture'}-evidence.png`), fullPage: true })
        await dialog.locator('.el-dialog__headerbtn').click()
        await dialog.waitFor({ state: 'hidden' })
      }
      const weekly = page.waitForResponse(r => new URL(r.url()).pathname === '/api/analysis/propagation-evidence')
      await page.getByRole('button', { name: '查看依据', exact: true }).first().click()
      await weekly
      assert.equal(evidenceUrls[evidenceUrls.length - 1].searchParams.has('date'), false, 'weekly summary must retain weekly scope')
      await page.getByRole('dialog').locator('.el-dialog__headerbtn').click()
      await page.getByRole('dialog').waitFor({ state: 'hidden' })
      await page.waitForTimeout(600)
      const nonblank = await page.locator('canvas').evaluateAll(canvases => canvases.length >= 2 && canvases.every(canvas => {
        const ctx = canvas.getContext('2d')
        return ctx && canvas.width > 0 && canvas.height > 0 && ctx.getImageData(0, 0, canvas.width, canvas.height).data.some((value, i) => i % 4 === 3 && value > 0)
      }))
      assert.ok(nonblank, 'existing charts must remain nonblank')
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'page has horizontal overflow')
      await table.scrollIntoViewIfNeeded()
      await page.screenshot({ path: path.join(output, `${label}-${live ? 'live' : 'fixture'}-daily.png`), fullPage: true })
      assert.deepEqual(errors, [])
      console.log(`PASS ${label} ${live ? 'database' : 'fixture'}: ${actualDays[0].date}..${actualDays[6].date}, 35 cells, daily evidence/reset, weekly summary, charts and layout.`)
      if (live && label === 'desktop') console.log(JSON.stringify(actualDays.map(day => ({ date: day.date, categories: day.platforms.map(row => row.lead_category || row.status), minutes: day.platforms.map(row => row.delay_minutes) }))))
      await context.close()
    }
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
