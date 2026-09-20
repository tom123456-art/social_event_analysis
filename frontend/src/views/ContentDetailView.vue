<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>内容明细</span>
        <h2>原始内容查询与传播样本追溯</h2>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
        <el-button type="primary" @click="load">刷新</el-button>
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
        <el-option label="中性" value="neutral" />
        <el-option label="负向" value="negative" />
      </el-select>
      <el-select v-model="category" style="width:150px">
        <el-option label="全部类型" value="全部" />
        <el-option v-for="item in categories" :key="item" :label="categoryName({ category: item })" :value="item" />
      </el-select>
      <el-button type="primary" @click="page = 1">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="analysis-card table-card content-table-card">
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
      <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredRows.length" />
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const rows = ref<any[]>([])
const query = ref('')
const platform = ref('全部')
const sentiment = ref('全部')
const category = ref('全部')
const page = ref(1)
const pageSize = 10
const detailVisible = ref(false)
const current = reactive<any>({})
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')

const platforms = computed(() => [...new Set(rows.value.map(item => item.platform).filter(Boolean))])
const categories = computed(() => [...new Set(rows.value.map(item => item.category || 'general').filter(Boolean))])
const filteredRows = computed(() => rows.value.filter(row => {
  const text = `${row.title || ''}${row.clean_text || ''}${row.author_name || ''}`
  return (!query.value || text.includes(query.value))
    && (platform.value === '全部' || row.platform === platform.value)
    && (sentiment.value === '全部' || row.sentiment_label === sentiment.value)
    && (category.value === '全部' || (row.category || 'general') === category.value)
}))
const pagedRows = computed(() => filteredRows.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const emptyRows = computed(() => Math.max(0, pageSize - pagedRows.value.length))

async function load() {
  const data = await getData(`/events/${eventId.value}/dashboard`)
  rows.value = data.realPublicContents || []
}
function reset() { query.value = ''; platform.value = '全部'; sentiment.value = '全部'; category.value = '全部'; page.value = 1 }
function openDetail(row: any) { Object.assign(current, row); detailVisible.value = true }
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道' } as Record<string, string>)[value] || value || '-' }
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '中性' }
function categoryName(row: any) { return row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合' }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取内容明细失败'))
})
useDatabaseAutoRefresh(load)
</script>
