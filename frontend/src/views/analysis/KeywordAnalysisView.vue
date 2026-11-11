<template>
  <!-- 主题词云、时间趋势、平台分布和互动观察共用同一组去重内容。 -->
  <div class="analysis-front keyword-analysis-page">
    <div class="analysis-page-head">
      <div>
        <span>关键词分析</span>
        <h2>新闻主题与互动关注观察</h2>
        <p>以新闻内容的互动总量为热度，按关键词共现关系归并主题簇，避免同一主题重复进入排名。</p>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <!-- 点击“刷新数据库”时执行 refreshData。 -->
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <div class="grid third">
      <section class="analysis-card keyword-cloud-card">
        <div class="card-title">主题关键词云 <span>前二十五个议题词簇</span></div>
        <div class="category-legend" aria-label="类别图例">
          <!-- 点击当前按钮时执行 toggleCloudCategory(category.name)。 -->
          <button v-for="category in categoryLegend" :key="category.name" type="button" :class="{ muted: hiddenCloudCategories.has(category.name) }" @click="toggleCloudCategory(category.name)">
            <i :style="{ background: category.color }"></i>{{ category.name }}
          </button>
        </div>
        <div class="keyword-cloud dynamic-cloud">
          <!-- 点击当前按钮时执行 selectTopic(topic.keyword)。 -->
          <button v-for="(topic, index) in cloudTopics" :key="topic.keyword" type="button" :title="topic.cluster_label" :style="cloudStyle(topic, index)" @click="selectTopic(topic.keyword)">{{ topic.keyword }}</button>
          <!-- 仅当 !cloudTopics.length 成立时显示此处内容，条件变化时自动更新。 -->
          <p v-if="!cloudTopics.length" class="chart-empty">当前类别筛选下暂无关键词</p>
        </div>
      </section>

      <section class="analysis-card keyword-trend-card">
        <div class="card-title chart-title-with-control">
          <span>主题热度趋势 <small>前八个跨主题代表词</small></span>
          <el-radio-group v-model="trendGranularity" size="small" aria-label="时间粒度">
            <el-radio-button label="day">日</el-radio-button>
            <el-radio-button label="week">周</el-radio-button>
          </el-radio-group>
        </div>
        <!-- 将 keywordTrendOption 交给图表组件绘制。 -->
        <ChartBox class="keyword-chart" :option="keywordTrendOption" />
      </section>

      <section class="analysis-card keyword-platform-card">
        <div class="card-title">主题-平台分布 <span>前十个跨主题代表词</span></div>
        <!-- 将 keywordPlatformOption 交给图表组件绘制。 -->
        <ChartBox class="keyword-platform-chart" :option="keywordPlatformOption" />
      </section>
    </div>

    <section class="analysis-card keyword-momentum-card">
      <div class="card-title">话题动量排行 <span>按最近 7 天与前 7 天的代表词频变化计算</span></div>
      <div class="momentum-grid">
        <div class="momentum-panel emerging">
          <h3>🔥 新兴话题 <small>近 7 天增速最快</small></h3>
          <!-- 仅当 emergingTopics.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="emergingTopics.length" class="momentum-list">
            <el-tooltip v-for="(topic, index) in emergingTopics" :key="topic.keyword" placement="right" effect="light" popper-class="keyword-momentum-tooltip">
              <template #content>
                <div class="sparkline-tooltip">
                  <strong>{{ topic.keyword }} · 近 4 周词频</strong>
                  <svg viewBox="0 0 120 30" role="img" aria-label="近四周词频趋势"><polyline :points="sparklinePoints(topic)" fill="none" stroke="#dc2626" stroke-width="2" /></svg>
                  <small>{{ topicSparkline(topic).join(' · ') }}</small>
                </div>
              </template>
              <!-- 点击当前按钮时执行 selectTopic(topic.keyword)。 -->
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
          <!-- 仅当 decliningTopics.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="decliningTopics.length" class="momentum-list">
            <el-tooltip v-for="(topic, index) in decliningTopics" :key="topic.keyword" placement="left" effect="light" popper-class="keyword-momentum-tooltip">
              <template #content>
                <div class="sparkline-tooltip">
                  <strong>{{ topic.keyword }} · 近 4 周词频</strong>
                  <svg viewBox="0 0 120 30" role="img" aria-label="近四周词频趋势"><polyline :points="sparklinePoints(topic)" fill="none" stroke="#0891b2" stroke-width="2" /></svg>
                  <small>{{ topicSparkline(topic).join(' · ') }}</small>
                </div>
              </template>
              <!-- 点击当前按钮时执行 selectTopic(topic.keyword)。 -->
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
        <div class="card-title">话题报道量与互动关注分布</div>
        <div class="observation-filters">
          <!-- 将当前控件的输入或选择同步到 observationPlatform，供查询或提交时读取。 -->
          <el-select v-model="observationPlatform" aria-label="观察平台"><el-option v-for="platform in NEWS_PLATFORMS" :key="platform" :label="platformName(platform)" :value="platform" /></el-select>
          <!-- 将当前控件的输入或选择同步到 observationMetric，供查询或提交时读取。 -->
          <el-select v-model="observationMetric" aria-label="互动指标"><el-option v-for="metric in INTERACTION_METRICS" :key="metric.field" :label="metric.label" :value="metric.field" /></el-select>
          <!-- 将当前控件的输入或选择同步到 observationRange，供查询或提交时读取。 -->
          <el-date-picker v-model="observationRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" />
        </div>
        <p class="observation-note">{{ observationScopeText }} · {{ observationRecords.length }} 篇新闻 · {{ observationTopics.length }} 个词簇（至少 3 篇报道）。图中各象限按报道量取前 5 个，共 {{ quadrantTopics.length }} 个。数据范围：最近 {{ rows.length }} 条新闻（上限 10000 条），非全量报道。</p>
        <p class="observation-note">分界中位数：报道量 {{ decimal(supplyMedian) }} 篇 / 单篇{{ metricLabel }} {{ decimal(demandMedian) }} 次；基于当前范围内全部互动有效词簇。高低仅为样本内比较，不代表公众需求或风险等级。</p>
        <!-- 将 keywordQuadrantOption 交给图表组件绘制。 -->
        <ChartBox class="keyword-quadrant-chart" :option="keywordQuadrantOption" @chart-click="handleQuadrantClick" />
        <p class="observation-note">互动缺失不计入平均数；入库值为 0 的按 0 计算，历史采集若已将缺失写成 0，则无法追溯区分。{{ missingObservationTopics.length }} 个词簇无有效互动，未绘制；不同词簇可能包含同一篇新闻。</p>
        <div class="observation-topic-list"><button v-for="topic in quadrantTopics" :key="topic.keyword" type="button" @click="openTopicEvidence(topic)"><b>{{ topic.keyword }}</b><span>{{ topic.content_count }} 篇 / {{ topic.valid_count }} 篇互动有效</span><span>{{ observationPosition(topic, supplyMedian, demandMedian) }}</span></button></div>
      </section>

      <section class="analysis-card table-card keyword-table-card">
        <div class="card-title">主题关键词观察清单 <span>五平台样本概览</span></div>
        <div class="keyword-table-tools">
          <!-- 将“按关键词搜索”的输入或选择同步到 tableQuery，供查询或提交时读取。 -->
          <el-input v-model="tableQuery" placeholder="按关键词搜索" clearable />
          <!-- 将当前控件的输入或选择同步到 tableCategory，供查询或提交时读取。 -->
          <el-select v-model="tableCategory" aria-label="按类别筛选">
            <el-option label="全部类别" value="全部" />
            <!-- 根据 category in categoryNames 循环生成条目，模板中的字段读取当前条目数据。 -->
            <el-option v-for="category in categoryNames" :key="category" :label="category" :value="category" />
          </el-select>
          <!-- 将当前控件的输入或选择同步到 tableSort，供查询或提交时读取。 -->
          <el-select v-model="tableSort" aria-label="排序方式">
            <el-option label="累计热度降序" value="heat" />
            <el-option label="内容量降序" value="content" />
            <el-option label="覆盖平台降序" value="platform" />
            <el-option label="关键词升序" value="keyword" />
          </el-select>
        </div>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
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
              <th>当前平台时段观察</th>
            </tr>
          </thead>
          <tbody>
            <!-- 按 topic in pagedTopics 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="topic in pagedTopics" :key="topic.keyword" @click="selectTopic(topic.keyword)">
              <td :title="topic.cluster_label"><b>{{ topic.keyword }}</b><small>{{ topic.category_top }}</small></td>
              <td>{{ topic.content_count }}</td>
              <td>{{ topic.platform_count }}</td>
              <td>{{ formatNumber(topic.heat_total) }}</td>
              <td><span class="conclusion-cell" :title="topic.conclusion">{{ topic.conclusion }}</span></td>
            </tr>
            <!-- 按 index in emptyTopicRows 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="index in emptyTopicRows" :key="`topic-empty-${index}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
        <el-pagination class="table-pagination" v-model:current-page="keywordPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredTopics.length" />
      </section>
    </div>
    <!-- 将弹框显隐与 evidenceVisible 绑定，集中展示当前操作的详情或表单。 -->
    <el-dialog v-model="evidenceVisible" :title="evidenceTopic ? `${evidenceTopic.keyword} · 新闻与计算依据` : '计算依据'" width="min(900px, 94vw)">
      <template v-if="evidenceTopic">
        <p>{{ evidenceScope }} · 主题词：{{ evidenceTopic.cluster_label }}</p>
        <p>报道量 {{ evidenceTopic.content_count }} 篇；互动有效 {{ evidenceTopic.valid_count }} 篇，缺失 {{ evidenceTopic.missing_count }} 篇。</p>
        <!-- 仅当 evidenceTopic.valid_count 成立时显示此处内容，条件变化时自动更新。 -->
        <p v-if="evidenceTopic.valid_count">平均{{ evidenceMetricLabel }} = {{ decimal(evidenceTopic.interaction_total) }} 次 ÷ {{ evidenceTopic.valid_count }} 篇 = {{ decimal(evidenceTopic.average_interaction) }} 次/篇。</p>
        <p v-else>无有效互动值，无法计算平均数或划分象限。</p>
        <p>{{ evidencePosition }}；报道量中位数 {{ decimal(evidenceCountMedian) }} 篇，平均互动中位数 {{ decimal(evidenceInteractionMedian) }} 次/篇。</p>
        <div class="topic-evidence-list"><article v-for="row in evidenceArticles" :key="row.id"><a v-if="safeSourceUrl(row.source.source_url)" :href="safeSourceUrl(row.source.source_url)" target="_blank" rel="noopener noreferrer">{{ row.source.title || row.source.content_id }}</a><b v-else>{{ row.source.title || row.source.content_id }}</b><small>{{ row.day }} · {{ evidenceMetricLabel }} {{ decimal(numericInteraction(row.source[evidenceField])) }} 次</small><span v-if="!safeSourceUrl(row.source.source_url)">未提供有效原文链接</span></article></div>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
        <el-pagination v-model:current-page="evidencePage" :page-size="10" layout="total, prev, pager, next" :total="evidenceTopic.articles.length" />
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../../components/common/ChartBox.vue'
import { clearGetCache, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'
import { INTERACTION_METRICS, isContextOnlyKeyword, numericInteraction, observeTopics, observationPosition, representativeTopics, topicMedian } from '../../utils/topicObservation'

type Granularity = 'day' | 'week'

// 限定新闻分析参与平台，防止把微博评论混入新闻正文统计。
const NEWS_PLATFORMS = ['SOHU_NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SINA_NEWS', 'THE_PAPER']
// 固定各类别颜色，使图例与各图表的标识一致。
const CATEGORY_COLORS: Record<string, string> = {
  财经: '#2563eb', 政治: '#7c3aed', 文娱: '#db2777', 综合: '#64748b', 社会: '#ea580c', 体育: '#16a34a', 科技: '#0f766e'
}
// 固定主题分类的中文名和图例排列顺序。
const CATEGORY_NAMES = ['财经', '政治', '文娱', '综合', '社会', '体育', '科技']
// 给主题趋势提供固定配色，选中主题后不重新分配颜色。
const TOPIC_LINE_COLORS = ['#2563eb', '#0f766e', '#d97706', '#7c3aed', '#dc2626', '#0891b2', '#16a34a', '#be123c']
// 保存不参与主题提取的词，关键词拆分后据此过滤。
const STOPWORDS = new Set(['没有', '为什么', '就是', '不是', '这个', '发文', '感谢', '不要', '除了', '还有', '运气', '切片', '包场', '效率', '方面', '理性', '这一', '导弹', '群众', '效果', '美军', '经验总结', '习近平'])

// 控制刷新按钮的加载状态，请求结束后解除。
const refreshing = ref(false)
// 保存接口返回的记录，作为筛选、聚合和详情展示的数据源。
const rows = ref<any[]>([])
// 记录当前强调的主题，再次点击同一主题时取消选择。
const selectedTopic = ref('')
// 记录主题趋势采用日粒度还是周粒度。
const trendGranularity = ref<Granularity>('week')
// 保存主题表格检索词，与图表筛选分别维护。
const tableQuery = ref('')
// 保存主题表格的类别筛选条件。
const tableCategory = ref('全部')
// 记录主题表格按热度、内容量、平台数或名称排序的方式。
const tableSort = ref('heat')
// 保存主题表当前页码，筛选或排序变化时重置。
const keywordPage = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 8
// 保存词云中被隐藏的类别，图例点击时增删集合成员。
const hiddenCloudCategories = ref(new Set<string>())
// 限定互动观察的平台，避免混用不同平台的互动口径。
const observationPlatform = ref('TENCENT_NEWS')
// 保存当前观察的单一互动字段，例如点赞数。
const observationMetric = ref('like_count')
// 保存互动观察日期范围，限定参与象限比较的文章。
const observationRange = ref<string[]>([])
// 控制计算依据弹框是否显示。
const evidenceVisible = ref(false)
// 保存当前查看依据的主题及其命中文章。
const evidenceTopic = ref<any>(null)
// 保存证据原文列表当前页码。
const evidencePage = ref(1)
// 保存打开依据时的平台和日期说明。
const evidenceScope = ref('')
// 保存打开依据时的互动字段，避免后续筛选改变弹框计算口径。
const evidenceField = ref('like_count')
// 保存互动字段的中文名，供弹框公式说明使用。
const evidenceMetricLabel = ref('点赞')
// 保存本次依据采用的报道量中位数。
const evidenceCountMedian = ref<number | null>(null)
// 保存本次依据采用的平均互动量中位数。
const evidenceInteractionMedian = ref<number | null>(null)
// 保存主题相对两项中位数的中文位置说明。
const evidencePosition = ref('')
// 从主题命中文章中截取当前页十条证据。
const evidenceArticles = computed(() => evidenceTopic.value?.articles.slice((evidencePage.value - 1) * 10, evidencePage.value * 10) || [])

// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME
// 将固定类别列表提供给模板循环展示。
const categoryNames = computed(() => CATEGORY_NAMES)
// 将类别中文名与固定颜色组合成词云图例。
const categoryLegend = computed(() => CATEGORY_NAMES.map(name => ({ name, color: CATEGORY_COLORS[name] })))

// 使用平台与内容编号联合去重，再统一类别、日期和关键词，保留原记录供证据回查。
const contentRecords = computed(() => {
  // 使用联合编号建立映射，同平台重复内容只保留第一次出现的记录。
  const uniqueRows = new Map<string, any>()
  rows.value.forEach((row, index) => {
    const id = `${row.platform}:${row.content_id || `row-${index}`}`
    if (!uniqueRows.has(id)) uniqueRows.set(id, row)
  })
  // 将原始字段转为分析统一结构，同时保留 source 供详情回查。
  return [...uniqueRows.values()].map((row, index) => ({
    id: `${row.platform}:${row.content_id || `row-${index}`}`,
    source: row,
    platform: String(row.platform || ''),
    category: categoryName(row.category_label || row.category),
    day: dayKey(row.publish_time),
    keywords: splitKeywords(row.keywords),
    heat: interactionHeat(row),
    author: String(row.author_name || '').trim()
  })).filter(row => NEWS_PLATFORMS.includes(row.platform) && row.keywords.length > 0)
})

// 先按关键词统计命中文章数与互动总量，为主题代表词和聚类频率提供依据。
const rawKeywordStats = computed(() => {
  // 为每个关键词建立一个计数器，同篇文章已在拆词阶段去重。
  const grouped = new Map<string, any>()
  for (const row of contentRecords.value) {
    for (const keyword of row.keywords) {
      const item = grouped.get(keyword) || { keyword, content_count: 0, heat_total: 0 }
      // 每命中一篇文章增加一次频次，并累计该文章的互动总量。
      item.content_count += 1
      item.heat_total += row.heat
      grouped.set(keyword, item)
    }
  }
  return [...grouped.values()].sort((left, right) => right.content_count - left.content_count || right.heat_total - left.heat_total)
})

// 统计文章内关键词共现，以共现次数和重叠率筛选关系，再用并查集合并主题。
const topicClusters = computed(() => {
  // 并查集初始时每个关键词各自作为一个主题的根节点。
  const parent = new Map<string, string>()
  rawKeywordStats.value.forEach(item => parent.set(item.keyword, item.keyword))
  // 递归寻找主题根词，随后压缩路径以减少下次查找次数。
  const find = (keyword: string): string => {
    const current = parent.get(keyword) || keyword
    if (current === keyword) return keyword
    const root = find(current)
    parent.set(keyword, root)
    return root
  }
  // 两个词属于不同主题时，把右侧主题的根挂到左侧主题下。
  const unite = (left: string, right: string) => {
    const leftRoot = find(left)
    const rightRoot = find(right)
    if (leftRoot !== rightRoot) parent.set(rightRoot, leftRoot)
  }
  // 统计同篇文章内每对关键词共同出现的文章数。
  const pairCounts = new Map<string, number>()
  for (const row of contentRecords.value) {
    // 先去重排序，确保同一词对的顺序一致，不重复累计反向组合。
    const members = [...new Set(row.keywords)].sort()
    for (let left = 0; left < members.length; left += 1) {
      for (let right = left + 1; right < members.length; right += 1) {
        // 使用空字符连接两个词，避免普通文本连接产生词对编号冲突。
        const key = `${members[left]}\u0000${members[right]}`
        pairCounts.set(key, (pairCounts.get(key) || 0) + 1)
      }
    }
  }
  // 建立词频索引，后续用较低词频作为共现覆盖率分母。
  const frequencies = new Map(rawKeywordStats.value.map(item => [item.keyword, item.content_count]))
  // 样本较多时提高最低共现次数，减少偶然共现造成的主题合并。
  const minimumCooccurrence = contentRecords.value.length >= 80 ? 3 : 2
  for (const [pair, count] of pairCounts) {
    // 只有共现次数达标且覆盖较少频词至少六成文章时才合并主题。
    const [left, right] = pair.split('\u0000')
    const overlap = count / Math.max(1, Math.min(frequencies.get(left) || 0, frequencies.get(right) || 0))
    if (count >= minimumCooccurrence && overlap >= .6) unite(left, right)
  }
  // 按最终根词把关键词分组，形成每个主题的成员列表。
  const clusters = new Map<string, string[]>()
  for (const keyword of parent.keys()) {
    const root = find(keyword)
    const members = clusters.get(root) || []
    members.push(keyword)
    clusters.set(root, members)
  }
  // 成员先按频次、再按热度排序，最前面的词作为主题代表词。
  const rawByKeyword = new Map(rawKeywordStats.value.map(item => [item.keyword, item]))
  return [...clusters.values()].map(members => {
    const sorted = [...members].sort((left, right) => Number(rawByKeyword.get(right)?.content_count || 0) - Number(rawByKeyword.get(left)?.content_count || 0)
      || Number(rawByKeyword.get(right)?.heat_total || 0) - Number(rawByKeyword.get(left)?.heat_total || 0)
      || left.localeCompare(right))
      return { keyword: sorted[0], members: sorted, cluster_label: sorted.slice(0, 5).join('、') }
  })
})

// 同篇文章命中同一主题的多个成员词时只计一次，同时累计平台、类别与日期分布。
const topicInsights = computed(() => {
  // 一个索引将成员词关联到主题，另一个保存主题累计统计。
  const topicByKeyword = new Map<string, string>()
  const grouped = new Map<string, any>()
  topicClusters.value.forEach(cluster => {
    // 每个主题从零开始累计数量；平台、作者使用集合避免重复计数。
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
    // 所有成员词指向同一主题代表词，便于按文章统一归组。
    cluster.members.forEach(keyword => topicByKeyword.set(keyword, cluster.keyword))
  })
  for (const row of contentRecords.value) {
    // 同一文章即使命中一个主题多个成员词，也只在该主题计数一次。
    const topicsInContent = new Set(row.keywords.map(keyword => topicByKeyword.get(keyword)).filter(Boolean) as string[])
    for (const topic of topicsInContent) {
      const item = grouped.get(topic)
      // 累计主题报道量与互动量，并记录文章来自的平台。
      item.content_count += 1
      item.heat_total += row.heat
      item.platforms.add(row.platform)
      // 分别维护平台、类别和日期计数，供分布图及趋势图读取。
      item.platform_counts.set(row.platform, (item.platform_counts.get(row.platform) || 0) + 1)
      item.category_counts.set(row.category, (item.category_counts.get(row.category) || 0) + 1)
      if (row.day) item.day_counts.set(row.day, (item.day_counts.get(row.day) || 0) + 1)
      if (row.author) item.authors.add(row.author)
    }
  }
  // 将集合和映射转换为可展示的数量与普通对象，最后按热度和报道量排序。
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

// 排除仅表示国家语境的词，保留具体新闻主题。
const topTopics = computed(() => topicInsights.value.filter(topic => !isContextOnlyKeyword(topic.keyword)))
// 去除被隐藏类别后，截取前二十五个主题绘制词云。
const cloudTopics = computed(() => topTopics.value.filter(topic => !hiddenCloudCategories.value.has(topic.category_top)).slice(0, 25))
// 默认显示前八个主题择的主题替换末位；额外选以保持可见。
const trendTopics = computed(() => {
  const primary = topTopics.value.slice(0, 8)
  const selected = topTopics.value.find(topic => topic.keyword === selectedTopic.value)
  return selected && !primary.some(topic => topic.keyword === selected.keyword)
    ? [selected, ...primary.slice(0, 7)]
    : primary
})
// 取前十个主题比较平台报道分布。
const platformTopics = computed(() => topTopics.value.slice(0, 10))

// 按日或周整理文章日期，去重排序后作为趋势横轴。
const trendBuckets = computed(() => [...new Set(contentRecords.value.map(row => bucketKey(row.day, trendGranularity.value)).filter(Boolean))].sort())
// 按每个时间桶统计主题文章数，并关联主题固定颜色。
const trendSeries = computed(() => trendTopics.value.map(topic => ({
  name: topic.keyword,
  color: TOPIC_LINE_COLORS[topTopics.value.findIndex(item => item.keyword === topic.keyword) % TOPIC_LINE_COLORS.length],
  data: trendBuckets.value.map(bucket => bucketCount(topic, bucket, trendGranularity.value))
})))
// 生成主题折线图，选中时只强调对应主题，不隐藏其他系列。
const keywordTrendOption = computed(() => ({
  color: TOPIC_LINE_COLORS,
  // 同一时间桶的各主题数量集中展示在提示中，数值单位为文章篇数。
  tooltip: { trigger: 'axis', formatter: (params: any[]) => `${params[0]?.axisValue || ''}<br/>${params.map(item => `${item.marker}${item.seriesName}：<b>${item.value}</b> 篇`).join('<br/>')}` },
  legend: { top: 0, type: 'scroll', data: trendSeries.value.map(item => item.name), textStyle: { color: '#475569' } },
  grid: { left: 44, right: 16, top: 44, bottom: 32 },
  xAxis: { type: 'category', boundaryGap: false, data: trendBuckets.value, axisLabel: { color: '#64748b', hideOverlap: true } },
  yAxis: { type: 'value', minInterval: 1, max: (value: any) => Math.max(1, Math.ceil(value.max * 1.2)), axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#e5eaf1' } } },
  // 每个主题生成一条折线，坐标和颜色沿用预先整理的主题序列。
  series: trendSeries.value.map(item => {
    return {
      name: item.name,
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: selectedTopic.value === item.name ? 7 : 4,
      zlevel: 0,
      z: 3,
      lineStyle: { color: item.color, width: selectedTopic.value === item.name ? 4 : 2, opacity: 1 },
      itemStyle: { color: item.color, opacity: 1 },
      emphasis: { focus: 'none', lineStyle: { opacity: 1 }, itemStyle: { opacity: 1 } },
      blur: { lineStyle: { opacity: 1 }, itemStyle: { opacity: 1 } },
      data: item.data
    }
  })
}))

// 将主题和平台作为坐标，用文章数量生成热力图。
const keywordPlatformOption = computed(() => {
  // 以所有主题平台单元格中的最大内容量作为热力图颜色映射上限。
  const max = Math.max(1, ...platformTopics.value.flatMap(topic => NEWS_PLATFORMS.map(platform => Number(topic.platform_counts[platform] || 0))))
  return {
    tooltip: { position: 'top', formatter: (params: any) => `${platformName(NEWS_PLATFORMS[params.value[1]])}<br/>${platformTopics.value[params.value[0]]?.keyword || ''}<br/>内容量：<b>${params.value[2]}</b>` },
    grid: { left: 76, right: 16, top: 10, bottom: 78 },
    xAxis: { type: 'category', data: platformTopics.value.map(item => item.keyword), axisLabel: { color: '#475569', rotate: 28, interval: 0 } },
    yAxis: { type: 'category', data: NEWS_PLATFORMS.map(platformName), axisLabel: { color: '#475569' } },
    visualMap: { min: 0, max, calculable: false, orient: 'horizontal', left: 'center', bottom: 2, itemWidth: 12, itemHeight: 116, textStyle: { color: '#64748b' }, inRange: { color: ['#f1f5f9', '#bfdbfe', '#2563eb'] } },
    // 每个单元格使用主题下标、平台下标和文章数三个值表示，缺少计数时取零。
    series: [{ type: 'heatmap', data: platformTopics.value.flatMap((topic, x) => NEWS_PLATFORMS.map((platform, y) => [x, y, Number(topic.platform_counts[platform] || 0)])), label: { show: true, color: '#1e293b', formatter: (params: any) => params.value[2] || '' }, emphasis: { itemStyle: { shadowBlur: 8, shadowColor: 'rgba(37,99,235,.25)' } } }]
  }
})

// 取文章中的最新日期，作为近期变化的统计基准。
const latestAnalysisDay = computed(() => {
  const days = [...new Set(contentRecords.value.map(row => row.day).filter(Boolean))].sort()
  return days[days.length - 1] || ''
})
// 以数据集中最新日期为基准，对比最近七天与此前七天，不依赖浏览器当天日期。
const momentumTopics = computed(() => topTopics.value.map(topic => {
  // 两个比较区间均为七天，且都以数据最新日期而不是系统当前日期为基准。
  const latest = latestAnalysisDay.value
  const current_count = latest ? dayRangeCount(topic, shiftDay(latest, -6), latest) : 0
  const previous_count = latest ? dayRangeCount(topic, shiftDay(latest, -13), shiftDay(latest, -7)) : 0
  // 前期有报道则计算增长率；前期为零但本期有报道时标记为新增增长。
  const growth = previous_count > 0 ? (current_count - previous_count) / previous_count : (current_count > 0 ? 1 : 0)
  // 新增主题单独提高排序权重，同时用本期数量区分新增主题先后。
  const growth_sort = previous_count > 0 ? growth : (current_count > 0 ? 10 + current_count / 1000 : 0)
  return { ...topic, current_count, previous_count, growth, growth_sort }
}))
// 筛选当前有报道且增长为正的主题，排序取前十个。
const emergingTopics = computed(() => momentumTopics.value.filter(topic => topic.current_count > 0 && topic.growth > 0)
  .sort((left, right) => right.growth_sort - left.growth_sort || right.current_count - left.current_count).slice(0, 10))
// 筛选此前有报道而当前下降的主题，取降幅明显的前十个。
const decliningTopics = computed(() => momentumTopics.value.filter(topic => topic.previous_count > 0 && topic.growth < 0)
  .sort((left, right) => left.growth - right.growth || left.current_count - right.current_count).slice(0, 10))

// 提取日期并去重排序，确定时间筛选的可用边界。
const availableDates = computed(() => [...new Set(contentRecords.value.map(row => row.day).filter(Boolean))].sort())
// 尚未选择时段时，用数据最早和最新日期初始化观察范围。
function setObservationRange() {
  if (observationRange.value.length === 2 || !availableDates.value.length) return
  const latest = availableDates.value.at(-1) as string
  observationRange.value = [availableDates.value[0], latest]
}
// 互动观察限定单一平台、时段与互动字段，避免把不同平台的互动口径直接混合。
const observationRecords = computed(() => {
  const [start, end] = observationRange.value
  return contentRecords.value.filter(row => row.platform === observationPlatform.value && (!start || !end || (row.day >= start && row.day <= end)))
})
// 至少命中三篇文章才参与观察；互动缺失与有效零值分别处理。
const observationTopics = computed(() => observeTopics(topTopics.value, observationRecords.value, observationMetric.value).filter(topic => topic.content_count >= 3))
// 找出平均互动量缺失的主题，单独提示数据不足。
const missingObservationTopics = computed(() => observationTopics.value.filter(topic => topic.average_interaction === null))
// 用互动有效主题的报道量计算横轴基准中位数。
const supplyMedian = computed(() => topicMedian(observationTopics.value.filter(topic => topic.average_interaction !== null).map(topic => topic.content_count)))
// 排除缺失均值后计算平均互动量中位数。
const demandMedian = computed(() => topicMedian(observationTopics.value.map(topic => topic.average_interaction).filter((value): value is number => value !== null)))
// 在四个象限分别选择代表主题，避免只显示高报道量主题。
const quadrantTopics = computed(() => representativeTopics(observationTopics.value, supplyMedian.value, demandMedian.value))
// 按字段找到互动中文名，未匹配时使用通用名称。
const metricLabel = computed(() => INTERACTION_METRICS.find(item => item.field === observationMetric.value)?.label || '互动')
// 拼接平台、起止日期和互动指标，形成证据范围说明。
const observationScopeText = computed(() => `${platformName(observationPlatform.value)} · ${observationRange.value[0] || '-'} 至 ${observationRange.value[1] || '-'} · 单一${metricLabel.value}`)
// 以报道量和单篇互动为横纵轴，以两项中位数划分象限。
const keywordQuadrantOption = computed(() => {
  // 根据代表主题和中位数确定横纵轴上限，至少为一避免生成零跨度坐标轴。
  const topics = quadrantTopics.value
  const maxSupply = Math.max(1, supplyMedian.value || 0, ...topics.map(topic => topic.content_count))
  const maxDemand = Math.max(1, demandMedian.value || 0, ...topics.map(topic => topic.average_interaction || 0))
  return {
    tooltip: {
      formatter: (params: any) => {
        // 从当前散点读取主题统计；无数据点时不显示提示。
        const topic = params.data
        if (!topic) return ''
        return `<b>${escapeHtml(topic.name)}</b><br/>报道量：<b>${topic.content_count}</b> 篇<br/>${metricLabel.value}有效：<b>${topic.valid_count}</b> 篇<br/>平均${metricLabel.value}：<b>${decimal(topic.average_interaction)}</b> 次/篇<br/>${observationPosition(topic, supplyMedian.value, demandMedian.value)}`
      }
    },
    grid: { left: 78, right: 24, top: 54, bottom: 76, containLabel: true },
    xAxis: {
      type: 'value',
      name: '报道量（篇）',
      nameLocation: 'middle',
      nameGap: 34,
      nameTextStyle: { color: '#334155', fontWeight: 700 },
      max: Math.ceil(maxSupply * 1.15),
      axisLabel: { color: '#475569', margin: 10 },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    yAxis: {
      type: 'value',
      name: `单篇${metricLabel.value}（次）`,
      nameLocation: 'middle',
      nameGap: 52,
      nameTextStyle: { color: '#334155', fontWeight: 700 },
      max: Math.ceil(maxDemand * 1.15),
      axisLabel: { color: '#475569', formatter: (value: number) => formatNumber(value) },
      splitLine: { lineStyle: { color: '#e2e8f0' } }
    },
    graphic: topics.length ? [
      { type: 'text', left: '9%', top: 5, style: { text: '报道较少 · 互动较高', fill: '#b91c1c', fontSize: 12, fontWeight: 700 } },
      { type: 'text', right: '7%', top: 5, style: { text: '报道较多 · 互动较高', fill: '#166534', fontSize: 12, fontWeight: 700 } },
      { type: 'text', left: '9%', bottom: 3, style: { text: '报道较少 · 互动较低', fill: '#64748b', fontSize: 12, fontWeight: 700 } },
      { type: 'text', right: '7%', bottom: 3, style: { text: '报道较多 · 互动较低', fill: '#b45309', fontSize: 12, fontWeight: 700 } }
    ] : [{ type: 'text', left: 'center', top: 'middle', style: { text: '当前范围暂无互动有效的主题数据', fill: '#64748b', fontSize: 14 } }],
    // 按类别拆分散点系列，每个点保存报道量、平均互动和作者数量。
    series: CATEGORY_NAMES.map((category, index) => ({
      name: category,
      type: 'scatter',
      data: topics.filter(topic => topic.category_top === category).map(topic => ({
        name: topic.keyword,
        value: [topic.content_count, topic.average_interaction, topic.author_count],
        content_count: topic.content_count,
        average_interaction: topic.average_interaction,
        valid_count: topic.valid_count,
        author_count: topic.author_count,
        cluster_label: topic.cluster_label
      })),
      symbolSize: 14,
      itemStyle: { color: CATEGORY_COLORS[category], opacity: .78, borderColor: '#fff', borderWidth: 1 },
      label: { show: true, formatter: '{b}', position: 'top', fontSize: 11 },
      labelLayout: { hideOverlap: true },
      emphasis: { scale: true, itemStyle: { opacity: 1, borderWidth: 2 }, label: { show: true, formatter: (params: any) => params.data.name, position: 'top', color: '#0f172a', fontSize: 12, fontWeight: 700 } },
      // 中位数参考线只在首个系列绘制一次，避免多个系列重复叠加。
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
// 同时应用检索、类别和排序条件，得到表格完整筛选结果。
const filteredTopics = computed(() => {
  // 检索统一转小写，同时匹配代表词和成员词。
  const query = tableQuery.value.trim().toLowerCase()
  return topTopics.value.filter(topic => {
    const matchesQuery = !query || `${topic.keyword} ${topic.member_keywords.join(' ')}`.toLowerCase().includes(query)
    return matchesQuery && (tableCategory.value === '全部' || topic.category_top === tableCategory.value)
  }).sort((left, right) => {
    // 按当前排序选择决定比较字段，同值时再用热度或内容量稳定排序。
    if (tableSort.value === 'content') return right.content_count - left.content_count || right.heat_total - left.heat_total
    if (tableSort.value === 'platform') return right.platform_count - left.platform_count || right.heat_total - left.heat_total
    if (tableSort.value === 'keyword') return left.keyword.localeCompare(right.keyword)
    return right.heat_total - left.heat_total || right.content_count - left.content_count
  })
})
// 截取主题表当前页，并为各主题附上互动观察结论。
const pagedTopics = computed(() => filteredTopics.value.slice((keywordPage.value - 1) * pageSize, keywordPage.value * pageSize).map(topic => ({ ...topic, conclusion: topicConclusion(topic) })))
// 计算需要补齐的空行，防止表格不足一页时高度变化。
const emptyTopicRows = computed(() => Math.max(0, pageSize - pagedTopics.value.length))

watch([tableQuery, tableCategory, tableSort], () => { keywordPage.value = 1 })
useDatabaseAutoRefresh(load)

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

async function load() {
  rows.value = await getData('/analysis/keyword-analysis', true)
  selectedTopic.value = ''
  setObservationRange()
}

// 按标点与空白拆分关键词，再过滤无效词。
function splitKeywords(value: any) {
  return String(value || '').split(/[,，、/|；;\s]+/).map(item => item.trim()).filter(validKeyword)
}
// 排除短词、停用词、国家语境词、单字母与纯数字词。
function validKeyword(keyword: string) {
  return keyword.length >= 2 && !STOPWORDS.has(keyword) && !isContextOnlyKeyword(keyword) && !/^[a-zA-Z]$/.test(keyword) && !/^\d+$/.test(keyword)
}
// 累加点赞、评论、转发和收藏，缺失字段按零计入。
function interactionHeat(row: any) {
  return ['like_count', 'comment_count', 'repost_count', 'favorite_count'].reduce((total, field) => total + Number(row[field] || 0), 0)
}
// 提取发布时间的年月日，作为文章的日期键。
function dayKey(value: any) { return value ? String(value).replace('T', ' ').slice(0, 10) : '' }
// 日粒度沿用原日期，周粒度将日期归入以周一为起点的时间桶。
function bucketKey(day: string, granularity: Granularity) {
  if (!day) return ''
  if (granularity === 'day') return day
  const date = new Date(`${day}T00:00:00`)
  const offset = (date.getDay() + 6) % 7
  date.setDate(date.getDate() - offset)
  return date.toISOString().slice(0, 10)
}
// 累计属于指定日或周时间桶的主题文章数。
function bucketCount(topic: any, bucket: string, granularity: Granularity) {
  return Object.entries(topic.day_counts || {}).reduce((total, [day, count]) => total + (bucketKey(day, granularity) === bucket ? Number(count) : 0), 0)
}
// 通过 UTC 日期运算增减天数，避免时区影响日期边界。
function shiftDay(day: string, offset: number) {
  const [year, month, date] = day.split('-').map(Number)
  const value = new Date(Date.UTC(year, month - 1, date))
  value.setUTCDate(value.getUTCDate() + offset)
  return value.toISOString().slice(0, 10)
}
// 累计起止日期闭区间内的主题文章数。
function dayRangeCount(topic: any, start: string, end: string) {
  return Object.entries(topic.day_counts || {}).reduce((total, [day, count]) => total + (day >= start && day <= end ? Number(count) : 0), 0)
}
// 以数据最新日期为基准，统计四个七天区间的报道量。
function topicSparkline(topic: any) {
  if (!latestAnalysisDay.value) return [0, 0, 0, 0]
  return [3, 2, 1, 0].map(index => {
    const end = shiftDay(latestAnalysisDay.value, -index * 7)
    return dayRangeCount(topic, shiftDay(end, -6), end)
  }).reverse()
}
// 按最大报道量归一化，把四段数值转换为迷你折线坐标。
function sparklinePoints(topic: any) {
  const values = topicSparkline(topic)
  const maximum = Math.max(1, ...values)
  return values.map((value, index) => `${index * 40},${27 - Math.round(value / maximum * 22)}`).join(' ')
}
// 前期为零而本期有记录时显示新增，其余情况显示变化幅度百分比。
function growthLabel(topic: any) {
  return topic.previous_count === 0 && topic.current_count > 0 ? '新增' : `${Math.abs(Number(topic.growth || 0) * 100).toFixed(0)}%`
}
// 查询主题在当前互动观察中的位置，样本不足时说明未参与比较。
function topicConclusion(topic: any) {
  const observed = observationTopics.value.find(item => item.keyword === topic.keyword)
  if (observed) return observationPosition(observed, supplyMedian.value, demandMedian.value)
  return '当前平台时段不足 3 篇，未参与比较'
}
// 按数量降序找出最多的一项，同值按名称排序，无记录时返回综合。
function topKey(values: Map<string, number>) { return [...values.entries()].sort((left, right) => right[1] - left[1] || left[0].localeCompare(right[0]))[0]?.[0] || '综合' }
// 优先使用已有中文类别，否则翻译类别编码，微博评论不展示新闻类别。
function categoryName(value: any) {
  const text = String(value || '').trim()
  return ({ finance: '财经', politics: '政治', culture: '文娱', general: '综合', society: '社会', sports: '体育', technology: '科技' } as Record<string, string>)[text] || (CATEGORY_COLORS[text] ? text : '综合')
}
// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) { return ({ SOHU_NEWS: '搜狐新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' } as Record<string, string>)[value] || '其他平台' }
// 使用中文区域规则格式化整数，缺少数值时按零处理。
function formatNumber(value: number) { return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(Number(value || 0)) }
// 有效数字保留一位小数，空值或非法数字显示短横线。
function decimal(value: unknown) { return value === null || value === undefined || !Number.isFinite(Number(value)) ? '-' : Number(value).toFixed(1) }
// 转义 HTML 特殊字符，防止图表提示将主题名解释为标签。
function escapeHtml(value: unknown) { return String(value || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;') }
// 仅返回 HTTP(S) 原文地址，其他协议和空值返回空字符串。
function safeSourceUrl(value: unknown) { const url = String(value || ''); return /^https?:\/\//i.test(url) ? url : '' }
// 打开证据时冻结当前观察范围、字段和中位数，确保弹框说明与点击时的结论一致。
function openTopicEvidence(topic: any) {
  evidenceTopic.value = topic; evidencePage.value = 1; evidenceField.value = observationMetric.value; evidenceMetricLabel.value = metricLabel.value
  evidenceScope.value = observationScopeText.value; evidenceCountMedian.value = supplyMedian.value; evidenceInteractionMedian.value = demandMedian.value
  evidencePosition.value = observationPosition(topic, supplyMedian.value, demandMedian.value); evidenceVisible.value = true
}
// 点击新主题时选中，重复点击同一主题时取消强调。
function selectTopic(keyword: string) { selectedTopic.value = selectedTopic.value === keyword ? '' : keyword }
// 按散点名称找到主题，选中并打开对应原文证据。
function handleQuadrantClick(params: any) {
  const topic = observationTopics.value.find(item => item.keyword === params?.data?.name)
  if (topic) { selectTopic(topic.keyword); openTopicEvidence(topic) }
}
// 复制隐藏类别集合再增删目标类别，触发词云重新计算。
function toggleCloudCategory(category: string) {
  const next = new Set(hiddenCloudCategories.value)
  if (next.has(category)) next.delete(category)
  else next.add(category)
  hiddenCloudCategories.value = next
}
function cloudStyle(topic: any, index: number) {
  // 使用疏松的螺旋布局，避免中心拥挤并让词条保持在词云轮廓内。
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
.observation-filters { display: grid; grid-template-columns: 160px 120px minmax(240px, 1fr); gap: 10px; }
.observation-filters :deep(.el-date-editor) { width: 100%; }
.observation-note { margin: 8px 0; font-size: 12px; color: #52627a; line-height: 1.6; }
.observation-topic-list { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.observation-topic-list button { display: grid; gap: 4px; text-align: left; border: 0; border-bottom: 1px solid #e2e8f0; background: transparent; padding: 8px; cursor: pointer; color: #475569; font-size: 12px; overflow-wrap: anywhere; }
.observation-topic-list b { color: #0f766e; }
.topic-evidence-list { max-height: 420px; overflow: auto; }
.topic-evidence-list article { display: grid; gap: 5px; padding: 12px 0; border-bottom: 1px solid #e2e8f0; overflow-wrap: anywhere; }
.topic-evidence-list small { color: #64748b; }
@media (max-width: 600px) { .observation-filters { grid-template-columns: 1fr 1fr; } .observation-filters :deep(.el-date-editor) { grid-column: 1 / -1; } .observation-topic-list { grid-template-columns: 1fr; } }
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
