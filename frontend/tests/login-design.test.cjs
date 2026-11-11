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
    await context.route('**/api/**', async route => {
      const request = route.request(), url = new URL(request.url())
      if (!url.pathname.startsWith('/api/')) return route.continue()
      const body = request.postDataJSON() || {}
      requests.push({ path: url.pathname, search: url.search, method: request.method(), body, auth: request.headers().authorization })
      let data = [], status = 200, code = 0, message = '操作成功'
      if (url.pathname === '/api/auth/login') {
        if (body.username !== 'admin' || body.password !== '123456') { status = 401; code = 500; message = '账号或密码不正确。' }
        else data = { username: 'admin', token: 'a'.repeat(64) }
      } else if (url.pathname === '/api/front/profile') {
        data = { id: 1, username: 'admin', nickname: '系统账号', phone: '', email: '', bio: '', status: 'ENABLED' }
      } else if (url.pathname === '/api/admin/reviews') data = { items: [], total: 0, waiting_count: 0 }
      else if (url.pathname.endsWith('/dashboard') || url.pathname.endsWith('/overview') || url.pathname.endsWith('/latest')) data = {}
      await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify({ code, message, data }) })
    })
    for (const target of ['/overview', '/analysis', '/platform', '/keywords', '/sentiment', '/contents', '/screen', '/admin/data']) {
      await page.goto(base + target)
      await page.waitForURL('**/login?redirect=**')
      assert.equal(new URL(page.url()).searchParams.get('redirect'), target)
    }
    assert.equal(requests.length, 0, 'Anonymous navigation must not send business requests')
    for (const [name, width, height] of [['desktop',1440,900],['wide',1920,1080],['tablet',820,1180],['mobile',390,844],['small',320,640],['landscape',844,390]]) {
      await page.setViewportSize({ width, height }); await page.goto(base + '/login')
      await page.waitForFunction(() => {
        const canvas = document.querySelector('canvas')
        if (!canvas || canvas.width < 320) return false
        return canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data.some((v,i) => i % 4 === 3 && v > 0)
      })
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, name + ': overflow')
      assert.equal(await page.getByRole('link', { name: /官网/ }).count(), 6)
      await page.getByRole('button', { name: '分析', exact: true }).click()
      const dialog = page.getByRole('dialog'); await dialog.waitFor(); await page.waitForTimeout(650)
      const bounds = await dialog.boundingBox()
      assert.ok(bounds.y >= 0 && bounds.x >= 0 && bounds.y + bounds.height <= height + 1 && bounds.x + bounds.width <= width + 1, name + ': dialog bounds')
      assert.equal(await page.getByRole('button', { name: /注册/ }).count(), 0)
      assert.equal(await dialog.getByRole('textbox').count(), 2)
      await page.screenshot({ path: path.join(output, 'single-account-' + name + '.png'), fullPage: true })
      await page.keyboard.press('Escape'); await dialog.waitFor({ state: 'hidden' })
      assert.equal(await page.getByRole('button', { name: '分析', exact: true }).evaluate(el => el === document.activeElement), true)
    }
    await page.setViewportSize({width:1440,height:900})
    await page.goto(base + '/login?redirect=/admin/data')
    await page.getByRole('button', { name: '分析', exact: true }).click(); await page.waitForTimeout(650)
    await page.getByRole('button', { name: '登录', exact: true }).click()
    await page.getByRole('alert').filter({hasText:'请输入账号和密码'}).waitFor()
    await page.getByRole('textbox', {name:'账号',exact:true}).fill('student')
    await page.getByRole('textbox', {name:'密码',exact:true}).fill('123456')
    await page.getByRole('button', {name:'登录',exact:true}).click()
    await page.getByRole('alert').filter({hasText:'账号或密码不正确'}).waitFor()
    await page.getByRole('textbox', {name:'账号',exact:true}).fill('admin')
    await page.getByRole('textbox', {name:'密码',exact:true}).press('Enter')
    await page.waitForURL('**/admin/data')
    assert.equal(await page.evaluate(() => localStorage.getItem('social_role')), null)
    assert.equal(await page.getByRole('link', {name:'用户管理',exact:true}).count(), 0)
    await page.getByRole('link', {name:'账号设置',exact:true}).click()
    await page.waitForURL('**/admin/profile')
    await page.getByRole('button', {name:'保存资料',exact:true}).click()
    await page.getByText('个人资料已保存',{exact:true}).waitFor()
    const saved = requests.find(r => r.path === '/api/front/profile' && r.method === 'PUT')
    assert.deepEqual(Object.keys(saved.body).sort(), ['bio','email','nickname','phone'])
    assert.ok(requests.filter(r => r.path === '/api/front/profile').every(r => r.search === ''))
    const dashboardRequest = page.waitForRequest(r => r.url().endsWith('/api/analysis/overview'))
    await page.goto(base + '/overview')
    await dashboardRequest
    assert.ok(requests.some(r => r.path === '/api/analysis/overview'))
    assert.ok(requests.every(r => !r.path.includes('/api/events') && !r.search.includes('eventId=') && r.path !== '/api/auth/register'))
    await page.goto(base + '/admin/users'); await page.waitForURL('**/overview')
    await page.goto(base + '/admin/profile')
    await page.screenshot({path:path.join(output,'single-account-settings-desktop.png'),fullPage:true})
    await page.setViewportSize({width:390,height:844})
    await page.screenshot({path:path.join(output,'single-account-settings-mobile.png'),fullPage:true})
    assert.deepEqual(errors, [])
    console.log('PASS: six login viewports, nonblank canvas, no registration/users/roles, role-free login, own profile whitelist, new analysis URLs.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
