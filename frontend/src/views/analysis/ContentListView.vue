<template>
  <!-- 多条件筛选使用服务端分页，详情弹框展示当前选中内容。 -->
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
      <!-- 将“搜索标题、正文、作者”的输入或选择同步到 query，供查询或提交时读取。 -->
      <el-input v-model="query" placeholder="搜索标题、正文、作者" style="width:280px" clearable />
      <!-- 将当前控件的输入或选择同步到 platform，供查询或提交时读取。 -->
      <el-select v-model="platform" style="width:150px">
        <el-option label="全部平台" value="全部" />
        <!-- 根据 item in platforms 循环生成条目，模板中的字段读取当前条目数据。 -->
        <el-option v-for="item in platforms" :key="item" :label="platformName(item)" :value="item" />
      </el-select>
      <!-- 将当前控件的输入或选择同步到 sentiment，供查询或提交时读取。 -->
      <el-select v-model="sentiment" style="width:150px">
        <el-option label="全部情感" value="全部" />
        <el-option label="正向" value="positive" />
        <el-option label="负向" value="negative" />
      </el-select>
      <!-- 将当前控件的输入或选择同步到 category，供查询或提交时读取。 -->
      <el-select v-model="category" style="width:150px">
        <el-option label="全部类型" value="全部" />
        <!-- 根据 item in categories 循环生成条目，模板中的字段读取当前条目数据。 -->
        <el-option v-for="item in categories" :key="item" :label="categoryName({ category: item })" :value="item" />
      </el-select>
      <!-- 点击“查询”时执行 page = 1; load(true)。 -->
      <el-button type="primary" @click="page = 1; load(true)">查询</el-button>
      <!-- 点击“重置”时执行 reset。 -->
      <el-button @click="reset">重置</el-button>
    </div>

    <div v-loading="loading" class="analysis-card table-card content-table-card">
      <div class="card-title">内容明细列表 <span>分析内容明细</span></div>
      <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
      <table class="table fixed-rows">
        <thead><tr><th>排名</th><th>平台</th><th>类型</th><th>内容分类</th><th>标题/正文</th><th>作者</th><th>发布时间</th><th>情感</th><th>热度</th><th style="width:90px">操作</th></tr></thead>
        <tbody>
          <!-- 按 row in pagedRows 循环生成表格行，使用 key 保持行标识稳定。 -->
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
          <!-- 按 i in emptyRows 循环生成表格行，使用 key 保持行标识稳定。 -->
          <tr v-for="i in emptyRows" :key="`content-empty-${i}`"><td colspan="10"></td></tr>
        </tbody>
      </table>
      <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
      <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="total" @current-change="load" />
    </div>

    <!-- 将弹框显隐与 detailVisible 绑定，集中展示当前操作的详情或表单。 -->
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
import { numberText, formatTime } from '../../utils/analysisFormat'
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData } from '../../api/client'
import { useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'
import { ANALYSIS_EVENT_NAME } from '../../config/analysisDataset'

// 保存后端返回的当前页内容，不在前端再次切片。
const rows = ref<any[]>([])
// 保存文本、平台、情感和类别四项查询条件。
const query = ref('')
// 保存平台、类别等当前筛选条件，后续查询或计算据此过滤。
const platform = ref('全部')
// 提取情感时间趋势，供大屏情感图生成使用。
const sentiment = ref('全部')
const category = ref('全部')
// 记录当前页码，每页向后端请求五条内容。
const page = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 5
// 保存后端统计的匹配总条数，供分页组件计算页数。
const total = ref(0)
// 请求期间展示加载状态。
const loading = ref(false)
// 控制详情弹框，并用响应式对象保存当前选中的记录。
const detailVisible = ref(false)
// 保存当前选中记录或对话的完整内容，供详情区读取。
const current = reactive<any>({})
// 引用统一中文名称，供页面事件标签显示。
const currentEventName = ANALYSIS_EVENT_NAME

// 定义可展示或选择的平台集合，供循环和筛选使用。
const platforms = ['DOUYIN', 'WEIBO', 'BILIBILI', 'XIAOHONGSHU', 'NEWS', 'TENCENT_NEWS', 'NETEASE_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'OWN_SITE']
// 下拉筛选提交后端识别的类别编码，而不是中文显示名称。
const categories = ['finance', 'politics', 'technology', 'sports', 'culture', 'society', 'general']
// 直接展示服务端分页结果。
const pagedRows = computed(() => rows.value)
// 不足五条时补齐空行，使翻页前后的表格高度保持稳定。
const emptyRows = computed(() => Math.max(0, pageSize - pagedRows.value.length))

// 将非默认筛选条件编码到查询参数中，直接使用后端返回的当前页与总条数。
async function load(force = false) {
  loading.value = true
  try {
    // 页码与每页条数始终发送，其他条件只有被选择时才加入参数。
    const params = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize) })
    if (query.value.trim()) params.set('query', query.value.trim())
    if (platform.value !== '全部') params.set('platform', platform.value)
    if (sentiment.value !== '全部') params.set('sentiment', sentiment.value)
    if (category.value !== '全部') params.set('category', category.value)
    // force 为真时绕过请求缓存，重新读取筛选后的当前页。
    const data = await getData(`/analysis/content-page?${params.toString()}`, force)
    // 同步当前页记录和匹配总数，兼容响应字段为空的情况。
    rows.value = data.rows || []
    total.value = Number(data.total || 0)
  } finally {
    // 即使请求失败，也结束表格加载状态。
    loading.value = false
  }
}
// 清空筛选、回到第一页，并强制重新请求。
function reset() { query.value = ''; platform.value = '全部'; sentiment.value = '全部'; category.value = '全部'; page.value = 1; load(true) }
// 把所选行复制到详情对象，再打开详情弹框。
function openDetail(row: any) { Object.assign(current, row); detailVisible.value = true }
// 将平台编码映射为中文名称，未知平台使用备用名称。
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道' } as Record<string, string>)[value] || '其他平台' }
// 将情感标签转换为中文，未识别标签显示未分类。
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '未分类' }
// 优先使用已有中文类别，否则翻译类别编码，微博评论不展示新闻类别。
function categoryName(row: any) { return ['WEIBO', 'SINA_WEIBO'].includes(String(row.platform || '').trim().toUpperCase()) ? '-' : row.category_label || ({ finance: '财经', politics: '政治', technology: '科技', sports: '体育', culture: '文娱', society: '社会', general: '综合' } as Record<string, string>)[row.category] || '综合' }

onMounted(async () => {
  await load().catch(() => ElMessage.error('读取内容明细失败'))
})
// 数据库同步或页面激活期间的定时刷新均绕过缓存，保持明细及时更新。
useDatabaseAutoRefresh(() => load(true), 5000)
</script>
