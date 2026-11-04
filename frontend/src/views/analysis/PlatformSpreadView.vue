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
        <div class="card-title">类别热度响应时间线 <span>{{ formatWindow(categorySummary) }} · 首次达到阈值</span></div>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <table class="table fixed-rows">
          <thead><tr><th>平台</th><th>首达类别</th><th>首次达阈值</th><th>时间差</th><th>类别数</th><th>峰值关注指数</th></tr></thead>
          <tbody>
            <!-- 按 row in timeline 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="row in timeline" :key="row.platform">
              <td>{{ platformName(row.platform) }}</td>
              <td><b>{{ row.lead_category || '-' }}</b></td>
              <td>{{ formatTime(row.first_hot_time) }}</td>
              <td>{{ row.delay_minutes }} 分钟</td>
              <td>{{ row.category_count || 0 }}</td>
              <td>{{ fixed(row.peak_attention) }}</td>
            </tr>
            <!-- 按 i in Math.max(0, 5 - timeline.length) 循环生成表格行，使用 key 保持行标识稳定。 -->
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
    <!-- 传入当前证据模式、事件编号和快照，关闭时清空模式以隐藏弹框。 -->
    <PropagationEvidenceDialog :mode="evidenceMode" :dashboard="data" @close="evidenceMode = ''" />
  </div>
</template>

<script setup lang="ts">
import { numberText, formatTime, fixed } from '../../utils/analysisFormat'
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
// 拼接最早达到阈值的平台名称；同一小时有多个平台时注明并列。
const leadText = computed(() => {
  // 优先使用并列平台列表，兼容只有一个领先平台字段的响应。
  const platforms = categorySummary.value.lead_platforms || (categorySummary.value.lead_platform ? [categorySummary.value.lead_platform] : [])
  return platforms.length ? `${platforms.map(platformName).join('、')}${platforms.length > 1 ? '（同一小时）' : `，${categorySummary.value.lead_category}`}` : '暂无达阈值数据'
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
  data.value = await getData('/analysis/dashboard')
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
// 将统计窗口的起止时间格式化为日期文本，无有效日期时显示默认窗口说明。
function formatWindow(summary: any) {
  const start = formatTime(summary?.window_start).slice(0, 10)
  const end = formatTime(summary?.window_end).slice(0, 10)
  return start && end && start !== '-' && end !== '-' ? `${start} 至 ${end}` : '最近 7 天'
}
// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || '其他平台' }

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取平台扩散失败'))
})

useDatabaseAutoRefresh(load)
</script>

<style scoped>
.insight-list > div { flex-wrap: wrap; gap: 8px; }
.insight-list > div > span { flex: 1; min-width: 130px; }
</style>
