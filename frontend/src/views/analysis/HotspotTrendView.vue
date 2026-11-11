<template>
  <!-- 平台与日期范围联动热度趋势、类别比较及高热内容列表。 -->
  <div class="analysis-front trend-page">
    <div class="analysis-page-head">
      <div>
        <span>热点趋势</span>
        <h2>平台互动热度趋势</h2>
        <p>各平台按自身可用互动字段计算内容热度，跨平台只比较平台内相对位置。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <!-- 点击“刷新数据库”时执行 refreshData。 -->
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <div class="trend-filters">
      <!-- 切换总体或单个平台后，下方计算属性自动重算对应统计范围。 -->
      <el-radio-group v-model="selectedPlatform" class="platform-switch">
        <el-radio-button label="ALL">总体对比</el-radio-button>
        <el-radio-button v-for="platform in platforms" :key="platform" :label="platform">{{ platformName(platform) }}</el-radio-button>
      </el-radio-group>
      <!-- 将当前控件的输入或选择同步到 dateRange，供查询或提交时读取。 -->
      <el-date-picker
        v-model="dateRange"
        class="date-range"
        type="daterange"
        value-format="YYYY-MM-DD"
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        :clearable="false"
        @change="loadTrendContentRank"
      />
    </div>

    <!-- 把统计指标传入共用指标组件，循环显示名称、数值与说明。 -->
    <MetricGrid :items="metrics" />

    <div class="calculation-note">
      <strong>{{ selectedPlatform === 'ALL' ? '总体热度趋势' : `${platformName(selectedPlatform)}热度趋势` }}</strong>
      <span>{{ selectedPlatform === 'ALL' ? '总体线为当天有数据平台的等权平均，不按平台内容量累加。' : '类别热度同时参考新闻互动热度与该类别在平台当日内容中的占比。' }}</span>
    </div>

    <div class="grid two trend-chart-grid">
      <div class="analysis-card trend-chart-card">
        <div class="card-title">
          <span class="title-main">{{ selectedPlatform === 'ALL' ? '五平台互动热度趋势' : '平台内互动热度趋势' }}</span>
          <span>日均单内容热度指数 0-100</span>
        </div>
        <!-- 将 heatTrendOption 交给图表组件绘制。 -->
        <ChartBox :option="heatTrendOption" />
      </div>
      <div class="analysis-card trend-chart-card">
        <div class="card-title">
          <span class="title-main">{{ selectedPlatform === 'ALL' ? '总体类别热度趋势' : '平台内类别热度趋势' }}</span>
          <span>{{ selectedPlatform === 'ALL' ? '五平台等权平均 · 7 类状态' : '互动热度 × 内容占比' }}</span>
        </div>
        <!-- 将 categoryChartOption 交给图表组件绘制。 -->
        <ChartBox :option="categoryChartOption" />
        <!-- 仅当 selectedPlatform === 'ALL' 成立时显示此处内容，条件变化时自动更新。 -->
        <div v-if="selectedPlatform === 'ALL'" class="radar-top-list">
          <span v-for="item in radarTopCategories" :key="item.platform" class="radar-top-item">
            <i :style="{ background: platformColor(item.platform) }"></i>{{ platformName(item.platform) }}：<b>{{ item.category }}</b>
          </span>
        </div>
        <div v-else class="category-status-list">
          <span v-for="item in categoryStatusSummary" :key="item.status" :class="['status-chip', `status-${item.status}`]">
            {{ statusLabel(item.status) }} {{ item.count }} 类
          </span>
        </div>
      </div>
    </div>

    <div class="grid two trend-bottom-grid">
      <div class="analysis-card trend-chart-card trend-summary-card">
        <div class="card-title">
          <span class="title-main">平台热度峰值</span>
          <span>选定时段内最高日均热度</span>
        </div>
        <!-- 将 platformPeakOption 交给图表组件绘制。 -->
        <ChartBox :option="platformPeakOption" />
      </div>
      <div class="analysis-card table-card hot-event-card">
        <div class="card-title">
          <span class="title-main">选定时段热门事件前三名</span>
          <span>{{ selectedPlatform === 'ALL' ? '各平台分别取前 3' : `${platformName(selectedPlatform)}前 3` }}</span>
        </div>
        <div class="hot-event-groups">
          <section v-for="group in hotEventGroups" :key="group.platform" class="hot-event-group">
            <div class="hot-event-platform"><span class="platform-dot" :style="{ background: platformColor(group.platform) }"></span>{{ platformName(group.platform) }}</div>
            <ol v-if="group.items.length">
              <!-- 根据 (item, index) in group.items 循环生成条目，模板中的字段读取当前条目数据。 -->
              <li v-for="(item, index) in group.items" :key="`${group.platform}-${item.content_id || item.title || index}`">
                <span class="event-rank">{{ index + 1 }}</span>
                <span class="event-title" :title="item.title">{{ item.title }}</span>
                <b>{{ fixed(item.heat) }}</b>
              </li>
            </ol>
            <div v-else class="hot-event-empty">当前时段暂无可用事件</div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { fixed } from '../../utils/analysisFormat'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../../components/common/ChartBox.vue'
import MetricGrid from '../../components/common/MetricGrid.vue'
import { clearGetCache, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'

// 为新闻平台定义固定颜色，使不同图表中的同一平台保持一致。
const platformColors: Record<string, string> = {
  TENCENT_NEWS: '#2563eb', NETEASE_NEWS: '#172033', SOHU_NEWS: '#d97706', SINA_NEWS: '#7c3aed', THE_PAPER: '#0f766e'
}
// 保存仪表盘完整响应，作为后续指标和图表的统一数据源。
const data = ref<any>({})
// 保存所选日期范围内的内容热榜，供各平台高热内容展示。
const trendContentRows = ref<any[]>([])
// 控制刷新按钮的加载状态，请求结束后解除。
const refreshing = ref(false)
// 保存当前平台选择，ALL 表示全部平台。
const selectedPlatform = ref('ALL')
// 保存统计起止日期，热度图和内容热榜使用相同范围。
const dateRange = ref<string[]>([])

// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) { return ({ TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' } as Record<string, string>)[value] || '其他平台' }
// 读取平台的固定图表颜色，未找到时采用备用颜色。
function platformColor(value: string) { return platformColors[value] || '#64748b' }
// 固定七个标准新闻类别及其显示顺序。
const CATEGORY_LABELS = ['财经', '政治', '科技', '体育', '文娱', '社会', '综合']
// 固定各类别颜色，使图例与各图表的标识一致。
const CATEGORY_COLORS: Record<string, string> = { 财经: '#2563eb', 政治: '#7c3aed', 科技: '#0891b2', 体育: '#16a34a', 文娱: '#d97706', 社会: '#dc2626', 综合: '#64748b' }
// 为升温、降温和平稳状态指定显示颜色。
const categoryStatusColors: Record<string, string> = { warming: '#16a34a', cooling: '#dc2626', stable: '#64748b' }
// 将类别状态编码转换为中文名称。
const categoryStatusLabels: Record<string, string> = { warming: '升温', cooling: '降温', stable: '平稳' }
// 统一后端类别编码与中文别名，过滤缺失值和无业务意义的类别占位。
function cleanCategory(value: unknown) {
  // 将输入转换为去除两侧空白的文本，先排除空值及占位编码。
  const text = String(value ?? '').trim()
  if (!text || /^(null|undefined|none|n\/a|nan|unknown|未知|其他|未分类|\d+)$/i.test(text)) return ''
  // 将英文类别和常见中文别名归并到标准七类名称。
  const aliases: Record<string, string> = {
    finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合',
    娱乐: '文娱', 影视: '文娱', 音乐: '文娱', 社会新闻: '社会', 民生: '社会', 综合类: '综合', 新闻: '综合'
  }
  return aliases[text.toLowerCase()] || aliases[text] || text
}
// 将类别变化状态转换为升温、降温或平稳。
function statusLabel(value: string) { return categoryStatusLabels[value] || '平稳' }
// 从后端时间中提取日期，缺失时返回空字符串。
function dateText(value: any) { return value ? String(value).replace('T', ' ').slice(0, 10) : '' }
// 在指定日期上增减天数，并返回年月日格式的文本。
function shiftDate(value: string, days: number) {
  const date = new Date(`${value}T00:00:00`)
  date.setDate(date.getDate() + days)
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}
// 将计数转换为中文数字格式或万单位，减少大数值显示长度。
function countText(value: any) { return Number(value || 0).toLocaleString('zh-CN') }

// 仅保留已支持新闻平台且具有日期的数据，供所有热度筛选共用。
const heatRows = computed<any[]>(() => (data.value.platformDailyHeat || [])
  .map((row: any) => ({ ...row, platform: String(row.platform || ''), day: dateText(row.time_bucket) }))
  .filter((row: any) => platformColors[row.platform] && row.day))
// 定义可展示或选择的平台集合，供循环和筛选使用。
const platforms = computed(() => [...new Set(heatRows.value.map(row => row.platform))].sort())
// 提取日期并去重排序，确定时间筛选的可用边界。
const availableDates = computed(() => [...new Set(heatRows.value.map(row => row.day))].sort())
// 只保留符合当前平台和起止日期的日热度记录。
const scopedHeatRows = computed(() => {
  // 两个日期完整时按闭区间过滤，未选范围时暂不限定日期。
  const [start, end] = dateRange.value
  return heatRows.value.filter(row => (selectedPlatform.value === 'ALL' || row.platform === selectedPlatform.value)
    && (!start || !end || (row.day >= start && row.day <= end)))
})
// 从当前范围提取日期，作为热度折线横轴。
const heatDates = computed(() => [...new Set(scopedHeatRows.value.map(row => row.day))].sort())
// 全部模式显示有效平台列表，否则只显示选中的平台。
const visiblePlatforms = computed(() => selectedPlatform.value === 'ALL' ? platforms.value : [selectedPlatform.value])
// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME

// 在选定时段内计算平台日均热度、单日峰值及内容量，而非使用全量排名。
const summaryRows = computed(() => visiblePlatforms.value.map(platform => {
  // 每次仅提取一个平台的数据，没有记录的平台不进入排名。
  const rows = scopedHeatRows.value.filter(row => row.platform === platform)
  if (!rows.length) return null
  // 平台时段日均值对每天等权平均；峰值则取单日指数最高的一条。
  const average = rows.reduce((sum, row) => sum + Number(row.average_heat_index || 0), 0) / rows.length
  const peak = [...rows].sort((a, b) => Number(b.average_heat_index || 0) - Number(a.average_heat_index || 0))[0]
  return {
    platform,
    average_heat_index: average,
    peak_day: peak.day,
    peak_heat_index: Number(peak.average_heat_index || 0),
    content_count: rows.reduce((sum, row) => sum + Number(row.content_count || 0), 0),
    high_heat_content_count: rows.reduce((sum, row) => sum + Number(row.high_heat_content_count || 0), 0),
  }
}).filter(Boolean).sort((a: any, b: any) => b.average_heat_index - a.average_heat_index) as any[])
// 按平台筛选所选时段文章，按热度降序取前三条。
const hotEventGroups = computed(() => visiblePlatforms.value.map(platform => {
  // 用发布日期限定文章范围，整理展示标题和热度后取最高三条。
  const [start, end] = dateRange.value
  const items = trendContentRows.value
    .filter((row: any) => String(row.platform || '') === platform
      && (!start || !end || (dateText(row.publish_time) >= start && dateText(row.publish_time) <= end)))
    .map((row: any) => ({
      ...row,
      title: String(row.title || row.clean_text || '未命名事件').trim(),
      heat: Number(row.platform_heat_index ?? row.hot_score ?? 0)
    }))
    .sort((a: any, b: any) => b.heat - a.heat)
    .slice(0, 3)
  return { platform, items }
}))

// 对每天有数据的平台热度取算术平均，生成总体热度序列。
const overallSeries = computed(() => heatDates.value.map(day => {
  // 找到当日参与统计的平台，缺少当日记录时返回空值而不是补零。
  const rows = scopedHeatRows.value.filter(row => row.day === day)
  if (!rows.length) return null
  return rows.reduce((sum, row) => sum + Number(row.average_heat_index || 0), 0) / rows.length
}))
// 将统计结果整理为指标组件所需的名称、数值与说明。
const metrics = computed(() => {
  // 分别找时段日均最高与单日峰值最高的平台，并累计选定范围内容量。
  const strongest = summaryRows.value[0]
  const peak = [...summaryRows.value].sort((a, b) => b.peak_heat_index - a.peak_heat_index)[0]
  const total = summaryRows.value.reduce((sum, row) => sum + row.content_count, 0)
  return [
    { label: '时段日均热度最高', value: strongest ? platformName(strongest.platform) : '-', sub: `日均指数 ${fixed(strongest?.average_heat_index)}` },
    { label: '单日热度峰值最高', value: peak ? platformName(peak.platform) : '-', sub: `${peak?.peak_day || '-'} / ${fixed(peak?.peak_heat_index)}` },
    { label: '选定时段内容量', value: countText(total), sub: `${dateRange.value[0] || '-'} 至 ${dateRange.value[1] || '-'}` },
    { label: '参与平台数', value: summaryRows.value.length, sub: '按平台内相对热度指数计算' }
  ]
})

// 生成平台热度折线及总体趋势的坐标轴、提示和系列数据。
const heatTrendOption = computed(() => ({
  color: visiblePlatforms.value.map(platformColor),
  // 悬停日期时汇总当天各平台指数，并显示实际参与的平台数。
  tooltip: {
    trigger: 'axis',
    formatter: (params: any[]) => {
      const day = params[0]?.axisValue || ''
      const lines = params.filter(item => item.value !== null && item.value !== undefined).map(item => `${item.marker}${item.seriesName}: <b>${fixed(item.value)}</b>`)
      const active = scopedHeatRows.value.filter(row => row.day === day).length
      return `${day}<br/>${lines.join('<br/>')}<br/><span style="color:#64748b">参与平台：${active}</span>`
    }
  },
  // 图例按当前可见平台生成，只有全部平台模式额外显示总体热度。
  legend: { top: 0, type: 'scroll', data: [...visiblePlatforms.value.map(platformName), ...(selectedPlatform.value === 'ALL' ? ['总体热度'] : [])] },
  grid: { left: 54, right: 20, top: 44, bottom: 36 },
  xAxis: { type: 'category', boundaryGap: false, data: heatDates.value, axisLabel: { color: '#64748b', hideOverlap: true } },
  yAxis: { type: 'value', alignTicks: false, min: 0, max: 100, name: '指数', nameTextStyle: { color: '#64748b' }, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
  series: [
    // 每个平台生成一条折线，同一天的数值从当前筛选后的日热度记录读取。
    ...visiblePlatforms.value.map(platform => ({
      name: platformName(platform), type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, lineStyle: { width: 2.5 },
      data: heatDates.value.map(day => Number(scopedHeatRows.value.find(row => row.platform === platform && row.day === day)?.average_heat_index ?? 0))
    })),
    // 仅全部平台模式追加总体序列，已有总体均值保留两位小数，缺失日保留空值。
    ...(selectedPlatform.value === 'ALL' ? [{ name: '总体热度', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, itemStyle: { color: '#dc2626' }, lineStyle: { width: 3, type: 'dashed', color: '#dc2626' }, data: overallSeries.value.map(value => value === null ? null : Number(value.toFixed(2))) }] : [])
  ]
}))

// 使用平台时段日均热度绘制柱图，提示中补充峰值日期。
const platformPeakOption = computed(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, formatter: (params: any[]) => {
    const row = summaryRows.value.find(item => platformName(item.platform) === params[0]?.name)
    return `${params[0]?.name || ''}<br/>日均热度：<b>${fixed(params[0]?.value)}</b><br/>峰值日期：${row?.peak_day || '-'}<br/>峰值：<b>${fixed(row?.peak_heat_index)}</b>`
  }},
  grid: { left: 88, right: 32, top: 20, bottom: 24 },
  xAxis: { type: 'value', alignTicks: false, min: 0, max: 100, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
  yAxis: { type: 'category', inverse: true, data: summaryRows.value.map(row => platformName(row.platform)), axisLabel: { color: '#475569' } },
  series: [{ type: 'bar', barMaxWidth: 24, label: { show: true, position: 'right', color: '#475569', formatter: (params: any) => fixed(params.value) }, data: summaryRows.value.map(row => ({ value: Number(row.average_heat_index.toFixed(2)), itemStyle: { color: platformColor(row.platform) } })) }]
}))

// 统一类别和日期字段，移除不支持的平台和非标准类别。
const categoryRows = computed<any[]>(() => (data.value.platformCategoryHeat || [])
  .map((row: any) => ({ ...row, platform: String(row.platform || ''), category: cleanCategory(row.category), day: dateText(row.time_bucket) }))
  .filter((row: any) => platformColors[row.platform] && row.day && CATEGORY_LABELS.includes(row.category)))
// 将平台与日期筛选同时应用到类别统计。
const scopedCategoryRows = computed(() => {
  const [start, end] = dateRange.value
  return categoryRows.value.filter(row => (selectedPlatform.value === 'ALL' || row.platform === selectedPlatform.value)
    && (!start || !end || (row.day >= start && row.day <= end)))
})
// 全部平台沿用热度日期，单平台采用本平台类别数据日期。
const categoryDates = computed(() => selectedPlatform.value === 'ALL'
  ? heatDates.value
  : [...new Set(scopedCategoryRows.value.map(row => row.day))].sort())
// 类别指数先在平台内按内容量加权，跨平台时再等权平均；前后半段相差五分判定升降温。
function categorySeries(category: string) {
  // 按类别取出记录，再逐日计算该类别的指数。
  const rows = scopedCategoryRows.value.filter(row => row.category === category)
  const values = categoryDates.value.map(day => {
    const dayRows = rows.filter(row => row.day === day)
    if (selectedPlatform.value === 'ALL') {
      // 全部平台模式以当日有热度记录的平台作为参与集合。
      const activePlatforms = new Set(heatRows.value.filter(row => row.day === day).map(row => row.platform))
      if (!activePlatforms.size) return null
      // 每个平台内部先按文章数量加权，缺少该类别时使用零。
      const platformScores = [...activePlatforms].map(platform => {
        const platformRows = dayRows.filter(row => row.platform === platform)
        const weight = platformRows.reduce((sum, row) => sum + Number(row.content_count || 0), 0)
        return weight ? platformRows.reduce((sum, row) => sum + Number(row.composite_attention_index || 0) * Number(row.content_count || 0), 0) / weight : 0
      })
      // 对各平台结果等权平均，避免文章数量较多的平台直接主导总体类别值。
      return platformScores.reduce((sum, value) => sum + value, 0) / platformScores.length
    }
    // 单平台没有当日记录时保留空值，图中不把缺失误画成低热度。
    if (!dayRows.length) return null
    const totalWeight = dayRows.reduce((sum, row) => sum + Number(row.content_count || 0), 0)
    return totalWeight ? dayRows.reduce((sum, row) => sum + Number(row.composite_attention_index || 0) * Number(row.content_count || 0), 0) / totalWeight : 0
  })
  // 排除缺失日期后把有效序列分成前后两段，各自计算平均值。
  const available = values.filter((value): value is number => value !== null)
  const split = Math.max(1, Math.floor(available.length / 2))
  const firstPeriod = available.slice(0, split)
  const lastPeriod = available.slice(split)
  const first = firstPeriod.length ? firstPeriod.reduce((sum, value) => sum + value, 0) / firstPeriod.length : 0
  const last = lastPeriod.length ? lastPeriod.reduce((sum, value) => sum + value, 0) / lastPeriod.length : first
  // 后段减前段得到指数变化，增减五分及以上才判为升温或降温。
  const change = last - first
  const status = change >= 5 ? 'warming' : change <= -5 ? 'cooling' : 'stable'
  return { category, values, first, last, change, status }
}
// 逐类别计算时间序列和升温、降温或平稳状态。
const categorySeriesRows = computed(() => CATEGORY_LABELS.map(categorySeries))
// 分别统计三种变化状态包含的类别数量。
const categoryStatusSummary = computed(() => ['warming', 'cooling', 'stable'].map(status => ({ status, count: categorySeriesRows.value.filter(row => row.status === status).length })))
// 把类别时序转换为折线图配置，缺失点不强制连线。
const categoryTrendOption = computed(() => ({
  color: CATEGORY_LABELS.map(category => CATEGORY_COLORS[category]),
  tooltip: { trigger: 'axis', formatter: (params: any[]) => {
    const day = params[0]?.axisValue || ''
    const lines = params.filter(item => item.value !== null && item.value !== undefined).map(item => {
      const row = categorySeriesRows.value.find(candidate => candidate.category === item.seriesName)
      return `${item.marker}${item.seriesName}: <b>${fixed(item.value)}</b> <span style="color:${categoryStatusColors[row?.status || 'stable']}">${statusLabel(row?.status || 'stable')}</span>`
    })
    return `${day}<br/>${lines.join('<br/>')}`
  }},
  legend: { top: 0, type: 'scroll', data: CATEGORY_LABELS },
  grid: { left: 54, right: 20, top: 44, bottom: 36 },
  xAxis: { type: 'category', boundaryGap: false, data: categoryDates.value, axisLabel: { color: '#64748b', hideOverlap: true } },
  yAxis: { type: 'value', alignTicks: false, min: 0, max: 100, name: '类别热度', nameTextStyle: { color: '#64748b' }, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
  series: categorySeriesRows.value.map(row => ({ name: row.category, type: 'line', connectNulls: false, smooth: true, symbol: 'circle', symbolSize: 5, lineStyle: { width: 2.2, color: CATEGORY_COLORS[row.category], type: row.status === 'stable' ? 'dashed' : 'solid' }, itemStyle: { color: CATEGORY_COLORS[row.category] }, data: row.values.map(value => value === null ? null : Number(value.toFixed(2))) }))
}))
// 雷达图按当前时段的内容量加权聚合类别指数，避免直接累加日均值。
function periodCategoryScore(platform: string, category: string) {
  const rows = scopedCategoryRows.value.filter(row => row.platform === platform && row.category === category)
  const weight = rows.reduce((sum, row) => sum + Number(row.content_count || 0), 0)
  return weight ? rows.reduce((sum, row) => sum + Number(row.composite_attention_index || 0) * Number(row.content_count || 0), 0) / weight : 0
}
// 比较每个平台的类别指数，找出时段得分最高的类别。
const radarTopCategories = computed(() => radarPlatforms.value.map(platform => {
  const scores = CATEGORY_LABELS.map(category => ({ category, value: periodCategoryScore(platform, category) }))
  const top = [...scores].sort((a, b) => b.value - a.value)[0]
  return { platform, category: top?.value ? top.category : '暂无数据', value: top?.value || 0 }
}))
// 读取有效平台列表，作为雷达图各维度。
const radarPlatforms = computed(() => platforms.value)
// 以平台为维度展示各类别的时段加权指数。
const categoryRadarOption = computed(() => ({
  color: CATEGORY_LABELS.map(category => CATEGORY_COLORS[category]),
  // 雷达图提示按维度下标把指数值与平台名称一一对应。
  tooltip: { trigger: 'item', formatter: (params: any) => {
    const values = (params.value || []).map((value: number, index: number) => `${radarPlatforms.value[index] ? platformName(radarPlatforms.value[index]) : ''}：${fixed(value)}`)
    return `${params.name}<br/>${values.join('<br/>')}`
  }},
  legend: { top: 0, type: 'scroll', data: CATEGORY_LABELS },
  radar: { center: ['50%', '56%'], radius: '63%', indicator: radarPlatforms.value.map(platform => ({ name: platformName(platform), max: 100 })), splitNumber: 4, axisName: { color: '#475569', fontSize: 12 }, splitLine: { lineStyle: { color: ['#dbe5f0', '#e5eaf1', '#edf2f7', '#f3f6fb'] } }, splitArea: { areaStyle: { color: ['#ffffff', '#f8fbff'] } }, axisLine: { lineStyle: { color: '#cbd5e1' } } },
  series: [{
    type: 'radar',
    symbol: 'circle',
    symbolSize: 5,
    // 每个类别形成一组雷达数据，数组各位置依次对应各新闻平台。
    data: CATEGORY_LABELS.map(category => ({
      name: category,
      value: radarPlatforms.value.map(platform => Number(periodCategoryScore(platform, category).toFixed(2))),
      lineStyle: { width: 2, color: CATEGORY_COLORS[category] },
      itemStyle: { color: CATEGORY_COLORS[category] },
      areaStyle: { color: CATEGORY_COLORS[category], opacity: 0.04 }
    }))
  }]
}))
// 全部平台时显示雷达图，单平台时切换为类别趋势图。
const categoryChartOption = computed(() => selectedPlatform.value === 'ALL' ? categoryRadarOption.value : categoryTrendOption.value)

async function load() {
  // 先取得日统计并初始化有效日期范围，再请求该范围的内容热榜。
  data.value = await getData('/analysis/hotspot-trend')
  setDefaultDateRange()
  await loadTrendContentRank()
}
// 内容排名单独按起止日期请求，使展示内容与图表的时间范围一致。
async function loadTrendContentRank() {
  const [start, end] = dateRange.value
  if (!start || !end) {
    trendContentRows.value = []
    return
  }
  trendContentRows.value = await getData(
    `/analysis/trend-content-rank?startDate=${encodeURIComponent(start)}&endDate=${encodeURIComponent(end)}`,
    true
  )
}
// 仅当原范围无效或超出可用日期时设置默认范围，保留用户有效选择。
function setDefaultDateRange() {
  // 没有任何可用日期时保持原值；否则读取最早和最晚日期边界。
  const dates = availableDates.value
  if (!dates.length) return
  const latest = dates[dates.length - 1]
  const earliest = dates[0]
  const selectedEnd = dateRange.value[1]
  if (dateRange.value.length !== 2 || !selectedEnd || selectedEnd < earliest || selectedEnd > latest) {
    const start = shiftDate(latest, -6)
    dateRange.value = [start < earliest ? earliest : start, latest]
  }
}
async function refreshData() {
  refreshing.value = true
  try { clearGetCache(); await load(); ElMessage.success('已读取最新数据库快照') }
  catch (error: any) { ElMessage.error(getErrorMessage(error, '读取热点趋势失败，请稍后重试。')) }
  finally { refreshing.value = false }
}

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取热点趋势失败'))
})
useDatabaseAutoRefresh(load)
</script>

<style scoped>
.trend-page { min-width: 0; padding-bottom: 28px; overflow-x: hidden; }
.trend-filters { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 14px 0 12px; }
.platform-switch { display: flex; flex-wrap: wrap; gap: 4px; }
.date-range { width: 255px; }
.calculation-note { display: flex; align-items: baseline; gap: 12px; margin: 12px 0; padding: 11px 14px; border-left: 4px solid #2563eb; background: #f7faff; color: #52627a; font-size: 13px; }
.calculation-note strong { color: #1e3a5f; font-size: 14px; white-space: nowrap; }
.trend-chart-grid, .trend-bottom-grid { margin-top: 12px; }
.trend-bottom-grid { grid-template-columns: minmax(280px, .78fr) minmax(0, 1.22fr); }
.trend-chart-card { min-width: 0; }
.trend-chart-card :deep(.chart) { min-height: 348px; width: 100%; }
.hot-event-card { min-width: 0; }
.hot-event-groups { max-height: 348px; overflow-y: auto; padding: 0 14px 14px; scrollbar-gutter: stable; }
.hot-event-group + .hot-event-group { margin-top: 12px; padding-top: 12px; border-top: 1px solid #e5eaf1; }
.hot-event-platform { display: flex; align-items: center; margin-bottom: 5px; color: #1e3a5f; font-size: 13px; font-weight: 800; }
.hot-event-group ol { display: grid; gap: 4px; margin: 0; padding: 0; list-style: none; }
.hot-event-group li { display: grid; grid-template-columns: 20px minmax(0, 1fr) 42px; align-items: center; gap: 6px; min-height: 28px; color: #52627a; font-size: 12px; }
.event-rank { width: 18px; height: 18px; display: grid; place-items: center; border-radius: 50%; background: #eef5ff; color: #205bc3; font-size: 11px; font-weight: 800; }
.event-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hot-event-group li b { color: #2563eb; text-align: right; }
.hot-event-empty { padding: 8px 0; color: #94a3b8; font-size: 12px; }
.card-title { align-items: flex-start; gap: 10px; }
.card-title .title-main { min-width: 0; color: #1e293b; }
.card-title > span:last-child { flex: 0 1 42%; text-align: right; color: #718096; }
.trend-table { width: 100%; table-layout: fixed; }
.trend-table th, .trend-table td { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.trend-table td:first-child { text-align: left; }
.entity-cell { font-weight: 700; }
.platform-dot { display: inline-block; width: 8px; height: 8px; margin-right: 7px; border-radius: 50%; }
.heat-value { color: #2563eb; }
.category-status-list { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 14px 14px; }
.radar-top-list { display: flex; flex-wrap: wrap; column-gap: 12px; row-gap: 8px; padding: 0 14px 14px; }
.radar-top-item { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; min-width: 116px; padding: 6px 10px; border: 1px solid #dce5f0; border-radius: 4px; background: #f8fbff; color: #52627a; font-size: 12px; white-space: nowrap; }
.radar-top-item i { width: 7px; height: 7px; border-radius: 50%; }
.radar-top-item b { color: #1e3a5f; }
.status-chip { display: inline-flex; align-items: center; padding: 4px 9px; border-radius: 4px; font-size: 12px; font-weight: 700; }
.status-warming { background: #ecfdf3; color: #15803d; }
.status-cooling { background: #fff1f2; color: #b91c1c; }
.status-stable { background: #f1f5f9; color: #475569; }
@media (max-width: 900px) { .trend-filters { align-items: flex-start; flex-direction: column; } .date-range { width: 100%; } .calculation-note { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
