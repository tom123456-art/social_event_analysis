<template>
  <div class="page admin-dense">
    <div class="page-head compact">
      <div>
        <h2>后台管理首页</h2>
      </div>
      <div>
        <el-button @click="router.push('/overview')">返回前台</el-button>
        <el-button type="primary" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <MetricGrid :items="metrics" />

    <div class="grid admin-top" style="margin-top:12px">
      <div class="card">
      <div class="card-title">数据来源说明 <span>数据库快照</span></div>
        <div class="source-box">
          <b>当前数据来自外部 Python 采集与 ETL</b>
          <p>外部 Python 采集器负责生成 social_event_real.csv；本系统负责接收 Raw CSV、执行 ETL，并向前台提供分析结果。</p>
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
        <ChartBox size="md" :option="successOption" />
      </div>

      <div class="card">
        <div class="card-title">平台热度排行 <span>ADS来源汇总</span></div>
        <ChartBox size="md" :option="platformOption" />
      </div>
    </div>

    <div class="grid admin-bottom" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">最近ETL批次 <span>etl_batch</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>批次号</th><th>事件</th><th>源路径</th><th>阶段</th><th>源记录</th><th>有效</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="batch in pagedBatches" :key="batch.batch_id">
              <td>{{ batch.batch_id }}</td>
              <td>{{ batch.event_id }}</td>
              <td>{{ batch.source_path }}</td>
              <td>{{ batch.current_stage }}</td>
              <td>{{ batch.source_count }}</td>
              <td>{{ batch.valid_count }}</td>
              <td><span class="tag" :class="batch.status === 'SUCCESS' ? 'green' : 'red'">{{ batch.status }}</span></td>
            </tr>
            <tr v-for="i in emptyBatchRows" :key="`batch-empty-${i}`"><td colspan="7"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="batchPage" :page-size="pageSize" layout="total, prev, pager, next" :total="batches.length" />
        <div class="stage-strip">
          <div>ODS采集</div>
          <div>DWD清洗</div>
          <div>DWS聚合</div>
          <div>ADS统计</div>
          <div>MySQL同步</div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">运行链路 <span>三台虚拟机集群</span></div>
        <div class="ops-grid">
          <div v-for="item in runtime" :key="item.label">
            <label>{{ item.label }}</label>
            <strong>{{ item.value }}</strong>
          </div>
        </div>
      </div>
    </div>

    <div class="grid admin-extra equal-grid" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">高热内容审核 <span>Top 6</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>排名</th><th>平台</th><th>内容标题</th><th>情感</th><th>热度</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedContentRank" :key="row.content_id">
              <td>{{ row.rank_no }}</td>
              <td>{{ row.platform }}</td>
              <td>{{ row.title || row.clean_text }}</td>
              <td>{{ labelText(row.sentiment_label) }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
            </tr>
            <tr v-for="i in emptyContentRows" :key="`content-empty-${i}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="contentPage" :page-size="pageSize" layout="total, prev, pager, next" :total="contentRank.length" />
      </div>

      <div class="card table-card">
        <div class="card-title">数据质量巡检 <span>noise_summary</span></div>
        <div class="quality-list">
          <div v-for="(row, index) in pagedNoiseSummary" :key="`${row.platform}-${index}`">
            <span>{{ row.platform }}</span>
            <b>{{ row.content_count }}条内容</b>
            <em>噪声 {{ row.noise_count }} / 重复 {{ row.duplicate_count }}</em>
          </div>
          <div v-for="i in emptyNoiseRows" :key="`noise-empty-${i}`" class="quality-empty"></div>
        </div>
        <el-pagination class="table-pagination" v-model:current-page="noisePage" :page-size="pageSize" layout="total, prev, pager, next" :total="noiseSummary.length" />
      </div>

      <div class="card quick-card">
        <div class="card-title">后台快捷入口 <span>管理动作</span></div>
        <div class="quick-grid">
          <el-button @click="router.push('/admin/data')">数据管理</el-button>
          <el-button @click="router.push('/admin/etl')">ETL任务</el-button>
          <el-button @click="router.push('/admin/users')">用户管理</el-button>
          <el-button type="primary" @click="router.push('/screen')">打开大屏</el-button>
        </div>
        <div class="source-note">管理动作顺序：使用独立 Python 采集器生成 Raw CSV；在 ETL 任务中上传并执行数据处理，前台与大屏读取最新分析结果。</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import MetricGrid from '../components/MetricGrid.vue'
import { clearGetCache, getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const router = useRouter()
const admin = ref<any>({})
const dashboard = ref<any>({})
const batchPage = ref(1)
const contentPage = ref(1)
const noisePage = ref(1)
const pageSize = 6
const batches = computed<any[]>(() => admin.value.batches || [])
const latestBatch = computed(() => batches.value[0])
const platformSummary = computed<any[]>(() => dashboard.value.platformTimeline || [])
const contentRank = computed<any[]>(() => dashboard.value.contentRank || [])
const noiseSummary = computed<any[]>(() => dashboard.value.noiseSummary || [])
const eventCount = computed(() => Number(admin.value.eventCount?.event_count || 0))
const successCount = computed(() => batches.value.filter(item => item.status === 'SUCCESS').length)
const failedCount = computed(() => batches.value.filter(item => item.status === 'FAILED').length)
const successRate = computed(() => batches.value.length ? Math.round(successCount.value / batches.value.length * 100) : 0)
const pagedBatches = computed(() => batches.value.slice((batchPage.value - 1) * pageSize, batchPage.value * pageSize))
const pagedContentRank = computed(() => contentRank.value.slice((contentPage.value - 1) * pageSize, contentPage.value * pageSize))
const pagedNoiseSummary = computed(() => noiseSummary.value.slice((noisePage.value - 1) * pageSize, noisePage.value * pageSize))
const emptyBatchRows = computed(() => Math.max(0, pageSize - pagedBatches.value.length))
const emptyContentRows = computed(() => Math.max(0, pageSize - pagedContentRank.value.length))
const emptyNoiseRows = computed(() => Math.max(0, pageSize - pagedNoiseSummary.value.length))

const metrics = computed(() => [
  { label: '统一分析事件容器', value: eventCount.value, sub: 'event_info：public_rss_latest' },
  { label: '热点主题', value: Number(admin.value.topicCount?.topic_count || 0), sub: '按标题聚合' },
  { label: '源记录数', value: latestBatch.value?.source_count || 0, sub: 'CSV输入' },
  { label: '有效记录', value: latestBatch.value?.valid_count || 0, sub: 'Spark清洗后' },
  { label: '关键词结果', value: dashboard.value.keywordRank?.length || 0, sub: 'ads_keyword_rank' }
])

const runtime = computed(() => [
  { label: 'ETL执行节点', value: '192.168.154.131' },
  { label: 'Spark模式', value: 'VM local[1]' },
  { label: '虚拟机仓库', value: '/opt/apps/social-hotspot-analytics/warehouse' },
  { label: '计算框架', value: 'Spark 4.1.2' },
  { label: '后端服务', value: 'SpringBoot 18080' },
  { label: '前端服务', value: 'Vue 5174' },
  { label: '结果库', value: 'VM MySQL 192.168.154.131:3306' },
  { label: '当前状态', value: latestBatch.value?.status || '-' }
])

const successOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'pie', radius: ['52%', '72%'], data: [
    { name: '成功', value: successCount.value },
    { name: '失败', value: failedCount.value }
  ] }]
}))

const platformOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 58, right: 12, top: 18, bottom: 28 },
  xAxis: { type: 'value' },
  yAxis: { type: 'category', data: platformSummary.value.map(item => item.platform), inverse: true },
  series: [{ type: 'bar', data: platformSummary.value.map(item => Number(item.hot_score || 0)), itemStyle: { color: '#2f6feb' } }]
}))

async function load() {
  admin.value = await getData('/admin/overview')
  dashboard.value = await getData('/events/public_rss_latest/dashboard')
}

async function refreshData() {
  clearGetCache()
  await load()
  ElMessage.success('后台数据已刷新')
}

function numberText(value: any) {
  const num = Number(value || 0)
  return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0)
}

function labelText(value: string) {
  return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '中性'
}

onMounted(() => load().catch(() => ElMessage.error('读取后台首页失败')))
useDatabaseAutoRefresh(load)
</script>
