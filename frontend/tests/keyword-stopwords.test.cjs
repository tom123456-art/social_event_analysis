const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const read = name => readFileSync(path.resolve(__dirname, name), 'utf8')
const excluded = ['效率', '方面', '理性', '这一', '导弹', '群众', '效果', '美军', '经验总结', '习近平']

test('requested terms are excluded from UI, ETL and existing rankings', () => {
  const view = read('../src/views/analysis/KeywordAnalysisView.vue')
  const stopwords = vm.runInNewContext(view.match(/const STOPWORDS = (new Set\([^\n]+\))/)[1])
  const etl = read('../../server/etl/src/main/java/com/social/hotspot/etl/aggregate/DwsSummaryAggregator.java')
  const etlTerms = etl.match(/col\("keyword"\)\.isin\(([\s\S]*?)\)\)\);/)[1]
  const mapper = read('../../server/backend/src/main/resources/mapper/AnalyticsMapper.xml')
  const query = mapper.match(/<select id="keywordRank"[\s\S]*?<\/select>/)[0]
  for (const word of excluded) {
    assert.ok(stopwords.has(word), `UI: ${word}`)
    assert.ok(etlTerms.includes(`"${word}"`), `ETL: ${word}`)
    assert.ok(query.includes(`'${word}'`), `existing rankings: ${word}`)
  }
  assert.ok(query.indexOf('NOT IN') < query.indexOf('LIMIT'))
  for (const word of ['人工智能', '世界杯', '导弹试射']) assert.equal(stopwords.has(word), false)
  assert.ok(view.includes('!STOPWORDS.has(keyword)'))
})
