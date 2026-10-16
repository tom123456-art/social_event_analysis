const assert = require('node:assert/strict')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME })
  try {
    for (const width of [1440, 1024, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } })
      page.on('pageerror', error => console.error(error.message))
      await page.addInitScript(() => {
        localStorage.setItem('social_token', 'test')
        localStorage.setItem('social_user', 'admin')
        localStorage.setItem('social_role', 'admin')
      })
      await page.route('**/api/**', route => {
        if (!new URL(route.request().url()).pathname.startsWith('/api/')) return route.continue()
        return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data: [] }) })
      })
      for (const route of ['/overview', '/admin']) {
        await page.goto((process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5175') + route)
        const brand = page.locator(route === '/overview' ? '.front-brand' : '.brand')
        await brand.waitFor({ state: 'attached' })
        if (route === '/admin' && width < 1100) continue
        await brand.waitFor({ state: 'visible' })
        assert.equal(await brand.locator('br').count(), 1)
        assert.equal(await brand.locator('.brand-mark, small, span').count(), 0)
        assert.equal((await brand.innerText()).replace(/\s/g, ''), '\u7f51\u7edc\u5a92\u4f53\u70ed\u70b9\u4e8b\u4ef6\u4f20\u64ad\u6001\u52bf\u4e0e\u8206\u60c5\u5206\u6790')
        assert.ok(await brand.evaluate(element => {
          const text = element.querySelector('b').getBoundingClientRect()
          const box = element.getBoundingClientRect()
          return text.left >= box.left && text.right <= box.right + 1 && text.bottom <= box.bottom + 1
        }), 'Brand text must fit its container')
        await page.screenshot({ path: path.resolve('../runtime/brand-' + width + '-' + route.slice(1) + '.png') })
      }
      await page.close()
    }
    console.log('PASS: two-line brand, no logo or subtitle, desktop/tablet/mobile text bounds.')
  } finally {
    await browser.close()
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
