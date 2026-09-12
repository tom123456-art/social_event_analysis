import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import OverviewView from '../views/OverviewView.vue'
import EventAnalysisView from '../views/EventAnalysisView.vue'
import PlatformSpreadView from '../views/PlatformSpreadView.vue'
import KeywordSentimentView from '../views/KeywordSentimentView.vue'
import SentimentAnalysisView from '../views/SentimentAnalysisView.vue'
import UserProfileAnalysisView from '../views/UserProfileAnalysisView.vue'
import ContentDetailView from '../views/ContentDetailView.vue'
import AdminHomeView from '../views/AdminHomeView.vue'
import DataManageView from '../views/DataManageView.vue'
import EtlTaskView from '../views/EtlTaskView.vue'
import UserManageView from '../views/UserManageView.vue'
import ProfileManageView from '../views/ProfileManageView.vue'
import CrawlerManageView from '../views/CrawlerManageView.vue'
import ScreenView from '../views/ScreenView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/overview' },
    { path: '/login', component: LoginView, meta: { title: '登录', public: true } },
    { path: '/overview', component: OverviewView, meta: { title: '热点门户', group: 'front' } },
    { path: '/analysis', component: EventAnalysisView, meta: { title: '传播趋势', group: 'front' } },
    { path: '/platform', component: PlatformSpreadView, meta: { title: '平台扩散', group: 'front' } },
    { path: '/interaction', redirect: '/analysis' },
    { path: '/keywords', component: KeywordSentimentView, meta: { title: '关键词分析', group: 'front' } },
    { path: '/sentiment', component: SentimentAnalysisView, meta: { title: '情感分析', group: 'front' } },
    { path: '/users', component: UserProfileAnalysisView, meta: { title: '用户画像', group: 'front' } },
    { path: '/contents', component: ContentDetailView, meta: { title: '内容明细', group: 'front' } },
    { path: '/admin', component: AdminHomeView, meta: { title: '后台首页', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    { path: '/admin/crawler', component: CrawlerManageView, meta: { title: '采集管理', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    { path: '/admin/data', component: DataManageView, meta: { title: '数据管理', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    { path: '/admin/etl', component: EtlTaskView, meta: { title: 'ETL任务', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    { path: '/admin/users', component: UserManageView, meta: { title: '用户管理', group: 'admin', requiresAuth: true, requiresAdmin: true } },
    { path: '/admin/profile', component: ProfileManageView, meta: { title: '个人信息', group: 'admin', requiresAuth: true } },
    { path: '/screen', component: ScreenView, meta: { title: '数据大屏', group: 'screen' } }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public || to.meta.group === 'screen') return true
  if (to.meta.group === 'front') return true
  if (!to.meta.requiresAuth) return true
  if (!localStorage.getItem('social_token')) {
    return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  }
  const isAdmin = localStorage.getItem('social_user') === 'admin' && localStorage.getItem('social_role') === 'admin'
  if (to.meta.requiresAdmin && !isAdmin) {
    return '/admin/profile'
  }
  return true
})

export default router
