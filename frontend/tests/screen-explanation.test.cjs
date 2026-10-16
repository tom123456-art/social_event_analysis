const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const { join } = require('node:path')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const source = readFileSync(join(__dirname, '../src/views/screen/ScreenView.vue'), 'utf8')

test('screen compiles with nine calculation entries and its own dark dialog', () => {
  const { descriptor, errors } = parse(source)
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'screen' })
  const template = compileTemplate({ id: 'screen', source: descriptor.template.content, filename: 'ScreenView.vue', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
  for (const key of ['platform', 'category', 'heat', 'spread', 'keyword', 'rank', 'content', 'sentiment', 'peak']) {
    assert.ok(source.includes("openExplanation('" + key + "')"))
  }
  assert.match(source, /screen-metric-dialog/)
  assert.doesNotMatch(source, /Sentiment.*Dialog/)
  assert.match(source, /最近 18 个时间桶/)
  assert.match(source, /不是全库类别占比/)
  assert.match(source, /15 秒前端缓存/)
})

test('screen links ETL and safely opens news without triggering chart explanations', () => {
  assert.ok(source.includes("router.push('/admin/etl')"))
  assert.doesNotMatch(source, /实时内容流/)
  assert.match(source, /内容流 <small>按发布时间/)
  assert.ok(source.includes('new URL(String(value'))
  assert.ok(source.includes("['http:', 'https:'].includes(url.protocol)"))
  assert.equal((source.match(/rel="noopener noreferrer"/g) || []).length, 2)
  assert.equal((source.match(/@click.stop>{{ row.title/g) || []).length, 2)
})
