<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>用户管理</h2>
      </div>
      <el-button type="primary" @click="openUser()">新增用户</el-button>
    </div>

    <div class="grid user-manage-layout">
      <div class="grid">
        <div class="card table-card user-table-card">
          <div class="card-title">用户列表 <span>app_user</span></div>
          <div class="toolbar inline">
            <el-input v-model="query" placeholder="搜索账号、昵称、角色" style="width:300px" clearable />
            <el-button type="primary" @click="userPage = 1">查询</el-button>
            <el-button @click="query = ''; userPage = 1">重置</el-button>
          </div>
          <table class="table fixed-rows">
            <thead><tr><th>ID</th><th>账号</th><th>昵称</th><th>手机</th><th>邮箱</th><th>角色</th><th>状态</th><th style="width:150px">操作</th></tr></thead>
            <tbody>
              <tr v-for="user in pagedUsers" :key="user.id">
                <td>{{ user.id }}</td>
                <td>{{ user.username }}</td>
                <td>{{ user.nickname }}</td>
                <td>{{ user.phone }}</td>
                <td>{{ user.email }}</td>
                <td>{{ user.role }}</td>
                <td><span class="tag" :class="user.status === 'ENABLED' ? 'green' : 'gray'">{{ user.status }}</span></td>
                <td>
                  <el-button link type="primary" @click="openUser(user)">编辑</el-button>
                  <el-button link type="danger" @click="removeUser(user)">删除</el-button>
                </td>
              </tr>
              <tr v-for="i in emptyUserRows" :key="`empty-${i}`"><td colspan="8"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="userPage" :page-size="pageSize" layout="total, prev, pager, next" :total="filteredUsers.length" />
        </div>
      </div>

      <div class="grid">
        <div class="card">
          <div class="card-title">账号概览 <span>角色与状态</span></div>
          <div class="grid g2">
            <div class="mini-metric"><label>用户总数</label><strong>{{ users.length }}</strong></div>
            <div class="mini-metric"><label>启用账号</label><strong>{{ enabledCount }}</strong></div>
            <div class="mini-metric"><label>管理员</label><strong>{{ adminCount }}</strong></div>
            <div class="mini-metric"><label>普通用户</label><strong>{{ userCount }}</strong></div>
          </div>
        </div>
        <div class="card table-card small-card">
          <div class="card-title">维护记录 <span>本页操作</span></div>
          <table class="table fixed-rows">
            <tbody>
              <tr v-for="log in pagedLogs" :key="log.time"><td>{{ log.time }}</td><td>{{ log.text }}</td></tr>
              <tr v-for="i in emptyLogRows" :key="`log-empty-${i}`"><td colspan="2"></td></tr>
            </tbody>
          </table>
          <el-pagination class="table-pagination" v-model:current-page="logPage" :page-size="logPageSize" layout="total, prev, pager, next" :total="operationLogs.length" />
        </div>
      </div>
    </div>

    <el-dialog v-model="userDialog" :title="draft.id ? '编辑用户' : '新增用户'" width="620px">
      <el-form label-width="82px">
        <el-form-item label="账号"><el-input v-model="draft.username" :disabled="Boolean(draft.id)" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="draft.nickname" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="手机"><el-input v-model="draft.phone" /></el-form-item>
          <el-form-item label="邮箱"><el-input v-model="draft.email" /></el-form-item>
        </div>
        <div class="form-grid">
          <el-form-item label="角色"><el-select v-model="draft.role" style="width:100%"><el-option label="管理员" value="ADMIN" /><el-option label="普通用户" value="USER" /></el-select></el-form-item>
          <el-form-item label="状态"><el-select v-model="draft.status" style="width:100%"><el-option label="启用" value="ENABLED" /><el-option label="禁用" value="DISABLED" /></el-select></el-form-item>
        </div>
        <el-form-item label="简介"><el-input v-model="draft.bio" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
// 用户管理页：维护账号、角色和启用状态。
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteData, getData, getErrorMessage, postData, putData } from '../api/client'

const users = ref<any[]>([])
const query = ref('')
const userPage = ref(1)
const logPage = ref(1)
const pageSize = 5
const logPageSize = 4
const userDialog = ref(false)
const draft = reactive<any>({})
const operationLogs = ref<any[]>([])

const filteredUsers = computed(() => {
  const text = query.value.trim()
  if (!text) return users.value
  return users.value.filter(item => `${item.username}${item.nickname}${item.role}${item.status}`.includes(text))
})
const pagedUsers = computed(() => filteredUsers.value.slice((userPage.value - 1) * pageSize, userPage.value * pageSize))
const emptyUserRows = computed(() => Math.max(0, pageSize - pagedUsers.value.length))
const enabledCount = computed(() => users.value.filter(item => item.status === 'ENABLED').length)
const adminCount = computed(() => users.value.filter(item => item.role === 'ADMIN').length)
const userCount = computed(() => users.value.filter(item => item.role !== 'ADMIN').length)
const pagedLogs = computed(() => operationLogs.value.slice((logPage.value - 1) * logPageSize, logPage.value * logPageSize))
const emptyLogRows = computed(() => Math.max(0, logPageSize - pagedLogs.value.length))

async function load() {
  users.value = await getData('/admin/users').catch(() => [])
  pushLog('用户数据已刷新')
}
function openUser(user?: any) {
  Object.assign(draft, user || { username: '', nickname: '', phone: '', email: '', role: 'USER', status: 'ENABLED', bio: '' })
  userDialog.value = true
}
async function saveUser() {
  if (!draft.username || !draft.nickname) {
    ElMessage.warning('请填写账号和昵称')
    return
  }
  try {
    if (draft.id) await putData(`/admin/users/${draft.id}`, draft)
    else await postData('/admin/users', draft)
    ElMessage.success('用户已保存')
    userDialog.value = false
    pushLog(`${draft.username} 资料已保存`)
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存用户失败，请稍后重试。'))
  }
}
async function removeUser(user: any) {
  if (user.username === 'admin') {
    ElMessage.warning('admin账号不能删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除用户：${user.username}？`, '删除确认')
    await deleteData(`/admin/users/${user.id}`)
    ElMessage.success('用户已删除')
    pushLog(`${user.username} 已删除`)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error, '删除用户失败，请稍后重试。'))
  }
}

function pushLog(text: string) {
  operationLogs.value.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text })
  operationLogs.value = operationLogs.value.slice(0, 12)
}

onMounted(load)
</script>
