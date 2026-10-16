<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>ETL任务管理</h2>
      </div>
      <div class="head-actions">
        <el-button type="primary" :loading="etlRunning" :disabled="uploadLoading || etlRunning" @click="runCurrentEtl">运行增量处理</el-button>
        <el-button type="primary" @click="load">刷新批次</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="batchQuery" placeholder="批次号" style="width:190px" clearable />
      <el-select v-model="status" placeholder="状态" style="width:130px">
        <el-option label="全部" value="全部" />
        <el-option label="成功" value="SUCCESS" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="grid two equal-grid" style="margin-bottom:12px">
      <div class="card">
        <div class="card-title">替换原始数据 <span>上传后重建分析结果</span></div>
        <el-upload
          drag
          accept=".csv"
          :show-file-list="false"
          :http-request="uploadCsv"
          :disabled="uploadLoading || etlRunning"
        >
          <div class="upload-panel">
            <strong>{{ uploadLoading ? '正在上传...' : '点击或拖拽CSV到这里' }}</strong>
            <span>表头须采用系统支持的字段结构，上传后自动清洗、汇总并写入数据库。</span>
          </div>
        </el-upload>
        <div class="source-note" v-if="uploadResult">
          最近替换：{{ uploadResult.file_name }}，写入 {{ uploadResult.append_count }} 行，ETL 批次 {{ uploadResult.etl_batch_id }}。{{ uploadResult.next_step }}
        </div>
        <div class="etl-action-strip">
          <span>处理成功后才替换原始文件；失败时保留原始文件，数据库结果需按批次状态核查。</span>
        </div>
      </div>

      <div class="card">
        <div class="card-title">数据库同步状态 <span>批次记录</span></div>
        <div class="detail-list">
          <div><span>最近批次</span><b>{{ latestBatch?.batch_id || '-' }}</b></div>
          <div><span>同步状态</span><b>{{ statusName(latestBatch?.status) }}</b></div>
          <div><span>源记录</span><b>{{ latestBatch?.source_count || 0 }} 条</b></div>
          <div><span>有效记录</span><b>{{ latestBatch?.valid_count || 0 }} 条</b></div>
        </div>
        <div class="source-note">本卡片、批次列表和任务日志全部来自 MySQL，不直接读取本地 CSV。</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">ETL阶段 <span>当前批次：{{ selectedBatch || '未选择' }}</span></div>
      <div class="progress-row etl-progress-row">
        <span>{{ progress.statusText }}</span>
        <div class="progress"><i :style="{ width: `${progress.percent}%` }"></i></div>
        <strong>{{ progress.percent }}%</strong>
      </div>
      <div class="source-note etl-progress-note">{{ progress.detail }}<span v-if="progress.eta">，预计还需 {{ progress.eta }}</span></div>
      <el-steps :active="progress.activeStage" :process-status="progress.failed ? 'error' : 'process'" :finish-status="progress.failed ? 'error' : 'success'" simple>
        <el-step title="原始接入" />
        <el-step title="明细清洗" />
        <el-step title="指标汇总" />
        <el-step title="应用统计" />
        <el-step title="数据库同步" />
      </el-steps>
    </div>

    <div class="grid two equal-grid" style="margin-top:12px">
      <div class="card table-card">
        <div class="card-title">批次列表 <span>点击行查看日志</span></div>
        <table class="table fixed-rows">
          <thead><tr><th>批次号</th><th>事件</th><th>阶段</th><th>源记录</th><th>有效</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="batch in pagedBatches" :key="batch.batch_id" @click="select(batch.batch_id)">
              <td>{{ batch.batch_id }}</td>
              <td>{{ eventName(batch.event_id) }}</td>
              <td>{{ stageName(batch.current_stage) }}</td>
              <td>{{ batch.source_count }}</td>
              <td>{{ batch.valid_count }}</td>
              <td><span class="tag" :class="batch.status === 'SUCCESS' ? 'green' : 'red'">{{ statusName(batch.status) }}</span></td>
            </tr>
            <tr v-for="i in emptyBatchRows" :key="`batch-empty-${i}`"><td colspan="6"></td></tr>
          </tbody>
        </table>
        <el-pagination class="table-pagination" v-model:current-page="batchPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredBatches.length" />
      </div>

      <div class="grid">
        <div class="card">
          <div class="card-title">数据质量 <span>当前批次</span></div>
          <div class="grid g4">
            <div class="mini-metric"><label>源记录</label><strong>{{ selected?.source_count || 0 }}</strong></div>
            <div class="mini-metric"><label>有效记录</label><strong>{{ selected?.valid_count || 0 }}</strong></div>
            <div class="mini-metric"><label>脏数据</label><strong>{{ selected?.dirty_count || 0 }}</strong></div>
            <div class="mini-metric"><label>重复数据</label><strong>{{ selected?.duplicate_count || 0 }}</strong></div>
          </div>
        </div>
        <div class="card table-card">
          <div class="card-title">任务日志 <span>任务执行记录</span></div>
          <div class="empty compact-empty" v-if="!logs.length">请选择批次查看日志</div>
          <table v-else class="table fixed-rows">
            <thead><tr><th>阶段</th><th>任务</th><th>输入</th><th>输出</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="log in pagedLogs" :key="log.id">
                <td>{{ stageName(log.task_stage) }}</td>
                <td>{{ taskName(log.task_name) }}</td>
                <td>{{ log.input_count }}</td>
                <td>{{ log.output_count }}</td>
                <td>{{ statusName(log.status) }}</td>
              </tr>
              <tr v-for="i in emptyLogRows" :key="`log-empty-${i}`"><td colspan="5"></td></tr>
            </tbody>
          </table>
          <el-pagination v-if="logs.length" class="table-pagination" v-model:current-page="logPage" :page-size="pageSize" layout="total, prev, pager, next" :total="logs.length" />
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
// ETL 任务页：上传原始 CSV、启动虚拟机 Spark 任务并展示进度。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getData, getErrorMessage } from '../../api/client'
import { chineseMessage, statusName, stageName, taskName, eventName } from '../../utils/userText'
import { notifyDatabaseSynced, useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'

const batches = ref<any[]>([])
const logs = ref<any[]>([])
const uploadResult = ref<any>(null)
const uploadLoading = ref(false)
const etlRunning = ref(false)
const selectedBatch = ref('')
const batchQuery = ref('')
const status = ref('全部')
const batchPage = ref(1)
const logPage = ref(1)
const pageSize = 8
const etlRequestTimeout = 2100000
const progressTimer = ref<number>()
const progressStartedAt = ref(0)
const progressBaselineBatchId = ref('')
const progress = ref({ batchId: '', percent: 0, activeStage: 0, statusText: '等待任务', detail: '尚未开始 ETL', eta: '', failed: false })
const progressTrackedBatchId = ref('')
const stageProgress: Record<string, { percent: number, activeStage: number, text: string }> = {
  ODS: { percent: 20, activeStage: 0, text: '原始数据接入' },
  DWD: { percent: 45, activeStage: 1, text: '明细清洗' },
  DWS: { percent: 65, activeStage: 2, text: '指标汇总' },
  ADS: { percent: 82, activeStage: 3, text: '应用指标生成' },
  MYSQL_SYNC: { percent: 95, activeStage: 4, text: '数据库同步' }
}

const filteredBatches = computed(() => batches.value.filter(item => {
  const matchBatch = !batchQuery.value || String(item.batch_id).includes(batchQuery.value)
  const matchStatus = status.value === '全部' || item.status === status.value
  return matchBatch && matchStatus
}))
const selected = computed(() => batches.value.find(item => item.batch_id === selectedBatch.value))
const latestBatch = computed(() => batches.value[0])
const pagedBatches = computed(() => filteredBatches.value.slice((batchPage.value - 1) * pageSize, batchPage.value * pageSize))
const pagedLogs = computed(() => logs.value.slice((logPage.value - 1) * pageSize, logPage.value * pageSize))
const emptyBatchRows = computed(() => Math.max(0, pageSize - pagedBatches.value.length))
const emptyLogRows = computed(() => Math.max(0, pageSize - pagedLogs.value.length))

async function load() {
  batches.value = await getData('/etl/batches?limit=50').catch(() => [])
  if (!selectedBatch.value && batches.value[0]) await select(batches.value[0].batch_id)
}
async function uploadCsv(option: any) {
  uploadLoading.value = true
  await startProgressMonitor()
  try {
    const formData = new FormData()
    formData.append('file', option.file)
    const response = await api.post('/etl/raw/replace', formData, { timeout: etlRequestTimeout })
    if (response.data?.code !== 0) throw new Error(chineseMessage(response.data?.message, '数据文件上传失败，请稍后重试。'))
    uploadResult.value = response.data.data
    updateProgress({ status: 'SUCCESS', current_stage: 'MYSQL_SYNC', batch_id: uploadResult.value.etl_batch_id })
    selectedBatch.value = uploadResult.value.etl_batch_id
    notifyDatabaseSynced()
    ElMessage.success(`CSV已上传并同步数据库，批次 ${uploadResult.value.etl_batch_id}`)
    await load()
    await select(uploadResult.value.etl_batch_id)
    option.onSuccess?.(response.data)
  } catch (error: any) {
    const message = getErrorMessage(error, '数据文件上传失败，请检查文件格式或稍后重试。')
    ElMessage.error(message)
    option.onError?.(error)
  } finally {
    stopProgressMonitor()
    uploadLoading.value = false
  }}
async function runCurrentEtl() {
  etlRunning.value = true
  await startProgressMonitor()
  try {
    const response = await api.post('/etl/run', {}, { timeout: etlRequestTimeout })
    if (response.data?.code !== 0) throw new Error(chineseMessage(response.data?.message, '数据处理失败，请稍后重试。'))
    const result = response.data.data
    updateProgress({ status: 'SUCCESS', current_stage: 'MYSQL_SYNC', batch_id: result.batch_id })
    selectedBatch.value = result.batch_id
    notifyDatabaseSynced()
    if (result.no_changes) ElMessage.info('原始数据没有新增完整记录，无需执行处理任务')
    else if (result.etl_mode === 'FULL_BASELINE') ElMessage.success(`增量基线已建立，批次 ${result.batch_id}`)
    else ElMessage.success(`增量数据处理已完成，批次 ${result.batch_id}`)
    await load()
    await select(result.batch_id)
  } catch (error: any) {
    const message = getErrorMessage(error, '数据处理失败，请检查任务状态或联系管理员。')
    ElMessage.error(message)
  } finally {
    stopProgressMonitor()
    etlRunning.value = false
  }
}
function updateProgress(batch: any) {
  const status = String(batch?.status || 'RUNNING').toUpperCase()
  const stage = String(batch?.current_stage || '').toUpperCase()
  const item = stageProgress[stage]
  const failed = status === 'FAILED'
  const previous = progress.value
  const done = status === 'SUCCESS'
  const percent = done ? 100 : failed ? Math.max(5, item?.percent || previous.percent || 5) : item?.percent || 5
  const elapsed = progressStartedAt.value ? Math.max(1, Math.round((Date.now() - progressStartedAt.value) / 1000)) : 0
  const etaSeconds = !done && percent > 5 ? Math.max(1, Math.round(elapsed * (100 - percent) / percent)) : 0
  progress.value = {
    batchId: batch?.batch_id || progress.value.batchId,
    percent,
    activeStage: done ? 5 : failed ? (item?.activeStage ?? previous.activeStage) : item?.activeStage || 0,
    statusText: failed ? 'ETL 失败' : done ? 'ETL 完成' : item?.text || '正在准备任务',
    detail: failed ? chineseMessage(batch?.error_message, '任务执行失败，请联系管理员查看后台日志。') : done ? '五个阶段已完成，数据库已同步' : item ? `正在执行 ${item.text}` : '正在上传并等待远程处理任务启动',
    eta: etaSeconds ? formatDuration(etaSeconds) : done ? '' : '计算中',
    failed
  }
}
async function pollProgress() {
  try {
    const response = await api.get('/etl/batches?limit=1')
    const batch = response.data?.data?.[0]
    const batchId = String(batch?.batch_id || '')
    if (!batchId) return
    if (!progressTrackedBatchId.value) {
      if (!progressBaselineBatchId.value) {
        progressBaselineBatchId.value = batchId
        return
      }
      if (batchId === progressBaselineBatchId.value) return
      progressTrackedBatchId.value = batchId
    }
    if (batchId !== progressTrackedBatchId.value) return
    updateProgress(batch)
  } catch {
    // Keep the last known progress while the status request is unavailable.
  }
}
async function startProgressMonitor() {
  stopProgressMonitor()
  progressStartedAt.value = Date.now()
  progressBaselineBatchId.value = String(latestBatch.value?.batch_id || '')
  progressTrackedBatchId.value = ''
  progress.value = { batchId: '', percent: 5, activeStage: 0, statusText: '正在准备任务', detail: '文件已提交，等待远程数据处理开始', eta: '计算中', failed: false }
  if (!progressBaselineBatchId.value) {
    try {
      const response = await api.get('/etl/batches?limit=1')
      progressBaselineBatchId.value = String(response.data?.data?.[0]?.batch_id || '')
    } catch {
      // The first poll establishes the baseline if the status endpoint is temporarily unavailable.
    }
  }
  progressTimer.value = window.setInterval(() => void pollProgress(), 2000)
  void pollProgress()
}
function stopProgressMonitor() {
  if (progressTimer.value) window.clearInterval(progressTimer.value)
  progressTimer.value = undefined
}
function formatDuration(seconds: number) {
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.floor(seconds / 60)} 分钟 ${seconds % 60} 秒`
}
async function select(batchId: string) {
  selectedBatch.value = batchId
  logs.value = await getData(`/etl/batches/${batchId}/logs`).catch(() => [])
  logPage.value = 1
}
function search() { ElMessage.success(`查询到 ${filteredBatches.value.length} 个批次`) }
function reset() { batchQuery.value = ''; status.value = '全部'; load() }

onMounted(load)
useDatabaseAutoRefresh(load)
</script>
