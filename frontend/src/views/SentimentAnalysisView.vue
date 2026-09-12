<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>情感分析</span>
        <h2>情感倾向变化与舆论反转观察</h2>
        <p>按时间窗口和平台统计正向、中性、负向内容占比，识别负向情绪集中时段和可能的舆论反转点。</p>
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
        <div class="card-title">情感趋势 <span>按时间桶统计</span></div>
        <ChartBox :option="trendOption" />
      </div>
      <div class="analysis-card">
        <div class="card-title">平台情感结构 <span>正向/中性/负向</span></div>
        <ChartBox :option="platformOption" />
      </div>
    </div>

    <div class="grid half equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">情感时间明细 <span>ads_sentiment_trend</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>时间</th><th>平台</th><th>情感</th><th>数量</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedSentiment" :key="`${row.time_bucket}-${row.platform}-${row.sentiment_label}`">
              <td>{{ formatTime(row.time_bucket) }}</td>
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ sentimentName(row.sentiment_label) }}</td>
              <td>{{ row.sentiment_count }}</td>
              <td>{{ row.sentiment_label === 'negative' ? '关注负向集中' : '常规舆情样本' }}</td>
            </tr>
            <tr v-for="i in emptyRows" :key="`sentiment-empty-${i}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="sentiment.length" />
      </div>

      <div class="analysis-card">
        <div class="card-title">舆论反转观察 <span>规则识别</span></div>
        <div class="insight-list">
          <div><b>主导情感</b><span>{{ sentimentName(mainSentiment) }}，占比最高</span></div>
          <div><b>负向峰值</b><span>{{ formatTime(negativePeak?.time_bucket) }}，{{ platformName(negativePeak?.platform) }}，{{ negativePeak?.sentiment_count || 0 }} 条</span></div>
          <div><b>反转判断</b><span>{{ reversalText }}</span></div>
          <div><b>分析口径</b><span>第一版采用 Spark 规则/词典分类，后续可替换轻量模型</span></div>
        </div>
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
const page = ref(1)
const pageSize = 8
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')

const sentiment = computed<any[]>(() => data.value.sentimentTrend || [])
const overview = computed(() => data.value.overview || {})
const pagedSentiment = computed(() => sentiment.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const emptyRows = computed(() => Math.max(0, pageSize - pagedSentiment.value.length))
const totals = computed(() => sentiment.value.reduce((acc, row) => {
  acc[row.sentiment_label] = (acc[row.sentiment_label] || 0) + Number(row.sentiment_count || 0)
  return acc
}, {} as Record<string, number>))
const mainSentiment = computed(() => (Object.entries(totals.value) as [string, number][]).sort((a, b) => b[1] - a[1])[0]?.[0] || 'neutral')
const negativePeak = computed(() => sentiment.value.filter(item => item.sentiment_label === 'negative').sort((a, b) => Number(b.sentiment_count || 0) - Number(a.sentiment_count || 0))[0])
const reversalText = computed(() => totals.value.negative && totals.value.positive && totals.value.negative > totals.value.positive * 0.6 ? '存在明显负向集中，需要关注舆论反转风险' : '未出现强烈反转，情绪结构相对稳定')

const metrics = computed(() => [
  { label: '正向内容', value: totals.value.positive || overview.value.positive_count || 0, sub: 'positive' },
  { label: '中性内容', value: totals.value.neutral || overview.value.neutral_count || 0, sub: 'neutral' },
  { label: '负向内容', value: totals.value.negative || overview.value.negative_count || 0, sub: 'negative' },
  { label: '负向峰值', value: negativePeak.value?.sentiment_count || 0, sub: formatTime(negativePeak.value?.time_bucket) }
])

const trendOption = computed(() => {
  const times = [...new Set(sentiment.value.map(item => String(item.time_bucket).replace('T', ' ').slice(11, 16)))]
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 42, right: 16, top: 40, bottom: 32 },
    xAxis: { type: 'category', data: times },
    yAxis: { type: 'value' },
    series: ['positive', 'neutral', 'negative'].map(label => ({
      name: sentimentName(label),
      type: 'line',
      smooth: true,
      areaStyle: {},
      data: times.map(time => sentiment.value.filter(item => item.sentiment_label === label && String(item.time_bucket).replace('T', ' ').slice(11, 16) === time).reduce((sum, item) => sum + Number(item.sentiment_count || 0), 0))
    }))
  }
})

const platformOption = computed(() => {
  const platforms = [...new Set(sentiment.value.map(item => item.platform))]
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 54, right: 16, top: 24, bottom: 32 },
    xAxis: { type: 'category', data: platforms.map(platformName) },
    yAxis: { type: 'value' },
    series: ['positive', 'neutral', 'negative'].map(label => ({
      name: sentimentName(label),
      type: 'bar',
      stack: 'sentiment',
      data: platforms.map(platform => sentiment.value.filter(item => item.platform === platform && item.sentiment_label === label).reduce((sum, item) => sum + Number(item.sentiment_count || 0), 0))
    }))
  }
})

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
function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || value || '-' }
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '中性' }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取情感分析失败'))
})

useDatabaseAutoRefresh(load)
</script>
