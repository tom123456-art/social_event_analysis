const assert = require('node:assert/strict')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME })
  try {
    for (const width of [1440, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } })
      const errors = []
      page.on('pageerror', error => errors.push(error.message))
      await page.addInitScript(() => {
        localStorage.setItem('social_token', 'test')
        localStorage.setItem('social_role', 'admin')
      })
      const rows = ['WEIBO', 'SOHU_NEWS'].flatMap(platform => ['positive', 'negative', 'neutral', null].map((sentiment_label, i) => ({
        platform, sentiment_label, content_id: platform + i, title: 'sample-' + i, content_text: 'sample-' + i,
        publish_time: '2026-10-01 10:00:00', keywords: 'topic', platform_heat_index: 50,
        sentiment_positive_score: i === 0 ? 0.98 : 0.02, sentiment_negative_score: i === 0 ? 0.02 : 0.98
      })))
      await page.route('**/api/**', route => {
        const url = new URL(route.request().url())
        if (!url.pathname.startsWith('/api/')) return route.continue()
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data: rows }) })
      })
      await page.goto((process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5174') + '/sentiment')
      const table = page.locator('.sentiment-detail-table:visible')
      await table.locator('tbody tr').first().waitFor()
      await page.waitForFunction(() => document.querySelector('.sentiment-detail-table tbody')?.textContent.includes('sample-'))
      for (const tab of ['weibo', 'news']) {
        if (tab === 'news') await page.locator('#tab-news').click()
        const visible = page.locator('.sentiment-detail-table:visible')
        assert.equal(await visible.locator('.sentiment-pill').count(), 2)
        assert.equal(await visible.getByText('sample-2', { exact: true }).count(), 0)
        assert.equal(await visible.getByText('sample-3', { exact: true }).count(), 0)
        assert.equal(await page.locator('.sentiment-page').getByText('\u53ea\u770b\u4e2d\u6027', { exact: true }).count(), 0)
        await page.screenshot({ path: path.resolve('../runtime/sentiment-binary-' + width + '-' + tab + '.png'), fullPage: true })
      }
      assert.deepEqual(errors, [])
      await page.close()
    }
    console.log('PASS: neutral/unclassified mock rows excluded from both sentiment tabs on desktop/mobile.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
