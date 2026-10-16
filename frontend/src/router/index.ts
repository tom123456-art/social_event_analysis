// 前端路由配置：将访问路径映射到对应的页面组件。
import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/auth/LoginView.vue'
import OverviewView from '../views/analysis/OverviewView.vue'
import HotspotTrendView from '../views/analysis/HotspotTrendView.vue'
import PlatformSpreadView from '../views/analysis/PlatformSpreadView.vue'
import KeywordAnalysisView from '../views/analysis/KeywordAnalysisView.vue'
import SentimentAnalysisView from '../views/analysis/SentimentAnalysisView.vue'
import ContentListView from '../views/analysis/ContentListView.vue'
import AdminHomeView from '../views/admin/AdminHomeView.vue'
import DataManagementView from '../views/admin/DataManagementView.vue'
import EtlTaskView from '../views/admin/EtlTaskView.vue'
import UserManagementView from '../views/admin/UserManagementView.vue'
import ProfileView from '../views/user/ProfileView.vue'
import ScreenView from '../views/screen/ScreenView.vue'

// 创建使用浏览器 History API 的路由实例。
const router = createRouter({
  // 页面 URL 不使用哈希片段。
  history: createWebHistory(),
  // 定义分析页、管理页和兼容跳转规则。
  routes: [
    // 根地址默认进入热点门户。
    { path: '/', redirect: '/overview' },
    // 登录页是路由守卫允许匿名进入的公开页面。
    { path: '/login', component: LoginView, meta: { title: '登录', public: true } },
    // 热点门户展示当前事件的整体分析结果。
    { path: '/overview', component: OverviewView, meta: { title: '热点门户', group: 'front' } },
    // 热点趋势页按平台和时间范围比较热度。
    { path: '/analysis', component: HotspotTrendView, meta: { title: '热点趋势', group: 'front' } },
    // 平台扩散页展示平台之间的观测先后关系。
    { path: '/platform', component: PlatformSpreadView, meta: { title: '平台扩散', group: 'front' } },
    // 兼容重构前的互动分析地址。
    { path: '/interaction', redirect: '/analysis' },
    // 关键词页展示词频与主题观察结果。
    { path: '/keywords', component: KeywordAnalysisView, meta: { title: '关键词分析', group: 'front' } },
    // 情感页展示满足情感资格的内容统计。
    { path: '/sentiment', component: SentimentAnalysisView, meta: { title: '情感分析', group: 'front' } },
    // 内容页提供分析明细查询入口。
    { path: '/contents', component: ContentListView, meta: { title: '内容明细', group: 'front' } },
    // 管理首页只允许管理员访问。
    { path: '/admin', component: AdminHomeView, meta: { title: '后台首页', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    // 数据管理页只允许管理员维护原始数据和审核队列。
    { path: '/admin/data', component: DataManagementView, meta: { title: '数据管理', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    // ETL 任务页只允许管理员提交和查看处理任务。
    { path: '/admin/etl', component: EtlTaskView, meta: { title: 'ETL任务', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    // 用户管理页只允许管理员维护账号。
    { path: '/admin/users', component: UserManagementView, meta: { title: '用户管理', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    // 个人资料页允许所有已登录用户进入。
    { path: '/admin/profile', component: ProfileView, meta: { title: '个人信息', group: 'admin', requiresAuth: true } },
    // 数据大屏使用独立的 screen 页面分组。
    { path: '/screen', component: ScreenView, meta: { title: '数据大屏', group: 'screen' } }
  ]
})

// 在每次导航前检查本地会话及管理页面角色。
router.beforeEach((to) => {
  // 公开页面无需认证。
  if (to.meta.public) return true
  // 未登录用户跳转到登录页并保留目标地址。
  if (!localStorage.getItem('social_token')) {
    return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  // 读取本地角色，仅用于界面路由控制，接口权限仍由后端校验。
  const isAdmin = localStorage.getItem('social_role') === 'admin'
  // 普通用户访问管理员页面时转到本人资料页。
  if (to.meta.requiresAdmin && !isAdmin) {
    return '/admin/profile'
  }
  // 已登录且角色满足目标页面要求时放行导航。
  return true
})

// 导出路由实例供应用入口安装。
export default router
