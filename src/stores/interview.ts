import { ref } from 'vue'
import {
  interviewStream,
  reportInterview,
  startInterview,
  type InterviewReport,
  type InterviewType,
} from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 「模拟面试」的会话状态。
 * <p>
 * 这是全站唯一一个「有状态的三段式」页面，用 phase 显式建模：
 *   setup → interview → report
 * 三段之间靠内存里的 sessionId 串起来。
 * <p>
 * <b>状态为什么放在模块作用域？</b>
 * 一场面试是"多条问题 + 多轮回答 + 一份复盘"，是这几个页里最不该被一次误点毁掉的。
 * 如果 phase / turns / report 活在组件内，用户稍微切页看一眼就重来 ——
 * 已经答到第 5 题也得从头开始。提到模块级后，切页只重建视图，phase、已答轮次、
 * 复盘全都还在，回来接着答即可。
 * <p>
 * <b>边界不变</b>：模块级状态只活在"当前这次页面加载"里，按 F5 刷新依然等于重开一场面试 ——
 * 这是刻意的（面试会话不该跨刷新续上，而且后端记忆是 50 条滑动窗口）。
 */

export interface Turn {
  role: 'user' | 'assistant'
  text: string
  time: string
  error?: boolean
}

export type Phase = 'setup' | 'interview' | 'report'

const phase = ref<Phase>('setup')
const role = ref('Java 后端开发实习生')
const type = ref<InterviewType>('tech')

const sessionId = ref('')
const turns = ref<Turn[]>([])
const draft = ref('')
const busy = ref(false)

const report = ref<InterviewReport | null>(null)
const error = ref('')

/** 这一次请求的瞬时控制量 */
let controller: AbortController | null = null

/** 取状态：返回同一组 ref 本身，改一处、处处可见 */
export function useInterviewSession() {
  return { phase, role, type, sessionId, turns, draft, busy, report, error }
}

/** 候选人真正回答的轮数 —— 复盘要有依据，判据就在这里（assistant 的开场白不算回答） */
export function answeredTurns(): number {
  return turns.value.filter((t) => t.role === 'user').length
}

/* ---------------- 开始面试 ---------------- */

export async function beginInterview(): Promise<void> {
  if (busy.value || !role.value.trim()) return
  busy.value = true
  error.value = ''
  turns.value = []
  report.value = null

  try {
    const res = await startInterview(role.value.trim(), type.value)
    sessionId.value = res.sessionId
    turns.value.push({ role: 'assistant', text: res.opening, time: hhmmss() })
    phase.value = 'interview'
  } catch (e) {
    error.value = (e as Error).message || '面试开启失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

/* ---------------- 逐轮回答 ---------------- */

export async function sendAnswer(): Promise<void> {
  const text = draft.value.trim()
  if (!text || busy.value || phase.value !== 'interview') return

  turns.value.push({ role: 'user', text, time: hhmmss() })
  draft.value = ''

  turns.value.push({ role: 'assistant', text: '', time: hhmmss() })
  const reply = turns.value[turns.value.length - 1]
  busy.value = true
  controller = new AbortController()

  const onChunk = (chunk: string) => {
    reply.text += chunk
  }

  try {
    await interviewStream(
      sessionId.value,
      text,
      role.value.trim(),
      type.value,
      onChunk,
      controller.signal
    )
  } catch (e) {
    if ((e as Error)?.name !== 'AbortError') {
      reply.error = true
      reply.text = reply.text || `请求失败：${(e as Error).message}`
    }
  } finally {
    busy.value = false
    controller = null
  }
}

export function stopAnswer(): void {
  controller?.abort()
  busy.value = false
}

/* ---------------- 结束并复盘 ---------------- */

export async function finishInterview(): Promise<void> {
  if (busy.value || phase.value !== 'interview') return
  if (answeredTurns() === 0) {
    error.value = '至少回答一轮，才能生成有依据的复盘。'
    return
  }
  busy.value = true
  error.value = ''

  try {
    report.value = await reportInterview(sessionId.value, role.value.trim(), type.value)
    phase.value = 'report'
  } catch (e) {
    error.value = (e as Error).message || '复盘生成失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

/** 复盘页切回对话视图（不改变已有记录） */
export function backToInterview(): void {
  phase.value = 'interview'
}

/** 显式重开一场（视图上的「重新开始」按钮 / 登出时调用） */
export function restartInterview(): void {
  controller?.abort()
  controller = null
  phase.value = 'setup'
  sessionId.value = ''
  turns.value = []
  draft.value = ''
  report.value = null
  error.value = ''
  busy.value = false
}
