import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

/**
 * 路由即信息架构：四个页面正好对应后端的四组能力。
 * 路由 meta 里带章节号，左栏导航和顶部面包屑都从同一处读取，不会两边写重复文案。
 */
const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/chat' },
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
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
