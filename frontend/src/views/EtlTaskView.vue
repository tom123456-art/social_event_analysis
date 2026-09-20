<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>ETL任务管理</h2>
      </div>
      <div class="head-actions">
        <el-button type="primary" :loading="etlRunning" :disabled="uploadLoading || etlRunning" @click="runCurrentEtl">运行当前 Raw ETL</el-button>
        <el-button type="primary" @click="load">刷新批次</el-button>
      </div>
    </div>

    <div class="toolbar">
      <el-input v-model="batchQuery" placeholder="批次号" style="width:190px" clearable />
      <el-select v-model="status" placeholder="状态" style="width:130px">
        <el-option label="全部" value="全部" />
        <el-option label="SUCCESS" value="SUCCESS" />
        <el-option label="FAILED" value="FAILED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="grid two equal-grid" style="margin-bottom:12px">
      <div class="card">
        <div class="card-title">替换数据基底 CSV <span>上传后覆盖当前 Raw 并重建分析结果</span></div>
        <el-upload
          drag
          accept=".csv"
          :show-file-list="false"
          :http-request="uploadCsv"
          :disabled="uploadLoading || etlRunning"
        >
          <div class="upload-panel">
            <strong>{{ uploadLoading ? '正在上传...' : '点击或拖拽CSV到这里' }}</strong>
            <span>表头必须与统一 Raw Schema 一致，上传完成后自动执行 ETL 并提交数据库。</span>
          </div>
        </el-upload>
        <div class="source-note" v-if="uploadResult">
          最近替换：{{ uploadResult.file_name }}，写入 {{ uploadResult.append_count }} 行，ETL 批次 {{ uploadResult.etl_batch_id }}。{{ uploadResult.next_step }}
        </div>
        <div class="etl-action-strip">
          <span>上传请求只有在 ADS 表和 MySQL 同步成功后才返回成功；失败时会恢复上传前的 Raw 快照。</span>
        </div>
      </div>

      <div class="card">
        <div class="card-title">数据库同步状态 <span>etl_batch</span></div>
        <div class="detail-list">
          <div><span>最近批次</span><b>{{ latestBatch?.batch_id || '-' }}</b></div>
          <div><span>同步状态</span><b>{{ latestBatch?.status || '-' }}</b></div>
          <div><span>源记录</span><b>{{ latestBatch?.source_count || 0 }} 条</b></div>
          <div><span>有效记录</span><b>{{ latestBatch?.valid_count || 0 }} 条</b></div>
        </div>
        <div class="source-note">本卡片、批次列表和任务日志全部来自 MySQL，不直接读取本地 CSV。</div>
      </div>
    </div>

    <div class="card">
      <div class="card-title">ETL阶段 <span>当前批次：{{ selectedBatch || '未选择' }}</span></div>
      <el-steps :active="selected?.status === 'SUCCESS' ? 5 : 1" finish-status="success" simple>
        <el-step title="ODS接入" />
        <el-step title="DWD清洗" />
        <el-step title="DWS聚合" />
        <el-step title="ADS统计" />
        <el-step title="MySQL同步" />
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
              <td>{{ batch.event_id }}</td>
              <td>{{ batch.current_stage }}</td>
              <td>{{ batch.source_count }}</td>
              <td>{{ batch.valid_count }}</td>
              <td><span class="tag" :class="batch.status === 'SUCCESS' ? 'green' : 'red'">{{ batch.status }}</span></td>
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
          <div class="card-title">任务日志 <span>etl_task_log</span></div>
          <div class="empty compact-empty" v-if="!logs.length">请选择批次查看日志</div>
          <table v-else class="table fixed-rows">
            <thead><tr><th>阶段</th><th>任务</th><th>输入</th><th>输出</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="log in pagedLogs" :key="log.id">
                <td>{{ log.task_stage }}</td>
                <td>{{ log.task_name }}</td>
                <td>{{ log.input_count }}</td>
                <td>{{ log.output_count }}</td>
                <td>{{ log.status }}</td>
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
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, getData } from '../api/client'
import { notifyDatabaseSynced, useDatabaseAutoRefresh } from '../composables/useDatabaseAutoRefresh'

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
  try {
    const formData = new FormData()
    formData.append('file', option.file)
    const response = await api.post('/etl/raw/replace', formData, { timeout: 300000 })
    if (response.data?.code !== 0) throw new Error(response.data?.message || '上传失败')
    uploadResult.value = response.data.data
    selectedBatch.value = uploadResult.value.etl_batch_id
    notifyDatabaseSynced()
    ElMessage.success(`CSV已上传并同步数据库，批次 ${uploadResult.value.etl_batch_id}`)
    await load()
    await select(uploadResult.value.etl_batch_id)
    option.onSuccess?.(response.data)
  } catch (error: any) {
    const message = error?.response?.data?.message || error?.message || 'CSV上传失败'
    ElMessage.error(message)
    option.onError?.(error)
  } finally {
    uploadLoading.value = false
  }}
async function runCurrentEtl() {
  etlRunning.value = true
  try {
    const response = await api.post('/etl/run', {}, { timeout: 300000 })
    if (response.data?.code !== 0) throw new Error(response.data?.message || 'ETL 执行失败')
    const result = response.data.data
    selectedBatch.value = result.batch_id
    notifyDatabaseSynced()
    ElMessage.success(`当前 Raw CSV ETL 已完成，批次 ${result.batch_id}`)
    await load()
    await select(result.batch_id)
  } catch (error: any) {
    const message = error?.response?.data?.message || error?.message || 'ETL 执行失败'
    ElMessage.error(message)
  } finally {
    etlRunning.value = false
  }
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
