<template>
  <Teleport to="body">
    <button ref="orb" class="ai-orb" :style="{ left: `${position.x}px`, top: `${position.y}px` }" aria-label="打开观澜 AI 助手" title="观澜 AI"
      @pointerdown="dragStart" @pointermove="dragMove" @pointerup="dragEnd" @pointercancel="dragCancel" @click="openOrb"><span>AI</span><i /></button>
    <el-dialog v-model="visible" class="observatory-dialog" :show-close="false" :close-on-click-modal="false" :fullscreen="fullscreen" align-center @opened="focusInput" @closed="orb?.focus()">
      <template #header>
        <div class="agent-topbar">
          <div class="agent-identity"><span class="agent-mark">AI</span><div><b>观澜 <small>舆情研判助手</small></b><span class="agent-subtitle">PUBLIC OPINION INTELLIGENCE</span></div></div>
          <div class="agent-window-actions">
            <AiModelSelector :models="models" :model-id="modelId" :disabled="busy" @select="modelId = $event" @added="addModel" />
            <button class="agent-icon" :title="fullscreen ? '还原窗口' : '全屏'" :aria-label="fullscreen ? '还原窗口' : '全屏'" @click="fullscreen = !fullscreen"><el-icon><CopyDocument v-if="fullscreen" /><FullScreen v-else /></el-icon></button>
            <button class="agent-icon" title="关闭" aria-label="关闭助手" @click="visible = false"><el-icon><Close /></el-icon></button>
          </div>
        </div>
      </template>
      <div class="agent-layout" :class="{ 'show-history': showHistory, 'show-evidence': showEvidence }">
        <aside class="agent-history">
          <button class="agent-new" :disabled="busy" @click="newChat"><el-icon><Plus /></el-icon>新建对话</button>
          <label class="history-search"><el-icon><Search /></el-icon><input v-model="search" placeholder="搜索对话" aria-label="搜索历史对话" /></label>
          <div class="history-caption">最近对话 <span>{{ conversations.length }}</span></div>
          <div class="history-items">
            <div v-for="item in filtered" :key="item.id" class="history-item" :class="{ active: item.id === current.id }">
              <button :disabled="busy" @click="selectChat(item.id)"><el-icon><ChatDotRound /></el-icon><span>{{ item.title }}</span></button>
              <button class="history-delete" :disabled="busy" title="删除对话" aria-label="删除对话" @click="removeChat(item.id)"><el-icon><Delete /></el-icon></button>
            </div>
            <p v-if="!filtered.length" class="history-empty">暂无匹配的对话</p>
          </div>
          <div class="history-bottom"><el-icon><MagicStick /></el-icon><div><b>系统知识空间</b><small>分析快照 / 内容证据</small></div><span class="knowledge-dot" /></div>
        </aside>
        <main class="agent-main">
          <div class="conversation-bar">
            <button class="agent-icon mobile-history-toggle" title="历史对话" aria-label="历史对话" @click="showHistory = !showHistory"><el-icon><Fold /></el-icon></button>
            <span>{{ current.title }}</span><div>
              <button class="agent-icon" :disabled="!current.messages.length || busy" title="导出对话" aria-label="导出对话" @click="exportChat"><el-icon><Download /></el-icon></button>
              <button class="agent-icon" :class="{ selected: showEvidence }" title="数据依据" aria-label="数据依据" :aria-expanded="showEvidence" @click="showEvidence = !showEvidence"><el-icon><Document /></el-icon></button>
            </div>
          </div>
          <div ref="scrollArea" class="agent-scroll" @scroll="trackScroll">
            <div v-if="current.messages.length" class="agent-messages" aria-live="polite" :aria-busy="busy">
              <article v-for="(message, index) in current.messages" :key="index" class="agent-message" :class="message.role">
                <div class="message-avatar">{{ message.role === 'assistant' ? 'AI' : '我' }}</div>
                <div class="message-body">
                  <div class="message-byline">{{ message.role === 'assistant' ? '观澜' : '你' }}<small v-if="message.role === 'assistant'">{{ message.error ? '请求未完成' : message.modelName || '舆情研判助手' }}</small></div>
                  <div v-if="!message.content && busy" class="agent-thinking"><i /><i /><i /><span>{{ progress }}</span></div>
                  <div v-else class="message-content" :class="{ 'message-error': message.error }" v-html="format(message.content)" />
                  <div v-if="message.sources?.length" class="message-citations"><button @click="selectedSources = message.sources; showEvidence = true"><el-icon><DocumentChecked /></el-icon>{{ message.sources.length }} 项数据依据 <el-icon><ArrowRight /></el-icon></button></div>
                  <div v-if="message.role === 'assistant' && message.content && !busy" class="message-actions">
                    <button class="agent-icon" title="复制回答" aria-label="复制回答" @click="copy(message.content)"><el-icon><CopyDocument /></el-icon></button>
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
              <button type="button" class="agent-web-toggle" :class="{ active: webSearch }" :aria-pressed="webSearch" :disabled="busy" title="联网搜索" @click="toggleSearch"><el-icon><Search /></el-icon>联网搜索</button>
              <span v-if="busy" class="agent-tool-status" role="status">{{ progress }}</span>
            </div>
            <div v-if="error" class="agent-request-error" role="alert">{{ error }}</div>
            <form class="agent-composer" @submit.prevent="send()">
              <textarea ref="input" v-model="draft" :disabled="busy" maxlength="2000" rows="2" placeholder="向观澜提问..." aria-label="输入问题" @keydown="keydown" />
              <div class="composer-bottom"><span><el-icon><Connection /></el-icon>系统数据优先 <small>{{ draft.length }}/2000</small></span><button class="agent-send" type="submit" :disabled="busy || !draft.trim()" title="发送" aria-label="发送问题"><el-icon><Promotion /></el-icon></button></div>
            </form>
            <div class="agent-disclaimer">AI 回答仅供辅助研判，请以原始数据与来源核验为准。</div>
          </div>
        </main>
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

interface Source { name: string; detail: string; kind?: string; url?: string }
interface Message { role: 'user' | 'assistant'; content: string; sources?: Source[]; error?: boolean; modelName?: string }
interface Conversation { id: string; title: string; messages: Message[]; modelId?: string }
const visible = ref(false), fullscreen = ref(false), showHistory = ref(false), showEvidence = ref(false)
const busy = ref(false), draft = ref(''), search = ref(''), error = ref('')
const webSearch = ref(false), searchAvailable = ref(false), progress = ref('正在分析查询需求')
function toggleSearch() {
  if (webSearch.value) { webSearch.value = false; return }
  if (!searchAvailable.value) { ElMessage.warning('联网搜索尚未配置搜索服务密钥，请联系管理员配置。'); return }
  webSearch.value = true
}
function safeUrl(value?: string) { try { const url = new URL(value || ''); return ['https:', 'http:'].includes(url.protocol) ? url.href : undefined } catch { return undefined } }
const models = ref<AiModel[]>([]), modelId = ref('qwen')
const selectedModel = computed(() => models.value.find(item => item.id === modelId.value))
function addModel(model: AiModel) { models.value.push(model); modelId.value = model.id }
const conversations = ref<{ id: string; title: string }[]>([])
const current = ref<Conversation>({ id: '', title: '新对话', messages: [] })
const selectedSources = ref<Source[]>([])
const filtered = computed(() => conversations.value.filter(item => item.title.toLowerCase().includes(search.value.toLowerCase())))
const sources = computed(() => selectedSources.value.length ? selectedSources.value : [...current.value.messages].reverse().find(item => item.sources?.length)?.sources || [])
const input = ref<HTMLTextAreaElement>(), orb = ref<HTMLButtonElement>(), scrollArea = ref<HTMLElement>()
const position = ref({ x: Math.max(12, window.innerWidth - 92), y: Math.max(12, window.innerHeight - 120) })
const suggestions = [
  { title: '热点速览', question: '概括一下系统中的热点和整体数据情况', icon: DataLine },
  { title: '传播脉络', question: '哪些平台率先升温，传播关系如何解读？', icon: Share },
  { title: '情绪观察', question: '正负向情感分布如何，有哪些解读注意事项？', icon: MagicStick },
  { title: '系统导航', question: '我想分析一个热点事件，应该从哪些页面入手？', icon: Compass }
]
let autoScroll = true
let drag: { x: number; y: number; startX: number; startY: number; moved: boolean } | undefined
let dragged = false
function clamp() { position.value.x = Math.max(12, Math.min(window.innerWidth - 76, position.value.x)); position.value.y = Math.max(12, Math.min(window.innerHeight - 76, position.value.y)) }
function dragStart(e: PointerEvent) { if (e.button !== 0) return; dragged = false; drag = { x: e.clientX, y: e.clientY, startX: position.value.x, startY: position.value.y, moved: false }; (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId) }
function dragMove(e: PointerEvent) { if (!drag) return; const x = e.clientX - drag.x, y = e.clientY - drag.y; if (Math.hypot(x, y) > 5) drag.moved = true; if (drag.moved) { position.value = { x: drag.startX + x, y: drag.startY + y }; clamp() } }
function dragEnd() { dragged = !!drag?.moved; drag = undefined }
function dragCancel() { dragged = true; drag = undefined }
function openOrb() { if (!dragged) visible.value = true; dragged = false }
function focusInput() { nextTick(() => input.value?.focus()) }
function scroll() { nextTick(() => { if (autoScroll && scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight }) }
function trackScroll() { const el = scrollArea.value; if (el) autoScroll = el.scrollHeight - el.scrollTop - el.clientHeight < 100 }
async function refreshList() { conversations.value = await getData('/ai/conversations', true) }
async function selectChat(id: string) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { current.value = await getData(`/ai/conversations/${id}`, true); modelId.value = current.value.modelId || 'qwen'; selectedSources.value = []; showHistory.value = false; autoScroll = true; scroll() }
  catch (e) { error.value = getErrorMessage(e, '读取对话失败。') }
  finally { busy.value = false }
}
async function newChat() {
  if (busy.value) return
  current.value = { id: '', title: '新对话', messages: [] }; draft.value = ''; error.value = ''; selectedSources.value = []; showHistory.value = false; focusInput()
}
async function removeChat(id: string) {
  if (busy.value) return
  try { await ElMessageBox.confirm('删除后无法恢复，确定删除此对话？', '删除对话', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) }
  catch { return }
  busy.value = true
  try { await deleteData(`/ai/conversations/${id}`); await refreshList(); if (current.value.id === id) { current.value = { id: '', title: '新对话', messages: [] }; selectedSources.value = [] } }
  catch (e) { error.value = getErrorMessage(e, '删除对话失败。') }
  finally { busy.value = false }
}
async function send(question = draft.value) {
  const text = question.trim()
  if (!text || busy.value) return
  if (!selectedModel.value?.configured) { error.value = '当前模型尚未配置，请配置后端默认模型或在右上角新增模型。'; return }
  busy.value = true; error.value = ''; autoScroll = true
  progress.value = '正在分析查询需求'
  let original: Message[] = []
  try {
    if (!current.value.id) current.value = await postData('/ai/conversations', {})
    original = [...current.value.messages]
    current.value.messages.push({ role: 'user', content: text }, { role: 'assistant', content: '' })
    draft.value = ''; scroll()
    const pending = current.value.messages[current.value.messages.length - 1]!
    pending.modelName = selectedModel.value?.name
    current.value = await streamAnswer<Conversation>(`/ai/conversations/${current.value.id}/messages/stream`, { content: text, modelId: modelId.value, webSearch: String(webSearch.value) }, delta => {
      pending.content += delta
      scroll()
    }, status => { progress.value = status })
    selectedSources.value = []
  } catch (e) {
    const message = getErrorMessage(e, '智能体回答失败，请稍后重试。')
    if (current.value.id) {
      const partial = current.value.messages.length > original.length ? current.value.messages[current.value.messages.length - 1]?.content || '' : ''
      current.value.messages = [...original, { role: 'user', content: text }, { role: 'assistant', content: `${partial}${partial ? '\n\n' : ''}${message}`, error: true }]
    }
    else error.value = message
    draft.value = text
  } finally {
    try { await refreshList() } catch (e) { error.value = getErrorMessage(e, '读取历史列表失败。') }
    busy.value = false; scroll(); focusInput()
  }
}
function retry() { const messages = current.value.messages; const question = messages[messages.length - 2]?.content; if (question) { messages.splice(-2); send(question) } }
function keydown(e: KeyboardEvent) { if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); send() } }
async function copy(content: string) { try { await navigator.clipboard.writeText(content); ElMessage.success('已复制') } catch { ElMessage.error('复制失败，请检查浏览器剪贴板权限。') } }
function exportChat() {
  const content = current.value.messages.map(item => `## ${item.role === 'user' ? '你' : '观澜'}\n\n${item.content}\n\n${(item.sources || []).map((source, index) => `[${index + 1}] ${source.name} ${source.detail}`).join('\n')}`).join('\n\n')
  const url = URL.createObjectURL(new Blob([content], { type: 'text/markdown;charset=utf-8' })); const link = document.createElement('a'); link.href = url; link.download = `观澜-${Date.now()}.md`; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
}
function format(content: string) { return content.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;').replace(/^#{1,4}\s+(.+)$/gm, '<strong class="answer-heading">$1</strong>').replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>').replace(/`([^`\n]+)`/g, '<code>$1</code>').replace(/\n/g, '<br>') }
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
