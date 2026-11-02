<template>
  <!-- 上传、增量处理和批次日志共用任务进度区域，进度以后台阶段状态为依据。 -->
  <div class="page">
    <div class="page-head">
      <div>
        <h2>ETL任务管理</h2>
      </div>
      <div class="head-actions">
        <!-- 点击“运行增量处理”时执行 runCurrentEtl。 -->
        <el-button type="primary" :loading="etlRunning" :disabled="uploadLoading || etlRunning" @click="runCurrentEtl">运行增量处理</el-button>
        <!-- 点击“刷新批次”时执行 load。 -->
        <el-button type="primary" @click="load">刷新批次</el-button>
      </div>
    </div>

    <div class="toolbar">
      <!-- 将“批次号”的输入或选择同步到 batchQuery，供查询或提交时读取。 -->
      <el-input v-model="batchQuery" placeholder="批次号" style="width:190px" clearable />
      <!-- 将“状态”的输入或选择同步到 status，供查询或提交时读取。 -->
      <el-select v-model="status" placeholder="状态" style="width:130px">
        <el-option label="全部" value="全部" />
        <el-option label="成功" value="SUCCESS" />
        <el-option label="失败" value="FAILED" />
      </el-select>
      <!-- 点击“查询”时执行 search。 -->
      <el-button type="primary" @click="search">查询</el-button>
      <!-- 点击“重置”时执行 reset。 -->
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="grid two equal-grid" style="margin-bottom:12px">
      <div class="card">
        <div class="card-title">替换原始数据 <span>上传后重建分析结果</span></div>
        <!-- 接收用户选择的文件，并交给自定义上传函数提交到后端。 -->
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
        <!-- 仅当 uploadResult 成立时显示此处内容，条件变化时自动更新。 -->
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
        <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
        <table class="table fixed-rows">
          <thead><tr><th>批次号</th><th>事件</th><th>阶段</th><th>源记录</th><th>有效</th><th>状态</th></tr></thead>
          <tbody>
            <!-- 按 batch in pagedBatches 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="batch in pagedBatches" :key="batch.batch_id" @click="select(batch.batch_id)">
              <td>{{ batch.batch_id }}</td>
              <td>{{ eventName(batch.event_id) }}</td>
              <td>{{ stageName(batch.current_stage) }}</td>
              <td>{{ batch.source_count }}</td>
              <td>{{ batch.valid_count }}</td>
              <td><span class="tag" :class="batch.status === 'SUCCESS' ? 'green' : 'red'">{{ statusName(batch.status) }}</span></td>
            </tr>
            <!-- 按 i in emptyBatchRows 循环生成表格行，使用 key 保持行标识稳定。 -->
            <tr v-for="i in emptyBatchRows" :key="`batch-empty-${i}`"><td colspan="6"></td></tr>
          </tbody>
        </table>
        <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
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
          <!-- 仅当 !logs.length 成立时显示此处内容，条件变化时自动更新。 -->
          <div class="empty compact-empty" v-if="!logs.length">请选择批次查看日志</div>
          <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
          <table v-else class="table fixed-rows">
            <thead><tr><th>阶段</th><th>任务</th><th>输入</th><th>输出</th><th>状态</th></tr></thead>
            <tbody>
              <!-- 按 log in pagedLogs 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="log in pagedLogs" :key="log.id">
                <td>{{ stageName(log.task_stage) }}</td>
                <td>{{ taskName(log.task_name) }}</td>
                <td>{{ log.input_count }}</td>
                <td>{{ log.output_count }}</td>
                <td>{{ statusName(log.status) }}</td>
              </tr>
              <!-- 按 i in emptyLogRows 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="i in emptyLogRows" :key="`log-empty-${i}`"><td colspan="5"></td></tr>
            </tbody>
          </table>
          <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
          <el-pagination v-if="logs.length" class="table-pagination" v-model:current-page="logPage" :page-size="pageSize" layout="total, prev, pager, next" :total="logs.length" />
        </div>
      </div>
    </div>

  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getData, getErrorMessage } from '../../api/client'
import { chineseMessage, statusName, stageName, taskName, eventName } from '../../utils/userText'
import { notifyDatabaseSynced, useDatabaseAutoRefresh } from '../../composables/useDatabaseAutoRefresh'

// 保存批次列表和当前选中批次的日志，分别绑定两张表格。
const batches = ref<any[]>([])
const logs = ref<any[]>([])
// 保存 CSV 上传结果，尚未上传时没有结果对象。
const uploadResult = ref<any>(null)
// 分别控制上传操作和增量处理操作的加载状态。
const uploadLoading = ref(false)
const etlRunning = ref(false)
// 记录选中的批次编号，查询日志时以它作为接口路径参数。
const selectedBatch = ref('')
// 批次检索词与状态筛选值只用于过滤已读取的批次列表。
const batchQuery = ref('')
const status = ref('全部')
// 批次表与日志表各自分页，每页显示八条。
const batchPage = ref(1)
const logPage = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 8
// 远程 ETL 为长任务，请求超时与开发代理保持一致，最多等待三十五分钟。
const etlRequestTimeout = 2100000
// 保存进度轮询定时器编号，便于请求结束时取消轮询。
const progressTimer = ref<number>()
// 保存监控开始时刻，用于估算任务耗时和剩余时间。
const progressStartedAt = ref(0)
// 记录提交前的最新批次，等待后台生成不同编号的新批次。
const progressBaselineBatchId = ref('')
// 集中保存进度条、当前步骤、状态说明和失败标记。
const progress = ref({ batchId: '', percent: 0, activeStage: 0, statusText: '等待任务', detail: '尚未开始 ETL', eta: '', failed: false })
// 绑定本次已识别的新批次，防止后续轮询跟踪到其他批次。
const progressTrackedBatchId = ref('')
// 后端阶段码映射为前端进度百分比、步骤下标与中文名称。
const stageProgress: Record<string, { percent: number, activeStage: number, text: string }> = {
  ODS: { percent: 20, activeStage: 0, text: '原始数据接入' },
  DWD: { percent: 45, activeStage: 1, text: '明细清洗' },
  DWS: { percent: 65, activeStage: 2, text: '指标汇总' },
  ADS: { percent: 82, activeStage: 3, text: '应用指标生成' },
  MYSQL_SYNC: { percent: 95, activeStage: 4, text: '数据库同步' }
}

// 同时满足编号检索和状态筛选的批次才保留。
const filteredBatches = computed(() => batches.value.filter(item => {
  const matchBatch = !batchQuery.value || String(item.batch_id).includes(batchQuery.value)
  const matchStatus = status.value === '全部' || item.status === status.value
  return matchBatch && matchStatus
}))
// 从已加载列表找到选中批次，首项则作为最新批次。
const selected = computed(() => batches.value.find(item => item.batch_id === selectedBatch.value))
// 读取快照关联的最新批次，供同步状态和时间展示。
const latestBatch = computed(() => batches.value[0])
// 根据两张表各自的页码截取当前页，不修改完整列表。
const pagedBatches = computed(() => filteredBatches.value.slice((batchPage.value - 1) * pageSize, batchPage.value * pageSize))
const pagedLogs = computed(() => logs.value.slice((logPage.value - 1) * pageSize, logPage.value * pageSize))
// 计算需要补齐的空行数，使批次表与日志表保留固定行高。
const emptyBatchRows = computed(() => Math.max(0, pageSize - pagedBatches.value.length))
const emptyLogRows = computed(() => Math.max(0, pageSize - pagedLogs.value.length))

async function load() {
  // 最多读取五十个批次，首次进入时自动选中最新批次并读取日志。
  batches.value = await getData('/etl/batches?limit=50').catch(() => [])
  if (!selectedBatch.value && batches.value[0]) await select(batches.value[0].batch_id)
}
// 上传 CSV 替换原始输入，成功后通知其他页面读取新快照并展示本批次日志。
async function uploadCsv(option: any) {
  uploadLoading.value = true
  await startProgressMonitor()
  try {
    // 用 multipart 表单携带用户选择的文件，供后端读取 CSV 原始内容。
    const formData = new FormData()
    formData.append('file', option.file)
    const response = await api.post('/etl/raw/replace', formData, { timeout: etlRequestTimeout })
    // HTTP 成功不代表业务成功，仍需检查后端统一响应中的业务码。
    if (response.data?.code !== 0) throw new Error(chineseMessage(response.data?.message, '数据文件上传失败，请稍后重试。'))
    // 保存上传结果并把本次任务标记为完成，随后选中后端返回的批次。
    uploadResult.value = response.data.data
    updateProgress({ status: 'SUCCESS', current_stage: 'MYSQL_SYNC', batch_id: uploadResult.value.etl_batch_id })
    selectedBatch.value = uploadResult.value.etl_batch_id
    notifyDatabaseSynced()
    ElMessage.success(`CSV已上传并同步数据库，批次 ${uploadResult.value.etl_batch_id}`)
    await load()
    await select(uploadResult.value.etl_batch_id)
    // 通知上传组件本次上传成功，结束组件自身的上传流程。
    option.onSuccess?.(response.data)
  } catch (error: any) {
    const message = getErrorMessage(error, '数据文件上传失败，请检查文件格式或稍后重试。')
    ElMessage.error(message)
    // 通知上传组件失败，避免组件仍停留在上传中状态。
    option.onError?.(error)
  } finally {
    stopProgressMonitor()
    uploadLoading.value = false
  }}
// 对现有输入执行处理，区分无新增记录、首次建立基线和增量完成三种结果。
async function runCurrentEtl() {
  etlRunning.value = true
  await startProgressMonitor()
  try {
    // 不重新上传文件，直接让后端处理现有原始数据。
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
// 将阶段映射为展示进度；剩余时间按已耗时估算，并非后台精确调度时间。
function updateProgress(batch: any) {
  // 统一任务与阶段编码的大小写，缺少状态时按运行中处理。
  const status = String(batch?.status || 'RUNNING').toUpperCase()
  const stage = String(batch?.current_stage || '').toUpperCase()
  const item = stageProgress[stage]
  // 区分失败与完成终态，失败时保留此前已展示的有效进度。
  const failed = status === 'FAILED'
  const previous = progress.value
  const done = status === 'SUCCESS'
  const percent = done ? 100 : failed ? Math.max(5, item?.percent || previous.percent || 5) : item?.percent || 5
  // 将已耗时转换为秒，再按当前阶段百分比估算剩余秒数。
  const elapsed = progressStartedAt.value ? Math.max(1, Math.round((Date.now() - progressStartedAt.value) / 1000)) : 0
  const etaSeconds = !done && percent > 5 ? Math.max(1, Math.round(elapsed * (100 - percent) / percent)) : 0
  // 一次性替换进度对象，让步骤条、说明文本和错误状态同步更新。
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
// 先记录旧批次基线，再绑定新批次，防止把历史成功任务误认为本次进度。
async function pollProgress() {
  try {
    // 绕过业务 GET 缓存，直接查询后台最新一条批次状态。
    const response = await api.get('/etl/batches?limit=1')
    const batch = response.data?.data?.[0]
    const batchId = String(batch?.batch_id || '')
    if (!batchId) return
    if (!progressTrackedBatchId.value) {
      if (!progressBaselineBatchId.value) {
        progressBaselineBatchId.value = batchId
        return
      }
      // 仍是提交前的旧批次则继续等待，不更新为旧批次的完成状态。
      if (batchId === progressBaselineBatchId.value) return
      progressTrackedBatchId.value = batchId
    }
    // 已绑定批次后忽略其他批次，仅刷新本次跟踪任务。
    if (batchId !== progressTrackedBatchId.value) return
    updateProgress(batch)
  } catch {
    // 状态接口暂时不可用时保留最后一次成功读取的进度。
  }
}
async function startProgressMonitor() {
  // 启动前先清理旧轮询并重置本次监控的时刻、基线和绑定编号。
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
      // 状态接口暂不可用时，由首次成功轮询建立旧批次基线。
    }
  }
  // 每两秒查询进度，并立即执行一次以免首次展示等待整个周期。
  progressTimer.value = window.setInterval(() => void pollProgress(), 2000)
  void pollProgress()
}
// 请求结束时停止进度轮询，同时清理定时器引用。
function stopProgressMonitor() {
  if (progressTimer.value) window.clearInterval(progressTimer.value)
  progressTimer.value = undefined
}
function formatDuration(seconds: number) {
  // 不足一分钟只显示秒，否则显示分钟和剩余秒数。
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.floor(seconds / 60)} 分钟 ${seconds % 60} 秒`
}
async function select(batchId: string) {
  // 切换批次时替换日志列表，并将日志分页复位到第一页。
  selectedBatch.value = batchId
  logs.value = await getData(`/etl/batches/${batchId}/logs`).catch(() => [])
  logPage.value = 1
}
// 执行当前查询动作，或显示已筛选出的批次数量。
function search() { ElMessage.success(`查询到 ${filteredBatches.value.length} 个批次`) }
// 恢复默认筛选或表单字段，并清除上次操作残留状态。
function reset() { batchQuery.value = ''; status.value = '全部'; load() }

onMounted(load)
useDatabaseAutoRefresh(load)
</script>
