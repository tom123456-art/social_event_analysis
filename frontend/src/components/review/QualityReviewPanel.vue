<template>
  <section class="review-panel">
    <div class="review-toolbar">
      <el-input v-model="query" placeholder="标题、平台、内容 ID" clearable @keyup.enter="search" />
      <el-select v-model="status" placeholder="全部审核状态" clearable @change="search">
        <el-option label="待审核" value="PENDING" /><el-option label="通过" value="APPROVED" /><el-option label="驳回" value="REJECTED" />
      </el-select>
      <el-select v-model="applyStatus" placeholder="全部生效状态" clearable @change="search">
        <el-option label="待应用" value="WAITING" /><el-option label="处理中" value="PROCESSING" />
        <el-option label="已生效" value="APPLIED" /><el-option label="失败" value="FAILED" />
      </el-select>
      <el-button :icon="Search" @click="search">查询</el-button>
      <el-button type="primary" :icon="Upload" :loading="submitting" :disabled="running || waiting === 0" @click="apply">
        应用审核结果（{{ waiting }}）
      </el-button>
      <el-button :icon="Refresh" title="刷新审核队列" aria-label="刷新审核队列" @click="load" />
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="job.id" class="review-job" :class="{ failed: job.status === 'FAILED' }">
      <el-tag :type="job.status === 'FAILED' ? 'danger' : job.status === 'SUCCESS' ? 'success' : 'warning'">{{ jobName(job.status) }}</el-tag>
      <span>{{ job.batch_id || job.id }}</span><span v-if="job.error_message">{{ job.error_message }}</span>
    </div>
    <el-table :data="items" v-loading="loading" row-key="id" empty-text="暂无待审核异常记录">
      <el-table-column prop="platform" label="平台" width="135"><template #default="scope">{{ platformName(scope.row.platform) }}</template></el-table-column>
      <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
      <el-table-column label="异常原因" min-width="230"><template #default="scope"><span>{{ reasonText(scope.row.reasons) }}</span></template></el-table-column>
      <el-table-column label="审核" width="85"><template #default="scope">{{ statusName(scope.row.status) }}</template></el-table-column>
      <el-table-column label="生效状态" width="100"><template #default="scope"><el-tooltip :content="scope.row.apply_error || applyName(scope.row.apply_status)"><span>{{ applyName(scope.row.apply_status) }}</span></el-tooltip></template></el-table-column>
      <el-table-column label="操作" width="150"><template #default="scope">
        <el-button link type="primary" :icon="Edit" @click="open(scope.row.id)">审核</el-button>
        <el-button link :icon="Folder" :disabled="scope.row.apply_status !== 'APPLIED'" @click="archive(scope.row)">归档</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <el-dialog v-model="visible" title="内容修正与审核" width="min(1000px, 94vw)" top="5vh" body-class="quality-review-body" destroy-on-close :close-on-click-modal="false">
      <template v-if="detail.id">
        <div class="review-detail-meta">{{ detail.content_id }} · {{ reasonText(detail.reasons) }}</div>
        <div class="review-comparison">
          <div class="review-column-heading">原始内容</div><div class="review-column-heading">修正内容</div>
          <template v-for="field in fields" :key="field.key">
            <div class="review-original"><label>{{ field.label }}</label><div>{{ detail.original_data[field.key] || '—' }}</div></div>
            <div class="review-edit"><label>{{ field.label }}</label>
              <el-select v-if="field.key === 'platform'" v-model="correction[field.key]" :disabled="locked">
                <el-option v-for="platform in platforms" :key="platform" :label="platformName(platform)" :value="platform" />
              </el-select>
              <el-input v-else v-model="correction[field.key]" :disabled="locked" :type="field.multiline ? 'textarea' : 'text'" :rows="field.key === 'content_text' ? 6 : 2" />
            </div>
          </template>
        </div>
        <el-form label-width="110px" class="review-decision">
          <el-form-item label="人工情感标注"><el-select v-model="manualSentiment" clearable :disabled="locked" placeholder="保留模型判断">
            <el-option label="正面" value="positive" /><el-option label="负面" value="negative" /><el-option label="中性" value="neutral" />
          </el-select></el-form-item>
          <el-form-item label="审核备注"><el-input v-model="note" type="textarea" :rows="2" maxlength="1000" :disabled="locked" /></el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="visible = false">关闭</el-button>
        <el-button :icon="Check" type="primary" :disabled="locked" :loading="saving" @click="save('APPROVED')">修正并通过</el-button>
        <el-button :icon="Close" type="danger" :disabled="locked" :loading="saving" @click="save('REJECTED')">驳回</el-button>
        <el-button :icon="Edit" :disabled="locked" :loading="saving" @click="save('PENDING')">保存待审核</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { readSession } from '../../utils/session'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Upload, Refresh, Edit, Folder, Check, Close } from '@element-plus/icons-vue'
import { getData, postData, putData, deleteData, getErrorMessage } from '../../api/client'
import { notifyDatabaseSynced } from '../../composables/useDatabaseAutoRefresh'
import { platformName, statusName } from '../../utils/userText'

const items = ref<any[]>([]), total = ref(0), waiting = ref(0), page = ref(1), size = 20
const query = ref(''), status = ref(''), applyStatus = ref(''), error = ref('')
const loading = ref(false), submitting = ref(false), saving = ref(false), visible = ref(false)
const detail = ref<any>({}), correction = ref<Record<string, string>>({}), manualSentiment = ref(''), note = ref('')
const job = ref<any>({}), running = computed(() => ['QUEUED', 'RUNNING'].includes(job.value.status))
const locked = computed(() => detail.value.apply_status === 'PROCESSING')
const platforms = ['TENCENT_NEWS', 'SOHU_NEWS', 'SINA_NEWS', 'THE_PAPER', 'NETEASE_NEWS', 'WEIBO']
const fields = [
  { key: 'platform', label: '平台' }, { key: 'title', label: '标题', multiline: true },
  { key: 'content_text', label: '正文', multiline: true }, { key: 'author_name', label: '作者' },
  { key: 'publish_time', label: '发布时间' }, { key: 'crawl_time', label: '采集时间' },
  { key: 'like_count', label: '点赞' }, { key: 'comment_count', label: '评论' },
  { key: 'repost_count', label: '转发' }, { key: 'favorite_count', label: '收藏' },
  { key: 'keywords', label: '关键词', multiline: true }, { key: 'category', label: '类别' },
  { key: 'source_url', label: '来源链接' }, { key: 'image_url', label: '图片链接' },
  { key: 'parent_content_id', label: '父内容 ID' }
]
const reasons: Record<string, string> = { UNKNOWN_PLATFORM: '平台待确认', INVALID_TIME: '时间无效', TIME_CONFLICT: '时间先后冲突',
  SHORT_TEXT: '有效文本不足', ENCODED_HTML: '编码 HTML 残留', SUSPECT_TEXT: '疑似缺字', ID_FORMAT: '内容 ID 格式例外',
  IMAGE_URL: '图片链接异常', POSSIBLE_TRUNCATION: '标题疑似截断', LOW_CONFIDENCE: '情感低置信度' }
function reasonText(value: any) {
  try { return (typeof value === 'string' ? JSON.parse(value) : value || []).map((key: string) => key.startsWith('TEXT_DAMAGE:') ? '原文编码损坏' : reasons[key] || key).join('、') }
  catch { return String(value || '') }
}
function applyName(value: string) { return ({ WAITING: '待应用', PROCESSING: '处理中', APPLIED: '已生效', FAILED: '失败待重试' } as Record<string, string>)[value] || value }
function jobName(value: string) { return ({ QUEUED: '应用任务排队中', RUNNING: '分析更新中', SUCCESS: '应用完成', FAILED: '应用失败' } as Record<string, string>)[value] || value }
async function load() {
  loading.value = true
  try {
    const params = new URLSearchParams({ status: status.value, applyStatus: applyStatus.value, query: query.value, page: String(page.value), size: String(size) })
    const result = await getData<any>(`/admin/reviews?${params}`, true)
    items.value = result.items; total.value = result.total; waiting.value = result.waiting_count; error.value = ''
    const latest = await getData<any>('/admin/reviews/jobs/latest', true)
    const completed = running.value && latest.status === 'SUCCESS'
    job.value = latest
    if (completed) notifyDatabaseSynced()
  } catch (e) { error.value = getErrorMessage(e, '加载审核队列失败') }
  finally { loading.value = false }
}
function search() { page.value = 1; void load() }
async function open(id: number) {
  try {
    detail.value = await getData<any>(`/admin/reviews/${id}`, true)
    correction.value = Object.fromEntries(fields.map(field => [field.key, String(detail.value.corrected_data[field.key] ?? '')]))
    manualSentiment.value = detail.value.manual_sentiment || ''; note.value = detail.value.review_note || ''; visible.value = true
  } catch (e) { ElMessage.error(getErrorMessage(e, '加载审核详情失败')) }
}
async function save(nextStatus: string) {
  saving.value = true
  try {
    await putData(`/admin/reviews/${detail.value.id}`, { revision: detail.value.revision, corrected_data: correction.value,
      status: nextStatus, manual_sentiment: manualSentiment.value || '', review_note: note.value,
      reviewer: readSession().user || 'admin' })
    visible.value = false; ElMessage.success('审核决定已保存'); await load()
  } catch (e) { ElMessage.error(getErrorMessage(e, '保存审核失败')) }
  finally { saving.value = false }
}
async function apply() {
  try {
    await ElMessageBox.confirm(`应用 ${waiting.value} 条审核决定？后台将校验修正记录并完整重建分析，原始 CSV 不变。`, '应用审核结果')
    submitting.value = true
    job.value = await postData('/admin/reviews/apply', {})
    ElMessage.success('应用任务已提交'); await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(getErrorMessage(e, '提交应用任务失败')) }
  finally { submitting.value = false }
}
async function archive(row: any) {
  try {
    await ElMessageBox.confirm('归档后保留审核记录和已生效决定，不物理删除。', '归档审核记录')
    await deleteData(`/admin/reviews/${row.id}`); await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(getErrorMessage(e, '归档失败')) }
}
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { void load(); timer = setInterval(() => { if (!loading.value) void load() }, 5000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })
</script>

<style scoped>
.review-panel { min-width: 0; }
.review-toolbar { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; align-items: center; }
.review-toolbar > .el-input { width: 230px; }
.review-toolbar > .el-select { width: 160px; }
.review-job { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; margin: 12px 0; font-size: 13px; overflow-wrap: anywhere; }
.review-job.failed { color: #b42318; }
.el-pagination { margin-top: 16px; }
.review-detail-meta { font-size: 13px; margin-bottom: 16px; overflow-wrap: anywhere; }
.review-comparison { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px 20px; }
.review-column-heading { font-weight: 600; padding-bottom: 8px; border-bottom: 1px solid #dcdfe6; }
.review-original, .review-edit { min-width: 0; }
.review-original label, .review-edit label { display: block; font-size: 12px; color: #606266; margin-bottom: 6px; }
.review-original > div { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 180px; overflow-y: auto; font-size: 13px; }
.review-edit .el-select { width: 100%; }
.review-decision { margin-top: 24px; }
@media (max-width: 600px) { .review-toolbar > .el-input, .review-toolbar > .el-select { width: 100%; } .review-comparison { gap: 10px; } }
</style>

<style>
.quality-review-body { max-height: calc(100dvh - 220px); overflow-y: auto; }
</style>
