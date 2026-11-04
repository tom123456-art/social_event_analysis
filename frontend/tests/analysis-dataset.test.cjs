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
  assert.equal(exportsObject.ANALYSIS_EVENT_ID, undefined)
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
    assert.doesNotMatch(source, /eventId|public_rss_latest|\/events\//)
    assert.match(source, /const currentEventName = ANALYSIS_EVENT_NAME/)
    if (page === 'analysis/OverviewView') assert.doesNotMatch(descriptor.template.content, /<el-tag class=\x22single-event-tag\x22/)
    else assert.match(descriptor.template.content, /<el-tag class=\x22single-event-tag\x22/)
  })
}

test('已移除的事件列表接口不保留底层查询，分析接口由后端选择固定数据集', () => {
  const root = path.join(__dirname, '../..')
  const mapper = readFileSync(path.join(root, 'server/backend/src/main/resources/mapper/AnalyticsMapper.xml'), 'utf8')
  assert.doesNotMatch(mapper, /<select\s+id="events"/)
  for (const file of ['mapper/AnalyticsMapper.java', 'service/analysis/AnalyticsService.java', 'service/analysis/AnalyticsServiceImpl.java']) {
    const source = readFileSync(path.join(root, 'server/backend/src/main/java/com/social/hotspot', file), 'utf8')
    assert.doesNotMatch(source, /\bevents\s*\(/)
  }
  const controller = readFileSync(path.join(root, 'server/backend/src/main/java/com/social/hotspot/controller/AnalyticsController.java'), 'utf8')
  assert.doesNotMatch(controller, /@GetMapping\("\/events/)
  assert.doesNotMatch(controller, /@(?:PathVariable|RequestParam)\([^)]*eventId/)
  assert.match(controller, /service\.dashboard\(EVENT_ID\)/)
})
