<template>
  <!-- 门户集中展示数据概况、内容检索与来源入口，列表筛选基于已加载的预览内容。 -->
  <div class="portal-page">
    <section class="portal-hero">
      <div class="portal-copy">
        <span class="front-kicker">公开前台</span>
        <h1>{{ currentEventName }}</h1>
        <div class="portal-search">
          <!-- 将“搜索标题、正文、关键词”的输入或选择同步到 keyword，供查询或提交时读取。 -->
          <el-input v-model="keyword" placeholder="搜索标题、正文、关键词" clearable />
          <!-- 点击“刷新数据库”时执行 refreshData。 -->
          <el-button class="database-refresh-button" type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
          <!-- 点击“查看明细”时执行 router.push('/contents')。 -->
          <el-button @click="router.push('/contents')">查看明细</el-button>
        </div>
        <div class="portal-actions">
          <!-- 点击“热点趋势”时执行 router.push('/analysis')。 -->
          <button @click="router.push('/analysis')">热点趋势</button>
          <!-- 点击“平台扩散”时执行 router.push('/platform')。 -->
          <button @click="router.push('/platform')">平台扩散</button>
          <!-- 点击“关键词分析”时执行 router.push('/keywords')。 -->
          <button @click="router.push('/keywords')">关键词分析</button>
          <!-- 点击“情感变化”时执行 router.push('/sentiment')。 -->
          <button @click="router.push('/sentiment')">情感变化</button>
        </div>
      </div>
      <div class="portal-media">
        <img src="https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=900&q=80" alt="网络媒体数据观察" />
        <div>
          <b>{{ platformCount }} 个传播平台</b>
          <span>MySQL ADS 最新提交快照</span>
        </div>
      </div>
    </section>

    <!-- 把统计指标传入共用指标组件，循环显示名称、数值与说明。 -->
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
          <!-- 根据 row in pagedRank 循环生成条目，模板中的字段读取当前条目数据。 -->
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
                <!-- 点击“打开原文”时执行 openSource(row)。 -->
                <el-button size="small" :disabled="!sourceUrl(row)" @click.stop="openSource(row)">打开原文</el-button>
                <b>{{ row.rank_no ? `热榜第 ${row.rank_no}` : '热榜内容' }}</b>
              </div>
            </div>
          </article>
          <!-- 仅当 !pagedRank.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="!pagedRank.length" class="empty">暂无内容，请先由采集器生成原始数据文件，再上传并执行数据处理。</div>
        </div>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
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
          <!-- 将 heatOption 交给图表组件绘制。 -->
          <ChartBox v-if="sourceStats.length" size="md" :option="heatOption" />
          <div v-else class="empty compact-empty">暂无来源数据</div>
        </div>

        <div class="analysis-card">
          <div class="card-title">热点分类 <span>累计热度前八名</span></div>
          <!-- 仅当 categoryRank.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="categoryRank.length" class="category-rank-list">
            <!-- 点击当前按钮时执行 category = item.category。 -->
            <button v-for="item in categoryRank.slice(0, 8)" :key="item.category" type="button" @click="category = item.category">
              <span class="category-badge" :class="`category-${item.category}`">{{ item.category_label }}</span>
              <b>{{ numberText(item.hot_score) }}</b>
              <small>{{ item.content_count }} 条</small>
            </button>
          </div>
          <div v-else class="empty compact-empty">暂无分类数据</div>
        </div>

        <div class="analysis-card">
          <div class="card-title">关键词热度 <span>前十二名</span></div>
          <div class="keyword-pills compact">
            <span v-for="word in keywords.slice(0, 12)" :key="word.keyword">{{ word.keyword }}</span>
          </div>
        </div>
      </aside>
    </section>
  </div>
</template>

<script setup lang="ts">
import { numberText } from '../../utils/analysisFormat'
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChartBox from '../../components/common/ChartBox.vue'
import MetricGrid from '../../components/common/MetricGrid.vue'
import { clearGetCache, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'

const router = useRouter()
// 保存平台、类别等当前筛选条件，后续查询或计算据此过滤。
const platform = ref('全部')
const category = ref('全部')
const keyword = ref('')
// 保存门户内容列表当前页码。
const rankPage = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 6
// 保存后端仪表盘快照，供概览指标和来源统计读取。
const dashboard = ref<any>({})
// 控制刷新按钮的加载状态，请求结束后解除。
const refreshing = ref(false)

// 提取快照中的真实内容，缺失时使用空列表。
const realContents = computed<any[]>(() => dashboard.value.realPublicContents || [])
// 把可展示内容提供给筛选与分页计算。
const displayContents = computed<any[]>(() => realContents.value)
// 总内容量采用后端汇总值，不把有限的门户预览条数误当作全量规模。
const totalContentCount = computed(() => Number(dashboard.value.overview?.content_count || 0))
// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME
// 优先读取后端源数量，缺失时按当前内容统计平台数。
const sourceCount = computed(() => Number(dashboard.value.overview?.platform_count || new Set(displayContents.value.map(item => canonicalPlatform(item.platform)).filter(Boolean)).size))
// 将来源平台归一化并去重，生成平台筛选选项。
const platformOptions = computed(() => [...new Set(displayContents.value.map(item => canonicalPlatform(item.platform)).filter(Boolean))])
// 即使当前预览内容未覆盖所有类别，也始终保留七个类别筛选入口。
const categoryOptions = ['finance', 'politics', 'technology', 'sports', 'culture', 'society', 'general']
// 读取平台总数，缺失时按有效平台列表数量显示。
const platformCount = computed(() => Number(dashboard.value.overview?.platform_count || platformOptions.value.length || 0))
// 按总内容数是否为零显示数据库来源或无数据提示。
const dataSourceLabel = computed(() => totalContentCount.value ? 'MySQL ADS 清洗后数据' : '暂无数据库内容')
// 读取后端类别排名，供页面汇总展示。
const categoryRank = computed<any[]>(() => dashboard.value.categoryRank || [])
// 读取快照关联的最新批次，供同步状态和时间展示。
const latestBatch = computed<any>(() => dashboard.value.latestBatch || {})
// 联合筛选平台、新闻类别及文本；微博评论不参与新闻类别匹配。
const filteredRank = computed(() => {
  // 去除检索词两侧空白，并对每条记录分别判断平台、类别和文本条件。
  const text = keyword.value.trim()
  return displayContents.value.filter(item => {
    const matchPlatform = platform.value === '全部' || canonicalPlatform(item.platform) === platform.value
    const matchCategory = category.value === '全部' || (canonicalPlatform(item.platform) !== 'WEIBO' && (item.category || 'general') === category.value)
    const matchKeyword = !text || `${item.title}${item.clean_text}`.includes(text)
    return matchPlatform && matchCategory && matchKeyword
  })
})
// 根据当前页码从筛选结果中截取门户内容。
const pagedRank = computed(() => filteredRank.value.slice((rankPage.value - 1) * pageSize, rankPage.value * pageSize))
// 按平台整理内容量和累计热度，排序后最多展示八项。
const sourceStats = computed<Array<{ source: string; count: number; hot: number }>>(() => {
  // 平台统计先转换为显示字段，按内容量优先、热度次优先排序，最多取八项。
  return (dashboard.value.interaction || [])
    .map((item: any) => ({ source: platformName(item.platform), count: Number(item.content_count || 0), hot: Number(item.hot_score || 0) }))
    .sort((a: any, b: any) => b.count - a.count || b.hot - a.hot)
    .slice(0, 8)
})
// 提取关键词排名，供关键词区域循环展示。
const keywords = computed<any[]>(() => dashboard.value.keywordRank || [])

// 将统计结果整理为指标组件所需的名称、数值与说明。
const metrics = computed(() => [
  { label: '展示内容', value: totalContentCount.value, sub: dataSourceLabel.value },
  { label: '采集源', value: sourceCount.value, sub: '多平台公开接口与页面' },
  { label: '最新发布时间', value: latestTime.value, sub: '来自源站字段' },
  { label: '同步批次', value: latestBatch.value.status || '-', sub: latestBatch.value.batch_id || '暂无批次' }
])
// 从首条内容的发布时间提取月日与时分。
const latestTime = computed(() => {
  // 后端展示列表首条作为最新内容，截取月份、日期与时分。
  const first = displayContents.value[0]?.publish_time
  return first ? String(first).replace('T', ' ').slice(5, 16) : '-'
})

// 把当前统计数据转换为热度或来源比较图的配置。
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
  // 按固定事件编号请求仪表盘，替换快照后各项计算属性自动更新。
  dashboard.value = await getData('/analysis/dashboard')
}
async function refreshData() {
  // 先启动按钮加载状态，再清缓存重新读取；finally 保证错误时也能恢复按钮。
  refreshing.value = true
  try {
    clearGetCache()
    await load()
    ElMessage.success('已读取最新数据库快照')
  } catch (error: any) {
    ElMessage.error(getErrorMessage(error, '读取数据库失败，请稍后重试。'))
  } finally {
    refreshing.value = false
  }
}

// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) {
  return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', news: '新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', weibo: '微博', douyin: '抖音' } as Record<string, string>)[value] || '其他平台'
}
// 兼容来源字段大小写和历史新闻源名称，统一为筛选使用的平台编码。
function canonicalPlatform(value: any) {
  // 清除空白并统一大小写，先匹配已知平台编码。
  const text = String(value || '').trim()
  const upper = text.toUpperCase()
  if (['DOUYIN', 'WEIBO', 'BILIBILI', 'XIAOHONGSHU', 'NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'OWN_SITE', 'UNKNOWN'].includes(upper)) return upper
  // 历史新闻名称或 RSS 来源归为新闻，其余无法识别值归为未知平台。
  if (!text || upper.includes('RSS') || text.includes('新闻') || text.includes('中新网') || text.includes('百度')) return 'NEWS'
  return 'UNKNOWN'
}
// 优先使用已有中文类别，否则翻译类别编码，微博评论不展示新闻类别。
function categoryName(row: any) {
  if (['WEIBO', 'SINA_WEIBO'].includes(String(row.platform || '').trim().toUpperCase())) return '-'
  return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合'
}
// 将情感标签转换为中文，未识别标签显示未分类。
function sentimentName(value: string) {
  return value === 'positive' ? '正向情绪' : value === 'negative' ? '负向情绪' : value === 'neutral' ? '中性情绪' : '未分类'
}
function openSource(row: any) {
  const url = sourceUrl(row)
  if (!url) return
  window.open(url, '_blank', 'noopener,noreferrer')
}
// 将历史自建站记录链接转换为首页评论锚点，其他来源维持原始地址。
function sourceUrl(row: any) {
  // 没有原始来源时不生成链接；有来源则尝试解析是否需要旧站地址兼容。
  const raw = String(row?.source_url || '')
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    if (parsed.hostname === 'willowy-cupcake-717ed2.netlify.app' && parsed.pathname.startsWith('/record/')) {
      // 旧版详情路径改为首页评论锚点，并清除原查询参数。
      parsed.pathname = '/'
      parsed.search = ''
      parsed.hash = `comment-${encodeURIComponent(String(row?.content_id || ''))}`
      return parsed.toString()
    }
  } catch { return raw }
  return raw
}
function sourceKey(row: any) {
  // 解析 URL 主机名作为来源标识，非法链接返回空字符串。
  try { return new URL(String(row?.source_url || '')).hostname } catch { return '' }
}
function sourceLabel(row: any) {
  // 自建站显示固定中文名，其他站点直接显示主机名。
  const host = sourceKey(row)
  if (host.includes('willowy-cupcake-717ed2.netlify.app')) return '新闻都知道'
  return host || '公开来源'
}
function titleInitial(value: string) {
  // 去掉非中文、字母和数字字符，取前两位作为无封面内容的占位文字。
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
  await load().catch(() => ElMessage.error('读取前台热点门户失败'))
})

useDatabaseAutoRefresh(load)
</script>
