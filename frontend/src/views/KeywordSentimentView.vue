<template>
  <div class="analysis-front keyword-analysis-page">
    <div class="analysis-page-head">
      <div>
        <span>关键词分析</span>
        <h2>跨主题关注与采编建议</h2>
        <p>以新闻内容的互动总量为热度，按关键词共现关系归并主题簇，避免同一主题重复进入 Top N。</p>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
      </div>
    </div>

    <div class="grid third">
      <section class="analysis-card keyword-cloud-card">
        <div class="card-title">主题关键词云 <span>Top25 独立主题</span></div>
        <div class="category-legend" aria-label="类别图例">
          <button v-for="category in categoryLegend" :key="category.name" type="button" :class="{ muted: hiddenCloudCategories.has(category.name) }" @click="toggleCloudCategory(category.name)">
            <i :style="{ background: category.color }"></i>{{ category.name }}
          </button>
        </div>
        <div class="keyword-cloud dynamic-cloud">
          <button v-for="(topic, index) in cloudTopics" :key="topic.keyword" type="button" :title="topic.cluster_label" :style="cloudStyle(topic, index)" @click="selectTopic(topic.keyword)">{{ topic.keyword }}</button>
          <p v-if="!cloudTopics.length" class="chart-empty">当前类别筛选下暂无关键词</p>
        </div>
      </section>

      <section class="analysis-card keyword-trend-card">
        <div class="card-title chart-title-with-control">
          <span>主题热度趋势 <small>Top8 跨主题代表词</small></span>
          <el-radio-group v-model="trendGranularity" size="small" aria-label="时间粒度">
            <el-radio-button label="day">日</el-radio-button>
            <el-radio-button label="week">周</el-radio-button>
          </el-radio-group>
        </div>
        <ChartBox class="keyword-chart" :option="keywordTrendOption" />
      </section>

      <section class="analysis-card keyword-platform-card">
        <div class="card-title">主题-平台分布 <span>Top10 跨主题代表词</span></div>
        <ChartBox class="keyword-platform-chart" :option="keywordPlatformOption" />
      </section>
    </div>

    <section class="analysis-card keyword-momentum-card">
      <div class="card-title">话题动量排行 <span>按最近 7 天与前 7 天的代表词频变化计算</span></div>
      <div class="momentum-grid">
        <div class="momentum-panel emerging">
          <h3>🔥 新兴话题 <small>近 7 天增速最快</small></h3>
          <div v-if="emergingTopics.length" class="momentum-list">
            <el-tooltip v-for="(topic, index) in emergingTopics" :key="topic.keyword" placement="right" effect="light" popper-class="keyword-momentum-tooltip">
              <template #content>
                <div class="sparkline-tooltip">
                  <strong>{{ topic.keyword }} · 近 4 周词频</strong>
                  <svg viewBox="0 0 120 30" role="img" aria-label="近四周词频趋势"><polyline :points="sparklinePoints(topic)" fill="none" stroke="#dc2626" stroke-width="2" /></svg>
                  <small>{{ topicSparkline(topic).join(' · ') }}</small>
                </div>
              </template>
              <button class="momentum-row" type="button" @click="selectTopic(topic.keyword)">
                <span class="momentum-rank">{{ index + 1 }}</span>
                <b :title="topic.cluster_label">{{ topic.keyword }}</b>
                <span class="category-tag" :style="{ '--tag-color': CATEGORY_COLORS[topic.category_top] }">{{ topic.category_top }}</span>
                <span class="momentum-count">{{ topic.current_count }} 篇</span>
                <strong class="momentum-change up">↑ {{ growthLabel(topic) }}</strong>
              </button>
            </el-tooltip>
          </div>
          <p v-else class="momentum-empty">最近两个周期没有可比较的增长话题</p>
        </div>
        <div class="momentum-panel declining">
          <h3>❄️ 衰退话题 <small>近 7 天衰退最快</small></h3>
          <div v-if="decliningTopics.length" class="momentum-list">
            <el-tooltip v-for="(topic, index) in decliningTopics" :key="topic.keyword" placement="left" effect="light" popper-class="keyword-momentum-tooltip">
              <template #content>
                <div class="sparkline-tooltip">
                  <strong>{{ topic.keyword }} · 近 4 周词频</strong>
                  <svg viewBox="0 0 120 30" role="img" aria-label="近四周词频趋势"><polyline :points="sparklinePoints(topic)" fill="none" stroke="#0891b2" stroke-width="2" /></svg>
                  <small>{{ topicSparkline(topic).join(' · ') }}</small>
                </div>
              </template>
              <button class="momentum-row" type="button" @click="selectTopic(topic.keyword)">
                <span class="momentum-rank">{{ index + 1 }}</span>
                <b :title="topic.cluster_label">{{ topic.keyword }}</b>
                <span class="category-tag" :style="{ '--tag-color': CATEGORY_COLORS[topic.category_top] }">{{ topic.category_top }}</span>
                <span class="momentum-count">{{ topic.current_count }} 篇</span>
                <strong class="momentum-change down">↓ {{ growthLabel(topic) }}</strong>
              </button>
            </el-tooltip>
          </div>
          <p v-else class="momentum-empty">最近两个周期没有可比较的衰退话题</p>
        </div>
      </div>
    </section>

    <div class="grid half equal-grid" style="margin-top:12px">
      <section class="analysis-card keyword-quadrant-card">
        <div class="card-title">话题供需效率四象限图 <span>供给为内容篇数，需求效率为单篇平均互动</span></div>
        <ChartBox class="keyword-quadrant-chart" :option="keywordQuadrantOption" @chart-click="handleQuadrantClick" />
      </section>

      <section class="analysis-card table-card keyword-table-card">
        <div class="card-title">主题关键词观察清单 <span>代表词点击后联动图表</span></div>
        <div class="keyword-table-tools">
          <el-input v-model="tableQuery" placeholder="按关键词搜索" clearable />
          <el-select v-model="tableCategory" aria-label="按类别筛选">
            <el-option label="全部类别" value="全部" />
            <el-option v-for="category in categoryNames" :key="category" :label="category" :value="category" />
          </el-select>
          <el-select v-model="tableSort" aria-label="排序方式">
            <el-option label="累计热度降序" value="heat" />
            <el-option label="内容量降序" value="content" />
            <el-option label="覆盖平台降序" value="platform" />
            <el-option label="关键词升序" value="keyword" />
          </el-select>
        </div>
        <table class="table fixed-rows keyword-observation-table">
          <thead>
            <tr>
              <th>关键词</th>
              <th>内容量</th>
              <th>覆盖平台</th>
              <th>
                <el-tooltip content="热度 = 含该主题词内容的点赞 + 评论 + 转发 + 收藏之和" placement="top">
                  <span class="heat-heading">累计热度 <b>?</b></span>
                </el-tooltip>
              </th>
              <th>关注结论</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="topic in pagedTopics" :key="topic.keyword" @click="selectTopic(topic.keyword)">
              <td :title="topic.cluster_label"><b>{{ topic.keyword }}</b><small>{{ topic.category_top }}</small></td>
              <td>{{ topic.content_count }}</td>
              <td>{{ topic.platform_count }}</td>
              <td>{{ formatNumber(topic.heat_total) }}</td>
              <td><span class="conclusion-cell" :title="topic.conclusion">{{ topic.conclusion }}</span></td>
            </tr>
            <tr v-for="index in emptyTopicRows" :key="`topic-empty-${index}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="keywordPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredTopics.length" />
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

type Granularity = 'day' | 'week'

const NEWS_PLATFORMS = ['SOHU_NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SINA_NEWS', 'THE_PAPER']
const CATEGORY_COLORS: Record<string, string> = {
  财经: '#2563eb', 政治: '#7c3aed', 文娱: '#db2777', 综合: '#64748b', 社会: '#ea580c', 体育: '#16a34a', 科技: '#0f766e'
}
const CATEGORY_NAMES = ['财经', '政治', '文娱', '综合', '社会', '体育', '科技']
const STOPWORDS = new Set(['没有', '为什么', '就是', '不是', '这个', '发文', '感谢', '不要', '除了', '还有', '运气', '切片', '包场'])

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const rows = ref<any[]>([])
const selectedTopic = ref('')
const trendGranularity = ref<Granularity>('week')
const tableQuery = ref('')
const tableCategory = ref('全部')
const tableSort = ref('heat')
const keywordPage = ref(1)
const pageSize = 8
const hiddenCloudCategories = ref(new Set<string>())

const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')
const categoryNames = computed(() => CATEGORY_NAMES)
const categoryLegend = computed(() => CATEGORY_NAMES.map(name => ({ name, color: CATEGORY_COLORS[name] })))

const contentRecords = computed(() => {
  const uniqueRows = new Map<string, any>()
  rows.value.forEach((row, index) => {
    const id = String(row.content_id || `row-${index}`)
    if (!uniqueRows.has(id)) uniqueRows.set(id, row)
  })
  return [...uniqueRows.values()].map((row, index) => ({
    id: String(row.content_id || `row-${index}`),
    platform: String(row.platform || ''),
    category: categoryName(row.category_label || row.category),
    day: dayKey(row.publish_time),
    keywords: splitKeywords(row.keywords),
    heat: interactionHeat(row),
    author: String(row.author_name || '').trim()
  })).filter(row => NEWS_PLATFORMS.includes(row.platform) && row.keywords.length > 0)
})

const rawKeywordStats = computed(() => {
  const grouped = new Map<string, any>()
  for (const row of contentRecords.value) {
    for (const keyword of row.keywords) {
      const item = grouped.get(keyword) || { keyword, content_count: 0, heat_total: 0 }
      item.content_count += 1
      item.heat_total += row.heat
      grouped.set(keyword, item)
    }
  }
  return [...grouped.values()].sort((left, right) => right.content_count - left.content_count || right.heat_total - left.heat_total)
})

const topicClusters = computed(() => {
  const parent = new Map<string, string>()
  rawKeywordStats.value.forEach(item => parent.set(item.keyword, item.keyword))
  const find = (keyword: string): string => {
    const current = parent.get(keyword) || keyword
    if (current === keyword) return keyword
    const root = find(current)
    parent.set(keyword, root)
    return root
  }
  const unite = (left: string, right: string) => {
    const leftRoot = find(left)
    const rightRoot = find(right)
    if (leftRoot !== rightRoot) parent.set(rightRoot, leftRoot)
  }
  const pairCounts = new Map<string, number>()
  for (const row of contentRecords.value) {
    const members = [...new Set(row.keywords)].sort()
    for (let left = 0; left < members.length; left += 1) {
      for (let right = left + 1; right < members.length; right += 1) {
        const key = `${members[left]}\u0000${members[right]}`
        pairCounts.set(key, (pairCounts.get(key) || 0) + 1)
      }
    }
  }
  const frequencies = new Map(rawKeywordStats.value.map(item => [item.keyword, item.content_count]))
  const minimumCooccurrence = contentRecords.value.length >= 80 ? 3 : 2
  for (const [pair, count] of pairCounts) {
    const [left, right] = pair.split('\u0000')
    const overlap = count / Math.max(1, Math.min(frequencies.get(left) || 0, frequencies.get(right) || 0))
    if (count >= minimumCooccurrence && overlap >= .6) unite(left, right)
  }
  const clusters = new Map<string, string[]>()
  for (const keyword of parent.keys()) {
    const root = find(keyword)
    const members = clusters.get(root) || []
    members.push(keyword)
    clusters.set(root, members)
  }
  const rawByKeyword = new Map(rawKeywordStats.value.map(item => [item.keyword, item]))
  return [...clusters.values()].map(members => {
    const sorted = [...members].sort((left, right) => Number(rawByKeyword.get(right)?.content_count || 0) - Number(rawByKeyword.get(left)?.content_count || 0)
      || Number(rawByKeyword.get(right)?.heat_total || 0) - Number(rawByKeyword.get(left)?.heat_total || 0)
      || left.localeCompare(right))
    return { keyword: sorted[0], members: sorted, cluster_label: sorted.slice(0, 5).join('、') }
  })
})

const topicInsights = computed(() => {
  const topicByKeyword = new Map<string, string>()
  const grouped = new Map<string, any>()
  topicClusters.value.forEach(cluster => {
    grouped.set(cluster.keyword, {
      keyword: cluster.keyword,
      member_keywords: cluster.members,
      cluster_label: cluster.cluster_label,
      content_count: 0,
      heat_total: 0,
      platforms: new Set<string>(),
      platform_counts: new Map<string, number>(),
      category_counts: new Map<string, number>(),
      day_counts: new Map<string, number>(),
      authors: new Set<string>()
    })
    cluster.members.forEach(keyword => topicByKeyword.set(keyword, cluster.keyword))
  })
  for (const row of contentRecords.value) {
    const topicsInContent = new Set(row.keywords.map(keyword => topicByKeyword.get(keyword)).filter(Boolean) as string[])
    for (const topic of topicsInContent) {
      const item = grouped.get(topic)
      item.content_count += 1
      item.heat_total += row.heat
      item.platforms.add(row.platform)
      item.platform_counts.set(row.platform, (item.platform_counts.get(row.platform) || 0) + 1)
      item.category_counts.set(row.category, (item.category_counts.get(row.category) || 0) + 1)
      if (row.day) item.day_counts.set(row.day, (item.day_counts.get(row.day) || 0) + 1)
      if (row.author) item.authors.add(row.author)
    }
  }
  return [...grouped.values()].map(item => ({
    keyword: item.keyword,
    member_keywords: item.member_keywords,
    cluster_label: item.cluster_label,
    content_count: item.content_count,
    heat_total: Math.round(item.heat_total),
    platform_count: item.platforms.size,
    author_count: item.authors.size,
    platform_counts: Object.fromEntries(item.platform_counts),
    category_top: topKey(item.category_counts),
    day_counts: Object.fromEntries(item.day_counts)
  })).sort((left, right) => right.heat_total - left.heat_total || right.content_count - left.content_count)
})

const topTopics = computed(() => topicInsights.value)
const cloudTopics = computed(() => topTopics.value.filter(topic => !hiddenCloudCategories.value.has(topic.category_top)).slice(0, 25))
const trendTopics = computed(() => {
  const primary = topTopics.value.slice(0, 8)
  const selected = topTopics.value.find(topic => topic.keyword === selectedTopic.value)
  return selected && !primary.some(topic => topic.keyword === selected.keyword)
    ? [selected, ...primary.slice(0, 7)]
    : primary
})
const platformTopics = computed(() => topTopics.value.slice(0, 10))

const trendBuckets = computed(() => [...new Set(contentRecords.value.map(row => bucketKey(row.day, trendGranularity.value)).filter(Boolean))].sort())
const trendSeries = computed(() => trendTopics.value.map(topic => ({
  name: topic.keyword,
  data: trendBuckets.value.map(bucket => bucketCount(topic, bucket, trendGranularity.value))
})))
const keywordTrendOption = computed(() => ({
  color: ['#2563eb', '#0f766e', '#d97706', '#7c3aed', '#dc2626', '#0891b2', '#16a34a', '#be123c'],
  tooltip: { trigger: 'axis', formatter: (params: any[]) => `${params[0]?.axisValue || ''}<br/>${params.map(item => `${item.marker}${item.seriesName}：<b>${item.value}</b> 篇`).join('<br/>')}` },
  legend: { top: 0, type: 'scroll', data: trendSeries.value.map(item => item.name), textStyle: { color: '#475569' } },
  grid: { left: 44, right: 16, top: 44, bottom: 32 },
  xAxis: { type: 'category', boundaryGap: false, data: trendBuckets.value, axisLabel: { color: '#64748b', hideOverlap: true } },
  yAxis: { type: 'value', minInterval: 1, max: (value: any) => Math.max(1, Math.ceil(value.max * 1.2)), axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
  series: trendSeries.value.map(item => {
    const highlighted = !selectedTopic.value || item.name === selectedTopic.value
    return {
      name: item.name,
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: selectedTopic.value === item.name ? 7 : 4,
      z: selectedTopic.value === item.name ? 5 : 1,
      lineStyle: { width: selectedTopic.value === item.name ? 4 : 2, opacity: highlighted ? 1 : .24 },
      itemStyle: { opacity: highlighted ? 1 : .24 },
      data: item.data
    }
  })
}))

const keywordPlatformOption = computed(() => {
  const max = Math.max(1, ...platformTopics.value.flatMap(topic => NEWS_PLATFORMS.map(platform => Number(topic.platform_counts[platform] || 0))))
  return {
    tooltip: { position: 'top', formatter: (params: any) => `${platformName(NEWS_PLATFORMS[params.value[1]])}<br/>${platformTopics.value[params.value[0]]?.keyword || ''}<br/>内容量：<b>${params.value[2]}</b>` },
    grid: { left: 76, right: 16, top: 10, bottom: 78 },
    xAxis: { type: 'category', data: platformTopics.value.map(item => item.keyword), axisLabel: { color: '#475569', rotate: 28, interval: 0 } },
    yAxis: { type: 'category', data: NEWS_PLATFORMS.map(platformName), axisLabel: { color: '#475569' } },
    visualMap: { min: 0, max, calculable: false, orient: 'horizontal', left: 'center', bottom: 2, itemWidth: 12, itemHeight: 116, textStyle: { color: '#64748b' }, inRange: { color: ['#f1f5f9', '#bfdbfe', '#2563eb'] } },
    series: [{ type: 'heatmap', data: platformTopics.value.flatMap((topic, x) => NEWS_PLATFORMS.map((platform, y) => [x, y, Number(topic.platform_counts[platform] || 0)])), label: { show: true, color: '#1e293b', formatter: (params: any) => params.value[2] || '' }, emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(37,99,235,.25)' } } }]
  }
})

const latestAnalysisDay = computed(() => {
  const days = [...new Set(contentRecords.value.map(row => row.day).filter(Boolean))].sort()
  return days[days.length - 1] || ''
})
const momentumTopics = computed(() => topTopics.value.map(topic => {
  const latest = latestAnalysisDay.value
  const current_count = latest ? dayRangeCount(topic, shiftDay(latest, -6), latest) : 0
  const previous_count = latest ? dayRangeCount(topic, shiftDay(latest, -13), shiftDay(latest, -7)) : 0
  const growth = previous_count > 0 ? (current_count - previous_count) / previous_count : (current_count > 0 ? 1 : 0)
  const growth_sort = previous_count > 0 ? growth : (current_count > 0 ? 10 + current_count / 1000 : 0)
  return { ...topic, current_count, previous_count, growth, growth_sort }
}))
const emergingTopics = computed(() => momentumTopics.value.filter(topic => topic.current_count > 0 && topic.growth > 0)
  .sort((left, right) => right.growth_sort - left.growth_sort || right.current_count - left.current_count).slice(0, 10))
const decliningTopics = computed(() => momentumTopics.value.filter(topic => topic.previous_count > 0 && topic.growth < 0)
  .sort((left, right) => left.growth - right.growth || left.current_count - right.current_count).slice(0, 10))

const quadrantTopics = computed(() => topTopics.value.slice(0, 15).map(topic => ({
  ...topic,
  demand_efficiency: interactionPerContent(topic)
})))
const supplyMedian = computed(() => median(quadrantTopics.value.map(topic => topic.content_count)))
const demandMedian = computed(() => median(quadrantTopics.value.map(topic => topic.demand_efficiency)))
const keywordQuadrantOption = computed(() => {
  const topics = quadrantTopics.value
  const maxSupply = Math.max(1, ...topics.map(topic => topic.content_count))
  const maxDemand = Math.max(1, ...topics.map(topic => topic.demand_efficiency))
  return {
    tooltip: {
      formatter: (params: any) => {
        const topic = params.data
        if (!topic) return ''
        return `<b>${topic.name}</b><br/>内容供给：<b>${topic.content_count}</b> 篇<br/>需求效率：<b>${formatNumber(topic.demand_efficiency)}</b> / 篇<br/>覆盖作者：<b>${topic.author_count}</b><br/>所属簇：${topic.cluster_label}`
      }
    },
    grid: { left: 64, right: 44, top: 54, bottom: 58 },
    xAxis: {
      type: 'value',
      name: '内容供给（篇）',
      nameTextStyle: { color: '#334155', fontWeight: 700, padding: [8, 0, 0, 0] },
      max: Math.ceil(maxSupply * 1.15),
      axisLabel: { color: '#475569' },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      name: '需求效率（平均互动）',
      nameTextStyle: { color: '#334155', fontWeight: 700, padding: [0, 0, 8, 0] },
      max: Math.ceil(maxDemand * 1.15),
      axisLabel: { color: '#475569', formatter: (value: number) => formatNumber(value) },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    graphic: topics.length ? [
      { type: 'text', left: '9%', top: 5, style: { text: '低供给高互动 → 加大产出', fill: '#b91c1c', fontSize: 12, fontWeight: 700 } },
      { type: 'text', right: '7%', top: 5, style: { text: '高供给高互动 → 维持产出', fill: '#166534', fontSize: 12, fontWeight: 700 } },
      { type: 'text', left: '9%', bottom: 3, style: { text: '低供给低互动 → 暂不投入', fill: '#64748b', fontSize: 12, fontWeight: 700 } },
      { type: 'text', right: '7%', bottom: 3, style: { text: '高供给低互动 → 缩减产出', fill: '#b45309', fontSize: 12, fontWeight: 700 } }
    ] : [{ type: 'text', left: 'center', top: 'middle', style: { text: '暂无可用于供需分析的主题数据', fill: '#64748b', fontSize: 14 } }],
    series: CATEGORY_NAMES.map((category, index) => ({
      name: category,
      type: 'scatter',
      data: topics.filter(topic => topic.category_top === category).map(topic => ({
        name: topic.keyword,
        value: [topic.content_count, topic.demand_efficiency, topic.author_count],
        content_count: topic.content_count,
        demand_efficiency: topic.demand_efficiency,
        author_count: topic.author_count,
        cluster_label: topic.cluster_label
      })),
      symbolSize: (value: number[]) => Math.max(12, Math.min(36, 10 + Math.sqrt(Math.max(1, value[2])) * 3.5)),
      itemStyle: { color: CATEGORY_COLORS[category], opacity: .78, borderColor: '#fff', borderWidth: 1 },
      label: { show: false },
      emphasis: { scale: true, itemStyle: { opacity: 1, borderWidth: 2 }, label: { show: true, formatter: (params: any) => params.data.name, position: 'top', color: '#0f172a', fontSize: 12, fontWeight: 700 } },
      markLine: index === 0 ? {
        silent: true,
        symbol: 'none',
        label: { show: false },
        lineStyle: { color: '#94a3b8', type: 'dashed', width: 1.25 },
        data: [{ xAxis: supplyMedian.value }, { yAxis: demandMedian.value }]
      } : undefined
    }))
  }
})
const filteredTopics = computed(() => {
  const query = tableQuery.value.trim().toLowerCase()
  return topTopics.value.filter(topic => {
    const matchesQuery = !query || `${topic.keyword} ${topic.member_keywords.join(' ')}`.toLowerCase().includes(query)
    return matchesQuery && (tableCategory.value === '全部' || topic.category_top === tableCategory.value)
  }).sort((left, right) => {
    if (tableSort.value === 'content') return right.content_count - left.content_count || right.heat_total - left.heat_total
    if (tableSort.value === 'platform') return right.platform_count - left.platform_count || right.heat_total - left.heat_total
    if (tableSort.value === 'keyword') return left.keyword.localeCompare(right.keyword)
    return right.heat_total - left.heat_total || right.content_count - left.content_count
  })
})
const pagedTopics = computed(() => filteredTopics.value.slice((keywordPage.value - 1) * pageSize, keywordPage.value * pageSize).map(topic => ({ ...topic, conclusion: topicConclusion(topic) })))
const emptyTopicRows = computed(() => Math.max(0, pageSize - pagedTopics.value.length))

watch([tableQuery, tableCategory, tableSort], () => { keywordPage.value = 1 })
useDatabaseAutoRefresh(load)

async function load() {
  rows.value = await getData(`/events/${eventId.value}/keyword-analysis`).catch(async () => {
    const dashboard = await getData(`/events/${eventId.value}/dashboard`)
    return dashboard.realPublicContents || dashboard.trendContentRank || []
  })
  selectedTopic.value = ''
}

function splitKeywords(value: any) {
  return String(value || '').split(/[,，、/|；;\s]+/).map(item => item.trim()).filter(validKeyword)
}
function validKeyword(keyword: string) {
  return keyword.length >= 2 && !STOPWORDS.has(keyword) && !/^[a-zA-Z]$/.test(keyword) && !/^\d+$/.test(keyword)
}
function interactionHeat(row: any) {
  return ['like_count', 'comment_count', 'repost_count', 'favorite_count'].reduce((total, field) => total + Number(row[field] || 0), 0)
}
function dayKey(value: any) { return value ? String(value).replace('T', ' ').slice(0, 10) : '' }
function bucketKey(day: string, granularity: Granularity) {
  if (!day) return ''
  if (granularity === 'day') return day
  const date = new Date(`${day}T00:00:00`)
  const offset = (date.getDay() + 6) % 7
  date.setDate(date.getDate() - offset)
  return date.toISOString().slice(0, 10)
}
function bucketCount(topic: any, bucket: string, granularity: Granularity) {
  return Object.entries(topic.day_counts || {}).reduce((total, [day, count]) => total + (bucketKey(day, granularity) === bucket ? Number(count) : 0), 0)
}
function shiftDay(day: string, offset: number) {
  const [year, month, date] = day.split('-').map(Number)
  const value = new Date(Date.UTC(year, month - 1, date))
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}
function dayRangeCount(topic: any, start: string, end: string) {
  return Object.entries(topic.day_counts || {}).reduce((total, [day, count]) => total + (day >= start && day <= end ? Number(count) : 0), 0)
}
function topicSparkline(topic: any) {
  if (!latestAnalysisDay.value) return [0, 0, 0, 0]
  return [3, 2, 1, 0].map(index => {
    const end = shiftDay(latestAnalysisDay.value, -index * 7)
    return dayRangeCount(topic, shiftDay(end, -6), end)
  }).reverse()
}
function sparklinePoints(topic: any) {
  const values = topicSparkline(topic)
  const maximum = Math.max(1, ...values)
  return values.map((value, index) => `${index * 40},${27 - Math.round(value / maximum * 22)}`).join(' ')
}
function growthLabel(topic: any) {
  return topic.previous_count === 0 && topic.current_count > 0 ? '新增' : `${Math.abs(Number(topic.growth || 0) * 100).toFixed(0)}%`
}
function interactionPerContent(topic: any) { return Number(topic.heat_total || 0) / Math.max(1, Number(topic.content_count || 0)) }
function topicConclusion(topic: any) {
  const demand = interactionPerContent(topic)
  if (topic.content_count < supplyMedian.value && demand >= demandMedian.value) return '供给偏低、互动较高，建议加大产出'
  if (topic.content_count >= supplyMedian.value && demand < demandMedian.value) return '供给偏高、互动较低，建议控制选题密度'
  const momentum = momentumTopics.value.find(item => item.keyword === topic.keyword)
  if (Number(momentum?.growth || 0) > 0) return '近期词频上升，建议持续跟进'
  if (Number(momentum?.growth || 0) < 0) return '近期词频走弱，建议转入观察'
  return '供需相对稳定，持续观察'
}
function median(values: number[]) {
  const sorted = [...values].sort((left, right) => left - right)
  if (!sorted.length) return 0
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}
function topKey(values: Map<string, number>) { return [...values.entries()].sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))[0]?.[0] || '综合' }
function categoryName(value: any) {
  const text = String(value || '').trim()
  return ({ finance: '财经', politics: '政治', culture: '文娱', general: '综合', society: '社会', sports: '体育', technology: '科技' } as Record<string, string>)[text] || (CATEGORY_COLORS[text] ? text : '综合')
}
function platformName(value: string) { return ({ SOHU_NEWS: '搜狐新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' } as Record<string, string>)[value] || value }
function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(Number(value || 0)) }
function selectTopic(keyword: string) { selectedTopic.value = keyword }
function handleQuadrantClick(params: any) {
  if (params?.data?.name) selectTopic(params.data.name)
}
function toggleCloudCategory(category: string) {
  const next = new Set(hiddenCloudCategories.value)
  if (next.has(category)) next.delete(category)
  else next.add(category)
  hiddenCloudCategories.value = next
}
function cloudStyle(topic: any, index: number) {
  // A loose spiral avoids a dense center while keeping every word inside the cloud silhouette.
  const positions = [
    [50, 48, 0], [30, 31, -10], [71, 29, 10], [29, 65, 90], [72, 66, -90],
    [50, 15, 8], [51, 83, -8], [19, 47, 0], [82, 48, 0], [39, 42, -12],
    [63, 44, 12], [39, 72, 8], [63, 73, -8], [38, 18, 90], [63, 18, -90],
    [22, 75, 12], [80, 76, -12], [23, 23, 0], [78, 22, 8], [50, 33, -6],
    [50, 62, 6], [32, 52, 90], [68, 54, -90], [39, 88, 0], [62, 88, 10]
  ]
  const [left, top, rotate] = positions[index % positions.length]
  const max = Math.max(1, ...topTopics.value.map(item => item.content_count))
  const relativeWeight = Math.pow(topic.content_count / max, .58)
  return {
    left: `${left}%`,
    top: `${top}%`,
    transform: `translate(-50%, -50%) rotate(${rotate}deg)`,
    fontSize: `${11 + Math.round(relativeWeight * 25)}px`,
    color: CATEGORY_COLORS[topic.category_top] || CATEGORY_COLORS.综合
  }
}

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取关键词分析失败'))
})
</script>

<style scoped>
.keyword-analysis-page .third { align-items: stretch; }
.keyword-analysis-page .analysis-card { min-width: 0; }
.keyword-analysis-page .third > .analysis-card { min-height: 378px; display: flex; flex-direction: column; }
.category-legend { min-height: 24px; display: flex; flex-wrap: wrap; gap: 5px 10px; margin: -2px 0 6px; color: #334155; font-size: 12px; }
.category-legend button { padding: 0; display: inline-flex; align-items: center; gap: 4px; border: 0; background: transparent; color: #334155; cursor: pointer; font-weight: 700; transition: opacity .16s ease; }
.category-legend button.muted { opacity: .32; }
.category-legend i { width: 8px; height: 8px; border-radius: 50%; }
.keyword-cloud { position: relative; min-height: 310px; flex: 1; overflow: hidden; }
.keyword-cloud.dynamic-cloud { display: block; border: 1px solid #e1e9f2; border-radius: 6px; background: #f8fbff; box-shadow: inset 0 0 0 8px rgba(255, 255, 255, .45); }
.keyword-cloud.dynamic-cloud button { position: absolute; padding: 0; border: 0; background: transparent; cursor: pointer; font-weight: 700; line-height: 1.08; text-align: center; text-shadow: 0 1px 0 rgba(255, 255, 255, .75); white-space: nowrap; }
.chart-empty { height: 100%; display: grid; place-items: center; color: #64748b; }
.chart-title-with-control { height: auto; min-height: 24px; }
.chart-title-with-control > span { color: #10243f; font-size: 14px; font-weight: 900; }
.chart-title-with-control small { margin-left: 6px; color: #475569; font-size: 12px; font-weight: 600; }
.keyword-chart { height: 308px; margin-block: auto; }
.keyword-platform-chart { height: 308px; margin-block: auto; }
.keyword-momentum-card { min-height: 334px; margin-top: 12px; display: flex; flex-direction: column; }
.momentum-grid { min-height: 235px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.momentum-panel { min-width: 0; padding: 10px 12px; border: 1px solid #dbe5f0; border-radius: 6px; background: #fbfdff; }
.momentum-panel.emerging { border-top: 3px solid #dc2626; }
.momentum-panel.declining { border-top: 3px solid #0891b2; }
.momentum-panel h3 { margin: 0 0 7px; color: #172554; font-size: 14px; font-weight: 900; }
.momentum-panel h3 small { margin-left: 5px; color: #475569; font-size: 11px; font-weight: 700; }
.momentum-list { display: grid; gap: 2px; }
.momentum-row { width: 100%; min-width: 0; min-height: 20px; padding: 3px 4px; display: grid; grid-template-columns: 19px minmax(48px, 1fr) 43px 46px 48px; align-items: center; gap: 5px; border: 0; border-radius: 4px; background: transparent; color: #334155; cursor: pointer; text-align: left; }
.momentum-row:hover { background: #eff6ff; }
.momentum-row > b { overflow: hidden; color: #10243f; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.momentum-rank { color: #64748b; font-size: 11px; font-weight: 800; text-align: center; }
.category-tag { width: fit-content; max-width: 43px; padding: 1px 4px; overflow: hidden; border-radius: 3px; background: color-mix(in srgb, var(--tag-color) 12%, white); color: var(--tag-color); font-size: 10px; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.momentum-count { color: #475569; font-size: 11px; font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
.momentum-change { font-size: 11px; font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
.momentum-change.up { color: #dc2626; }
.momentum-change.down { color: #0284c7; }
.momentum-empty { min-height: 184px; display: grid; place-items: center; margin: 0; color: #64748b; font-size: 12px; }
:global(.keyword-momentum-tooltip) { max-width: 180px; }
:global(.keyword-momentum-tooltip .sparkline-tooltip) { display: grid; gap: 3px; color: #334155; font-size: 12px; }
:global(.keyword-momentum-tooltip svg) { width: 120px; height: 30px; overflow: visible; }
:global(.keyword-momentum-tooltip small) { color: #64748b; font-variant-numeric: tabular-nums; }
.keyword-quadrant-card { grid-column: 1 / -1; min-height: 510px; display: flex; flex-direction: column; }
.keyword-quadrant-chart { height: 442px; margin-block: auto; }
.keyword-table-card { grid-column: 1 / -1; min-height: 430px; }
.keyword-table-tools { display: grid; grid-template-columns: minmax(0, 1.15fr) .85fr .95fr; gap: 8px; margin-bottom: 10px; }
.keyword-observation-table th:nth-child(1) { width: 20%; }
.keyword-observation-table th:nth-child(2), .keyword-observation-table th:nth-child(3) { width: 13%; }
.keyword-observation-table th:nth-child(4) { width: 18%; }
.keyword-observation-table th:nth-child(5) { width: 36%; }
.keyword-observation-table td:first-child b { display: block; color: #0f766e; }
.keyword-observation-table td:first-child small { color: #475569; font-size: 10px; font-weight: 600; }
.keyword-observation-table td:last-child { white-space: normal; }
.conclusion-cell { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.heat-heading { display: inline-flex; align-items: center; gap: 4px; cursor: help; }
.heat-heading b { width: 14px; height: 14px; display: inline-grid; place-items: center; border-radius: 50%; background: #dbeafe; color: #1d4ed8; font-size: 10px; }
@media (max-width: 1100px) {
  .keyword-analysis-page .third > .analysis-card { min-height: 360px; }
  .keyword-chart, .keyword-platform-chart { height: 292px; }
  .momentum-grid { grid-template-columns: 1fr; }
  .keyword-momentum-card { min-height: 550px; }
  .keyword-quadrant-card, .keyword-table-card { min-height: 400px; }
  .keyword-quadrant-chart { height: 380px; }
  .keyword-table-tools { grid-template-columns: 1fr; }
}
</style>
