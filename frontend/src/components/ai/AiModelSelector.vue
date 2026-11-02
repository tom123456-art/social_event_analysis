<template>
  <!-- 模型选择与新增共用入口，连接测试通过后再通知父组件更新列表。 -->
  <el-dropdown trigger="click" placement="bottom-end" :disabled="disabled" @command="choose">
    <button class="agent-model-trigger" :disabled="disabled" title="选择模型" aria-label="选择模型">
      <span class="agent-model-dot" :class="{ unavailable: !selected?.configured }" /><span>{{ selected?.name || '通义千问' }}</span><el-icon><ArrowDown /></el-icon>
    </button>
    <template #dropdown>
      <el-dropdown-menu class="agent-model-menu">
        <!-- 根据 item in models 循环生成条目，模板中的字段读取当前条目数据。 -->
        <el-dropdown-item v-for="item in models" :key="item.id" :command="item.id">
          <div class="agent-model-option"><div><b>{{ item.name }}</b><small>{{ item.model }}</small></div><el-icon v-if="modelId === item.id" class="agent-model-check"><Check /></el-icon></div>
        </el-dropdown-item>
        <el-dropdown-item v-if="!staticMode" command="add" divided><span class="agent-model-add"><el-icon><Plus /></el-icon>新增模型</span></el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
  <!-- 将弹框显隐与 adding 绑定，集中展示当前操作的详情或表单。 -->
  <el-dialog v-model="adding" title="新增模型" class="agent-model-dialog" width="500px" append-to-body :close-on-click-modal="false" :close-on-press-escape="!testing" :show-close="!testing" @closed="reset">
    <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
    <el-form ref="form" :model="fields" :rules="rules" label-position="top" :disabled="testing" @submit.prevent="testAndAdd">
      <!-- 为“显示名称”展示输入控件，并承接该字段的校验提示。 -->
      <el-form-item label="显示名称" prop="name"><el-input v-model="fields.name" aria-label="显示名称" maxlength="60" placeholder="例如：A 模型" /></el-form-item>
      <!-- 为“模型 ID”展示输入控件，并承接该字段的校验提示。 -->
      <el-form-item label="模型 ID" prop="model"><el-input v-model="fields.model" aria-label="模型 ID" maxlength="120" placeholder="例如：qwen-plus" /></el-form-item>
      <!-- 为“请求地址”展示输入控件，并承接该字段的校验提示。 -->
      <el-form-item label="请求地址" prop="requestUrl"><el-input v-model="fields.requestUrl" aria-label="请求地址" maxlength="2048" placeholder="https://api.example.com/v1/chat/completions" /></el-form-item>
      <!-- 为“API Key”展示输入控件，并承接该字段的校验提示。 -->
      <el-form-item label="API Key" prop="apiKey"><el-input v-model="fields.apiKey" aria-label="API Key" type="password" show-password autocomplete="off" maxlength="2048" placeholder="请输入 API Key" /></el-form-item>
      <el-collapse v-model="expanded"><el-collapse-item title="可选配置" name="optional">
        <!-- 为“厂商名称（选填）”展示输入控件，并承接该字段的校验提示。 -->
        <el-form-item label="厂商名称（选填）"><el-input v-model="fields.provider" maxlength="60" placeholder="例如：阿里云百炼" /></el-form-item>
        <div class="agent-model-numeric">
          <!-- 为“超时时间 / 秒（选填）”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="超时时间 / 秒（选填）"><el-input-number v-model="fields.timeoutSeconds" :min="5" :max="100" :precision="0" controls-position="right" /></el-form-item>
          <!-- 为“温度（选填）”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="温度（选填）"><el-input-number v-model="fields.temperature" :min="0" :max="2" :step="0.1" :precision="1" controls-position="right" placeholder="默认" /></el-form-item>
        </div>
      </el-collapse-item></el-collapse>
      <!-- 按当前错误或提示状态显示消息，不把后端技术堆栈直接放入页面。 -->
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

// configured 表示后端是否已有可用凭据，不在模型列表中暴露密钥。
export interface AiModel { id: string; name: string; provider: string; model: string; configured: boolean }
// 声明父组件传入的数据与控制状态，供模板和计算属性读取。
const props = defineProps<{ models: AiModel[]; modelId: string; disabled: boolean }>()
// 声明向父组件发送的事件及参数类型。
const emit = defineEmits<{ select: [id: string]; added: [model: AiModel] }>()
// 按当前选中编号查找列表中的对应记录。
const selected = computed(() => props.models.find(item => item.id === props.modelId))
// 读取静态模式开关，静态展示时隐藏新增模型操作。
const staticMode = import.meta.env.VITE_STATIC_MODE === 'true'
// 分别保存新增弹框、连接测试、失败提示和可选配置展开状态。
const adding = ref(false), testing = ref(false), failure = ref(''), expanded = ref<string[]>([])
// 保存表单实例或表单字段，用于验证输入并提交数据。
const form = ref<FormInstance>()
// 每次生成独立的默认模型表单，重置时不复用上次对象。
const initial = () => ({ name: '', model: '', requestUrl: '', apiKey: '', provider: '', timeoutSeconds: 90, temperature: undefined as number | undefined })
// 定义允许编辑的字段集合或表单初始字段。
const fields = ref(initial())
// 生成必填校验规则，输入失焦时提示对应字段不能为空。
const required = (message: string) => [{ required: true, whitespace: true, message, trigger: 'blur' }]
// 为模型名称、编号、请求地址和密钥设置各自的必填提示。
const rules: FormRules = { name: required('请填写显示名称'), model: required('请填写供应商提供的模型 ID'), requestUrl: required('请填写完整请求地址'), apiKey: required('请填写 API Key') }
// 选择新增项时打开表单，选择已有模型时向父组件发送选中的编号。
function choose(id: string) { if (id === 'add') { failure.value = ''; adding.value = true } else emit('select', id) }
// 重建默认表单，清空失败消息、折叠状态和上次的表单验证结果。
function reset() { fields.value = initial(); failure.value = ''; expanded.value = []; form.value?.clearValidate() }
// 依次校验表单、名称和 HTTPS 地址；由后端测试并保存模型连接配置。
async function testAndAdd() {
  // 已在测试或表单验证不通过时直接返回，避免发起无效请求。
  if (testing.value || !await form.value?.validate().catch(() => false)) return
  // 名称比较忽略大小写和两侧空白，禁止添加重名模型。
  if (props.models.some(item => item.name.toLowerCase() === fields.value.name.trim().toLowerCase())) { failure.value = '显示名称已存在，请换一个名称。'; return }
  try {
    const url = new URL(fields.value.requestUrl.trim())
    // 地址必须使用标准 HTTPS，且不能携带账号、查询参数、片段或非标准端口。
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || (url.port && url.port !== '443')) throw new Error()
  } catch { failure.value = '请输入完整的 HTTPS 模型接口地址，不要包含账号、查询参数或非标准端口。'; return }
  // 清除旧错误并锁定提交按钮，直到本次连接测试结束。
  testing.value = true; failure.value = ''
  try {
    // 把模型配置发送给后端，后端实际连接成功后才保存并返回模型信息。
    const result = await postData<AiModel>('/ai/models/test-and-save', fields.value)
    // 通知父组件追加新模型，显示成功提示并关闭新增弹框。
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
