<template>
  <!-- 内容只读查询与人工质量审核按标签页分开维护。 -->
  <div class="page">
    <div class="page-head">
      <div>
        <h2>数据管理</h2>
      </div>
      <div class="head-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
      </div>
    </div>

    <el-tabs v-model="activeTab" class="admin-tabs">
      <!-- 把“内容数据（ETL只读）”作为独立标签页，切换后展示对应内容。 -->
      <el-tab-pane label="内容数据（ETL只读）" name="content">
        <div class="toolbar">
          <!-- 将“搜索标题、正文、平台”的输入或选择同步到 query，供查询或提交时读取。 -->
          <el-input v-model="query" placeholder="搜索标题、正文、平台" style="width:320px" clearable />
          <!-- 点击“查询”时执行 load。 -->
          <el-button type="primary" @click="load">查询</el-button>
          <!-- 点击“查看前台”时执行 router.push('/overview')。 -->
          <el-button @click="router.push('/overview')">查看前台</el-button>
          <!-- 点击“重置”时执行 query = ''。 -->
          <el-button @click="query = ''">重置</el-button>
        </div>
        <div class="card table-card" v-loading="loading">
          <div class="card-title">内容数据列表 <span>分析明细</span></div>
          <div class="source-note">该表随采集和 CSV 上传自动重建，不允许单独增删改，避免内容明细与聚合图表不一致。</div>
          <!-- 仅当 loadError 成立时显示此处内容，条件变化时自动更新。 -->
          <div v-if="loadError" class="source-note error-note">{{ loadError }}</div>
          <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
          <table class="table fixed-rows">
            <thead><tr><th>排名</th><th>平台</th><th>标题</th><th>分类</th><th>情感</th><th>热度</th></tr></thead>
            <tbody>
              <!-- 按 row in pagedContents 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="row in pagedContents" :key="row.id">
                <td>{{ row.rank_no }}</td>
                <td>{{ platformName(row.platform) }}</td>
                <td>{{ row.title }}</td>
                <td>{{ categoryName(row.category) }}</td>
                <td>{{ sentimentName(row.sentiment_label) }}</td>
                <td>{{ row.hot_score }}</td>
              </tr>
              <tr v-if="!loading && !filteredContents.length"><td colspan="6">当前事件暂无内容，请先由采集器生成原始数据文件，再上传并执行数据处理。</td></tr>
              <!-- 按 i in emptyContentRows 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="i in emptyContentRows" :key="`content-empty-${i}`"><td colspan="6"></td></tr>
            </tbody>
          </table>
          <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
          <el-pagination class="table-pagination" v-model:current-page="contentPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredContents.length" />
        </div>
      </el-tab-pane>

      <!-- 把“人工标注审核”作为独立标签页，切换后展示对应内容。 -->
      <el-tab-pane label="人工标注审核" name="review" lazy>
        <!-- 在当前标签页加载审核队列组件，由组件独立处理修正和应用任务。 -->
        <QualityReviewPanel />
      </el-tab-pane>

    </el-tabs>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import QualityReviewPanel from '../../components/review/QualityReviewPanel.vue'
import { ElMessage } from 'element-plus'
import { getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'
import { platformName, categoryName, sentimentName } from '../../utils/userText'

const router = useRouter()
// 默认显示内容标签页，切换时保留其他标签页的数据状态。
const activeTab = ref('content')
// 保存当前事件编号及本地文本检索条件。
const query = ref('')
// 保存当前已加载的只读内容。
const contents = ref<any[]>([])
// 内容列表每页展示八条。
const contentPage = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 8
// loading 控制加载效果，loadError 保存可展示的加载失败原因。
const loading = ref(false)
const loadError = ref('')
// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME

// 在当前已加载的内容中按标题、清洗文本及平台名称进行本地筛选。
const filteredContents = computed(() => {
  const text = query.value.trim()
  // 未输入检索词时返回全部已加载内容。
  if (!text) return contents.value
  // 同时匹配标题、正文及平台的编码和中文名称。
  return contents.value.filter(item => `${item.title}${item.clean_text}${item.platform}${platformName(item.platform)}`.includes(text))
})
// 从筛选后的内容中截取当前页。
const pagedContents = computed(() => filteredContents.value.slice((contentPage.value - 1) * pageSize, contentPage.value * pageSize))
// 内容为空时表格已有一行空状态，因此补行数量再扣除一行。
const emptyContentRows = computed(() => Math.max(0, pageSize - pagedContents.value.length - (filteredContents.value.length ? 0 : 1)))

// 读取当前数据集的只读内容。
async function load() {
  // 每次加载前清除旧错误并启动加载状态。
  loading.value = true
  loadError.value = ''
  try {
    const rows = await getData<any[]>('/admin/contents?limit=200')
    contents.value = rows
  } catch (error: any) {
    // 加载失败时清空内容并保存中文错误，避免误把旧结果当作最新数据。
    contents.value = []
    loadError.value = getErrorMessage(error, '内容数据加载失败，请检查后端服务或稍后重试。')
    ElMessage.error(loadError.value)
  } finally {
    // 不论接口成功或失败，都解除加载状态。
    loading.value = false
  }
}

onMounted(async () => {
  await load()
})

useDatabaseAutoRefresh(load)
</script>
