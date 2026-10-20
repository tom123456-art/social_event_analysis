const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')
const { parse, compileTemplate } = require('@vue/compiler-sfc')

const pages = ['analysis/OverviewView', 'analysis/HotspotTrendView', 'analysis/PlatformSpreadView', 'analysis/KeywordAnalysisView', 'analysis/ContentListView', 'admin/DataManagementView', 'analysis/SentimentAnalysisView']
const config = readFileSync(path.join(__dirname, '../src/config/analysisDataset.ts'), 'utf8')
const exportsObject = {}
vm.runInNewContext(ts.transpileModule(config, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: exportsObject })

test('dataset identity remains canonical and its label is valid Unicode', () => {
  assert.equal(exportsObject.ANALYSIS_EVENT_ID, 'public_rss_latest')
  assert.equal(exportsObject.ANALYSIS_EVENT_NAME, '网络媒体热点事件传播态势与舆情分析系统')
  assert.doesNotMatch(exportsObject.ANALYSIS_EVENT_NAME, /[?\uFFFD]/)
})

for (const page of pages) {
  test(`${page} cannot recreate an event selector from historical imports`, () => {
    const source = readFileSync(path.join(__dirname, `../src/views/${page}.vue`), 'utf8')
    const { descriptor, errors } = parse(source)
    assert.deepEqual(errors, [])
    assert.deepEqual(compileTemplate({ source: descriptor.template.content, filename: `${page}.vue`, id: page }).errors, [])
    assert.doesNotMatch(descriptor.template.content, /<el-select[^>]*v-model=\x22eventId\x22/)
    assert.doesNotMatch(source, /events\.length|events\.value|\/events['\x22]|events\[0\]/)
    assert.match(source, /const eventId = ref\(ANALYSIS_EVENT_ID\)/)
    assert.match(source, /const currentEventName = ANALYSIS_EVENT_NAME/)
    if (page === 'analysis/OverviewView') assert.doesNotMatch(descriptor.template.content, /<el-tag class=\x22single-event-tag\x22/)
    else assert.match(descriptor.template.content, /<el-tag class=\x22single-event-tag\x22/)
  })
}

test('event API excludes legacy imports and normalizes the canonical label', () => {
  const root = path.join(__dirname, '../..')
  const mapper = readFileSync(path.join(root, 'server/backend/src/main/resources/mapper/AnalyticsMapper.xml'), 'utf8')
  const query = mapper.match(/<select id=\x22events\x22[\s\S]*?<\/select>/)[0]
  assert.match(query, /WHERE event_id = 'public_rss_latest' AND status = 'ACTIVE'/)
  const service = readFileSync(path.join(root, 'server/backend/src/main/java/com/social/hotspot/service/analysis/AnalyticsServiceImpl.java'), 'utf8')
  const method = service.slice(service.indexOf('public List<Map<String, Object>> events()'), service.indexOf('public Map<String, Object> dashboard('))
  assert.match(method, /CANONICAL_EVENT_ID\.equals\(row\.get\(\x22event_id\x22\)\)/)
  assert.match(method, /event\.put\(\x22event_name\x22,/)
})
