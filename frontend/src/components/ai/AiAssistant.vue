<template>
  <!-- 将悬浮入口和对话框挂到页面根部，避免受页面容器的层级与裁剪影响。 -->
  <Teleport to="body">
    <!-- 点击“打开观澜 AI 助手”时执行 openOrb。 -->
    <button ref="orb" class="ai-orb" :style="{ left: `${position.x}px`, top: `${position.y}px` }" aria-label="打开观澜 AI 助手" title="观澜 AI"
      @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragCancel" @click="openOrb"><span>AI</span><i /></button>
    <!-- 将弹框显隐与 visible 绑定，集中展示当前操作的详情或表单。 -->
    <el-dialog v-model="visible" class="observatory-dialog" :show-close="false" :close-on-click-modal="false" :fullscreen="fullscreen" align-center @opened="focusInput" @closed="orb?.focus()">
      <template #header>
        <div class="agent-topbar">
          <div class="agent-identity"><span class="agent-mark">AI</span><div><b>观澜 <small>舆情研判助手</small></b><span class="agent-subtitle">PUBLIC OPINION INTELLIGENCE</span></div></div>
          <div class="agent-window-actions">
            <!-- 把模型列表和当前编号传给选择器，选择或新增后更新当前问答模型。 -->
            <AiModelSelector :models="models" :model-id="modelId" :disabled="busy" @select="modelId = $event" @added="addModel" />
            <!-- 点击当前按钮时执行 fullscreen = !fullscreen。 -->
            <button class="agent-icon" :title="fullscreen ? '还原窗口' : '全屏'" :aria-label="fullscreen ? '还原窗口' : '全屏'" @click="fullscreen = !fullscreen"><el-icon><CopyDocument v-if="fullscreen" /><FullScreen v-else /></el-icon></button>
            <!-- 点击“关闭助手”时执行 visible = false。 -->
            <button class="agent-icon" title="关闭" aria-label="关闭助手" @click="visible = false"><el-icon><Close /></el-icon></button>
          </div>
        </div>
      </template>
      <div class="agent-layout" :class="{ 'show-history': showHistory, 'show-evidence': showEvidence }">
        <aside class="agent-history">
          <!-- 点击“新建对话”时执行 newChat。 -->
          <button class="agent-new" :disabled="busy" @click="newChat"><el-icon><Plus /></el-icon>新建对话</button>
          <label class="history-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索对话" aria-label="搜索历史对话" /></label>
          <div class="history-caption">最近对话 <span>{{ conversations.length }}</span></div>
          <div class="history-items">
            <!-- 根据 item in filtered 循环生成条目，模板中的字段读取当前条目数据。 -->
            <div v-for="item in filtered" :key="item.id" class="history-item" :class="{ active: item.id === current.id }">
              <!-- 点击当前按钮时执行 selectChat(item.id)。 -->
              <button :disabled="busy" @click="selectChat(item.id)"><el-icon><ChatDotRound /></el-icon><span>{{ item.title }}</span></button>
              <!-- 点击“删除对话”时执行 removeChat(item.id)。 -->
              <button class="history-delete" :disabled="busy" title="删除对话" aria-label="删除对话" @click="removeChat(item.id)"><el-icon><Delete /></el-icon></button>
            </div>
            <!-- 仅当 !filtered.length 成立时显示此处内容，条件变化时自动更新。 -->
            <p v-if="!filtered.length" class="history-empty">暂无匹配的对话</p>
          </div>
          <div class="history-bottom"><el-icon><MagicStick /></el-icon><div><b>系统知识空间</b><small>分析快照 / 内容证据</small></div><span class="knowledge-dot" /></div>
        </aside>
        <main class="agent-main">
          <div class="conversation-bar">
            <!-- 点击“历史对话”时执行 showHistory = !showHistory。 -->
            <button class="agent-icon mobile-history-toggle" title="历史对话" aria-label="历史对话" @click="showHistory = !showHistory"><el-icon><Fold /></el-icon></button>
            <span>{{ current.title }}</span><div>
              <!-- 点击“导出对话”时执行 exportChat。 -->
              <button class="agent-icon" :disabled="!current.messages.length || busy" title="导出对话" aria-label="导出对话" @click="exportChat"><el-icon><Download /></el-icon></button>
              <!-- 点击“数据依据”时执行 showEvidence = !showEvidence。 -->
              <button class="agent-icon" :class="{ selected: showEvidence }" title="数据依据" aria-label="数据依据" :aria-expanded="showEvidence" @click="showEvidence = !showEvidence"><el-icon><Document /></el-icon></button>
            </div>
          </div>
          <div ref="scrollArea" class="agent-scroll" @scroll="trackScroll">
            <!-- 仅当 current.messages.length 成立时显示此处内容，条件变化时自动更新。 -->
            <div v-if="current.messages.length" class="agent-messages" aria-live="polite" :aria-busy="busy">
              <!-- 根据 (message, index) in current.messages 循环生成条目，模板中的字段读取当前条目数据。 -->
              <article v-for="(message, index) in current.messages" :key="index" class="agent-message" :class="message.role">
                <div class="message-avatar">{{ message.role === 'assistant' ? 'AI' : '我' }}</div>
                <div class="message-body">
                  <div class="message-byline">{{ message.role === 'assistant' ? '观澜' : '你' }}<small v-if="message.role === 'assistant'">{{ message.error ? '请求未完成' : message.modelName || '舆情研判助手' }}</small></div>
                  <!-- 仅当 !message.content && busy 成立时显示此处内容，条件变化时自动更新。 -->
                  <div v-if="!message.content && busy" class="agent-thinking"><i /><i /><i /><span>{{ progress }}</span></div>
                  <div v-else class="message-content" :class="{ 'message-error': message.error }" v-html="format(message.content)" />
                  <!-- 仅当 message.sources?.length 成立时显示此处内容，条件变化时自动更新。 -->
                  <div v-if="message.sources?.length" class="message-citations"><button @click="selectedSources = message.sources; showEvidence = true"><el-icon><DocumentChecked /></el-icon>{{ message.sources.length }} 项数据依据 <el-icon><ArrowRight /></el-icon></button></div>
                  <!-- 仅当 message.role === 'assistant' && message.content && !busy 成立时显示此处内容，条件变化时自动更新。 -->
                  <div v-if="message.role === 'assistant' && message.content && !busy" class="message-actions">
                    <!-- 点击“复制回答”时执行 copy(message.content)。 -->
                    <button class="agent-icon" title="复制回答" aria-label="复制回答" @click="copy(message.content)"><el-icon><CopyDocument /></el-icon></button>
                    <!-- 点击“重新尝试”时执行 retry。 -->
                    <button v-if="message.error && index === current.messages.length - 1" class="agent-icon" title="重新尝试" aria-label="重新尝试" @click="retry"><el-icon><RefreshRight /></el-icon></button>
                  </div>
                </div>
              </article>
            </div>
            <div v-else class="agent-welcome">
              <div class="welcome-emblem"><el-icon><MagicStick /></el-icon><span>观澜</span></div>
              <div class="welcome-kicker">从数据出发，让洞察发生</div><h2>今天，你想了解哪些舆情？</h2>
              <div class="welcome-tags"><span><i />系统数据</span><span>传播态势</span><span>情感研判</span></div>
              <div class="agent-suggestions"><button v-for="(item, index) in suggestions" :key="item.title" :disabled="busy" @click="send(item.question)"><span class="suggestion-number">0{{ index + 1 }}</span><el-icon><component :is="item.icon" /></el-icon><b>{{ item.title }}</b><span>{{ item.question }}</span><el-icon class="suggestion-arrow"><ArrowRight /></el-icon></button></div>
            </div>
          </div>
          <div class="agent-composer-wrap">
            <div class="agent-tool-options">
              <!-- 点击“联网搜索”时执行 toggleSearch。 -->
              <button type="button" class="agent-web-toggle" :class="{ active: webSearch }" :aria-pressed="webSearch" :disabled="busy" title="联网搜索" @click="toggleSearch"><el-icon><Search /></el-icon>联网搜索</button>
              <!-- 仅当 busy 成立时显示此处内容，条件变化时自动更新。 -->
              <span v-if="busy" class="agent-tool-status" role="status">{{ progress }}</span>
            </div>
            <!-- 仅当 error 成立时显示此处内容，条件变化时自动更新。 -->
            <div v-if="error" class="agent-request-error" role="alert">{{ error }}</div>
            <form class="agent-composer" @submit.prevent="send()">
              <!-- 将“向观澜提问...”的输入或选择同步到 draft，供查询或提交时读取。 -->
              <textarea ref="input" v-model="draft" :disabled="busy" maxlength="2000" rows="2" placeholder="向观澜提问..." aria-label="输入问题" @keydown="keydown" />
              <div class="composer-bottom"><span><el-icon><Connection /></el-icon>系统数据优先 <small>{{ draft.length }}/2000</small></span><button class="agent-send" type="submit" :disabled="busy || !draft.trim()" title="发送" aria-label="发送问题"><el-icon><Promotion /></el-icon></button></div>
            </form>
            <div class="agent-disclaimer">AI 回答仅供辅助研判，请以原始数据与来源核验为准。</div>
          </div>
        </main>
        <!-- 仅当 showEvidence 成立时显示此处内容，条件变化时自动更新。 -->
        <aside v-if="showEvidence" class="agent-evidence">
          <header><b><el-icon><DocumentChecked /></el-icon>数据依据</b><button class="agent-icon" title="关闭数据依据" aria-label="关闭数据依据" @click="showEvidence = false"><el-icon><Close /></el-icon></button></header>
          <div class="evidence-intro"><span class="evidence-line" /><b>本次回答的数据来源</b><p>数据库查询、分析快照与网络资料分别标注；快照不代表实时数据。</p></div>
          <ol v-if="sources.length" class="evidence-list"><li v-for="(source, index) in sources" :key="index"><span class="evidence-index">{{ index + 1 }}</span><div><small>{{ source.kind === 'web' ? '网络资料' : source.kind === 'database' ? '数据库查询' : '分析快照' }}</small><a v-if="safeUrl(source.url)" :href="safeUrl(source.url)" target="_blank" rel="noopener noreferrer">{{ source.name }}</a><b v-else>{{ source.name }}</b><p>{{ source.detail }}</p></div></li></ol>
          <div v-else class="evidence-placeholder"><el-icon><Document /></el-icon><p>暂无引用记录</p></div>
          <div class="evidence-note"><el-icon><InfoFilled /></el-icon><span>统计口径、样本范围与发布时间可能不同，请结合来源解读。</span></div>
        </aside>
      </div>
    </el-dialog>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight, ChatDotRound, Close, Connection, CopyDocument, DataLine, Delete, Document, DocumentChecked, Download, Fold, FullScreen, InfoFilled, MagicStick, Plus, Promotion, RefreshRight, Search, Share, Compass } from '@element-plus/icons-vue'
import { deleteData, getData, getErrorMessage, postData } from '../../api/client'
import { streamAnswer } from '../../api/aiStream'
import '../../styles/ai-assistant.css'
import AiModelSelector, { type AiModel } from './AiModelSelector.vue'

// 对话保留消息角色、所用模型及引用来源，便于历史回放和证据查看。
interface Source { name: string; detail: string; kind?: string; url?: string }
interface Message { role: 'user' | 'assistant'; content: string; sources?: Source[]; error?: boolean; modelName?: string }
interface Conversation { id: string; title: string; messages: Message[]; modelId?: string }
// 控制当前弹框或面板的显示与隐藏。
const visible = ref(false), fullscreen = ref(false), showHistory = ref(false), showEvidence = ref(false)
// 保存提交状态、输入内容和错误信息，提交期间禁止重复发送。
const busy = ref(false), draft = ref(''), search = ref(''), error = ref('')
// 保存联网搜索开关、后端搜索能力和当前回答的进度说明。
const webSearch = ref(false), searchAvailable = ref(false), progress = ref('正在分析查询需求')
// 只有后端声明已配置搜索服务时才允许开启联网搜索。
function toggleSearch() {
  // 已开启时直接关闭；未开启时先检查后端是否具备搜索能力。
  if (webSearch.value) { webSearch.value = false; return }
  if (!searchAvailable.value) { ElMessage.warning('联网搜索尚未配置搜索服务密钥，请联系管理员配置。'); return }
  webSearch.value = true
}
// 只把有效的 HTTP(S) 来源变成链接，异常地址返回空值。
function safeUrl(value?: string) { try { const url = new URL(value || ''); return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined } catch { return undefined } }
// 保存可用模型列表和当前模型编号，发送问题时提交所选模型。
const models = ref<AiModel[]>([]), modelId = ref('qwen')
// 根据模型编号查找当前模型，读取显示名称和配置状态。
const selectedModel = computed(() => models.value.find(item => item.id === modelId.value))
// 新模型加入列表后立即设为当前选中模型。
function addModel(model: AiModel) { models.value.push(model); modelId.value = model.id }
// 保存历史对话的编号与标题，供历史列表循环展示。
const conversations = ref<{ id: string; title: string }[]>([])
// 保存当前选中记录或对话的完整内容，供详情区读取。
const current = ref<Conversation>({ id: '', title: '新对话', messages: [] })
// 保存用户选中回答的引用来源，优先于默认引用显示。
const selectedSources = ref<Source[]>([])
// 按检索词过滤历史对话标题，不修改完整列表。
const filtered = computed(() => conversations.value.filter(item => item.title.toLowerCase().includes(search.value.toLowerCase())))
// 优先显示选中引用，否则回溯最近一条带引用的回答。
const sources = computed(() => selectedSources.value.length ? selectedSources.value : [...current.value.messages].reverse().find(item => item.sources?.length)?.sources || [])
// 保存输入框、悬浮入口和消息容器节点，供聚焦与滚动控制使用。
const input = ref<HTMLTextAreaElement>(), orb = ref<HTMLButtonElement>(), scrollArea = ref<HTMLElement>()
// 按窗口尺寸计算入口初始位置，并保留右下角边距。
const position = ref({ x: Math.max(12, window.innerWidth - 92), y: Math.max(12, window.innerHeight - 120) })
// 定义快捷问题的标题、内容和图标，点击后直接发起问答。
const suggestions = [
  { title: '热点速览', question: '概括一下系统中的热点和整体数据情况', icon: DataLine },
  { title: '传播脉络', question: '哪些平台率先升温，传播关系如何解读？', icon: Share },
  { title: '情绪观察', question: '正负向情感分布如何，有哪些解读注意事项？', icon: MagicStick },
  { title: '系统导航', question: '我想分析一个热点事件，应该从哪些页面入手？', icon: Compass }
]
// 用户向上查看历史消息时暂停自动滚动，避免流式输出打断阅读。
let autoScroll = true
// 保存按下时的指针和入口坐标，拖动时用坐标差更新入口位置。
let drag: { x: number; y: number; startX: number; startY: number; moved: boolean } | undefined
// 拖动标记区分移动入口与点击入口，防止拖拽结束时误开对话框。
let dragged = false
// 将入口限制在屏幕边界以内，四周至少保留十二像素。
function clamp() { position.value.x = Math.max(12, Math.min(window.innerWidth - 76, position.value.x)); position.value.y = Math.max(12, Math.min(window.innerHeight - 76, position.value.y)) }
// 仅响应主按键，记录起始位置并捕获后续指针移动事件。
function dragStart(e: PointerEvent) { if (e.button !== 0) return; dragged = false; drag = { x: e.clientX, y: e.clientY, startX: position.value.x, startY: position.value.y, moved: false }; (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId) }
// 位移超过五像素才视为拖动，移动后再限制入口位置。
function dragMove(e: PointerEvent) { if (!drag) return; const x = e.clientX - drag.x, y = e.clientY - drag.y; if (Math.hypot(x, y) > 5) drag.moved = true; if (drag.moved) { position.value = { x: drag.startX + x, y: drag.startY + y }; clamp() } }
// 松开时记录是否拖动，再清除本轮拖动上下文。
function dragEnd() { dragged = !!drag?.moved; drag = undefined }
// 取消指针操作时阻止随后可能触发的点击，并清理拖动状态。
function dragCancel() { dragged = true; drag = undefined }
// 只有未发生拖动时才打开助手，随后清除拖动标记。
function openOrb() { if (!dragged) visible.value = true; dragged = false }
// 等待输入框完成渲染后，再移动键盘焦点。
function focusInput() { nextTick(() => input.value?.focus()) }
// 等待消息写入 DOM 后，在允许跟随时滚到消息末尾。
function scroll() { nextTick(() => { if (autoScroll && scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight }) }
// 距离底部不足一百像素时允许跟随，否则保留用户的历史阅读位置。
function trackScroll() { const el = scrollArea.value; if (el) autoScroll = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
// 强制重新读取历史列表，不使用旧的对话缓存。
async function refreshList() { conversations.value = await getData('/ai/conversations', true) }
// 切换历史对话时同步模型和引用区域，并恢复滚动到底部。
async function selectChat(id: string) {
  // 回答生成期间不切换对话，避免增量内容被写入另一段历史。
  if (busy.value) return
  busy.value = true; error.value = ''
  try { current.value = await getData(`/ai/conversations/${id}`, true); modelId.value = current.value.modelId || 'qwen'; selectedSources.value = []; showHistory.value = false; autoScroll = true; scroll() }
  catch (e) { error.value = getErrorMessage(e, '读取对话失败。') }
  finally { busy.value = false }
}
// 还未发送第一条消息时只清空本地状态，不立即创建后端对话。
async function newChat() {
  if (busy.value) return
  current.value = { id: '', title: '新对话', messages: [] }; draft.value = ''; error.value = ''; selectedSources.value = []; showHistory.value = false; focusInput()
}
// 先询问删除确认，再删除后端对话并刷新历史列表。
async function removeChat(id: string) {
  if (busy.value) return
  try { await ElMessageBox.confirm('删除后无法恢复，确定删除此对话？', '删除对话', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) }
  catch { return }
  busy.value = true
  try { await deleteData(`/ai/conversations/${id}`); await refreshList(); if (current.value.id === id) { current.value = { id: '', title: '新对话', messages: [] }; selectedSources.value = [] } }
  catch (e) { error.value = getErrorMessage(e, '删除对话失败。') }
  finally { busy.value = false }
}
// 先插入用户消息和回答占位，再逐段写入流式内容；失败时保留已生成文本以供重试。
async function send(question = draft.value) {
  // 去除问题两端空白，空问题或忙碌状态直接返回。
  const text = question.trim()
  if (!text || busy.value) return
  // 模型没有可用配置时在本地提示，不创建无效的消息请求。
  if (!selectedModel.value?.configured) { error.value = '当前模型尚未配置，请配置后端默认模型或在右上角新增模型。'; return }
  busy.value = true; error.value = ''; autoScroll = true
  progress.value = '正在分析查询需求'
  // 保存发送前的消息快照，失败时可在原历史后补上失败提示。
  let original: Message[] = []
  try {
    // 新对话只有在首次发送时才向后端申请编号。
    if (!current.value.id) current.value = await postData('/ai/conversations', {})
    original = [...current.value.messages]
    // 立即显示用户问题，并插入一个空助手消息承接后续增量内容。
    current.value.messages.push({ role: 'user', content: text }, { role: 'assistant', content: '' })
    draft.value = ''; scroll()
    // 保留最后一条助手消息的引用，流式回调直接向其追加文本。
    const pending = current.value.messages[current.value.messages.length - 1]!
    pending.modelName = selectedModel.value?.name
    current.value = await streamAnswer<Conversation>(`/ai/conversations/${current.value.id}/messages/stream`, { content: text, modelId: modelId.value, webSearch: String(webSearch.value) }, delta => {
      // 每收到一段文本就更新占位消息，并尝试跟随滚动。
      pending.content += delta
      scroll()
    }, status => { progress.value = status })
    selectedSources.value = []
  } catch (e) {
    // 转换为可展示的错误信息，并保留已经收到的部分回答。
    const message = getErrorMessage(e, '智能体回答失败，请稍后重试。')
    if (current.value.id) {
      const partial = current.value.messages.length > original.length ? current.value.messages[current.value.messages.length - 1]?.content || '' : ''
      current.value.messages = [...original, { role: 'user', content: text }, { role: 'assistant', content: `${partial}${partial ? '\n\n' : ''}${message}`, error: true }]
    }
    else error.value = message
    // 把原问题放回输入框，便于修改或重试。
    draft.value = text
  } finally {
    // 无论生成成功与否都更新历史，并解除忙碌状态、恢复输入焦点。
    try { await refreshList() } catch (e) { error.value = getErrorMessage(e, '读取历史列表失败。') }
    busy.value = false; scroll(); focusInput()
  }
}
// 移除上次失败的问答两条消息，再用原问题重新发送。
function retry() { const messages = current.value.messages; const question = messages[messages.length - 2]?.content; if (question) { messages.splice(-2); send(question) } }
// 回车发送、Shift 回车换行，输入法正在组词时不触发发送。
function keydown(e: KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send() } }
// 调用浏览器剪贴板写入文本，失败时提示检查剪贴板权限。
async function copy(content: string) { try { await navigator.clipboard.writeText(content); ElMessage.success('已复制') } catch { ElMessage.error('复制失败，请检查浏览器剪贴板权限。') } }
// 将消息与引用导出为 Markdown，下载后释放临时对象地址。
function exportChat() {
  // 按消息角色组织 Markdown 标题，同时附上每条回答携带的来源列表。
  const content = current.value.messages.map(item => `## ${item.role === 'user' ? '你' : '观澜'}\n\n${item.content}\n\n${(item.sources || []).map((source, index) => `[${index + 1}] ${source.name} ${source.detail}`).join('\n')}`).join('\n\n')
  // 用临时对象地址触发文件下载，一秒后释放地址占用的内存。
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' })); const link = document.createElement('a'); link.href = url; link.download = `观澜-${Date.now()}.md`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
// 先转义 HTML 特殊字符，再将有限的标题、加粗和代码语法转为展示标签。
function format(content: string) { return content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/^#{1,4}\s+(.+)$/gm, '<strong class="answer-heading">$1</strong>').replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>').replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\n/g, '<br>') }
// 打开助手时重新读取模型、搜索能力和历史列表，以反映后端最新配置。
watch(visible, async open => {
  if (!open || busy.value) return
  busy.value = true; error.value = ''
  try {
    const configuration = await getData<{ models: AiModel[]; capabilities?: { webSearch: boolean } }>('/ai/config', true)
    models.value = configuration.models; searchAvailable.value = Boolean(configuration.capabilities?.webSearch)
    if (!searchAvailable.value) webSearch.value = false
    await refreshList()
  }
  catch (e) { error.value = getErrorMessage(e, '读取智能体配置失败。') }
  finally { busy.value = false }
})
onMounted(() => window.addEventListener('resize', clamp))
onBeforeUnmount(() => window.removeEventListener('resize', clamp))
</script>
