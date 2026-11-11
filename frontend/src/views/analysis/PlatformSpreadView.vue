<template>
  <!-- 扩散关系、首达时间与热度比较共享数据快照，结论可打开原文证据。 -->
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>平台扩散</span>
        <h2>最近7天跨平台类别热度扩散分析</h2>
        <p>以数据中最新日期为基准，观察最近 7 天同一新闻类别在不同平台的热度先后响应。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <!-- 点击“刷新数据库”时执行 refreshData。 -->
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <div class="grid two">
      <div class="analysis-card">
        <div class="card-title">
          <span>跨平台扩散网络</span>
          <span>{{ categoryPropagationLinks.length ? '类别热度先后关系，不代表单条新闻转载' : '暂无达到阈值的类别关系' }}</span>
          <!-- 点击“恢复全部”时执行 clearHiddenPlatforms。 -->
          <el-button v-if="hiddenPlatforms.length" link type="primary" size="small" @click="clearHiddenPlatforms">恢复全部</el-button>
        </div>
        <!-- 将 graphOption 交给图表组件绘制，图表点击时调用 togglePlatform。 -->
        <ChartBox :option="graphOption" @chartClick="togglePlatform" />
      </div>
      <div class="analysis-card">
        <div class="card-title">平台热度漏斗 <span>最近 7 天总体热度贡献排序</span></div>
        <!-- 将 funnelOption 交给图表组件绘制。 -->
        <ChartBox :option="funnelOption" />
      </div>
    </div>

    <div class="grid two equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title daily-title">近7天每日类别响应 <span>{{ dailyWindow }}</span></div>
        <div class="daily-metric">当日首达类别 / 时分 / 较当日最早平台的分钟差</div>
        <div class="daily-table-wrap">
          <table class="table daily-responses" aria-label="近7天每日类别响应">
            <thead><tr><th scope="col">日期</th><th v-for="platform in spreadPlatforms" :key="platform" scope="col">{{ platformName(platform) }}</th></tr></thead>
            <tbody>
              <tr v-for="day in dailyResponses" :key="day.date">
                <th scope="row"><time :datetime="day.date">{{ day.date.slice(5) }}</time></th>
                <td v-for="row in day.platforms" :key="row.platform">
                  <button v-if="row.status === 'activated'" class="daily-record" :class="{ earliest: row.delay_minutes === 0 }"
                    :aria-label="`${day.date} ${platformName(row.platform)} ${dailyCategories(row)} 查看依据`"
                    :title="`${day.date} ${platformName(row.platform)} · 查看当天依据`" @click="openDailyEvidence(day.date, row)">
                    <b>{{ dailyCategories(row) }}</b>
                    <span class="daily-time">{{ formatTime(row.first_hot_time).slice(11, 16) }} <el-icon><View /></el-icon></span>
                    <span class="daily-delay">{{ row.delay_minutes === 0 ? '当日最早' : `+${row.delay_minutes} 分钟` }}</span>
                  </button>
                  <span v-else class="daily-missing">{{ row.status === 'below_threshold' ? '未达阈值' : '无数据' }}</span>
                </td>
              </tr>
              <tr v-if="!dailyResponses.length"><td colspan="6" class="daily-empty">暂无每日响应数据</td></tr>
            </tbody>
          </table>
        </div>
      </div>
      <div class="analysis-card">
        <div class="card-title">类别扩散结论摘要 <span>统计推断</span></div>
        <div class="insight-list">
          <div><b>最早达到阈值</b><span>{{ leadText }}</span><el-button link type="primary" @click="evidenceMode = 'lead'">查看依据</el-button></div>
          <div><b>累计热度最高</b><span>{{ platformName(hottestPlatform?.platform) }}，累计 {{ numberText(hottestPlatform?.hot_score) }}</span><el-button link type="primary" @click="evidenceMode = 'heat'">查看依据</el-button></div>
          <div><b>各平台首达时间跨度</b><span>{{ timeline.length ? `${lastTimeline?.delay_minutes || 0} 分钟，${timeline.length} 个平台` : '暂无达阈值平台' }}</span><el-button link type="primary" @click="evidenceMode = 'span'">查看依据</el-button></div>
          <div><b>同类别先后关系</b><span>{{ categorySummary?.relation_count || 0 }} 组统计关系，不代表新闻转载。</span><el-button link type="primary" @click="evidenceMode = 'relations'">查看依据</el-button></div>
        </div>
      </div>
    </div>
    <!-- 传入当前证据模式、事件编号和快照，关闭时清空模式以隐藏弹框。 -->
    <PropagationEvidenceDialog :mode="evidenceMode" :dashboard="data" :daily-selection="dailySelection" @close="evidenceMode = ''" />
  </div>
</template>

<script setup lang="ts">
import { numberText, formatTime } from '../../utils/analysisFormat'
import { View } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../../components/common/ChartBox.vue'
import PropagationEvidenceDialog from '../../components/analysis/PropagationEvidenceDialog.vue'
import { clearGetCache, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'

// 保存查询接口使用的统一事件编号。
// 保存后端返回的整份仪表盘数据，供各图表共享。
const data = ref<any>({})
// 控制刷新按钮的加载状态，提示请求尚未结束。
const refreshing = ref(false)
// 保存要查看的结论类型；空字符串表示关闭证据弹框。
const evidenceMode = ref('')
const dailySelection = ref<{ date: string; platform: string; category: string; time: string }>()
const dailyResponses = computed<any[]>(() => data.value.categoryDailyResponses || [])
const dailyWindow = computed(() => dailyResponses.value.length
  ? `${dailyResponses.value[0].date} 至 ${dailyResponses.value[dailyResponses.value.length - 1].date}`
  : '最近 7 天')
function dailyCategories(row: any) { return (row.lead_categories || [row.lead_category]).join('、') }
function openDailyEvidence(date: string, row: any) {
  dailySelection.value = { date, platform: row.platform, category: row.lead_category, time: String(row.first_hot_time) }
  evidenceMode.value = 'daily'
}
// 限定扩散分析参与展示的五个新闻平台。
const spreadPlatforms = ['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER']
// 记录用户隐藏了关联线的平台编号。
const hiddenPlatforms = ref<string[]>([])
// 使用统一中文事件名称作为页面标签。
const currentEventName = ANALYSIS_EVENT_NAME
// 复制时间线后筛选支持的平台，再按累计热度降序排列，不修改接口原数组。
const platformTimeline = computed<any[]>(() => [...(data.value.platformTimeline || [])]
  .filter(item => spreadPlatforms.includes(String(item.platform || '')))
  .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)))
// 优先使用近期窗口数据；旧响应缺少该字段时兼容全量平台时间线。
const recentPlatformTimeline = computed<any[]>(() => [...(data.value.recentPlatformTimeline || platformTimeline.value)]
  .filter(item => spreadPlatforms.includes(String(item.platform || '')))
  .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)))
// 将类别首达时间线按延迟从小到大排列，最早达到阈值的平台排在前面。
const timeline = computed<any[]>(() => [...(data.value.categoryPropagationTimeline || [])]
  .sort((a, b) => Number(a.delay_minutes || 0) - Number(b.delay_minutes || 0)))
// 读取类别传播摘要，数据未加载时用空对象防止访问报错。
const categorySummary = computed<any>(() => data.value.categoryPropagationSummary || {})
// 拼接最早达到阈值的平台名称；同一分钟有多个平台时注明并列。
const leadText = computed(() => {
  // 优先使用并列平台列表，兼容只有一个领先平台字段的响应。
  const platforms = categorySummary.value.lead_platforms || (categorySummary.value.lead_platform ? [categorySummary.value.lead_platform] : [])
  return platforms.length ? `${platforms.map(platformName).join('、')}${platforms.length > 1 ? '（同一分钟）' : `，${categorySummary.value.lead_category}`}` : '暂无达阈值数据'
})
// 排序后的首项就是近期累计热度最高的平台。
const hottestPlatform = computed(() => recentPlatformTimeline.value[0])
// 时间线末项用于展示各平台首达时间的最大延迟。
const lastTimeline = computed(() => timeline.value[timeline.value.length - 1])
// 取最高热度作为节点大小的归一化分母，最小为一以避免除零。
const maxHeat = computed(() => Math.max(1, ...recentPlatformTimeline.value.map(item => Number(item.hot_score || 0))))
// 从快照中提取平台间的类别先后关系，缺失时不绘制连线。
const categoryPropagationLinks = computed<any[]>(() => data.value.categoryPropagationLinks || [])

// 边表示同类别达到阈值的观测先后关系，不据此推断转载或因果传播。
const graphOption = computed(() => ({
  tooltip: {
    formatter: (params: any) => {
      // 鼠标指向关系边时，显示来源、目标、类别和平均时间差。
      if (params.dataType === 'edge') {
        return `${params.data.source} → ${params.data.target}<br/>类别：${params.data.category_label || '-'}<br/>关系组数：${params.data.link_count || 0}<br/>平均时间差：${params.data.average_lag_minutes || 0} 分钟`
      }
      return `${params.name}<br/>平台总热度：${numberText(params.data?.value || 0)}<br/><span style="color:#64748b">点击节点可隐藏或恢复关联线</span>`
    }
  },
  series: [{
    type: 'graph',
    layout: 'force',
    roam: true,
    force: { repulsion: 220, edgeLength: 130 },
    label: { show: true, fontWeight: 700 },
    // 将每个平台转换为图节点，热度越高则节点越大。
    data: recentPlatformTimeline.value.map(item => ({
      name: platformName(item.platform),
      platform: item.platform,
      value: item.hot_score,
      symbolSize: 34 + Number(item.hot_score || 0) / maxHeat.value * 42,
      itemStyle: hiddenPlatforms.value.includes(item.platform) ? { color: '#cbd5e1', borderColor: '#94a3b8', borderWidth: 1 } : undefined,
      label: { color: hiddenPlatforms.value.includes(item.platform) ? '#94a3b8' : '#172033' }
    })),
    // 先排除用户隐藏平台的关系，再转换为图中的起点、终点和权重。
    links: categoryPropagationLinks.value
      .filter(item => !hiddenPlatforms.value.includes(item.source_platform) && !hiddenPlatforms.value.includes(item.target_platform))
      .map(item => ({
      source: platformName(item.source_platform),
      target: platformName(item.target_platform),
      value: Number(item.link_count || 0),
      link_count: Number(item.link_count || 0),
      category_label: item.category_label,
      average_lag_minutes: item.average_lag_minutes,
      lineStyle: { width: Math.min(7, 1 + Number(item.link_count || 0)), curveness: 0.15 }
      }))
  }]
}))

// 将各平台近期累计热度映射为漏斗数据，并按数值从大到小显示。
const funnelOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'funnel', sort: 'descending', data: recentPlatformTimeline.value.map(item => ({ name: platformName(item.platform), value: Number(item.hot_score || 0) })) }]
}))

async function load() {
  // 查询当前事件的仪表盘接口，并用返回结果替换旧快照。
  data.value = await getData('/analysis/platform-spread')
}
// 刷新期间显示加载状态，请求成功或失败后均恢复按钮。
async function refreshData() {
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
// 仅响应节点点击，隐藏关联边但保留节点，便于再次点击恢复关系。
function togglePlatform(params: any) {
  if (params?.dataType !== 'node' || !params.data?.platform) {
    return
  }
  // 将节点的平台编号转为字符串，作为隐藏列表的统一键。
  const platform = String(params.data.platform)
  // 已隐藏则移除编号，未隐藏则追加编号，实现再次点击切换。
  hiddenPlatforms.value = hiddenPlatforms.value.includes(platform)
    ? hiddenPlatforms.value.filter(item => item !== platform)
    : [...hiddenPlatforms.value, platform]
}
// 清空隐藏列表，使所有平台的关系线重新出现。
function clearHiddenPlatforms() { hiddenPlatforms.value = [] }
// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || '其他平台' }

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取平台扩散失败'))
})

useDatabaseAutoRefresh(load)
</script>

<style scoped>
.daily-title { flex-wrap: wrap; gap: 6px 12px; }
.daily-metric { font-size: 12px; color: #64748b; margin: -2px 0 12px; }
.daily-table-wrap { overflow-x: auto; max-width: 100%; }
.daily-responses { table-layout: fixed; min-width: 570px; width: 100%; }
.daily-responses th, .daily-responses td { text-align: center; padding: 5px 3px; vertical-align: middle; white-space: normal; }
.daily-responses thead th { height: 36px; white-space: normal; font-size: 12px; }
.daily-responses th:first-child { width: 54px; }
.daily-responses tbody th { color: #475569; font-size: 12px; font-weight: 600; }
.daily-record { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 3px; width: 100%; min-height: 72px; padding: 5px 2px; background: transparent; border: 1px solid transparent; border-radius: 4px; color: #334155; font: inherit; font-size: 12px; cursor: pointer; }
.daily-record b { font-size: 13px; overflow-wrap: anywhere; }
.daily-record:hover, .daily-record:focus-visible { background: #eff6ff; border-color: #93c5fd; outline: none; }
.daily-time { display: flex; align-items: center; gap: 4px; font-variant-numeric: tabular-nums; }
.daily-time .el-icon { color: #64748b; }
.daily-delay { color: #64748b; font-size: 11px; }
.earliest .daily-delay { color: #047857; }
.daily-missing { font-size: 12px; color: #94a3b8; }
.daily-empty { height: 150px; color: #94a3b8; }
.insight-list > div { flex-wrap: wrap; gap: 8px; }
.insight-list > div > span { flex: 1; min-width: 130px; }
</style>
