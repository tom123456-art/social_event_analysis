const assert = require('node:assert/strict')
const path = require('node:path')
const fs = require('node:fs')
const { chromium } = require('playwright')

async function main() {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe' })
  const base = process.env.REVIEW_BASE_URL || 'http://127.0.0.1:5174'
  const output = path.resolve(__dirname, '../../runtime/page-split')
  fs.mkdirSync(output, { recursive: true })
  const content = {
    content_id: 'news-1', title: '科技产业观察', clean_text: '芯片与人工智能产业发展', content_text: '芯片与人工智能产业发展',
    platform: 'TENCENT_NEWS', category: 'technology', category_label: '科技', publish_time: '2026-10-20T08:00:00',
    hot_score: 60, platform_heat_index: 60, like_count: 10, comment_count: 5, repost_count: 3, favorite_count: 2,
    author_name: '媒体', source_url: 'https://example.invalid/news/1', keywords: '芯片,人工智能', sentiment_label: 'positive',
    sentiment_positive_score: 0.98, sentiment_negative_score: 0.02, sentiment_source: 'model', rank_no: 1
  }
  const timeline = [{ platform: 'TENCENT_NEWS', first_publish_time: '2026-10-20T08:00:00', content_count: 2, hot_score: 120, delay_minutes: 0 }]
  const daily = ['2026-10-19', '2026-10-20'].map(day => ({ platform: 'TENCENT_NEWS', time_bucket: day, content_count: 2, average_heat_index: 60, high_heat_content_count: 1 }))
  const category = [{ category: 'technology', category_label: '科技', content_count: 2, hot_score: 120 }]
  const batch = { batch_id: 'batch-1', status: 'SUCCESS', current_stage: 'MYSQL_SYNC', source_count: 3, valid_count: 2, started_at: '2026-10-20T08:00:00', ended_at: '2026-10-20T08:01:00' }
  const keywords = [{ keyword: '芯片', word_count: 2, platform_top: 'TENCENT_NEWS', sentiment_top: 'positive' }]
  const summary = { window_start: '2026-10-14T00:00:00', window_end: '2026-10-20T08:00:00', lead_platform: 'TENCENT_NEWS', lead_platforms: ['TENCENT_NEWS'], lead_category: '科技', first_hot_time: '2026-10-20T08:00:00', relation_count: 0 }
  const common = {
    overview: { content_count: 2, platform_count: 1, hot_score: 120 }, realPublicContents: [content], latestBatch: batch,
    interaction: timeline, categoryRank: category, keywordRank: keywords, platformTimeline: timeline, contentRank: [content],
    heatTrend: daily.map(row => ({ ...row, hot_score: 120 })),
    sentimentTrend: [{ platform: 'TENCENT_NEWS', time_bucket: '2026-10-20', sentiment_label: 'positive', sentiment_count: 2 }]
  }
  const fixtures = {
    '/api/analysis/overview': Object.fromEntries(['overview', 'realPublicContents', 'latestBatch', 'interaction', 'categoryRank', 'keywordRank'].map(key => [key, common[key]])),
    '/api/analysis/hotspot-trend': { platformDailyHeat: daily, platformCategoryHeat: daily.map(row => ({ ...row, category: '科技', category_heat_index: 60, average_relative_heat_index: 60, category_share: 100 })) },
    '/api/analysis/trend-content-rank': [content],
    '/api/analysis/platform-spread': { platformTimeline: timeline, recentPlatformTimeline: timeline, categoryPropagationLinks: [], categoryPropagationTimeline: [{ ...timeline[0], lead_category: '科技', first_hot_time: summary.first_hot_time, category_count: 1, peak_attention: 60 }], categoryPropagationSummary: summary, platformDailyHeat: daily },
    '/api/analysis/propagation-evidence': { items: [content], total: 1, page: 1, page_size: 20, analysis: { summary, hourly: [], activations: [], relations: [] } },
    '/api/analysis/keyword-analysis': [1, 2, 3].map(index => ({ ...content, content_id: `news-${index}`, category: '科技' })),
    '/api/analysis/sentiment-analysis': [content],
    '/api/analysis/content-page': { rows: [content], total: 1, page: 1, pageSize: 10 },
    '/api/admin/overview': { eventCount: { event_count: 1 }, topicCount: { topic_count: 1 }, batches: [batch], keywordRank: keywords, platformTimeline: timeline, contentRank: [content], noiseSummary: [{ platform: 'TENCENT_NEWS', content_count: 3, noise_count: 1, duplicate_count: 0 }] },
    '/api/analysis/screen': Object.fromEntries(['overview', 'realPublicContents', 'latestBatch', 'categoryRank', 'keywordRank', 'platformTimeline', 'contentRank', 'heatTrend', 'sentimentTrend'].map(key => [key, common[key]]))
  }
  const pages = [
    ['/overview', '/api/analysis/overview'], ['/analysis', '/api/analysis/hotspot-trend'], ['/platform', '/api/analysis/platform-spread'],
    ['/keywords', '/api/analysis/keyword-analysis'], ['/sentiment', '/api/analysis/sentiment-analysis'], ['/contents', '/api/analysis/content-page'],
    ['/admin', '/api/admin/overview'], ['/screen', '/api/analysis/screen']
  ]
  try {
    for (const [label, viewport] of [['desktop', { width: 1440, height: 1000 }], ['mobile', { width: 390, height: 844 }]]) {
      const context = await browser.newContext({ viewport })
      await context.addInitScript(() => {
        localStorage.setItem('social_token', 'a'.repeat(64))
        localStorage.setItem('social_user', 'admin')
      })
      const page = await context.newPage(), errors = [], requests = []
      page.on('pageerror', error => errors.push(`${page.url()}: ${error.stack || error.message}`))
      await context.route('**/api/**', async route => {
        const pathname = new URL(route.request().url()).pathname
        if (!pathname.startsWith('/api/')) return route.continue()
        requests.push(pathname)
        let data = fixtures[pathname] || []
        if (pathname === '/api/ai/config') data = { models: [], capabilities: {} }
        await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 0, data, message: '操作成功' }) })
      })
      for (const [url, endpoint] of pages) {
        const start = requests.length
        const response = page.waitForResponse(r => new URL(r.url()).pathname === endpoint)
        await page.goto(base + url)
        await response
        await page.waitForTimeout(700)
        const calls = requests.slice(start)
        const analysisCalls = calls.filter(p => p.startsWith('/api/analysis/') || p === '/api/admin/overview')
        const allowed = new Set([endpoint, ...(url === '/analysis' ? ['/api/analysis/trend-content-rank'] : [])])
        assert.ok(analysisCalls.every(p => allowed.has(p)), `${url}: unexpected page query ${analysisCalls}`)
        assert.ok(!calls.includes('/api/analysis/dashboard'), 'legacy dashboard requested')
        if (url === '/analysis') assert.ok(calls.includes('/api/analysis/trend-content-rank'), 'date-ranked content request missing')
        if (['/overview', '/analysis', '/platform', '/screen'].includes(url)) {
          await page.screenshot({ path: path.join(output, `${label}-${url.slice(1)}.png`), fullPage: true })
          const nonblank = await page.locator('canvas').evaluateAll(canvases => canvases.some(canvas => {
            const context = canvas.getContext('2d')
            return context && canvas.width && canvas.height && context.getImageData(0, 0, canvas.width, canvas.height).data.some((v, i) => i % 4 === 3 && v > 0)
          }))
          assert.ok(nonblank, `${label} ${url}: chart canvas is blank`)
        }
      }
      assert.deepEqual(errors, [], `${label}: runtime errors`)
      await context.close()
    }
    console.log('PASS: eight pages on desktop/mobile, isolated API requests, range query, nonblank charts and zero runtime errors; API responses are fixture-backed.')
  } finally { await browser.close() }
}
main().catch(error => { console.error(error); process.exitCode = 1 })
