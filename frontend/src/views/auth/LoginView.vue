<template>
  <!-- 登录弹层打开时禁用背景交互，并在关闭后归还焦点。 -->
  <main class="signal-login">
    <div class="signal-background" :inert="opened">
      <!-- 渲染传播场景背景，分析事件触发时打开账号表单。 -->
      <PropagationScene ref="scene" :obscured="opened" @analyze="open" />
      <header class="signal-brand"><h1>网络媒体热点事件传播态势与舆情分析系统</h1></header>
    </div>
    <Transition name="signal-expand" @after-enter="focusAccount" @after-leave="scene?.focusAnalysis()">
      <!-- 仅当 opened 成立时显示此处内容，条件变化时自动更新。 -->
      <div v-if="opened" class="signal-backdrop" @click.self="close" @keydown.esc.prevent="close" @keydown.tab="trapFocus">
        <section ref="dialog" class="signal-dialog" role="dialog" aria-modal="true" aria-labelledby="access-title" tabindex="-1">
          <header class="signal-dialog-header"><h2 id="access-title">登录分析</h2><el-tooltip content="关闭"><button class="signal-close" type="button" aria-label="关闭登录框" @click="close"><el-icon><Close /></el-icon></button></el-tooltip></header>
          <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
          <el-form label-position="top" @submit.prevent="submit">
            <div class="signal-fields">
            <!-- 为“账号”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="账号"><el-input ref="account" v-model="form.username" autocomplete="username" aria-label="账号" placeholder="请输入账号" :maxlength="32" :prefix-icon="User" @input="error = ''" /></el-form-item>
            <!-- 为“密码”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" aria-label="密码" placeholder="请输入密码" :maxlength="64" :prefix-icon="Lock" @input="error = ''" /></el-form-item>
            </div>
            <div class="signal-error" role="alert" aria-live="polite">{{ error }}</div>
            <div class="signal-actions"><el-button class="signal-primary" native-type="submit" :loading="busy" :disabled="busy">登录<el-icon v-if="!busy"><Right /></el-icon></el-button></div>
          </el-form>
        </section>
      </div>
    </Transition>
  </main>
</template>

<script setup lang="ts">
import { saveSession } from '../../utils/session'
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Lock, Right, User } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { clearGetCache, getErrorMessage, postData } from '../../api/client'
import PropagationScene from '../../components/analysis/PropagationScene.vue'

const router = useRouter(), route = useRoute()
// 保存背景场景组件实例，关闭登录弹层后可归还焦点。
const scene = ref<InstanceType<typeof PropagationScene>>()
// 保存弹层和账号输入框引用，供焦点限制和自动聚焦使用。
const dialog = ref<HTMLElement>(), account = ref<{ focus: () => void }>()
// 保存登录弹层开关、提交状态和错误。
const opened = ref(false), busy = ref(false), error = ref('')
// 保存表单实例或表单字段，用于验证输入并提交数据。
const form = reactive({ username: '', password: '' })
// 打开账号表单，模板随之禁止背景场景交互。
function open() { opened.value = true }
// 请求进行中保持弹框打开，空闲时才允许关闭。
function close() { if (!busy.value) opened.value = false }
// 输入框存在时，将键盘焦点移到账号字段。
function focusAccount() { account.value?.focus() }
// 将 Tab 焦点限制在可见弹层控件内，支持正向与反向循环访问。
function trapFocus(event: KeyboardEvent) {
  // 仅收集未禁用且实际可见的控件，隐藏或禁用输入不进入焦点循环。
  const controls = [...(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), [tabindex="0"]') || [])].filter(el => el.getClientRects().length)
  // 找到首尾控件，以便 Tab 和 Shift+Tab 到边界时循环回弹层内部。
  const first = controls[0], last = controls[controls.length - 1]
  if (!first || !last) { event.preventDefault(); dialog.value?.focus(); return }
  if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.value)) { event.preventDefault(); last.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
// 登录成功后保存会话，并返回原目标页面。
async function submit() {
  // 已提交时不重复进入；账号和密码为空则直接显示表单错误。
  if (busy.value) return
  if (!form.username.trim() || !form.password) { error.value = '请输入账号和密码'; return }
  // 验证通过后锁定表单并清除旧错误，再执行对应接口请求。
  busy.value = true; error.value = ''
  try {
      const user = await postData<{ token: string; username: string }>('/auth/login', { username: form.username.trim(), password: form.password })
      if (!user.token || !user.username) throw new Error('登录响应无效')
      // 清除前一账号的数据缓存，再保存新会话，防止切号后使用旧数据。
      clearGetCache()
      saveSession(user)
      // 只接受站内绝对路径作为回跳地址，拒绝协议相对地址和再次进入登录页。
      const requested = typeof route.query.redirect === 'string' ? route.query.redirect : ''
      const redirect = requested.startsWith('/') && !requested.startsWith('//') && !requested.startsWith('/login') ? requested : ''
      await router.replace(redirect || '/admin')
      ElMessage.success('登录成功')
  } catch (err) { error.value = getErrorMessage(err, '登录失败，请检查账号密码或后端连接') }
  finally { busy.value = false }
}
</script>

<style scoped>
.signal-login { position: relative; height: 100dvh; min-height: 320px; overflow: hidden; color: #e5ede7; background: #151e18; letter-spacing: 0; }
.signal-background { position: absolute; inset: 0; }
.signal-brand { position: absolute; top: 32px; left: 40px; right: 40px; pointer-events: none; }
.signal-brand h1 { margin: 0; font-size: 22px; font-weight: 500; line-height: 1.5; overflow-wrap: anywhere; }
.signal-backdrop { position: absolute; inset: 0; z-index: 2; background: #08100938; }
.signal-dialog { position: absolute; top: 52%; left: 50%; width: 380px; max-width: calc(100% - 32px); max-height: calc(96dvh - 32px); overflow-y: auto; transform: translate(-50%, -50%); transform-origin: center; padding: 26px 30px 30px; border: 1px solid #cce5d638; border-radius: 8px; background: #e3f0e916; backdrop-filter: blur(26px) saturate(1.25); -webkit-backdrop-filter: blur(26px) saturate(1.25); box-shadow: 0 24px 80px #0006, inset 0 1px 0 #e4f1e71c; }
.signal-dialog-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 26px; }
.signal-dialog :deep(.el-textarea__inner) { color: #eaf6ed; background: #10241a52; box-shadow: 0 0 0 1px #b4d6c130 inset; border-radius: 5px; }
.signal-dialog :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px #a4d4b4 inset; }
.signal-dialog :deep(.el-textarea__inner::placeholder) { color: #93aa9b; }
.signal-dialog h2 { font-size: 20px; margin: 0; font-weight: 500; color: #edf5ef; }
.signal-close { display: grid; place-items: center; border: none; background: none; color: #aec5b5; width: 30px; height: 30px; cursor: pointer; font-size: 19px; padding: 0; }
.signal-close:hover { color: #fff; }
.signal-dialog :deep(.el-form-item) { margin-bottom: 18px; }
.signal-dialog :deep(.el-form-item__label) { color: #bbcfbf; font-size: 12px; margin-bottom: 8px; }
.signal-dialog :deep(.el-input__wrapper) { min-height: 46px; border-radius: 5px; background: #10241a52; box-shadow: 0 0 0 1px #b4d6c130 inset; padding: 0 13px; }
.signal-dialog :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 1px #a4d4b4 inset; }
.signal-dialog :deep(.el-input__inner) { color: #eaf6ed; font-size: 14px; }
.signal-dialog :deep(.el-input__inner::placeholder) { color: #93aa9b; font-size: 12px; }
.signal-dialog :deep(.el-input__prefix), .signal-dialog :deep(.el-input__suffix) { color: #a7c0b0; }
.signal-error { min-height: 34px; padding: 0 0 10px; color: #ffb3a1; font-size: 12px; line-height: 1.6; }
.signal-actions { display: grid; grid-template-columns: 1fr; }
.signal-actions .el-button { height: 44px; border-radius: 5px; margin: 0; font-size: 13px; }
.signal-primary { background: #b9dfc7; border-color: #b9dfc7; color: #1c3b27; }
.signal-primary:hover { background: #d0edd9; border-color: #d0edd9; color: #1c3b27; }
.signal-primary :deep(> span) { display: flex; align-items: center; gap: 12px; }
.signal-expand-enter-active, .signal-expand-leave-active { transition: background .45s; }
.signal-expand-enter-active .signal-dialog { transition: transform .55s cubic-bezier(.16, 1, .3, 1), opacity .4s; }
.signal-expand-leave-active .signal-dialog { transition: transform .3s cubic-bezier(.4, 0, .6, 1), opacity .25s; }
.signal-expand-enter-from, .signal-expand-leave-to { background: transparent; }
.signal-expand-enter-from .signal-dialog, .signal-expand-leave-to .signal-dialog { transform: translate(-50%, -50%) scale(.04); opacity: 0; }
.signal-login button:focus-visible, .signal-login a:focus-visible { outline: 2px solid #b9dfc7; outline-offset: 4px; }
@media (max-width: 600px) { .signal-brand { top: 24px; left: 20px; right: 20px; } .signal-brand h1 { font-size: 18px; } .signal-dialog { padding: 24px; } }
@media (prefers-reduced-motion: reduce) { .signal-expand-enter-active, .signal-expand-leave-active, .signal-expand-enter-active .signal-dialog, .signal-expand-leave-active .signal-dialog { transition: opacity .12s; } .signal-expand-enter-from .signal-dialog, .signal-expand-leave-to .signal-dialog { transform: translate(-50%, -50%); } }
@media (max-height: 480px) and (min-width: 601px) { .signal-brand { top: 12px; } .signal-brand h1 { font-size: 20px; } }
</style>
