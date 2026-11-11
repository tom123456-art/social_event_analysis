<template>
  <!-- 集中展示传播结论对应的分钟统计、日度热度与原文记录，便于追溯依据。 -->
  <el-dialog :model-value="Boolean(mode)" :title="titles[mode]" width="min(1100px, 94vw)" @close="$emit('close')">
    <div v-loading="loading" class="evidence">
      <!-- 按当前错误或提示状态显示消息，不把后端技术堆栈直接放入页面。 -->
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <!-- 点击“重试”时执行 load。 -->
      <el-button v-if="error" @click="load">重试</el-button>
      <p v-if="mode === 'daily'">统计日期：{{ dailySelection?.date }}，仅使用当天新闻计算当天峰值、阈值与首达时间。时间差以当天最早达阈值的平台为基准，忽略秒。</p>
      <p v-else>统计窗口：{{ time(summary.window_start).slice(0, 10) }} 至 {{ time(summary.window_end).slice(0, 10) }}，以数据最新日期为准。新闻按实际发布时间的时分统计，忽略秒。</p>
      <template v-if="mode !== 'heat'">
        <p><strong>类别关注指数 = √(类别新闻数 ÷ 平台该分钟有类别新闻总数 × 100 × 类别平均内容热度指数)</strong></p>
        <p>每个平台、类别分别取窗口峰值 P；P 小于 35 不参与。其余阈值为 max(35, P × 70%)，首次达到阈值的分钟为首达时间。这是阈值识别，不是环比上涨，也不证明事件因果传播。</p>
        <!-- 仅当 mode === 'lead' 成立时显示此处内容，条件变化时自动更新。 -->
        <p v-if="mode === 'lead'">比较所有平台、类别的首达时间，取最早者。同一分钟首达为并列，时间差按实际日期和时分计算。</p>
        <!-- 仅当 mode === 'span' 成立时显示此处内容，条件变化时自动更新。 -->
        <p v-if="mode === 'span'">跨度 = 各平台最晚首达时间 − 全局最早首达时间。不同平台可能对应不同类别，并不代表同一事件覆盖这些平台。</p>
        <!-- 仅当 mode === 'relations' 成立时显示此处内容，条件变化时自动更新。 -->
        <p v-if="mode === 'relations'">同类别按首达时间排序，统计相邻平台且间隔大于 0、不超过 4320 分钟的关系。同时达到不计方向。关系数是类别—平台对数，不是转载次数。</p>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <el-table v-if="mode === 'relations'" :data="analysis.relations || []" max-height="220">
          <el-table-column prop="category" label="类别" width="75" />
          <el-table-column label="先达平台 / 时间" min-width="210"><template #default="{ row }"><el-button link type="primary" @click="select(row.source_platform, row.category, row.source_time)">{{ name(row.source_platform) }} {{ time(row.source_time) }}</el-button></template></el-table-column>
          <el-table-column label="后达平台 / 时间" min-width="210"><template #default="{ row }"><el-button link type="primary" @click="select(row.target_platform, row.category, row.target_time)">{{ name(row.target_platform) }} {{ time(row.target_time) }}</el-button></template></el-table-column>
          <el-table-column prop="lag_minutes" label="间隔/分钟" width="100" />
        </el-table>
        <h3>平台与类别的首达时间比较</h3>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
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
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <el-table :data="dashboard.recentPlatformTimeline || []" @row-click="(row: any) => select(row.platform, '', '')">
          <el-table-column label="平台"><template #default="{ row }">{{ name(row.platform) }}</template></el-table-column>
          <el-table-column prop="content_count" label="新闻数" />
          <el-table-column label="加权平均热度"><template #default="{ row }">{{ fixed(row.average_heat_index) }}</template></el-table-column>
          <el-table-column label="累计热度"><template #default="{ row }">{{ fixed(row.hot_score) }}</template></el-table-column>
        </el-table>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <el-table :data="daily" max-height="220">
          <el-table-column label="日期"><template #default="{ row }">{{ time(row.time_bucket).slice(0, 10) }}</template></el-table-column>
          <el-table-column prop="content_count" label="新闻数" />
          <el-table-column label="日均指数"><template #default="{ row }">{{ fixed(row.average_heat_index) }}</template></el-table-column>
          <el-table-column label="当日贡献"><template #default="{ row }">{{ fixed(Number(row.average_heat_index) * Number(row.content_count)) }}</template></el-table-column>
        </el-table>
      </template>
      <div class="selection">
        <!-- 将当前控件的输入或选择同步到 platform，供查询或提交时读取。 -->
        <el-select v-model="platform" aria-label="平台" @change="reset"><el-option v-for="p in platforms" :key="p" :label="name(p)" :value="p" /></el-select>
        <!-- 将当前控件的输入或选择同步到 category，供查询或提交时读取。 -->
        <el-select v-if="mode !== 'heat'" v-model="category" aria-label="类别" @change="reset"><el-option v-for="c in categories" :key="c" :label="c" :value="c" /></el-select>
        <!-- 点击“整个窗口”时执行 reset。 -->
        <el-button v-if="hour" link type="primary" @click="reset">{{ mode === 'daily' ? '当天全部分钟' : '整个窗口' }}</el-button>
      </div>
      <template v-if="mode !== 'heat'">
        <h3>逐分钟计算 · {{ name(platform) }} · {{ category }}</h3>
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <el-table :data="hourly" max-height="230">
          <el-table-column label="时间" min-width="160"><template #default="{ row }">{{ time(row.time_bucket) }}</template></el-table-column>
          <el-table-column prop="content_count" label="类别新闻数" width="100" />
          <el-table-column prop="platform_content_count" label="平台分钟总数" width="110" />
          <el-table-column label="占比 %" width="90"><template #default="{ row }">{{ fixed(row.coverage_share) }}</template></el-table-column>
          <el-table-column prop="average_relative_heat_index" label="平均热度" width="95" />
          <el-table-column label="关注指数" width="95"><template #default="{ row }">{{ fixed(row.attention) }}</template></el-table-column>
          <el-table-column label="阈值状态" min-width="130"><template #default="{ row }">{{ status(row) }}</template></el-table-column>
          <el-table-column label="新闻" width="70"><template #default="{ row }"><el-button link type="primary" @click="select(platform, category, row.time_bucket)">查看</el-button></template></el-table-column>
        </el-table>
      </template>
      <h3>参与计算的新闻 · {{ name(platform) }}{{ mode !== 'heat' ? ` · ${category}` : '' }} · {{ hour ? time(hour) : (mode === 'daily' ? dailySelection?.date : '整个窗口') }} · 共 {{ result.total || 0 }} 条</h3>
      <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
      <el-table :data="result.items || []" max-height="320">
        <el-table-column label="标题 / 原文" min-width="260"><template #default="{ row }"><a v-if="safeUrl(row.source_url)" :href="safeUrl(row.source_url)" target="_blank" rel="noopener noreferrer">{{ row.title || row.clean_text || row.content_id }}</a><span v-else>{{ row.title || row.clean_text || row.content_id }}</span></template></el-table-column>
        <el-table-column label="发布时间" min-width="160"><template #default="{ row }">{{ time(row.publish_time) }}</template></el-table-column>
        <el-table-column label="内容热度指数" width="120"><template #default="{ row }">{{ fixed(row.platform_heat_index) }}</template></el-table-column>
        <el-table-column prop="like_count" label="赞" width="85" /><el-table-column prop="comment_count" label="评论" width="85" />
      </el-table>
      <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
      <el-pagination v-if="result.total > 20" v-model:current-page="page" :page-size="20" :total="Number(result.total)" layout="prev, pager, next, total" @current-change="load" />
      <p>内容热度沿用 ETL 的平台内 0–100 相对指数：在同平台、同数据集内，对可用互动字段按 log(1 + 互动量) 求百分位排名，再对有效字段等权平均。腾讯使用赞、评论、转发、收藏；搜狐和澎湃使用赞、评论；新浪和网易使用评论。字段须有至少 1% 的记录大于零才参与，无有效字段则指数缺失。类别分钟平均按现有 SQL 将缺失指数计为 0；累计热度沿用日均指数的计算口径。</p>
      <p>原文用于核查，数据为当前数据库快照。单条新闻也可能达到阈值，需结合样本量判断。未观测分钟不补零；窗口开始前是否已活跃未知；系统阈值未经显著性检验。</p>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getData, getErrorMessage } from '../../api/client'
// 声明父组件传入的数据与控制状态，供模板和计算属性读取。
const props = defineProps<{ mode: string; dashboard: any; dailySelection?: { date: string; platform: string; category: string; time: string } }>()
defineEmits<{ close: [] }>()
// 将证据模式转换为弹框标题。
const titles: Record<string, string> = { daily: '每日类别响应 · 当天依据', lead: '最早达到阈值 · 结论来源', heat: '累计热度最高 · 结论来源', span: '各平台首达时间跨度 · 结论来源', relations: '同类别先后关系 · 结论来源' }
// 定义平台编号和中文名称的对应关系。
const names: Record<string, string> = { TENCENT_NEWS: '腾讯新闻', NETEASE_NEWS: '网易新闻', SOHU_NEWS: '搜狐新闻', SINA_NEWS: '新浪新闻', THE_PAPER: '澎湃新闻' }
// 定义可展示或选择的平台集合，供循环和筛选使用。
const platforms = Object.keys(names)
// 定义可选新闻类别，供筛选控件生成选项。
const categories = ['财经', '政治', '科技', '体育', '文娱', '社会', '综合']
// 保存平台、类别等当前筛选条件，后续查询或计算据此过滤。
const platform = ref(''), category = ref(''), hour = ref(''), page = ref(1)
// 保存接口结果和加载、错误状态，供证据区域显示。
const result = ref<any>({}), loading = ref(false), error = ref('')
// 每次查询递增序号，关闭或切换条件后忽略旧请求的迟到响应。
let requestId = 0
// 从证据响应中提取分析结果，缺失时使用空对象。
const analysis = computed(() => result.value.analysis || {})
// 优先使用证据摘要，缺失时回退到仪表盘的传播摘要。
const summary = computed(() => analysis.value.summary || props.dashboard.categoryPropagationSummary || {})
// 仅保留所选平台和类别的分钟统计。
const hourly = computed(() => (analysis.value.hourly || []).filter((r: any) => r.platform === platform.value && r.category === category.value))
// 按日期聚合当前样本，或读取当前筛选范围内的日度记录。
const daily = computed(() => (props.dashboard.platformDailyHeat || []).filter((r: any) => r.platform === platform.value && time(r.time_bucket).slice(0, 10) >= time(summary.value.window_start).slice(0, 10) && time(r.time_bucket).slice(0, 10) <= time(summary.value.window_end).slice(0, 10)))
// 按结论类型预选领先平台或最高热度平台，并重置证据分页。
watch(() => props.mode, mode => {
  // 关闭弹框时递增序号，让尚未结束的旧请求失去更新界面的资格。
  if (!mode) { ++requestId; return }
  const s = props.dashboard.categoryPropagationSummary || {}
  // 按近期累计热度排序，取首项作为热度模式默认平台。
  const top = [...(props.dashboard.recentPlatformTimeline || [])].sort((a: any, b: any) => Number(b.hot_score) - Number(a.hot_score))[0]
  platform.value = (mode === 'heat' ? top?.platform : s.lead_platform) || platforms[0]
  category.value = s.lead_category || categories[0]
  // 最早达阈值模式定位到首达分钟，其他模式默认不限制分钟。
  hour.value = mode === 'lead' ? String(s.first_hot_time || '') : ''
  if (mode === 'daily' && props.dailySelection) {
    platform.value = props.dailySelection.platform
    category.value = props.dailySelection.category
    hour.value = props.dailySelection.time
  }
  page.value = 1; result.value = {}; void load()
})
// 热度模式查询平台范围，其他模式进一步限定类别，可按分钟定位原文。
async function load() {
  // 捕获本次请求序号，回调只有在序号仍是最新时才可写入结果。
  const id = ++requestId
  // 加载前清空旧明细和旧错误，保留已取得的其他分析字段。
  loading.value = true; error.value = ''; result.value = { ...result.value, items: [], total: 0 }
  const params = new URLSearchParams({ platform: platform.value, page: String(page.value) })
  // 热度证据按整个平台查看，其余结论再限定类别；选择分钟后继续缩小范围。
  if (props.mode !== 'heat') params.set('category', category.value)
  if (hour.value) params.set('hour', hour.value)
  if (props.mode === 'daily' && props.dailySelection) params.set('date', props.dailySelection.date)
  try { const data = await getData(`/analysis/propagation-evidence?${params}`); if (id === requestId) result.value = data }
  catch (e: any) { if (id === requestId) error.value = getErrorMessage(e, '读取来源失败，请稍后重试。') }
  finally { if (id === requestId) loading.value = false }
}
// 点击分钟统计行后同步平台、类别和分钟，回到第一页重新读取原文。
function select(p: string, c: string, h: any) { platform.value = p; category.value = c; hour.value = String(h || ''); page.value = 1; void load() }
// 取消分钟定位但保留平台和类别，再从第一页读取证据。
function reset() { hour.value = ''; page.value = 1; void load() }
// 将平台编码转成中文名称，未知平台显示其他平台。
function name(p: string) { return names[p] || '其他平台' }
// 将后端时间转换为当前页面使用的显示或比较形式。
function time(v: any) { return v ? String(v).replace('T', ' ').slice(0, 16) : '-' }
// 有效数值按指定小数位格式化，缺失和非法数值显示占位符。
function fixed(v: any) { return v == null || !Number.isFinite(Number(v)) ? '-' : Number(v).toFixed(2) }
function safeUrl(v: any) { try { const u = new URL(String(v)); return ['http:', 'https:'].includes(u.protocol) ? u.href : undefined } catch { return undefined } }
// 使用同一平台与类别的激活阈值，区分首次达标与后续达标。
function status(row: any) {
  // 找出与当前分钟记录同平台、同类别的激活信息。
  const a = (analysis.value.activations || []).find((v: any) => v.platform === row.platform && v.category === row.category)
  // 未激活表示峰值不足门槛；首达分钟与后续达标分钟分别显示。
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
