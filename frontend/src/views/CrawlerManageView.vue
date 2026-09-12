<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>采集管理</h2>
        <p>JS 小规模真实采集：支持腾讯、网易、搜狐、新浪、澎湃、微博六个平台；每次采集结果直接追加并自动同步分析数据库。</p>
      </div>
      <el-button native-type="button" type="primary" :loading="loading" @click.stop.prevent="runCrawler">运行真实采集</el-button>
    </div>

    <div class="grid crawler-layout equal-grid">
      <div class="card">
          <div class="card-title">采集参数 <span>Node.js 脚本</span></div>
        <div class="crawler-form">
          <div class="crawler-field">
            <label>采集源</label>
            <el-select v-model="form.source" style="width:100%">
              <el-option v-for="item in sources" :key="item.code" :label="`${item.name}（${item.platform}）`" :value="item.code" />
            </el-select>
          </div>
          <div class="crawler-field">
            <label>事件ID</label>
            <el-input v-model="form.eventId" />
          </div>
          <div class="crawler-field">
            <label>采集条数</label>
            <el-input-number v-model="form.limit" :min="minLimit" :max="maxLimit" />
          </div>
          <div class="crawler-field crawler-actions">
            <span></span>
            <div>
              <el-button native-type="button" type="primary" :loading="loading" @click.stop.prevent="runCrawler">开始采集</el-button>
              <el-button native-type="button" @click.stop.prevent="loadSources">刷新源</el-button>
            </div>
          </div>
        </div>
        <div class="source-note">
          脚本位置：tools/crawler/hotspot-crawler.js。{{ limitHint }}{{ selectedSource.description || '' }}。后台通过 Java ProcessBuilder 调用 node，按条输出进度，随后上传到虚拟机执行 Spark ETL，并同步虚拟机 MySQL 分析数据。
        </div>
      </div>

      <div class="card">
        <div class="card-title">合规边界 <span>答辩说明</span></div>
        <div class="rule-list">
          <div><b>少量采集</b><span>默认 {{ defaultLimit }} 条，可选 {{ minLimit }}–{{ maxLimit }} 条，避免持续抓取。</span></div>
          <div><b>公开源</b><span>{{ selectedSource.description || '读取公开返回数据' }}，不登录、不使用 Cookie。</span></div>
          <div><b>不绕限制</b><span>先检查 robots.txt，不绕过验证码、反爬、付费墙或平台权限。</span></div>
          <div><b>字段边界</b><span>标题、正文、发布时间、分类及互动数量只写入接口真实返回值，缺失值保留为空。</span></div>
        </div>
      </div>
    </div>

    <div class="card crawler-console-card">
      <div class="crawler-console-head">
        <div class="card-title">
          <span class="console-title"><i class="console-dot" :class="{ active: loading }"></i>本次采集日志</span>
          <span>Node.js → Java 控制台</span>
        </div>
        <span class="console-status" :class="{ running: loading, done: !loading && logLines.length }">
          {{ loading ? '真实采集中' : logLines.length ? '本次日志已接收' : '等待运行' }}
        </span>
      </div>
      <div ref="consoleRef" class="crawler-console" aria-live="polite">
        <div v-if="loading && !logLines.length" class="console-placeholder">正在启动真实采集进程，等待公开接口返回第一条记录……</div>
        <div v-else-if="!logLines.length" class="console-placeholder">点击“开始采集”后，这里会按条显示标题、分类、时间、互动数据、正文摘要、原文链接及跳过原因。</div>
        <div v-for="(line, index) in logLines" :key="`${index}-${line}`" class="console-line" :class="logTone(line)">{{ line }}</div>
      </div>
      <div class="source-note console-note">日志来自本次真实 Node 进程的 stderr 输出；页面与后端 Java 控制台使用同一份采集日志，未在前端补造新闻或互动数量。</div>
    </div>

    <div class="grid crawler-result-grid" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">数据库内容预览 <span>{{ rows.length ? `最近 ${rows.length} 条` : '暂无数据' }}</span></div>
        <div class="crawler-summary">
          <div><span>本次来源</span><b>{{ safeResult.source_name || '-' }}</b></div>
          <div><span>本次采集</span><b>{{ safeResult.count || 0 }}</b></div>
          <div><span>本次追加</span><b>{{ safeResult.append_count ?? safeResult.count ?? 0 }}</b></div>
          <div><span>数据库同步</span><b>{{ syncStatusText(safeResult.database_sync_status || latestBatch.status) }}</b></div>
          <div><span>数据库累计</span><b>{{ overview.content_count || 0 }}</b></div>
          <div><span>ETL有效</span><b>{{ safeResult.etl_valid_count ?? latestBatch.valid_count ?? '-' }}</b></div>
          <div><span>ETL批次</span><b :title="safeResult.etl_batch_id || latestBatch.batch_id || ''">{{ safeResult.etl_batch_id || latestBatch.batch_id || '-' }}</b></div>
          <div><span>最近同步</span><b>{{ formatTime(latestBatch.finished_at || latestBatch.updated_at) }}</b></div>
          <div><span>运行时间</span><b>{{ formatTime(safeResult.run_time) }}</b></div>
          <div><span>抓取方式</span><b>{{ safeResult.mode?.startsWith('REAL_HTTP_') ? 'HTTP实时返回' : '-' }}</b></div>
          <div><span>候选记录</span><b>{{ safeResult.source_metadata?.candidate_count ?? safeResult.source_metadata?.fetched_seed_records ?? '-' }} 条</b></div>
          <div><span>详情成功</span><b>{{ safeResult.source_metadata?.detail_success_count ?? safeResult.count ?? 0 }} 条</b></div>
        </div>
        <div class="crawler-table-scroll">
        <table class="table fixed-rows">
          <thead><tr><th>排名</th><th>平台</th><th>分类</th><th>文章标题</th><th>正文内容</th><th>发布时间</th><th>互动数据</th><th>链接</th></tr></thead>
          <tbody>
            <tr v-for="(row, index) in pagedRows" :key="`${row.content_id}-${index}`">
              <td>{{ row.hot_rank ?? row.rank_no }}</td>
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ row.category_label || row.source_category || row.category || '-' }}</td>
              <td :title="row.title || row.clean_text">{{ row.title || row.clean_text }}</td>
              <td :title="row.clean_text">{{ row.clean_text || '-' }}</td>
              <td>{{ formatTime(row.publish_time) }}</td>
              <td>
                <div class="crawler-interactions">
                  <span>赞 {{ formatCount(row.like_count) }}</span>
                  <span>藏 {{ formatCount(row.favorite_count) }}</span>
                  <span>评 {{ formatCount(row.comment_count) }}</span>
                  <span>转 {{ formatCount(row.forward_count) }}</span>
                </div>
              </td>
              <td><a v-if="row.source_url || row.url" :href="safeSourceUrl(row.source_url || row.url)" target="_blank" rel="noreferrer">查看</a><span v-else>-</span></td>
            </tr>
            <tr v-for="i in emptyRows" :key="`crawl-empty-${i}`"><td colspan="8"></td></tr>
          </tbody>
        </table>
        </div>
        <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="rows.length" />
        <div class="source-note csv-note">本表只读取 MySQL 中本次 ETL 已提交的数据。每次采集结果直接追加到统一 CSV 和数据库，重复内容也作为新的采集记录累计保留；前端不直接读取 CSV。</div>
      </div>

      <div class="card">
        <div class="card-title">字段映射 <span>ETL Raw Schema</span></div>
        <div class="detail-list">
          <div><span>事件</span><b>event_id / event_name</b></div>
          <div><span>平台</span><b>platform / content_type</b></div>
          <div><span>内容</span><b>title / content_text / content_type</b></div>
          <div><span>作者与链路</span><b>author_id / author_name / parent_content_id</b></div>
          <div><span>时间</span><b>publish_time / crawl_time</b></div>
          <div><span>分类</span><b>category（采用各公开源返回字段）</b></div>
          <div><span>互动</span><b>like / comment / repost / share / favorite / view</b></div>
          <div><span>画像</span><b>location / user_age_group / user_gender</b></div>
          <div><span>检索与来源</span><b>keywords / source_url / image_url</b></div>
          <div><span>来源地址</span><a v-if="safeResult.source_url" :href="safeSourceUrl(safeResult.source_url)" target="_blank" rel="noreferrer">打开公开源</a><b v-else>-</b></div>
        </div>
        <div class="empty small" v-if="safeResult.warning">{{ safeResult.warning }}</div>
        <div class="source-note" v-else>{{ safeResult.compliance_note || '运行采集后显示合规说明。' }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '../api/client'
import { notifyDatabaseSynced, useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const sources = ref<any[]>([])
const result = ref<any>({})
const dashboard = ref<any>({})
const loading = ref(false)
const consoleRef = ref<HTMLElement | null>(null)
const crawlerLogs = ref<string[]>([])
const page = ref(1)
const pageSize = 8
const form = reactive({ source: 'TENCENT_NEWS', eventId: 'public_rss_latest', limit: 12 })

const safeResult = computed<any>(() => result.value || {})
const overview = computed<any>(() => dashboard.value.overview || {})
const latestBatch = computed<any>(() => dashboard.value.latestBatch || {})
const selectedSource = computed<any>(() => sources.value.find(item => item.code === form.source) || {})
const minLimit = computed(() => Number(selectedSource.value.min_limit ?? 10))
const maxLimit = computed(() => Number(selectedSource.value.max_limit ?? 20))
const defaultLimit = computed(() => Number(selectedSource.value.default_limit ?? 12))
const limitHint = computed(() => `${selectedSource.value.name || '当前来源'}单次限制为 ${minLimit.value}–${maxLimit.value} 条；`)
const rows = computed<any[]>(() => Array.isArray(dashboard.value.realPublicContents) ? dashboard.value.realPublicContents : [])
const pagedRows = computed(() => rows.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const emptyRows = computed(() => Math.max(0, pageSize - pagedRows.value.length))
const logLines = computed<string[]>(() => {
  if (crawlerLogs.value.length) return crawlerLogs.value
  if (Array.isArray(safeResult.value.progress_lines)) return safeResult.value.progress_lines.filter(Boolean)
  return String(safeResult.value.progress_log || '').split(/\r?\n/).filter(Boolean)
})

async function loadSources() {
  const sourceList = await getData('/admin/crawler/sources').catch(() => [])
  sources.value = Array.isArray(sourceList)
    ? sourceList
    : []
  if (!sources.value.some(item => item.code === form.source)) form.source = sources.value[0]?.code || form.source
  form.limit = Number(selectedSource.value.default_limit ?? form.limit)
}

async function loadDatabaseData() {
  dashboard.value = await getData(`/events/${form.eventId}/dashboard`).catch(() => ({}))
}

async function runCrawler() {
  loading.value = true
  crawlerLogs.value = []
  result.value = {}
  try {
    const response = await fetch('/api/admin/crawler/run/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(form)
    })
    if (!response.ok || !response.body) {
      throw new Error((await response.text()) || '实时采集通道不可用')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let streamResult: any = null
    const consumeEvent = (block: string) => {
      const lines = block.split(/\r?\n/)
      const eventName = lines.find(line => line.startsWith('event:'))?.slice(6).trim() || 'message'
      const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
      if (eventName === 'log') {
        appendCrawlerLog(data)
      } else if (eventName === 'result') {
        streamResult = JSON.parse(data)
      } else if (eventName === 'error') {
        throw new Error(data || '实时采集失败')
      }
    }

    while (true) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const blocks = buffer.split(/\r?\n\r?\n/)
      buffer = blocks.pop() || ''
      blocks.filter(block => block.trim()).forEach(consumeEvent)
      if (done) break
    }
    if (buffer.trim()) consumeEvent(buffer)
    if (!streamResult) throw new Error('采集服务未返回最终结果')
    result.value = streamResult
    await loadDatabaseData()
    notifyDatabaseSynced()
    page.value = 1
    ElMessage.success(`采集并同步完成：本次追加 ${result.value.append_count ?? result.value.count ?? 0} 条，数据库共 ${result.value.etl_valid_count || 0} 条`)
  } catch (error: any) {
    const message = error?.response?.data?.message || error?.message || '采集失败，请检查后端和 Node.js 环境'
    result.value = {
      source_name: '采集失败',
      platform: form.source,
      count: 0,
      database_sync_status: 'FAILED',
      mode: 'ERROR',
      run_time: new Date().toISOString(),
      warning: message
    }
    ElMessage.error(message.includes('timeout') ? '采集超时，请稍后重试或减少采集条数' : message.includes('aborted') || message.includes('canceled') ? '采集请求被页面中断，请刷新后重试' : message)
  } finally {
    loading.value = false
  }
}

function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function formatCount(value: any) { return value === null || value === undefined || value === '' ? '未返回' : Number(value).toLocaleString('zh-CN') }
function syncStatusText(value: any) { return value === 'SUCCESS' ? '已同步' : value === 'FAILED' ? '失败' : '未执行' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', weibo: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', tencent_news: '腾讯新闻', NETEASE_NEWS: '网易新闻', netease_news: '网易新闻', SOHU_NEWS: '搜狐新闻', sohu_news: '搜狐新闻', SINA_NEWS: '新浪新闻', sina_news: '新浪新闻', THE_PAPER: '澎湃新闻', the_paper: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道' } as Record<string, string>)[value] || value || '-' }
function safeSourceUrl(value: any) {
  const raw = String(value || '')
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    if (parsed.hostname === 'willowy-cupcake-717ed2.netlify.app' && parsed.pathname.startsWith('/record/')) {
      parsed.pathname = '/'
      parsed.search = ''
      return parsed.toString()
    }
  } catch { return raw }
  return raw
}
function appendCrawlerLog(line: string) {
  if (!line) return
  crawlerLogs.value.push(line)
  void nextTick(() => {
    if (consoleRef.value) consoleRef.value.scrollTop = consoleRef.value.scrollHeight
  })
}
function logTone(line: string) {
  if (line.startsWith('[跳过]') || line.startsWith('提示：')) return 'is-note'
  if (line.includes('失败') || line.startsWith('[错误]')) return 'is-error'
  if (line.startsWith('采集完成')) return 'is-success'
  return ''
}
onMounted(async () => {
  await Promise.all([loadSources(), loadDatabaseData()])
})

useDatabaseAutoRefresh(loadDatabaseData)

watch(() => form.source, () => {
  form.limit = defaultLimit.value
})
</script>
