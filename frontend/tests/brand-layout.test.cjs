const assert = require('node:assert/strict')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME })
  try {
    for (const width of [1920, 1440, 1280, 1200, 1024, 390, 320]) {
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
        await page.goto((process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5174') + route)
        const brand = page.locator(route === '/overview' ? '.front-brand' : '.brand')
        await brand.waitFor({ state: 'attached' })
        if (route === '/admin' && width < 1100) continue
        await brand.waitFor({ state: 'visible' })
        assert.equal(await brand.locator('br').count(), route === '/overview' ? 0 : 1)
        assert.equal(await brand.locator('.brand-mark, small, span').count(), 0)
        assert.equal((await brand.innerText()).replace(/\s/g, ''), '网络媒体热点事件传播态势与舆情分析' + (route === '/overview' ? '系统' : ''))
        if (route === '/overview') {
          assert.equal(await page.locator('.portal-copy h1').innerText(), '网络媒体热点事件传播态势与舆情分析系统')
          assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'No horizontal overflow')
          if (width >= 780) assert.ok(await brand.locator('b').evaluate(el => el.getBoundingClientRect().height <= parseFloat(getComputedStyle(el).lineHeight) + 1), 'Desktop brand stays on one line')
          if (width >= 780) assert.ok(await page.locator('.front-nav').evaluate(el => el.scrollWidth <= el.clientWidth + 1), 'All desktop navigation links fit')
        }
        assert.ok(await brand.evaluate(element => {
          const text = element.querySelector('b').getBoundingClientRect()
          const box = element.getBoundingClientRect()
          return text.left >= box.left && text.right <= box.right + 1 && text.bottom <= box.bottom + 1
        }), 'Brand text must fit its container')
        await page.screenshot({ path: path.resolve('../runtime/brand-' + width + '-' + route.slice(1) + '.png') })
      }
      await page.close()
    }
    console.log('PASS: single-line desktop brand, canonical portal name, desktop/tablet/mobile text bounds.')
  } finally {
    await browser.close()
  }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
