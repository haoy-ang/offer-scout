import { ref } from 'vue'
import { agentRunStream } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 「作战规划」Agent 的会话状态。
 * <p>
 * 和 `stores/chat.ts` 一样刻意放在**模块作用域**，而不是 AgentView 的组件实例里。
 * <p>
 * AgentView 是整个应用里"最贵"的一页：一次任务要跑 3~8 步、几十秒。
 * 如果状态活在组件内，用户中途切去别处看一眼（哪怕只是想对照一下简历页），
 * 回来就是一片空白 + 一次白烧的 token —— 结果没了，钱也花了。
 * 把状态和「跑一次任务」的逻辑一起提到模块级，就切断了「组件重挂 = 状态清零」这层绑定。
 * <p>
 * 同理，这里**不在路由离开时中止请求**：任务该跑完就跑完，回来还能看到结果
 * （见 AgentView 里已移除的 onBeforeUnmount(abort)）。
 * <p>
 * 还有一层保护：**最近一次结果会落到 localStorage**。
 * 模块级状态扛得住切页，扛不住按 F5 —— 而一次 Agent 任务要跑几十秒、
 * 花掉相当于十几次普通对话的额度，手滑刷新一下就没了，太亏。
 * 注意这份缓存是"浏览器级"的，所以 {@link resetAgent} 会把它清掉（登出时就会走到）。
 * <p>
 * 为什么不引 Pinia？见 `stores/session.ts` 与 `stores/chat.ts` 的说明：
 * 需要共享的就这几个 ref，套状态库只换来依赖与样板。
 */

/**
 * 后端每步推来的是形如 `第 3 步｜工具 xxx 返回：…` 的一行文本；
 * 最终结论则是单独一次推送的整段 Markdown。
 * 于是「能匹配这个正则的 = 过程轨迹，匹配不上的 = 最终报告」，就此分开。
 */
const STEP_RE = /^第\s*(\d+)\s*步\s*[｜|]\s*([\s\S]*)$/

export interface Trace {
  index: number
  kind: 'think' | 'tool'
  text: string
}

const draft = ref('')
const task = ref('')
const busy = ref(false)
const traces = ref<Trace[]>([])
const report = ref('')
const reportTime = ref('')
const error = ref('')
const elapsed = ref(0)

/**
 * 上一份结果是从缓存里恢复的，会带上保存时间（形如 `10-13 14:32`）。
 * <p>
 * 有这个标记，界面才能把「我刚跑出来的」和「昨天跑完的」区分开 ——
 * 否则用户打开页面看到一份报告，会以为是这次跑出来的。
 */
const restoredAt = ref('')

/** 这一次请求的瞬时控制量：活不到跨渲染就重建，所以不进 store 的对外接口 */
let controller: AbortController | null = null
let ticker: number | undefined

/** 缓存 key。带着 offerscout. 前缀，跟 token 用同一套命名空间 */
const STORAGE_KEY = 'offerscout.agent.last'

/** 缓存里放的字段。刻意只放结果，不放 draft / busy 这些"正在进行中"的状态 */
interface PersistedRun {
  task: string
  traces: Trace[]
  report: string
  reportTime: string
  error: string
  /** 保存时刻（形如 10-13 14:32），用来在界面上标注"这是历史结果" */
  savedAt: string
}

/** MM-DD HH:mm，够看清"多久之前"又不啰嗦 */
function stamp(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 把当前结果写进缓存。
 * <p>
 * <b>读写都要 try-catch</b>：无痕模式、被浏览器策略禁用、配额写满时 localStorage 会直接抛。
 * 缓存是锦上添花，绝不能因为它失败把整个任务流程带崩。
 */
function persist(): void {
  try {
    // 什么都没有就别占着 key（比如清空后再刷新）
    if (!report.value && !error.value && !task.value) {
      localStorage.removeItem(STORAGE_KEY)
      return
    }
    const payload: PersistedRun = {
      task: task.value,
      traces: traces.value,
      report: report.value,
      reportTime: reportTime.value,
      error: error.value,
      savedAt: stamp(),
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(payload))
  } catch {
    // 存不下就算了，功能照常
  }
}

/** 恢复上次结果。字段逐个校验，缓存被改坏时当作没有 */
function restore(): void {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return
    const parsed = JSON.parse(raw) as Partial<PersistedRun>
    if (!parsed || typeof parsed.report !== 'string') return

    task.value = typeof parsed.task === 'string' ? parsed.task : ''
    report.value = parsed.report
    reportTime.value = typeof parsed.reportTime === 'string' ? parsed.reportTime : ''
    error.value = typeof parsed.error === 'string' ? parsed.error : ''
    traces.value = Array.isArray(parsed.traces) ? parsed.traces : []
    restoredAt.value = typeof parsed.savedAt === 'string' ? parsed.savedAt : ''
  } catch {
    // 缓存不可用（无痕模式 / 内容损坏）就当没有，静默降级
  }
}

restore()

/**
 * 取状态。
 * <p>
 * 返回的是**同一组 ref 本身**，不是副本 —— 所以任何调用点改它、别处都看得见，
 * 这正是"结果能在路由之间存活"的实现方式。
 */
export function useAgentSession() {
  return { draft, task, busy, traces, report, reportTime, error, elapsed, restoredAt }
}

function startTicker() {
  elapsed.value = 0
  const began = Date.now()
  ticker = window.setInterval(() => {
    elapsed.value = Math.floor((Date.now() - began) / 1000)
  }, 500)
}

function stopTicker() {
  if (ticker) window.clearInterval(ticker)
  ticker = undefined
}

function onChunk(chunk: string) {
  const match = STEP_RE.exec(chunk)
  if (match) {
    const body = match[2].trim()
    traces.value.push({
      index: Number(match[1]),
      // 工具类的步骤会以「工具 xxx 返回」开头，用颜色区分开，扫一眼就知道它在「想」还是「做」
      kind: body.startsWith('工具') ? 'tool' : 'think',
      text: body || '（本步无输出）',
    })
    return
  }
  // 匹配不上步骤格式的，就是收尾那一次推送 —— 最终报告
  if (!reportTime.value) reportTime.value = hhmmss()
  report.value += chunk
}

/** 跑一次任务。重复点击/正在跑时直接忽略（判据在 store 里，视图不必再挡一遍）。 */
export async function runAgent(text: string): Promise<void> {
  const trimmed = text.trim()
  if (!trimmed || busy.value) return

  task.value = trimmed
  draft.value = ''
  traces.value = []
  report.value = ''
  reportTime.value = ''
  error.value = ''
  // 开始新任务，就不再是"历史结果"了
  restoredAt.value = ''
  busy.value = true
  controller = new AbortController()
  startTicker()

  try {
    await agentRunStream(trimmed, onChunk, controller.signal)
  } catch (e) {
    if ((e as Error)?.name !== 'AbortError') {
      error.value =
        report.value ||
        `执行失败：${(e as Error).message}\n\n检查后端是否已启动（默认 http://localhost:8124），Agent 首次运行可能要等十几秒。`
    }
  } finally {
    busy.value = false
    controller = null
    stopTicker()
    // 任务收尾（成功 / 失败 / 被停止）才落盘：跑的过程中每来一片就写一次纯属浪费
    persist()
  }
}

/** 用户主动「停止」：中止这次请求，已产出的轨迹与报告保留 */
export function stopAgent(): void {
  controller?.abort()
  busy.value = false
  stopTicker()
}

/** 显式清空（视图上的「清空」按钮 / 登出时调用） */
export function resetAgent(): void {
  if (busy.value) stopAgent()
  task.value = ''
  traces.value = []
  report.value = ''
  reportTime.value = ''
  error.value = ''
  draft.value = ''
  elapsed.value = 0
  restoredAt.value = ''
  // 登出时必须一并清掉：这份缓存里可能有别人的简历片段和求职规划
  try {
    localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 无痕模式等场景下清不掉，不影响主流程
  }
}
