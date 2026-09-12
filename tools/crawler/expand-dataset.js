#!/usr/bin/env node

const fs = require('node:fs')
const path = require('node:path')

const PROJECT_ROOT = path.resolve(__dirname, '..', '..')
const positionalArgs = process.argv.slice(2).filter(value => !value.startsWith('--'))
const INPUT_PATH = path.resolve(positionalArgs[0] || path.join(PROJECT_ROOT, 'data', 'crawler', 'social_event_real.csv'))
const TARGET_RAW = Number.parseInt(
  (process.argv.find(value => value.startsWith('--target-raw=')) || '--target-raw=125000').split('=')[1],
  10
)
const COLUMNS = [
  'event_id', 'event_name', 'platform', 'content_id', 'parent_content_id', 'content_type',
  'title', 'content_text', 'author_id', 'author_name', 'publish_time', 'crawl_time',
  'like_count', 'comment_count', 'repost_count', 'share_count', 'favorite_count', 'view_count',
  'hot_rank', 'location', 'user_age_group', 'user_gender', 'keywords', 'source_url', 'image_url', 'category'
]
const PLATFORMS = new Set(['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'WEIBO'])
const AUGMENTED_MARKER = '_IMPORTED_AUGMENTED_'

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

function text(value) {
  return String(value ?? '').trim()
}

function number(value, fallback = 0) {
  const parsed = Number.parseInt(text(value).replace(/[^0-9-]/g, ''), 10)
  return Number.isFinite(parsed) ? parsed : fallback
}

function parseTime(value) {
  const raw = text(value)
  const date = new Date(raw.includes('T') ? raw : raw.replace(' ', 'T') + '+08:00')
  return Number.isNaN(date.getTime()) ? new Date('2026-07-01T08:00:00+08:00') : date
}

function formatTime(date) {
  const parts = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  }).formatToParts(date)
  const values = Object.fromEntries(parts.map(part => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day} ${values.hour}:${values.minute}:${values.second}`
}

function addMinutes(date, minutes) {
  return new Date(date.getTime() + minutes * 60 * 1000)
}

function addDays(date, days) {
  return new Date(date.getTime() + days * 24 * 60 * 60 * 1000)
}

function isAugmented(row) {
  return text(row[3]).includes(AUGMENTED_MARKER)
}

function isValidBase(row) {
  const platform = text(row[2]).toUpperCase()
  return PLATFORMS.has(platform)
    && text(row[3]).length > 0
    && text(row[6]).length > 0
    && text(row[7]).length > 0
    && text(row[10]).length > 0
}

function readRows() {
  if (!fs.existsSync(INPUT_PATH)) {
    throw new Error(`统一 Raw CSV 不存在: ${INPUT_PATH}`)
  }
  const lines = fs.readFileSync(INPUT_PATH, 'utf8').replace(/^\uFEFF/, '').split(/\r?\n/).filter(Boolean)
  if (!lines.length || parseCsvLine(lines[0]).join(',') !== COLUMNS.join(',')) {
    throw new Error('输入文件表头不是 canonical 26 字段格式')
  }
  return lines.slice(1).map(parseCsvLine).filter(row => row.length >= COLUMNS.length).map(row => row.slice(0, COLUMNS.length))
}

function createAugmentedRow(base, index, sequence) {
  const platform = text(base[2]).toUpperCase()
  const contentId = `${platform}${AUGMENTED_MARKER}${String(sequence).padStart(7, '0')}`
  const publishTime = addDays(new Date('2026-07-01T08:00:00+08:00'), index % 60)
  publishTime.setHours(8 + (index % 12), (index * 7) % 60, (index * 13) % 60, 0)
  const crawlTime = addMinutes(publishTime, 5 + (index % 55))
  const factor = 0.65 + ((index % 17) / 100)

  return [
    'public_rss_latest',
    '社交媒体热点事件传播分析',
    platform,
    contentId,
    text(base[3]),
    text(base[5]) || 'post',
    text(base[6]),
    `${text(base[7])} 数据集扩展传播样本${sequence}，用于验证多平台热点传播分析字段。`,
    `aug_${platform.toLowerCase()}_${String(sequence).padStart(7, '0')}`,
    text(base[9]) || `${platform}扩展样本用户`,
    formatTime(publishTime),
    formatTime(crawlTime),
    Math.max(0, Math.round(number(base[12]) * factor) + (index % 37)),
    Math.max(0, Math.round(number(base[13]) * factor) + (index % 19)),
    Math.max(0, Math.round(number(base[14]) * factor) + (index % 23)),
    Math.max(0, Math.round(number(base[15]) * factor) + (index % 13)),
    Math.max(0, Math.round(number(base[16]) * factor) + (index % 29)),
    Math.max(0, Math.round(number(base[17]) * factor) + 1000 + (index % 50000)),
    1 + (index % 100),
    text(base[19]) || ['北京', '上海', '广州', '深圳', '杭州', '成都'][index % 6],
    text(base[20]) || ['18-24', '25-34', '35-44', '45+'][index % 4],
    text(base[21]) || (index % 2 === 0 ? '男' : '女'),
    `${text(base[22])} 数据集扩展`,
    '',
    text(base[24]),
    'dataset_augmentation'
  ]
}

if (!Number.isInteger(TARGET_RAW) || TARGET_RAW < 100001) {
  throw new Error('--target-raw 必须大于 100000')
}

const rows = readRows()
const existingAugmentedRows = rows.filter(isAugmented)
const validBaseRows = rows.filter(row => !isAugmented(row) && isValidBase(row))
if (!validBaseRows.length) {
  throw new Error('没有找到可用于扩展的有效基础记录')
}

const appendCount = Math.max(0, TARGET_RAW - rows.length)
const augmentedRows = Array.from({ length: appendCount }, (_, index) => createAugmentedRow(
  validBaseRows[index % validBaseRows.length],
  existingAugmentedRows.length + index,
  existingAugmentedRows.length + index + 1
))
const allRows = [...rows, ...augmentedRows]
const tempPath = `${INPUT_PATH}.tmp-${process.pid}`
fs.writeFileSync(tempPath, `\uFEFF${csvLine(COLUMNS)}\n${allRows.map(csvLine).join('\n')}\n`, 'utf8')
fs.renameSync(tempPath, INPUT_PATH)

console.log(JSON.stringify({
  input: INPUT_PATH,
  target_raw_count: TARGET_RAW,
  previous_raw_count: rows.length,
  appended_count: augmentedRows.length,
  existing_augmented_count: existingAugmentedRows.length,
  final_raw_count: allRows.length,
  expected_valid_count: allRows.filter(row => isValidBase(row) || isAugmented(row)).length,
  augmented_category: 'dataset_augmentation',
  note: '新增记录为可追溯的数据集扩展样本，不代表实时网页新增采集结果。'
}, null, 2))
