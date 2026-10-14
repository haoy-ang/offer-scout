import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { isLoggedIn } from '@/stores/session'

/**
 * 路由即信息架构：每个业务页面正好对应后端的一组能力。
 *
 * meta.no —— **只作内部标记，不呈现给用户**。它承担两件事：
 *            ① 左栏导航的准入条件（有 no 的路由才收进导航）；
 *            ② 左栏的顺序键（00 → 06）。
 *            它曾经会被渲染成界面上的「§ 01」章节标号，现已按需求去掉 ——
 *            避免"编号是装饰、改一次要同步四处硬编码"这种维护债。
 * meta.label / hint —— 对用户可见的文案：左栏名称 + 顶栏的一句话说明。
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
    // 通用对话与知识库问答合并成同一页面，靠 ?kb=1 区分（见 views/ChatView.vue）。
    // 不设 props：模式从 URL 读，切换开关不会让组件重挂，对话历史得以保留。
    meta: { no: '01', label: '对话', hint: '多轮记忆，可切换「检索知识库」' },
  },
  {
    // 旧的知识库问答入口：保留路径做重定向，老书签 / 外链不会 404
    path: '/rag',
    redirect: { name: 'chat', query: { kb: '1' } },
  },
  {
    path: '/agent',
    name: 'agent',
    component: () => import('@/views/AgentView.vue'),
    meta: { no: '02', label: '作战规划', hint: '自主检索并产出结构化准备方案' },
  },
  {
    path: '/archive',
    name: 'archive',
    component: () => import('@/views/ArchiveView.vue'),
    meta: { no: '03', label: '会话档案', hint: '历史会话记录、继续对话与清理' },
  },
  {
    path: '/resume',
    name: 'resume',
    component: () => import('@/views/ResumeView.vue'),
    meta: { no: '04', label: '简历诊断', hint: '上传简历，AI 逐项打分并给出改法' },
  },
  {
    path: '/match',
    name: 'match',
    component: () => import('@/views/JobMatchView.vue'),
    meta: { no: '05', label: '岗位匹配', hint: '简历对照 JD，算出离这个岗位差多远' },
  },
  {
    path: '/interview',
    name: 'interview',
    component: () => import('@/views/InterviewView.vue'),
    meta: { no: '06', label: '模拟面试', hint: 'AI 面试官多轮追问，结束给结构化复盘' },
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

/**
 * 懒加载失败兜底。
 * <p>
 * 每个路由都是 `component: () => import(...)` —— 首次进入某个页面时才去拉那一块 JS。
 * 拉不到的时候，Vue Router 会把整个导航判为失败，而 {@code RouterView} 里什么都不会渲染：
 * 用户点一下左栏，页面纹丝不动，控制台之外看不到任何线索，只能自己猜"是不是卡了"。
 * <p>
 * 最常见的成因不是网络断了，而是**"部署了新版本、浏览器还揣着旧 index.html"**：
 * 旧页面按老哈希去找已经下线的 chunk 文件，必然 404。这种情况下整页重载一次
 * （拉新的 index.html 和新的 chunk）基本就能好 —— 也是唯一能让用户自己走出去的办法。
 * <p>
 * 用 sessionStorage 当保险丝，确保**只自动重载一次**：如果重载后依然失败，
 * 那就是真坏了（或者 chunk 一直拿不到），再刷下去只会变成刷新死循环。
 */
const CHUNK_RETRY_FLAG = 'offerscout.chunk-reloaded'

const CHUNK_FAILURE_RE = /Loading chunk|dynamically imported module|Importing a module script failed/i

router.onError((error, to) => {
  const message = error instanceof Error ? error.message : String(error)
  // 只处理"模块拉不下来"这一类；组件内部抛的错重载也不会好，不该拿刷新去掩盖
  if (!CHUNK_FAILURE_RE.test(message)) return

  try {
    if (sessionStorage.getItem(CHUNK_RETRY_FLAG)) {
      // 已经救过一次还是失败：停在这儿，把原因留给控制台，别陷入无限刷新
      console.error('[router] 懒加载 chunk 仍然取不到，已放弃自动重载：', error)
      return
    }
    sessionStorage.setItem(CHUNK_RETRY_FLAG, '1')
  } catch {
    // 存不进标记（无痕模式等）就不敢重载 —— 宁可白屏也不能冒刷新死循环的风险
    return
  }

  window.location.assign(to.fullPath)
})

// 只要有一次导航成功，就说明 chunk 拉得下来，把保险丝复位，下次出问题还能再救一次
router.afterEach(() => {
  try {
    sessionStorage.removeItem(CHUNK_RETRY_FLAG)
  } catch {
    // 无所谓，清不掉顶多下次少救一次
  }
})
