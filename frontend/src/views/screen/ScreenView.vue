<template>
  <!-- 独立大屏不使用业务导航外壳，各图表共用同一份分析快照和口径说明。 -->
  <div class="big-screen">
    <header class="screen-head">
      <!-- 点击“返回前台”时执行 router.push('/overview')。 -->
      <button class="screen-back" type="button" title="返回前台" @click="router.push('/overview')">
        <el-icon><ArrowLeft /></el-icon>
        <span>返回前台</span>
      </button>

      <div class="screen-head-copy">
        <div class="screen-kicker"><i></i>多源公开舆情监测 <b>{{ platformCount }} 个数据来源</b></div>
        <h1 title="网络媒体热点事件传播态势与舆情分析系统">网络媒体传播态势与舆情大屏</h1>
      </div>

      <div class="screen-head-status">
        <!-- 点击“打开 ETL 管理”时执行 router.push('/admin/etl')。 -->
        <button class="screen-sync screen-etl-link" type="button" title="打开 ETL 管理" @click="router.push('/admin/etl')"><i></i><span>ETL 数据同步<small>ETL 完成后刷新</small></span></button>
        <div class="screen-clock"><small>{{ dateText }}</small><strong>{{ timeText }}</strong></div>
      </div>
    </header>

    <main class="screen-layout">
      <aside class="screen-column screen-column-left">
        <section class="screen-card platform-card chart-live screen-explainable" @click="openExplanation('platform')">
          <h3>五源热度矩阵 <small>5个新闻平台</small><button class="screen-info" type="button" title="查看五源热度计算依据" aria-label="查看五源热度计算依据" @click.stop="openExplanation('platform')"><el-icon><InfoFilled /></el-icon></button></h3>
          <!-- 将 platformRankOption 交给图表组件绘制。 -->
          <ChartBox size="sm" :option="platformRankOption" />
        </section>

        <section class="screen-card source-card chart-live screen-explainable" title="打开 ETL 管理" @click="router.push('/admin/etl')">
          <h3>数据质量概览 <small>{{ countText(sourceCount) }} 条原始记录</small></h3>
          <div class="screen-quality">
            <div class="screen-quality-summary">
              <div><strong>{{ cleanRate }}%</strong><span>ETL 有效率</span></div>
              <em :class="{ warning: latestBatch?.status && latestBatch.status !== 'SUCCESS' }">{{ statusName(latestBatch?.status) }}</em>
            </div>
            <div class="screen-quality-list">
              <!-- 根据 metric in sourceMetrics 循环生成条目，模板中的字段读取当前条目数据。 -->
              <div v-for="metric in sourceMetrics" :key="metric.label">
                <div class="screen-quality-label"><span>{{ metric.label }}</span><b>{{ countText(metric.value) }}{{ metric.unit }}</b></div>
                <div class="screen-quality-track"><i :style="{ width: metric.ratio + '%' }"></i></div>
              </div>
            </div>
            <div class="screen-quality-footer"><span>最近 ETL：{{ batchTime }}</span><button class="screen-etl-detail" type="button" @click.stop="router.push('/admin/etl')">ETL 管理 <el-icon><TopRight /></el-icon></button></div>
          </div>
        </section>

        <section class="screen-card category-card chart-live screen-explainable" @click="openExplanation('category')">
          <h3>热点类型构成 <small>{{ categoryRank.length }}类主题</small><button class="screen-info" type="button" title="查看热点类型计算依据" aria-label="查看热点类型计算依据" @click.stop="openExplanation('category')"><el-icon><InfoFilled /></el-icon></button></h3>
          <!-- 将 categoryOption 交给图表组件绘制。 -->
          <ChartBox size="sm" :option="categoryOption" />
        </section>
      </aside>

      <section class="screen-center">
        <section class="screen-card hero-chart chart-live screen-explainable" @click="openExplanation('heat')">
          <div class="screen-panel-head">
            <h3>传播热度脉冲 <small>时段新增热度 / 内容量</small><button class="screen-info" type="button" title="查看传播热度计算依据" aria-label="查看传播热度计算依据" @click.stop="openExplanation('heat')"><el-icon><InfoFilled /></el-icon></button></h3>
            <div class="screen-hero-metrics">
              <span>总热度 <b>{{ heatText(overview.hot_score) }}</b></span>
              <span>内容 <b>{{ countText(overview.content_count) }}</b></span>
              <span>峰值 <b>{{ formatTime(peakPoint.time) }}</b></span>
            </div>
          </div>
          <!-- 将 heatOption 交给图表组件绘制。 -->
          <ChartBox :key="heatRefreshKey" :option="heatOption" />
        </section>

        <div class="screen-center-bottom">
          <section class="screen-card spread-card chart-live screen-explainable" @click="openExplanation('spread')">
            <h3>跨平台时序 <small>无父级关系不推断因果</small><button class="screen-info" type="button" title="查看跨平台时序计算依据" aria-label="查看跨平台时序计算依据" @click.stop="openExplanation('spread')"><el-icon><InfoFilled /></el-icon></button></h3>
            <!-- 将 spreadOption 交给图表组件绘制。 -->
            <ChartBox size="md" :option="spreadOption" />
          </section>

          <section class="screen-card keyword-card chart-live screen-explainable" @click="openExplanation('keyword')">
            <h3>关键词情报矩阵 <small>词频 / 来源平台</small><button class="screen-info" type="button" title="查看关键词计算依据" aria-label="查看关键词计算依据" @click.stop="openExplanation('keyword')"><el-icon><InfoFilled /></el-icon></button></h3>
            <div class="screen-keywords">
              <!-- 根据 (word, index) in keywordRows 循环生成条目，模板中的字段读取当前条目数据。 -->
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
              <!-- 仅当 !keywordRows.length 成立时显示此处内容，条件变化时自动更新。 -->
              <p v-if="!keywordRows.length" class="screen-empty">暂无关键词结果</p>
            </div>
          </section>
        </div>
      </section>

      <aside class="screen-column screen-column-right">
        <section class="screen-card rank-card chart-live screen-explainable" @click="openExplanation('rank')">
          <h3>高热内容排行 <small>前 {{ rankRows.length }} 条</small><button class="screen-info" type="button" title="查看内容排行计算依据" aria-label="查看内容排行计算依据" @click.stop="openExplanation('rank')"><el-icon><InfoFilled /></el-icon></button></h3>
          <div class="screen-rank">
            <!-- 根据 (row, index) in rankRows 循环生成条目，模板中的字段读取当前条目数据。 -->
            <div v-for="(row, index) in rankRows" :key="row.content_id">
              <b>{{ String(index + 1).padStart(2, '0') }}</b>
              <span><small :style="{ color: platformColor(row.platform) }">{{ platformName(row.platform) }}</small><strong><a v-if="newsUrl(row.source_url)" :href="newsUrl(row.source_url)" target="_blank" rel="noopener noreferrer" :title="row.title || row.clean_text" @click.stop>{{ row.title || row.clean_text }}</a><template v-else>{{ row.title || row.clean_text }}</template></strong></span>
              <em>{{ heatText(row.hot_score) }}</em>
            </div>
          </div>
        </section>

        <section class="screen-card ticker-card screen-explainable" @click="openExplanation('content')">
          <h3>内容流 <small>按发布时间</small><button class="screen-info" type="button" title="查看内容流数据依据" aria-label="查看内容流数据依据" @click.stop="openExplanation('content')"><el-icon><InfoFilled /></el-icon></button></h3>
          <div class="screen-ticker">
            <div>
              <p v-for="(row, index) in tickerRows" :key="String(row.content_id) + '-' + index">
                <span :style="{ color: platformColor(row.platform) }">{{ platformName(row.platform) }}</span>
                <time>{{ formatTime(row.publish_time) }}</time>
                <b><a v-if="newsUrl(row.source_url)" :href="newsUrl(row.source_url)" target="_blank" rel="noopener noreferrer" :title="row.title || row.clean_text" @click.stop>{{ row.title || row.clean_text }}</a><template v-else>{{ row.title || row.clean_text }}</template></b>
              </p>
            </div>
          </div>
        </section>

        <section class="screen-card sentiment-card chart-live screen-explainable" @click="openExplanation('sentiment')">
          <h3>平台情绪结构 <small>正向 / 负向 · 过滤后样本</small><button class="screen-info" type="button" title="查看情绪结构计算依据" aria-label="查看情绪结构计算依据" @click.stop="openExplanation('sentiment')"><el-icon><InfoFilled /></el-icon></button></h3>
          <!-- 将 sentimentOption 交给图表组件绘制。 -->
          <ChartBox size="sm" :option="sentimentOption" />
        </section>

        <section class="screen-card peak-card chart-live screen-explainable" @click="openExplanation('peak')">
          <h3>峰值传播强度 <small>{{ formatTime(peakPoint.time) }}</small><button class="screen-info" type="button" title="查看峰值强度计算依据" aria-label="查看峰值强度计算依据" @click.stop="openExplanation('peak')"><el-icon><InfoFilled /></el-icon></button></h3>
          <!-- 将 speedOption 交给图表组件绘制。 -->
          <ChartBox size="sm" :option="speedOption" />
        </section>
      </aside>
    </main>
    <!-- 将弹框显隐与 explanationVisible 绑定，集中展示当前操作的详情或表单。 -->
    <el-dialog v-model="explanationVisible" class="screen-metric-dialog" modal-class="screen-metric-overlay" :title="activeExplanation.title" width="min(520px, calc(100vw - 32px))" top="15vh" append-to-body>
      <div class="screen-metric-kicker">计算依据</div>
      <section class="screen-metric-section"><h4>数据前提</h4><p>{{ activeExplanation.basis }}</p></section>
      <section class="screen-metric-section"><h4>生成过程</h4><ol><li v-for="step in activeExplanation.steps" :key="step">{{ step }}</li></ol></section>
      <div class="screen-metric-note"><el-icon><InfoFilled /></el-icon><p>{{ activeExplanation.limit }}</p></div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ArrowLeft, InfoFilled, TopRight } from '@element-plus/icons-vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChartBox from '../../components/common/ChartBox.vue'
import { getData } from '../../api/client'
import { statusName } from '../../utils/userText'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'

const router = useRouter()
// 说明与当前大屏的筛选、截取和统计口径保持一致，不将样本结果解释为全量事实。
const explanations = {
  platform: {
    title: '五源热度矩阵',
    basis: 'ETL 将清洗后的内容按事件、平台汇总，发布到 MySQL 平台时序表。',
    steps: ['单篇热度：在同一事件、同一平台内，对支持且正值覆盖率达到 1% 的互动字段计算百分位，再等权平均；没有有效信号时为 0。', '平台热度：累加该平台内容的 hot_score；大屏排除微博，按累计热度取前 5 个平台。'],
    limit: '这是平台内相对热度的累计值，受内容量影响，不等于阅读量，也不是跨平台统一的绝对影响力。'
  },
  category: {
    title: '热点类型构成',
    basis: '后端从已发布内容中按原始类别各取最新最多 300 条，补充类别标签；没有内容样本时回退到高热排行样本。',
    steps: ['按类别标签分组，分别累计内容条数与单篇 hot_score。', '按类别累计热度降序排列，大屏显示前 7 类；悬浮提示同时显示真实热度与条数。'],
    limit: '这是接口返回样本的类别结构，不是全库类别占比；零热度条形保留最小可见宽度，但数值仍为 0。'
  },
  heat: {
    title: '传播热度脉冲',
    basis: '读取 ETL 按时间桶、平台生成的热度汇总，时间依据为内容发布时间。',
    steps: ['将相同时间桶的各平台内容量、热度分别求和，按时间升序保留最近 18 个时间桶。', '柱形表示该时段内容条数，折线表示该时段单篇热度之和；最大折线值标记为峰值。'],
    limit: '新增指该发布时间桶内的内容，不是页面刷新后新抓取的数据；顶部总热度、内容数来自事件总览，范围大于图内的 18 个时段。'
  },
  spread: {
    title: '跨平台时序',
    basis: '清洗后的有效内容需有平台和发布时间，ETL 为各平台汇总最早发布时间、内容条数与累计热度。',
    steps: ['以后端汇总结果中最早的发布时间为基准，计算各平台首次出现的分钟差。', '大屏合并平台别名，按延迟排序取前 8 个节点，以热点事件为根节点展示。'],
    limit: '首发只表示现有数据中最早出现，不证明原创；连线表示事件与平台的归属，不证明平台之间的转载或传播因果。'
  },
  keyword: {
    title: '关键词情报矩阵',
    basis: 'ETL 优先使用 keywords 字段，缺失时使用清洗文本，按分隔符和空白拆分后过滤词长与停用词。',
    steps: ['按词统计出现次数，按词频降序查询前 30 项；页面再过滤通用词和不合适的词长，展示前 10 项。', '词条颜色使用聚合结果的情绪标签，旁边平台使用聚合时取到的来源平台。'],
    limit: '词频是拆分后出现次数，不是文章去重数；来源平台与颜色标签取聚合中的一个值，不代表出现最多的平台或多数情绪。'
  },
  rank: {
    title: '高热内容排行',
    basis: 'ETL 对已清洗内容按单篇 hot_score 降序编号，后端读取排名靠前的 20 条。',
    steps: ['单篇 hot_score 为同平台有效互动信号的百分位均值，通常在 0 至 100 之间，无有效信号记为 0。', '页面在这 20 条中按热度、发布时间降序排列，再按内容 ID（缺失时用标题）去重，最多展示 6 条。'],
    limit: '排名反映当前 ETL 数据中的相对互动热度，不是实时新闻榜；有有效原文地址的标题可打开新闻来源。'
  },
  content: {
    title: '内容流',
    basis: '内容来自 MySQL 已发布的 ETL 结果；接口按原始类别各取最新最多 300 条。',
    steps: ['页面将返回内容按发布时间倒序排列，展示最新 10 条；较多条目通过复制一组实现循环滚动。', '页面每 5 秒触发刷新检查，请求还受 15 秒前端缓存及后端缓存影响；新闻内容取自已发布结果，标题链接读取 source_url。'],
    limit: '这不是实时采集或实时推送流；循环重复的条目不算新增，没有有效原文地址的内容不会生成链接。'
  },
  sentiment: {
    title: '平台情绪结构',
    basis: 'ETL 发布有效情绪标签的分时段样本统计，大屏仅使用 positive、negative 两类结果。',
    steps: ['合并平台别名，将各时段正向、负向 sentiment_count 分别累加。', '平台正向占比 = 正向样本数 ÷（正向 + 负向）× 100%；负向占比同理，没有样本时为 0。'],
    limit: '分母是已纳入统计的正负向样本，不是全部新闻；中性、未识别及未入统计的样本不参与占比。'
  },
  peak: {
    title: '峰值传播强度',
    basis: '与传播热度脉冲使用相同数据：按时间合并各平台后，取最近 18 个时间桶。',
    steps: ['取这 18 个时间桶中最大的热度合计，并显示对应时间及该桶内容条数。', '仪表上限 = max(100, ceil(峰值 × 1.2 ÷ 100) × 100)，给峰值保留显示空间。'],
    limit: '它是展示窗口内的最大时段热度，不是全历史峰值，也不是每分钟传播速度；仪表进度不是业务达标率。'
  }
}
// 保存当前说明键，复用一个弹框承载所有图表说明。
const explanationKey = ref<keyof typeof explanations>('heat')
const explanationVisible = ref(false)
const activeExplanation = computed(() => explanations[explanationKey.value])
// 保存当前指标说明键，再打开共用弹框显示对应口径。
function openExplanation(key: keyof typeof explanations) {
  explanationKey.value = key
  explanationVisible.value = true
}
// 仅允许绝对 HTTP(S) 原文地址，防止异常协议被渲染为可点击链接。
function newsUrl(value: unknown): string | undefined {
  try {
    const url = new URL(String(value || '').trim())
    return ['http:', 'https:'].includes(url.protocol) ? url.href : undefined
  } catch {
    return undefined
  }
}
// 保存仪表盘完整响应，作为后续指标和图表的统一数据源。
const data = ref<any>({})
// 保存大屏显示的当前日期，时钟每秒更新时同步刷新。
const dateText = ref('')
// 保存大屏当前时分秒文本。
const timeText = ref('')
// 每次热度刷新后递增，触发对应展示区域更新。
const heatRefreshKey = ref(0)
// 保存时钟定时器编号，离开页面时取消。
let clockTimer: number | undefined
// 保存热度自动刷新定时器编号，与时钟分别管理。
let heatRefreshTimer: number | undefined
// 保存上次快照摘要，判断是否需要替换数据以减少重绘。
let dataSignature = ''

// 提取快照总览，未加载时提供空对象默认值。
const overview = computed(() => data.value.overview || {})
// 提取热度时间序列，供趋势和峰值计算使用。
const heat = computed<any[]>(() => data.value.heatTrend || [])
// 提取内容排名，供热榜展示。
const rank = computed<any[]>(() => data.value.contentRank || [])
// 提取快照中的真实内容，缺失时使用空列表。
const realContents = computed<any[]>(() => data.value.realPublicContents || [])
// 提取关键词排名，供关键词区域循环展示。
const keywords = computed<any[]>(() => data.value.keywordRank || [])
// 提取情感时间趋势，供大屏情感图生成使用。
const sentiment = computed<any[]>(() => data.value.sentimentTrend || [])
// 读取快照关联的最新批次，供同步状态和时间展示。
const latestBatch = computed(() => data.value.latestBatch || data.value.batch || undefined)

// 读取后端类别排名，供页面汇总展示。
const categoryRank = computed<any[]>(() => data.value.categoryRank || [])

const timeline = computed<any[]>(() => compactTimeline(data.value.platformTimeline || []))
// 读取平台总数，缺失时按有效平台列表数量显示。
const platformCount = computed(() => Number(overview.value.platform_count || timeline.value.length || 0))
// 优先读取后端源数量，缺失时按当前内容统计平台数。
const sourceCount = computed(() => Number(latestBatch.value?.source_count || overview.value.content_count || 0))
// 读取有效记录数，缺少批次统计时回退到总览内容量。
const validCount = computed(() => Number(latestBatch.value?.valid_count || overview.value.content_count || 0))
// 优先使用脏记录统计，缺失时用源数减有效数估算并限制非负。
const dirtyCount = computed(() => Math.max(0, Number(latestBatch.value?.dirty_count ?? (sourceCount.value - validCount.value))))
// 计算有效数占源记录数百分比，源数为零时返回零。
const cleanRate = computed(() => sourceCount.value ? Math.min(100, Number((validCount.value / sourceCount.value * 100).toFixed(1))) : 0)
// 依次从结束、开始和总览更新时间中选取展示时间。
const batchTime = computed(() => formatTime(latestBatch.value?.end_time || latestBatch.value?.start_time || overview.value.updated_at))

// 把来源数、有效量和清洗率组织为大屏指标。
const sourceMetrics = computed(() => [
  { label: '有效记录', value: validCount.value, unit: '条', ratio: cleanRate.value },
  { label: '异常记录', value: dirtyCount.value, unit: '条', ratio: dirtyCount.value ? Math.min(100, dirtyCount.value / Math.max(1, sourceCount.value) * 100) : 0 },
  { label: '平台覆盖', value: platformCount.value, unit: '个', ratio: Math.min(100, platformCount.value / 6 * 100) },
  { label: '关键词结果', value: keywords.value.length, unit: '项', ratio: Math.min(100, keywords.value.length / 30 * 100) }
])

// 整理趋势记录中的时间、内容数和热度值。
const trendRows = computed(() => {
  // 按相同时间桶合并平台记录，内容量与热度分别累加。
  const grouped = new Map<string, { time: string; content: number; hot: number }>()
  heat.value.forEach(item => {
    const time = String(item.time_bucket || '')
    const row = grouped.get(time) || { time, content: 0, hot: 0 }
    row.content += Number(item.content_count || 0)
    row.hot += Number(item.hot_score || 0)
    grouped.set(time, row)
  })
  // 时间升序后只取最近十八个桶，限制大屏横轴的展示密度。
  return [...grouped.values()].sort((a, b) => a.time.localeCompare(b.time)).slice(-18)
})

// 遍历趋势列表，取热度最高的时间点和数值。
const peakPoint = computed(() => trendRows.value.reduce((peak, item) => item.hot > peak.hot ? item : peak, { time: '', content: 0, hot: 0 }))

// 整理热榜字段并截取大屏需要展示的条数。
const rankRows = computed(() => {
  // 热榜先按热度排序，同热度按发布时间排序，再用内容编号或标题去重。
  const seen = new Set<string>()
  return [...rank.value]
    .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0) || String(b.publish_time || '').localeCompare(String(a.publish_time || '')))
    .filter(row => {
      const key = String(row.content_id || row.title || '')
      // 缺少标识或已经展示过的记录不进入热榜。
      if (!key || seen.has(key)) return false
      seen.add(key)
      return true
    })
    .slice(0, 6)
})

// 从真实内容中整理最新动态，供滚动列表使用。
const liveRows = computed(() => [...realContents.value]
  .sort((a, b) => String(b.publish_time || '').localeCompare(String(a.publish_time || '')))
  .slice(0, 10))
// 动态超过五条时复制列表，使滚动内容首尾衔接。
const tickerRows = computed(() => liveRows.value.length > 5 ? liveRows.value.concat(liveRows.value) : liveRows.value)

// 整理关键词名称、数量和相关统计，供大屏显示。
const keywordRows = computed(() => {
  // 去除通用词，并限制词长，最多保留十个可读关键词。
  const ignored = new Set(['热点', '新闻', '内容', '表示', '进行', '来源', '编辑'])
  return keywords.value
    .filter(item => {
      const word = String(item.keyword || '').trim()
      return word.length >= 2 && word.length <= 14 && !ignored.has(word)
    })
    .slice(0, 10)
})

// 把当前统计数据转换为热度或来源比较图的配置。
const heatOption = computed(() => {
  // 根据当前趋势生成横轴时间文本，最后一个位置用作最新节点提示。
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
        // 把图表参数统一为数组，再按数据下标找到对应时间桶。
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
        // 新增内容使用第二个纵轴，避免数量和热度共享刻度而失真。
        name: '新增内容', type: 'bar', yAxisIndex: 1, barMaxWidth: 22,
        data: rows.map(item => item.content),
        itemStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: '#6ee7b7' }, { offset: 1, color: 'rgba(16,185,129,.16)' }] }, borderRadius: [2, 2, 0, 0] }
      },
      {
        // 热度用折线显示，并在下方从同一趋势列表提取数值。
        name: '时段热度', type: 'line', smooth: 0.3, symbol: 'circle', symbolSize: 7,
        lineStyle: { width: 3, color: '#22d3ee', shadowBlur: 10, shadowColor: 'rgba(34,211,238,.45)' },
        itemStyle: { color: '#dffaff', borderColor: '#22d3ee', borderWidth: 2 },
        areaStyle: { color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1, colorStops: [{ offset: 0, color: 'rgba(34,211,238,.24)' }, { offset: 1, color: 'rgba(34,211,238,0)' }] } },
        data: rows.map(item => item.hot),
        markPoint: { symbol: 'pin', symbolSize: 42, itemStyle: { color: '#fbbf24' }, label: { color: '#07121e', fontWeight: 900 }, data: rows.length ? [{ type: 'max', name: '峰值' }] : [] }
      },
      {
        // 最新时间点叠加单个提示节点，不额外增加一组统计样本。
        name: '最新节点', type: 'effectScatter', coordinateSystem: 'cartesian2d', symbolSize: 12,
        rippleEffect: { brushType: 'stroke', scale: 3.2, period: 3 }, itemStyle: { color: '#fb7185' },
        data: rows.length ? [[labels[lastIndex], rows[lastIndex].hot]] : [], tooltip: { show: false }, z: 5
      }
    ]
  }
})

// 把平台统计转换为排名图的轴和系列数据。
const platformRankOption = computed(() => {
  // 排除微博后取热度前五个平台，倒序排列以适配横向柱图的显示顺序。
  const rows = timeline.value.filter(item => canonicalPlatform(item.platform) !== 'WEIBO').sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)).slice(0, 5).reverse()
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

// 把类别统计转换为分类分布图配置。
const categoryOption = computed(() => {
  // 最多绘制七个类别，提示和标签仍保留原始热度与内容量。
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
        // 柱条最小值设为一保证可见，但另存原值供标签显示真实热度。
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

// 根据平台时间线生成扩散图的展示数据。
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

// 把情感趋势转换为大屏正负分布图配置。
const sentimentOption = computed(() => {
  // 按平台归并正负记录，忽略其他标签，不让未知情感进入百分比分母。
  const platforms = timeline.value.map(item => canonicalPlatform(item.platform)).reverse()
  const labels = platforms.map(platformName)
  const grouped = new Map<string, Record<string, number>>()
  sentiment.value.forEach(item => {
    const platform = canonicalPlatform(item.platform)
    const row = grouped.get(platform) || { positive: 0, negative: 0 }
    const label = String(item.sentiment_label || '').toLowerCase()
    // 当前大屏只统计正面和负面，中性和跳过记录直接忽略。
    if (!['positive', 'negative'].includes(label)) return
    row[label] = (row[label] || 0) + Number(item.sentiment_count || 0)
    grouped.set(platform, row)
  })
  // 分别生成正负两个堆叠系列，平台内两项占比之和为百分之百或零。
  const seriesConfig = [
    { key: 'positive', name: '正向', color: '#34d399' },
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
        // 每个平台单独以正负样本合计为分母，保留原始数量供提示显示。
        const row = grouped.get(platform) || { positive: 0, negative: 0 }
        const total = Object.values(row).reduce((sum, value) => sum + Number(value || 0), 0)
        const count = Number(row[config.key] || 0)
        return { value: total ? count / total * 100 : 0, count }
      })
    }))
  }
})

// 读取趋势峰值作为仪表盘数值，并为刻度上限预留空间。
const speedOption = computed(() => {
  // 最大刻度至少为一百，否则按峰值的一点二倍向上取整到百位。
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

// 比较批次、总量、榜首及平台指标签名，仅在摘要变化时替换快照，减少重复渲染。
async function load() {
  // 请求快照后，用批次、总量、榜首和平台统计拼接轻量比较签名。
  const dashboard = await getData('/analysis/screen').catch(() => ({}))
  const platformSignature = (dashboard.platformTimeline || []).map((item: any) => `${item.platform}:${item.content_count}:${item.hot_score}`).join(',')
  const nextSignature = [dashboard.latestBatch?.batch_id, dashboard.overview?.content_count, dashboard.overview?.hot_score, dashboard.contentRank?.[0]?.content_id, platformSignature].join('|')
  // 摘要相同则保留原响应式对象，避免相同数据引起图表重绘。
  if (nextSignature !== dataSignature) {
    dataSignature = nextSignature
    data.value = dashboard
  }
}

// 定时读取数据后推进热度刷新标记，让展示动效与数据读取周期同步。
async function refreshHeatPulse() {
  await load()
  heatRefreshKey.value += 1
}

// 读取本地当前时刻，分别更新大屏日期和时分秒。
function tick() {
  const current = new Date()
  dateText.value = current.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', weekday: 'short' })
  timeText.value = current.toLocaleTimeString('zh-CN', { hour12: false })
}
// 把热度转换为整数、一位小数或万单位显示，兼容空值。
function heatText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
// 将计数转换为中文数字格式或万单位，减少大数值显示长度。
function countText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(0) }
// 将后端时间转换为当前页面需要的日期时间文本，空值显示占位符。
function formatTime(value: any) {
  const text = String(value || '').replace('T', ' ')
  if (!text) return '--'
  const stamp = text.match(/\d{2}-\d{2} \d{2}:\d{2}/)
  return stamp ? stamp[0] : text.slice(5, 16)
}
// 替换 ISO 时间分隔符并截取到分钟，空值显示占位符。
function formatFullTime(value: any) { return String(value || '').replace('T', ' ').slice(0, 16) || '--' }
// 将延迟分钟转换为首发、分钟、小时或天数说明。
function delayText(value: any) {
  const minutes = Number(value || 0)
  if (!minutes) return '首发平台'
  if (minutes >= 1440) return `${(minutes / 1440).toFixed(1)}天`
  if (minutes >= 60) return `${(minutes / 60).toFixed(1)}小时`
  return `${minutes}分钟`
}
// 过长关键词截断并添加省略号，防止词条宽度超限。
function shortKeyword(value: any) { const text = String(value || ''); return text.length > 10 ? `${text.slice(0, 10)}…` : text }
function keywordColor(value: any) { return ({ positive: '#6ee7b7', neutral: '#7dd3fc', negative: '#fb7185' } as Record<string, string>)[String(value || '').toLowerCase()] || '#7dd3fc' }
function categoryColor(item: any) { return ({ finance: '#d9952f', politics: '#d96078', technology: '#1fa6bd', society: '#7c6ed1', culture: '#db7042', sports: '#2ca66f', general: '#477fc1' } as Record<string, string>)[item.category] || '#3e91b5' }
// 优先使用已有中文类别，否则翻译类别编码，微博评论不展示新闻类别。
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
// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: any) { const code = canonicalPlatform(value); return PLATFORM_LABELS[code] || '其他平台' }
// 读取平台的固定图表颜色，未找到时采用备用颜色。
function platformColor(value: any) { return PLATFORM_COLORS[canonicalPlatform(value)] || '#7dd3fc' }
// 归一化平台别名后合并数量与热度、保留最早发布时间，并截取前八个平台。
function compactTimeline(rows: any[]) {
  // 先把中英文别名转为同一平台编码，再合并重复平台的数量与热度。
  const grouped = new Map<string, any>()
  rows.forEach(item => {
    const platform = canonicalPlatform(item.platform)
    const row = grouped.get(platform) || { ...item, platform, content_count: 0, hot_score: 0 }
    row.content_count += Number(item.content_count || 0)
    row.hot_score += Number(item.hot_score || 0)
    // 同平台有多个记录时保留最早发布时间，供首达时间展示。
    if (!row.first_publish_time || String(item.first_publish_time || '') < String(row.first_publish_time)) row.first_publish_time = item.first_publish_time
    grouped.set(platform, row)
  })
  return [...grouped.values()].sort((a, b) => Number(a.delay_minutes || 0) - Number(b.delay_minutes || 0)).slice(0, 8)
}

// 定义规范平台编码与中文名对应关系，归一化后据此显示。
const PLATFORM_LABELS: Record<string, string> = {
  TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', WEIBO: '微博', OWN_SITE: '新闻都知道',
  DOUYIN: '抖音', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '其他新闻', UNKNOWN: '未知平台'
}
// 为平台定义固定图表颜色，方便跨图表识别。
const PLATFORM_COLORS: Record<string, string> = {
  TENCENT_NEWS: '#35b9e8', NETEASE_NEWS: '#e95b66', SOHU_NEWS: '#f59e45', SINA_NEWS: '#e6c64c', THE_PAPER: '#8b7de3', WEIBO: '#fb7185', OWN_SITE: '#43c991', NEWS: '#6f8ea3', UNKNOWN: '#6f8ea3'
}

// 时钟每秒更新，热度每五秒刷新；两个定时器在大屏卸载时分别释放。
onMounted(() => {
  tick()
  load()
  clockTimer = window.setInterval(tick, 1000)
  heatRefreshTimer = window.setInterval(() => void refreshHeatPulse(), 5000)
})
onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
  if (heatRefreshTimer) clearInterval(heatRefreshTimer)
})

useDatabaseAutoRefresh(load)
</script>
