<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>用户画像</span>
        <h2>参与群体与核心传播用户分析</h2>
        <p>从平台、作者、地域、年龄、性别等维度观察参与群体结构；当原始数据缺少画像字段时，页面保留空值说明。</p>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
        <el-button type="primary" @click="load">刷新</el-button>
      </div>
    </div>

    <MetricGrid :items="metrics" />

    <div class="grid third" style="margin-top:12px">
      <div class="analysis-card">
        <div class="card-title">平台参与分布 <span>按内容与用户近似</span></div>
        <ChartBox :option="platformOption" />
      </div>
      <div class="analysis-card">
          <div class="card-title">画像字段覆盖 <span>来自 ADS 实际字段</span></div>
        <ChartBox :option="coverageOption" />
      </div>
      <div class="analysis-card">
        <div class="card-title">核心传播节点 <span>作者活跃度</span></div>
        <ChartBox :option="authorOption" />
      </div>
    </div>

    <div class="grid half equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">活跃用户样例 <span>按内容排行反推</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>作者</th><th>平台</th><th>代表内容</th><th>热度</th><th>情感</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedAuthors" :key="row.content_id">
              <td>{{ row.author_name || '匿名用户' }}</td>
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ row.title || row.clean_text }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
              <td>{{ sentimentName(row.sentiment_label) }}</td>
            </tr>
            <tr v-for="i in emptyRows" :key="`author-empty-${i}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="page" :page-size="pageSize" layout="total, prev, pager, next" :total="rank.length" />
      </div>

      <div class="analysis-card">
        <div class="card-title">画像结论 <span>数据可解释性</span></div>
        <div class="insight-list">
          <div><b>主要参与平台</b><span>{{ platformName(topPlatform?.platform) }}，内容量 {{ topPlatform?.content_count || 0 }}</span></div>
          <div><b>核心传播用户</b><span>{{ topAuthor?.author_name || '匿名用户' }}，代表内容热度 {{ numberText(topAuthor?.hot_score) }}</span></div>
          <div><b>画像字段</b><span>若样例数据缺少年龄、性别、地域字段，按需求允许为空并在质量结果体现</span></div>
          <div><b>分析价值</b><span>用于回答事件由哪些群体参与、哪些账号带来扩散、不同群体情绪是否不同</span></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import MetricGrid from '../components/MetricGrid.vue'
import { getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const data = ref<any>({})
const page = ref(1)
const pageSize = 8
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')

const overview = computed(() => data.value.overview || {})
const interaction = computed<any[]>(() => data.value.interaction || [])
const rank = computed<any[]>(() => data.value.contentRank || [])
const profileCoverage = computed(() => data.value.profileCoverage || {})
const pagedAuthors = computed(() => rank.value.slice((page.value - 1) * pageSize, page.value * pageSize))
const emptyRows = computed(() => Math.max(0, pageSize - pagedAuthors.value.length))
const topPlatform = computed(() => [...interaction.value].sort((a, b) => Number(b.content_count || 0) - Number(a.content_count || 0))[0])
const topAuthor = computed(() => [...rank.value].sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0))[0])

const metrics = computed(() => [
  { label: '参与用户', value: overview.value.user_count || 0, sub: '去重作者' },
  { label: '覆盖平台', value: overview.value.platform_count || 0, sub: '用户来源' },
  { label: '核心用户样例', value: rank.value.length, sub: '内容排行作者' },
  { label: '画像字段', value: '可为空', sub: '年龄/性别/地域' }
])

const platformOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'pie', radius: ['44%', '72%'], data: interaction.value.map(item => ({ name: platformName(item.platform), value: Number(item.content_count || 0) })) }]
}))

const coverageOption = computed(() => ({
  tooltip: {},
  radar: { indicator: [{ name: '平台', max: 100 }, { name: '作者', max: 100 }, { name: '地域', max: 100 }, { name: '年龄', max: 100 }, { name: '性别', max: 100 }] },
  series: [{ type: 'radar', data: [{ name: '字段覆盖率', value: [
    percentage(overview.value.platform_count, 6),
    percentage(profileCoverage.value.author_count, profileCoverage.value.content_count),
    percentage(profileCoverage.value.location_count, profileCoverage.value.content_count),
    percentage(profileCoverage.value.age_count, profileCoverage.value.content_count),
    percentage(profileCoverage.value.gender_count, profileCoverage.value.content_count)
  ], areaStyle: {} }] }]
}))

function percentage(value: any, total: any) {
  const denominator = Number(total || 0)
  return denominator ? Math.min(100, Math.round(Number(value || 0) / denominator * 100)) : 0
}

const authorOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { left: 44, right: 14, top: 24, bottom: 48 },
  xAxis: { type: 'category', axisLabel: { rotate: 30 }, data: rank.value.slice(0, 8).map(item => item.author_name || '匿名') },
  yAxis: { type: 'value' },
  series: [{ type: 'bar', data: rank.value.slice(0, 8).map(item => Number(item.hot_score || 0)) }]
}))

async function load() { data.value = await getData(`/events/${eventId.value}/dashboard`) }
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || value || '-' }
function sentimentName(value: string) { return value === 'positive' ? '正向' : value === 'negative' ? '负向' : '中性' }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取用户画像失败'))
})

useDatabaseAutoRefresh(load)
</script>
