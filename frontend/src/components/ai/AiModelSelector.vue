<template>
  <el-dropdown trigger="click" placement="bottom-end" :disabled="disabled" @command="choose">
    <button class="agent-model-trigger" :disabled="disabled" title="选择模型" aria-label="选择模型">
      <span class="agent-model-dot" :class="{ unavailable: !selected?.configured }" /><span>{{ selected?.name || '通义千问' }}</span><el-icon><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu class="agent-model-menu">
        <el-dropdown-item v-for="item in models" :key="item.id" :command="item.id">
          <div class="agent-model-option"><div><b>{{ item.name }}</b><small>{{ item.model }}</small></div><el-icon v-if="modelId === item.id" class="agent-model-check"><Check /></el-icon></div>
        </el-dropdown-item>
        <el-dropdown-item v-if="!staticMode" command="add" divided><span class="agent-model-add"><el-icon><Plus /></el-icon>新增模型</span></el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <el-dialog v-model="adding" title="新增模型" class="agent-model-dialog" width="500px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!testing" :show-close="!testing" @closed="reset">
    <el-form ref="form" :model="fields" :rules="rules" label-position="top" :disabled="testing" @submit.prevent="testAndAdd">
      <el-form-item label="显示名称" prop="name"><el-input v-model="fields.name" aria-label="显示名称" maxlength="60" placeholder="例如：A 模型" /></el-form-item>
      <el-form-item label="模型 ID" prop="model"><el-input v-model="fields.model" aria-label="模型 ID" maxlength="120" placeholder="例如：qwen-plus" /></el-form-item>
      <el-form-item label="请求地址" prop="requestUrl"><el-input v-model="fields.requestUrl" aria-label="请求地址" maxlength="2048" placeholder="https://api.example.com/v1/chat/completions" /></el-form-item>
      <el-form-item label="API Key" prop="apiKey"><el-input v-model="fields.apiKey" aria-label="API Key" type="password" show-password autocomplete="off" maxlength="2048" placeholder="请输入 API Key" /></el-form-item>
      <el-collapse v-model="expanded"><el-collapse-item title="可选配置" name="optional">
        <el-form-item label="厂商名称（选填）"><el-input v-model="fields.provider" maxlength="60" placeholder="例如：阿里云百炼" /></el-form-item>
        <div class="agent-model-numeric">
          <el-form-item label="超时时间 / 秒（选填）"><el-input-number v-model="fields.timeoutSeconds" :min="5" :max="100" :precision="0" controls-position="right" /></el-form-item>
          <el-form-item label="温度（选填）"><el-input-number v-model="fields.temperature" :min="0" :max="2" :step="0.1" :precision="1" controls-position="right" placeholder="默认" /></el-form-item>
        </div>
      </el-collapse-item></el-collapse>
      <el-alert v-if="failure" :title="failure" type="error" :closable="false" show-icon role="alert" class="agent-model-result" />
      <div class="agent-model-footer"><el-button :disabled="testing" @click="adding = false">取消</el-button><el-button type="primary" :loading="testing" native-type="submit"><el-icon v-if="!testing"><Connection /></el-icon><span>{{ testing ? '正在测试连接' : '测试并添加' }}</span></el-button></div>
    </el-form>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { ArrowDown, Check, Connection, Plus } from '@element-plus/icons-vue'
import { getErrorMessage, postData } from '../../api/client'

export interface AiModel { id: string; name: string; provider: string; model: string; configured: boolean }
const props = defineProps<{ models: AiModel[]; modelId: string; disabled: boolean }>()
const emit = defineEmits<{ select: [id: string]; added: [model: AiModel] }>()
const selected = computed(() => props.models.find(item => item.id === props.modelId))
const staticMode = import.meta.env.VITE_STATIC_MODE === 'true'
const adding = ref(false), testing = ref(false), failure = ref(''), expanded = ref<string[]>([])
const form = ref<FormInstance>()
const initial = () => ({ name: '', model: '', requestUrl: '', apiKey: '', provider: '', timeoutSeconds: 90, temperature: undefined as number | undefined })
const fields = ref(initial())
const required = (message: string) => [{ required: true, whitespace: true, message, trigger: 'blur' }]
const rules: FormRules = { name: required('请填写显示名称'), model: required('请填写供应商提供的模型 ID'), requestUrl: required('请填写完整请求地址'), apiKey: required('请填写 API Key') }
function choose(id: string) { if (id === 'add') { failure.value = ''; adding.value = true } else emit('select', id) }
function reset() { fields.value = initial(); failure.value = ''; expanded.value = []; form.value?.clearValidate() }
async function testAndAdd() {
  if (testing.value || !await form.value?.validate().catch(() => false)) return
  if (props.models.some(item => item.name.toLowerCase() === fields.value.name.trim().toLowerCase())) { failure.value = '显示名称已存在，请换一个名称。'; return }
  try {
    const url = new URL(fields.value.requestUrl.trim())
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || (url.port && url.port !== '443')) throw new Error()
  } catch { failure.value = '请输入完整的 HTTPS 模型接口地址，不要包含账号、查询参数或非标准端口。'; return }
  testing.value = true; failure.value = ''
  try {
    const result = await postData<AiModel>('/ai/models/test-and-save', fields.value)
    emit('added', result); ElMessage.success('连接测试通过，模型已添加并选中。'); adding.value = false
  } catch (e) { failure.value = getErrorMessage(e, '连接测试失败，请检查模型配置后重试。') }
  finally { testing.value = false }
}
</script>

<style>
.agent-model-trigger { display:flex;align-items:center;gap:7px;border:1px solid #dce8e2;background:#f7faf8;color:#426c5d;border-radius:5px;min-height:32px;max-width:200px;padding:5px 9px;font-size:12px!important; }
.agent-model-trigger>span:nth-child(2) { overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }
.agent-model-dot { width:6px;height:6px;flex-shrink:0;border-radius:50%;background:#28a179; }
.agent-model-dot.unavailable { background:#bd8c52; }
.agent-model-menu { min-width:220px;max-width:min(320px,90vw); }
.agent-model-menu .el-dropdown-menu__item { padding:9px 15px; }
.agent-model-option { display:flex;align-items:center;justify-content:space-between;gap:20px;width:100%;min-width:180px; }
.agent-model-option>div { min-width:0; }
.agent-model-option b { display:block;font-size:12px;font-weight:500;overflow-wrap:anywhere; }
.agent-model-option small { display:block;color:#879990;font-size:10px;overflow-wrap:anywhere; }
.agent-model-check { color:#23975d;flex-shrink:0;font-size:17px; }
.agent-model-add { display:flex;align-items:center;gap:8px;color:#227e64;font-size:12px; }
.agent-model-dialog { max-width:calc(100vw - 28px);border-radius:8px; }
.agent-model-dialog .el-dialog__body { max-height:calc(90dvh - 90px);overflow:auto; }
.agent-model-dialog .el-form-item { margin-bottom:17px; }
.agent-model-dialog .el-form-item__label { font-size:12px; }
.agent-model-dialog .el-input-number { width:100%; }
.agent-model-numeric { display:grid;grid-template-columns:1fr 1fr;gap:15px; }
.agent-model-footer { display:flex;justify-content:flex-end;gap:8px;padding-top:18px; }
.agent-model-footer .el-button { margin:0; }
.agent-model-footer .el-button>span { display:flex;align-items:center;gap:6px; }
.agent-model-result { margin-top:15px;overflow-wrap:anywhere; }
@media(max-width:760px) { .agent-model-trigger { max-width:115px;font-size:11px!important;padding:5px 7px; } }
@media(max-width:480px) { .agent-identity b small { display:none; }.agent-subtitle { max-width:130px; }.agent-model-trigger { max-width:100px; }.agent-model-numeric { grid-template-columns:1fr;gap:0; } }
</style>
