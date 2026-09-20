#!/usr/bin/env node

const crypto = require('crypto')
// Small enough for a classroom demonstration. The crawler never expands a remote
// count field and never creates records that were not returned by the deployed site.
const MAX_LIMIT = 50
const MIN_LIMIT = 30
const PUBLIC_MIN_LIMIT = 10
const PUBLIC_MAX_LIMIT = 20
const REQUEST_DELAY_MS = 240
const CANONICAL_EVENT_ID = 'public_rss_latest'
const CSV_COLUMNS = [
  'event_id', 'event_name', 'platform', 'content_id', 'parent_content_id', 'content_type',
  'title', 'content_text', 'author_id', 'author_name', 'publish_time', 'crawl_time',
  'like_count', 'comment_count', 'repost_count', 'share_count', 'favorite_count', 'view_count',
  'hot_rank', 'location', 'user_age_group', 'user_gender', 'keywords', 'source_url', 'image_url', 'category'
]

const SOURCES = {
  TENCENT_NEWS: {
    platform: 'TENCENT_NEWS',
    name: '腾讯新闻公开文章',
    event_name: '腾讯新闻热点传播分析',
    feed_url: 'https://i.news.qq.com/web_feed/get_command_pagination?page=1',
    robots_url: 'https://news.qq.com/robots.txt',
    comment_url: 'https://i.news.qq.com/getQQNewsComment',
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_TENCENT_NEWS',
    compliance_note: '通过 HTTP GET 真实读取腾讯新闻公开推荐接口、文章详情页和评论总数接口；只保存接口实际返回字段，不填充缺失互动数据。'
  },
  NETEASE_NEWS: {
    platform: 'NETEASE_NEWS',
    name: '网易新闻公开文章',
    event_name: '网易新闻热点传播分析',
    feed_url: 'https://temp.163.com/special/00804KVA/cm_yaowen20200213.js?callback=data_callback',
    robots_url: 'https://www.163.com/robots.txt',
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_NETEASE_NEWS',
    compliance_note: '通过 HTTP GET 读取网易新闻公开要闻列表和 robots.txt 允许的文章详情页；评论数仅使用列表实际返回的 tienum。'
  },
  SOHU_NEWS: {
    platform: 'SOHU_NEWS',
    name: '搜狐新闻公开文章',
    event_name: '搜狐新闻热点传播分析',
    feed_url: 'https://news.sohu.com/',
    robots_url: 'https://news.sohu.com/robots.txt',
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_SOHU_NEWS',
    compliance_note: '通过 HTTP GET 读取 news.sohu.com 公开频道页和同域文章详情；不访问 robots.txt 禁止通用爬虫访问的 www.sohu.com 详情路径。'
  },
  SINA_NEWS: {
    platform: 'SINA_NEWS',
    name: '新浪新闻公开文章',
    event_name: '新浪新闻热点传播分析',
    feed_url: 'https://feed.mix.sina.com.cn/api/roll/get?pageid=153&lid=2509&num=20&page=1',
    robots_url: 'https://news.sina.com.cn/robots.txt',
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_SINA_NEWS',
    compliance_note: '通过 HTTP GET 读取新浪新闻公开滚动接口；正文摘要、关键词、发布时间和评论数只采用接口实际返回字段。'
  },
  THE_PAPER: {
    platform: 'THE_PAPER',
    name: '澎湃新闻公开文章',
    event_name: '澎湃新闻热点传播分析',
    feed_url: 'https://cache.thepaper.cn/contentapi/wwwIndex/rightSidebar',
    robots_url: 'https://www.thepaper.cn/robots.txt',
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_THE_PAPER',
    compliance_note: '通过 HTTP GET 读取澎湃新闻公开热榜接口和 robots.txt 允许的详情页；点赞和正文均来自公开返回，未明确提供的互动字段保持为空。'
  },
  WEIBO: {
    platform: 'WEIBO',
    name: '微博公开内容',
    event_name: '微博热点传播分析',
    feed_url: 'https://weibo.com/sitemap/auto.xml',
    robots_url: 'https://weibo.com/robots.txt',
    user_agent: 'ChatGPT-User',
    request_delay_ms: 1000,
    min_limit: PUBLIC_MIN_LIMIT,
    max_limit: PUBLIC_MAX_LIMIT,
    mode: 'REAL_HTTP_WEIBO_SITEMAP_DETAIL',
    compliance_note: '通过 HTTP GET 读取微博 robots.txt 对 ChatGPT-User 明确允许的 sitemap、/2/detail/ 和 /ttarticle/p/show 页面；不登录、不使用 Cookie，互动字段未公开时保持为空。'
  },
  OWN_DEPLOYED_COMMENTS: {
    platform: 'OWN_SITE',
    name: '新闻都知道公开评论 JSON',
    event_name: '新闻都知道评论传播分析',
    url: 'https://willowy-cupcake-717ed2.netlify.app/news.json',
    mode: 'REAL_HTTP_PUBLIC_JSON_SMALL_LIMIT',
    compliance_note: '通过 HTTP GET 真实读取新闻都知道公开 JSON；只保存接口实际返回的 seed_comments，不扩展声明数量、不补写假数据。'
  },
}

function argValue(name, fallback = '') {
  const prefix = `--${name}=`
  const found = process.argv.find(item => item.startsWith(prefix))
  return found ? found.slice(prefix.length) : fallback
}

function csvCell(value) {
  return `"${String(value ?? '').replace(/"/g, '""').replace(/[\r\n]+/g, ' ')}"`
}

function csvText(items) {
  const rows = items.map(item => CSV_COLUMNS.map(column => csvCell(item[column])).join(','))
  return `\uFEFF${CSV_COLUMNS.join(',')}\n${rows.join('\n')}${rows.length ? '\n' : ''}`
}

function sleep(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function requestHeaders(extra = {}) {
  return {
    'User-Agent': 'SocialHotspotAnalyticsStudentCrawler/1.0 (+small-limit-demo)',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.7',
    ...extra
  }
}

async function fetchText(url, options = {}) {
  let lastError
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const response = await fetch(url, {
        ...options,
        headers: requestHeaders(options.headers),
        signal: AbortSignal.timeout(options.timeout || 10000)
      })
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`)
      }
      const charset = String(response.headers.get('content-type') || '').match(/charset=([^;]+)/i)?.[1]?.trim().toLowerCase()
      const encoding = charset && /gbk|gb2312|gb18030/.test(charset) ? 'gb18030' : 'utf-8'
      return new TextDecoder(encoding).decode(await response.arrayBuffer())
    } catch (error) {
      lastError = error
      if (attempt === 0) {
        await sleep(500)
      }
    }
  }
  throw new Error(`${url}：${lastError?.message || '请求失败'}`)
}

function robotsRuleRegex(rule) {
  const escaped = String(rule || '')
    .replace(/[.+?^{}()|[\]\\]/g, '\\$&')
    .replace(/\*/g, '.*')
  return new RegExp(`^${escaped}${rule.endsWith('$') ? '' : '.*'}`)
}

function robotsAllowsUrl(robotsText, targetUrl, userAgent = '*') {
  const lines = String(robotsText || '').split(/\r?\n/).map(line => line.replace(/#.*$/, '').trim())
  const groups = []
  let current = null
  for (const line of lines) {
    if (!line) continue
    const separator = line.indexOf(':')
    if (separator < 0) continue
    const field = line.slice(0, separator).trim().toLowerCase()
    const value = line.slice(separator + 1).trim()
    if (field === 'user-agent') {
      if (!current || current.rules.length) {
        current = { agents: [], rules: [] }
        groups.push(current)
      }
      current.agents.push(value.toLowerCase())
    } else if (current && (field === 'allow' || field === 'disallow')) {
      current.rules.push({ type: field, value })
    }
  }
  const agent = String(userAgent || '*').toLowerCase()
  const specificGroups = groups.filter(group => group.agents.some(value => value !== '*' && agent.includes(value)))
  const selectedGroups = specificGroups.length ? specificGroups : groups.filter(group => group.agents.includes('*'))
  const rules = selectedGroups.flatMap(group => group.rules)
  if (!rules.length) return true
  const parsed = new URL(targetUrl)
  const path = parsed.pathname + parsed.search
  const matching = rules
    .filter(rule => rule.value && robotsRuleRegex(rule.value).test(path))
    .sort((left, right) => right.value.length - left.value.length || (left.type === 'allow' ? -1 : 1))
  return !matching.length || matching[0].type === 'allow'
}

async function assertRobotsAllowed(source, targetUrl) {
  const userAgent = source.user_agent || requestHeaders()['User-Agent']
  const robotsText = await fetchText(source.robots_url, { headers: { 'User-Agent': userAgent } })
  if (!robotsAllowsUrl(robotsText, targetUrl, userAgent)) {
    throw new Error(`robots.txt 不允许访问：${new URL(targetUrl).pathname}`)
  }
  return robotsText
}

async function fetchJson(url, options = {}) {
  const text = await fetchText(url, {
    ...options,
    headers: { Accept: 'application/json', ...(options.headers || {}) }
  })
  try {
    return JSON.parse(text)
  } catch (error) {
    throw new Error(`${url} 返回内容不是合法 JSON：${error.message}`)
  }
}

function textBetween(xml, tag) {
  const match = xml.match(new RegExp(`<${tag}[^>]*>([\\s\\S]*?)<\\/${tag}>`, 'i'))
  return match ? cleanXml(match[1]) : ''
}

function cleanXml(value) {
  return String(value || '')
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/<[^>]+>/g, '')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/\s+/g, ' ')
    .trim()
}

function absolutizeUrl(value, baseUrl) {
  try {
    return value ? new URL(value, baseUrl).toString() : ''
  } catch {
    return value || ''
  }
}

function commentPageUrl(baseUrl, id) {
  try {
    const page = new URL(baseUrl)
    page.pathname = '/'
    page.search = ''
    page.hash = `comment-${encodeURIComponent(String(id))}`
    return page.toString()
  } catch {
    return baseUrl || ''
  }
}

function parsePublishTime(value) {
  const sourceValue = String(value || '').trim()
  if (/^\d{10,13}$/.test(sourceValue)) {
    const numeric = Number(sourceValue)
    const timestamp = sourceValue.length === 10 ? numeric * 1000 : numeric
    return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : ''
  }
  const neteaseMatch = sourceValue.match(/^(\d{2})\/(\d{2})\/(\d{4})[ T](\d{2}:\d{2}:\d{2})$/)
  if (neteaseMatch) {
    const timestamp = Date.parse(`${neteaseMatch[3]}-${neteaseMatch[1]}-${neteaseMatch[2]}T${neteaseMatch[4]}+08:00`)
    return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : ''
  }
  const localMatch = sourceValue.match(/^(\d{4}-\d{2}-\d{2})[ T](\d{2}:\d{2})(?::(\d{2}))?$/)
  const normalized = localMatch
    ? `${localMatch[1]}T${localMatch[2]}:${localMatch[3] || '00'}+08:00`
    : sourceValue
  const stableLocal = sourceValue.length === 16 && sourceValue[4] === '-' && sourceValue[7] === '-' && sourceValue[10] === ' '
    ? `${sourceValue.replace(' ', 'T')}:00+08:00`
    : normalized
  const timestamp = Date.parse(stableLocal)
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : ''
}

function cleanTitle(value) {
  return decodeHtmlEntity(String(value || ''))
    .replace(/[_｜|][-—]?[^_｜|]{0,30}(?:新闻|搜狐|网易|新浪|澎湃|The Paper).*$/i, '')
    .trim()
}

function metaContent(html, key, attribute = 'name') {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const first = html.match(new RegExp(`<meta[^>]+${attribute}=["']${escaped}["'][^>]+content=["']([\\s\\S]*?)["'][^>]*>`, 'i'))
  const reversed = html.match(new RegExp(`<meta[^>]+content=["']([\\s\\S]*?)["'][^>]+${attribute}=["']${escaped}["'][^>]*>`, 'i'))
  return decodeHtmlEntity((first || reversed)?.[1] || '').trim()
}

function attributeValue(html, attribute) {
  const escaped = attribute.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  return decodeHtmlEntity(html.match(new RegExp(`${escaped}=["']([^"']+)["']`, 'i'))?.[1] || '').trim()
}

function htmlSection(html, startPattern, endPattern) {
  const start = html.search(startPattern)
  if (start < 0) return ''
  const tail = html.slice(start)
  const end = tail.search(endPattern)
  return end > 0 ? tail.slice(0, end) : tail
}

function htmlSectionUntil(html, startPattern, endPatterns) {
  const start = html.search(startPattern)
  if (start < 0) return ''
  const tail = html.slice(start)
  const endPositions = endPatterns.map(pattern => tail.search(pattern)).filter(position => position > 0)
  return endPositions.length ? tail.slice(0, Math.min(...endPositions)) : tail
}

function normalizeKeywords(value) {
  const values = Array.isArray(value) ? value : [value]
  return values.flatMap(item => {
    if (item === null || item === undefined) return []
    if (typeof item !== 'object') return String(item).split(',')
    return [item.name, item.keyword, item.tag, item.title, item.value].filter(Boolean)
  }).map(item => String(item).trim()).filter(Boolean).join(',')
}

function sitemapEntries(xml) {
  return [...String(xml || '').matchAll(/<url>\s*([\s\S]*?)<\/url>/gi)].map(match => ({
    url: textBetween(match[1], 'loc'),
    lastmod: textBetween(match[1], 'lastmod')
  })).filter(item => item.url)
}

function extractNextData(html) {
  const match = html.match(/<script[^>]+id=["']__NEXT_DATA__["'][^>]*>([\s\S]*?)<\/script>/i)
  if (!match) return null
  try {
    return JSON.parse(match[1])
  } catch (error) {
    throw new Error(`__NEXT_DATA__ JSON 解析失败：${error.message}`)
  }
}

function nullableCount(value) {
  if (value === null || value === undefined) return null
  const text = String(value).trim().replace(/,/g, '')
  if (!text || text === '-' || text.toLowerCase() === 'null') return null
  const unit = text.endsWith('亿') ? 100000000 : text.endsWith('万') ? 10000 : 1
  const numeric = Number(text.replace(/[亿万]$/, ''))
  return Number.isFinite(numeric) ? Math.max(0, Math.round(numeric * unit)) : null
}

function normalizeItem(item, source, index, eventId = CANONICAL_EVENT_ID) {
  const now = new Date().toISOString()
  const sourceId = String(item.id || '').trim()
  const stableKey = sourceId || item.article_url || item.article_title || item.content || `${source.platform}_${index + 1}`
  const stableId = sourceId || crypto.createHash('sha1').update(`${source.platform}:${stableKey}`).digest('hex').slice(0, 20)
  return {
    event_id: eventId,
    event_name: source.event_name,
    platform: source.platform,
    content_id: `${source.platform}_${stableId}`,
    parent_content_id: item.parent_content_id || '',
    content_type: item.content_type || (source.platform === 'TENCENT_NEWS' ? 'news' : 'comment'),
    title: item.article_title || item.topic || '',
    content_text: item.content || '',
    author_id: item.author_id || '',
    author_name: item.author_name || '',
    keywords: item.keyword || '',
    category: item.category || '',
    publish_time: item.publish_time || '',
    crawl_time: now,
    hot_rank: index + 1,
    location: item.location || '',
    user_age_group: item.user_age_group || '',
    user_gender: item.user_gender || '',
    source_url: item.source_url || item.url || '',
    image_url: item.image_url || '',
    like_count: nullableCount(item.like_count),
    repost_count: nullableCount(item.repost_count ?? item.forward_count),
    share_count: nullableCount(item.share_count),
    favorite_count: nullableCount(item.favorite_count),
    comment_count: nullableCount(item.comment_count),
    view_count: nullableCount(item.view_count)
  }
}

async function crawlOwnDeployedComments(source, limit) {
  const response = await fetch(source.url, {
    headers: {
      'User-Agent': 'SocialHotspotAnalyticsStudentCrawler/1.0 (+small-limit-demo)',
      'Accept': 'application/json'
    },
    signal: AbortSignal.timeout(8000)
  })
  if (!response.ok) throw new Error(`新闻都知道 JSON 请求失败：${response.status}`)
  let payload
  try {
    payload = JSON.parse(await response.text())
  } catch (error) {
    throw new Error(`新闻都知道返回内容不是合法 JSON：${error.message}`)
  }
  if (!Array.isArray(payload.seed_comments)) {
    throw new Error('新闻都知道 JSON 缺少 seed_comments 数组，未写入任何数据')
  }
  const items = payload.seed_comments.map(item => ({
    id: item.id,
    content: item.content,
    topic: item.topic,
    keyword: item.keyword,
    category: item.category,
    article_title: item.article_title,
    publish_time: parsePublishTime(item.published_at),
    url: commentPageUrl(absolutizeUrl(payload.source_page || source.url, source.url), item.id)
  })).filter(item => item.id && item.content && item.publish_time && item.url)
  const unique = new Map()
  for (const item of items) {
    if (!unique.has(item.url)) unique.set(item.url, item)
  }
  return {
    items: [...unique.values()].slice(0, limit),
    metadata: {
      synthetic: payload.synthetic === true,
      record_mode: payload.record_mode || 'unknown',
      declared_total_records: Number.isFinite(Number(payload.total_records)) ? Number(payload.total_records) : null,
      fetched_seed_records: payload.seed_comments.length,
      source_page: payload.source_page || ''
    }
  }
}

function decodeHtmlEntity(value) {
  const named = { nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" }
  return value.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (full, entity) => {
    const lower = entity.toLowerCase()
    if (lower.startsWith('#x')) {
      const code = Number.parseInt(lower.slice(2), 16)
      return Number.isFinite(code) ? String.fromCodePoint(code) : full
    }
    if (lower.startsWith('#')) {
      const code = Number.parseInt(lower.slice(1), 10)
      return Number.isFinite(code) ? String.fromCodePoint(code) : full
    }
    return named[lower] || full
  })
}

function htmlToText(value) {
  return decodeHtmlEntity(String(value || ''))
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(?:p|section|div|article|li|h[1-6])\s*>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/[ \t\f\v]+/g, ' ')
    .replace(/\n\s*\n+/g, '\n')
    .trim()
}

function extractAssignedObject(html, variableName) {
  const assignment = html.indexOf(variableName)
  if (assignment < 0) return null
  const start = html.indexOf('{', assignment)
  if (start < 0) return null
  let depth = 0
  let inString = false
  let escaped = false
  for (let index = start; index < html.length; index++) {
    const character = html[index]
    if (inString) {
      if (escaped) {
        escaped = false
      } else if (character === '\\') {
        escaped = true
      } else if (character === '"') {
        inString = false
      }
      continue
    }
    if (character === '"') {
      inString = true
    } else if (character === '{') {
      depth++
    } else if (character === '}') {
      depth--
      if (depth === 0) {
        try {
          return JSON.parse(html.slice(start, index + 1))
        } catch (error) {
          throw new Error(`${variableName} JSON 解析失败：${error.message}`)
        }
      }
    }
  }
  return null
}

function categoryName(value) {
  const categories = {
    politics: '政治',
    finance: '财经',
    economy: '财经',
    tech: '科技',
    society: '社会',
    social: '社会',
    sports: '体育',
    entertainment: '娱乐',
    world: '国际',
    international: '国际',
    military: '军事',
    health: '健康',
    education: '教育',
    automobile: '汽车',
    culture: '文化',
    travel: '旅游',
    domestic: '国内',
    law: '法治',
    history: '历史'
  }
  const text = String(value || '').trim()
  return categories[text.toLowerCase()] || text || '未分类'
}

function canonicalTencentUrl(candidate) {
  try {
    const parsed = new URL(candidate.url || '')
    if (!['new.qq.com', 'news.qq.com', 'view.inews.qq.com'].includes(parsed.hostname)) return ''
    const articleId = String(candidate.id || parsed.pathname.split('/').filter(Boolean).pop() || '').trim()
    if (!articleId || !/^[A-Za-z0-9]+$/.test(articleId)) return ''
    return `https://news.qq.com/rain/a/${articleId}`
  } catch {
    return ''
  }
}

function robotsAllowsRainPath(robotsText) {
  const lines = String(robotsText || '').split(/\r?\n/)
  const allow = lines
    .map(line => line.trim())
    .filter(line => /^allow\s*:/i.test(line))
    .map(line => line.replace(/^allow\s*:/i, '').trim())
    .filter(Boolean)
  const disallow = lines
    .map(line => line.trim())
    .filter(line => /^disallow\s*:/i.test(line))
    .map(line => line.replace(/^disallow\s*:/i, '').trim())
    .filter(Boolean)
  const path = '/rain/'
  const blocked = disallow.some(rule => path.startsWith(rule))
  const explicitlyAllowed = allow.some(rule => path.startsWith(rule) || rule.startsWith(path))
  return !blocked || explicitlyAllowed
}

function tencentCandidates(payload) {
  const candidates = []
  for (const entity of Array.isArray(payload?.data) ? payload.data : []) {
    for (const article of Array.isArray(entity?.article_list) ? entity.article_list : []) {
      const url = canonicalTencentUrl(article)
      if (url) candidates.push({ id: article.id, url, title: article.title || '' })
    }
  }
  const unique = new Map()
  for (const candidate of candidates) {
    if (!unique.has(candidate.id)) unique.set(candidate.id, candidate)
  }
  return [...unique.values()]
}

async function crawlTencentNews(source, limit, report) {
  const robotsText = await fetchText(source.robots_url)
  if (!robotsAllowsRainPath(robotsText)) {
    throw new Error('robots.txt 未允许访问 /rain/ 文章路径，已停止采集')
  }
  const feedPayload = await fetchJson(source.feed_url, { headers: { Referer: 'https://news.qq.com/' } })
  const candidates = tencentCandidates(feedPayload)
  const items = []
  const failures = []
  let attemptedCount = 0
  let commentSuccessCount = 0
  let commentFailureCount = 0
  for (const candidate of candidates) {
    if (items.length >= limit) break
    attemptedCount++
    if (attemptedCount > 1) await sleep(REQUEST_DELAY_MS)
    try {
      const articleHtml = await fetchText(candidate.url, { headers: { Referer: 'https://news.qq.com/' } })
      const article = extractAssignedObject(articleHtml, 'window.DATA')
      if (!article) throw new Error('详情页缺少 window.DATA')
      const articleId = String(article.article_id || candidate.id).trim()
      const body = htmlToText(article.originContent?.text || article.content || article.desc || '')
      if (!articleId || !article.title || !body) throw new Error('详情页缺少标题或正文')

      let commentCount = null
      let commentError = ''
      try {
        const commentUrl = `${source.comment_url}?apptype=web&article_id=${encodeURIComponent(articleId)}&reqNum=5&transparam=`
        const commentPayload = await fetchJson(commentUrl, { headers: { Referer: candidate.url } })
        commentCount = nullableCount(commentPayload?.comments?.count)
        if (commentCount !== null) commentSuccessCount++
        else commentError = '评论接口未返回 comments.count'
      } catch (error) {
        commentError = error.message
      }
      if (commentCount === null) commentFailureCount++

      const item = {
        id: articleId,
        article_title: String(article.title).trim(),
        content: body,
        category: categoryName(article.catalog1 || article.category),
        publish_time: parsePublishTime(article.pubtime || article.publish_time),
        url: canonicalTencentUrl({ id: articleId, url: article.url || candidate.url }),
        like_count: nullableCount(article.interationCount?.like),
        favorite_count: nullableCount(article.interationCount?.collect),
        comment_count: commentCount,
        forward_count: nullableCount(article.interationCount?.share),
        comment_error: commentError
      }
      items.push(normalizeItem(item, source, items.length))
      await report?.onItem?.(items[items.length - 1], items.length, candidates.length)
    } catch (error) {
      failures.push({ url: candidate.url, title: candidate.title, error: error.message })
      await report?.onFailure?.(candidate, error)
    }
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_article_detail',
      robots_checked: true,
      feed_url: source.feed_url,
      comment_url: source.comment_url,
      candidate_count: candidates.length,
      attempted_count: attemptedCount,
      detail_success_count: items.length,
      comment_success_count: commentSuccessCount,
      comment_failure_count: commentFailureCount,
      failed_articles: failures
    }
  }
}

function uniqueBy(items, keySelector) {
  const unique = new Map()
  for (const item of items) {
    const key = keySelector(item)
    if (key && !unique.has(key)) unique.set(key, item)
  }
  return [...unique.values()]
}

function articleIdFromUrl(url) {
  try {
    return new URL(url).pathname.split('/').filter(Boolean).pop()?.replace(/\.s?html?$/i, '') || ''
  } catch {
    return ''
  }
}

async function reportItem(report, item, count, total) {
  await report?.onItem?.(item, count, total)
}

async function crawlNeteaseNews(source, limit, report) {
  const robotsText = await fetchText(source.robots_url)
  const feedText = await fetchText(source.feed_url, { headers: { Referer: 'https://news.163.com/' } })
  let payload
  try {
    payload = JSON.parse(feedText.replace(/^\s*[A-Za-z0-9_$]+\s*\(/, '').replace(/\)\s*;?\s*$/, ''))
  } catch (error) {
    throw new Error(`网易新闻列表解析失败：${error.message}`)
  }
  const candidates = uniqueBy((Array.isArray(payload) ? payload : []).map(item => ({
    ...item,
    url: absolutizeUrl(item.docurl || item.tlink, source.feed_url)
  })).filter(item => {
    try {
      const parsed = new URL(item.url)
      return ['www.163.com', 'news.163.com'].includes(parsed.hostname) && /\/article\/[^/]+\.html$/i.test(parsed.pathname)
    } catch {
      return false
    }
  }), item => item.url)
  const items = []
  const failures = []
  let attemptedCount = 0
  for (const candidate of candidates) {
    if (items.length >= limit) break
    attemptedCount++
    if (!robotsAllowsUrl(robotsText, candidate.url)) {
      failures.push({ url: candidate.url, title: candidate.title, error: 'robots.txt 不允许访问' })
      continue
    }
    if (attemptedCount > 1) await sleep(REQUEST_DELAY_MS)
    try {
      const html = await fetchText(candidate.url, { headers: { Referer: 'https://news.163.com/' } })
      const bodyHtml = htmlSectionUntil(html, /<div[^>]+class=["'][^"']*post_body[^"']*["'][^>]*>/i, [
        /<div[^>]+class=["'][^"']*post_statement/i,
        /<div[^>]+class=["'][^"']*(?:post_recommend|post_recommends|post_side|post_bottom)[^"']*["']/i,
        /<footer\b/i
      ])
      const title = cleanTitle(html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)?.[1] || candidate.title)
      const content = htmlToText(bodyHtml) || metaContent(html, 'description')
      const item = normalizeItem({
        id: articleIdFromUrl(candidate.url),
        article_title: title,
        content,
        keyword: metaContent(html, 'keywords') || normalizeKeywords(candidate.keywords),
        category: attributeValue(html.slice(0, 5000), 'data-category') || candidate.channelname || candidate.label,
        publish_time: parsePublishTime(attributeValue(html.slice(0, 5000), 'data-publishtime') || candidate.time),
        url: candidate.url,
        comment_count: nullableCount(candidate.tienum),
        content_type: 'news'
      }, source, items.length)
      if (!item.content_id || !item.title || !item.content_text || !item.publish_time) throw new Error('详情页缺少标题、正文或发布时间')
      items.push(item)
      await reportItem(report, item, items.length, candidates.length)
    } catch (error) {
      failures.push({ url: candidate.url, title: candidate.title, error: error.message })
      await report?.onFailure?.(candidate, error)
    }
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_article_detail',
      robots_checked: true,
      candidate_count: candidates.length,
      attempted_count: attemptedCount,
      detail_success_count: items.length,
      failed_articles: failures
    }
  }
}

function sohuCandidates(payload) {
  const candidates = []
  const visit = value => {
    if (!value || typeof value !== 'object') return
    if (Array.isArray(value)) {
      value.forEach(visit)
      return
    }
    const rawUrl = value.url || value.link
    if ((value.title || value.name) && rawUrl) {
      try {
        const parsed = new URL(rawUrl, 'https://news.sohu.com/')
        if (['www.sohu.com', 'news.sohu.com'].includes(parsed.hostname) && /^\/a\/[^/]+/.test(parsed.pathname)) {
          parsed.hostname = 'news.sohu.com'
          parsed.search = ''
          parsed.hash = ''
          candidates.push({ id: parsed.pathname.split('/').pop(), title: value.title || value.name, url: parsed.toString() })
        }
      } catch {
        // Ignore malformed navigation links in the channel payload.
      }
    }
    Object.values(value).forEach(visit)
  }
  visit(payload)
  return uniqueBy(candidates, item => item.url)
}

async function crawlSohuNews(source, limit, report) {
  const robotsText = await fetchText(source.robots_url)
  if (!robotsAllowsUrl(robotsText, source.feed_url)) throw new Error('robots.txt 不允许访问搜狐新闻频道页')
  const indexHtml = await fetchText(source.feed_url)
  const blockData = extractAssignedObject(indexHtml, 'window.blockRenderData')
  if (!blockData) throw new Error('搜狐新闻频道页缺少 window.blockRenderData')
  const candidates = sohuCandidates(blockData)
  const items = []
  const failures = []
  let attemptedCount = 0
  for (const candidate of candidates) {
    if (items.length >= limit) break
    attemptedCount++
    if (!robotsAllowsUrl(robotsText, candidate.url)) {
      failures.push({ ...candidate, error: 'robots.txt 不允许访问' })
      continue
    }
    if (attemptedCount > 1) await sleep(REQUEST_DELAY_MS)
    try {
      const html = await fetchText(candidate.url, { headers: { Referer: source.feed_url } })
      const bodyHtml = htmlSection(html, /<article[^>]+id=["']mp-editor["'][^>]*>/i, /<\/article>/i)
      const title = cleanTitle(html.match(/<h1[^>]*>([\s\S]*?)<\/h1>/i)?.[1] || html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1] || candidate.title)
      const item = normalizeItem({
        id: candidate.id,
        article_title: title,
        content: htmlToText(bodyHtml) || metaContent(html, 'description'),
        keyword: metaContent(html, 'keywords'),
        publish_time: parsePublishTime(attributeValue(htmlSection(html, /id=["']news-time["']/i, /<\/span>/i), 'data-val') || html.match(/id=["']news-time["'][^>]*>([\s\S]*?)<\/span>/i)?.[1]),
        url: candidate.url,
        content_type: 'news'
      }, source, items.length)
      if (!item.content_id || !item.title || !item.content_text || !item.publish_time) throw new Error('详情页缺少标题、正文或发布时间')
      items.push(item)
      await reportItem(report, item, items.length, candidates.length)
    } catch (error) {
      failures.push({ ...candidate, error: error.message })
      await report?.onFailure?.(candidate, error)
    }
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_article_detail',
      robots_checked: true,
      candidate_count: candidates.length,
      attempted_count: attemptedCount,
      detail_success_count: items.length,
      failed_articles: failures
    }
  }
}

async function crawlSinaNews(source, limit, report) {
  const robotsText = await fetchText(source.robots_url)
  const separator = source.feed_url.includes('?') ? '&' : '?'
  const payload = await fetchJson(`${source.feed_url}${separator}r=${Date.now()}`, { headers: { Referer: 'https://news.sina.com.cn/' } })
  const candidates = uniqueBy(Array.isArray(payload?.result?.data) ? payload.result.data : [], item => item.docid || item.oid || item.url)
  const items = []
  for (const candidate of candidates) {
    if (items.length >= limit) break
    const url = absolutizeUrl(candidate.url || candidate.wapurl, 'https://news.sina.com.cn/')
    if (!url || !robotsAllowsUrl(robotsText, url)) continue
    const item = normalizeItem({
      id: String(candidate.docid || candidate.oid || articleIdFromUrl(url)).replace(/^comos:/, ''),
      article_title: candidate.title,
      content: candidate.intro || candidate.summary || candidate.wapsummary,
      keyword: candidate.keywords,
      publish_time: parsePublishTime(candidate.ctime || candidate.mtime || candidate.intime),
      url,
      like_count: nullableCount(candidate.praise),
      comment_count: nullableCount(candidate.comment_total),
      content_type: 'news'
    }, source, items.length)
    if (!item.content_id || !item.title || !item.content_text || !item.publish_time) continue
    items.push(item)
    await reportItem(report, item, items.length, candidates.length)
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_json_feed',
      robots_checked: true,
      candidate_count: candidates.length,
      detail_success_count: items.length
    }
  }
}

async function crawlThePaper(source, limit, report) {
  const robotsText = await fetchText(source.robots_url)
  const payload = await fetchJson(source.feed_url, { headers: { Referer: 'https://www.thepaper.cn/' } })
  const feedItems = [...(payload?.data?.hotNews || []), ...(payload?.data?.editorHandpicked || [])]
  const candidates = uniqueBy(feedItems.filter(item => item?.contId && item?.name && !item?.paywalled), item => String(item.contId))
  const items = []
  const failures = []
  let attemptedCount = 0
  for (const candidate of candidates) {
    if (items.length >= limit) break
    const url = `https://www.thepaper.cn/newsDetail_forward_${candidate.contId}`
    attemptedCount++
    if (!robotsAllowsUrl(robotsText, url)) {
      failures.push({ url, title: candidate.name, error: 'robots.txt 不允许访问' })
      continue
    }
    if (attemptedCount > 1) await sleep(REQUEST_DELAY_MS)
    try {
      const html = await fetchText(url, { headers: { Referer: 'https://www.thepaper.cn/' } })
      const detail = extractNextData(html)?.props?.pageProps?.detailData?.contentDetail
      if (!detail) throw new Error('详情页缺少 contentDetail')
      const item = normalizeItem({
        id: String(detail.contId || candidate.contId),
        article_title: detail.name || candidate.name,
        content: htmlToText(detail.content) || detail.summary || candidate.name,
        keyword: detail.tags || (Array.isArray(candidate.tagList) ? candidate.tagList.map(tag => tag.tag).join(',') : ''),
        category: detail.nodeInfo?.name || candidate.nodeInfo?.name,
        publish_time: parsePublishTime(detail.publishTime || detail.pubTime || candidate.publishTime || candidate.pubTimeLong),
        url,
        like_count: nullableCount(candidate.praiseTimes),
        content_type: Number(detail.contType) === 9 ? 'video' : 'news'
      }, source, items.length)
      if (!item.content_id || !item.title || !item.content_text || !item.publish_time) throw new Error('详情页缺少标题、正文或发布时间')
      items.push(item)
      await reportItem(report, item, items.length, candidates.length)
    } catch (error) {
      failures.push({ url, title: candidate.name, error: error.message })
      await report?.onFailure?.({ url, title: candidate.name }, error)
    }
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_article_detail',
      robots_checked: true,
      candidate_count: candidates.length,
      attempted_count: attemptedCount,
      detail_success_count: items.length,
      failed_articles: failures
    }
  }
}

async function crawlWeibo(source, limit, report) {
  const robotsText = await assertRobotsAllowed(source, source.feed_url)
  const sitemap = await fetchText(source.feed_url, { headers: { 'User-Agent': source.user_agent } })
  const candidates = uniqueBy(sitemapEntries(sitemap).map(entry => {
    try {
      const parsed = new URL(entry.url)
      parsed.searchParams.delete('utm_source')
      return { ...entry, url: parsed.toString() }
    } catch {
      return null
    }
  }).filter(candidate => candidate && robotsAllowsUrl(robotsText, candidate.url, source.user_agent)
      && (/^\/2\/detail\/\d+\/?$/.test(new URL(candidate.url).pathname)
        || /^\/ttarticle\/p\/show$/.test(new URL(candidate.url).pathname))), item => item.url)
  const items = []
  const failures = []
  let attemptedCount = 0
  for (const candidate of candidates) {
    if (items.length >= limit) break
    attemptedCount++
    if (attemptedCount > 1) await sleep(source.request_delay_ms || REQUEST_DELAY_MS)
    try {
      const html = await fetchText(candidate.url, {
        headers: { 'User-Agent': source.user_agent, Referer: source.feed_url },
        timeout: 12000
      })
      const parsedUrl = new URL(candidate.url)
      const detailBody = html.match(/<div[^>]+class=["'][^"']*htmlText[^"']*["'][^>]*>([\s\S]*?)<\/div>/i)?.[1]
      const articleBody = htmlSectionUntil(html, /<div[^>]+node-type=["']contentBody["'][^>]*>/i, [
        /<div[^>]+class=["'][^"']*(?:WB_artical_bottom|artical_add_box)[^"']*["']/i,
        /<script\b/i
      ])
      const isArticle = parsedUrl.pathname.startsWith('/ttarticle/')
      const extractedContent = htmlToText(detailBody || articleBody) || metaContent(html, 'description')
      const extractedTitle = metaContent(html, 'og:title', 'property') || cleanTitle(html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1])
      const item = normalizeItem({
        id: isArticle ? parsedUrl.searchParams.get('id') : parsedUrl.pathname.split('/').filter(Boolean).pop(),
        article_title: extractedTitle === '微博正文' ? extractedContent.slice(0, 60) : extractedTitle,
        content: extractedContent,
        keyword: metaContent(html, 'keywords') || metaContent(html, 'og:keywords', 'property'),
        publish_time: parsePublishTime(metaContent(html, 'article:published_time', 'property') || candidate.lastmod),
        url: candidate.url,
        content_type: isArticle ? 'article' : 'post'
      }, source, items.length)
      if (!item.content_id || !item.title || !item.content_text || !item.publish_time) throw new Error('详情页缺少标题、正文或发布时间')
      items.push(item)
      await reportItem(report, item, items.length, candidates.length)
    } catch (error) {
      failures.push({ ...candidate, error: error.message })
      await report?.onFailure?.(candidate, error)
    }
  }
  return {
    items,
    metadata: {
      synthetic: false,
      record_mode: 'live_public_sitemap_detail',
      robots_checked: true,
      candidate_count: candidates.length,
      attempted_count: attemptedCount,
      detail_success_count: items.length,
      failed_articles: failures,
      interaction_fields: 'not_public_in_sitemap_pages'
    }
  }
}

async function main() {
  const sourceKey = argValue('source', 'OWN_DEPLOYED_COMMENTS').toUpperCase()
  const outputMode = argValue('output', 'human').toLowerCase()
  const humanOutput = outputMode === 'human'
  const progressMode = argValue('progress', humanOutput ? 'stdout' : 'none').toLowerCase()
  const progressStream = progressMode === 'stderr' ? process.stderr : process.stdout
  const showProgress = progressMode === 'stdout' || progressMode === 'stderr'
  const source = SOURCES[sourceKey]

  if (!source) {
    throw new Error(`Unsupported source: ${sourceKey}`)
  }

  const sourceMin = source.min_limit || MIN_LIMIT
  const sourceMax = source.max_limit || MAX_LIMIT
  const limit = Math.max(sourceMin, Math.min(sourceMax, Number(argValue('limit', String(sourceMin))) || sourceMin))
  const eventId = argValue('eventId', CANONICAL_EVENT_ID)

  if (humanOutput) {
    process.stdout.write(`开始采集：${source.name}（最多 ${limit} 条）\n`)
    process.stdout.write(`公开源：${source.url || source.feed_url}\n\n`)
  }

  let warning = ''
  let rawItems = []
  let sourceMetadata = {}
  const crawlers = {
    TENCENT_NEWS: crawlTencentNews,
    NETEASE_NEWS: crawlNeteaseNews,
    SOHU_NEWS: crawlSohuNews,
    SINA_NEWS: crawlSinaNews,
    THE_PAPER: crawlThePaper,
    WEIBO: crawlWeibo,
    OWN_DEPLOYED_COMMENTS: crawlOwnDeployedComments
  }
  try {
    const fetched = await crawlers[sourceKey](source, limit, {
        onItem: async (item, index, total) => {
          if (!showProgress) return
          progressStream.write(`[${index}/${Math.min(limit, total)}] 已采集${source.name}：${item.title}\n`)
          progressStream.write(`          分类：${item.category || '未分类'}｜发布时间：${item.publish_time || '未返回'}\n`)
          progressStream.write(`          互动：点赞 ${item.like_count ?? '未返回'}｜收藏 ${item.favorite_count ?? '未返回'}｜评论 ${item.comment_count ?? '未返回'}｜转发 ${item.forward_count ?? '未返回'}\n`)
          progressStream.write(`          正文：${item.content_text.slice(0, 100)}${item.content_text.length > 100 ? '…' : ''}\n`)
          progressStream.write(`          原文：${item.url}\n`)
        },
        onFailure: async (candidate, error) => {
          if (showProgress) progressStream.write(`[跳过] ${source.name}详情失败：${candidate.url}（${error.message}）\n`)
        }
    })
    rawItems = fetched.items
    sourceMetadata = fetched.metadata
  } catch (error) {
    warning = `在线采集失败，未写入虚假样例：${error.message}`
    rawItems = []
  }
  if (!warning && sourceKey === 'TENCENT_NEWS') {
    const commentFailures = Number(sourceMetadata.comment_failure_count || 0)
    const articleFailures = Array.isArray(sourceMetadata.failed_articles) ? sourceMetadata.failed_articles.length : 0
    if (commentFailures || articleFailures) {
      warning = `本次有 ${commentFailures} 条评论数未返回、${articleFailures} 篇详情未成功；对应字段保留为空，未填充模拟数据。`
    }
  }
  if (!warning && sourceKey !== 'TENCENT_NEWS' && Array.isArray(sourceMetadata.failed_articles) && sourceMetadata.failed_articles.length) {
    warning = `本次有 ${sourceMetadata.failed_articles.length} 篇详情未成功，已跳过且未填充模拟数据。`
  }
  if (!warning && rawItems.length === 0) {
    warning = `${source.name}本次没有返回可用记录，系统未写入虚假样例。`
  }

  const sourceItems = rawItems.slice(0, limit)
  const items = []
  for (let index = 0; index < sourceItems.length; index++) {
    const normalized = sourceKey !== 'OWN_DEPLOYED_COMMENTS'
      ? { ...sourceItems[index], event_id: eventId }
      : normalizeItem(sourceItems[index], source, index, eventId)
    items.push(normalized)
    if (showProgress && sourceKey === 'OWN_DEPLOYED_COMMENTS') {
      const title = normalized.title || normalized.content_text || '无标题评论'
      progressStream.write(`[${index + 1}/${sourceItems.length}] 已采集评论：${title}\n`)
      if (normalized.url) {
        progressStream.write(`          原文：${normalized.url}\n`)
      }
      if (index < sourceItems.length - 1) {
        await sleep(REQUEST_DELAY_MS)
      }
    }
  }
  const result = {
    source: sourceKey,
    source_name: source.name,
    event_id: eventId,
    event_name: source.event_name,
    source_url: source.url || source.feed_url,
    platform: source.platform,
    limit,
    count: items.length,
    mode: source.mode,
    source_metadata: sourceMetadata,
    compliance_note: source.compliance_note,
    warning,
    items
  }
  if (humanOutput) {
    process.stdout.write('\n')
    if (warning) {
      process.stdout.write(`提示：${warning}\n`)
    }
    process.stdout.write(`采集完成：${items.length} 条\n`)
    process.stdout.write('结果已保留公开源链接，可用于演示时核验来源。\n')
    return
  }
  if (outputMode === 'csv') {
    process.stdout.write(csvText(items))
  } else {
    process.stdout.write(JSON.stringify(result))
  }
}

main().catch(error => {
  process.stderr.write(error.stack || String(error))
  process.exit(1)
})
