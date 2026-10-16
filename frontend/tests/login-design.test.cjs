const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME })
  const base = process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5174'
  const output = path.resolve(__dirname, '../../runtime')
  fs.mkdirSync(output, { recursive: true })
  try {
    const context = await browser.newContext()
    const page = await context.newPage(), errors = [], requests = []
    page.on('pageerror', error => errors.push(error.message))
    const users = new Map([['admin', { password: '123456', role: 'admin' }]])
    await context.route('**/api/**', async route => {
      const url = new URL(route.request().url())
      if (!url.pathname.startsWith('/api/')) return route.continue()
      const body = route.request().postDataJSON() || {}, name = body.username
      requests.push({ path: url.pathname, body, auth: route.request().headers().authorization })
      let data = [], status = 200, code = 0, message = '操作成功'
      if (url.pathname === '/api/auth/register') {
        if (users.has(name)) { code = 500; message = '该账号已存在，请更换账号或直接登录。' }
        else { users.set(name, { password: body.password, role: 'user' }); data = { username: name, role: 'user' } }
      } else if (url.pathname === '/api/auth/login') {
        const user = users.get(name)
        if (!user || user.password !== body.password) { status = 401; code = 500; message = '账号或密码不正确。' }
        else data = { username: name, role: user.role, token: 'a'.repeat(64) }
      } else if (url.pathname === '/api/admin/reviews/jobs/latest') data = {}
      else if (url.pathname === '/api/front/profile') data = { username: 'reader', role: 'USER', status: 'ENABLED' }
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    })
    for (const path of ['/overview', '/analysis', '/platform', '/keywords', '/sentiment', '/contents', '/screen', '/admin/data']) {
      await page.goto(`${base}${path}`)
      await page.waitForURL('**/login?redirect=**')
      assert.equal(new URL(page.url()).searchParams.get('redirect'), path)
    }
    assert.equal(requests.some(req => req.path === '/api/events'), false, 'No analysis requests before login')
    for (const [name, width, height] of [['desktop', 1440, 900], ['wide', 1920, 1080], ['tablet', 820, 1180], ['mobile', 390, 844], ['small', 320, 640], ['landscape', 844, 390]]) {
      await page.setViewportSize({ width, height }); await page.goto(`${base}/login`)
      await page.locator('canvas').waitFor()
      await page.waitForFunction(() => {
        const canvas = document.querySelector('canvas')
        if (!canvas || canvas.width < 320) return false
        const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data
        return pixels.some((value, index) => index % 4 === 3 && value > 0)
      })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `${name}: overflow`)
      assert.equal(await page.getByRole('dialog').count(), 0)
      assert.equal(await page.locator('.signal-brand').innerText(), '网络媒体热点事件传播态势与舆情分析系统')
      assert.equal(await page.locator('.signal-mark, .signal-brand a, .scene-controls').count(), 0)
      assert.equal(await page.getByRole('button', { name: /传播动画/ }).count(), 0)
      const heading = await page.getByRole('heading', { level: 1 }).boundingBox()
      const sohu = await page.getByRole('link', { name: '搜狐官网（新窗口）', exact: true }).locator('.platform-symbol').boundingBox()
      assert.ok(heading.y + heading.height < sohu.y, `${name}: title overlaps graph`)
      const pixels = await page.locator('canvas').evaluate(canvas => {
        const data = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data
        let count = 0; for (let i = 3; i < data.length; i += 4) if (data[i]) count++
        return count
      })
      assert.ok(pixels > 200, `${name}: blank canvas`)
      assert.equal(await page.getByRole('link', { name: /官网/ }).count(), 6)
      await page.screenshot({ path: path.join(output, `login-network-${name}.png`), fullPage: true })
      const center = await page.getByRole('button', { name: '分析', exact: true }).boundingBox()
      await page.getByRole('button', { name: '分析', exact: true }).click()
      await page.getByRole('dialog').waitFor(); await page.waitForTimeout(650)
      const dialog = page.getByRole('dialog'), bounds = await dialog.boundingBox()
      assert.ok(Math.abs(bounds.x + bounds.width / 2 - center.x - center.width / 2) < 2, `${name}: wrong animation origin`)
      assert.ok(bounds.width < width && bounds.height < height && bounds.y >= 0 && bounds.y + bounds.height <= height + 1, `${name}: dialog out of bounds`)
      const blur = await dialog.evaluate(el => getComputedStyle(el).backdropFilter)
      assert.ok(blur.includes('blur'), 'Dialog must use frosted glass')
      await page.screenshot({ path: path.join(output, `login-glass-${name}.png`), fullPage: true })
      await page.getByRole('button', { name: '注册账号', exact: true }).click()
      const registration = await dialog.boundingBox()
      assert.ok(registration.y >= 0 && registration.y + registration.height <= height + 1, `${name}: registration out of bounds`)
      await page.getByRole('button', { name: '注册', exact: true }).scrollIntoViewIfNeeded()
      assert.equal(await page.getByRole('button', { name: '注册', exact: true }).isVisible(), true)
      await page.screenshot({ path: path.join(output, `login-register-${name}.png`), fullPage: true })
      await page.getByRole('button', { name: '返回登录', exact: true }).click()
      await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' })
      assert.equal(await page.getByRole('button', { name: '分析', exact: true }).evaluate(el => el === document.activeElement), true)
    }
    const expected = ['https://news.qq.com/', 'https://www.sohu.com/', 'https://www.sina.com.cn/', 'https://www.thepaper.cn/', 'https://www.163.com/', 'https://weibo.com/']
    assert.deepEqual(await page.locator('.platform-node').evaluateAll(links => links.map(link => link.href)), expected)
    for (const url of expected) await context.route(`${url}**`, route => route.fulfill({ body: '<html><body>Official link test</body></html>' }))
    const popupWait = page.waitForEvent('popup')
    await page.getByRole('link', { name: '腾讯官网（新窗口）', exact: true }).click()
    const popup = await popupWait; await popup.waitForLoadState(); assert.equal(popup.url(), expected[0]); await popup.close()
    await page.setViewportSize({ width: 1440, height: 900 })
    const snapshot = () => page.locator('canvas').evaluate(canvas => canvas.toDataURL())
    const before = await snapshot(); await page.waitForTimeout(150)
    assert.notEqual(await snapshot(), before)
    const inward = await page.locator('canvas').evaluate(async canvas => {
      const ctx = canvas.getContext('2d'), original = ctx.arc, dots = []
      ctx.arc = function(x, y, radius, ...rest) {
        if (radius === 2.6) dots.push([x, y])
        return original.call(this, x, y, radius, ...rest)
      }
      try {
        await new Promise(resolve => setTimeout(resolve, 200))
        if (dots.length < 24) return false
        const rect = canvas.getBoundingClientRect(), cx = rect.width * .5, cy = rect.height * .52
        const distance = point => Math.hypot(point[0] - cx, point[1] - cy)
        const last = dots.length - 12
        return [0, 1].some(i => distance(dots[last + i]) < distance(dots[i]) - 1)
      } finally { ctx.arc = original }
    })
    assert.equal(inward, true, 'Moving dots must travel from platforms toward analysis')
    await page.getByRole('button', { name: '分析', exact: true }).click()
    await page.waitForTimeout(60)
    const expanding = await page.getByRole('dialog').evaluate(el => new DOMMatrix(getComputedStyle(el).transform).a)
    assert.ok(expanding > 0 && expanding < 1, 'Dialog must expand gradually, not appear instantly')
    await page.waitForTimeout(600)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '请输入账号和密码' }).waitFor()
    await page.getByRole('button', { name: '注册账号', exact: true }).click()
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('reader')
    await page.getByRole('textbox', { name: '昵称', exact: true }).fill('新闻读者')
    await page.getByRole('textbox', { name: '手机号', exact: true }).fill('13800138000')
    await page.getByRole('textbox', { name: '邮箱', exact: true }).fill('reader@example.com')
    await page.getByRole('textbox', { name: '个人简介', exact: true }).fill('关注跨平台新闻传播')
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('test-password-123')
    await page.getByRole('textbox', { name: '确认密码', exact: true }).fill('different')
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '两次输入的密码不一致' }).waitFor()
    assert.equal(requests.filter(req => req.path === '/api/auth/register').length, 0)
    await page.getByRole('textbox', { name: '确认密码', exact: true }).fill('test-password-123')
    await page.getByRole('textbox', { name: '确认密码', exact: true }).press('Enter')
    await page.getByText('注册成功，请登录', { exact: true }).waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('social_token')), null)
    assert.equal(Object.hasOwn(requests.find(req => req.path === '/api/auth/register').body, 'role'), false)
    const registeredBody = requests.find(req => req.path === '/api/auth/register').body
    assert.equal(registeredBody.nickname, '新闻读者'); assert.equal(registeredBody.phone, '13800138000')
    assert.equal(registeredBody.email, 'reader@example.com'); assert.equal(registeredBody.bio, '关注跨平台新闻传播')
    await page.getByRole('button', { name: '注册账号', exact: true }).click()
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('test-password-123')
    await page.getByRole('textbox', { name: '确认密码', exact: true }).fill('test-password-123')
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '该账号已存在' }).waitFor()
    await page.getByRole('button', { name: '返回登录', exact: true }).click()
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('wrong')
    await page.getByRole('textbox', { name: '密码', exact: true }).press('Enter')
    await page.getByRole('alert').filter({ hasText: '账号或密码不正确' }).waitFor()
    assert.equal(await page.evaluate(() => localStorage.getItem('social_token')), null)
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('test-password-123')
    assert.deepEqual(errors, [])
    await page.getByRole('textbox', { name: '密码', exact: true }).press('Enter')
    await page.waitForURL('**/overview', { waitUntil: 'domcontentloaded' })
    assert.equal(await page.evaluate(() => localStorage.getItem('social_role')), 'user')
    await page.goto(`${base}/login?redirect=${encodeURIComponent('/admin/data')}`)
    await page.getByRole('button', { name: '分析', exact: true }).click(); await page.waitForTimeout(650)
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('reader')
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('test-password-123')
    const profileRequest = page.waitForRequest(request => new URL(request.url()).pathname === '/api/front/profile'
      && request.headers().authorization === `Bearer ${'a'.repeat(64)}`)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.waitForURL('**/admin/profile', { waitUntil: 'domcontentloaded' })
    await profileRequest
    assert.ok(requests.some(req => req.path === '/api/front/profile' && req.auth === `Bearer ${'a'.repeat(64)}`))
    await page.goto(`${base}/login?redirect=${encodeURIComponent('/admin/data')}`)
    await page.getByRole('button', { name: '分析', exact: true }).click(); await page.waitForTimeout(650)
    await page.getByRole('textbox', { name: '账号', exact: true }).fill('admin')
    await page.getByRole('textbox', { name: '密码', exact: true }).fill('123456')
    await page.getByRole('textbox', { name: '密码', exact: true }).press('Enter')
    await page.waitForURL('**/admin/data', { waitUntil: 'domcontentloaded' })
    assert.equal(await page.evaluate(() => localStorage.getItem('social_role')), 'admin')
    await page.emulateMedia({ reducedMotion: 'reduce' }); await page.goto(`${base}/login`)
    await page.locator('canvas').waitFor(); await page.waitForTimeout(200)
    const frozen = await snapshot(); await page.waitForTimeout(150); assert.equal(await snapshot(), frozen)
    console.log('PASS: renamed clean header, inward animation, authenticated pages, six official links, glass dialog, six viewports, reduced motion, registration/login and role redirects (mock API).')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
