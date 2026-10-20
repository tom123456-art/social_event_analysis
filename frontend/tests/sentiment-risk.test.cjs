const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileTemplate } = require('@vue/compiler-sfc')

const source = readFileSync(require('node:path').join(__dirname, '../src/views/analysis/SentimentAnalysisView.vue'), 'utf8')
const helpers = source.slice(source.indexOf('function heatValue('), source.indexOf('const newsMetrics ='))
const context = vm.createContext({})
vm.runInContext(ts.transpileModule(helpers, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, context)

test('zero probability and zero heat are valid values, not fallbacks', () => {
  const row = { sentiment_negative_score: 0, platform_heat_index: 0, sentiment_label: 'negative', hot_score: 90 }
  assert.equal(context.hasRiskData(row), true)
  assert.equal(context.riskScore(row), 0)
  assert.equal(context.riskDisplay(row), '0.00')
})

test('missing heat never uses hot_score as a substitute', () => {
  for (const value of [null, undefined, '', 'invalid']) {
    const row = { sentiment_negative_score: 0.9, platform_heat_index: value, hot_score: 90 }
    assert.equal(context.hasRiskData(row), false)
    assert.equal(context.riskDisplay(row), '数据不足')
  }
})

test('missing probability is not inferred from a negative label', () => {
  const row = { sentiment_label: 'negative', platform_heat_index: 100 }
  assert.equal(context.hasRiskData(row), false)
  assert.equal(context.riskDisplay(row), '数据不足')
})

test('risk follows the displayed probability times relative heat formula', () => {
  const row = { sentiment_negative_score: 0.8, platform_heat_index: 75 }
  assert.equal(context.riskScore(row), 60)
  assert.equal(context.riskDisplay(row), '60.00')
})

test('template compiles and evidence dialog appears only once', () => {
  const { descriptor, errors } = parse(source)
  assert.deepEqual(errors, [])
  assert.equal((source.match(/<el-dialog\b/g) || []).length, 1)
  assert.equal(source.includes('ƽ̨'), false)
  assert.equal(source.includes('events.length'), false)
  assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: 'SentimentAnalysisView.vue', id: 'sentiment-test' }).errors, [])
})

test('sentiment views show two model classes without a synthetic neutral probability', () => {
  assert.doesNotMatch(source, /只看中性|中性评论|正面 \/ 中性 \/ 负面/)
  assert.doesNotMatch(source, /sentiment_neutral_score/)
  assert.match(source, /\['positive', 'negative'\]/)
  assert.match(source, /占比不代表全部原始内容/)
})

test('negative peak displays the actual daily negative count and percentage', () => {
  assert.match(source, /negativePeak\.negative/)
  assert.match(source, /percent\(negativePeak\.negativeRate\)/)
  assert.doesNotMatch(source, /negativePeak\.(count|rate)\b/)
})

test('sentiment trend fixes positive to green and negative to red for lines and markers', () => {
  const chartContext = vm.createContext({
    computed: fn => ({ value: fn() }),
    trendDays: { value: ['2026-09-30'] },
    dailyWeibo: { value: [{ date: '2026-09-30', positive: 12, negative: 3 }] },
    sentimentName: key => key === 'positive' ? '正面' : '负面'
  })
  const chartSource = [
    source.match(/^const SENTIMENT_COLORS.*$/m)[0],
    source.match(/^const weiboTrendOption.*$/m)[0],
    'globalThis.option = weiboTrendOption.value'
  ].join('\n')
  vm.runInContext(ts.transpileModule(chartSource, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText, chartContext)
  const [positive, negative] = chartContext.option.series
  assert.equal(chartContext.option.series.length, 2)
  assert.equal(positive.lineStyle.color, '#16a34a')
  assert.equal(positive.itemStyle.color, '#16a34a')
  assert.equal(negative.lineStyle.color, '#dc2626')
  assert.equal(negative.itemStyle.color, '#dc2626')
  assert.equal(positive.data[0], 12)
  assert.equal(negative.data[0], 3)
})

test('neutral and skipped rows cannot enter sentiment counts, charts or percentage denominators', () => {
  const rows = ['WEIBO', 'SOHU_NEWS'].flatMap(platform =>
    ['positive', 'negative', 'neutral', null].map(sentiment_label => ({
      platform, sentiment_label, publish_time: '2026-10-01 10:00:00', keywords: 'topic',
      sentiment_status: sentiment_label === null ? 'SKIPPED' : 'PREDICTED'
    })))
  const chartContext = vm.createContext({
    computed: fn => ({ get value() { return fn() } }),
    sentimentRows: { value: rows }, NEWS_PLATFORMS: ['SOHU_NEWS'],
    trendRange: { value: '7' }, heatValue: () => null, riskScore: () => 0
  })
  const prefixes = [
    'const binarySentimentRows =', 'const isWeibo =', 'const tokens =',
    'const weiboRows =', 'const totals =', 'const weiboTotals =',
    'const daily =', 'const dailyWeibo =', 'function topicStats(', 'const topics ='
  ]
  const logic = source.split('\n').filter(line => prefixes.some(prefix => line.startsWith(prefix))).join('\n')
  vm.runInContext(ts.transpileModule(logic + '\nglobalThis.result = { weiboRows: weiboRows.value, newsRows: newsRows.value, totals: weiboTotals.value, total: weiboTotal.value, day: dailyWeibo.value[0], heatmap: topicHeatOption.value, positiveShare: share(weiboTotals.value.positive, weiboTotal.value) }', {
    compilerOptions: { target: ts.ScriptTarget.ES2020 }
  }).outputText, chartContext)
  const result = chartContext.result
  assert.equal(result.weiboRows.length, 2)
  assert.equal(result.newsRows.length, 2)
  assert.equal(result.total, 2)
  assert.equal(result.totals.positive, 1)
  assert.equal(result.totals.negative, 1)
  assert.equal(result.positiveShare, '50.0%')
  assert.equal(result.day.total, 2)
  assert.equal(result.heatmap.yAxis.data.length, 2)
  assert.equal(result.heatmap.series[0].data.length, 2)
  assert.equal(result.heatmap.series[0].data[0][2], 50)
  assert.equal(result.heatmap.series[0].data[1][2], 50)
})

test('sentiment API only selects positive and negative labels', () => {
  const mapper = readFileSync(require('node:path').join(__dirname, '../../server/backend/src/main/resources/mapper/AnalyticsMapper.xml'), 'utf8')
  const query = mapper.match(/<select id="sentimentAnalysisRows"[\s\S]*?<\/select>/)[0]
  assert.match(query, /sentiment_label IN \('positive', 'negative'\)/)
  assert.doesNotMatch(query, /'neutral'/)
})
