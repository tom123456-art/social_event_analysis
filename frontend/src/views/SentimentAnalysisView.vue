<template>
  <div class="analysis-front sentiment-page">
    <div class="analysis-page-head">
      <div>
        <span>情感分析 · 多平台舆情</span>
        <h2>舆情情感变化与传播风险观察</h2>
        <p>微博评论观察公众情绪；五个新闻平台正文观察报道基调与传播关注程度。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-button class="database-refresh-button" type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>
    <el-tabs v-model="activeTab" class="sentiment-tabs">
      <el-tab-pane label="微博公众情绪" name="weibo">
        <MetricGrid :items="weiboMetrics" />
        <div class="grid two sentiment-top-grid">
          <div class="analysis-card trend-card"><div class="card-title"><span>微博情感趋势 <small>按日观察评论情绪结构变化</small></span><el-radio-group v-model="trendRange" size="small"><el-radio-button label="3">3天</el-radio-button><el-radio-button label="7">7天</el-radio-button><el-radio-button label="30">30天</el-radio-button></el-radio-group></div><ChartBox :option="weiboTrendOption" /></div>
          <div class="analysis-card reversal-card"><div class="card-title">微博风险观察 <small>只保留可行动的变化</small></div><div class="insight-list"><div><b>主导情感</b><span>{{ sentimentName(mainSentiment) }} {{ mainSentimentShare }}。{{ mainSentimentInsight }}</span></div><div><b>负向峰值</b><span>{{ negativePeak ? `${negativePeak.date}，${negativePeak.count} 条，占当日 ${percent(negativePeak.rate)}` : '暂无足够数据' }}。{{ negativePeakInsight }}</span></div><div><b>反转判断</b><span>{{ reversalText }}</span></div><div class="insight-action"><b>建议动作</b><span>{{ reversalAction }}</span></div></div></div>
        </div>
        <div class="grid two sentiment-middle-grid"><div class="analysis-card heat-card"><div class="card-title">情感-话题交叉热力图 <small>Top 10 主题，显示各情感占比</small></div><ChartBox :option="topicHeatOption" /></div><div class="analysis-card table-card"><div class="card-title">微博评论明细 <small>{{ filteredWeiboRows.length }} 条清洗后评论</small></div><DetailTable :rows="filteredWeiboRows" :page="page" :page-size="pageSize" :filter="sentimentFilter" :keyword="keywordQuery" @update:page="page = $event" @update:filter="sentimentFilter = $event" @update:keyword="keywordQuery = $event" /></div></div>
      </el-tab-pane>
      <el-tab-pane label="新闻传播导向" name="news">
        <MetricGrid :items="newsMetrics" />
        <div class="news-filter-bar"><el-select v-model="newsPlatform" size="small" style="width:170px"><el-option label="全部新闻平台" value="ALL" /><el-option v-for="platform in NEWS_PLATFORMS" :key="platform" :label="platformName(platform)" :value="platform" /></el-select><el-radio-group v-model="trendRange" size="small"><el-radio-button label="3">3天</el-radio-button><el-radio-button label="7">7天</el-radio-button><el-radio-button label="30">30天</el-radio-button></el-radio-group></div>
        <div class="grid two sentiment-top-grid"><div class="analysis-card trend-card"><div class="card-title"><span>新闻情感导向与热度趋势 <small>按新闻各自发布时间聚合</small></span></div><ChartBox :option="newsTrendOption" @chart-click="openTrendEvidence" /></div><div class="analysis-card risk-card"><div class="card-title">传播关注研判</div><div class="risk-summary"><button class="evidence-link" type="button" @click="openConclusionEvidence"><div class="risk-grade" :class="riskClass">{{ riskGrade }}</div><strong>{{ newsConclusion }}</strong></button><p>关注指数 = 负面概率 × 平台相对热度。缺少热度不等于低风险，高指数也不等于引导效果已被证实。</p></div><div class="risk-platform-list"><button v-for="row in newsPlatformRows" :key="row.platform" class="risk-platform-row" type="button" @click="openPlatformEvidence(row)"><span class="platform-dot" :style="{ background: platformColor(row.platform) }"></span><b>{{ platformName(row.platform) }}</b><span>{{ row.negativeRate.toFixed(1) }}% 负面 · {{ row.validCount ? row.risk.toFixed(2) : '无法计算' }} 指数合计</span><span class="row-arrow">查看依据</span></button></div></div></div>
        <div class="grid two sentiment-middle-grid"><div class="analysis-card quadrant-card"><div class="card-title">负面报道占比与传播热度</div><ChartBox class="negative-spread-chart" :option="quadrantOption" @chart-click="openQuadrantEvidence" /><div class="negative-sample-list"><button v-for="row in newsPlatformRows" :key="row.platform" type="button" @click="openPlatformEvidence(row)">{{ platformName(row.platform) }}：负面 {{ row.negative }} 条 · 热度有效 {{ row.negativeHeatCount }} 条<span v-if="row.negativeHeat === null"> · 暂无有效热度</span></button></div><p class="evidence-explain">负面占比反映报道基调；下图仅统计负面报道的热度，不混入正面新闻。相对热度是同平台样本中的互动位置，不是跨平台阅读量，也不能证明情绪引导效果。</p></div><div class="analysis-card table-card"><div class="card-title">新闻关注明细 <small>{{ filteredNewsRows.length }} 条新闻正文</small></div><div class="table-filters"><el-select v-model="newsSentimentFilter" size="small" style="width:120px"><el-option label="全部情感" value="all" /><el-option label="只看正面" value="positive" /><el-option label="只看中性" value="neutral" /><el-option label="只看负面" value="negative" /></el-select><el-input v-model="newsKeyword" size="small" clearable placeholder="搜索标题或正文" style="width:190px" /></div><table class="table fixed-rows sentiment-detail-table news-detail-table"><thead><tr><th>平台</th><th>时间</th><th>导向</th><th>关注指数</th><th>新闻标题</th></tr></thead><tbody><tr v-for="(row,index) in pagedNewsRows" :key="`${row.content_id}-${index}`" class="clickable-row" @click="openNewsEvidence(row)"><td><span class="platform-name"><i class="platform-dot" :style="{ background: platformColor(row.platform) }"></i>{{ platformName(row.platform) }}</span></td><td>{{ fullTime(row.publish_time) }}</td><td><span class="sentiment-pill" :class="label(row.sentiment_label)">{{ sentimentName(row.sentiment_label) }}</span></td><td><b :class="riskTextClass(row)">{{ riskDisplay(row) }}</b></td><td class="content-cell" :title="row.title || row.content_text">{{ row.title || row.content_text || '-' }}</td></tr><tr v-for="i in newsEmptyRows" :key="`news-empty-${i}`"><td colspan="5"></td></tr></tbody></table><el-pagination class="table-pagination" v-model:current-page="newsPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredNewsRows.length" /></div></div>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="evidenceVisible" :title="evidenceTitle" width="min(860px, 94vw)">
      <div class="evidence-dialog">
        <div v-for="item in evidenceItems" :key="item.label" class="evidence-item">
          <span>{{ item.label }}</span>
          <b>{{ item.value }}</b>
        </div>
        <p class="evidence-explain">{{ evidenceText }}</p>
        <template v-if="evidenceArticle">
          <h3 class="evidence-section-title">{{ evidenceArticle.title || '新闻正文' }}</h3>
          <div class="evidence-body">{{ evidenceArticle.content_text || '暂无正文' }}</div>
          <p v-if="!evidenceUrl" class="evidence-explain">此条新闻未提供有效原文链接。</p>
        </template>
        <template v-if="evidenceComparisons.length">
          <h3 class="evidence-section-title">平台计算对照</h3>
          <div class="evidence-table-wrap"><table class="table"><thead><tr><th>平台</th><th>样本数</th><th>指数有效样本</th><th>负面占比</th><th>指数合计</th></tr></thead><tbody><tr v-for="row in evidenceComparisons" :key="row.platform"><td>{{ platformName(row.platform) }}</td><td>{{ row.total }}</td><td>{{ row.validCount }}</td><td>{{ row.negativeRate.toFixed(2) }}%</td><td>{{ row.validCount ? row.risk.toFixed(2) : '无法计算' }}</td></tr></tbody></table></div>
        </template>
        <template v-if="evidenceNews.length">
          <h3 class="evidence-section-title">对应新闻 · {{ evidenceNews.length }} 条</h3>
          <div class="evidence-news-list"><button v-for="(row, index) in evidenceNews.slice(0, evidenceLimit)" :key="String(row.content_id) + '-' + index" type="button" @click="openNewsEvidence(row)"><span>{{ row.title || row.content_text || '无标题新闻' }}</span><small>{{ platformName(row.platform) }} · {{ fullTime(row.publish_time) }} · 指数 {{ riskDisplay(row) }}</small></button></div>
          <el-button v-if="evidenceNews.length > evidenceLimit" @click="evidenceLimit += 100">加载更多新闻</el-button>
        </template>
        <el-button v-if="evidenceUrl" type="primary" @click="openSource">打开新闻原文</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ANALYSIS_EVENT_ID, ANALYSIS_EVENT_NAME } from '../config/analysisDataset'
import ChartBox from '../components/ChartBox.vue'
import MetricGrid from '../components/MetricGrid.vue'
import { clearGetCache, getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const NEWS_PLATFORMS = ['SOHU_NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SINA_NEWS', 'THE_PAPER']
const PLATFORM_NAMES: Record<string, string> = { SOHU_NEWS: '搜狐新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' }
const PLATFORM_COLORS: Record<string, string> = { SOHU_NEWS: '#e11d48', TENCENT_NEWS: '#0ea5e9', NETEASE_NEWS: '#f97316', SINA_NEWS: '#dc2626', THE_PAPER: '#334155' }
const eventId = ref(ANALYSIS_EVENT_ID), sentimentRows = ref<any[]>([]), refreshing = ref(false), activeTab = ref('weibo'), page = ref(1), newsPage = ref(1), pageSize = 5, sentimentFilter = ref('all'), newsSentimentFilter = ref('all'), keywordQuery = ref(''), newsKeyword = ref(''), trendRange = ref('7'), newsPlatform = ref('ALL'), evidenceVisible = ref(false), evidenceTitle = ref('计算依据'), evidenceItems = ref<any[]>([]), evidenceText = ref(''), evidenceUrl = ref(''), evidenceArticle = ref<any>(null), evidenceNews = ref<any[]>([]), evidenceComparisons = ref<any[]>([]), evidenceLimit = ref(100)
const currentEventName = ANALYSIS_EVENT_NAME
const isWeibo = (v: any) => String(v || '').trim().toUpperCase() === 'WEIBO', label = (v: any) => ['positive', 'neutral', 'negative'].includes(String(v)) ? String(v) : 'neutral', sentimentName = (v: string) => v === 'positive' ? '正面' : v === 'negative' ? '负面' : '中性', fullTime = (v: any) => v ? String(v).replace('T', ' ').replace(/\.\d+$/, '').slice(0, 19) : '-', date = (v: any) => fullTime(v).slice(0, 10), time = (v: any) => Date.parse(String(v || '').replace('T', ' ')) || 0, percent = (v: any) => `${Number(v || 0).toFixed(1)}%`, platformName = (v: any) => PLATFORM_NAMES[String(v)] || String(v || '-'), platformColor = (v: any) => PLATFORM_COLORS[String(v)] || '#64748b'
const tokens = (v: any) => [...new Set(String(v || '').split(/[,，、/\\#\s]+/).map(x => x.trim()).filter(x => x.length > 1 && !/^(关键词|话题)$/.test(x)))], displayKeywords = (v: any) => tokens(v).slice(0, 3).join('、') || '-'
const weiboRows = computed(() => sentimentRows.value.filter(row => isWeibo(row.platform))), newsRows = computed(() => sentimentRows.value.filter(row => NEWS_PLATFORMS.includes(String(row.platform || '').toUpperCase())))
const totals = (rows: any[]) => rows.reduce((out: any, row: any) => { const key = label(row.sentiment_label); out[key] = (out[key] || 0) + 1; return out }, { positive: 0, neutral: 0, negative: 0 }), share = (v: any, total: number) => total ? percent(Number(v || 0) / total * 100) : '0.0%'
const weiboTotals = computed(() => totals(weiboRows.value)), weiboTotal = computed(() => weiboRows.value.length)
const weiboMetrics = computed(() => [{ label: '正面评论', value: weiboTotals.value.positive, sub: `${share(weiboTotals.value.positive, weiboTotal.value)} · 微博公众情绪` }, { label: '中性评论', value: weiboTotals.value.neutral, sub: `${share(weiboTotals.value.neutral, weiboTotal.value)} · 微博公众情绪` }, { label: '负面评论', value: weiboTotals.value.negative, sub: `${share(weiboTotals.value.negative, weiboTotal.value)} · 微博公众情绪` }, { label: '微博评论总量', value: weiboTotal.value || '-', sub: '来自清洗后评论记录' }])
const daily = (rows: any[]) => { const map: Record<string, any> = {}; rows.forEach(row => { const day = date(row.publish_time); if (!day || day === '-') return; const item = map[day] ||= { date: day, total: 0, positive: 0, neutral: 0, negative: 0, heat: 0, heatCount: 0, risk: 0 }; item.total++; item[label(row.sentiment_label)]++; item.heat += heatValue(row) ?? 0; if (heatValue(row) !== null) item.heatCount++; item.risk += riskScore(row) }); return Object.values(map).sort((a: any, b: any) => a.date.localeCompare(b.date)).map((x: any) => ({ ...x, negativeRate: x.total ? x.negative / x.total * 100 : 0, direction: x.total ? (x.positive - x.negative) / x.total * 100 : 0 })) }
const dailyWeibo = computed(() => daily(weiboRows.value)), trendDays = computed(() => dailyWeibo.value.slice(-Number(trendRange.value)).map(x => x.date))
const weiboTrendOption = computed(() => ({ tooltip: { trigger: 'axis' }, legend: { top: 0 }, grid: { left: 42, right: 16, top: 40, bottom: 34 }, xAxis: { type: 'category', data: trendDays.value, axisLabel: { formatter: (v: string) => v.slice(5) } }, yAxis: { type: 'value', minInterval: 1 }, series: ['positive', 'neutral', 'negative'].map(key => ({ name: sentimentName(key), type: 'line', smooth: true, data: trendDays.value.map(day => dailyWeibo.value.find(x => x.date === day)?.[key] || 0) })) }))
function topicStats(rows: any[]) { const map: Record<string, any> = {}; rows.forEach(row => tokens(row.keywords).forEach(topic => { const item = map[topic] ||= { topic, positive: 0, neutral: 0, negative: 0, total: 0 }; item[label(row.sentiment_label)]++; item.total++ })); return Object.values(map).map((item: any) => ({ ...item, positiveRate: item.positive / item.total * 100, negativeRate: item.negative / item.total * 100 })).sort((a: any, b: any) => b.total - a.total) }
const topics = computed(() => topicStats(weiboRows.value)), topicHeatOption = computed(() => ({ tooltip: { formatter: (p: any) => `${p.name} · ${sentimentName(p.value[1])}：${p.value[2]}%` }, grid: { left: 64, right: 22, top: 18, bottom: 88, containLabel: true }, xAxis: { type: 'category', data: topics.value.slice(0, 10).map(x => x.topic), axisLabel: { rotate: 32 } }, yAxis: { type: 'category', data: ['负面', '中性', '正面'] }, visualMap: { min: 0, max: 100, orient: 'horizontal', left: 'center', bottom: 4, inRange: { color: ['#f3f4f6', '#fecaca', '#ef4444'] } }, series: [{ type: 'heatmap', data: topics.value.slice(0, 10).flatMap((topic: any, x: number) => ['negative', 'neutral', 'positive'].map((key, y) => [x, y, Number((topic[key] / topic.total * 100 || 0).toFixed(1))])), label: { show: true, formatter: (p: any) => `${p.value[2]}%` } }] }))
const filteredWeiboRows = computed(() => weiboRows.value.filter(row => (sentimentFilter.value === 'all' || label(row.sentiment_label) === sentimentFilter.value) && (!keywordQuery.value.trim() || `${row.keywords || ''}${row.content_text || ''}`.toLowerCase().includes(keywordQuery.value.trim().toLowerCase()))).sort((a, b) => time(b.publish_time) - time(a.publish_time)))
const mainSentiment = computed(() => Object.entries(weiboTotals.value).sort((a: any, b: any) => b[1] - a[1])[0]?.[0] || 'neutral'), mainSentimentShare = computed(() => share(weiboTotals.value[mainSentiment.value], weiboTotal.value)), mainSentimentInsight = computed(() => mainSentiment.value === 'negative' ? '当前评论存在明显风险，应优先查看负向峰值对应的主题。' : mainSentiment.value === 'positive' ? '整体反馈偏积极，仍需关注少数高频负向主题。' : '多数评论偏信息传递或观望，需结合负面占比和变化速度判断。')
const negativePeak = computed(() => [...dailyWeibo.value].sort((a, b) => b.negative - a.negative)[0]), negativePeakInsight = computed(() => negativePeak.value && negativePeak.value.negativeRate >= 50 ? '该日负面评论已过半，建议回看对应传播内容。' : '峰值只代表数量，仍需结合占比判断风险。'), reversalText = computed(() => '当前版本保留微博评论的负向变化观察，不将新闻正文与微博评论混合判断。'), reversalAction = computed(() => negativePeak.value?.negativeRate >= 50 ? '优先核查负面峰值日期的主题与原始评论。' : '继续观察负面占比是否连续上升。')
const newsScopedRows = computed(() => newsRows.value.filter(row => newsPlatform.value === 'ALL' || row.platform === newsPlatform.value)), newsTotals = computed(() => totals(newsScopedRows.value)), newsTotal = computed(() => newsScopedRows.value.length)
function heatValue(row: any): number | null {
  const value = row.platform_heat_index
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Math.max(0, Math.min(100, Number(value)))
}
function negativeProbability(row: any): number | null {
  const value = row.sentiment_negative_score
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Math.max(0, Math.min(1, Number(value)))
}
function hasRiskData(row: any) { return heatValue(row) !== null && negativeProbability(row) !== null }
function riskScore(row: any) { return (negativeProbability(row) ?? 0) * (heatValue(row) ?? 0) }
function riskDisplay(row: any) { return hasRiskData(row) ? riskScore(row).toFixed(2) : '数据不足' }
function probabilityDisplay(value: any) { return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? '未提供' : (Number(value) * 100).toFixed(2) + '%' }

const newsMetrics = computed(() => {
  const valid = newsScopedRows.value.filter(hasRiskData)
  const avg = valid.length ? valid.reduce((sum, row) => sum + riskScore(row), 0) / valid.length : null
  return [{ label: '新闻报道总量', value: newsTotal.value, sub: '覆盖 ' + new Set(newsScopedRows.value.map(x => x.platform)).size + ' 个平台' }, { label: '主导报道基调', value: newsTotal.value ? sentimentName(Object.entries(newsTotals.value).sort((a: any, b: any) => b[1] - a[1])[0]?.[0]) : '-', sub: '负面 ' + share(newsTotals.value.negative, newsTotal.value) }, { label: '平均关注指数', value: avg === null ? '数据不足' : avg.toFixed(2), sub: '有效样本 ' + valid.length + ' / ' + newsTotal.value }, { label: '优先核查新闻', value: valid.filter(row => riskScore(row) >= 45).length, sub: '指数 ≥ 45；页面观察阈值' }]
})
const newsPlatformRows = computed(() => NEWS_PLATFORMS.map(platform => {
  const rows = newsScopedRows.value.filter(x => x.platform === platform), valid = rows.filter(hasRiskData), heatRows = rows.filter(x => heatValue(x) !== null), t = totals(rows)
  const negativeRows = rows.filter(row => label(row.sentiment_label) === 'negative')
  const negativeHeatRows = negativeRows.filter(row => heatValue(row) !== null)
  const negativeHeat = negativeHeatRows.length ? negativeHeatRows.reduce((sum, row) => sum + heatValue(row)!, 0) / negativeHeatRows.length : null
  return { platform, total: rows.length, negative: t.negative, negativeHeat, negativeHeatCount: negativeHeatRows.length, negativeRate: rows.length ? t.negative / rows.length * 100 : 0, heatCount: heatRows.length, validCount: valid.length, heat: heatRows.length ? heatRows.reduce((sum, row) => sum + heatValue(row)!, 0) / heatRows.length : null, risk: valid.reduce((sum, row) => sum + riskScore(row), 0) }
}).filter(row => row.total))
const newsConclusionRow = computed(() => [...newsPlatformRows.value].filter(row => row.validCount > 0).sort((a, b) => b.risk - a.risk)[0])
const newsConclusion = computed(() => {
  const row = newsConclusionRow.value
  if (!newsTotal.value) return '暂无新闻样本'
  if (!row) return '缺少有效热度或模型概率，传播程度待核实'
  if (row.risk === 0) return '当前有效样本的关注指数为零，仍需结合原文核查'
  return platformName(row.platform) + '在当前样本中的关注指数合计最高（' + row.risk.toFixed(2) + '）'
})
const riskGrade = computed(() => {
  const valid = newsScopedRows.value.filter(hasRiskData)
  if (!valid.length) return '传播程度待核实'
  const max = Math.max(...valid.map(riskScore))
  return max >= 70 ? '优先核查' : max >= 45 ? '持续关注' : '常规观察'
})
const riskClass = computed(() => riskGrade.value === '优先核查' ? 'danger' : riskGrade.value === '持续关注' ? 'warn' : 'normal')
const newsDaily = computed(() => daily(newsScopedRows.value)), newsDays = computed(() => newsDaily.value.slice(-Number(trendRange.value))), newsTrendOption = computed(() => ({ tooltip: { trigger: 'axis' }, legend: { top: 0 }, grid: { left: 48, right: 48, top: 40, bottom: 34 }, xAxis: { type: 'category', data: newsDays.value.map(x => x.date), axisLabel: { formatter: (v: string) => v.slice(5) } }, yAxis: [{ type: 'value', name: '热度' }, { type: 'value', name: '导向', min: -100, max: 100 }], series: [{ name: '相对热度合计', type: 'bar', data: newsDays.value.map(x => x.heatCount ? Number(x.heat.toFixed(1)) : null) }, { name: '情感导向', type: 'line', yAxisIndex: 1, smooth: true, data: newsDays.value.map(x => Number(x.direction.toFixed(1))), itemStyle: { color: '#dc2626' } }] }))
const quadrantOption = computed(() => {
  const rows = newsPlatformRows.value
  return {
    tooltip: { trigger: 'item', formatter: (p: any) => {
      const row = rows.find(row => row.platform === p.data.platform)
      if (!row) return ''
      return `${platformName(row.platform)}<br/>负面报道：${row.negative} / ${row.total} 条（${row.negativeRate.toFixed(1)}%）<br/>负面报道平均相对热度：${row.negativeHeat === null ? '数据不足' : row.negativeHeat.toFixed(1)}<br/>负面热度有效样本：${row.negativeHeatCount} 条<br/>负面热度缺失：${row.negative - row.negativeHeatCount} 条`
    } },
    title: [{ text: '负面报道占比（%）', left: 75, top: 4, textStyle: { fontSize: 12 } }, { text: '负面报道平均热度（0–100）', left: 75, top: 218, textStyle: { fontSize: 12 } }],
    grid: [{ left: 90, right: 60, top: 35, height: 155 }, { left: 90, right: 60, top: 250, height: 155 }],
    xAxis: [0, 1].map(gridIndex => ({ gridIndex, type: 'value', min: 0, max: 100 })),
    yAxis: [0, 1].map(gridIndex => ({ gridIndex, type: 'category', inverse: true, data: rows.map(row => platformName(row.platform)), axisTick: { show: false }, axisLine: { show: false } })),
    series: [
      { type: 'bar', barMaxWidth: 18, data: rows.map(row => ({ value: row.negativeRate, platform: row.platform, itemStyle: { color: '#dc2626' } })), label: { show: true, position: 'right', formatter: (p: any) => `${p.value.toFixed(1)}%` } },
      { type: 'bar', xAxisIndex: 1, yAxisIndex: 1, barMaxWidth: 18, data: rows.map(row => ({ value: row.negativeHeat, platform: row.platform, itemStyle: { color: '#0891b2' } })), label: { show: true, position: 'right', formatter: (p: any) => p.value === null ? '' : Number(p.value).toFixed(1) } }
    ]
  }
})
const filteredNewsRows = computed(() => newsScopedRows.value.filter(row => (newsSentimentFilter.value === 'all' || label(row.sentiment_label) === newsSentimentFilter.value) && (!newsKeyword.value.trim() || `${row.title || ''}${row.content_text || ''}`.toLowerCase().includes(newsKeyword.value.trim().toLowerCase()))).sort((a, b) => riskScore(b) - riskScore(a) || time(b.publish_time) - time(a.publish_time))), pagedNewsRows = computed(() => filteredNewsRows.value.slice((newsPage.value - 1) * pageSize, newsPage.value * pageSize)), newsEmptyRows = computed(() => Math.max(0, pageSize - pagedNewsRows.value.length))
const riskTextClass = (row: any) => !hasRiskData(row) ? 'risk-normal' : riskScore(row) >= 70 ? 'risk-danger' : riskScore(row) >= 45 ? 'risk-warn' : 'risk-normal'
watch([sentimentFilter, keywordQuery], () => page.value = 1); watch([newsSentimentFilter, newsKeyword, newsPlatform], () => newsPage.value = 1)
async function load() { sentimentRows.value = await getData(`/events/${eventId.value}/sentiment-analysis`, true) }
function openEvidence(title: string, items: any[], text: string) {
  evidenceTitle.value = title; evidenceItems.value = items; evidenceText.value = text
  evidenceLimit.value = 100; evidenceUrl.value = ''; evidenceArticle.value = null; evidenceNews.value = []; evidenceComparisons.value = []
  evidenceVisible.value = true
}
const methodText = '单篇关注指数 = 模型负面概率（0—1）× 平台相对热度（0—100）。平台合计为有效样本的单篇指数之和，受样本数量和互动字段覆盖率影响，不是跨平台传播效果的严格排名。热度由 ETL 对同平台同事件可用互动字段计算百分位后取平均，不代表绝对阅读量。45、70 仅为本页观察分档，不是法定预警标准，也不能证明公众情绪变化或报道的引导效果。'
function sortedEvidenceNews(rows: any[]) { return [...rows].sort((a, b) => Number(hasRiskData(b)) - Number(hasRiskData(a)) || riskScore(b) - riskScore(a) || time(b.publish_time) - time(a.publish_time)) }
function openConclusionEvidence() {
  openEvidence('结论依据与平台对照', [{ label: '判定范围', value: newsPlatform.value === 'ALL' ? '全部新闻平台 · 当前数据快照' : platformName(newsPlatform.value) }, { label: '新闻样本', value: newsTotal.value }, { label: '计算有效样本', value: newsScopedRows.value.filter(hasRiskData).length }, { label: '观察分档依据', value: '有效样本中最大单篇指数；45 / 70 为页面分档阈值' }], methodText)
  evidenceComparisons.value = [...newsPlatformRows.value].sort((a, b) => b.risk - a.risk)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value)
}
function openPlatformEvidence(row: any) {
  openEvidence(platformName(row.platform) + ' · 计算依据', [{ label: '新闻样本数', value: row.total }, { label: '负面新闻数 / 样本数', value: row.negative + ' / ' + row.total }, { label: '负面占比', value: row.negativeRate.toFixed(2) + '%' }, { label: '有效热度样本数', value: row.heatCount }, { label: '指数有效样本数', value: row.validCount }, { label: '平均相对热度', value: row.heat === null ? '数据缺失' : row.heat.toFixed(2) }, { label: '关注指数合计', value: row.validCount ? row.risk.toFixed(2) : '无法计算' }], methodText)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value.filter(x => x.platform === row.platform))
}
function openNewsEvidence(row: any) {
  openEvidence('新闻正文分析依据', [{ label: '平台', value: platformName(row.platform) }, { label: '发布时间', value: fullTime(row.publish_time) }, { label: '模型情感标签', value: sentimentName(row.sentiment_label) }, { label: '正面 / 中性 / 负面概率', value: [row.sentiment_positive_score, row.sentiment_neutral_score, row.sentiment_negative_score].map(probabilityDisplay).join(' / ') }, { label: '点赞 / 收藏 / 评论 / 转发', value: [row.like_count, row.favorite_count, row.comment_count, row.repost_count].map(value => value === null || value === undefined ? '未提供' : String(value)).join(' / ') }, { label: '平台相对热度', value: heatValue(row) === null ? '缺失，不能据此判断低热度' : heatValue(row)!.toFixed(2) }, { label: '计算过程', value: hasRiskData(row) ? negativeProbability(row)!.toFixed(4) + ' × ' + heatValue(row)!.toFixed(2) + ' = ' + riskScore(row).toFixed(2) : '缺少模型概率或有效热度，无法计算' }], methodText + ' 情感概率来自模型对标题与正文的整体预测，不是人工核实结论，也不是逐句证据。')
  evidenceArticle.value = row
  const url = String(row.source_url || '')
  evidenceUrl.value = /^https?:\/\//i.test(url) ? url : ''
}
function openSource() { if (evidenceUrl.value) window.open(evidenceUrl.value, '_blank', 'noopener,noreferrer') }
function openQuadrantEvidence(params: any) { const row = newsPlatformRows.value.find(x => x.platform === params.data?.platform); if (row) openPlatformEvidence(row) }
function openTrendEvidence(params: any) {
  const day = newsDays.value[params.dataIndex]
  if (!day) return
  openEvidence(day.date + ' · 日度计算依据', [{ label: '新闻样本数', value: day.total }, { label: '正面 / 中性 / 负面数', value: [day.positive, day.neutral, day.negative].join(' / ') }, { label: '报道导向计算', value: '(' + day.positive + ' − ' + day.negative + ') ÷ ' + day.total + ' × 100 = ' + day.direction.toFixed(2) }, { label: '有效热度样本', value: day.heatCount }, { label: '相对热度合计', value: day.heatCount ? day.heat.toFixed(2) : '缺少有效热度' }], '报道导向 =（正面报道数 − 负面报道数）÷ 新闻总数 × 100；按各自发布时间归入当天，不与微博时间对齐，也不作因果分析。日度热度是有效新闻的相对热度合计，受报道数量影响。' + methodText)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value.filter(row => date(row.publish_time) === day.date))
}
async function refreshData() { refreshing.value = true; try { clearGetCache(); await load(); ElMessage.success('已读取最新数据库快照') } catch (error: any) { ElMessage.error(error?.response?.data?.message || error?.message || '读取数据失败') } finally { refreshing.value = false } }
const DetailTable = defineComponent({ props: { rows: { type: Array, required: true }, page: { type: Number, required: true }, pageSize: { type: Number, required: true }, filter: { type: String, required: true }, keyword: { type: String, required: true } }, emits: ['update:page', 'update:filter', 'update:keyword'], setup(props, { emit }) { return () => h('div', [h('div', { class: 'table-filters' }, [h('select', { value: props.filter, onChange: (e: any) => emit('update:filter', e.target.value) }, [h('option', { value: 'all' }, '全部情感'), h('option', { value: 'positive' }, '只看正面'), h('option', { value: 'neutral' }, '只看中性'), h('option', { value: 'negative' }, '只看负面')]), h('input', { value: props.keyword, placeholder: '搜索关键词', onInput: (e: any) => emit('update:keyword', e.target.value) })]), h('table', { class: 'table fixed-rows sentiment-detail-table' }, [h('thead', [h('tr', ['时间', '情感', '主题/关键词', '评论内容'].map(x => h('th', x)))]), h('tbody', (props.rows as any[]).slice((props.page - 1) * props.pageSize, props.page * props.pageSize).map((row: any) => h('tr', [h('td', fullTime(row.publish_time)), h('td', [h('span', { class: `sentiment-pill ${label(row.sentiment_label)}` }, sentimentName(row.sentiment_label))]), h('td', displayKeywords(row.keywords)), h('td', { class: 'content-cell', title: row.content_text }, row.content_text || '-')]))) ]), h('el-pagination', { class: 'table-pagination', currentPage: props.page, pageSize: props.pageSize, total: props.rows.length, layout: 'total, prev, pager, next', 'onUpdate:currentPage': (value: number) => emit('update:page', value) })]) } })
onMounted(() => load().catch(() => ElMessage.error('读取情感分析失败')))
useDatabaseAutoRefresh(load)
</script>

<style scoped>
.sentiment-page { display: block; }
.negative-spread-chart { height: 440px; min-height: 440px !important; }
.negative-sample-list { display: grid; gap: 6px; }
.negative-sample-list button { padding: 4px 0; background: transparent; border: 0; text-align: left; font-size: 12px; color: #475569; cursor: pointer; }
.evidence-section-title { margin: 8px 0; font-size: 15px; overflow-wrap: anywhere; }
.evidence-body { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow-y: auto; line-height: 1.8; }
.evidence-table-wrap { overflow-x: auto; }
.evidence-table-wrap .table { min-width: 560px; }
.evidence-news-list { display: grid; max-height: 280px; overflow-y: auto; }
.evidence-news-list button { border: 0; border-bottom: 1px solid #e5e7eb; background: transparent; text-align: left; padding: 12px 0; cursor: pointer; display: grid; gap: 6px; color: #172033; font: inherit; }
.evidence-news-list button:hover { color: #2563eb; }
.evidence-news-list span { overflow-wrap: anywhere; }
.evidence-news-list small { color: #64748b; }
.evidence-item b { text-align: right; overflow-wrap: anywhere; min-width: 0; }
.table-card { min-width: 0; overflow-x: auto; }
.news-detail-table { min-width: 720px; }
.news-detail-table th:last-child { width: auto; min-width: 220px; }
.risk-platform-row { font-size: 12px; }
@media (max-width: 600px) { .risk-platform-row { grid-template-columns: 10px 1fr; } .risk-platform-row > span:nth-child(n+3) { grid-column: 2; } .table-filters { flex-wrap: wrap; } .table-card { overflow-x: auto; } }
.sentiment-tabs { margin-top: 12px; }
.sentiment-top-grid, .sentiment-middle-grid { margin-top: 12px; }
.analysis-card > .chart { min-height: 280px; }
.card-title small { color: #69778d; font-size: 12px; font-weight: 500; }
.news-filter-bar { display: flex; justify-content: space-between; align-items: center; margin: 12px 0; }
.risk-summary { display: grid; gap: 8px; padding: 8px 2px 12px; }
.risk-summary strong { color: #172033; }
.risk-summary p { margin: 0; color: #64748b; line-height: 1.65; font-size: 12px; }
.risk-grade { width: fit-content; padding: 5px 10px; border-radius: 4px; font-weight: 700; font-size: 13px; }
.risk-grade.normal { color: #166534; background: #dcfce7; }.risk-grade.warn { color: #9a3412; background: #ffedd5; }.risk-grade.danger { color: #991b1b; background: #fee2e2; }
.risk-platform-list { display: grid; gap: 8px; border-top: 1px solid #e5e7eb; padding-top: 10px; }
.risk-platform-list > div { display: grid; grid-template-columns: 10px 90px 1fr; gap: 8px; align-items: center; font-size: 12px; color: #64748b; }.risk-platform-list b { color: #334155; }.evidence-link, .risk-platform-row { border: 0; background: transparent; font: inherit; text-align: left; cursor: pointer; }.evidence-link { display: grid; gap: 8px; padding: 0; }.risk-platform-row { display: grid; grid-template-columns: 10px 90px 1fr auto; gap: 8px; align-items: center; width: 100%; padding: 5px 0; color: #64748b; }.risk-platform-row:hover, .evidence-link:hover strong { color: #2563eb; }.row-arrow { color: #2563eb; font-size: 11px; }.clickable-row { cursor: pointer; }.clickable-row:hover td { background: #f8fafc; }.evidence-dialog { display: grid; gap: 12px; }.evidence-item { display: flex; justify-content: space-between; gap: 16px; border-bottom: 1px solid #eef2f7; padding-bottom: 8px; }.evidence-item span { color: #64748b; }.evidence-explain { color: #475569; line-height: 1.7; font-size: 13px; }
.platform-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; }.platform-name { display: inline-flex; align-items: center; gap: 6px; }
.table-filters { display: flex; justify-content: flex-end; gap: 8px; margin-bottom: 8px; }.table-filters select, .table-filters input { height: 28px; border: 1px solid #d9e0ea; border-radius: 4px; padding: 0 8px; color: #475569; background: #fff; }
.sentiment-detail-table { table-layout: fixed; }.sentiment-detail-table th:nth-child(1) { width: 145px; }.sentiment-detail-table th:nth-child(2) { width: 68px; }.sentiment-detail-table th:nth-child(3) { width: 150px; }.news-detail-table th:nth-child(1) { width: 100px; }.news-detail-table th:nth-child(2) { width: 145px; }.news-detail-table th:nth-child(3) { width: 68px; }.news-detail-table th:nth-child(4) { width: 85px; }
.content-cell { max-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.sentiment-pill { display: inline-flex; padding: 3px 7px; border-radius: 999px; font-size: 11px; }.sentiment-pill.positive { color: #166534; background: #dcfce7; }.sentiment-pill.neutral { color: #475569; background: #f1f5f9; }.sentiment-pill.negative { color: #991b1b; background: #fee2e2; }
.risk-normal { color: #64748b; }.risk-warn { color: #c2410c; }.risk-danger { color: #b91c1c; }
@media (max-width: 1100px) { .news-filter-bar { align-items: flex-start; gap: 8px; flex-direction: column; }.analysis-card > .chart { min-height: 250px; } }
</style>
