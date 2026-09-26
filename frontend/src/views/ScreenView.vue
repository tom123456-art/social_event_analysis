<template>
  <div class="big-screen">
    <header class="screen-head">
      <button class="screen-back" type="button" title="返回前台" @click="router.push('/overview')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回前台</span>
      </button>

      <div class="screen-head-copy">
        <div class="screen-kicker"><i></i>多源公开舆情监测 <b>{{ platformCount }} SOURCES ONLINE</b></div>
        <h1>社交媒体热点事件传播态势大屏</h1>
      </div>

      <div class="screen-head-status">
        <div class="screen-sync"><i></i><span>数据同步正常<small>ETL 完成后刷新</small></span></div>
        <div class="screen-clock"><small>{{ dateText }}</small><strong>{{ timeText }}</strong></div>
      </div>
    </header>

    <main class="screen-layout">
      <aside class="screen-column screen-column-left">
        <section class="screen-card platform-card chart-live">
          <h3>六源热度矩阵 <small>{{ platformCount }}个平台</small></h3>
          <ChartBox size="sm" :option="platformRankOption" />
        </section>

        <section class="screen-card source-card chart-live">
          <h3>数据质量概览 <small>{{ countText(sourceCount) }}条 Raw</small></h3>
          <div class="screen-quality">
            <div class="screen-quality-summary">
              <div><strong>{{ cleanRate }}%</strong><span>ETL 有效率</span></div>
              <em :class="{ warning: latestBatch?.status && latestBatch.status !== 'SUCCESS' }">{{ latestBatch?.status || 'SUCCESS' }}</em>
            </div>
            <div class="screen-quality-list">
              <div v-for="metric in sourceMetrics" :key="metric.label">
                <div class="screen-quality-label"><span>{{ metric.label }}</span><b>{{ countText(metric.value) }}{{ metric.unit }}</b></div>
                <div class="screen-quality-track"><i :style="{ width: metric.ratio + '%' }"></i></div>
              </div>
            </div>
            <div class="screen-quality-footer"><span>最近 ETL：{{ batchTime }}</span><span>统一 CSV</span></div>
          </div>
        </section>

        <section class="screen-card category-card chart-live">
          <h3>热点类型构成 <small>{{ categoryRank.length }}类主题</small></h3>
          <ChartBox size="sm" :option="categoryOption" />
        </section>
      </aside>

      <section class="screen-center">
        <section class="screen-card hero-chart chart-live">
          <div class="screen-panel-head">
            <h3>传播热度脉冲 <small>时段新增热度 / 内容量</small></h3>
            <div class="screen-hero-metrics">
              <span>总热度 <b>{{ heatText(overview.hot_score) }}</b></span>
              <span>内容 <b>{{ countText(overview.content_count) }}</b></span>
              <span>峰值 <b>{{ formatTime(peakPoint.time) }}</b></span>
            </div>
          </div>
          <ChartBox :option="heatOption" />
        </section>

        <div class="screen-center-bottom">
          <section class="screen-card spread-card chart-live">
            <h3>跨平台时序 <small>无父级关系不推断因果</small></h3>
            <ChartBox size="md" :option="spreadOption" />
          </section>

          <section class="screen-card keyword-card chart-live">
            <h3>关键词情报矩阵 <small>词频 / 主导平台</small></h3>
            <div class="screen-keywords">
              <div
                v-for="(word, index) in keywordRows"
                :key="word.keyword"
                :class="{ leading: index < 2 }"
                :style="{ '--keyword-color': keywordColor(word.sentiment_top) }"
                :title="String(word.keyword)"
              >
                <b>{{ shortKeyword(word.keyword) }}</b>
                <span>{{ platformName(word.platform_top) }}</span>
                <em>{{ countText(word.word_count) }}</em>
              </div>
              <p v-if="!keywordRows.length" class="screen-empty">暂无关键词结果</p>
            </div>
          </section>
        </div>
      </section>

      <aside class="screen-column screen-column-right">
        <section class="screen-card rank-card chart-live">
          <h3>高热内容排行 <small>TOP {{ rankRows.length }}</small></h3>
          <div class="screen-rank">
            <div v-for="(row, index) in rankRows" :key="row.content_id">
              <b>{{ String(index + 1).padStart(2, '0') }}</b>
              <span><small :style="{ color: platformColor(row.platform) }">{{ platformName(row.platform) }}</small><strong>{{ row.title || row.clean_text }}</strong></span>
              <em>{{ heatText(row.hot_score) }}</em>
            </div>
          </div>
        </section>

        <section class="screen-card ticker-card chart-live">
          <h3>实时内容流 <small>按发布时间</small></h3>
          <div class="screen-ticker">
            <div>
              <p v-for="(row, index) in tickerRows" :key="String(row.content_id) + '-' + index">
                <span :style="{ color: platformColor(row.platform) }">{{ platformName(row.platform) }}</span>
                <time>{{ formatTime(row.publish_time) }}</time>
                <b>{{ row.title || row.clean_text }}</b>
              </p>
            </div>
          </div>
        </section>

        <section class="screen-card sentiment-card chart-live">
          <h3>平台情绪结构 <small>正向 / 中性 / 负向</small></h3>
          <ChartBox size="sm" :option="sentimentOption" />
        </section>

        <section class="screen-card peak-card chart-live">
          <h3>峰值传播强度 <small>{{ formatTime(peakPoint.time) }}</small></h3>
          <ChartBox size="sm" :option="speedOption" />
        </section>
      </aside>
    </main>
  </div>
</template>

<script setup lang="ts">
// 大屏页：以可视化方式展示事件传播和情感概览。
import { ArrowLeft } from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChartBox from '../components/ChartBox.vue'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const router = useRouter()
const data = ref<any>({})
const dateText = ref('')
const timeText = ref('')
let clockTimer: number | undefined
let dataSignature = ''

const overview = computed(() => data.value.overview || {})
const heat = computed<any[]>(() => data.value.heatTrend || [])
const rank = computed<any[]>(() => data.value.contentRank || [])
const realContents = computed<any[]>(() => data.value.realPublicContents || [])
const keywords = computed<any[]>(() => data.value.keywordRank || [])
const sentiment = computed<any[]>(() => data.value.sentimentTrend || [])
const latestBatch = computed(() => data.value.latestBatch || data.value.batch || undefined)

const categoryRank = computed<any[]>(() => data.value.categoryRank || [])

const timeline = computed<any[]>(() => compactTimeline(data.value.platformTimeline || []))
const platformCount = computed(() => Number(overview.value.platform_count || timeline.value.length || 0))
const sourceCount = computed(() => Number(latestBatch.value?.source_count || overview.value.content_count || 0))
const validCount = computed(() => Number(latestBatch.value?.valid_count || overview.value.content_count || 0))
const dirtyCount = computed(() => Math.max(0, Number(latestBatch.value?.dirty_count ?? (sourceCount.value - validCount.value))))
const cleanRate = computed(() => sourceCount.value ? Math.min(100, Number((validCount.value / sourceCount.value * 100).toFixed(1))) : 0)
const batchTime = computed(() => formatTime(latestBatch.value?.end_time || latestBatch.value?.start_time || overview.value.updated_at))

const sourceMetrics = computed(() => [
  { label: '有效记录', value: validCount.value, unit: '条', ratio: cleanRate.value },
  { label: '异常记录', value: dirtyCount.value, unit: '条', ratio: dirtyCount.value ? Math.min(100, dirtyCount.value / Math.max(1, sourceCount.value) * 100) : 0 },
  { label: '平台覆盖', value: platformCount.value, unit: '个', ratio: Math.min(100, platformCount.value / 6 * 100) },
  { label: '关键词结果', value: keywords.value.length, unit: '项', ratio: Math.min(100, keywords.value.length / 30 * 100) }
])

const trendRows = computed(() => {
  const grouped = new Map<string, { time: string; content: number; hot: number }>()
  heat.value.forEach(item => {
    const time = String(item.time_bucket || '')
    const row = grouped.get(time) || { time, content: 0, hot: 0 }
    row.content += Number(item.content_count || 0)
    row.hot += Number(item.hot_score || 0)
    grouped.set(time, row)
  })
  return [...grouped.values()].sort((a, b) => a.time.localeCompare(b.time)).slice(-18)
})

const peakPoint = computed(() => trendRows.value.reduce((peak, item) => item.hot > peak.hot ? item : peak, { time: '', content: 0, hot: 0 }))

const rankRows = computed(() => {
  const seen = new Set<string>()
  return [...rank.value]
    .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0) || String(b.publish_time || '').localeCompare(String(a.publish_time || '')))
    .filter(row => {
      const key = String(row.content_id || row.title || '')
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
    .slice(0, 6)
})

const liveRows = computed(() => [...realContents.value]
  .sort((a, b) => String(b.publish_time || '').localeCompare(String(a.publish_time || '')))
  .slice(0, 10))
const tickerRows = computed(() => liveRows.value.length > 5 ? liveRows.value.concat(liveRows.value) : liveRows.value)

const keywordRows = computed(() => {
  const ignored = new Set(['热点', '新闻', '内容', '表示', '进行', '来源', '编辑'])
  return keywords.value
    .filter(item => {
      const word = String(item.keyword || '').trim()
      return word.length >= 2 && word.length <= 14 && !ignored.has(word)
    })
    .slice(0, 10)
})

const heatOption = computed(() => {
  const rows = trendRows.value
  const labels = rows.map(item => formatTime(item.time))
  const lastIndex = Math.max(0, rows.length - 1)
  return {
    backgroundColor: 'transparent',
    animationDuration: 900,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross', lineStyle: { color: 'rgba(125,211,252,.55)' } },
      formatter: (params: any) => {
        const list = Array.isArray(params) ? params : [params]
        const row = rows[list[0]?.dataIndex || 0]
        return row ? `${formatFullTime(row.time)}<br/>新增内容：${countText(row.content)}条<br/>时段热度：${heatText(row.hot)}` : ''
      }
    },
    legend: { top: 2, left: 8, textStyle: { color: '#9fbad0', fontSize: 10 }, itemWidth: 12, itemHeight: 7 },
    grid: { left: 56, right: 50, top: 48, bottom: 34 },
    xAxis: {
      type: 'category', boundaryGap: true, data: labels,
      axisLabel: { color: '#7896ae', interval: 'auto', hideOverlap: true, fontSize: 10 },
      axisLine: { lineStyle: { color: '#23435a' } }, axisTick: { show: false }
    },
    yAxis: [
      { type: 'value', name: '时段热度', nameTextStyle: { color: '#739db8' }, axisLabel: { color: '#7896ae', formatter: (value: number) => heatText(value) }, splitLine: { lineStyle: { color: 'rgba(93,134,160,.14)' } } },
      { type: 'value', name: '新增内容', nameTextStyle: { color: '#739db8' }, axisLabel: { color: '#7896ae' }, splitLine: { show: false } }
    ],
    series: [
      {
        name: '新增内容', type: 'bar', yAxisIndex: 1, barMaxWidth: 22,
        data: rows.map(item => item.content),
        itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#6ee7b7' }, { offset: 1, color: 'rgba(16,185,129,.16)' }] }, borderRadius: [2, 2, 0, 0] }
      },
      {
        name: '时段热度', type: 'line', smooth: 0.3, symbol: 'circle', symbolSize: 7,
        lineStyle: { width: 3, color: '#22d3ee', shadowBlur: 10, shadowColor: 'rgba(34,211,238,.45)' },
        itemStyle: { color: '#dffaff', borderColor: '#22d3ee', borderWidth: 2 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(34,211,238,.24)' }, { offset: 1, color: 'rgba(34,211,238,0)' }] } },
        data: rows.map(item => item.hot),
        markPoint: { symbol: 'pin', symbolSize: 42, itemStyle: { color: '#fbbf24' }, label: { color: '#07121e', fontWeight: 900 }, data: rows.length ? [{ type: 'max', name: '峰值' }] : [] }
      },
      {
        name: '最新节点', type: 'effectScatter', coordinateSystem: 'cartesian2d', symbolSize: 12,
        rippleEffect: { brushType: 'stroke', scale: 3.2, period: 3 }, itemStyle: { color: '#fb7185' },
        data: rows.length ? [[labels[lastIndex], rows[lastIndex].hot]] : [], tooltip: { show: false }, z: 5
      }
    ]
  }
})

const platformRankOption = computed(() => {
  const rows = [...timeline.value].sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)).slice(0, 7).reverse()
  return {
    backgroundColor: 'transparent', animationDuration: 800,
    tooltip: { trigger: 'item', formatter: (params: any) => `${params.name}<br/>内容：${countText(params.data?.content_count)}条<br/>热度：${heatText(params.value)}` },
    grid: { left: 72, right: 42, top: 6, bottom: 6 },
    xAxis: { type: 'value', show: false },
    yAxis: { type: 'category', data: rows.map(item => platformName(item.platform)), axisLabel: { color: '#b8d6e8', fontSize: 10 }, axisLine: { show: false }, axisTick: { show: false } },
    series: [{
      type: 'bar', barWidth: 8, showBackground: true, backgroundStyle: { color: 'rgba(122,164,190,.08)', borderRadius: 4 },
      data: rows.map(item => ({ value: Number(item.hot_score || 0), content_count: Number(item.content_count || 0), itemStyle: { color: platformColor(item.platform), borderRadius: 4 } })),
      label: { show: true, position: 'right', color: '#dff6ff', fontSize: 9, formatter: (params: any) => heatText(params.value) }
    }]
  }
})

const categoryOption = computed(() => {
  const rows = categoryRank.value.slice(0, 7)
  return {
    backgroundColor: 'transparent',
    animationDuration: 850,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (params: any[]) => {
        const item = params[0]
        return `${item.name}<br/>热度：${heatText(item.data?.raw_hot_score ?? item.value)}<br/>内容：${countText(item.data?.content_count)}条`
      }
    },
    grid: { left: 58, right: 62, top: 4, bottom: 4, containLabel: false },
    xAxis: { type: 'value', show: false },
    yAxis: {
      type: 'category',
      inverse: true,
      data: rows.map(item => item.category_label || categoryName(item)),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: '#d8eff8', fontSize: 11, fontWeight: 700, margin: 9 }
    },
    series: [{
      type: 'bar',
      barMaxWidth: 18,
      showBackground: true,
      backgroundStyle: { color: 'rgba(112, 165, 188, .08)', borderRadius: 4 },
      label: {
        show: true,
        position: 'right',
        color: '#b9dbe8',
        fontSize: 9,
        formatter: (params: any) => `${heatText(params.data?.raw_hot_score ?? params.value)}  ${countText(params.data?.content_count)}条`
      },
      data: rows.map(item => {
        const hotScore = Number(item.hot_score || 0)
        return {
          value: Math.max(1, hotScore),
          raw_hot_score: hotScore,
          content_count: Number(item.content_count || 0),
          itemStyle: { color: categoryColor(item), borderRadius: 4 }
        }
      })
    }]
  }
})

const spreadOption = computed(() => ({
  backgroundColor: 'transparent', animationDuration: 900,
  tooltip: { trigger: 'item', formatter: (params: any) => params.data?.root ? `统一热点事件<br/>覆盖 ${platformCount.value} 个平台` : `${params.name}<br/>内容：${countText(params.data?.content_count)}条<br/>首发：${formatFullTime(params.data?.first_publish_time)}<br/>相对延迟：${delayText(params.data?.delay_minutes)}` },
  series: [{
    type: 'tree', layout: 'orthogonal', orient: 'LR', top: 8, bottom: 8, left: 82, right: 132,
    symbol: 'rect', symbolSize: [9, 15], edgeShape: 'polyline', edgeForkPosition: '45%', roam: false, initialTreeDepth: -1, expandAndCollapse: false,
    lineStyle: { color: '#2b8fb6', width: 1.5 },
    label: { position: 'left', color: '#ccecff', fontSize: 9, fontWeight: 800, distance: 8 },
    leaves: { label: { position: 'right', distance: 8, color: '#e7f8ff', fontSize: 9, formatter: (params: any) => `${params.name}  ${countText(params.data?.content_count)}条` } },
    itemStyle: { color: '#22d3ee', borderColor: '#b8f3ff', borderWidth: 1, shadowBlur: 9, shadowColor: 'rgba(34,211,238,.55)' },
    data: [{
      name: '热点事件', root: true, value: Number(overview.value.hot_score || 0),
      children: timeline.value.map(item => ({ name: platformName(item.platform), value: Number(item.hot_score || 0), content_count: Number(item.content_count || 0), first_publish_time: item.first_publish_time, delay_minutes: item.delay_minutes, itemStyle: { color: platformColor(item.platform) } }))
    }]
  }]
}))

const sentimentOption = computed(() => {
  const platforms = timeline.value.map(item => canonicalPlatform(item.platform)).reverse()
  const labels = platforms.map(platformName)
  const grouped = new Map<string, Record<string, number>>()
  sentiment.value.forEach(item => {
    const platform = canonicalPlatform(item.platform)
    const row = grouped.get(platform) || { positive: 0, neutral: 0, negative: 0 }
    const label = String(item.sentiment_label || 'neutral').toLowerCase()
    row[label] = (row[label] || 0) + Number(item.sentiment_count || 0)
    grouped.set(platform, row)
  })
  const seriesConfig = [
    { key: 'positive', name: '正向', color: '#34d399' },
    { key: 'neutral', name: '中性', color: '#60a5fa' },
    { key: 'negative', name: '负向', color: '#fb7185' }
  ]
  return {
    backgroundColor: 'transparent', animationDuration: 800,
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (params: any[]) => `${params[0]?.axisValue || ''}<br/>${params.map(item => `${item.marker}${item.seriesName}：${item.data?.count || 0}条 (${Number(item.value || 0).toFixed(0)}%)`).join('<br/>')}` },
    legend: { top: 0, right: 0, textStyle: { color: '#8eaec3', fontSize: 9 }, itemWidth: 9, itemHeight: 6 },
    grid: { left: 68, right: 8, top: 24, bottom: 2 },
    xAxis: { type: 'value', max: 100, show: false },
    yAxis: { type: 'category', data: labels, axisLabel: { color: '#a9c7d9', fontSize: 8 }, axisLine: { show: false }, axisTick: { show: false } },
    series: seriesConfig.map(config => ({
      name: config.name, type: 'bar', stack: 'sentiment', barWidth: 7,
      itemStyle: { color: config.color, borderColor: '#071522', borderWidth: .5 },
      data: platforms.map(platform => {
        const row = grouped.get(platform) || { positive: 0, neutral: 0, negative: 0 }
        const total = Object.values(row).reduce((sum, value) => sum + Number(value || 0), 0)
        const count = Number(row[config.key] || 0)
        return { value: total ? count / total * 100 : 0, count }
      })
    }))
  }
})

const speedOption = computed(() => {
  const peak = Number(peakPoint.value.hot || 0)
  const max = Math.max(100, Math.ceil(peak * 1.2 / 100) * 100)
  return {
    backgroundColor: 'transparent', animationDuration: 900,
    series: [{
      type: 'gauge', min: 0, max, startAngle: 205, endAngle: -25, radius: '94%', center: ['50%', '59%'],
      progress: { show: true, width: 8, roundCap: true, itemStyle: { color: '#22d3ee', shadowBlur: 12, shadowColor: 'rgba(34,211,238,.65)' } },
      axisLine: { lineStyle: { width: 8, color: [[1, 'rgba(89,132,157,.16)']] } },
      pointer: { show: true, length: '48%', width: 3, itemStyle: { color: '#fbbf24' } },
      anchor: { show: true, size: 7, itemStyle: { color: '#fbbf24', borderColor: '#071522', borderWidth: 2 } },
      axisTick: { show: false }, splitLine: { distance: -11, length: 5, lineStyle: { color: '#567b91', width: 1 } }, axisLabel: { show: false },
      title: { show: true, offsetCenter: [0, '64%'], color: '#789bb1', fontSize: 9 },
      detail: { valueAnimation: true, offsetCenter: [0, '22%'], color: '#effcff', fontSize: 19, fontWeight: 900, formatter: (value: number) => heatText(value) },
      data: [{ value: peak, name: `${countText(peakPoint.value.content)}条内容形成峰值` }]
    }]
  }
})

async function load() {
  const dashboard = await getData('/events/public_rss_latest/dashboard').catch(() => ({}))
  const platformSignature = (dashboard.platformTimeline || []).map((item: any) => `${item.platform}:${item.content_count}:${item.hot_score}`).join(',')
  const nextSignature = [dashboard.latestBatch?.batch_id, dashboard.overview?.content_count, dashboard.overview?.hot_score, dashboard.contentRank?.[0]?.content_id, platformSignature].join('|')
  if (nextSignature !== dataSignature) {
    dataSignature = nextSignature
    data.value = dashboard
  }
}

function tick() {
  const current = new Date()
  dateText.value = current.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' })
  timeText.value = current.toLocaleTimeString('zh-CN', { hour12: false })
}
function heatText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function countText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(0) }
function formatTime(value: any) {
  const text = String(value || '').replace('T', ' ')
  if (!text) return '--'
  const stamp = text.match(/\d{2}-\d{2} \d{2}:\d{2}/)
  return stamp ? stamp[0] : text.slice(5, 16)
}
function formatFullTime(value: any) { return String(value || '').replace('T', ' ').slice(0, 16) || '--' }
function delayText(value: any) {
  const minutes = Number(value || 0)
  if (!minutes) return '首发平台'
  if (minutes >= 1440) return `${(minutes / 1440).toFixed(1)}天`
  if (minutes >= 60) return `${(minutes / 60).toFixed(1)}小时`
  return `${minutes}分钟`
}
function shortKeyword(value: any) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 10)}…` : text }
function keywordColor(value: any) { return ({ positive: '#6ee7b7', neutral: '#7dd3fc', negative: '#fb7185' } as Record<string, string>)[String(value || '').toLowerCase()] || '#7dd3fc' }
function categoryColor(item: any) { return ({ finance: '#d9952f', politics: '#d96078', technology: '#1fa6bd', society: '#7c6ed1', culture: '#db7042', sports: '#2ca66f', general: '#477fc1' } as Record<string, string>)[item.category] || '#3e91b5' }
function categoryName(row: any) { return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合' }

function canonicalPlatform(value: any) {
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  const aliases: Record<string, string> = {
    腾讯新闻: 'TENCENT_NEWS', 网易新闻: 'NETEASE_NEWS', 搜狐新闻: 'SOHU_NEWS', 新浪新闻: 'SINA_NEWS', 澎湃新闻: 'THE_PAPER', 微博: 'WEIBO', 新闻都知道: 'OWN_SITE'
  }
  if (aliases[text]) return aliases[text]
  if (PLATFORM_LABELS[upper]) return upper
  if (!text || upper.includes('RSS') || text.includes('中新网') || text.includes('百度')) return 'NEWS'
  return upper || 'UNKNOWN'
}
function platformName(value: any) { const code = canonicalPlatform(value); return PLATFORM_LABELS[code] || String(value || '未知平台') }
function platformColor(value: any) { return PLATFORM_COLORS[canonicalPlatform(value)] || '#7dd3fc' }
function compactTimeline(rows: any[]) {
  const grouped = new Map<string, any>()
  rows.forEach(item => {
    const platform = canonicalPlatform(item.platform)
    const row = grouped.get(platform) || { ...item, platform, content_count: 0, hot_score: 0 }
    row.content_count += Number(item.content_count || 0)
    row.hot_score += Number(item.hot_score || 0)
    if (!row.first_publish_time || String(item.first_publish_time || '') < String(row.first_publish_time)) row.first_publish_time = item.first_publish_time
    grouped.set(platform, row)
  })
  return [...grouped.values()].sort((a, b) => Number(a.delay_minutes || 0) - Number(b.delay_minutes || 0)).slice(0, 8)
}

const PLATFORM_LABELS: Record<string, string> = {
  TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', WEIBO: '微博', OWN_SITE: '新闻都知道',
  DOUYIN: '抖音', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '其他新闻', UNKNOWN: '未知平台'
}
const PLATFORM_COLORS: Record<string, string> = {
  TENCENT_NEWS: '#35b9e8', NETEASE_NEWS: '#e95b66', SOHU_NEWS: '#f59e45', SINA_NEWS: '#e6c64c', THE_PAPER: '#8b7de3', WEIBO: '#fb7185', OWN_SITE: '#43c991', NEWS: '#6f8ea3', UNKNOWN: '#6f8ea3'
}

onMounted(() => {
  tick()
  load()
  clockTimer = window.setInterval(tick, 1000)
})
onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})

useDatabaseAutoRefresh(load)
</script>
