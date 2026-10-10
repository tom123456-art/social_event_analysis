const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const helpers = {}
vm.runInNewContext(ts.transpileModule(readFileSync(path.join(__dirname, '../src/utils/topicObservation.ts'), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText, { exports: helpers })

test('standalone countries are contextual entities, specific issue words remain', () => {
  for (const word of ['中国', '美国', '日本']) assert.equal(helpers.isContextOnlyKeyword(word), true)
  for (const word of ['中美贸易', '日本央行', '中国经济']) assert.equal(helpers.isContextOnlyKeyword(word), false)
})
test('missing or invalid interaction is not zero; actual zero is valid', () => {
  for (const value of [null, undefined, '', ' ', -1, 'bad']) assert.equal(helpers.numericInteraction(value), null)
  assert.equal(helpers.numericInteraction(0), 0)
  assert.equal(helpers.numericInteraction('4'), 4)
})
test('topic average uses valid articles and deduplicates member matches per article', () => {
  const topics = [{ keyword: '议题', member_keywords: ['议题', '关联词'] }]
  const records = [
    { keywords: ['议题', '关联词'], source: { like_count: 10, comment_count: 100 } },
    { keywords: ['议题'], source: { like_count: 0, comment_count: 0 } },
    { keywords: ['议题'], source: { like_count: null, comment_count: 20 } }
  ]
  const result = helpers.observeTopics(topics, records, 'like_count')[0]
  assert.equal(result.content_count, 3)
  assert.equal(result.valid_count, 2)
  assert.equal(result.missing_count, 1)
  assert.equal(result.average_interaction, 5)
  assert.equal(helpers.observeTopics(topics, records, 'comment_count')[0].average_interaction, 40)
})
test('no valid interaction stays unclassified and median equality is explicit', () => {
  assert.equal(helpers.observationPosition({ content_count: 3, average_interaction: null }, 2, 4), '互动数据不足')
  assert.equal(helpers.observationPosition({ content_count: 2, average_interaction: 4 }, 2, 4), '报道量处于中位数 · 互动处于中位数')
  assert.equal(helpers.topicMedian([1, 3, 7, 9]), 5)
  assert.equal(helpers.topicMedian([]), null)
})

test('representative sampling includes every populated quadrant, not only high volume topics', () => {
  const topics = [
    { content_count: 3, average_interaction: 2, interaction_total: 6 },
    { content_count: 30, average_interaction: 2, interaction_total: 60 },
    { content_count: 3, average_interaction: 20, interaction_total: 60 },
    { content_count: 30, average_interaction: 20, interaction_total: 600 },
    { content_count: 40, average_interaction: null, interaction_total: 0 }
  ]
  assert.equal(helpers.representativeTopics(topics, 10, 10).length, 4)
})
