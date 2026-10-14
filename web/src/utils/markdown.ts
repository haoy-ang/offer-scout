import MarkdownIt from 'markdown-it'

/**
 * Markdown 渲染器。
 * <p>
 * 模型输出的是 Markdown（标题、表格、列表），直接塞进 DOM 就是一堆 `**` 和 `|`。
 * 这里关掉 `html` 选项：markdown-it 会把原始 HTML 转义掉，
 * 于是即使模型输出里混进了 `<script>` 也不会被执行 —— 用 v-html 才安全。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
})

export function renderMarkdown(text: string | null | undefined): string {
  if (!text) return ''
  return md.render(text)
}

/** 复制到剪贴板，兼容非 HTTPS 环境（localhost 之外 navigator.clipboard 可能不可用） */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 毫秒时间戳 → HH:mm:ss */
export function hhmmss(date: Date = new Date()): string {
  return date.toLocaleTimeString('zh-CN', { hour12: false })
}

/** 生成一个前端会话 ID（与后端 UUID 风格保持一致） */
export function newChatId(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `web-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}
