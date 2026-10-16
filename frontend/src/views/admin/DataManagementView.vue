<template>
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
      <el-tab-pane label="内容数据（ETL只读）" name="content">
        <div class="toolbar">
          <el-input v-model="query" placeholder="搜索标题、正文、平台" style="width:320px" clearable />
          <el-button type="primary" @click="load">查询</el-button>
          <el-button @click="router.push('/overview')">查看前台</el-button>
          <el-button @click="query = ''">重置</el-button>
        </div>
        <div class="card table-card" v-loading="loading">
          <div class="card-title">内容数据列表 <span>分析明细</span></div>
          <div class="source-note">该表随采集和 CSV 上传自动重建，不允许单独增删改，避免内容明细与聚合图表不一致。</div>
          <div v-if="loadError" class="source-note error-note">{{ loadError }}</div>
          <table class="table fixed-rows">
            <thead><tr><th>排名</th><th>平台</th><th>标题</th><th>分类</th><th>情感</th><th>热度</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedContents" :key="row.id">
                <td>{{ row.rank_no }}</td>
                <td>{{ platformName(row.platform) }}</td>
                <td>{{ row.title }}</td>
                <td>{{ categoryName(row.category) }}</td>
                <td>{{ sentimentName(row.sentiment_label) }}</td>
                <td>{{ row.hot_score }}</td>
              </tr>
              <tr v-if="!loading && !filteredContents.length"><td colspan="6">当前事件暂无内容，请先由采集器生成原始数据文件，再上传并执行数据处理。</td></tr>
              <tr v-for="i in emptyContentRows" :key="`content-empty-${i}`"><td colspan="6"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="contentPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredContents.length" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="人工标注审核" name="review" lazy>
        <QualityReviewPanel />
      </el-tab-pane>

      <el-tab-pane label="互动记录" name="interaction">
        <div class="card table-card">
          <div class="card-title">用户互动记录 <span>互动明细</span></div>
          <table class="table fixed-rows">
            <thead><tr><th>用户</th><th>事件</th><th>平台</th><th>动作</th><th>内容</th><th>时间</th><th style="width:88px">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedInteractions" :key="row.id">
                <td>{{ row.username }}</td>
                <td>{{ eventName(row.event_name || row.event_id) }}</td>
                <td>{{ platformName(row.platform) }}</td>
                <td>{{ actionName(row.action_type) }}</td>
                <td>{{ row.comment_text || row.content_id }}</td>
                <td>{{ formatTime(row.created_at) }}</td>
                <td><el-button link type="danger" @click="removeInteraction(row)">删除</el-button></td>
              </tr>
              <tr v-for="i in emptyInteractionRows" :key="`interaction-empty-${i}`"><td colspan="7"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="interactionPage" :page-size="pageSize" layout="total, prev, pager, next" :total="interactions.length" />
        </div>
      </el-tab-pane>
    </el-tabs>

  </div>
</template>

<script setup lang="ts">
// 数据管理页：查看内容数据、审核清洗异常和管理互动记录。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import QualityReviewPanel from '../../components/review/QualityReviewPanel.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteData, getData, getErrorMessage } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_ID, ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'
import { platformName, categoryName, sentimentName, actionName, eventName } from '../../utils/userText'

const router = useRouter()
const activeTab = ref('content')
const eventId = ref(ANALYSIS_EVENT_ID)
const query = ref('')
const contents = ref<any[]>([])
const interactions = ref<any[]>([])
const contentPage = ref(1)
const interactionPage = ref(1)
const pageSize = 8
const loading = ref(false)
const loadError = ref('')
const currentEventName = ANALYSIS_EVENT_NAME

const filteredContents = computed(() => {
  const text = query.value.trim()
  if (!text) return contents.value
  return contents.value.filter(item => `${item.title}${item.clean_text}${item.platform}${platformName(item.platform)}`.includes(text))
})
const pagedContents = computed(() => filteredContents.value.slice((contentPage.value - 1) * pageSize, contentPage.value * pageSize))
const pagedInteractions = computed(() => interactions.value.slice((interactionPage.value - 1) * pageSize, interactionPage.value * pageSize))
const emptyContentRows = computed(() => Math.max(0, pageSize - pagedContents.value.length - (filteredContents.value.length ? 0 : 1)))
const emptyInteractionRows = computed(() => Math.max(0, pageSize - pagedInteractions.value.length))

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    let rows = await getData<any[]>(`/admin/contents?eventId=${encodeURIComponent(eventId.value)}&limit=200`)
    if (!rows.length && eventId.value !== 'public_rss_latest') {
      rows = await getData<any[]>('/admin/contents?eventId=public_rss_latest&limit=200')
      eventId.value = 'public_rss_latest'
    }
    contents.value = rows
    interactions.value = await getData('/admin/interactions?limit=200')
  } catch (error: any) {
    contents.value = []
    loadError.value = getErrorMessage(error, '内容数据加载失败，请检查后端服务或稍后重试。')
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function removeInteraction(row: any) {
  try {
    await ElMessageBox.confirm('确认删除这条互动记录？', '删除确认')
    await deleteData(`/admin/interactions/${row.id}`)
    ElMessage.success('已删除互动')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error, '删除互动记录失败，请稍后重试。'))
  }
}

function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }

onMounted(async () => {
  await load()
})

useDatabaseAutoRefresh(load)
</script>
