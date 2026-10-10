<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>内容明细</span>
        <h2>原始内容查询与传播样本追溯</h2>
      </div>
      <div class="analysis-actions">
        <el-tag class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="query" placeholder="搜索标题、正文、作者" style="width:280px" clearable />
      <el-select v-model="platform" style="width:150px">
        <el-option label="全部平台" value="全部" />
        <el-option v-for="item in platforms" :key="item" :label="platformName(item)" :value="item" />
      </el-select>
      <el-select v-model="sentiment" style="width:150px">
        <el-option label="全部情感" value="全部" />
        <el-option label="正向" value="positive" />
        <el-option label="负向" value="negative" />
      </el-select>
      <el-select v-model="category" style="width:150px">
        <el-option label="全部类型" value="全部" />
        <el-option v-for="item in categories" :key="item" :label="categoryName({ category: item })" :value="item" />
      </el-select>
      <el-button type="primary" @click="page = 1; load(true)">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div v-loading="loading" class="analysis-card table-card content-table-card">
      <div class="card-title">内容明细列表 <span>ads_content_hot_rank</span></div>
      <table class="table fixed-rows">
        <thead><tr><th>排名</th><th>平台</th><th>类型</th><th>内容分类</th><th>标题/正文</th><th>作者</th><th>发布时间</th><th>情感</th><th>热度</th><th style="width:90px">操作</th></tr></thead>
        <tbody>
          <tr v-for="row in pagedRows" :key="row.content_id">
            <td>{{ row.rank_no }}</td>
            <td>{{ platformName(row.platform) }}</td>
            <td>{{ row.content_type || '-' }}</td>
            <td><span class="category-badge" :class="`category-${row.category || 'general'}`">{{ categoryName(row) }}</span></td>
            <td>{{ row.title || row.clean_text }}</td>
            <td>{{ row.author_name || '-' }}</td>
            <td>{{ formatTime(row.publish_time) }}</td>
            <td>{{ sentimentName(row.sentiment_label) }}</td>
            <td>{{ numberText(row.hot_score) }}</td>
            <td><el-button link type="primary" @click="openDetail(row)">详情</el-button></td>
          </tr>
          <tr v-for="i in emptyRows" :key="`content-empty-${i}`"><td colspan="10"></td></tr>
        </tbody>
      </table>
      <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="total" @current-change="load" />
    </div>

    <el-dialog v-model="detailVisible" title="内容传播样本详情" width="720px">
      <div class="detail-list">
        <div><span>内容ID</span><b>{{ current.content_id }}</b></div>
        <div><span>平台</span><b>{{ platformName(current.platform) }}</b></div>
        <div><span>内容分类</span><b><span class="category-badge" :class="`category-${current.category || 'general'}`">{{ categoryName(current) }}</span></b></div>
        <div><span>作者</span><b>{{ current.author_name || '-' }}</b></div>
        <div><span>情感</span><b>{{ sentimentName(current.sentiment_label) }}</b></div>
        <div><span>热度</span><b>{{ numberText(current.hot_score) }}</b></div>
      </div>
      <div class="empty" style="margin-top:12px">{{ current.clean_text || current.title }}</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 内容明细页：展示事件下的热点内容并支持筛选查看。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_ID, ANALYSIS_EVENT_NAME } from '../config/analysisDataset'

const eventId = ref(ANALYSIS_EVENT_ID)
const rows = ref<any[]>([])
const query = ref('')
const platform = ref('全部')
const sentiment = ref('全部')
const category = ref('全部')
const page = ref(1)
const pageSize = 5
const total = ref(0)
const loading = ref(false)
const detailVisible = ref(false)
const current = reactive<any>({})
const currentEventName = ANALYSIS_EVENT_NAME

const platforms = ['DOUYIN', 'WEIBO', 'BILIBILI', 'XIAOHONGSHU', 'NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'OWN_SITE']
const categories = ['finance', 'politics', 'technology', 'sports', 'culture', 'society', 'general']
const pagedRows = computed(() => rows.value)
const emptyRows = computed(() => Math.max(0, pageSize - pagedRows.value.length))

async function load(force = false) {
  loading.value = true
  try {
    const params = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize) })
    if (query.value.trim()) params.set('query', query.value.trim())
    if (platform.value !== '全部') params.set('platform', platform.value)
    if (sentiment.value !== '全部') params.set('sentiment', sentiment.value)
    if (category.value !== '全部') params.set('category', category.value)
    const data = await getData(`/events/${eventId.value}/content-page?${params.toString()}`, force)
    rows.value = data.rows || []
    total.value = Number(data.total || 0)
  } finally {
    loading.value = false
  }
}
function reset() { query.value = ''; platform.value = '全部'; sentiment.value = '全部'; category.value = '全部'; page.value = 1; load(true) }
function openDetail(row: any) { Object.assign(current, row); detailVisible.value = true }
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道' } as Record<string, string>)[value] || value || '-' }
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '未分类' }
function categoryName(row: any) { return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合' }

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取内容明细失败'))
})
useDatabaseAutoRefresh(() => load(true), 5000)
</script>
