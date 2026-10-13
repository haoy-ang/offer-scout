import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isLoggedIn } from '@/stores/session'

/**
 * 路由即信息架构：四个页面正好对应后端的四组能力。
 * 路由 meta 里带章节号，左栏导航和顶部面包屑都从同一处读取，不会两边写重复文案。
 *
 * meta.bare = true 的页面自成一套版式（不套左栏 + 顶栏的外壳），登录页属于这种。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    // 没有 no / label，所以不会被左栏导航收进去
    meta: { bare: true },
  },
  {
    path: '/chat',
    name: 'chat',
    component: () => import('@/views/ChatView.vue'),
    props: { mode: 'chat' },
    meta: { no: '01', label: '对话', hint: '带多轮记忆的通用咨询' },
  },
  {
    path: '/rag',
    name: 'rag',
    component: () => import('@/views/ChatView.vue'),
    props: { mode: 'rag' },
    meta: { no: '02', label: '知识库问答', hint: '先检索面经与岗位资料再作答' },
  },
  {
    path: '/agent',
    name: 'agent',
    component: () => import('@/views/AgentView.vue'),
    meta: { no: '03', label: '作战规划', hint: '自主检索并产出结构化准备方案' },
  },
  {
    path: '/archive',
    name: 'archive',
    component: () => import('@/views/ArchiveView.vue'),
    meta: { no: '04', label: '会话档案', hint: '历史会话记录与清理' },
  },
  {
    path: '/resume',
    name: 'resume',
    component: () => import('@/views/ResumeView.vue'),
    meta: { no: '05', label: '简历诊断', hint: '上传简历，AI 逐项打分并给出改法' },
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 登录守卫。
 * <p>
 * 判据只是"本地有没有 token"，**不在这里调接口验证 token 是否有效** ——
 * 那会让每次跳转都多一次网络往返。真正无效的 token 会在第一个业务请求上撞到 401，
 * 由 `api/client.ts` 统一清凭证并跳回登录页。
 * <p>
 * 这也是为什么两个方向都要拦：没 token 却想进业务页 → 去登录页；
 * 有 token 还想去登录页 → 直接放回对话页，避免登录后再手动跳回来。
 */
router.beforeEach((to) => {
  if (to.meta.bare) {
    return isLoggedIn.value ? { path: '/chat' } : true
  }
  if (!isLoggedIn.value) {
    return {
      name: 'login',
      query: to.fullPath === '/' ? {} : { redirect: to.fullPath },
    }
  }
  return true
})
