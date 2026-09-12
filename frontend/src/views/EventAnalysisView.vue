<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>传播趋势</span>
        <h2>热点事件热度变化分析</h2>
        <p>按时间观察事件热度、内容数量和平台贡献，识别爆发时间、峰值和扩散节奏。</p>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <MetricGrid :items="metrics" />

    <div class="grid two" style="margin-top:12px">
      <div class="analysis-card">
        <div class="card-title">平台热度趋势 <span>hot_score</span></div>
        <ChartBox :option="heatOption" />
      </div>
      <div class="analysis-card">
        <div class="card-title">来源贡献对比 <span>内容数/热度</span></div>
        <ChartBox :option="sourceOption" />
      </div>
    </div>

    <div class="grid half equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">峰值与关键节点 <span>按平台</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>平台</th><th>内容数</th><th>热度</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedPlatform" :key="row.platform">
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ row.content_count }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
            </tr>
            <tr v-for="i in emptyPlatformRows" :key="`platform-empty-${i}`"><td colspan="3"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="platformPage" :page-size="pageSize" layout="total, prev, pager, next" :total="platformSummary.length" />
      </div>
      <div class="analysis-card table-card">
        <div class="card-title">高热内容排行 <span>Top 10</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>内容</th><th style="width:76px">类型</th><th style="width:76px">平台</th><th style="width:90px">热度</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedRank" :key="row.content_id">
              <td>{{ row.title || row.clean_text }}</td>
              <td><span class="category-badge" :class="`category-${row.category || 'general'}`">{{ categoryName(row) }}</span></td>
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
            </tr>
            <tr v-for="i in emptyRankRows" :key="`rank-empty-${i}`"><td colspan="4"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="rankPage" :page-size="pageSize" layout="total, prev, pager, next" :total="rank.length" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import MetricGrid from '../components/MetricGrid.vue'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const data = ref<any>({})
const refreshing = ref(false)
const platformPage = ref(1)
const rankPage = ref(1)
const pageSize = 8
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')
const overview = computed(() => data.value.overview || {})
const rawHeat = computed<any[]>(() => data.value.heatTrend || [])
const heat = computed<any[]>(() => compactHeat(rawHeat.value))
const platformSummary = computed<any[]>(() => compactPlatform(data.value.platformTimeline || []))
const rank = computed<any[]>(() => data.value.contentRank || [])
const platforms = computed(() => [...new Set(heat.value.map(item => canonicalPlatform(item.platform)))])
const heatTimes = computed(() => [...new Set(heat.value.map(item => String(item.time_bucket).replace('T', ' ').slice(11, 16)))])
const pagedPlatform = computed(() => platformSummary.value.slice((platformPage.value - 1) * pageSize, platformPage.value * pageSize))
const pagedRank = computed(() => rank.value.slice((rankPage.value - 1) * pageSize, rankPage.value * pageSize))
const emptyPlatformRows = computed(() => Math.max(0, pageSize - pagedPlatform.value.length))
const emptyRankRows = computed(() => Math.max(0, pageSize - pagedRank.value.length))
const metrics = computed(() => [
  { label: '峰值时间', value: String(overview.value.peak_time || '-').replace('T', ' ').slice(0, 16), sub: '热度最高' },
  { label: '内容数', value: numberText(overview.value.content_count || 0), sub: '数据库有效记录' },
  { label: '来源数', value: platformSummary.value.length, sub: '公开采集源' },
  { label: '总热度', value: numberText(platformSummary.value.reduce((sum, item) => sum + Number(item.hot_score || 0), 0)), sub: '按内容与排名计算' }
])

const heatOption = computed(() => ({
  color: TREND_COLORS,
  tooltip: { trigger: 'axis' },
  legend: { top: 0 },
  grid: { left: 48, right: 18, top: 38, bottom: 32 },
  xAxis: { type: 'category', data: heatTimes.value },
  yAxis: { type: 'value' },
  series: platforms.value.map((platform, index) => ({
    name: platformName(platform),
    type: 'line',
    smooth: true,
    lineStyle: { color: TREND_COLORS[index] },
    itemStyle: { color: TREND_COLORS[index] },
    data: heatTimes.value.map(time => {
      const row = heat.value.find(item => item.platform === platform && String(item.time_bucket).replace('T', ' ').slice(11, 16) === time)
      return Number(row?.hot_score || 0)
    })
  }))
}))

const sourceOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  legend: { top: 0 },
  grid: { left: 48, right: 16, top: 38, bottom: 32 },
  xAxis: { type: 'category', data: platformSummary.value.map(item => platformName(item.platform)) },
  yAxis: { type: 'value' },
  series: [
    { name: '内容数', type: 'bar', data: platformSummary.value.map(item => Number(item.content_count || 0)) },
    { name: '热度', type: 'line', smooth: true, yAxisIndex: 0, data: platformSummary.value.map(item => Number(item.hot_score || 0)) }
  ]
}))

async function load() { data.value = await getData(`/events/${eventId.value}/dashboard`) }
async function refreshData() {
  refreshing.value = true
  try {
    await load()
    ElMessage.success('已读取最新数据库快照')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '读取数据库失败')
  } finally {
    refreshing.value = false
  }
}
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
const TREND_PLATFORM_LIMIT = 6
const TREND_COLORS = ['#2563eb', '#0f766e', '#ea580c', '#7c3aed', '#0891b2', '#be123c']

function compactHeat(rows: any[]) {
  const grouped = new Map<string, any>()
  rows.forEach(item => {
    const platform = canonicalPlatform(item.platform || item.author_name)
    const time = item.time_bucket
    const key = `${String(time)}::${platform}`
    const row = grouped.get(key) || { ...item, platform, hot_score: 0, content_count: 0 }
    row.hot_score += Number(item.hot_score || 0)
    row.content_count += Number(item.content_count || 0)
    grouped.set(key, row)
  })
  const totals = [...grouped.values()].reduce((map, item) => {
    const platform = item.platform
    map.set(platform, (map.get(platform) || 0) + Number(item.hot_score || 0))
    return map
  }, new Map<string, number>())
  const topPlatforms = [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, TREND_PLATFORM_LIMIT).map(item => item[0])
  return [...grouped.values()]
    .filter(item => topPlatforms.includes(item.platform))
    .sort((a, b) => String(a.time_bucket).localeCompare(String(b.time_bucket)))
}

function compactPlatform(rows: any[]) {
  const grouped = new Map<string, any>()
  rows.forEach(item => {
    const platform = canonicalPlatform(item.platform || item.author_name)
    const row = grouped.get(platform) || { platform, content_count: 0, hot_score: 0 }
    row.content_count += Number(item.content_count || 0)
    row.hot_score += Number(item.hot_score || 0)
    grouped.set(platform, row)
  })
  return [...grouped.values()].sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0))
}

function canonicalPlatform(value: any) {
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  if (['DOUYIN', 'WEIBO', 'BILIBILI', 'XIAOHONGSHU', 'NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'OWN_SITE', 'UNKNOWN'].includes(upper)) return upper
  if (!text || upper.includes('RSS') || text.includes('新闻') || text.includes('中新网') || text.includes('百度')) return 'NEWS'
  return 'UNKNOWN'
}

function categoryName(row: any) { return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', UNKNOWN: '未知平台', news: '新闻' } as Record<string, string>)[value] || value }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取传播趋势失败'))
})

useDatabaseAutoRefresh(load)
</script>
