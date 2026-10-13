/**
 * 后端接口封装。
 * <p>
 * 所有请求都走相对路径 `/api`：
 * 开发期由 Vite 代理到 localhost:8124，生产期交给 Nginx 反代。
 * 业务代码里不出现任何 host，换环境不用改一行。
 */

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

function buildUrl(path: string, params: Record<string, string | undefined> = {}): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== '') search.set(key, value)
  })
  const qs = search.toString()
  return qs ? `${BASE}${path}?${qs}` : `${BASE}${path}`
}

async function request<T>(path: string, params: Record<string, string | undefined> = {}): Promise<T> {
  const res = await fetch(buildUrl(path, params))
  if (!res.ok) {
    throw new Error(`请求失败 ${res.status}：${await res.text().catch(() => res.statusText)}`)
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
 * 这里刻意不用浏览器的 EventSource：它只支持 GET、不能自定义请求头、
 * 出错时会自动重连（流正常结束时也会触发 error，很难区分「结束」和「失败」）。
 * 直接用 fetch + ReadableStream 读原始字节自己解析，能拿到明确的流结束信号，
 * 也能通过 AbortSignal 实现「停止生成」。
 */
export async function streamSse(
  path: string,
  params: Record<string, string | undefined>,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal
): Promise<void> {
  const res = await fetch(buildUrl(path, params), { signal })
  if (!res.ok || !res.body) {
    throw new Error(`流式请求失败 ${res.status}`)
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

export async function deleteConversation(id: string): Promise<boolean> {
  const res = await fetch(`${BASE}/ai/conversation/${encodeURIComponent(id)}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`删除失败 ${res.status}`)
  return (await res.json()) as boolean
}
