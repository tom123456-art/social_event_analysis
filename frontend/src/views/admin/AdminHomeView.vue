<template>
  <!-- 汇总任务批次、平台数据与异常记录，各列表使用独立分页。 -->
  <div class="page admin-dense">
    <div class="page-head compact">
      <div>
        <h2>后台管理首页</h2>
      </div>
      <div>
        <!-- 点击“返回前台”时执行 router.push('/overview')。 -->
        <el-button @click="router.push('/overview')">返回前台</el-button>
        <!-- 点击“刷新数据库”时执行 refreshData。 -->
        <el-button type="primary" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <!-- 把统计指标传入共用指标组件，循环显示名称、数值与说明。 -->
    <MetricGrid :items="metrics" />

    <div class="grid admin-top" style="margin-top:12px">
      <div class="card">
      <div class="card-title">数据来源说明 <span>数据库快照</span></div>
        <div class="source-box">
          <b>当前数据来自外部 Python 采集与 ETL</b>
          <p>外部采集器生成原始数据文件；本系统接收文件并完成清洗、汇总，向前台提供分析结果。</p>
          <div class="source-grid">
            <div><span>源文件</span><strong>social_event_real.csv</strong></div>
            <div><span>虚拟机仓库</span><strong>/opt/apps/social-hotspot-analytics/warehouse</strong></div>
            <div><span>源记录</span><strong>{{ latestBatch?.source_count || 0 }} 条</strong></div>
            <div><span>有效记录</span><strong>{{ latestBatch?.valid_count || 0 }} 条</strong></div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">ETL成功率 <span>批次状态统计</span></div>
        <!-- 将 successOption 交给图表组件绘制。 -->
        <ChartBox size="md" :option="successOption" />
      </div>

      <div class="card">
        <div class="card-title">平台热度排行 <span>ADS来源汇总</span></div>
        <!-- 将 platformOption 交给图表组件绘制。 -->
        <ChartBox size="md" :option="platformOption" />
      </div>
    </div>

    <div class="grid admin-bottom" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">最近处理批次 <span>批次记录</span></div>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <table class="table fixed-rows">
          <thead><tr><th>批次号</th><th>事件</th><th>源路径</th><th>阶段</th><th>源记录</th><th>有效</th><th>状态</th></tr></thead>
          <tbody>
            <!-- 按 batch in pagedBatches 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="batch in pagedBatches" :key="batch.batch_id">
              <td>{{ batch.batch_id }}</td>
              <td>{{ eventName(batch.event_id) }}</td>
              <td>{{ batch.source_path }}</td>
              <td>{{ stageName(batch.current_stage) }}</td>
              <td>{{ batch.source_count }}</td>
              <td>{{ batch.valid_count }}</td>
              <td><span class="tag" :class="batch.status === 'SUCCESS' ? 'green' : 'red'">{{ statusName(batch.status) }}</span></td>
            </tr>
            <!-- 按 i in emptyBatchRows 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="i in emptyBatchRows" :key="`batch-empty-${i}`"><td colspan="7"></td></tr>
          </tbody>
        </table>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
        <el-pagination class="table-pagination" v-model:current-page="batchPage" :page-size="pageSize" layout="total, prev, pager, next" :total="batches.length" />
        <div class="stage-strip">
          <div>原始接入</div>
          <div>明细清洗</div>
          <div>指标汇总</div>
          <div>应用统计</div>
          <div>数据库同步</div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">运行链路 <span>三台虚拟机集群</span></div>
        <div class="ops-grid">
          <!-- 根据 item in runtime 循环生成条目，模板中的字段读取当前条目数据。 -->
          <div v-for="item in runtime" :key="item.label">
            <label>{{ item.label }}</label>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </div>
    </div>

    <div class="grid admin-extra equal-grid" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">高热内容审核 <span>前六条</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>排名</th><th>平台</th><th>内容标题</th><th>情感</th><th>热度</th></tr></thead>
          <tbody>
            <!-- 按 row in pagedContentRank 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="row in pagedContentRank" :key="row.content_id">
              <td>{{ row.rank_no }}</td>
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ row.title || row.clean_text }}</td>
              <td>{{ labelText(row.sentiment_label) }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
            </tr>
            <!-- 按 i in emptyContentRows 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="i in emptyContentRows" :key="`content-empty-${i}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
        <el-pagination class="table-pagination" v-model:current-page="contentPage" :page-size="pageSize" layout="total, prev, pager, next" :total="contentRank.length" />
      </div>

      <div class="card table-card">
        <div class="card-title">数据质量巡检 <span>噪声与重复汇总</span></div>
        <div class="quality-list">
          <!-- 根据 (row, index) in pagedNoiseSummary 循环生成条目，模板中的字段读取当前条目数据。 -->
          <div v-for="(row, index) in pagedNoiseSummary" :key="`${row.platform}-${index}`">
            <span>{{ platformName(row.platform) }}</span>
            <b>{{ row.content_count }}条内容</b>
            <em>噪声 {{ row.noise_count }} / 重复 {{ row.duplicate_count }}</em>
          </div>
          <!-- 根据 i in emptyNoiseRows 循环生成条目，模板中的字段读取当前条目数据。 -->
          <div v-for="i in emptyNoiseRows" :key="`noise-empty-${i}`" class="quality-empty"></div>
        </div>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
        <el-pagination class="table-pagination" v-model:current-page="noisePage" :page-size="pageSize" layout="total, prev, pager, next" :total="noiseSummary.length" />
      </div>

      <div class="card quick-card">
        <div class="card-title">后台快捷入口 <span>管理动作</span></div>
        <div class="quick-grid">
          <!-- 点击“数据管理”时执行 router.push('/admin/data')。 -->
          <el-button @click="router.push('/admin/data')">数据管理</el-button>
          <!-- 点击“ETL任务”时执行 router.push('/admin/etl')。 -->
          <el-button @click="router.push('/admin/etl')">ETL任务</el-button>
          <el-button @click="router.push('/admin/profile')">账号设置</el-button>
          <!-- 点击“打开大屏”时执行 router.push('/screen')。 -->
          <el-button type="primary" @click="router.push('/screen')">打开大屏</el-button>
        </div>
        <div class="source-note">先由采集器生成原始文件，再上传并执行数据处理，前台与大屏读取最新分析结果。</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChartBox from '../../components/common/ChartBox.vue'
import MetricGrid from '../../components/common/MetricGrid.vue'
import { clearGetCache, getData } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { statusName, stageName, platformName, eventName } from '../../utils/userText'

const router = useRouter()
// 分别保存后台管理概览与前台分析快照，两组接口数据不混用。
const admin = ref<any>({})
// 保存后端仪表盘快照，供概览指标和来源统计读取。
const dashboard = ref<any>({})
// 三张表各自保存页码，翻动一张表不影响另外两张。
const batchPage = ref(1)
const contentPage = ref(1)
const noisePage = ref(1)
// 每张表固定展示六行记录。
const pageSize = 6
// 从管理概览与分析快照提取数据，缺失列表使用空数组以支持首次加载。
const batches = computed<any[]>(() => admin.value.batches || [])
// 后端按时间倒序返回批次，首项用作最新运行状态。
const latestBatch = computed(() => batches.value[0])
// 提取平台时间线、内容热榜和噪声统计，缺失时提供空列表。
const platformSummary = computed<any[]>(() => dashboard.value.platformTimeline || [])
const contentRank = computed<any[]>(() => dashboard.value.contentRank || [])
const noiseSummary = computed<any[]>(() => dashboard.value.noiseSummary || [])
// 将接口计数转为数字，未返回事件计数时显示零。
const eventCount = computed(() => Number(admin.value.eventCount?.event_count || 0))
// 分别统计成功与失败的批次数，作为成功率和饼图的数据来源。
const successCount = computed(() => batches.value.filter(item => item.status === 'SUCCESS').length)
const failedCount = computed(() => batches.value.filter(item => item.status === 'FAILED').length)
// 成功率以所有已加载批次为分母，无批次时返回零而不是除零。
const successRate = computed(() => batches.value.length ? Math.round(successCount.value / batches.value.length * 100) : 0)
// 按各自页码截取当前页；第一页从数组下标零开始。
const pagedBatches = computed(() => batches.value.slice((batchPage.value - 1) * pageSize, batchPage.value * pageSize))
const pagedContentRank = computed(() => contentRank.value.slice((contentPage.value - 1) * pageSize, contentPage.value * pageSize))
const pagedNoiseSummary = computed(() => noiseSummary.value.slice((noisePage.value - 1) * pageSize, noisePage.value * pageSize))
// 计算三张表需要补齐的空行数，保证页面切换分页时布局稳定。
const emptyBatchRows = computed(() => Math.max(0, pageSize - pagedBatches.value.length))
const emptyContentRows = computed(() => Math.max(0, pageSize - pagedContentRank.value.length))
const emptyNoiseRows = computed(() => Math.max(0, pageSize - pagedNoiseSummary.value.length))

// 将事件、主题、源记录、有效记录和关键词数量转换为指标组件需要的结构。
const metrics = computed(() => [
  { label: '统一分析事件容器', value: eventCount.value, sub: '当前热点分析数据集' },
  { label: '热点主题', value: Number(admin.value.topicCount?.topic_count || 0), sub: '按标题聚合' },
  { label: '源记录数', value: latestBatch.value?.source_count || 0, sub: 'CSV输入' },
  { label: '有效记录', value: latestBatch.value?.valid_count || 0, sub: 'Spark清洗后' },
  { label: '关键词结果', value: dashboard.value.keywordRank?.length || 0, sub: '关键词排名结果' }
])

// 组织部署信息，并从最新批次读取动态运行状态。
const runtime = computed(() => [
  { label: 'ETL执行节点', value: '192.168.154.121' },
  { label: '计算模式', value: '独立集群，两个工作节点' },
  { label: '虚拟机仓库', value: 'HDFS /social-hotspot-analytics' },
  { label: '计算框架', value: 'Spark + HDFS + Hive' },
  { label: '后端服务', value: 'SpringBoot 8080' },
  { label: '前端服务', value: 'Vue 5174' },
  { label: '结果库', value: '虚拟机数据库 192.168.154.121:3306' },
  { label: '当前状态', value: statusName(latestBatch.value?.status) }
])

// 使用成功与失败批次数绘制环形图。
const successOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'pie', radius: ['52%', '72%'], data: [
    { name: '成功', value: successCount.value },
    { name: '失败', value: failedCount.value }
  ] }]
}))

// 将平台中文名作为纵轴，将该平台累计热度作为横向柱条数值。
const platformOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 58, right: 12, top: 18, bottom: 28 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: platformSummary.value.map(item => platformName(item.platform)), inverse: true },
  series: [{ type: 'bar', data: platformSummary.value.map(item => Number(item.hot_score || 0)), itemStyle: { color: '#2f6feb' } }]
}))

// 分别读取后台运行概览和统一分析数据集的仪表盘结果。
async function load() {
  // 先获取后台批次统计，再获取分析图表所用的仪表盘数据。
  admin.value = await getData('/admin/overview')
  dashboard.value = await getData('/analysis/dashboard')
}

// 手动刷新先清理共享请求缓存，避免沿用旧的数据库快照。
async function refreshData() {
  clearGetCache()
  await load()
  ElMessage.success('后台数据已刷新')
}

function numberText(value: any) {
  // 一万以上使用万单位，小数热度保留一位，其余显示整数。
  const num = Number(value || 0)
  return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0)
}

function labelText(value: string) {
  // 将正负向标签转换为中文，未知标签不默认归为正面。
  return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '未分类'
}

onMounted(() => load().catch(() => ElMessage.error('读取后台首页失败')))
useDatabaseAutoRefresh(load)
</script>
