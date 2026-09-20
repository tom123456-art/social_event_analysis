<template>
  <div class="portal-page">
    <section class="portal-hero">
      <div class="portal-copy">
        <span class="front-kicker">公开前台</span>
        <h1>{{ currentEventName }}</h1>
        <div class="portal-search">
          <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
          <el-select v-else v-model="eventId" @change="load">
            <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
          </el-select>
          <el-input v-model="keyword" placeholder="搜索标题、正文、关键词" clearable />
          <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
          <el-button @click="router.push('/contents')">查看明细</el-button>
        </div>
        <div class="portal-actions">
          <button @click="router.push('/analysis')">传播趋势</button>
          <button @click="router.push('/platform')">平台扩散</button>
          <button @click="router.push('/keywords')">关键词分析</button>
          <button @click="router.push('/sentiment')">情感变化</button>
        </div>
      </div>
      <div class="portal-media">
        <img src="https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=900&q=80" alt="社交媒体数据观察" />
        <div>
          <b>{{ platformCount }} 个传播平台</b>
          <span>MySQL ADS 最新提交快照</span>
        </div>
      </div>
    </section>

    <MetricGrid :items="metrics" />

    <section class="portal-layout">
      <main class="portal-main">
        <div class="portal-section-head">
          <div>
            <h2>正在被关注的内容</h2>
            <p>展示数据库中的清洗后内容样本；历史导入记录没有原文链接时仍保留展示，不补写模拟互动数据。</p>
          </div>
          <div class="portal-filters">
            <el-radio-group v-model="platform" size="small">
              <el-radio-button label="全部">全部平台</el-radio-button>
              <el-radio-button v-for="item in platformOptions" :key="item" :label="item">{{ platformName(item) }}</el-radio-button>
            </el-radio-group>
            <el-radio-group v-model="category" size="small">
              <el-radio-button label="全部">全部类型</el-radio-button>
              <el-radio-button v-for="item in categoryOptions" :key="item" :label="item">{{ categoryName({ category: item }) }}</el-radio-button>
            </el-radio-group>
          </div>
        </div>

        <div class="portal-feed">
          <article v-for="row in pagedRank" :key="row.content_id" class="portal-feed-card" :class="{ clickable: sourceUrl(row) }" @click="openSource(row)">
            <div class="feed-cover source-cover" :style="{ background: coverGradient(row) }">
              <span>{{ platformName(row.platform) }}</span>
              <b>{{ sourceLabel(row) }}</b>
              <em>{{ titleInitial(row.title) }}</em>
            </div>
            <div class="feed-info">
              <div class="feed-meta">
                <span>{{ sourceLabel(row) }}</span>
                <span class="category-badge" :class="`category-${row.category || 'general'}`">{{ categoryName(row) }}</span>
                <span>{{ sentimentName(row.sentiment_label) }}</span>
                <span>热度 {{ numberText(row.hot_score) }}</span>
              </div>
              <h3>{{ row.title || row.clean_text }}</h3>
              <p>{{ row.clean_text || row.title }}</p>
              <div class="feed-actions">
                <el-button size="small" :disabled="!sourceUrl(row)" @click.stop="openSource(row)">打开原文</el-button>
                <b>{{ row.rank_no ? `热榜第 ${row.rank_no}` : '热榜内容' }}</b>
              </div>
            </div>
          </article>
          <div v-if="!pagedRank.length" class="empty">暂无数据库内容，请先使用独立 Python 采集器生成 Raw CSV，再执行 ETL。</div>
        </div>
        <el-pagination class="table-pagination" v-model:current-page="rankPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredRank.length" />
      </main>

      <aside class="portal-side">
        <div class="analysis-card">
          <div class="card-title">数据来源 <span>公开说明</span></div>
          <div class="detail-list">
            <div><span>展示来源</span><b>{{ dataSourceLabel }}</b></div>
            <div><span>采集源数</span><b>{{ sourceCount }} 个</b></div>
            <div><span>当前内容</span><b>{{ totalContentCount }} 条</b></div>
            <div><span>最近批次</span><b>{{ latestBatch.batch_id || '-' }}</b></div>
          </div>
        </div>

        <div class="analysis-card">
          <div class="card-title">来源贡献排行 <span>内容数/热度</span></div>
          <ChartBox v-if="sourceStats.length" size="md" :option="heatOption" />
          <div v-else class="empty compact-empty">暂无来源数据</div>
        </div>

        <div class="analysis-card">
          <div class="card-title">热点分类 <span>累计热度 Top 8</span></div>
          <div v-if="categoryRank.length" class="category-rank-list">
            <button v-for="item in categoryRank.slice(0, 8)" :key="item.category" type="button" @click="category = item.category">
              <span class="category-badge" :class="`category-${item.category}`">{{ item.category_label }}</span>
              <b>{{ numberText(item.hot_score) }}</b>
              <small>{{ item.content_count }} 条</small>
            </button>
          </div>
          <div v-else class="empty compact-empty">暂无分类数据</div>
        </div>

        <div class="analysis-card">
          <div class="card-title">关键词热度 <span>Top 12</span></div>
          <div class="keyword-pills compact">
            <span v-for="word in keywords.slice(0, 12)" :key="word.keyword">{{ word.keyword }}</span>
          </div>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import MetricGrid from '../components/MetricGrid.vue'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const router = useRouter()
const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const platform = ref('全部')
const category = ref('全部')
const keyword = ref('')
const rankPage = ref(1)
const pageSize = 6
const dashboard = ref<any>({})
const refreshing = ref(false)

const realContents = computed<any[]>(() => dashboard.value.realPublicContents || [])
const displayContents = computed<any[]>(() => realContents.value)
const totalContentCount = computed(() => Number(dashboard.value.overview?.content_count || 0))
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')
const sourceCount = computed(() => Number(dashboard.value.overview?.platform_count || new Set(displayContents.value.map(item => canonicalPlatform(item.platform)).filter(Boolean)).size))
const platformOptions = computed(() => [...new Set(displayContents.value.map(item => canonicalPlatform(item.platform)).filter(Boolean))])
const categoryOptions = computed(() => [...new Set(displayContents.value.map(item => item.category || 'general').filter(Boolean))])
const platformCount = computed(() => Number(dashboard.value.overview?.platform_count || platformOptions.value.length || 0))
const dataSourceLabel = computed(() => totalContentCount.value ? 'MySQL ADS 清洗后数据' : '暂无数据库内容')
const categoryRank = computed<any[]>(() => dashboard.value.categoryRank || [])
const latestBatch = computed<any>(() => dashboard.value.latestBatch || {})
const filteredRank = computed(() => {
  const text = keyword.value.trim()
  return displayContents.value.filter(item => {
    const matchPlatform = platform.value === '全部' || canonicalPlatform(item.platform) === platform.value
    const matchCategory = category.value === '全部' || (item.category || 'general') === category.value
    const matchKeyword = !text || `${item.title}${item.clean_text}`.includes(text)
    return matchPlatform && matchCategory && matchKeyword
  })
})
const pagedRank = computed(() => filteredRank.value.slice((rankPage.value - 1) * pageSize, rankPage.value * pageSize))
const sourceStats = computed<Array<{ source: string; count: number; hot: number }>>(() => {
  return (dashboard.value.interaction || [])
    .map((item: any) => ({ source: platformName(item.platform), count: Number(item.content_count || 0), hot: Number(item.hot_score || 0) }))
    .sort((a: any, b: any) => b.count - a.count || b.hot - a.hot)
    .slice(0, 8)
})
const keywords = computed<any[]>(() => dashboard.value.keywordRank || [])

const metrics = computed(() => [
  { label: '展示内容', value: totalContentCount.value, sub: dataSourceLabel.value },
  { label: '采集源', value: sourceCount.value, sub: '多平台公开接口与页面' },
  { label: '最新发布时间', value: latestTime.value, sub: '来自源站字段' },
  { label: '同步批次', value: latestBatch.value.status || '-', sub: latestBatch.value.batch_id || '暂无批次' }
])
const latestTime = computed(() => {
  const first = displayContents.value[0]?.publish_time
  return first ? String(first).replace('T', ' ').slice(5, 16) : '-'
})

const heatOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params: any) => {
      const point = Array.isArray(params) ? params[0] : params
      const row = sourceStats.value.find(item => item.source === point.name)
      return `${point.name}<br/>内容数：${row?.count || 0}<br/>累计热度：${numberText(row?.hot || 0)}`
    }
  },
  grid: { left: 92, right: 20, top: 16, bottom: 22 },
  xAxis: {
    type: 'value',
    minInterval: 1,
    axisLabel: { color: '#64748b' },
    splitLine: { lineStyle: { color: '#e2e8f0' } }
  },
  yAxis: {
    type: 'category',
    inverse: true,
    data: sourceStats.value.map(item => item.source),
    axisLabel: {
      color: '#334155',
      width: 82,
      overflow: 'truncate'
    }
  },
  series: [{
    name: '内容数',
    type: 'bar',
    barWidth: 14,
    label: { show: true, position: 'right', color: '#1e293b', fontWeight: 700 },
    data: sourceStats.value.map((item, index) => ({
      value: item.count,
      itemStyle: {
        color: ['#2f6feb', '#16a34a', '#f59e0b', '#ef4444', '#14b8a6', '#8b5cf6', '#0ea5e9', '#64748b'][index % 8],
        borderRadius: [0, 8, 8, 0]
      }
    })),
    emphasis: { focus: 'series' }
  }]
}))

async function load() {
  dashboard.value = await getData(`/events/${eventId.value}/dashboard`)
}
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

function numberText(value: any) {
  const num = Number(value || 0)
  return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0)
}
function platformName(value: string) {
  return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', news: '新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', weibo: '微博', douyin: '抖音' } as Record<string, string>)[value] || value
}
function canonicalPlatform(value: any) {
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  if (['DOUYIN', 'WEIBO', 'BILIBILI', 'XIAOHONGSHU', 'NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'OWN_SITE', 'UNKNOWN'].includes(upper)) return upper
  if (!text || upper.includes('RSS') || text.includes('新闻') || text.includes('中新网') || text.includes('百度')) return 'NEWS'
  return 'UNKNOWN'
}
function categoryName(row: any) {
  return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合'
}
function sentimentName(value: string) {
  return value === 'positive' ? '正向情绪' : value === 'negative' ? '负向情绪' : '中性情绪'
}
function openSource(row: any) {
  const url = sourceUrl(row)
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}
function sourceUrl(row: any) {
  const raw = String(row?.source_url || '')
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    if (parsed.hostname === 'willowy-cupcake-717ed2.netlify.app' && parsed.pathname.startsWith('/record/')) {
      parsed.pathname = '/'
      parsed.search = ''
      parsed.hash = `comment-${encodeURIComponent(String(row?.content_id || ''))}`
      return parsed.toString()
    }
  } catch { return raw }
  return raw
}
function sourceKey(row: any) {
  try { return new URL(String(row?.source_url || '')).hostname } catch { return '' }
}
function sourceLabel(row: any) {
  const host = sourceKey(row)
  if (host.includes('willowy-cupcake-717ed2.netlify.app')) return '新闻都知道'
  return host || '公开来源'
}
function titleInitial(value: string) {
  return String(value || '评论').replace(/[^\u4e00-\u9fa5A-Za-z0-9]/g, '').slice(0, 2) || '评'
}
function coverGradient(row: any) {
  const seed = String(row.content_id || row.title || '').split('').reduce((sum, ch) => sum + ch.charCodeAt(0), 0)
  const palettes = [
    ['#1d4ed8', '#38bdf8'],
    ['#0f766e', '#5eead4'],
    ['#7c3aed', '#a78bfa'],
    ['#be123c', '#fb7185'],
    ['#b45309', '#fbbf24']
  ]
  const [a, b] = palettes[seed % palettes.length]
  return `linear-gradient(135deg, ${a}, ${b})`
}

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取前台热点门户失败'))
})

useDatabaseAutoRefresh(load)
</script>
