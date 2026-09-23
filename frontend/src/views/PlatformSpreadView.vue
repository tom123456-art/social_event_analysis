<template>
  <div class="analysis-front">
    <div class="analysis-page-head">
      <div>
        <span>平台扩散</span>
        <h2>跨平台传播路径分析</h2>
      </div>
      <div class="analysis-actions">
        <el-tag v-if="events.length <= 1" class="single-event-tag" size="large">{{ currentEventName }}</el-tag>
        <el-select v-else v-model="eventId" style="width:280px" @change="load">
          <el-option v-for="event in events" :key="event.event_id" :label="event.event_name" :value="event.event_id" />
        </el-select>
        <el-button type="primary" :loading="refreshing" @click="refreshData">刷新数据库</el-button>
      </div>
    </div>

    <div class="grid two">
      <div class="analysis-card">
        <div class="card-title">跨平台扩散网络 <span>{{ propagationLinks.length ? '基于 parent_content_id' : '同标题跨平台关联，非因果链' }}</span></div>
        <ChartBox :option="graphOption" />
      </div>
      <div class="analysis-card">
        <div class="card-title">平台热度漏斗 <span>贡献排序</span></div>
        <ChartBox :option="funnelOption" />
      </div>
    </div>

    <div class="grid two equal-grid" style="margin-top:12px">
      <div class="analysis-card table-card">
        <div class="card-title">扩散时间线 <span>first_publish_time</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>平台</th><th>首次出现</th><th>扩散延迟</th><th>内容数</th><th>热度</th></tr></thead>
          <tbody>
            <tr v-for="row in pagedTimeline" :key="row.platform">
              <td>{{ platformName(row.platform) }}</td>
              <td>{{ formatTime(row.first_publish_time) }}</td>
              <td>{{ row.delay_minutes }} 分钟</td>
              <td>{{ row.content_count }}</td>
              <td>{{ numberText(row.hot_score) }}</td>
            </tr>
            <tr v-for="i in emptyTimelineRows" :key="`timeline-empty-${i}`"><td colspan="5"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="timelinePage" :page-size="pageSize" layout="total, prev, pager, next" :total="timeline.length" />
      </div>
      <div class="analysis-card">
        <div class="card-title">传播结论摘要 <span>自动归纳</span></div>
        <div class="insight-list">
          <div><b>首发平台</b><span>{{ platformName(timeline[0]?.platform) }}，{{ formatTime(timeline[0]?.first_publish_time) }}</span></div>
          <div><b>最高热度平台</b><span>{{ platformName(hottest?.platform) }}，热度 {{ numberText(hottest?.hot_score) }}</span></div>
          <div><b>扩散跨度</b><span>{{ lastTimeline?.delay_minutes || 0 }} 分钟内覆盖 {{ timeline.length }} 个平台</span></div>
          <div><b>研究重点</b><span>{{ propagationLinks.length ? '当前图谱使用真实 parent_content_id 关系' : '原始数据未提供 parent_content_id，图谱仅展示同标题跨平台关联，不推断传播因果' }}</span></div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import ChartBox from '../components/ChartBox.vue'
import { clearGetCache, getData } from '../api/client'
import { useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

const events = ref<any[]>([])
const eventId = ref('public_rss_latest')
const data = ref<any>({})
const refreshing = ref(false)
const timelinePage = ref(1)
const pageSize = 8
const currentEventName = computed(() => events.value.find(item => item.event_id === eventId.value)?.event_name || '社交媒体热点事件融合分析')
const timeline = computed<any[]>(() => {
  return [...(data.value.platformTimeline || [])].sort((a, b) => Number(a.delay_minutes || 0) - Number(b.delay_minutes || 0))
})
const pagedTimeline = computed(() => timeline.value.slice((timelinePage.value - 1) * pageSize, timelinePage.value * pageSize))
const emptyTimelineRows = computed(() => Math.max(0, pageSize - pagedTimeline.value.length))
const hottest = computed(() => [...timeline.value].sort((a, b) => Number(b.hot_score || 0) - Number(a.hot_score || 0))[0])
const lastTimeline = computed(() => timeline.value[timeline.value.length - 1])
const maxHeat = computed(() => Math.max(1, ...timeline.value.map(item => Number(item.hot_score || 0))))
const parentLinks = computed<any[]>(() => data.value.propagationLinks || [])
const topicLinks = computed<any[]>(() => data.value.topicPropagationLinks || [])
const propagationLinks = computed<any[]>(() => parentLinks.value.length ? parentLinks.value : topicLinks.value)

const graphOption = computed(() => ({
  tooltip: {},
  series: [{
    type: 'graph',
    layout: 'force',
    roam: true,
    force: { repulsion: 220, edgeLength: 130 },
    label: { show: true, fontWeight: 700 },
    data: timeline.value.map(item => ({
      name: platformName(item.platform),
      value: item.hot_score,
      symbolSize: 34 + Number(item.hot_score || 0) / maxHeat.value * 42
    })),
    links: propagationLinks.value.map(item => ({
      source: platformName(item.source_platform),
      target: platformName(item.target_platform),
      value: Number(item.link_count || 0)
    }))
  }]
}))

const funnelOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{ type: 'funnel', sort: 'descending', data: timeline.value.map(item => ({ name: platformName(item.platform), value: Number(item.hot_score || 0) })) }]
}))

async function load() {
  data.value = await getData(`/events/${eventId.value}/dashboard`)
}
async function refreshData() {
  refreshing.value = true
  try {
    clearGetCache()
    await load()
    ElMessage.success('已读取最新数据库快照')
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || error?.message || '读取数据库失败')
  } finally {
    refreshing.value = false
  }
}
function numberText(value: any) { const num = Number(value || 0); return num >= 10000 ? `${(num / 10000).toFixed(1)}万` : num.toFixed(num % 1 ? 1 : 0) }
function formatTime(value: any) { return value ? String(value).replace('T', ' ').slice(0, 16) : '-' }
function platformName(value: string) { return ({ DOUYIN: '抖音', WEIBO: '微博', BILIBILI: 'B站', XIAOHONGSHU: '小红书', NEWS: '新闻', TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻', OWN_SITE: '新闻都知道', own_site: '新闻都知道', news: '新闻' } as Record<string, string>)[value] || value || '-' }

onMounted(async () => {
  events.value = await getData('/events').catch(() => [])
  eventId.value = events.value.find(item => item.event_id === 'public_rss_latest')?.event_id || events.value[0]?.event_id || eventId.value
  await load().catch(() => ElMessage.error('读取平台扩散失败'))
})

useDatabaseAutoRefresh(load)
</script>
