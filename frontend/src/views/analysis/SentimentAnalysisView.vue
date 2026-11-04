<template>
  <!-- 微博评论与新闻正文分别统计情感，新闻关注指数支持查看公式和原文依据。 -->
  <div class="analysis-front sentiment-page">
    <div class="analysis-page-head">
      <div>
        <span>情感分析 · 多平台舆情</span>
        <h2>舆情情感变化与传播风险观察</h2>
        <p>微博评论观察公众情绪；新闻正文观察报道基调。仅统计通过模型置信度过滤的正负记录，占比不代表全部原始内容。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <!-- 点击“刷新数据库”时执行 refreshData。 -->
        <el-button class="database-refresh-button" type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>
    <el-tabs v-model="activeTab" class="sentiment-tabs">
      <!-- 把“微博公众情绪”作为独立标签页，切换后展示对应内容。 -->
      <el-tab-pane label="微博公众情绪" name="weibo">
        <!-- 把统计指标传入共用指标组件，循环显示名称、数值与说明。 -->
        <MetricGrid :items="weiboMetrics" />
        <div class="grid two sentiment-top-grid">
          <div class="analysis-card trend-card"><div class="card-title"><span>微博情感趋势 <small>按日观察评论情绪结构变化</small></span><el-radio-group v-model="trendRange" size="small"><el-radio-button label="3">3天</el-radio-button><el-radio-button label="7">7天</el-radio-button><el-radio-button label="30">30天</el-radio-button></el-radio-group></div><ChartBox :option="weiboTrendOption" /></div>
          <div class="analysis-card reversal-card"><div class="card-title">微博风险观察 <small>只保留可行动的变化</small></div><div class="insight-list"><div><b>主导情感</b><span>{{ sentimentName(mainSentiment) }} {{ mainSentimentShare }}。{{ mainSentimentInsight }}</span></div><div><b>负向峰值</b><span>{{ negativePeak ? `${negativePeak.date}，${negativePeak.negative} 条，占当日 ${percent(negativePeak.negativeRate)}` : '暂无足够数据' }}。{{ negativePeakInsight }}</span></div><div><b>反转判断</b><span>{{ reversalText }}</span></div><div class="insight-action"><b>建议动作</b><span>{{ reversalAction }}</span></div></div></div>
        </div>
        <section class="analysis-card weibo-detail-card">
          <div class="comment-section-head">
            <div><h3>微博评论明细</h3><p>逐条核查情感判断、评论语境与互动表现</p></div>
            <span class="result-count">筛选结果 {{ filteredWeiboRows.length }} / {{ weiboRows.length }} 条</span>
          </div>
          <div class="comment-toolbar">
            <el-radio-group v-model="sentimentFilter" size="small">
              <el-radio-button label="all">全部 {{ weiboRows.length }}</el-radio-button>
              <el-radio-button label="negative">负面 {{ weiboTotals.negative }}</el-radio-button>
              <el-radio-button label="positive">正面 {{ weiboTotals.positive }}</el-radio-button>
            </el-radio-group>
            <div class="comment-filter-fields">
              <!-- 将当前控件的输入或选择同步到 weiboDateFilter，供查询或提交时读取。 -->
              <el-select v-model="weiboDateFilter" size="small" aria-label="发布日期" style="width: 142px"><el-option label="全部日期" value="all" /><el-option v-for="day in weiboDateOptions" :key="day" :label="day" :value="day" /></el-select>
              <!-- 将当前控件的输入或选择同步到 weiboSort，供查询或提交时读取。 -->
              <el-select v-model="weiboSort" size="small" aria-label="评论排序" style="width: 128px"><el-option label="最新发布" value="latest" /><el-option label="最早发布" value="earliest" /><el-option label="置信度最高" value="confidence" /></el-select>
              <!-- 将“搜索评论正文、话题或作者”的输入或选择同步到 keywordQuery，供查询或提交时读取。 -->
              <el-input v-model="keywordQuery" class="comment-search" size="small" clearable placeholder="搜索评论正文、话题或作者" />
            </div>
          </div>
          <div class="filter-summary"><span>正面 {{ filteredWeiboTotals.positive }}</span><span>负面 {{ filteredWeiboTotals.negative }}</span><span>负面占比 {{ share(filteredWeiboTotals.negative, filteredWeiboRows.length) }}</span><button v-if="hasWeiboFilters" type="button" @click="resetWeiboFilters">清除筛选</button></div>
          <!-- 仅当 pagedWeiboRows.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="pagedWeiboRows.length" class="comment-list">
            <!-- 根据 row in pagedWeiboRows 循环生成条目，模板中的字段读取当前条目数据。 -->
            <article v-for="row in pagedWeiboRows" :key="row.content_id" class="comment-item" @click="openWeiboDetail(row)">
              <div class="comment-sentiment" :class="label(row.sentiment_label)"><span>{{ sentimentName(row.sentiment_label) }}</span><strong>{{ confidenceDisplay(row) }}</strong></div>
              <div class="comment-main">
                <div class="comment-meta"><b>{{ row.author_name || '微博用户' }}</b><span>{{ fullTime(row.publish_time) }}</span><span>{{ sourceName(row.sentiment_source) }}</span></div>
                <p>{{ row.content_text || '暂无评论正文' }}</p>
                <!-- 仅当 tokens(row.keywords).length 成立时显示此处内容，条件变化时自动更新。 -->
                <div v-if="tokens(row.keywords).length" class="comment-topics"><span v-for="topic in tokens(row.keywords).slice(0, 5)" :key="topic">#{{ topic }}</span></div>
              </div>
              <span class="comment-detail-link">查看详情</span>
            </article>
          </div>
          <el-empty v-else description="没有符合当前条件的评论" :image-size="72" />
          <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
          <el-pagination v-if="filteredWeiboRows.length" class="table-pagination" v-model:current-page="page" :page-size="weiboPageSize" layout="total, prev, pager, next" :total="filteredWeiboRows.length" />
        </section>
      </el-tab-pane>
      <!-- 把“新闻传播导向”作为独立标签页，切换后展示对应内容。 -->
      <el-tab-pane label="新闻传播导向" name="news">
        <!-- 把统计指标传入共用指标组件，循环显示名称、数值与说明。 -->
        <MetricGrid :items="newsMetrics" />
        <div class="news-filter-bar"><el-select v-model="newsPlatform" size="small" style="width:170px"><el-option label="全部新闻平台" value="ALL" /><el-option v-for="platform in NEWS_PLATFORMS" :key="platform" :label="platformName(platform)" :value="platform" /></el-select><el-radio-group v-model="trendRange" size="small"><el-radio-button label="3">3天</el-radio-button><el-radio-button label="7">7天</el-radio-button><el-radio-button label="30">30天</el-radio-button></el-radio-group></div>
        <div class="grid two sentiment-top-grid"><div class="analysis-card trend-card"><div class="card-title"><span>新闻情感导向与热度趋势 <small>按新闻各自发布时间聚合</small></span></div><ChartBox :option="newsTrendOption" @chart-click="openTrendEvidence" /></div><div class="analysis-card risk-card"><div class="card-title">传播关注研判</div><div class="risk-summary"><button class="evidence-link" type="button" @click="openConclusionEvidence"><div class="risk-grade" :class="riskClass">{{ riskGrade }}</div><strong>{{ newsConclusion }}</strong></button><p>关注指数 = 负面概率 × 平台相对热度。缺少热度不等于低风险，高指数也不等于引导效果已被证实。</p></div><div class="risk-platform-list"><button v-for="row in newsPlatformRows" :key="row.platform" class="risk-platform-row" type="button" @click="openPlatformEvidence(row)"><span class="platform-dot" :style="{ background: platformColor(row.platform) }"></span><b>{{ platformName(row.platform) }}</b><span>{{ row.negativeRate.toFixed(1) }}% 负面 · {{ row.validCount ? row.risk.toFixed(2) : '无法计算' }} 指数合计</span><span class="row-arrow">查看依据</span></button></div></div></div>
        <div class="grid two sentiment-middle-grid news-detail-grid"><div class="analysis-card quadrant-card"><div class="card-title">负面报道占比与传播热度</div><ChartBox class="negative-spread-chart" :option="quadrantOption" @chart-click="openQuadrantEvidence" /></div><div class="analysis-card table-card news-attention-card"><div class="card-title">新闻关注明细 <small>{{ filteredNewsRows.length }} 条新闻正文</small></div><div class="table-filters"><el-select v-model="newsSentimentFilter" size="small" style="width:120px"><el-option label="全部情感" value="all" /><el-option label="只看正面" value="positive" /><el-option label="只看负面" value="negative" /></el-select><el-input v-model="newsKeyword" size="small" clearable placeholder="搜索标题或正文" style="width:190px" /></div><table class="table fixed-rows sentiment-detail-table news-detail-table"><thead><tr><th>平台</th><th>时间</th><th>导向</th><th>关注指数</th><th>新闻标题</th></tr></thead><tbody><tr v-for="(row,index) in pagedNewsRows" :key="`${row.content_id}-${index}`" class="clickable-row" @click="openNewsEvidence(row)"><td><span class="platform-name"><i class="platform-dot" :style="{ background: platformColor(row.platform) }"></i>{{ platformName(row.platform) }}</span></td><td>{{ fullTime(row.publish_time) }}</td><td><span class="sentiment-pill" :class="label(row.sentiment_label)">{{ sentimentName(row.sentiment_label) }}</span></td><td><b :class="riskTextClass(row)">{{ riskDisplay(row) }}</b></td><td class="content-cell" :title="row.title || row.content_text">{{ row.title || row.content_text || '-' }}</td></tr><tr v-for="i in newsEmptyRows" :key="`news-empty-${i}`"><td colspan="5"></td></tr></tbody></table><el-pagination class="table-pagination" v-model:current-page="newsPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredNewsRows.length" /></div></div>
      </el-tab-pane>
    </el-tabs>
    <!-- 将弹框显隐与 evidenceVisible 绑定，集中展示当前操作的详情或表单。 -->
    <el-dialog v-model="evidenceVisible" :title="evidenceTitle" width="min(860px, 94vw)">
      <div class="evidence-dialog">
        <!-- 根据 item in evidenceItems 循环生成条目，模板中的字段读取当前条目数据。 -->
        <div v-for="item in evidenceItems" :key="item.label" class="evidence-item">
          <span>{{ item.label }}</span>
          <b>{{ item.value }}</b>
        </div>
        <p class="evidence-explain">{{ evidenceText }}</p>
        <template v-if="evidenceArticle">
          <h3 class="evidence-section-title">{{ evidenceArticle.title || '新闻正文' }}</h3>
          <div class="evidence-body">{{ evidenceArticle.content_text || '暂无正文' }}</div>
          <!-- 仅当 !evidenceUrl 成立时显示此处内容，条件变化时自动更新。 -->
          <p v-if="!evidenceUrl" class="evidence-explain">此条新闻未提供有效原文链接。</p>
        </template>
        <template v-if="evidenceComparisons.length">
          <h3 class="evidence-section-title">平台计算对照</h3>
          <div class="evidence-table-wrap"><table class="table"><thead><tr><th>平台</th><th>样本数</th><th>指数有效样本</th><th>负面占比</th><th>指数合计</th></tr></thead><tbody><tr v-for="row in evidenceComparisons" :key="row.platform"><td>{{ platformName(row.platform) }}</td><td>{{ row.total }}</td><td>{{ row.validCount }}</td><td>{{ row.negativeRate.toFixed(2) }}%</td><td>{{ row.validCount ? row.risk.toFixed(2) : '无法计算' }}</td></tr></tbody></table></div>
        </template>
        <template v-if="evidenceNews.length">
          <h3 class="evidence-section-title">对应新闻 · {{ evidenceNews.length }} 条</h3>
          <div class="evidence-news-list"><button v-for="(row, index) in evidenceNews.slice(0, evidenceLimit)" :key="String(row.content_id) + '-' + index" type="button" @click="openNewsEvidence(row)"><span>{{ row.title || row.content_text || '无标题新闻' }}</span><small>{{ platformName(row.platform) }} · {{ fullTime(row.publish_time) }} · 指数 {{ riskDisplay(row) }}</small></button></div>
          <!-- 点击“加载更多新闻”时执行 evidenceLimit += 100。 -->
          <el-button v-if="evidenceNews.length > evidenceLimit" @click="evidenceLimit += 100">加载更多新闻</el-button>
        </template>
        <!-- 点击“打开新闻原文”时执行 openSource。 -->
        <el-button v-if="evidenceUrl" type="primary" @click="openSource">打开新闻原文</el-button>
      </div>
    </el-dialog>
    <el-drawer v-model="weiboDetailVisible" title="微博评论详情" size="min(560px, 92vw)">
      <!-- 仅当 selectedWeiboRow 成立时显示此处内容，条件变化时自动更新。 -->
      <div v-if="selectedWeiboRow" class="weibo-detail-drawer">
        <div class="detail-sentiment-row"><span class="sentiment-pill" :class="label(selectedWeiboRow.sentiment_label)">{{ sentimentName(selectedWeiboRow.sentiment_label) }}</span><b>{{ confidenceLabel(selectedWeiboRow) }} {{ confidenceDisplay(selectedWeiboRow) }}</b></div>
        <div class="detail-comment-text">{{ selectedWeiboRow.content_text || '暂无评论正文' }}</div>
        <div class="detail-topic-list"><span v-for="topic in tokens(selectedWeiboRow.keywords)" :key="topic">#{{ topic }}</span></div>
        <dl class="detail-grid">
          <div><dt>发布账号</dt><dd>{{ selectedWeiboRow.author_name || '微博用户' }}</dd></div>
          <div><dt>发布时间</dt><dd>{{ fullTime(selectedWeiboRow.publish_time) }}</dd></div>
          <div><dt>判定来源</dt><dd>{{ sourceName(selectedWeiboRow.sentiment_source) }}</dd></div>
          <div><dt>内容编号</dt><dd>{{ selectedWeiboRow.content_id || '-' }}</dd></div>
          <div><dt>正面概率</dt><dd>{{ probabilityDisplay(selectedWeiboRow.sentiment_positive_score) }}</dd></div>
          <div><dt>负面概率</dt><dd>{{ probabilityDisplay(selectedWeiboRow.sentiment_negative_score) }}</dd></div>
        </dl>
        <!-- 点击“打开微博原文”时执行 openWeiboSource。 -->
        <el-button v-if="validSourceUrl(selectedWeiboRow.source_url)" type="primary" @click="openWeiboSource">打开微博原文</el-button>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'
import ChartBox from '../../components/common/ChartBox.vue'
import MetricGrid from '../../components/common/MetricGrid.vue'
import { clearGetCache, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'

// 限定新闻分析参与平台，防止把微博评论混入新闻正文统计。
const NEWS_PLATFORMS = ['SOHU_NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SINA_NEWS', 'THE_PAPER']
// 将新闻平台编码映射为中文名称。
const PLATFORM_NAMES: Record<string, string> = { SOHU_NEWS: '搜狐新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' }
// 为平台定义固定图表颜色，方便跨图表识别。
const PLATFORM_COLORS: Record<string, string> = { SOHU_NEWS: '#e11d48', TENCENT_NEWS: '#0ea5e9', NETEASE_NEWS: '#f97316', SINA_NEWS: '#dc2626', THE_PAPER: '#334155' }
// 固定正面和负面配色，避免系列顺序改变后颜色混淆。
const SENTIMENT_COLORS: Record<string, string> = { positive: '#16a34a', negative: '#dc2626' }
const sentimentRows = ref<any[]>([]), refreshing = ref(false), activeTab = ref('weibo'), page = ref(1), newsPage = ref(1), pageSize = 8, weiboPageSize = 8, sentimentFilter = ref('all'), newsSentimentFilter = ref('all'), keywordQuery = ref(''), newsKeyword = ref(''), weiboDateFilter = ref('all'), weiboSort = ref('latest'), trendRange = ref('7'), newsPlatform = ref('ALL'), evidenceVisible = ref(false), evidenceTitle = ref('计算依据'), evidenceItems = ref<any[]>([]), evidenceText = ref(''), evidenceUrl = ref(''), evidenceArticle = ref<any>(null), evidenceNews = ref<any[]>([]), evidenceComparisons = ref<any[]>([]), evidenceLimit = ref(100), weiboDetailVisible = ref(false), selectedWeiboRow = ref<any>(null)
// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME
// 只保留正负二分类样本，不把中性或跳过记录计入分母。
const binarySentimentRows = computed(() => sentimentRows.value.filter(row => ['positive', 'negative'].includes(String(row.sentiment_label))))
// 定义微博识别、情感翻译、日期和百分比格式化等显示转换函数。
const isWeibo = (v: any) => String(v || '').trim().toUpperCase() === 'WEIBO', label = (v: any) => ['positive', 'negative'].includes(String(v)) ? String(v) : 'unknown', sentimentName = (v: string) => v === 'positive' ? '正面' : v === 'negative' ? '负面' : '未分类', fullTime = (v: any) => v ? String(v).replace('T', ' ').replace(/\.\d+$/, '').slice(0, 19) : '-', date = (v: any) => fullTime(v).slice(0, 10), time = (v: any) => Date.parse(String(v || '').replace('T', ' ')) || 0, percent = (v: any) => `${Number(v || 0).toFixed(1)}%`, platformName = (v: any) => PLATFORM_NAMES[String(v)] || (isWeibo(v) ? '微博' : '其他平台'), platformColor = (v: any) => PLATFORM_COLORS[String(v)] || '#64748b'
// 拆分关键词文本，去重并过滤过短词。
const tokens = (v: any) => [...new Set(String(v || '').split(/[,，、/\\#\s]+/).map(x => x.trim()).filter(x => x.length > 1 && !/^(关键词|话题)$/.test(x)))]
// 从有效样本拆出微博评论和新闻正文，两组数据独立统计。
const weiboRows = computed(() => binarySentimentRows.value.filter(row => isWeibo(row.platform))), newsRows = computed(() => binarySentimentRows.value.filter(row => NEWS_PLATFORMS.includes(String(row.platform || '').toUpperCase())))
// 提供正负计数累计函数，以及按指定总数计算占比的方法。
const totals = (rows: any[]) => rows.reduce((out: any, row: any) => { const key = label(row.sentiment_label); out[key] = (out[key] || 0) + 1; return out }, { positive: 0, negative: 0 }), share = (v: any, total: number) => total ? percent(Number(v || 0) / total * 100) : '0.0%'
// 计算评论正负数量和总数，作为评论指标共用分母。
const weiboTotals = computed(() => totals(weiboRows.value)), weiboTotal = computed(() => weiboRows.value.length)
// 整理评论正负数量、日期覆盖和保留样本总数等指标。
const weiboMetrics = computed(() => [{ label: '正面评论', value: weiboTotals.value.positive, sub: `${share(weiboTotals.value.positive, weiboTotal.value)} · 过滤后样本占比` }, { label: '负面评论', value: weiboTotals.value.negative, sub: `${share(weiboTotals.value.negative, weiboTotal.value)} · 过滤后样本占比` }, { label: '评论覆盖天数', value: dailyWeibo.value.length, sub: '按有效评论发布时间统计' }, { label: '保留评论总量', value: weiboTotal.value, sub: '模型筛选或人工审核后的评论' }])
// 按日期聚合当前样本，或读取当前筛选范围内的日度记录。
const daily = (rows: any[]) => { const map: Record<string, any> = {}; rows.forEach(row => { const day = date(row.publish_time); if (!day || day === '-') return; const item = map[day] ||= { date: day, total: 0, positive: 0, negative: 0, heat: 0, heatCount: 0, risk: 0 }; item.total++; item[label(row.sentiment_label)]++; item.heat += heatValue(row) ?? 0; if (heatValue(row) !== null) item.heatCount++; item.risk += riskScore(row) }); return Object.values(map).sort((a: any, b: any) => a.date.localeCompare(b.date)).map((x: any) => ({ ...x, negativeRate: x.total ? x.negative / x.total * 100 : 0, direction: x.total ? (x.positive - x.negative) / x.total * 100 : 0 })) }
// 按日聚合评论，取指定天数作为趋势横轴。
const dailyWeibo = computed(() => daily(weiboRows.value)), trendDays = computed(() => dailyWeibo.value.slice(-Number(trendRange.value)).map(x => x.date))
// 为正面、负面评论分别生成逐日数量折线。
const weiboTrendOption = computed(() => ({ tooltip: { trigger: 'axis' }, legend: { top: 0 }, grid: { left: 42, right: 16, top: 40, bottom: 34 }, xAxis: { type: 'category', data: trendDays.value, axisLabel: { formatter: (v: string) => v.slice(5) } }, yAxis: { type: 'value', minInterval: 1 }, series: ['positive', 'negative'].map(key => ({ name: sentimentName(key), type: 'line', smooth: true, lineStyle: { color: SENTIMENT_COLORS[key] }, itemStyle: { color: SENTIMENT_COLORS[key] }, data: trendDays.value.map(day => dailyWeibo.value.find(x => x.date === day)?.[key] || 0) })) }))
// 按当前情感标签取对应模型得分，供排序及展示使用。
const confidence = (row: any) => label(row.sentiment_label) === 'positive' ? Number(row.sentiment_positive_score) || 0 : Number(row.sentiment_negative_score) || 0
// 筛选评论情感、日期与文本，再按指定方式排序。
const filteredWeiboRows = computed(() => weiboRows.value.filter(row => (sentimentFilter.value === 'all' || label(row.sentiment_label) === sentimentFilter.value) && (weiboDateFilter.value === 'all' || date(row.publish_time) === weiboDateFilter.value) && (!keywordQuery.value.trim() || `${row.author_name || ''} ${row.keywords || ''} ${row.content_text || ''}`.toLowerCase().includes(keywordQuery.value.trim().toLowerCase()))).sort((a, b) => weiboSort.value === 'earliest' ? time(a.publish_time) - time(b.publish_time) : weiboSort.value === 'confidence' ? confidence(b) - confidence(a) : time(b.publish_time) - time(a.publish_time)))
// 从筛选后评论中截取当前页。
const pagedWeiboRows = computed(() => filteredWeiboRows.value.slice((page.value - 1) * weiboPageSize, page.value * weiboPageSize))
// 对筛选后的评论重新统计正负数量。
const filteredWeiboTotals = computed(() => totals(filteredWeiboRows.value))
// 提取有效评论日期，去重倒序后生成日期选项。
const weiboDateOptions = computed(() => [...new Set(weiboRows.value.map(row => date(row.publish_time)).filter(day => day !== '-'))].sort().reverse())
// 判断筛选和排序是否偏离默认值，决定是否显示重置入口。
const hasWeiboFilters = computed(() => sentimentFilter.value !== 'all' || weiboDateFilter.value !== 'all' || keywordQuery.value.trim() !== '' || weiboSort.value !== 'latest')
// 将情感判定来源显示为人工审核或模型预测。
const sourceName = (value: any) => String(value || '').toUpperCase() === 'MANUAL' ? '人工审核' : '模型预测'
// 人工记录显示判定方式，模型记录显示置信度标签。
const confidenceLabel = (row: any) => String(row.sentiment_source || '').toUpperCase() === 'MANUAL' ? '判定方式' : '模型置信度'
// 人工记录显示人工确认，模型记录有得分时才显示概率。
const confidenceDisplay = (row: any) => String(row.sentiment_source || '').toUpperCase() === 'MANUAL' ? '人工确认' : confidence(row) ? percent(confidence(row) * 100) : '未提供'
// 仅允许 HTTP 或 HTTPS 原文地址。
const validSourceUrl = (value: any) => /^https?:\/\//i.test(String(value || ''))
// 将评论情感、日期、关键词和排序恢复默认状态。
function resetWeiboFilters() { sentimentFilter.value = 'all'; weiboDateFilter.value = 'all'; keywordQuery.value = ''; weiboSort.value = 'latest' }
// 保存所选微博评论，再打开评论详情弹框。
function openWeiboDetail(row: any) { selectedWeiboRow.value = row; weiboDetailVisible.value = true }
// 校验来源地址后，在隔离的新窗口打开评论原文。
function openWeiboSource() { const url = selectedWeiboRow.value?.source_url; if (validSourceUrl(url)) window.open(url, '_blank', 'noopener,noreferrer') }
// 按评论数量找出主导情感，并计算占比与解释文本。
const mainSentiment = computed(() => weiboTotal.value ? Object.entries(weiboTotals.value).sort((a: any, b: any) => b[1] - a[1])[0]?.[0] || 'unknown' : 'unknown'), mainSentimentShare = computed(() => share(weiboTotals.value[mainSentiment.value], weiboTotal.value)), mainSentimentInsight = computed(() => mainSentiment.value === 'negative' ? '当前评论存在明显风险，应优先查看负向峰值对应的主题。' : mainSentiment.value === 'positive' ? '整体反馈偏积极，仍需关注少数高频负向主题。' : '暂无有效情感记录。')
// 找出负面评论数量最高的一天，生成峰值说明和核查建议。
const negativePeak = computed(() => [...dailyWeibo.value].sort((a, b) => b.negative - a.negative)[0]), negativePeakInsight = computed(() => negativePeak.value && negativePeak.value.negativeRate >= 50 ? '该日负面评论已过半，建议回看对应传播内容。' : '峰值只代表数量，仍需结合占比判断风险。'), reversalText = computed(() => '当前版本保留微博评论的负向变化观察，不将新闻正文与微博评论混合判断。'), reversalAction = computed(() => negativePeak.value?.negativeRate >= 50 ? '优先核查负面峰值日期的主题与原始评论。' : '继续观察负面占比是否连续上升。')
// 按选定平台过滤新闻，再计算范围内正负数和总数。
const newsScopedRows = computed(() => newsRows.value.filter(row => newsPlatform.value === 'ALL' || row.platform === newsPlatform.value)), newsTotals = computed(() => totals(newsScopedRows.value)), newsTotal = computed(() => newsScopedRows.value.length)
// 缺失热度保留为空，不解释为低热度；有效数值限制在平台相对指数范围内。
function heatValue(row: any): number | null {
  // 只读取平台相对热度；空值、空字符串及非有限数视为缺失，其余限制在零到一百。
  const value = row.platform_heat_index
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Math.max(0, Math.min(100, Number(value)))
}
// 模型负面概率限制在零到一；人工标注若未提供概率，则不参与指数计算。
function negativeProbability(row: any): number | null {
  // 只读取模型提供的负面概率，不根据人工标签补造概率值。
  const value = row.sentiment_negative_score
  return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? null : Math.max(0, Math.min(1, Number(value)))
}
// 两个参与乘法的字段都有效时，才允许把该记录作为指数有效样本。
function hasRiskData(row: any) { return heatValue(row) !== null && negativeProbability(row) !== null }
// 以负面概率乘平台相对热度得到关注指数，数值运算中的缺失字段按零处理。
function riskScore(row: any) { return (negativeProbability(row) ?? 0) * (heatValue(row) ?? 0) }
// 展示前再检查字段有效性，避免把缺失字段计算出的零误显示为真实指数。
function riskDisplay(row: any) { return hasRiskData(row) ? riskScore(row).toFixed(2) : '数据不足' }
// 模型概率转换为百分比展示，缺失或无法转换成数字的输入显示未提供。
function probabilityDisplay(value: any) { return value === null || value === undefined || value === '' || !Number.isFinite(Number(value)) ? '未提供' : (Number(value) * 100).toFixed(2) + '%' }

// 平均指数与优先核查数量只使用热度、概率均有效的样本，并单独展示覆盖数量。
const newsMetrics = computed(() => {
  // 只对热度和概率均有效的新闻计算平均关注指数，空样本返回空值。
  const valid = newsScopedRows.value.filter(hasRiskData)
  const avg = valid.length ? valid.reduce((sum, row) => sum + riskScore(row), 0) / valid.length : null
  return [{ label: '新闻报道总量', value: newsTotal.value, sub: '覆盖 ' + new Set(newsScopedRows.value.map(x => x.platform)).size + ' 个平台' }, { label: '主导报道基调', value: newsTotal.value ? sentimentName(Object.entries(newsTotals.value).sort((a: any, b: any) => b[1] - a[1])[0]?.[0]) : '-', sub: '负面 ' + share(newsTotals.value.negative, newsTotal.value) }, { label: '平均关注指数', value: avg === null ? '数据不足' : avg.toFixed(2), sub: '有效样本 ' + valid.length + ' / ' + newsTotal.value }, { label: '优先核查新闻', value: valid.filter(row => riskScore(row) >= 45).length, sub: '指数 ≥ 45；页面观察阈值' }]
})
// 平台内分别统计负面占比、负面平均热度和有效指数合计，保留各自分母。
const newsPlatformRows = computed(() => NEWS_PLATFORMS.map(platform => {
  // 对当前平台分别提取全部记录、指数有效记录与仅热度有效记录。
  const rows = newsScopedRows.value.filter(x => x.platform === platform), valid = rows.filter(hasRiskData), heatRows = rows.filter(x => heatValue(x) !== null), t = totals(rows)
  // 负面占比的分子是全部负面报道，负面平均热度则只取其中热度有效的记录。
  const negativeRows = rows.filter(row => label(row.sentiment_label) === 'negative')
  const negativeHeatRows = negativeRows.filter(row => heatValue(row) !== null)
  // 平均热度使用有效负面样本数作分母，没有有效样本时保留空值。
  const negativeHeat = negativeHeatRows.length ? negativeHeatRows.reduce((sum, row) => sum + heatValue(row)!, 0) / negativeHeatRows.length : null
  // 返回统计值及各自样本数量；平台指数为有效单篇指数之和，而非平均值。
  return { platform, total: rows.length, negative: t.negative, negativeHeat, negativeHeatCount: negativeHeatRows.length, negativeRate: rows.length ? t.negative / rows.length * 100 : 0, heatCount: heatRows.length, validCount: valid.length, heat: heatRows.length ? heatRows.reduce((sum, row) => sum + heatValue(row)!, 0) / heatRows.length : null, risk: valid.reduce((sum, row) => sum + riskScore(row), 0) }
}).filter(row => row.total))
// 在指数有效的平台中找出累计关注指数最高的一项。
const newsConclusionRow = computed(() => [...newsPlatformRows.value].filter(row => row.validCount > 0).sort((a, b) => b.risk - a.risk)[0])
// 根据样本和计算字段是否可用生成当前新闻结论。
const newsConclusion = computed(() => {
  // 分别处理没有新闻、缺少计算数据和有效指数为零，避免给出同一个误导结论。
  const row = newsConclusionRow.value
  if (!newsTotal.value) return '暂无新闻样本'
  if (!row) return '缺少有效热度或模型概率，传播程度待核实'
  if (row.risk === 0) return '当前有效样本的关注指数为零，仍需结合原文核查'
  return platformName(row.platform) + '在当前样本中的关注指数合计最高（' + row.risk.toFixed(2) + '）'
})
// 观察分档取有效样本的最高单篇指数，阈值只用于本页核查排序，不是法定预警。
const riskGrade = computed(() => {
  // 排除数据不足记录后取最高单篇指数，与四十五、七十分两个页面阈值比较。
  const valid = newsScopedRows.value.filter(hasRiskData)
  if (!valid.length) return '传播程度待核实'
  const max = Math.max(...valid.map(riskScore))
  return max >= 70 ? '优先核查' : max >= 45 ? '持续关注' : '常规观察'
})
// 将观察档位转换为模板使用的样式类名。
const riskClass = computed(() => riskGrade.value === '优先核查' ? 'danger' : riskGrade.value === '持续关注' ? 'warn' : 'normal')
// 按日汇总新闻，并生成相对热度与报道导向的双轴趋势图。
const newsDaily = computed(() => daily(newsScopedRows.value)), newsDays = computed(() => newsDaily.value.slice(-Number(trendRange.value))), newsTrendOption = computed(() => ({ tooltip: { trigger: 'axis' }, legend: { top: 0 }, grid: { left: 48, right: 48, top: 40, bottom: 34 }, xAxis: { type: 'category', data: newsDays.value.map(x => x.date), axisLabel: { formatter: (v: string) => v.slice(5) } }, yAxis: [{ type: 'value', name: '热度' }, { type: 'value', name: '导向', min: -100, max: 100 }], series: [{ name: '相对热度合计', type: 'bar', data: newsDays.value.map(x => x.heatCount ? Number(x.heat.toFixed(1)) : null) }, { name: '情感导向', type: 'line', yAxisIndex: 1, smooth: true, data: newsDays.value.map(x => Number(x.direction.toFixed(1))), itemStyle: { color: '#dc2626' } }] }))
// 分别展示平台负面比例和负面平均热度，不混用两项分母。
const quadrantOption = computed(() => {
  // 上下两组横向柱图共用平台行，但分别显示负面占比和负面平均热度。
  const rows = newsPlatformRows.value
  return {
    tooltip: { trigger: 'item', formatter: (p: any) => {
      const row = rows.find(row => row.platform === p.data.platform)
      // 点击或悬停对象没有匹配平台时不生成提示，防止读取空对象。
      if (!row) return ''
      return `${platformName(row.platform)}<br/>负面报道：${row.negative} / ${row.total} 条（${row.negativeRate.toFixed(1)}%）<br/>负面报道平均相对热度：${row.negativeHeat === null ? '数据不足' : row.negativeHeat.toFixed(1)}<br/>负面热度有效样本：${row.negativeHeatCount} 条<br/>负面热度缺失：${row.negative - row.negativeHeatCount} 条`
    } },
    title: [{ text: '负面报道占比（%）', left: 75, top: 4, textStyle: { fontSize: 12 } }, { text: '负面报道平均热度（0–100）', left: 75, top: 218, textStyle: { fontSize: 12 } }],
    grid: [{ left: 90, right: 60, top: 35, height: 155 }, { left: 90, right: 60, top: 250, height: 155 }],
    xAxis: [0, 1].map(gridIndex => ({ gridIndex, type: 'value', min: 0, max: 100 })),
    yAxis: [0, 1].map(gridIndex => ({ gridIndex, type: 'category', inverse: true, data: rows.map(row => platformName(row.platform)), axisTick: { show: false }, axisLine: { show: false } })),
    // 两组柱图分别关联第一和第二套坐标轴，平台顺序保持一致。
    series: [
      { type: 'bar', barMaxWidth: 18, data: rows.map(row => ({ value: row.negativeRate, platform: row.platform, itemStyle: { color: '#dc2626' } })), label: { show: true, position: 'right', formatter: (p: any) => `${p.value.toFixed(1)}%` } },
      { type: 'bar', xAxisIndex: 1, yAxisIndex: 1, barMaxWidth: 18, data: rows.map(row => ({ value: row.negativeHeat, platform: row.platform, itemStyle: { color: '#0891b2' } })), label: { show: true, position: 'right', formatter: (p: any) => p.value === null ? '' : Number(p.value).toFixed(1) } }
    ]
  }
})
// 筛选新闻情感与文本，按指数和时间排序后截取当前页。
const filteredNewsRows = computed(() => newsScopedRows.value.filter(row => (newsSentimentFilter.value === 'all' || label(row.sentiment_label) === newsSentimentFilter.value) && (!newsKeyword.value.trim() || `${row.title || ''}${row.content_text || ''}`.toLowerCase().includes(newsKeyword.value.trim().toLowerCase()))).sort((a, b) => riskScore(b) - riskScore(a) || time(b.publish_time) - time(a.publish_time))), pagedNewsRows = computed(() => filteredNewsRows.value.slice((newsPage.value - 1) * pageSize, newsPage.value * pageSize)), newsEmptyRows = computed(() => Math.max(0, pageSize - pagedNewsRows.value.length))
// 根据指数字段是否有效以及观察档位选择文本标识。
const riskTextClass = (row: any) => !hasRiskData(row) ? 'risk-normal' : riskScore(row) >= 70 ? 'risk-danger' : riskScore(row) >= 45 ? 'risk-warn' : 'risk-normal'
// 评论筛选变化重置评论页码，新闻筛选变化只重置新闻页码，两组分页互不影响。
watch([sentimentFilter, keywordQuery, weiboDateFilter, weiboSort], () => page.value = 1); watch([newsSentimentFilter, newsKeyword, newsPlatform], () => newsPage.value = 1)
// 强制读取当前事件的情感明细，后续计算属性自动重新统计。
async function load() { sentimentRows.value = await getData('/analysis/sentiment-analysis', true) }
// 每次打开共用证据弹框先清空原文、链接和对照，避免残留旧结论。
function openEvidence(title: string, items: any[], text: string) {
  // 先保存本次标题、指标和说明，再清空上次原文、来源和平台对照数据。
  evidenceTitle.value = title; evidenceItems.value = items; evidenceText.value = text
  evidenceLimit.value = 100; evidenceUrl.value = ''; evidenceArticle.value = null; evidenceNews.value = []; evidenceComparisons.value = []
  evidenceVisible.value = true
}
// 保存指数计算公式及解释限制，供多个证据入口复用。
const methodText = '单篇关注指数 = 模型负面概率（0—1）× 平台相对热度（0—100）。平台合计为有效样本的单篇指数之和，受样本数量和互动字段覆盖率影响，不是跨平台传播效果的严格排名。热度由 ETL 对同平台同事件可用互动字段计算百分位后取平均，不代表绝对阅读量。45、70 仅为本页观察分档，不是法定预警标准，也不能证明公众情绪变化或报道的引导效果。'
// 复制原数组，先排字段完整记录，再按指数和时间降序排序。
function sortedEvidenceNews(rows: any[]) { return [...rows].sort((a, b) => Number(hasRiskData(b)) - Number(hasRiskData(a)) || riskScore(b) - riskScore(a) || time(b.publish_time) - time(a.publish_time)) }
// 打开总体结论来源，同时提供平台对照和当前范围内的原文列表。
function openConclusionEvidence() {
  openEvidence('结论依据与平台对照', [{ label: '判定范围', value: newsPlatform.value === 'ALL' ? '全部新闻平台 · 当前数据快照' : platformName(newsPlatform.value) }, { label: '新闻样本', value: newsTotal.value }, { label: '计算有效样本', value: newsScopedRows.value.filter(hasRiskData).length }, { label: '观察分档依据', value: '有效样本中最大单篇指数；45 / 70 为页面分档阈值' }], methodText)
  // 平台对照按累计关注指数降序，原文按有效性和单篇指数排序。
  evidenceComparisons.value = [...newsPlatformRows.value].sort((a, b) => b.risk - a.risk)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value)
}
// 填入单个平台的样本量、有效样本量、负面占比和关注指数计算依据。
function openPlatformEvidence(row: any) {
  openEvidence(platformName(row.platform) + ' · 计算依据', [{ label: '新闻样本数', value: row.total }, { label: '负面新闻数 / 样本数', value: row.negative + ' / ' + row.total }, { label: '负面占比', value: row.negativeRate.toFixed(2) + '%' }, { label: '有效热度样本数', value: row.heatCount }, { label: '指数有效样本数', value: row.validCount }, { label: '平均相对热度', value: row.heat === null ? '数据缺失' : row.heat.toFixed(2) }, { label: '关注指数合计', value: row.validCount ? row.risk.toFixed(2) : '无法计算' }], methodText)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value.filter(x => x.platform === row.platform))
}
// 填入单篇新闻原始字段及指数乘法过程，缺少概率或热度时说明无法计算。
function openNewsEvidence(row: any) {
  openEvidence('新闻正文分析依据', [{ label: '平台', value: platformName(row.platform) }, { label: '发布时间', value: fullTime(row.publish_time) }, { label: '情感标签', value: sentimentName(row.sentiment_label) }, { label: '判定来源', value: row.sentiment_source === 'MANUAL' ? '人工标注' : '模型预测' }, { label: '正面 / 负面模型得分', value: [row.sentiment_positive_score, row.sentiment_negative_score].map(probabilityDisplay).join(' / ') }, { label: '点赞 / 收藏 / 评论 / 转发', value: [row.like_count, row.favorite_count, row.comment_count, row.repost_count].map(value => value === null || value === undefined ? '未提供' : String(value)).join(' / ') }, { label: '平台相对热度', value: heatValue(row) === null ? '缺失，不能据此判断低热度' : heatValue(row)!.toFixed(2) }, { label: '计算过程', value: hasRiskData(row) ? negativeProbability(row)!.toFixed(4) + ' × ' + heatValue(row)!.toFixed(2) + ' = ' + riskScore(row).toFixed(2) : '缺少模型概率或有效热度，无法计算' }], methodText + ' 模型记录须达到配置阈值（默认 0.95）；人工审核记录使用人工标签，模型概率留空；高分不保证客观新闻已被排除。情感概率来自模型对标题与正文前 256 个 token 的预测，不是人工核实结论，也不是逐句证据。')
  // 保存原文对象供正文展示，来源地址仅接受 HTTP(S) 协议。
  evidenceArticle.value = row
  const url = String(row.source_url || '')
  evidenceUrl.value = /^https?:\/\//i.test(url) ? url : ''
}
// 来源地址非空才打开新窗口，并阻止新页面访问当前窗口对象。
function openSource() { if (evidenceUrl.value) window.open(evidenceUrl.value, '_blank', 'noopener,noreferrer') }
// 从图表数据读取平台编码，找到对应统计行后打开平台证据。
function openQuadrantEvidence(params: any) { const row = newsPlatformRows.value.find(x => x.platform === params.data?.platform); if (row) openPlatformEvidence(row) }
// 按点击日期回查新闻，展示正负数量、报道导向与有效热度的计算过程。
function openTrendEvidence(params: any) {
  // 用图表数据下标定位日期，点击空区域时不打开证据。
  const day = newsDays.value[params.dataIndex]
  if (!day) return
  openEvidence(day.date + ' · 日度计算依据', [{ label: '新闻样本数', value: day.total }, { label: '正面 / 负面数', value: [day.positive, day.negative].join(' / ') }, { label: '报道导向计算', value: '(' + day.positive + ' − ' + day.negative + ') ÷ ' + day.total + ' × 100 = ' + day.direction.toFixed(2) }, { label: '有效热度样本', value: day.heatCount }, { label: '相对热度合计', value: day.heatCount ? day.heat.toFixed(2) : '缺少有效热度' }], '报道导向 =（正面报道数 − 负面报道数）÷ 新闻总数 × 100；按各自发布时间归入当天，不与微博时间对齐，也不作因果分析。日度热度是有效新闻的相对热度合计，受报道数量影响。' + methodText)
  evidenceNews.value = sortedEvidenceNews(newsScopedRows.value.filter(row => date(row.publish_time) === day.date))
}
// 清缓存后重读情感明细，结束时无论成功与否都解除刷新按钮的加载状态。
async function refreshData() { refreshing.value = true; try { clearGetCache(); await load(); ElMessage.success('已读取最新数据库快照') } catch (error: any) { ElMessage.error(getErrorMessage(error, '读取数据失败，请稍后重试。')) } finally { refreshing.value = false } }
// 首次挂载加载数据，读取失败时显示页面级中文提示。
onMounted(() => load().catch(() => ElMessage.error('读取情感分析失败')))
useDatabaseAutoRefresh(load)
</script>

<style scoped>
.sentiment-page { display: block; min-width: 0; }
.negative-spread-chart { height: 440px; min-height: 440px !important; }
.news-detail-grid { align-items: stretch; }
.news-detail-grid > .analysis-card { height: 100%; }
.news-attention-card { min-height: 0; }
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
.weibo-detail-card { margin-top: 12px; min-height: 420px; }
.comment-section-head, .comment-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.comment-section-head h3 { margin: 0; color: #172033; font-size: 16px; }
.comment-section-head p { margin: 5px 0 0; color: #69778d; font-size: 12px; }
.result-count { color: #52627a; font-size: 12px; white-space: nowrap; }
.comment-toolbar { margin-top: 16px; padding: 12px; background: #f6f8fb; border: 1px solid #e3e9f1; border-radius: 6px; }
.comment-filter-fields { display: flex; justify-content: flex-end; gap: 8px; min-width: 0; }
.comment-search { width: 260px; }
.filter-summary { min-height: 38px; display: flex; align-items: center; gap: 18px; padding: 0 4px; border-bottom: 1px solid #e5eaf1; color: #64748b; font-size: 12px; }
.filter-summary button { margin-left: auto; padding: 4px 0; border: 0; background: transparent; color: #2563eb; cursor: pointer; }
.comment-list { display: grid; }
.comment-item { display: grid; grid-template-columns: 82px minmax(0, 1fr) auto; gap: 16px; align-items: center; min-height: 112px; padding: 16px 8px; border-bottom: 1px solid #e8edf3; cursor: pointer; }
.comment-item:hover { background: #f9fbfd; }
.comment-sentiment { align-self: stretch; display: grid; align-content: center; justify-items: center; gap: 6px; border-left: 3px solid; background: #f8fafc; }
.comment-sentiment span { font-size: 13px; font-weight: 800; }
.comment-sentiment strong { color: #64748b; font-size: 11px; font-weight: 600; }
.comment-sentiment.positive { border-color: #16a34a; color: #166534; background: #f0fdf4; }
.comment-sentiment.negative { border-color: #dc2626; color: #991b1b; background: #fef2f2; }
.comment-main { min-width: 0; }
.comment-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 14px; color: #7b8798; font-size: 12px; }
.comment-meta b { color: #27364b; font-size: 13px; }
.comment-main p { margin: 8px 0 10px; color: #172033; font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.comment-topics { display: flex; flex-wrap: wrap; gap: 6px; color: #2563eb; font-size: 12px; }
.comment-detail-link { color: #2563eb; font-size: 12px; white-space: nowrap; }
.weibo-detail-drawer { display: grid; gap: 20px; }
.detail-sentiment-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #475569; font-size: 13px; }
.detail-comment-text { padding: 16px; border-left: 3px solid #2563eb; background: #f7f9fc; color: #172033; line-height: 1.8; overflow-wrap: anywhere; }
.detail-topic-list { display: flex; flex-wrap: wrap; gap: 8px; color: #2563eb; font-size: 13px; }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0; margin: 0; border-top: 1px solid #e5eaf1; }
.detail-grid div { min-width: 0; padding: 12px 4px; border-bottom: 1px solid #e5eaf1; }
.detail-grid dt { margin-bottom: 5px; color: #7b8798; font-size: 12px; }
.detail-grid dd { margin: 0; color: #27364b; font-size: 13px; overflow-wrap: anywhere; }
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
.content-cell { max-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.sentiment-pill { display: inline-flex; padding: 3px 7px; border-radius: 999px; font-size: 11px; }.sentiment-pill.positive { color: #166534; background: #dcfce7; }.sentiment-pill.negative { color: #991b1b; background: #fee2e2; }
.risk-normal { color: #64748b; }.risk-warn { color: #c2410c; }.risk-danger { color: #b91c1c; }
@media (max-width: 1100px) { .news-filter-bar { align-items: flex-start; gap: 8px; flex-direction: column; }.analysis-card > .chart { min-height: 250px; }.comment-toolbar { align-items: flex-start; flex-direction: column; }.comment-filter-fields { width: 100%; justify-content: flex-start; }.comment-search { flex: 1; width: auto; }.comment-item { grid-template-columns: 72px minmax(0, 1fr) auto; } }
@media (max-width: 700px) { .comment-section-head { align-items: flex-start; flex-direction: column; gap: 8px; }.comment-filter-fields { flex-wrap: wrap; }.comment-search { flex-basis: 100%; }.filter-summary { flex-wrap: wrap; gap: 8px 14px; padding: 8px 4px; }.comment-item { grid-template-columns: 1fr auto; gap: 10px; padding: 14px 0; }.comment-sentiment { grid-column: 1 / -1; align-self: auto; grid-template-columns: auto auto; justify-content: start; justify-items: start; padding: 7px 10px; }.detail-grid { grid-template-columns: 1fr; } }
</style>
