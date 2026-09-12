<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>关键词分析</span>
        <h2>公众关注重点与话题演变</h2>
        <p>对正文和标题进行关键词统计，观察事件讨论主题、平台分布和内容来源。</p>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
        <el-input v-model="keywordFilter" placeholder="关键词筛选" style="width:180px" clearable />
      </div>
    </div>

    <div class="grid third">
      <div class="analysis-card">
        <div class="card-title">关键词云 <span>词频越高字号越大</span></div>
        <div class="keyword-cloud dynamic-cloud">
          <span v-for="(word, index) in filteredKeywords.slice(0, 16)" :key="word.keyword" :style="cloudStyle(word, index)">{{ word.keyword }}</span>
        </div>
      </div>
      <div class="analysis-card">
        <div class="card-title">关键词时间演变 <span>按时间桶观察</span></div>
        <ChartBox :option="sentimentTrendOption" />
      </div>
      <div class="analysis-card">
        <div class="card-title">关键词平台分布 <span>代表平台</span></div>
        <ChartBox :option="sentimentPieOption" />
      </div>
    </div>

    <div class="grid half equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">关键词排行 <span>Spark统计结果</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>关键词</th><th>次数</th><th>代表平台</th><th>主要情感</th></tr></thead>
          <tbody>
            <tr v-for="word in pagedKeywords" :key="word.keyword">
              <td>{{ word.keyword }}</td>
              <td>{{ word.word_count }}</td>
              <td>{{ platformName(word.platform_top) }}</td>
              <td>{{ sentimentName(word.sentiment_top) }}</td>
            </tr>
            <tr v-for="i in emptyKeywordRows" :key="`keyword-empty-${i}`"><td colspan="4"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="keywordPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredKeywords.length" />
      </div>
      <div class="analysis-card table-card">
        <div class="card-title">内容样例 <span>解释关键词来源</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>平台</th><th>内容</th><th style="width:76px">情感</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedRank" :key="row.content_id">
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ row.clean_text }}</td>
              <td>{{ sentimentName(row.sentiment_label) }}</td>
            </tr>
            <tr v-for="i in emptyRankRows" :key="`rank-empty-${i}`"><td colspan="3"></td></tr>
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
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const keywordFilter = ref('')
const keywordPage = ref(1)
const rankPage = ref(1)
const pageSize = 8
const data = ref<any>({})
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')
const keywords = computed<any[]>(() => data.value.keywordRank || [])
const sentiment = computed<any[]>(() => data.value.sentimentTrend || [])
const rank = computed<any[]>(() => data.value.contentRank || [])
const filteredKeywords = computed(() => keywordFilter.value ? keywords.value.filter(item => String(item.keyword).includes(keywordFilter.value)) : keywords.value)
const pagedKeywords = computed(() => filteredKeywords.value.slice((keywordPage.value - 1) * pageSize, keywordPage.value * pageSize))
const pagedRank = computed(() => rank.value.slice((rankPage.value - 1) * pageSize, rankPage.value * pageSize))
const emptyKeywordRows = computed(() => Math.max(0, pageSize - pagedKeywords.value.length))
const emptyRankRows = computed(() => Math.max(0, pageSize - pagedRank.value.length))
const maxWord = computed(() => Math.max(1, ...keywords.value.map(item => Number(item.word_count || 0))))
const sentimentTrendOption = computed(() => {
  const times = [...new Set(sentiment.value.map(item => String(item.time_bucket).replace('T', ' ').slice(11, 16)))]
  return {
    tooltip: { trigger: 'axis' },
    legend: { top: 0 },
    grid: { left: 42, right: 14, top: 36, bottom: 30 },
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

useDatabaseAutoRefresh(load)

const sentimentPieOption = computed(() => {
  const counts = { positive: 0, neutral: 0, negative: 0 } as Record<string, number>
  sentiment.value.forEach(item => { counts[item.sentiment_label] = (counts[item.sentiment_label] || 0) + Number(item.sentiment_count || 0) })
  return { tooltip: { trigger: 'item' }, series: [{ type: 'pie', radius: ['48%', '72%'], data: [
    { name: '正向', value: counts.positive },
    { name: '中性', value: counts.neutral },
    { name: '负向', value: counts.negative }
  ] }] }
})

async function load() { data.value = await getData(`/events/${eventId.value}/dashboard`) }
function wordSize(value: any) { return 13 + Math.round(Number(value || 0) / maxWord.value * 18) }
function cloudStyle(word: any, index: number) {
  const positions = [
    [42, 18, 0], [18, 34, -10], [68, 32, 8], [38, 50, 90],
    [58, 58, -8], [24, 66, 8], [76, 72, 0], [14, 80, -90],
    [50, 82, 12], [84, 50, -12], [30, 20, 8], [62, 18, -8],
    [12, 54, 0], [44, 70, -6], [70, 86, 6], [88, 24, 90]
  ]
  const colors = ['#1d4ed8', '#0f766e', '#7c3aed', '#b45309', '#be123c', '#2563eb']
  const [left, top, rotate] = positions[index % positions.length]
  return {
    left: `${left}%`,
    top: `${top}%`,
    transform: `translate(-50%, -50%) rotate(${rotate}deg)`,
    fontSize: `${Math.min(30, wordSize(word.word_count))}px`,
    color: colors[index % colors.length]
  }
}
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || value || '-' }
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '中性' }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取关键词分析失败'))
})
</script>
