<template>
  <el-dialog :model-value="Boolean(mode)" :title="titles[mode]" width="min(1100px, 94vw)" @close="$emit('close')">
    <div v-loading="loading" class="evidence">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-button v-if="error" @click="load">重试</el-button>
      <p>统计窗口：{{ time(summary.window_start).slice(0, 10) }} 至 {{ time(summary.window_end).slice(0, 10) }}，以数据最新日期为准。新闻按发布时间归入整点小时。</p>
      <template v-if="mode !== 'heat'">
        <p><strong>类别关注指数 = √(类别新闻数 ÷ 平台该小时有类别新闻总数 × 100 × 类别平均内容热度指数)</strong></p>
        <p>每个平台、类别分别取窗口峰值 P；P 小于 35 不参与。其余阈值为 max(35, P × 70%)，首次达到阈值的小时为首达时间。这是阈值识别，不是环比上涨，也不证明事件因果传播。</p>
        <p v-if="mode === 'lead'">比较所有平台、类别的首达时间，取最早者。同一小时首达为并列，不能判断小时内谁先发生。</p>
        <p v-if="mode === 'span'">跨度 = 各平台最晚首达时间 − 全局最早首达时间。不同平台可能对应不同类别，并不代表同一事件覆盖这些平台。</p>
        <p v-if="mode === 'relations'">同类别按首达时间排序，统计相邻平台且间隔大于 0、不超过 4320 分钟的关系。同时达到不计方向。关系数是类别—平台对数，不是转载次数。</p>
        <el-table v-if="mode === 'relations'" :data="analysis.relations || []" max-height="220">
          <el-table-column prop="category" label="类别" width="75" />
          <el-table-column label="先达平台 / 时间" min-width="210"><template #default="{ row }"><el-button link type="primary" @click="select(row.source_platform, row.category, row.source_time)">{{ name(row.source_platform) }} {{ time(row.source_time) }}</el-button></template></el-table-column>
          <el-table-column label="后达平台 / 时间" min-width="210"><template #default="{ row }"><el-button link type="primary" @click="select(row.target_platform, row.category, row.target_time)">{{ name(row.target_platform) }} {{ time(row.target_time) }}</el-button></template></el-table-column>
          <el-table-column prop="lag_minutes" label="间隔/分钟" width="100" />
        </el-table>
        <h3>平台与类别的首达时间比较</h3>
        <el-table :data="analysis.activations || []" max-height="230" @row-click="(row: any) => select(row.platform, row.category, row.first_hot_time)">
          <el-table-column label="平台" min-width="100"><template #default="{ row }">{{ name(row.platform) }}</template></el-table-column>
          <el-table-column prop="category" label="类别" width="75" />
          <el-table-column label="首次达到阈值" min-width="160"><template #default="{ row }">{{ time(row.first_hot_time) }}</template></el-table-column>
          <el-table-column label="窗口峰值 P" width="110"><template #default="{ row }">{{ fixed(row.peak_attention) }}</template></el-table-column>
          <el-table-column label="阈值 max(35, 0.7P)" min-width="155"><template #default="{ row }">{{ fixed(row.threshold) }}</template></el-table-column>
          <el-table-column label="新闻" width="75"><template #default="{ row }"><el-button link type="primary" @click.stop="select(row.platform, row.category, row.first_hot_time)">查看</el-button></template></el-table-column>
        </el-table>
      </template>
      <template v-else>
        <p><strong>累计热度 = Σ(当日平均内容热度指数 × 当日新闻数)</strong>。这是累计量，不是峰值或单篇平均值；新闻数量更多可能导致累计更高，不能据此认定传播能力更强。</p>
        <el-table :data="dashboard.recentPlatformTimeline || []" @row-click="(row: any) => select(row.platform, '', '')">
          <el-table-column label="平台"><template #default="{ row }">{{ name(row.platform) }}</template></el-table-column>
          <el-table-column prop="content_count" label="新闻数" />
          <el-table-column label="加权平均热度"><template #default="{ row }">{{ fixed(row.average_heat_index) }}</template></el-table-column>
          <el-table-column label="累计热度"><template #default="{ row }">{{ fixed(row.hot_score) }}</template></el-table-column>
        </el-table>
        <el-table :data="daily" max-height="220">
          <el-table-column label="日期"><template #default="{ row }">{{ time(row.time_bucket).slice(0, 10) }}</template></el-table-column>
          <el-table-column prop="content_count" label="新闻数" />
          <el-table-column label="日均指数"><template #default="{ row }">{{ fixed(row.average_heat_index) }}</template></el-table-column>
          <el-table-column label="当日贡献"><template #default="{ row }">{{ fixed(Number(row.average_heat_index) * Number(row.content_count)) }}</template></el-table-column>
        </el-table>
      </template>
      <div class="selection">
        <el-select v-model="platform" aria-label="平台" @change="reset"><el-option v-for="p in platforms" :key="p" :label="name(p)" :value="p" /></el-select>
        <el-select v-if="mode !== 'heat'" v-model="category" aria-label="类别" @change="reset"><el-option v-for="c in categories" :key="c" :label="c" :value="c" /></el-select>
        <el-button v-if="hour" link type="primary" @click="reset">整个窗口</el-button>
      </div>
      <template v-if="mode !== 'heat'">
        <h3>逐小时计算 · {{ name(platform) }} · {{ category }}</h3>
        <el-table :data="hourly" max-height="230">
          <el-table-column label="小时" min-width="160"><template #default="{ row }">{{ time(row.time_bucket) }}</template></el-table-column>
          <el-table-column prop="content_count" label="类别新闻数" width="100" />
          <el-table-column prop="platform_content_count" label="平台小时总数" width="110" />
          <el-table-column label="占比 %" width="90"><template #default="{ row }">{{ fixed(row.coverage_share) }}</template></el-table-column>
          <el-table-column prop="average_relative_heat_index" label="平均热度" width="95" />
          <el-table-column label="关注指数" width="95"><template #default="{ row }">{{ fixed(row.attention) }}</template></el-table-column>
          <el-table-column label="阈值状态" min-width="130"><template #default="{ row }">{{ status(row) }}</template></el-table-column>
          <el-table-column label="新闻" width="70"><template #default="{ row }"><el-button link type="primary" @click="select(platform, category, row.time_bucket)">查看</el-button></template></el-table-column>
        </el-table>
      </template>
      <h3>参与计算的新闻 · {{ name(platform) }}{{ mode !== 'heat' ? ` · ${category}` : '' }} · {{ hour ? time(hour) : '整个窗口' }} · 共 {{ result.total || 0 }} 条</h3>
      <el-table :data="result.items || []" max-height="320">
        <el-table-column label="标题 / 原文" min-width="260"><template #default="{ row }"><a v-if="safeUrl(row.source_url)" :href="safeUrl(row.source_url)" target="_blank" rel="noopener noreferrer">{{ row.title || row.clean_text || row.content_id }}</a><span v-else>{{ row.title || row.clean_text || row.content_id }}</span></template></el-table-column>
        <el-table-column label="发布时间" min-width="160"><template #default="{ row }">{{ time(row.publish_time) }}</template></el-table-column>
        <el-table-column label="内容热度指数" width="120"><template #default="{ row }">{{ fixed(row.platform_heat_index) }}</template></el-table-column>
        <el-table-column prop="like_count" label="赞" width="85" /><el-table-column prop="comment_count" label="评论" width="85" />
      </el-table>
      <el-pagination v-if="result.total > 20" v-model:current-page="page" :page-size="20" :total="Number(result.total)" layout="prev, pager, next, total" @current-change="load" />
      <p>内容热度沿用 ETL 的平台内 0–100 相对指数：在同平台、同数据集内，对可用互动字段按 log(1 + 互动量) 求百分位排名，再对有效字段等权平均。腾讯使用赞、评论、转发、收藏；搜狐和澎湃使用赞、评论；新浪和网易使用评论。字段须有至少 1% 的记录大于零才参与，无有效字段则指数缺失。类别小时平均按现有 SQL 将缺失指数计为 0；累计热度沿用日均指数的计算口径。</p>
      <p>原文用于核查，数据为当前数据库快照。单条新闻也可能达到阈值，需结合样本量判断。未观测小时不补零；窗口开始前是否已活跃未知；系统阈值未经显著性检验。</p>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getData } from '../api/client'
const props = defineProps<{ mode: string; eventId: string; dashboard: any }>()
defineEmits<{ close: [] }>()
const titles: Record<string, string> = { lead: '最早达到阈值 · 结论来源', heat: '累计热度最高 · 结论来源', span: '各平台首达时间跨度 · 结论来源', relations: '同类别先后关系 · 结论来源' }
const names: Record<string, string> = { TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' }
const platforms = Object.keys(names)
const categories = ['财经', '政治', '科技', '体育', '文娱', '社会', '综合']
const platform = ref(''), category = ref(''), hour = ref(''), page = ref(1)
const result = ref<any>({}), loading = ref(false), error = ref('')
let requestId = 0
const analysis = computed(() => result.value.analysis || {})
const summary = computed(() => analysis.value.summary || props.dashboard.categoryPropagationSummary || {})
const hourly = computed(() => (analysis.value.hourly || []).filter((r: any) => r.platform === platform.value && r.category === category.value))
const daily = computed(() => (props.dashboard.platformDailyHeat || []).filter((r: any) => r.platform === platform.value && time(r.time_bucket).slice(0, 10) >= time(summary.value.window_start).slice(0, 10) && time(r.time_bucket).slice(0, 10) <= time(summary.value.window_end).slice(0, 10)))
watch(() => props.mode, mode => {
  if (!mode) { ++requestId; return }
  const s = props.dashboard.categoryPropagationSummary || {}
  const top = [...(props.dashboard.recentPlatformTimeline || [])].sort((a: any, b: any) => Number(b.hot_score) - Number(a.hot_score))[0]
  platform.value = (mode === 'heat' ? top?.platform : s.lead_platform) || platforms[0]
  category.value = s.lead_category || categories[0]
  hour.value = mode === 'lead' ? String(s.first_hot_time || '') : ''
  page.value = 1; result.value = {}; void load()
})
async function load() {
  const id = ++requestId
  loading.value = true; error.value = ''; result.value = { ...result.value, items: [], total: 0 }
  const params = new URLSearchParams({ platform: platform.value, page: String(page.value) })
  if (props.mode !== 'heat') params.set('category', category.value)
  if (hour.value) params.set('hour', hour.value)
  try { const data = await getData(`/events/${props.eventId}/propagation-evidence?${params}`); if (id === requestId) result.value = data }
  catch (e: any) { if (id === requestId) error.value = e?.message || '读取来源失败' }
  finally { if (id === requestId) loading.value = false }
}
function select(p: string, c: string, h: any) { platform.value = p; category.value = c; hour.value = String(h || ''); page.value = 1; void load() }
function reset() { hour.value = ''; page.value = 1; void load() }
function name(p: string) { return names[p] || p || '-' }
function time(v: any) { return v ? String(v).replace('T', ' ').slice(0, 16) : '-' }
function fixed(v: any) { return v == null || !Number.isFinite(Number(v)) ? '-' : Number(v).toFixed(2) }
function safeUrl(v: any) { try { const u = new URL(String(v)); return ['http:', 'https:'].includes(u.protocol) ? u.href : undefined } catch { return undefined } }
function status(row: any) {
  const a = (analysis.value.activations || []).find((v: any) => v.platform === row.platform && v.category === row.category)
  if (!a) return '峰值不足 35'
  if (time(row.time_bucket) === time(a.first_hot_time)) return '首次达到阈值'
  return Number(row.attention) >= Number(a.threshold) ? '达到阈值' : '未达到阈值'
}
</script>

<style scoped>
.evidence { min-height: 180px; overflow-wrap: anywhere; }
.evidence p { line-height: 1.7; color: #475569; margin: 12px 0; }
.evidence h3 { font-size: 15px; margin: 18px 0 10px; }
.selection { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 18px; }
.selection .el-select { width: 155px; }
.evidence a { color: #2563eb; }
.evidence .el-pagination { margin-top: 12px; max-width: 100%; overflow-x: auto; }
</style>
