import { ref } from 'vue'
import { analyzeResume, type ResumeDiagnosis } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 「简历诊断」的会话状态。
 * <p>
 * 提到**模块作用域**的理由和 `stores/chat.ts` / `stores/agent.ts` 一致：
 * 诊断一次要等大模型十几秒，用户中途去别页看一眼，回来报告就没了 —— 只能重跑一遍，
 * 既浪费时间又白花一次调用的钱。状态放模块级，切页只重建视图，结果还在。
 * <p>
 * 另有两点是跟 `stores/agent.ts` 对齐后补上的，都是踩过的坑：
 * <ol>
 *   <li><b>能停</b>：请求持有 {@code AbortController}。这是全站最慢的两个动作之一
 *       （结构化输出不完整会重试，最坏要等一两分钟），原先没有停止口子，
 *       用户只能刷新页面 —— 而刷新会把精心粘进来的材料一起清掉。</li>
 *   <li><b>{@link resetResume} 会停计时器、清 busy、中止在途请求</b>。
 *       只清数据不清这三样，登出时会留下一个永远空转的 interval；
 *       更糟的是在途请求返回时会把刚清掉的 error 又写回去 ——
 *       下一个账号登进来，就看到一条属于上一个账号的报错。</li>
 * </ol>
 */

const file = ref<File | null>(null)
const pastedText = ref('')
const targetJob = ref('')
const busy = ref(false)
const result = ref<ResumeDiagnosis | null>(null)
const error = ref('')
/** 用户主动停止 —— 它不是失败，别用「诊断失败」的框去套 */
const stopped = ref(false)
const elapsed = ref(0)
const reportTime = ref('')

let ticker: number | undefined
/** 这一次请求的瞬时控制量 */
let controller: AbortController | null = null

/** 取状态：返回同一组 ref 本身，改一处、处处可见 */
export function useResumeSession() {
  return { file, pastedText, targetJob, busy, result, error, stopped, elapsed, reportTime }
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

/** 跑一次诊断。输入为空时把提示写进 error，由视图统一展示。 */
export async function runResume(): Promise<void> {
  if (busy.value) return
  if (!file.value && !pastedText.value.trim()) {
    error.value = '请先上传一份 PDF 简历，或直接粘贴简历文本。'
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
    result.value = await analyzeResume(
      {
        file: file.value,
        text: pastedText.value.trim() || undefined,
        targetJob: targetJob.value.trim() || undefined,
      },
      controller.signal
    )
    reportTime.value = hhmmss()
  } catch (e) {
    // 用户点「停止」不算失败：材料原样留着，只说一句人话，
    // 不要把主动中止渲染成「诊断失败」的红色警示框。
    if ((e as Error)?.name === 'AbortError') {
      stopped.value = true
    } else {
      error.value = (e as Error).message || '诊断失败，请稍后重试。'
    }
  } finally {
    busy.value = false
    controller = null
    stopTicker()
  }
}

/** 用户主动停止：中止这次请求，已输入的材料保留 */
export function stopResume(): void {
  controller?.abort()
  controller = null
  busy.value = false
  stopTicker()
}

/** 显式清空（视图上的「清空」按钮 / 登出时调用） */
export function resetResume(): void {
  // 不做 busy 拦截：调用方（清空按钮）自己会禁用，而登出时必须能强制清干净 ——
  // 否则换个账号登进来，上一任用户的诊断报告还挂在页面上。
  // 但"这一次请求的痕迹"必须一起清：在途请求、计时器、busy。
  controller?.abort()
  controller = null
  stopTicker()

  file.value = null
  pastedText.value = ''
  targetJob.value = ''
  result.value = null
  error.value = ''
  stopped.value = false
  reportTime.value = ''
  elapsed.value = 0
  busy.value = false
}
