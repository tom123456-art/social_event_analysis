<template>
  <router-view v-if="route.meta.public || route.meta.group === 'screen'" />

  <div v-else-if="route.meta.group === 'front'" class="front-shell">
    <header class="front-header">
      <router-link class="front-brand" to="/overview">
        <span class="brand-mark">SH</span>
        <div>
          <b>社交媒体热点传播分析</b>
          <small>Public Analysis Portal</small>
        </div>
      </router-link>
      <nav class="front-nav">
        <router-link v-for="item in frontMenus" :key="item.path" :to="item.path">{{ item.name }}</router-link>
      </nav>
      <div class="front-actions">
        <router-link class="screen-link" to="/screen">数据大屏</router-link>
        <el-button type="primary" @click="goAdmin">{{ adminEntryText }}</el-button>
      </div>
    </header>
    <router-view v-slot="{ Component }">
      <KeepAlive>
        <component :is="Component" />
      </KeepAlive>
    </router-view>
  </div>

  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark">SH</div>
        <div>
          <b>社媒热点传播分析</b>
          <span>Spark Analytics Console</span>
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

    <main class="main">
      <header class="topbar">
        <div>
          <div class="crumb">{{ groupName }} / {{ route.meta.title }}</div>
          <h1>{{ route.meta.title }}</h1>
        </div>
        <div class="admin-status">
          <span>{{ roleLabel }}</span>
          <b>{{ isAdmin ? '权限边界已启用' : '普通用户权限' }}</b>
          <em>{{ isAdmin ? 'Raw CSV / Spark ETL / ADS 管理' : '仅可维护本人资料' }}</em>
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
          <el-button plain @click="logout">退出</el-button>
        </div>
      </header>
      <router-view v-slot="{ Component }">
        <KeepAlive>
          <component :is="Component" />
        </KeepAlive>
      </router-view>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { DataLine, Files, Monitor, User, Postcard } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()

const frontMenus = [
  { path: '/overview', name: '热点门户' },
  { path: '/analysis', name: '热点趋势' },
  { path: '/platform', name: '平台扩散' },
  { path: '/keywords', name: '关键词分析' },
  { path: '/sentiment', name: '情感分析' },
  { path: '/contents', name: '内容明细' }
]

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

const userMenuGroups = [
  {
    title: '个人中心',
    children: [
      { path: '/admin/profile', name: '个人信息', icon: Postcard }
    ]
  }
]

const groupName = computed(() => route.meta.group === 'admin' ? '后台管理' : '前台分析')
const session = ref(readSession())
const userName = computed(() => session.value.user || '管理员')
const userInitial = computed(() => userName.value.slice(0, 1))
const isLoggedIn = computed(() => Boolean(session.value.token))
const isAdmin = computed(() => session.value.user === 'admin' && session.value.role === 'admin')
const adminMenuGroups = computed(() => isAdmin.value ? fullAdminMenuGroups : userMenuGroups)
const roleLabel = computed(() => isAdmin.value ? 'ADMIN' : 'USER')
const adminEntryText = computed(() => !isLoggedIn.value ? '后台登录' : isAdmin.value ? '后台管理' : '个人信息')

watch(() => route.fullPath, () => {
  session.value = readSession()
}, { immediate: true })

watchEffect(() => {
  if (route.meta.requiresAdmin && isLoggedIn.value && !isAdmin.value) {
    router.replace('/admin/profile')
  }
})

function readSession() {
  return {
    token: localStorage.getItem('social_token') || '',
    user: localStorage.getItem('social_user') || '',
    role: (localStorage.getItem('social_role') || '').toLowerCase()
  }
}

function goAdmin() {
  if (isLoggedIn.value) {
    router.push(isAdmin.value ? '/admin' : '/admin/profile')
    return
  }
  router.push('/login?redirect=/admin')
}

function logout() {
  localStorage.removeItem('social_token')
  localStorage.removeItem('social_user')
  localStorage.removeItem('social_role')
  session.value = readSession()
  router.replace('/login')
}
</script>
