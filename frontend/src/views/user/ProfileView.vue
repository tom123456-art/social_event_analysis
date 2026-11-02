<template>
  <!-- 基础资料与密码修改分开提交；密码修改成功后要求重新登录。 -->
  <div class="page">
    <div class="page-head">
      <div>
        <h2>个人信息管理</h2>
      </div>
      <!-- 点击“保存资料”时执行 saveProfile。 -->
      <el-button type="primary" @click="saveProfile">保存资料</el-button>
    </div>

    <div class="grid profile-grid equal-grid">
      <!-- 个人信息卡片：头像、昵称、角色及账号详情 -->
      <div class="card profile-card">
        <div class="profile-avatar">{{ avatarText }}</div>
        <h3>{{ profile.nickname || profile.username || currentUser }}</h3>
        <p>{{ roleText }}</p>
        <div class="detail-list">
          <div><span>账号</span><b>{{ profile.username || '-' }}</b></div>
          <div><span>角色</span><b>{{ roleName(profile.role) }}</b></div>
          <div><span>状态</span><b>{{ statusName(profile.status) }}</b></div>
          <div><span>最近更新</span><b>{{ updatedAt }}</b></div>
        </div>
      </div>

      <!-- 基础资料编辑卡：账号不可改，昵称/手机/邮箱/简介可编辑，角色与状态只读 -->
      <div class="card">
        <div class="card-title">基础资料 <span>账号资料</span></div>
        <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
        <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
        <el-form label-width="86px" class="compact-form">
          <div class="form-grid">
            <!-- 为“账号”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="账号"><el-input v-model="profile.username" disabled /></el-form-item>
            <!-- 为“昵称”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="昵称"><el-input v-model="profile.nickname" /></el-form-item>
          </div>
          <div class="form-grid">
            <!-- 为“手机”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="手机"><el-input v-model="profile.phone" /></el-form-item>
            <!-- 为“邮箱”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="邮箱"><el-input v-model="profile.email" /></el-form-item>
          </div>
          <!-- 为“个人简介”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="个人简介"><el-input v-model="profile.bio" type="textarea" :rows="5" /></el-form-item>
          <div class="form-grid">
            <!-- 为“角色”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="角色"><el-input :model-value="roleName(profile.role)" disabled /></el-form-item>
            <!-- 为“状态”展示输入控件，并承接该字段的校验提示。 -->
            <el-form-item label="状态">
              <!-- 将当前控件的输入或选择同步到 profile.status，供查询或提交时读取。 -->
              <el-select v-model="profile.status" style="width:100%" disabled>
                <el-option label="启用" value="ENABLED" />
                <el-option label="禁用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </div>
        </el-form>
      </div>

      <!-- 安全设置卡：修改密码需提供原密码，成功后强制重新登录 -->
      <div class="card">
        <div class="card-title">安全设置 <span>账号密码</span></div>
        <el-form label-width="86px" class="compact-form">
          <!-- 为“原密码”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="原密码"><el-input v-model="password.old" type="password" show-password /></el-form-item>
          <!-- 为“新密码”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="新密码"><el-input v-model="password.next" type="password" show-password /></el-form-item>
          <!-- 为“确认密码”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="确认密码"><el-input v-model="password.confirm" type="password" show-password /></el-form-item>
          <!-- 为“当前字段”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item>
            <!-- 点击“更新密码”时执行 resetPassword。 -->
            <el-button :loading="passwordBusy" @click="resetPassword">更新密码</el-button>
            <!-- 点击“重新读取”时执行 load。 -->
            <el-button @click="load">重新读取</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { clearSession, readSession } from '../../utils/session'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getData, getErrorMessage, putData } from '../../api/client'
import { roleName, statusName } from '../../utils/userText'

// 当前登录用户名，从本地会话读取，作为资料查询和保存的主键。
const currentUser = readSession().user || 'student'
// 个人资料响应式对象，初始用本地会话信息占位，加载后由后端数据覆盖。
const profile = reactive<any>({ username: currentUser, role: 'USER', status: 'ENABLED' })
// 密码修改表单：原密码、新密码、确认密码。
const password = reactive({ old: '', next: '', confirm: '' })
// 密码提交中状态，防止重复点击。
const passwordBusy = ref(false)
// 获取路由实例，改密成功后替换当前页面为登录页。
const router = useRouter()
// 最近更新时间，用本地时间格式化展示。
const updatedAt = computed(() => new Date().toLocaleString('zh-CN', { hour12: false }))
// 头像占位文字，取用户名前两位大写。
const avatarText = computed(() => String(profile.username || currentUser).slice(0, 2).toUpperCase())
// 角色中文描述，用于头像下方展示。
const roleText = computed(() => String(profile.role).toUpperCase() === 'ADMIN' ? '系统管理员' : '普通用户')

// 从后端读取当前用户的个人资料并合并到响应式对象。
async function load() {
  try {
    const data = await getData(`/front/profile?username=${encodeURIComponent(currentUser)}`)
    // 原地合并接口字段，保留模板已绑定的响应式资料对象。
    Object.assign(profile, data)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '\u8bfb\u53d6\u4e2a\u4eba\u8d44\u6599\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002'))
  }
}

// 提交修改后的基础资料到后端，成功后重新读取以同步最新状态。
async function saveProfile() {
  try {
    // 以编码后的用户名定位资料，提交整个资料对象。
    await putData(`/front/profile/${encodeURIComponent(currentUser)}`, profile)
    ElMessage.success('个人资料已保存')
    // 保存后重新查询，让页面显示服务端最终落库的资料。
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存个人资料失败，请稍后重试。'))
  }
}

// 修改密码：校验原密码非空且两次新密码一致后提交；成功后清理会话并跳转登录页重新登录。
async function resetPassword() {
  // 上次请求未结束时忽略点击，字段不全或两次新密码不一致时停止提交。
  if (passwordBusy.value) return
  if (!password.old || !password.next || password.next !== password.confirm) {
    ElMessage.warning('请完整填写密码，并确认两次新密码一致')
    return
  }
  passwordBusy.value = true
  try {
    await putData('/auth/password', { oldPassword: password.old, password: password.next, confirmPassword: password.confirm })
    password.old = ''; password.next = ''; password.confirm = ''
    // 密码修改成功后清除本地会话，要求用新密码重新登录。
    clearSession()
    ElMessage.success('密码已更新，请重新登录')
    await router.replace('/login')
  } catch (error) { ElMessage.error(getErrorMessage(error, '更新密码失败，请稍后重试。')) }
  finally { passwordBusy.value = false }
}

// 页面挂载时加载当前用户资料。
onMounted(load)
</script>
