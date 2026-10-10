const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

const source = readFileSync(path.join(__dirname, '../src/views/KeywordSentimentView.vue'), 'utf8')
const code = source.slice(source.indexOf('const trendSeries ='), source.indexOf('const keywordPlatformOption ='))
const palette = ['#2563eb', '#0f766e', '#d97706', '#7c3aed', '#dc2626', '#0891b2', '#16a34a', '#be123c']
function options(selected, names) {
  const scope = {
    computed: fn => ({ get value() { return fn() } }),
    TOPIC_LINE_COLORS: palette,
    topTopics: { value: Array.from({ length: 10 }, (_, i) => ({ keyword: `topic${i}` })) },
    trendTopics: { value: names.map(keyword => ({ keyword })) },
    trendBuckets: { value: ['2026-09-24', '2026-09-25'] },
    trendGranularity: { value: 'day' },
    selectedTopic: { value: selected },
    bucketCount: () => 3
  }
  const js = ts.transpileModule(code + '\nglobalThis.option = keywordTrendOption.value', { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText
  vm.runInNewContext(js, scope)
  return scope.option
}
test('all topic lines have the same layer and full opacity before and after selection', () => {
  for (const selected of ['', 'topic1']) {
    const chart = options(selected, ['topic0', 'topic1', 'topic2'])
    for (const line of chart.series) {
      assert.equal(line.zlevel, 0)
      assert.equal(line.z, 3)
      assert.equal(line.lineStyle.opacity, 1)
      assert.equal(line.itemStyle.opacity, 1)
      assert.equal(line.emphasis.focus, 'none')
      assert.equal(line.blur.lineStyle.opacity, 1)
      assert.equal(line.lineStyle.color, line.itemStyle.color)
      assert.equal(line.lineStyle.width, line.name === selected ? 4 : 2)
    }
  }
})
test('topic colors remain stable when selecting a topic outside the initial Top8', () => {
  const before = options('', ['topic0', 'topic1', 'topic2'])
  const after = options('topic9', ['topic9', 'topic0', 'topic1', 'topic2'])
  for (const line of before.series) {
    assert.equal(line.lineStyle.color, after.series.find(item => item.name === line.name).lineStyle.color)
  }
})
test('clicking the selected topic again clears the selection', () => {
  const scope = { selectedTopic: { value: '' } }
  const fn = source.match(/function selectTopic\(keyword: string\) \{[^\n]+\}/)[0]
  vm.runInNewContext(ts.transpileModule(fn, {}).outputText, scope)
  scope.selectTopic('topic1')
  assert.equal(scope.selectedTopic.value, 'topic1')
  scope.selectTopic('topic1')
  assert.equal(scope.selectedTopic.value, '')
})
