const { test } = require('node:test')
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')

const root = path.join(__dirname, '..')
const source = readFileSync(path.join(root, 'src/utils/userText.ts'), 'utf8')
const userText = {}
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: userText })

test('用户提示不展示英文异常、多行日志和混合技术堆栈', () => {
  for (const text of ['Network Error', 'Incremental ETL requires VM_ETL_WAREHOUSE_OUTPUT.', '上传失败：IllegalStateException - bad input', '任务失败：中文\njava.lang.Exception: error', '中文提示 Request failed', '中文�']) {
    assert.equal(userText.chineseMessage(text, '请稍后重试。'), '请稍后重试。')
  }
  assert.equal(userText.chineseMessage('原始数据表头已变化，请重新上传。'), '原始数据表头已变化，请重新上传。')
  assert.equal(userText.chineseMessage('请配置数仓目录（VM_ETL_WAREHOUSE_OUTPUT）。'), '请配置数仓目录（VM_ETL_WAREHOUSE_OUTPUT）。')
})

test('状态、角色、平台和任务代码显示为中文且不修改输入', () => {
  assert.equal(userText.statusName('SUCCESS'), '成功')
  assert.equal(userText.statusName('FAILED'), '失败')
  assert.equal(userText.statusName('PENDING'), '待审核')
  assert.equal(userText.roleName('ADMIN'), '管理员')
  assert.equal(userText.platformName('TENCENT_NEWS'), '腾讯新闻')
  assert.equal(userText.platformName('UNRECOGNIZED'), '其他平台')
  assert.equal(userText.stageName('MYSQL_SYNC'), '数据库同步')
  assert.equal(userText.taskName('RawToOdsJob'), '原始数据接入')
  assert.equal(userText.eventName('public_rss_latest'), '网络媒体热点事件传播态势与舆情分析')
})

test('前端 HTTP 客户端与全局异常统一走中文提示入口', () => {
  const client = readFileSync(path.join(root, 'src/api/client.ts'), 'utf8')
  const main = readFileSync(path.join(root, 'src/main.ts'), 'utf8')
  assert.match(client, /chineseMessage\(body.message/)
  assert.match(client, /error.response.data.message = error.message/)
  assert.match(main, /locale: zhCn/)
  assert.match(main, /getErrorMessage\(event.reason/)
})
