const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_PACKAGE || 'playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => {
      localStorage.setItem('social_token', 'test')
      localStorage.setItem('social_user', 'admin')
      localStorage.setItem('social_role', 'admin')
    })
    const original = { platform: 'TENCENT_NEWS', content_id: 'news-001', title: '待核对的新闻标题',
      content_text: '待人工核对的完整正文内容', publish_time: '2026-10-12 09:00:00', crawl_time: '2026-10-12 10:00:00',
      like_count: '', comment_count: '5', repost_count: '', favorite_count: '' }
    const row = { id: 1, platform: original.platform, content_id: original.content_id, title: original.title,
      reasons: '["LOW_CONFIDENCE"]', status: 'PENDING', apply_status: 'WAITING' }
    let latest = {}, submission, applied = 0, archived = 0, submissionRequests = 0
    await page.route('**/api/**', async route => {
      const url = new URL(route.request().url()), method = route.request().method()
      if (!url.pathname.startsWith('/api/')) return route.continue()
      if (url.pathname.startsWith('/api/admin/submissions')) submissionRequests++
      let data = []
      if (url.pathname === '/api/admin/reviews/jobs/latest') data = latest
      else if (url.pathname === '/api/admin/reviews/apply') {
        applied++; row.apply_status = 'PROCESSING'; latest = { id: 'test-job', status: 'RUNNING' }; data = latest
      } else if (url.pathname === '/api/admin/reviews/1' && method === 'PUT') {
        submission = route.request().postDataJSON(); row.status = submission.status; data = true
      } else if (url.pathname === '/api/admin/reviews/1' && method === 'DELETE') {
        archived++; data = true
      } else if (url.pathname === '/api/admin/reviews/1') {
        data = { ...row, revision: 1, original_data: original, corrected_data: original, logs: [] }
      } else if (url.pathname === '/api/admin/reviews') {
        data = { items: archived ? [] : [row], total: archived ? 0 : 1,
          waiting_count: row.status === 'APPROVED' && row.apply_status === 'WAITING' ? 1 : 0 }
      }
      await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data }) })
    })
    await page.goto(`${process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5174'}/admin/data`)
    assert.equal(await page.getByRole('tab', { name: '用户投稿审核', exact: true }).count(), 0)
    await page.getByRole('tab', { name: '人工标注审核', exact: true }).click()
    await page.getByText('情感低置信度', { exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: /应用审核结果/ }).isDisabled(), true)
    await page.getByRole('button', { name: '审核', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('.review-decision .el-select').click()
    await page.getByRole('option', { name: '中性', exact: true }).click()
    await dialog.getByRole('button', { name: '修正并通过', exact: true }).click()
    await page.getByText('审核决定已保存').waitFor()
    assert.equal(applied, 0, 'Approval must not automatically launch ETL')
    assert.equal(submission.corrected_data.like_count, '')
    assert.equal(submission.corrected_data.repost_count, '')
    assert.equal(submission.manual_sentiment, 'neutral')
    assert.equal(submission.revision, 1)
    await page.getByRole('button', { name: /应用审核结果/ }).click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await page.getByText('分析更新中', { exact: true }).waitFor()
    assert.equal(applied, 1)
    assert.equal(await page.getByRole('button', { name: /应用审核结果/ }).isDisabled(), true)
    latest = { id: 'test-job', status: 'SUCCESS', batch_id: 'test-batch' }; row.apply_status = 'APPLIED'
    await page.getByRole('button', { name: '刷新审核队列' }).click()
    await page.getByText('应用完成', { exact: true }).waitFor()
    const screenshots = path.resolve(__dirname, '../../runtime')
    fs.mkdirSync(screenshots, { recursive: true })
    await page.screenshot({ path: path.join(screenshots, 'review-desktop.png'), fullPage: true })
    await page.setViewportSize({ width: 390, height: 844 })
    await page.screenshot({ path: path.join(screenshots, 'review-mobile.png'), fullPage: true })
    await page.getByRole('button', { name: '审核', exact: true }).click()
    await dialog.waitFor()
    await dialog.locator('.review-comparison').waitFor()
    await page.screenshot({ path: path.join(screenshots, 'review-mobile-dialog.png'), fullPage: true, animations: 'disabled' })
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await page.getByRole('button', { name: '归档', exact: true }).click()
    await page.getByRole('button', { name: '确定', exact: true }).click()
    await page.getByText('暂无待审核异常记录', { exact: true }).waitFor()
    assert.equal(archived, 1)
    assert.equal(submissionRequests, 0, 'Data maintenance must not request removed submission data')
    assert.deepEqual(errors, [])
    console.log('PASS: review edits retain blanks; approval is separate from application; running lock, completion and archive; desktop/mobile rendering.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
