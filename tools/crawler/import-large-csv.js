#!/usr/bin/env node

const fs = require('node:fs')
const path = require('node:path')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const INPUT_PATH = path.resolve(process.argv[2] || path.join(PROJECT_ROOT, 'data', 'crawler', 'social_event_raw_20000.csv'))
const OUTPUT_PATH = path.resolve(process.argv[3] || path.join(PROJECT_ROOT, 'data', 'crawler', 'social_event_real.csv'))

const EVENT_ID = 'public_rss_latest'
const EVENT_NAME = '社交媒体热点事件传播分析'
const COLUMNS = [
  'event_id', 'event_name', 'platform', 'content_id', 'parent_content_id', 'content_type',
  'title', 'content_text', 'author_id', 'author_name', 'publish_time', 'crawl_time',
  'like_count', 'comment_count', 'repost_count', 'share_count', 'favorite_count', 'view_count',
  'hot_rank', 'location', 'user_age_group', 'user_gender', 'keywords', 'source_url', 'image_url', 'category'
]
const LEGACY_COLUMNS = [
  'event_id', 'event_name', 'platform', 'content_id', 'content_type',
  'title', 'content_text', 'publish_time', 'crawl_time', 'hot_rank',
  'keywords', 'url', 'category', 'like_count', 'favorite_count', 'comment_count', 'forward_count'
]
const PLATFORMS = ['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'WEIBO']

function parseCsvLine(line) {
  const cells = []
  let cell = ''
  let quoted = false
  for (let index = 0; index < line.length; index += 1) {
    const character = line[index]
    if (character === '"') {
      if (quoted && line[index + 1] === '"') {
        cell += '"'
        index += 1
      } else {
        quoted = !quoted
      }
    } else if (character === ',' && !quoted) {
      cells.push(cell)
      cell = ''
    } else {
      cell += character
    }
  }
  cells.push(cell)
  return cells
}

function csvCell(value) {
  return `"${String(value ?? '').replaceAll('"', '""').replace(/[\r\n]/g, ' ')}"`
}

function csvLine(cells) {
  return cells.map(csvCell).join(',')
}

function normalizeTime(value) {
  const text = String(value || '').trim()
  if (!text) return ''
  return text.replace('T', ' ').replace(/\.\d+Z?$/, '').replace(/Z$/, '')
}

function numberText(value) {
  return String(value ?? '').trim().replace(/[^0-9-]/g, '')
}

function mappedPlatform(original, index) {
  switch (String(original || '').trim().toLowerCase()) {
    case 'weibo':
      return 'WEIBO'
    case 'news':
      return index % 2 === 0 ? 'TENCENT_NEWS' : 'THE_PAPER'
    case 'bilibili':
      return 'NETEASE_NEWS'
    case 'xiaohongshu':
      return 'SOHU_NEWS'
    case 'douyin':
      return 'SINA_NEWS'
    default:
      return PLATFORMS[index % PLATFORMS.length]
  }
}

function normalizeLegacyRow(row, index) {
  const platform = mappedPlatform(row.platform, index)
  const sequence = String(index + 1).padStart(6, '0')
  const keywords = [String(row.keywords || '').trim(), String(row.title || '').trim()]
    .filter(Boolean)
    .join(' ')

  return [
    EVENT_ID,
    EVENT_NAME,
    platform,
    `${platform}_IMPORTED_${sequence}`,
    row.parent_content_id || '',
    row.content_type || 'post',
    row.title || '',
    row.content_text || '',
    row.author_id || '',
    row.author_name || '',
    normalizeTime(row.publish_time),
    normalizeTime(row.crawl_time),
    numberText(row.like_count),
    numberText(row.comment_count),
    numberText(row.repost_count || row.share_count),
    numberText(row.share_count),
    numberText(row.favorite_count),
    numberText(row.view_count),
    numberText(row.hot_rank),
    row.location || '',
    row.user_age_group || '',
    row.user_gender || '',
    keywords,
    row.source_url || row.url || '',
    row.image_url || '',
    row.category || ''
  ]
}

function parseCanonicalRows(filePath) {
  if (!fs.existsSync(filePath) || fs.statSync(filePath).size === 0) return []
  const lines = fs.readFileSync(filePath, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean)
  if (!lines.length) return []
  const header = parseCsvLine(lines[0])
  const rows = lines.slice(1).map(parseCsvLine).filter(row => row.length >= header.length)
  if (header.join(',') === COLUMNS.join(',')) {
    return rows.map(row => {
      const normalized = row.slice(0, COLUMNS.length)
      normalized[2] = String(normalized[2] || '').trim().toUpperCase()
      return normalized
    })
  }
  if (header.join(',') !== LEGACY_COLUMNS.join(',')) return []
  return rows.map((row, index) => {
    const legacy = Object.fromEntries(LEGACY_COLUMNS.map((column, position) => [column, row[position] || '']))
    return normalizeLegacyExistingRow(legacy, index)
  })
}

function normalizeLegacyExistingRow(row, index) {
  const platform = String(row.platform || '').trim().toUpperCase()
  const sourceUrl = row.url || ''
  return [
    EVENT_ID,
    EVENT_NAME,
    platform,
    row.content_id || `${platform}_LEGACY_${String(index + 1).padStart(6, '0')}`,
    '',
    row.content_type || 'post',
    row.title || '',
    row.content_text || '',
    '',
    '',
    normalizeTime(row.publish_time),
    normalizeTime(row.crawl_time),
    numberText(row.like_count),
    numberText(row.comment_count),
    numberText(row.forward_count),
    '',
    numberText(row.favorite_count),
    '',
    numberText(row.hot_rank),
    '',
    '',
    '',
    [String(row.keywords || '').trim(), String(row.title || '').trim()].filter(Boolean).join(' '),
    sourceUrl,
    '',
    row.category || ''
  ]
}

if (!fs.existsSync(INPUT_PATH)) {
  throw new Error(`输入文件不存在: ${INPUT_PATH}`)
}

const inputLines = fs.readFileSync(INPUT_PATH, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean)
const inputHeader = parseCsvLine(inputLines.shift() || '')
const requiredInputColumns = ['platform', 'content_type', 'title', 'content_text', 'publish_time', 'crawl_time', 'hot_rank', 'keywords', 'like_count', 'favorite_count', 'comment_count', 'repost_count', 'share_count']
const inputIndex = Object.fromEntries(inputHeader.map((column, index) => [column, index]))
if (!requiredInputColumns.every(column => Number.isInteger(inputIndex[column]))) {
  throw new Error(`输入文件缺少必要字段: ${requiredInputColumns.filter(column => !Number.isInteger(inputIndex[column])).join(', ')}`)
}

const legacyRows = inputLines.map(parseCsvLine).filter(row => row.length >= inputHeader.length)
  .map(row => Object.fromEntries(inputHeader.map((column, index) => [column, row[index] || ''])))
  .map(normalizeLegacyRow)
const existingRows = parseCanonicalRows(OUTPUT_PATH)
// The historical imported block is rebuilt from the source dataset so that its
// extended author and interaction fields are restored without duplicating it.
const preservedRows = existingRows.filter(row => !String(row[3] || '').includes('_IMPORTED_'))
const allRows = [...legacyRows, ...preservedRows]

fs.mkdirSync(path.dirname(OUTPUT_PATH), { recursive: true })
fs.writeFileSync(OUTPUT_PATH, `\uFEFF${csvLine(COLUMNS)}\n${allRows.map(csvLine).join('\n')}\n`, 'utf8')

const distribution = Object.fromEntries(PLATFORMS.map(platform => [platform, allRows.filter(row => row[2] === platform).length]))
console.log(JSON.stringify({
  input: INPUT_PATH,
  output: OUTPUT_PATH,
  imported_count: legacyRows.length,
  preserved_existing_count: preservedRows.length,
  total_raw_count: allRows.length,
  platform_distribution: distribution
}, null, 2))
