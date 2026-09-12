<template>
  <div class="login-page">
    <section class="login-hero">
      <div class="login-badge">后台管理入口</div>
      <h1>社交媒体热点事件传播特征分析系统</h1>
      <p>前台用于展示热点事件分析结果，后台用于维护事件数据、ETL任务、系统参数和用户权限。</p>
      <div class="login-stats">
        <div><b>3</b><span>虚拟机节点</span></div>
        <div><b>8</b><span>ADS结果表</span></div>
        <div><b>5秒</b><span>大屏刷新</span></div>
      </div>
    </section>

    <section class="login-card">
      <h2>系统登录</h2>
      <p>管理员进入完整后台，普通用户只能查看前台和维护自己的个人信息</p>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="账号">
          <el-input v-model="form.username" size="large" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" size="large" type="password" show-password placeholder="123456" />
        </el-form-item>
        <el-button class="login-btn" type="primary" size="large" @click="login">登录系统</el-button>
        <el-button class="login-btn secondary" size="large" @click="router.push('/overview')">进入前台展示</el-button>
      </el-form>
      <div class="login-tip">管理员：admin / 123456；普通用户：student / 123456</div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

const router = useRouter()
const route = useRoute()
const form = reactive({ username: 'admin', password: '123456' })

function login() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  if (form.password !== '123456') {
    ElMessage.warning('演示账号密码为 123456')
    return
  }
  const username = form.username.trim()
  const role = username === 'admin' ? 'admin' : 'user'
  localStorage.setItem('social_token', 'local-demo-token')
  localStorage.setItem('social_user', username)
  localStorage.setItem('social_role', role)
  ElMessage.success('登录成功')
  const redirect = (route.query.redirect as string) || ''
  if (role === 'admin') {
    router.replace(redirect || '/admin')
  } else {
    router.replace(redirect.startsWith('/admin') ? '/admin/profile' : (redirect || '/overview'))
  }
}
</script>
