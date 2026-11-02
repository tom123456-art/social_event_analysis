<template>
  <!-- 登录与注册复用弹层；弹层打开时禁用背景交互，并在关闭后归还焦点。 -->
  <main class="signal-login">
    <div class="signal-background" :inert="opened">
      <!-- 渲染传播场景背景，分析事件触发时打开账号表单。 -->
      <PropagationScene ref="scene" :obscured="opened" @analyze="open" />
      <header class="signal-brand"><h1>网络媒体热点事件传播态势与舆情分析系统</h1></header>
    </div>
    <Transition name="signal-expand" @after-enter="focusAccount" @after-leave="scene?.focusAnalysis()">
      <!-- 仅当 opened 成立时显示此处内容，条件变化时自动更新。 -->
      <div v-if="opened" class="signal-backdrop" @click.self="close" @keydown.esc.prevent="close" @keydown.tab="trapFocus">
        <section ref="dialog" class="signal-dialog" :class="{ 'signal-register': mode === 'register' }" role="dialog" aria-modal="true" aria-labelledby="access-title" tabindex="-1">
          <header class="signal-dialog-header"><h2 id="access-title">{{ mode === 'login' ? '登录分析' : '注册账号' }}</h2><el-tooltip content="关闭"><button class="signal-close" type="button" aria-label="关闭登录框" @click="close"><el-icon><Close /></el-icon></button></el-tooltip></header>
          <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
          <el-form label-position="top" @submit.prevent="submit">
            <div class="signal-fields">
            <!-- 为“账号”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="账号"><el-input ref="account" v-model="form.username" autocomplete="username" aria-label="账号" placeholder="请输入账号" :maxlength="32" :prefix-icon="User" @input="error = ''" /></el-form-item>
            <!-- 为“昵称”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item v-if="mode === 'register'" label="昵称"><el-input v-model="form.nickname" autocomplete="nickname" aria-label="昵称" placeholder="请输入昵称" :maxlength="100" :prefix-icon="User" @input="error = ''" /></el-form-item>
            <!-- 为“密码”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" aria-label="密码" :placeholder="mode === 'login' ? '请输入密码' : '八至六十四位字符'" :maxlength="64" :prefix-icon="Lock" @input="error = ''" /></el-form-item>
            <!-- 为“确认密码”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item v-if="mode === 'register'" label="确认密码"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" aria-label="确认密码" placeholder="再次输入密码" :maxlength="64" :prefix-icon="Lock" @input="error = ''" /></el-form-item>
            <!-- 为“手机号”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item v-if="mode === 'register'" label="手机号"><el-input v-model="form.phone" type="tel" autocomplete="tel" aria-label="手机号" placeholder="选填" :maxlength="32" :prefix-icon="Phone" @input="error = ''" /></el-form-item>
            <!-- 为“邮箱”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item v-if="mode === 'register'" label="邮箱"><el-input v-model="form.email" autocomplete="email" aria-label="邮箱" placeholder="选填" :maxlength="120" :prefix-icon="Message" @input="error = ''" /></el-form-item>
            <!-- 为“个人简介”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item v-if="mode === 'register'" class="signal-bio" label="个人简介"><el-input v-model="form.bio" type="textarea" aria-label="个人简介" placeholder="选填" :maxlength="300" :rows="2" resize="none" @input="error = ''" /></el-form-item>
            </div>
            <div class="signal-error" role="alert" aria-live="polite">{{ error }}</div>
            <div class="signal-actions"><el-button class="signal-primary" native-type="submit" :loading="busy" :disabled="busy">{{ mode === 'login' ? '登录' : '注册' }}<el-icon v-if="!busy"><Right /></el-icon></el-button><el-button class="signal-secondary" native-type="button" :disabled="busy" @click="switchMode">{{ mode === 'login' ? '注册账号' : '返回登录' }}</el-button></div>
          </el-form>
        </section>
      </div>
    </Transition>
  </main>
</template>

<script setup lang="ts">
import { saveSession } from '../../utils/session'
import { nextTick, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Close, Lock, Message, Phone, Right, User } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import { clearGetCache, getErrorMessage, postData } from '../../api/client'
import PropagationScene from '../../components/analysis/PropagationScene.vue'

const router = useRouter(), route = useRoute()
// 保存背景场景组件实例，关闭登录弹层后可归还焦点。
const scene = ref<InstanceType<typeof PropagationScene>>()
// 保存弹层和账号输入框引用，供焦点限制和自动聚焦使用。
const dialog = ref<HTMLElement>(), account = ref<{ focus: () => void }>()
// 保存登录注册弹层开关、提交状态、错误和当前模式。
const opened = ref(false), busy = ref(false), error = ref(''), mode = ref<'login' | 'register'>('login')
// 保存表单实例或表单字段，用于验证输入并提交数据。
const form = reactive({ username: '', nickname: '', password: '', confirmPassword: '', phone: '', email: '', bio: '' })
// 打开账号表单，模板随之禁止背景场景交互。
function open() { opened.value = true }
// 请求进行中保持弹框打开，空闲时才允许关闭。
function close() { if (!busy.value) opened.value = false }
// 输入框存在时，将键盘焦点移到账号字段。
function focusAccount() { account.value?.focus() }
// 切换模式清除密码和错误信息，等待表单更新后重新聚焦账号输入框。
async function switchMode() {
  // 登录和注册互相切换，但不保留上一个模式输入的密码和错误提示。
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''; form.password = ''; form.confirmPassword = ''
  await nextTick(); focusAccount()
}
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
// 注册先校验资料与密码字节长度；登录成功后保存会话并按角色选择目标页面。
async function submit() {
  // 已提交时不重复进入；账号和密码为空则直接显示表单错误。
  if (busy.value) return
  if (!form.username.trim() || !form.password) { error.value = '请输入账号和密码'; return }
  if (mode.value === 'register') {
    // 注册账号限定字母、数字与下划线，长度为三到三十二位。
    if (!/^[A-Za-z0-9_]{3,32}$/.test(form.username.trim())) { error.value = '账号须为三至三十二位字母、数字或下划线'; return }
    // 密码至少八个字符，UTF-8 编码后最多七十二字节，且不能全是空白。
    if (form.password.length < 8 || new TextEncoder().encode(form.password).length > 72 || form.password.trim() === '') { error.value = '密码至少八位，且不能超过七十二字节'; return }
    // 两次密码必须相同，昵称不能为空；选填的电话和邮箱只在有内容时校验格式。
    if (form.password !== form.confirmPassword) { error.value = '两次输入的密码不一致'; return }
    if (!form.nickname.trim()) { error.value = '请填写昵称'; return }
    if (form.phone.trim() && !/^\+?[0-9][0-9 ()-]{5,31}$/.test(form.phone.trim())) { error.value = '手机号格式不正确'; return }
    if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) { error.value = '邮箱格式不正确'; return }
  }
  // 验证通过后锁定表单并清除旧错误，再执行对应接口请求。
  busy.value = true; error.value = ''
  try {
    if (mode.value === 'register') {
      // 注册时去掉资料字段两侧空白，密码保持用户原始输入。
      await postData('/auth/register', { username: form.username.trim(), nickname: form.nickname.trim(), password: form.password, confirmPassword: form.confirmPassword, phone: form.phone.trim(), email: form.email.trim(), bio: form.bio.trim() })
      // 注册成功后切回登录模式并清空密码，不自动沿用注册凭据登录。
      mode.value = 'login'; form.password = ''; form.confirmPassword = ''
      ElMessage.success('注册成功，请登录')
      await nextTick(); focusAccount()
    } else {
      const user = await postData<{ token: string; username: string; role: string }>('/auth/login', { username: form.username.trim(), password: form.password })
      // 必须具备令牌、用户名及合法角色才接受登录响应。
      if (!user.token || !user.username || !['admin', 'user'].includes(user.role)) throw new Error('登录响应无效')
      // 清除前一账号的数据缓存，再保存新会话，防止切号后使用旧数据。
      clearGetCache()
      saveSession(user)
      // 只接受站内绝对路径作为回跳地址，拒绝协议相对地址和再次进入登录页。
      const requested = typeof route.query.redirect === 'string' ? route.query.redirect : ''
      const redirect = requested.startsWith('/') && !requested.startsWith('//') && !requested.startsWith('/login') ? requested : ''
      // 管理员默认进入后台；普通用户请求管理员页时改为个人资料页。
      await router.replace(user.role === 'admin' ? (redirect || '/admin') : (redirect.startsWith('/admin') && !redirect.startsWith('/admin/profile') ? '/admin/profile' : (redirect || '/overview')))
      ElMessage.success('登录成功')
    }
  } catch (err) { error.value = getErrorMessage(err, mode.value === 'register' ? '注册失败，请稍后重试' : '登录失败，请检查账号密码或后端连接') }
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
.signal-register { width: 520px; }
.signal-register .signal-fields { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); column-gap: 16px; }
.signal-bio { grid-column: 1 / -1; }
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
.signal-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.signal-actions .el-button { height: 44px; border-radius: 5px; margin: 0; font-size: 13px; }
.signal-primary { background: #b9dfc7; border-color: #b9dfc7; color: #1c3b27; }
.signal-primary:hover { background: #d0edd9; border-color: #d0edd9; color: #1c3b27; }
.signal-primary :deep(> span) { display: flex; align-items: center; gap: 12px; }
.signal-secondary { background: transparent; border-color: #a4c3b44a; color: #c2d9cb; }
.signal-secondary:hover { background: #bbdec611; border-color: #a4c3b4; color: #e4f2e9; }
.signal-expand-enter-active, .signal-expand-leave-active { transition: background .45s; }
.signal-expand-enter-active .signal-dialog { transition: transform .55s cubic-bezier(.16, 1, .3, 1), opacity .4s; }
.signal-expand-leave-active .signal-dialog { transition: transform .3s cubic-bezier(.4, 0, .6, 1), opacity .25s; }
.signal-expand-enter-from, .signal-expand-leave-to { background: transparent; }
.signal-expand-enter-from .signal-dialog, .signal-expand-leave-to .signal-dialog { transform: translate(-50%, -50%) scale(.04); opacity: 0; }
.signal-login button:focus-visible, .signal-login a:focus-visible { outline: 2px solid #b9dfc7; outline-offset: 4px; }
@media (max-width: 600px) { .signal-brand { top: 24px; left: 20px; right: 20px; } .signal-brand h1 { font-size: 18px; } .signal-dialog { padding: 24px; } }
@media (prefers-reduced-motion: reduce) { .signal-expand-enter-active, .signal-expand-leave-active, .signal-expand-enter-active .signal-dialog, .signal-expand-leave-active .signal-dialog { transition: opacity .12s; } .signal-expand-enter-from .signal-dialog, .signal-expand-leave-to .signal-dialog { transform: translate(-50%, -50%); } }
@media (max-width: 480px) { .signal-register .signal-fields { grid-template-columns: minmax(0, 1fr); } }
@media (max-height: 480px) and (min-width: 601px) { .signal-brand { top: 12px; } .signal-brand h1 { font-size: 20px; } }
</style>
