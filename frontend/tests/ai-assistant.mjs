import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const browser = await chromium.launch({ headless: true, executablePath: process.env.AI_TEST_BROWSER || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
const output = '../runtime/ai-tests'
fs.mkdirSync(output, { recursive: true })
try {
  for (const viewport of [{ width: 1440, height: 1000 }, { width: 390, height: 844 }]) {
    const page = await browser.newPage({ viewport })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    let chats = [{ id: 'old', title: '已有历史会话', messages: [{ role: 'user', content: '历史问题' }, { role: 'assistant', content: '历史回答', sources: [] }] }]
    let fail = false
    let modelTestFails = true
    const models = [{ id: 'qwen', name: '通义千问', provider: '通义千问', model: 'qwen-plus', configured: true }]
    await page.addInitScript(() => {
      localStorage.setItem('social_token', 'a'.repeat(64))
      localStorage.setItem('social_user', 'reader')
      localStorage.setItem('social_role', 'user')
    })
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      let data = {}
      if (path === '/api/ai/config') data = { models, capabilities: { database: true, sql: false, webSearch: true } }
      else if (path === '/api/ai/models/test-and-save') {
        if (modelTestFails) { await route.fulfill({ json: { code: 500, message: '模型认证失败，请检查密钥。' } }); return }
        const fields = request.postDataJSON()
        data = { id: 'custom-a', name: fields.name, model: fields.model, provider: fields.provider, configured: true }
        models.push(data)
      }
      else if (path === '/api/ai/conversations') {
        if (request.method() === 'POST') { const chat = { id: `new-${chats.length}`, title: '新对话', messages: [] }; chats.unshift(chat); data = chat }
        else data = chats.map(({ id, title }) => ({ id, title }))
      } else if (path.startsWith('/api/ai/conversations/')) {
        const id = path.split('/')[4], chat = chats.find(item => item.id === id)
        if (request.method() === 'DELETE') chats = chats.filter(item => item.id !== id)
        else if (path.endsWith('/messages/stream')) {
          if (fail) { await route.fulfill({ contentType: 'text/event-stream', body: 'event: error\ndata: ' + JSON.stringify({ message: '模型服务暂时不可用，请稍后重试。' }) + '\n\n' }); return }
          const question = request.postDataJSON().content
          assert.equal(request.postDataJSON().modelId, 'custom-a')
          chat.modelId = request.postDataJSON().modelId
          chat.title = question.slice(0, 24)
          chat.messages.push({ role: 'user', content: question }, { role: 'assistant', content: '## 分析结果\n**当前样本**共 123 条。<script>window.pwned=true</script>', sources: [{ name: '事件总览', detail: '当前系统分析快照' }] })
          await route.fulfill({ contentType: 'text/event-stream', body: 'event: status\ndata: ' + JSON.stringify({ message: '正在查询系统数据库' }) + '\n\nevent: delta\ndata: ' + JSON.stringify({ content: '分析中' }) + '\n\nevent: done\ndata: ' + JSON.stringify(chat) + '\n\n' }); return
        } else data = chat
      } else if (path.endsWith('/events')) data = []
      await route.fulfill({ json: { code: 0, data } })
    })
    await page.goto(`${process.env.AI_TEST_URL || 'http://127.0.0.1:5176'}/overview`)
    const orb = page.getByRole('button', { name: '打开观澜 AI 助手' })
    await orb.click()
    await page.getByText('今天，你想了解哪些舆情？').waitFor()
    const selector = page.getByRole('button', { name: '选择模型' })
    await selector.getByText('通义千问', { exact: true }).waitFor()
    await page.waitForTimeout(400)
    await page.screenshot({ path: `${output}/welcome-${viewport.width}.png`, fullPage: true })
    const dialog = page.locator('.observatory-dialog')
    const bounds = await dialog.boundingBox()
    assert(bounds.x >= -1 && bounds.y >= -1 && bounds.x + bounds.width <= viewport.width + 1 && bounds.y + bounds.height <= viewport.height + 1)
    assert.equal(await dialog.evaluate(el => el.scrollWidth > el.clientWidth), false)
    const webToggle = page.getByRole('button', { name: '联网搜索', exact: true })
    assert.equal(await webToggle.getAttribute('aria-pressed'), 'false')
    await webToggle.click()
    assert.equal(await webToggle.getAttribute('aria-pressed'), 'true')
    await webToggle.click()
    await selector.click()
    await page.getByRole('menuitem').filter({ hasText: '通义千问' }).locator('.agent-model-check').waitFor()
    await page.getByRole('menuitem').filter({ hasText: '新增模型' }).click()
    const modelDialog = page.locator('.agent-model-dialog')
    await modelDialog.getByRole('button', { name: '测试并添加' }).click()
    await modelDialog.getByText('请填写显示名称', { exact: true }).waitFor()
    await modelDialog.getByRole('textbox', { name: '显示名称', exact: true }).fill('A 模型')
    await modelDialog.getByRole('textbox', { name: '模型 ID', exact: true }).fill('a-chat')
    await modelDialog.getByRole('textbox', { name: '请求地址', exact: true }).fill('https://example.com/v1/chat/completions')
    await modelDialog.getByLabel('API Key', { exact: true }).fill('test-secret')
    await modelDialog.getByRole('button', { name: '测试并添加' }).click()
    await modelDialog.getByText('模型认证失败，请检查密钥。', { exact: true }).waitFor()
    assert.equal(await modelDialog.getByLabel('API Key', { exact: true }).inputValue(), 'test-secret')
    await page.screenshot({ path: `${output}/model-error-${viewport.width}.png` })
    modelTestFails = false
    await modelDialog.getByRole('button', { name: '测试并添加' }).click()
    await modelDialog.waitFor({ state: 'hidden' })
    await selector.getByText('A 模型', { exact: true }).waitFor()
    await selector.click()
    await page.getByRole('menuitem').filter({ hasText: 'A 模型' }).locator('.agent-model-check').waitFor()
    assert.equal(await page.getByRole('menuitem').filter({ hasText: '通义千问' }).locator('.agent-model-check').count(), 0)
    await page.screenshot({ path: `${output}/models-${viewport.width}.png` })
    await page.getByRole('menuitem').filter({ hasText: '通义千问' }).click()
    await selector.getByText('通义千问', { exact: true }).waitFor()
    await selector.click()
    await page.getByRole('menuitem').filter({ hasText: 'A 模型' }).click()
    await page.getByRole('textbox', { name: '输入问题' }).fill('概括当前热点')
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    await page.locator('.message-content').filter({ hasText: '当前样本' }).waitFor()
    assert.equal(await page.evaluate(() => window.pwned), undefined)
    await page.getByRole('button', { name: '1 项数据依据' }).click()
    await page.locator('.evidence-list').getByText('事件总览').waitFor()
    await page.waitForTimeout(200)
    await page.screenshot({ path: `${output}/answer-${viewport.width}.png`, fullPage: true })
    await page.getByRole('button', { name: '关闭数据依据' }).click()
    if (viewport.width < 760) await page.getByRole('button', { name: '历史对话', exact: true }).click()
    await page.getByRole('button', { name: '已有历史会话' }).click()
    await page.getByText('历史回答', { exact: true }).waitFor()
    await selector.click()
    await page.getByRole('menuitem').filter({ hasText: 'A 模型' }).click()
    fail = true
    await page.getByRole('textbox', { name: '输入问题' }).fill('失败后重试')
    await page.getByRole('button', { name: '发送问题', exact: true }).click()
    await page.locator('.message-error').waitFor()
    fail = false
    await page.getByRole('button', { name: '重新尝试', exact: true }).click()
    await page.locator('.message-content').filter({ hasText: '当前样本' }).waitFor()
    if (viewport.width < 760) await page.getByRole('button', { name: '历史对话', exact: true }).click()
    await page.getByRole('textbox', { name: '搜索历史对话' }).fill('not found')
    await page.getByText('暂无匹配的对话').waitFor()
    await page.getByRole('textbox', { name: '搜索历史对话' }).fill('')
    await page.getByRole('button', { name: '新建对话', exact: true }).click()
    await page.getByText('今天，你想了解哪些舆情？').waitFor()
    await page.getByRole('button', { name: '全屏', exact: true }).click()
    assert(await dialog.evaluate(el => el.classList.contains('is-fullscreen')))
    await page.getByRole('button', { name: '还原窗口', exact: true }).click()
    await page.getByRole('button', { name: '关闭助手', exact: true }).click()
    await dialog.waitFor({ state: 'hidden' })
    const before = await orb.boundingBox()
    await page.mouse.move(before.x + 32, before.y + 32)
    await page.mouse.down(); await page.mouse.move(before.x - 60, before.y - 80, { steps: 5 }); await page.mouse.up()
    const after = await orb.boundingBox()
    assert(after.x < before.x && after.y < before.y)
    assert.equal(await dialog.isVisible(), false)
    assert.deepEqual(errors, [])
    console.log(`AI UI passed at ${viewport.width}x${viewport.height}`)
    await page.close()
  }
} finally { await browser.close() }
