import { computed, ref } from 'vue'

/**
 * 登录态（会话）。
 * <p>
 * 刻意不引 Pinia / Vuex：整个项目需要共享的状态只有「token + 当前用户」两个字段，
 * 而它们本身已经是响应式的 ref —— 再套一层状态库，收益只是多一个依赖和一堆样板代码。
 * 什么时候该上状态库？当出现「多个模块互相改同一份状态、且需要时间旅行调试/插件生态」时。
 * 现在还没到那一步。
 * <p>
 * 这个文件**不 import 任何 API 模块**，只做纯状态。
 * 原因是 `api/client.ts` 需要读 token 去拼请求头；如果这里反过来 import client 去调登录接口，
 * 两个模块就形成循环依赖了。所以约定：状态留在这里，接口调用留在 client。
 */

export interface SessionUser {
  id: number
  username: string
  nickname: string | null
}

/**
 * token 存 localStorage 而不是内存变量。
 * <p>
 * 代价要说清楚：**localStorage 会被 XSS 读到**。真正更安全的做法是把 token 放进
 * HttpOnly + Secure + SameSite 的 Cookie，让 JS 完全碰不到它 ——
 * 但那要求后端支持 Cookie 鉴权并处理 CSRF，本项目没做那么重。
 * 这里选 localStorage 是"实现简单"与"安全强度"之间的一次明确取舍，
 * 对应的补偿措施是：token 有效期压到 12 小时，且登出时服务端会把它拉黑。
 */
const TOKEN_KEY = 'offerscout.token'

const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
const user = ref<SessionUser | null>(null)

/** 本地有没有 token。注意：它只代表"本地有凭证"，不代表服务端认可 —— 真正的判据是接口返回 401 */
export const isLoggedIn = computed(() => token.value.length > 0)

/** 当前登录用户（刷新页面后需要调 /auth/me 才会填上） */
export const currentUser = computed(() => user.value)

export function getToken(): string {
  return token.value
}

/** 登录/注册成功后写入凭证 */
export function setSession(newToken: string, newUser: SessionUser): void {
  token.value = newToken
  user.value = newUser
  localStorage.setItem(TOKEN_KEY, newToken)
}

export function setUser(next: SessionUser | null): void {
  user.value = next
}

/** 清空本地登录态（登出、或接口返回 401 时） */
export function clearSession(): void {
  token.value = ''
  user.value = null
  localStorage.removeItem(TOKEN_KEY)
}
