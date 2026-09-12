#!/usr/bin/env node

const fs = require('node:fs')
const path = require('node:path')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const OUTPUT_PATH = path.resolve(process.argv[2] || path.join(PROJECT_ROOT, 'data', 'crawler', 'social_event_real.csv'))
const EVENT_ID = 'public_rss_latest'
const EVENT_NAME = '社交媒体热点事件传播分析'
const PLATFORMS = ['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'WEIBO']
const COLUMNS = [
  'event_id', 'event_name', 'platform', 'content_id', 'parent_content_id', 'content_type',
  'title', 'content_text', 'author_id', 'author_name', 'publish_time', 'crawl_time',
  'like_count', 'comment_count', 'repost_count', 'share_count', 'favorite_count', 'view_count',
  'hot_rank', 'location', 'user_age_group', 'user_gender', 'keywords', 'source_url', 'image_url', 'category'
]
const FIXTURE_PREFIX = 'QUALITY_DIRTY_'

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

if (!fs.existsSync(OUTPUT_PATH)) {
  throw new Error(`统一 Raw CSV 不存在: ${OUTPUT_PATH}`)
}

const source = fs.readFileSync(OUTPUT_PATH, 'utf8').replace(/^\uFEFF/, '')
const lines = source.split(/\r?\n/).filter(line => line.length > 0)
if (!lines.length || parseCsvLine(lines[0]).join(',') !== COLUMNS.join(',')) {
  throw new Error('统一 Raw CSV 表头不是 canonical 26 字段格式')
}

const existingFixtureCount = lines.slice(1)
  .map(parseCsvLine)
  .filter(row => String(row[3] || '').startsWith(FIXTURE_PREFIX))
  .length

if (existingFixtureCount >= 50) {
  console.log(JSON.stringify({ output: OUTPUT_PATH, added_count: 0, existing_fixture_count: existingFixtureCount }, null, 2))
  process.exit(0)
}

const dirtyRows = Array.from({ length: 50 }, (_, index) => {
  const sequence = String(index + 1).padStart(4, '0')
  const platform = PLATFORMS[index % PLATFORMS.length]
  const missingField = index < 17 ? 'title' : index < 34 ? 'content_text' : 'publish_time'
  return [
    EVENT_ID,
    EVENT_NAME,
    platform,
    `${FIXTURE_PREFIX}${sequence}`,
    '',
    'post',
    missingField === 'title' ? '' : `质量校验测试记录 ${sequence}`,
    missingField === 'content_text' ? '' : `用于验证 ETL 字段清洗的测试正文 ${sequence}`,
    '',
    '',
    missingField === 'publish_time' ? '' : '2026-08-31 12:00:00',
    '2026-08-31 12:05:00',
    '0',
    '0',
    '0',
    '0',
    '0',
    '0',
    String(900 + index),
    '',
    '',
    '',
    '质量校验 ETL 脏数据',
    '',
    '',
    'quality_test'
  ]
})

const allLines = [...lines, ...dirtyRows.map(csvLine)]
fs.writeFileSync(OUTPUT_PATH, `\uFEFF${allLines.join('\n')}\n`, 'utf8')

console.log(JSON.stringify({
  output: OUTPUT_PATH,
  added_count: dirtyRows.length,
  total_raw_count: allLines.length - 1,
  fixture_prefix: FIXTURE_PREFIX,
  invalid_fields: { title: 17, content_text: 17, publish_time: 16 }
}, null, 2))
