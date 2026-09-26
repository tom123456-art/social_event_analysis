<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>数据管理</h2>
      </div>
      <div class="head-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:260px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
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
          <div class="card-title">内容数据列表 <span>ads_content_hot_rank</span></div>
          <div class="source-note">该表随采集和 CSV 上传自动重建，不允许单独增删改，避免内容明细与聚合图表不一致。</div>
          <div v-if="loadError" class="source-note error-note">{{ loadError }}</div>
          <table class="table fixed-rows">
            <thead><tr><th>排名</th><th>平台</th><th>标题</th><th>分类</th><th>情感</th><th>热度</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedContents" :key="row.id">
                <td>{{ row.rank_no }}</td>
                <td>{{ row.platform }}</td>
                <td>{{ row.title }}</td>
                <td>{{ row.category || '-' }}</td>
                <td>{{ row.sentiment_label }}</td>
                <td>{{ row.hot_score }}</td>
              </tr>
              <tr v-if="!loading && !filteredContents.length"><td colspan="6">当前事件暂无数据库内容，请先使用独立 Python 采集器生成 Raw CSV，再执行 ETL。</td></tr>
              <tr v-for="i in emptyContentRows" :key="`content-empty-${i}`"><td colspan="6"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="contentPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredContents.length" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="人工标注审核" name="submission">
        <div class="card table-card">
          <div class="card-title">异常内容与人工标注 <span>user_submission</span></div>
          <table class="table fixed-rows">
            <thead><tr><th>标注人</th><th>平台</th><th>标题</th><th>内容摘要</th><th>审核状态</th><th style="width:190px">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedSubmissions" :key="row.id">
                <td>{{ row.username }}</td>
                <td>{{ row.platform }}</td>
                <td>{{ row.title }}</td>
                <td>{{ row.content_text }}</td>
                <td><span class="tag" :class="row.status === 'APPROVED' ? 'green' : row.status === 'REJECTED' ? 'red' : 'amber'">{{ row.status }}</span></td>
                <td>
                  <el-button link type="primary" @click="setStatus(row, 'APPROVED')">通过</el-button>
                  <el-button link type="warning" @click="setStatus(row, 'REJECTED')">驳回</el-button>
                  <el-button link type="danger" @click="removeSubmission(row)">删除</el-button>
                </td>
              </tr>
              <tr v-for="i in emptySubmissionRows" :key="`submission-empty-${i}`"><td colspan="6"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="submissionPage" :page-size="pageSize" layout="total, prev, pager, next" :total="submissions.length" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="互动记录" name="interaction">
        <div class="card table-card">
          <div class="card-title">用户互动记录 <span>user_interaction</span></div>
          <table class="table fixed-rows">
            <thead><tr><th>用户</th><th>事件</th><th>平台</th><th>动作</th><th>内容</th><th>时间</th><th style="width:88px">操作</th></tr></thead>
            <tbody>
              <tr v-for="row in pagedInteractions" :key="row.id">
                <td>{{ row.username }}</td>
                <td>{{ row.event_name || row.event_id }}</td>
                <td>{{ row.platform }}</td>
                <td>{{ row.action_type }}</td>
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
// 数据管理页：管理人工投稿、互动记录和内容审核状态。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteData, getData, getErrorMessage, putData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const router = useRouter()
const activeTab = ref('content')
const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const query = ref('')
const contents = ref<any[]>([])
const submissions = ref<any[]>([])
const interactions = ref<any[]>([])
const contentPage = ref(1)
const submissionPage = ref(1)
const interactionPage = ref(1)
const pageSize = 8
const loading = ref(false)
const loadError = ref('')
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')

const filteredContents = computed(() => {
  const text = query.value.trim()
  if (!text) return contents.value
  return contents.value.filter(item => `${item.title}${item.clean_text}${item.platform}`.includes(text))
})
const pagedContents = computed(() => filteredContents.value.slice((contentPage.value - 1) * pageSize, contentPage.value * pageSize))
const pagedSubmissions = computed(() => submissions.value.slice((submissionPage.value - 1) * pageSize, submissionPage.value * pageSize))
const pagedInteractions = computed(() => interactions.value.slice((interactionPage.value - 1) * pageSize, interactionPage.value * pageSize))
const emptyContentRows = computed(() => Math.max(0, pageSize - pagedContents.value.length - (filteredContents.value.length ? 0 : 1)))
const emptySubmissionRows = computed(() => Math.max(0, pageSize - pagedSubmissions.value.length))
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
    submissions.value = await getData('/admin/submissions?limit=200')
    interactions.value = await getData('/admin/interactions?limit=200')
  } catch (error: any) {
    contents.value = []
    loadError.value = `内容数据加载失败：${error?.message || '请检查后端服务和数据库连接'}`
    ElMessage.error(loadError.value)
  } finally {
    loading.value = false
  }
}

async function setStatus(row: any, status: string) {
  try {
    await putData(`/admin/submissions/${row.id}/status`, { status })
    ElMessage.success('审核状态已更新')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '更新审核状态失败，请稍后重试。'))
  }
}

async function removeSubmission(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除投稿：${row.title}？`, '删除确认')
    await deleteData(`/admin/submissions/${row.id}`)
    ElMessage.success('已删除标注记录')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error, '删除标注记录失败，请稍后重试。'))
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
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load()
})

useDatabaseAutoRefresh(load)
</script>
