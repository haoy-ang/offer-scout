import { ref } from 'vue'
import { analyzeMatch, type JobMatch } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 「岗位匹配」的会话状态。
 * <p>
 * 和简历诊断同构：一次匹配 = 简历 + JD 全文的一次完整分析，等待久、成本高。
 * 状态在组件内的话，切页即清零 —— 用户精心粘进来的 JD 和刚出的报告一起消失。
 * 所以照 `stores/resume.ts` 的做法提到模块作用域，连同「能停」「reset 清干净」
 * 这两个细节也保持一致（见 `stores/resume.ts` 的说明）。
 */

const file = ref<File | null>(null)
const resumeText = ref('')
const jd = ref('')
const busy = ref(false)
const result = ref<JobMatch | null>(null)
const error = ref('')
/** 用户主动停止 —— 它不是失败，别用「匹配失败」的框去套 */
const stopped = ref(false)
const elapsed = ref(0)
const reportTime = ref('')

let ticker: number | undefined
/** 这一次请求的瞬时控制量 */
let controller: AbortController | null = null

/** 取状态：返回同一组 ref 本身，改一处、处处可见 */
export function useMatchSession() {
  return { file, resumeText, jd, busy, result, error, stopped, elapsed, reportTime }
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

/** 跑一次匹配。缺简历或 JD 时把提示写进 error，由视图统一展示。 */
export async function runMatch(): Promise<void> {
  if (busy.value) return
  if (!file.value && !resumeText.value.trim()) {
    error.value = '请先上传一份 PDF 简历，或直接粘贴简历文本。'
    return
  }
  if (!jd.value.trim()) {
    error.value = '请粘贴目标岗位的 JD（职位描述）。'
    return
  }

  busy.value = true
  result.value = null
  error.value = ''
  stopped.value = false
  reportTime.value = ''
  startTicker()
  controller = new AbortController()

  try {
    result.value = await analyzeMatch(
      {
        file: file.value,
        text: resumeText.value.trim() || undefined,
        jd: jd.value.trim(),
      },
      controller.signal
    )
    reportTime.value = hhmmss()
  } catch (e) {
    // 主动停止 ≠ 失败：材料保留，别摆红色警示框
    if ((e as Error)?.name === 'AbortError') {
      stopped.value = true
    } else {
      error.value = (e as Error).message || '匹配失败，请稍后重试。'
    }
  } finally {
    busy.value = false
    controller = null
    stopTicker()
  }
}

/** 用户主动停止：中止这次请求，已输入的材料保留 */
export function stopMatch(): void {
  controller?.abort()
  controller = null
  busy.value = false
  stopTicker()
}

/** 显式清空（视图上的「清空」按钮 / 登出时调用） */
export function resetMatch(): void {
  // 同 resetResume：不拦 busy，保证登出时能强制清干净；
  // 但也必须把在途请求、计时器、busy 一起清掉，否则会留下孤儿定时器和无主的报错回写。
  controller?.abort()
  controller = null
  stopTicker()

  file.value = null
  resumeText.value = ''
  jd.value = ''
  result.value = null
  error.value = ''
  stopped.value = false
  reportTime.value = ''
  elapsed.value = 0
  busy.value = false
}
