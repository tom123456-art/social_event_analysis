<template>
  <!-- 账号列表与资料弹框共用编辑草稿，操作日志仅记录当前页面会话中的动作。 -->
  <div class="page">
    <div class="page-head">
      <div>
        <h2>用户管理</h2>
      </div>
      <!-- 点击“新增用户”时执行 openUser()。 -->
      <el-button type="primary" @click="openUser()">新增用户</el-button>
    </div>

    <div class="grid user-manage-layout">
      <div class="grid">
        <div class="card table-card user-table-card">
          <div class="card-title">用户列表 <span>账号资料</span></div>
          <div class="toolbar inline">
            <!-- 将“搜索账号、昵称、角色”的输入或选择同步到 query，供查询或提交时读取。 -->
            <el-input v-model="query" placeholder="搜索账号、昵称、角色" style="width:300px" clearable />
            <!-- 点击“查询”时执行 userPage = 1。 -->
            <el-button type="primary" @click="userPage = 1">查询</el-button>
            <!-- 点击“重置”时执行 query = ''; userPage = 1。 -->
            <el-button @click="query = ''; userPage = 1">重置</el-button>
          </div>
          <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
          <!-- 使用表格展示当前列表，表头定义列含义，表体按记录循环生成行。 -->
          <table class="table fixed-rows">
            <thead><tr><th>编号</th><th>账号</th><th>昵称</th><th>手机</th><th>邮箱</th><th>角色</th><th>状态</th><th style="width:150px">操作</th></tr></thead>
            <tbody>
              <!-- 按 user in pagedUsers 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="user in pagedUsers" :key="user.id">
                <td>{{ user.id }}</td>
                <td>{{ user.username }}</td>
                <td>{{ user.nickname }}</td>
                <td>{{ user.phone }}</td>
                <td>{{ user.email }}</td>
                <td>{{ roleName(user.role) }}</td>
                <td><span class="tag" :class="user.status === 'ENABLED' ? 'green' : 'gray'">{{ statusName(user.status) }}</span></td>
                <td>
                  <!-- 点击“编辑”时执行 openUser(user)。 -->
                  <el-button link type="primary" @click="openUser(user)">编辑</el-button>
                  <!-- 点击“删除”时执行 removeUser(user)。 -->
                  <el-button link type="danger" @click="removeUser(user)">删除</el-button>
                </td>
              </tr>
              <!-- 按 i in emptyUserRows 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="i in emptyUserRows" :key="`empty-${i}`"><td colspan="8"></td></tr>
            </tbody>
          </table>
          <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
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
              <!-- 按 log in pagedLogs 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="log in pagedLogs" :key="log.time"><td>{{ log.time }}</td><td>{{ log.text }}</td></tr>
              <!-- 按 i in emptyLogRows 循环生成表格行，使用 key 保持行标识稳定。 -->
              <tr v-for="i in emptyLogRows" :key="`log-empty-${i}`"><td colspan="2"></td></tr>
            </tbody>
          </table>
          <!-- 绑定当前页码和总条数，翻页后重新查询或重新截取对应记录。 -->
          <el-pagination class="table-pagination" v-model:current-page="logPage" :page-size="logPageSize" layout="total, prev, pager, next" :total="operationLogs.length" />
        </div>
      </div>
    </div>

    <!-- 将弹框显隐与 userDialog 绑定，集中展示当前操作的详情或表单。 -->
    <el-dialog v-model="userDialog" :title="draft.id ? '编辑用户' : '新增用户'" width="620px">
      <!-- 使用表单模型和校验规则收集输入，提交时执行绑定的处理函数。 -->
      <el-form label-width="82px">
        <!-- 为“账号”展示输入控件，并承接该字段的校验提示。 -->
        <el-form-item label="账号"><el-input v-model="draft.username" :disabled="Boolean(draft.id)" /></el-form-item>
        <!-- 为“当前字段”展示输入控件，并承接该字段的校验提示。 -->
        <el-form-item :label="draft.id ? '重设密码' : '初始密码'"><el-input v-model="draft.password" type="password" show-password autocomplete="new-password" :placeholder="draft.id ? '留空保持原密码' : '八至六十四位字符'" /></el-form-item>
        <!-- 为“昵称”展示输入控件，并承接该字段的校验提示。 -->
        <el-form-item label="昵称"><el-input v-model="draft.nickname" /></el-form-item>
        <div class="form-grid">
          <!-- 为“手机”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="手机"><el-input v-model="draft.phone" /></el-form-item>
          <!-- 为“邮箱”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="邮箱"><el-input v-model="draft.email" /></el-form-item>
        </div>
        <div class="form-grid">
          <!-- 为“角色”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="角色"><el-select v-model="draft.role" style="width:100%"><el-option label="管理员" value="ADMIN" /><el-option label="普通用户" value="USER" /></el-select></el-form-item>
          <!-- 为“状态”展示输入控件，并承接该字段的校验提示。 -->
          <el-form-item label="状态"><el-select v-model="draft.status" style="width:100%"><el-option label="启用" value="ENABLED" /><el-option label="禁用" value="DISABLED" /></el-select></el-form-item>
        </div>
        <!-- 为“简介”展示输入控件，并承接该字段的校验提示。 -->
        <el-form-item label="简介"><el-input v-model="draft.bio" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <!-- 点击“取消”时执行 userDialog = false。 -->
        <el-button @click="userDialog = false">取消</el-button>
        <!-- 点击“保存”时执行 saveUser。 -->
        <el-button type="primary" @click="saveUser">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteData, getData, getErrorMessage, postData, putData } from '../../api/client'
import { roleName, statusName } from '../../utils/userText'

// 保存后端账号列表；输入框只在这份列表内执行文本筛选。
const users = ref<any[]>([])
const query = ref('')
// 用户表与操作提示表分别维护页码和每页条数。
const userPage = ref(1)
const logPage = ref(1)
// 定义每页展示数量，分页切片和补齐空行共同使用。
const pageSize = 5
const logPageSize = 4
// 控制资料弹框，并保存新增或编辑账号时的草稿。
const userDialog = ref(false)
const draft = reactive<any>({})
// 仅保存当前页面产生的操作提示，不是后端审计记录。
const operationLogs = ref<any[]>([])

const filteredUsers = computed(() => {
  const text = query.value.trim()
  // 空检索词不执行过滤；非空时匹配账号、昵称和角色、状态中文名称。
  if (!text) return users.value
  return users.value.filter(item => `${item.username}${item.nickname}${roleName(item.role)}${statusName(item.status)}`.includes(text))
})
// 先过滤再分页，保证总数和当前页使用相同的筛选条件。
const pagedUsers = computed(() => filteredUsers.value.slice((userPage.value - 1) * pageSize, userPage.value * pageSize))
// 根据当前页记录数计算占位空行。
const emptyUserRows = computed(() => Math.max(0, pageSize - pagedUsers.value.length))
// 三项概览分别统计启用账号、管理员账号及非管理员账号。
const enabledCount = computed(() => users.value.filter(item => item.status === 'ENABLED').length)
const adminCount = computed(() => users.value.filter(item => item.role === 'ADMIN').length)
const userCount = computed(() => users.value.filter(item => item.role !== 'ADMIN').length)
// 操作提示按独立页码截取，不受用户表分页影响。
const pagedLogs = computed(() => operationLogs.value.slice((logPage.value - 1) * logPageSize, logPage.value * logPageSize))
const emptyLogRows = computed(() => Math.max(0, logPageSize - pagedLogs.value.length))

async function load() {
  // 获取账号列表，接口异常时使用空列表，并记录本次刷新操作。
  users.value = await getData('/admin/users').catch(() => [])
  pushLog('用户数据已刷新')
}
// 打开新增或编辑前清空旧草稿，密码字段始终重新输入，不回显已有密码。
function openUser(user?: any) {
  // 删除上次编辑遗留的属性，再填入当前账号或新增默认值。
  for (const key of Object.keys(draft)) delete draft[key]
  Object.assign(draft, user || { username: '', nickname: '', phone: '', email: '', role: 'USER', status: 'ENABLED', bio: '' }, { password: '' })
  userDialog.value = true
}
// 新账号必须设置密码；编辑账号仅在填写新密码时校验字符数与编码字节数。
async function saveUser() {
  // 账号与昵称是必填字段，缺失时不向后端提交。
  if (!draft.username || !draft.nickname) {
    ElMessage.warning('请填写账号和昵称')
    return
  }
  // 新建账号或主动修改密码时，检查字符长度和 UTF-8 字节上限。
  if ((!draft.id || draft.password) && (!draft.password || draft.password.length < 8 || draft.password.length > 64 || new TextEncoder().encode(draft.password).length > 72)) {
    ElMessage.warning('请设置至少八位的初始密码')
    return
  }
  try {
    // 有数据库编号表示编辑，使用 PUT；没有编号表示新增，使用 POST。
    if (draft.id) await putData(`/admin/users/${draft.id}`, draft)
    else await postData('/admin/users', draft)
    ElMessage.success('用户已保存')
    // 保存成功后关闭弹框、记录操作提示并重读账号列表。
    userDialog.value = false
    pushLog(`${draft.username} 资料已保存`)
    await load()
  } catch (error) {
    ElMessage.error(getErrorMessage(error, '保存用户失败，请稍后重试。'))
  }
}
// 前端禁止删除默认管理员，并在删除其他账号前要求确认；权限仍由后端校验。
async function removeUser(user: any) {
  if (user.username === 'admin') {
    ElMessage.warning('系统管理员账号不能删除')
    return
  }
  try {
    await ElMessageBox.confirm(`确认删除用户：${user.username}？`, '删除确认')
    await deleteData(`/admin/users/${user.id}`)
    ElMessage.success('用户已删除')
    pushLog(`${user.username} 已删除`)
    await load()
  } catch (error) {
    // 用户取消确认是正常操作，不显示删除失败提示。
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getErrorMessage(error, '删除用户失败，请稍后重试。'))
  }
}

// 页面内仅保留最近十二条操作提示，不替代后端持久化审计日志。
function pushLog(text: string) {
  // 新提示放到列表最前面，并附上当前本地时刻。
  operationLogs.value.unshift({ time: new Date().toLocaleTimeString('zh-CN', { hour12: false }), text })
  operationLogs.value = operationLogs.value.slice(0, 12)
}

onMounted(load)
</script>
