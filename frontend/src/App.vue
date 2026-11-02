<template>
  <!-- 公开页（登录）或数据大屏直接渲染，不套用外壳布局 -->
<router-view v-if="route.meta.public || route.meta.group === 'screen'" />

  <!-- 前台分析页外壳：顶部导航 + KeepAlive 缓存的页面内容 -->
<div v-else-if="route.meta.group === 'front'" class="front-shell">
        <!-- 前台顶部：品牌标识、分析页导航、数据大屏与后台入口 -->
    <header class="front-header">
      <router-link class="front-brand" to="/overview">
        <b :title="ANALYSIS_EVENT_NAME">{{ ANALYSIS_EVENT_NAME }}</b>
      </router-link>
      <nav class="front-nav">
        <router-link v-for="item in frontMenus" :key="item.path" :to="item.path">{{ item.name }}</router-link>
      </nav>
      <div class="front-actions">
        <router-link class="screen-link" to="/screen">数据大屏</router-link>
        <!-- 点击当前按钮时执行 goAdmin。 -->
        <el-button type="primary" @click="goAdmin">{{ adminEntryText }}</el-button>
      </div>
    </header>
    <!-- 在此处渲染当前路由匹配的页面组件。 -->
    <router-view v-slot="{ Component }">
      <!-- 缓存切换过的页面组件，返回页面时保留其筛选与滚动状态。 -->
      <KeepAlive>
        <component :is="Component" />
      </KeepAlive>
    </router-view>
  </div>

  <!-- 后台管理外壳：左侧导航栏 + 右侧主区域（顶部状态条 + 页面内容） -->
<div v-else class="app-shell">
        <!-- 左侧导航：品牌区 + 按角色分组的菜单项 -->
    <aside class="sidebar">
      <div class="brand">
        <div>
          <b title="网络媒体热点事件传播态势与舆情分析">网络媒体热点事件<br />传播态势与舆情分析</b>
        </div>
      </div>

      <template v-for="group in adminMenuGroups" :key="group.title">
        <div class="side-section">{{ group.title }}</div>
        <router-link v-for="item in group.children" :key="item.path" class="nav-item" :to="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.name }}</span>
        </router-link>
      </template>
    </aside>

        <!-- 右侧主区域 -->
    <main class="main">
            <!-- 顶部状态条：面包屑、角色权限说明、操作入口与用户卡 -->
      <header class="topbar">
        <div>
          <div class="crumb">{{ groupName }} / {{ route.meta.title }}</div>
          <h1>{{ route.meta.title }}</h1>
        </div>
        <div class="admin-status">
          <span>{{ roleLabel }}</span>
          <b>{{ isAdmin ? '权限边界已启用' : '普通用户权限' }}</b>
          <em>{{ isAdmin ? '原始数据、处理任务与分析结果管理' : '仅可维护本人资料' }}</em>
        </div>
        <div class="top-actions">
          <router-link class="screen-link" to="/overview">返回前台</router-link>
          <router-link v-if="isAdmin" class="screen-link" to="/screen">数据大屏</router-link>
          <div class="user-card">
            <span class="avatar">{{ userInitial }}</span>
            <div>
              <b>{{ userName }}</b>
              <small>已登录</small>
            </div>
          </div>
          <!-- 点击“退出”时执行 logout。 -->
          <el-button plain @click="logout">退出</el-button>
        </div>
      </header>
      <!-- 在此处渲染当前路由匹配的页面组件。 -->
      <router-view v-slot="{ Component }">
        <!-- 缓存切换过的页面组件，返回页面时保留其筛选与滚动状态。 -->
        <KeepAlive>
          <component :is="Component" />
        </KeepAlive>
      </router-view>
    </main>
  </div>
  <!-- AI 助手浮窗：仅在已登录的非公开页面显示，以令牌为键保证换号后重建 -->
<AiAssistant v-if="!route.meta.public && isLoggedIn" :key="session.token" />
</template>

<script setup lang="ts">
import { computed, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataLine, Files, Monitor, User, Postcard } from '@element-plus/icons-vue'
import { clearGetCache, postData } from './api/client'
import { clearSession, readSession } from './utils/session'
import { ANALYSIS_EVENT_NAME } from './config/analysisDataset'
import AiAssistant from './components/ai/AiAssistant.vue'

// 根据路由分组切换公开页、大屏、前台导航及后台侧栏布局。
const route = useRoute()
// 获取路由实例，用于按钮触发的页面跳转。
const router = useRouter()

// 前台分析页的顶部导航菜单项，固定六个分析页面入口。
const frontMenus = [
  { path: '/overview', name: '热点门户' },
  { path: '/analysis', name: '热点趋势' },
  { path: '/platform', name: '平台扩散' },
  { path: '/keywords', name: '关键词分析' },
  { path: '/sentiment', name: '情感分析' },
  { path: '/contents', name: '内容明细' }
]

// 管理员可见的完整后台菜单分组：运营总览、数据维护、系统权限。
const fullAdminMenuGroups = [
  {
    title: '运营总览',
    children: [
      { path: '/admin', name: '后台首页', icon: Monitor },
      { path: '/admin/etl', name: 'ETL任务', icon: DataLine }
    ]
  },
  {
    title: '数据维护',
    children: [
      { path: '/admin/data', name: '数据管理', icon: Files },
      { path: '/admin/users', name: '用户管理', icon: User }
    ]
  },
  {
    title: '系统权限',
    children: [
      { path: '/admin/profile', name: '个人信息', icon: Postcard }
    ]
  }
]

// 普通用户仅可见个人中心，无法访问运营与数据维护入口。
const userMenuGroups = [
  {
    title: '个人中心',
    children: [
      { path: '/admin/profile', name: '个人信息', icon: Postcard }
    ]
  }
]

// 顶部面包屑分组名，按当前路由所属分组显示后台或前台。
const groupName = computed(() => route.meta.group === 'admin' ? '后台管理' : '前台分析')
// 会话状态保存在响应式引用中，路由切换时刷新以反映登录/退出变化。
const session = ref(readSession())
// 读取会话中的用户名，缺少时使用默认显示名称。
const userName = computed(() => session.value.user || '管理员')
// 用户名首字符作为头像占位文字。
const userInitial = computed(() => userName.value.slice(0, 1))
// 根据会话令牌是否存在判断当前是否登录。
const isLoggedIn = computed(() => Boolean(session.value.token))
// 将角色转为小写后判断是否为管理员。
const isAdmin = computed(() => session.value.role.toLowerCase() === 'admin')
// 根据角色切换侧栏菜单：管理员显示全部，普通用户只显示个人中心。
const adminMenuGroups = computed(() => isAdmin.value ? fullAdminMenuGroups : userMenuGroups)
// 根据当前角色计算界面显示的中文身份名称。
const roleLabel = computed(() => isAdmin.value ? '管理员' : '普通用户')
// 顶部"后台"按钮文案随登录状态和角色变化。
const adminEntryText = computed(() => !isLoggedIn.value ? '后台登录' : isAdmin.value ? '后台管理' : '个人信息')

// 路由切换时重新读取本地会话，确保退出或换号后界面身份同步。
watch(() => route.fullPath, () => {
  session.value = readSession()
}, { immediate: true })

// 普通用户误入需要管理员权限的页面时，重定向到个人资料页。
watchEffect(() => {
  if (route.meta.requiresAdmin && isLoggedIn.value && !isAdmin.value) {
    router.replace('/admin/profile')
  }
})

// 点击顶部"后台"按钮：已登录按角色跳转，未登录跳登录页并记录回跳地址。
function goAdmin() {
  if (isLoggedIn.value) {
    router.push(isAdmin.value ? '/admin' : '/admin/profile')
    return
  }
  router.push('/login?redirect=/admin')
}

// 退出登录：通知后端销毁会话，清理本地令牌与请求缓存后回到登录页。
async function logout() {
  // 请求后端退出接口，随后无论接口结果如何都在本地清理登录状态。
  try {
    await postData('/auth/logout', {})
  } catch {
    // 后端不可用时也继续完成本地退出。
  }
  clearGetCache()
  clearSession()
  session.value = readSession()
  router.replace('/login')
}
</script>
