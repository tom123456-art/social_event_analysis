const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

function load(name, globals = {}) {
  const source = readFileSync(path.join(__dirname, '../src/utils', name + '.ts'), 'utf8')
  const exports = {}
  vm.runInNewContext(ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS }
  }).outputText, { exports, ...globals })
  return exports
}

test('会话保存、切换和清除不影响其他本地配置', () => {
  const values = new Map([['theme', 'light']])
  const session = load('session', { localStorage: {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key)
  } })
  assert.equal(session.readSession().token, '')
  session.saveSession({ token: 'one', username: '管理员', role: 'admin' })
  assert.equal(session.readSession().user, '管理员')
  session.saveSession({ token: 'two', username: '学生', role: 'user' })
  assert.equal(session.readSession().token, 'two')
  assert.equal(session.readSession().role, 'user')
  session.clearSession()
  assert.equal(session.readSession().token, '')
  assert.equal(session.readSession().user, '')
  assert.equal(session.readSession().role, '')
  assert.equal(values.get('theme'), 'light')
})

test('公共格式化保持零值、缺失值和万单位显示规则', () => {
  const format = load('analysisFormat')
  assert.equal(format.numberText(0), '0')
  assert.equal(format.numberText(12.34), '12.3')
  assert.equal(format.numberText(12345), '1.2万')
  assert.equal(format.formatTime('2026-10-19T12:34:56'), '2026-10-19 12:34')
  assert.equal(format.formatTime(null), '-')
  assert.equal(format.fixed(0), '0.0')
  for (const value of [null, undefined, '', 'invalid', Infinity]) {
    assert.equal(format.fixed(value), '-')
  }
})
