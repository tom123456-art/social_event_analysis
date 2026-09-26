<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>个人信息管理</h2>
      </div>
      <el-button type="primary" @click="saveProfile">保存资料</el-button>
    </div>

    <div class="grid profile-grid equal-grid">
      <div class="card profile-card">
        <div class="profile-avatar">{{ avatarText }}</div>
        <h3>{{ profile.nickname || profile.username || currentUser }}</h3>
        <p>{{ roleText }}</p>
        <div class="detail-list">
          <div><span>账号</span><b>{{ profile.username || '-' }}</b></div>
          <div><span>角色</span><b>{{ profile.role || 'ADMIN' }}</b></div>
          <div><span>状态</span><b>{{ profile.status || 'ENABLED' }}</b></div>
          <div><span>最近更新</span><b>{{ updatedAt }}</b></div>
        </div>
      </div>

      <div class="card">
        <div class="card-title">基础资料 <span>app_user</span></div>
        <el-form label-width="86px" class="compact-form">
          <div class="form-grid">
            <el-form-item label="账号"><el-input v-model="profile.username" disabled /></el-form-item>
            <el-form-item label="昵称"><el-input v-model="profile.nickname" /></el-form-item>
          </div>
          <div class="form-grid">
            <el-form-item label="手机"><el-input v-model="profile.phone" /></el-form-item>
            <el-form-item label="邮箱"><el-input v-model="profile.email" /></el-form-item>
          </div>
          <el-form-item label="个人简介"><el-input v-model="profile.bio" type="textarea" :rows="5" /></el-form-item>
          <div class="form-grid">
            <el-form-item label="角色"><el-input v-model="profile.role" disabled /></el-form-item>
            <el-form-item label="状态">
              <el-select v-model="profile.status" style="width:100%">
                <el-option label="启用" value="ENABLED" />
                <el-option label="禁用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </div>
        </el-form>
      </div>

      <div class="card">
        <div class="card-title">安全设置 <span>本地演示</span></div>
        <el-form label-width="86px" class="compact-form">
          <el-form-item label="原密码"><el-input v-model="password.old" type="password" show-password /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="password.next" type="password" show-password /></el-form-item>
          <el-form-item label="确认密码"><el-input v-model="password.confirm" type="password" show-password /></el-form-item>
          <el-form-item>
            <el-button @click="resetPassword">更新密码</el-button>
            <el-button @click="load">重新读取</el-button>
          </el-form-item>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
// 个人资料页：查看并修改当前用户的基础资料。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getData, getErrorMessage, putData } from '../api/client'

const currentUser = localStorage.getItem('social_user') || 'student'
const profile = reactive<any>({ username: currentUser, role: 'USER', status: 'ENABLED' })
const password = reactive({ old: '', next: '', confirm: '' })
const updatedAt = computed(() => new Date().toLocaleString('zh-CN', { hour12: false }))
const avatarText = computed(() => String(profile.username || currentUser).slice(0, 2).toUpperCase())
const roleText = computed(() => String(profile.role).toUpperCase() === 'ADMIN' ? '系统管理员' : '普通用户')

async function load() {
  try {
    const data = await getData(`/front/profile?username=${encodeURIComponent(currentUser)}`)
    Object.assign(profile, data)
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '\u8bfb\u53d6\u4e2a\u4eba\u8d44\u6599\u5931\u8d25\uff0c\u8bf7\u7a0d\u540e\u91cd\u8bd5\u3002'))
  }
}

async function saveProfile() {
  try {
    await putData(`/front/profile/${encodeURIComponent(currentUser)}`, profile)
    ElMessage.success('个人资料已保存')
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存个人资料失败，请稍后重试。'))
  }
}

function resetPassword() {
  if (!password.old || !password.next || password.next !== password.confirm) {
    ElMessage.warning('请完整填写密码，并确认两次新密码一致')
    return
  }
  password.old = ''
  password.next = ''
  password.confirm = ''
  ElMessage.success('密码表单已校验，后续可接入认证接口')
}

onMounted(load)
</script>
