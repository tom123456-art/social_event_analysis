const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileTemplate } = require('@vue/compiler-sfc')

const source = readFileSync(require('node:path').join(__dirname, '../src/views/SentimentAnalysisView.vue'), 'utf8')
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
