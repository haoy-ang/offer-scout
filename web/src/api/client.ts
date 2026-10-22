/**
 * 后端接口封装。
 * <p>
 * 所有请求都走相对路径 `/api`：
 * 开发期由 Vite 代理到 localhost:8124，生产期交给 Nginx 反代。
 * 业务代码里不出现任何 host，换环境不用改一行。
 * <p>
 * 这一层同时负责"带上登录凭证"和"处理登录失效"两件事 —— 放在这里而不是各个页面里，
 * 是为了让每个调用点都不需要记得加 Authorization 头，也不会漏掉 401 的处理。
 */

import { clearSession, getToken, type SessionUser } from '@/stores/session'
import { noteRateLimit } from '@/stores/cooldown'
import { router } from '@/router'

const BASE = '/api'

export interface Conversation {
  id: string
  title: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

export interface ChatMessage {
  role: 'user' | 'assistant' | 'system' | 'tool'
  content: string | null
}

export interface AuthResult {
  token: string
  expiresIn: number
  user: SessionUser
}

/** 简历诊断结果（和后端 ResumeDiagnosis record 一一对应） */
export interface ResumeDiagnosis {
  overallScore: number
  summary: string
  dimensions: { name: string; score: number; comment: string }[]
  suggestions: { priority: 'high' | 'medium' | 'low'; problem: string; fix: string }[]
}

/** 岗位匹配结果（和后端 JobMatch record 一一对应） */
export interface JobMatch {
  overallScore: number
  summary: string
  strengths: { requirement: string; detail: string }[]
  gaps: { requirement: string; detail: string }[]
  dimensions: { name: string; score: number; comment: string }[]
  suggestions: { priority: 'high' | 'medium' | 'low'; problem: string; fix: string }[]
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE'
  /** 请求体（会被 JSON 序列化）。有值时自动带上 Content-Type */
  body?: unknown
  /**
   * 匿名请求：不带 token，且 401 不触发"跳登录页"。
   * 登录/注册接口专用 —— 密码输错时后端返回 401，
   * 如果不区分地当成"会话过期"，用户会看到一个莫名其妙的跳转，而不是"密码错误"。
   */
  anonymous?: boolean
}

function buildUrl(path: string, params: Record<string, string | undefined> = {}): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, value)
  })
  const qs = search.toString()
  return qs ? `${BASE}${path}?${qs}` : `${BASE}${path}`
}

/** 从失败响应里解析出的信息 */
interface ErrorDetail {
  message: string
  /** 限流时后端给的等待秒数；没有则为 0 */
  retryAfter: number
}

/**
 * 从失败的响应里挖出人话。
 * <p>
 * 后端限流时返回的是 `{"code":429,"message":"请求太频繁了，请 12 秒后再试。","retryAfter":12}`，
 * 如果直接 `res.text()` 塞给用户，界面上就会出现一坨 JSON。
 * 所以先试着按 JSON 解析、取 message 字段；解不出来（比如后端抛了堆栈）就原样返回。
 * <p>
 * `retryAfter` 也一并取出来 —— 它要用来在界面上显示倒计时。过去这里只取了 message、
 * 把这个数字丢掉了，结果用户只知道「请稍后再试」，不知道该等几秒，只能反复盲点。
 */
async function readError(res: Response): Promise<ErrorDetail> {
  const raw = await res.text().catch(() => '')
  let message = res.statusText || `HTTP ${res.status}`
  let retryAfter = 0

  if (raw) {
    message = raw
    try {
      const parsed = JSON.parse(raw) as { message?: unknown; retryAfter?: unknown }
      if (typeof parsed?.message === 'string' && parsed.message) message = parsed.message
      if (typeof parsed?.retryAfter === 'number' && parsed.retryAfter > 0) {
        retryAfter = parsed.retryAfter
      }
    } catch {
      // 不是 JSON（后端直接抛了堆栈），原样返回
    }
  }

  // 兜底：响应体里没带就退回头部的 Retry-After（标准头，大小写不敏感）
  if (!retryAfter) {
    const header = Number(res.headers.get('retry-after'))
    if (Number.isFinite(header) && header > 0) retryAfter = header
  }
  return { message, retryAfter }
}

/**
 * 统一的 HTTP 失败处理：401 送登录页、429 记冷却期，然后把可读的错误抛出去。
 * <p>
 * 三件事收在一处，是为了让每个调用点都只写一行 —— 少一处遗漏，
 * 就少一次"某个接口撞了限流却没进冷却倒计时"的不一致。
 *
 * @param anonymous 匿名接口（登录/注册）：401 是"密码错了"，不该当成掉登录态
 */
async function throwHttpError(res: Response, anonymous = false): Promise<never> {
  if (res.status === 401 && !anonymous) handleUnauthorized()

  const detail = await readError(res)
  if (res.status === 429) noteRateLimit(detail.retryAfter)

  throw new Error(`请求失败(${res.status})：${detail.message}`)
}

/**
 * 登录失效的统一处理：清掉本地凭证，把人送回登录页。
 * <p>
 * 带上 `redirect` 是为了登录成功后能回到原来那一页 —— 用户正在写第三次提问时
 * 被踢去登录，登完发现回到首页、内容全没了，是很糟的体验。
 */
function handleUnauthorized(): void {
  clearSession()
  const current = router.currentRoute.value
  if (current.name === 'login') return
  router.replace({
    name: 'login',
    query: current.fullPath === '/' ? {} : { redirect: current.fullPath },
  })
}

function authHeaders(): Record<string, string> {
  const token = getToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function request<T>(
  path: string,
  params: Record<string, string | undefined> = {},
  options: RequestOptions = {}
): Promise<T> {
  const headers: Record<string, string> = options.anonymous ? {} : authHeaders()
  let body: string | undefined
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(options.body)
  }

  const res = await fetch(buildUrl(path, params), {
    method: options.method ?? 'GET',
    headers,
    body,
  })

  if (!res.ok) {
    await throwHttpError(res, options.anonymous)
  }

  const contentType = res.headers.get('content-type') ?? ''
  // 后端可能直接返回纯文本（比如 Agent 的执行结果）
  return (contentType.includes('application/json') ? await res.json() : await res.text()) as T
}

/**
 * 解析一个 SSE 事件块（已按空行切开）。
 * <p>
 * 这里刻意写得比规范宽松：服务端用的是 Spring 的 {@code SseEmitter}，
 * 它发送多行字符串时**只会给第一行加 `data:` 前缀**，后续行是裸文本，例如：
 * <pre>
 *   data:第一行
 *   第二行
 *   第三行
 * </pre>
 * 如果按标准 SSE 只取 `data:` 行，长报告会被截断成第一行 —— Agent 几千字的
 * Markdown 结论会整段丢失。所以这里把「没有字段名的裸行」当作上一行数据的续行。
 */
function parseSseEvent(block: string): string {
  const parts: string[] = []
  for (const line of block.split(/\r?\n/)) {
    if (!line) continue
    // 以冒号开头的是注释（常用于心跳），直接忽略
    if (line.startsWith(':')) continue

    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)

    if (field === 'data') {
      parts.push(value)
    } else if (field === 'event' || field === 'id' || field === 'retry') {
      // 后端暂时没用到这些字段，忽略即可
      continue
    } else {
      // 没有字段名的裸行：视为上一行 data 的续行（兼容 SseEmitter 的行为）
      parts.push(line)
    }
  }
  return parts.join('\n')
}

/**
 * 消费 SSE 流。
 * <p>
 * 这里刻意不用浏览器的 EventSource：它只支持 GET、**不能自定义请求头**（也就带不了
 * Authorization）、出错时会自动重连（流正常结束时也会触发 error，很难区分"结束"和"失败"）。
 * 直接用 fetch + ReadableStream 读原始字节自己解析，既拿得到明确的流结束信号，
 * 也能通过 AbortSignal 实现「停止生成」。
 */
export async function streamSse(
  path: string,
  params: Record<string, string | undefined>,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch(buildUrl(path, params), { headers: authHeaders(), signal })
  if (!res.ok) {
    // 限流、参数错误、未登录等都会走到这里 —— 要把后端的中文提示透出来，而不是只报一个状态码
    await throwHttpError(res)
  }
  if (!res.body) {
    throw new Error('流式请求失败：响应没有可读的流')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行分隔事件；最后一段可能不完整，留在 buffer 里等下一批
      const events = buffer.split(/\r?\n\r?\n/)
      buffer = events.pop() ?? ''

      for (const event of events) {
        const data = parseSseEvent(event)
        if (data) onChunk(data)
      }
    }
    // 收尾：流可能在最后一个事件后没有补空行，别把剩下的内容丢掉
    if (buffer.trim()) {
      const data = parseSseEvent(buffer)
      if (data) onChunk(data)
    }
  } finally {
    reader.cancel().catch(() => undefined)
  }
}

/* ---------------- 登录鉴权 ---------------- */

/**
 * 注册。
 * <p>
 * `anonymous: true` —— 这两个接口本来就是给"还没有身份"的人用的，
 * 带上旧 token 没意义，401 也不该触发跳转。
 */
export const register = (username: string, password: string, nickname?: string) =>
  request<AuthResult>(
    '/auth/register',
    {},
    {
      method: 'POST',
      body: { username, password, nickname: nickname || undefined },
      anonymous: true,
    }
  )

export const login = (username: string, password: string) =>
  request<AuthResult>(
    '/auth/login',
    {},
    {
      method: 'POST',
      body: { username, password },
      anonymous: true,
    }
  )

export const logout = () => request<{ success: boolean }>('/auth/logout', {}, { method: 'POST' })

/** 用本地 token 换当前用户信息：刷新页面后恢复用户显示，同时验证 token 是否还有效 */
export const me = () => request<SessionUser>('/auth/me')

/* ---------------- 对话 ---------------- */

export const chatSync = (message: string, chatId: string) =>
  request<string>('/ai/job/chat/sync', { message, chatId })

export const chatRag = (message: string, chatId: string) =>
  request<string>('/ai/job/chat/rag', { message, chatId })

export const chatStream = (
  message: string,
  chatId: string,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
) => streamSse('/ai/job/chat/sse', { message, chatId }, onChunk, signal)

export const chatRagStream = (
  message: string,
  chatId: string,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
) => streamSse('/ai/job/chat/rag/sse', { message, chatId }, onChunk, signal)

/* ---------------- 求职规划 Agent ---------------- */

export const agentRun = (message: string) => request<string>('/ai/agent/run', { message })

export const agentRunStream = (
  message: string,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
) => streamSse('/ai/agent/run/sse', { message }, onChunk, signal)

/* ---------------- 会话档案 ---------------- */

export const listConversations = () => request<Conversation[]>('/ai/conversation/list')

export const getConversationMessages = (id: string) =>
  request<ChatMessage[]>(`/ai/conversation/${encodeURIComponent(id)}/messages`)

export const deleteConversation = (id: string) =>
  request<boolean>(`/ai/conversation/${encodeURIComponent(id)}`, {}, { method: 'DELETE' })

/* ---------------- 简历诊断 ---------------- */

/**
 * 上传简历（PDF 文件或粘贴文本）并拿到结构化诊断。
 * <p>
 * 这里不走上面的 {@link request}，是因为它只能序列化 JSON body；
 * 简历上传是 {@code multipart/form-data}，必须用 FormData 交给 fetch 自己设
 * Content-Type（浏览器会自动带上 boundary 分隔符，手动设反而会漏掉）。
 */
export async function analyzeResume(
  input: {
    file?: File | null
    text?: string
    targetJob?: string
  },
  /** 用于「停止」：诊断是最慢的动作之一，必须给用户一个喊停的口子 */
  signal?: AbortSignal
): Promise<ResumeDiagnosis> {
  const form = new FormData()
  if (input.file) form.append('file', input.file)
  if (input.text) form.append('text', input.text)
  if (input.targetJob) form.append('targetJob', input.targetJob)

  const res = await fetch(`${BASE}/ai/resume/analyze`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
    signal,
  })

  if (!res.ok) {
    await throwHttpError(res)
  }
  return (await res.json()) as ResumeDiagnosis
}

/* ---------------- 岗位匹配 ---------------- */

/**
 * 上传简历（PDF 文件或粘贴文本）+ 岗位 JD，拿到结构化匹配报告。
 * 同样走 multipart/form-data，理由同 {@link analyzeResume}。
 */
export async function analyzeMatch(
  input: {
    file?: File | null
    text?: string
    jd: string
  },
  /** 用于「停止」，理由同 {@link analyzeResume} */
  signal?: AbortSignal
): Promise<JobMatch> {
  const form = new FormData()
  if (input.file) form.append('file', input.file)
  if (input.text) form.append('text', input.text)
  form.append('jd', input.jd)

  const res = await fetch(`${BASE}/ai/match/analyze`, {
    method: 'POST',
    headers: authHeaders(),
    body: form,
    signal,
  })

  if (!res.ok) {
    await throwHttpError(res)
  }
  return (await res.json()) as JobMatch
}

/* ---------------- 模拟面试 ---------------- */

/** 面试类型：技术面 / HR 面 / 综合面 */
export type InterviewType = 'tech' | 'hr' | 'mixed'

/** 面试开场返回（和后端 InterviewStart record 一一对应） */
export interface InterviewStart {
  sessionId: string
  opening: string
}

/**
 * 复盘结论三态（和后端 `Verdict` 枚举一一对应）。
 * <p>
 * 以前这里是 `string`，值就是模型随手写的中文，前端只能靠 `includes('通过')` 猜配色 ——
 * 猜错的后果是「没过」显示成「待定」。现在后端收敛成枚举，配色判断是 `switch`，不再猜。
 */
export type InterviewVerdict = 'PASS' | 'PENDING' | 'REJECT'

/** 面试复盘报告（和后端 InterviewReport record 一一对应） */
export interface InterviewReport {
  overallScore: number
  verdict: InterviewVerdict
  summary: string
  dimensions: { name: string; score: number; comment: string }[]
  highlights: { question: string; comment: string }[]
  issues: { question: string; comment: string }[]
  suggestions: { priority: 'high' | 'medium' | 'low'; problem: string; fix: string }[]
}

/**
 * 开始一场面试：后端会生成绑定当前用户的 sessionId，并返回面试官的开场白。
 * <p>
 * 参数走 query string（后端用的是 {@code @RequestParam}），不传 body ——
 * 所以复用通用 {@link request} 即可，注意方法要显式指定 POST。
 */
export const startInterview = (role: string, type: InterviewType) =>
  request<InterviewStart>('/ai/interview/start', { role, type }, { method: 'POST' })

/**
 * 回答一轮，面试官追问（流式）。
 * <p>
 * sessionId 必须来自 {@link startInterview} —— 后端会校验它的前缀是否属于当前用户，
 * 拿别人的会话 ID 会直接 403。
 */
export const interviewStream = (
  sessionId: string,
  message: string,
  role: string,
  type: InterviewType,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
) => streamSse('/ai/interview/chat/sse', { sessionId, message, role, type }, onChunk, signal)

/** 结束面试并生成结构化复盘。后端从记忆里取整场记录，前端不需要回放对话。 */
export const reportInterview = (sessionId: string, role: string, type: InterviewType) =>
  request<InterviewReport>('/ai/interview/report', { sessionId, role, type }, { method: 'POST' })
