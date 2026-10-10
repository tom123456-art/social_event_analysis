<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>平台扩散</span>
        <h2>最近7天跨平台类别热度扩散分析</h2>
        <p>以数据中最新日期为基准，观察最近 7 天同一新闻类别在不同平台的热度先后响应。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <div class="grid two">
      <div class="analysis-card">
        <div class="card-title">
          <span>跨平台扩散网络</span>
          <span>{{ categoryPropagationLinks.length ? '类别热度先后关系，不代表单条新闻转载' : '暂无达到阈值的类别关系' }}</span>
          <el-button v-if="hiddenPlatforms.length" link type="primary" size="small" @click="clearHiddenPlatforms">恢复全部</el-button>
        </div>
        <ChartBox :option="graphOption" @chartClick="togglePlatform" />
      </div>
      <div class="analysis-card">
        <div class="card-title">平台热度漏斗 <span>最近 7 天总体热度贡献排序</span></div>
        <ChartBox :option="funnelOption" />
      </div>
    </div>

    <div class="grid two equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">类别热度响应时间线 <span>{{ formatWindow(categorySummary) }} · 首次达到阈值</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>平台</th><th>首达类别</th><th>首次达阈值</th><th>时间差</th><th>类别数</th><th>峰值关注指数</th></tr></thead>
          <tbody>
            <tr v-for="row in timeline" :key="row.platform">
              <td>{{ platformName(row.platform) }}</td>
              <td><b>{{ row.lead_category || '-' }}</b></td>
              <td>{{ formatTime(row.first_hot_time) }}</td>
              <td>{{ row.delay_minutes }} 分钟</td>
              <td>{{ row.category_count || 0 }}</td>
              <td>{{ fixed(row.peak_attention) }}</td>
            </tr>
            <tr v-for="i in Math.max(0, 5 - timeline.length)" :key="`timeline-empty-${i}`"><td colspan="6"></td></tr>
          </tbody>
        </table>
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
    <PropagationEvidenceDialog :mode="evidenceMode" :event-id="eventId" :dashboard="data" @close="evidenceMode = ''" />
  </div>
</template>

<script setup lang="ts">
// 平台扩散页：比较不同平台的传播时间、热度和覆盖情况。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import PropagationEvidenceDialog from '../components/PropagationEvidenceDialog.vue'
import { clearGetCache, getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_ID, ANALYSIS_EVENT_NAME } from '../config/analysisDataset'

const eventId = ref(ANALYSIS_EVENT_ID)
const data = ref<any>({})
const refreshing = ref(false)
const evidenceMode = ref('')
const spreadPlatforms = ['TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER']
const hiddenPlatforms = ref<string[]>([])
const currentEventName = ANALYSIS_EVENT_NAME
const platformTimeline = computed<any[]>(() => [...(data.value.platformTimeline || [])]
  .filter(item => spreadPlatforms.includes(String(item.platform || '')))
  .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)))
const recentPlatformTimeline = computed<any[]>(() => [...(data.value.recentPlatformTimeline || platformTimeline.value)]
  .filter(item => spreadPlatforms.includes(String(item.platform || '')))
  .sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0)))
const timeline = computed<any[]>(() => [...(data.value.categoryPropagationTimeline || [])]
  .sort((a, b) => Number(a.delay_minutes || 0) - Number(b.delay_minutes || 0)))
const categorySummary = computed<any>(() => data.value.categoryPropagationSummary || {})
const leadText = computed(() => {
  const platforms = categorySummary.value.lead_platforms || (categorySummary.value.lead_platform ? [categorySummary.value.lead_platform] : [])
  return platforms.length ? `${platforms.map(platformName).join('、')}${platforms.length > 1 ? '（同一小时）' : `，${categorySummary.value.lead_category}`}` : '暂无达阈值数据'
})
const hottestPlatform = computed(() => recentPlatformTimeline.value[0])
const lastTimeline = computed(() => timeline.value[timeline.value.length - 1])
const maxHeat = computed(() => Math.max(1, ...recentPlatformTimeline.value.map(item => Number(item.hot_score || 0))))
const categoryPropagationLinks = computed<any[]>(() => data.value.categoryPropagationLinks || [])

const graphOption = computed(() => ({
  tooltip: {
    formatter: (params: any) => {
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
    data: recentPlatformTimeline.value.map(item => ({
      name: platformName(item.platform),
      platform: item.platform,
      value: item.hot_score,
      symbolSize: 34 + Number(item.hot_score || 0) / maxHeat.value * 42,
      itemStyle: hiddenPlatforms.value.includes(item.platform) ? { color: '#cbd5e1', borderColor: '#94a3b8', borderWidth: 1 } : undefined,
      label: { color: hiddenPlatforms.value.includes(item.platform) ? '#94a3b8' : '#172033' }
    })),
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

const funnelOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'funnel', sort: 'descending', data: recentPlatformTimeline.value.map(item => ({ name: platformName(item.platform), value: Number(item.hot_score || 0) })) }]
}))

async function load() {
  data.value = await getData(`/events/${eventId.value}/dashboard`)
}
async function refreshData() {
  refreshing.value = true
  try {
    clearGetCache()
    await load()
    ElMessage.success('已读取最新数据库快照')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '读取数据库失败')
  } finally {
    refreshing.value = false
  }
}
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function fixed(value: any) { return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? '-' : Number(value).toFixed(1) }
function togglePlatform(params: any) {
  if (params?.dataType !== 'node' || !params.data?.platform) {
    return
  }
  const platform = String(params.data.platform)
  hiddenPlatforms.value = hiddenPlatforms.value.includes(platform)
    ? hiddenPlatforms.value.filter(item => item !== platform)
    : [...hiddenPlatforms.value, platform]
}
function clearHiddenPlatforms() { hiddenPlatforms.value = [] }
function formatWindow(summary: any) {
  const start = formatTime(summary?.window_start).slice(0, 10)
  const end = formatTime(summary?.window_end).slice(0, 10)
  return start && end && start !== '-' && end !== '-' ? `${start} 至 ${end}` : '最近 7 天'
}
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || value || '-' }

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取平台扩散失败'))
})

useDatabaseAutoRefresh(load)
</script>

<style scoped>
.insight-list > div { flex-wrap: wrap; gap: 8px; }
.insight-list > div > span { flex: 1; min-width: 130px; }
</style>
