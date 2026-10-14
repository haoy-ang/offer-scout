import { computed, ref } from 'vue'

/**
 * 限流冷却期。
 * <p>
 * 后端撞到 {@code @RateLimit} 时返回 429，响应体里带着 {@code retryAfter}（还秒数），
 * 响应头里也有 {@code Retry-After}。但前端过去只从响应体里取了 {@code message}，
 * 把这个数字丢掉了 —— 于是用户只看到「请稍后再试」，不知道要等多久，只能盲点，
 * 点一次撞一次。
 * <p>
 * 这里把秒数记下来，让界面能显示「N 秒后可重试」并把按钮真的禁住。
 * <p>
 * <b>为什么单独一个模块、而不是塞进某个 store？</b>限流是按「接口 + IP」算的，
 * 跟具体哪个功能页无关 —— 用户在这一页撞了限流，切到另一页也还是在冷却期内。
 * 放在这里共享一份，几个页面才能读到同一个倒计时。
 * <p>
 * 它只 import vue，不 import 任何业务模块，所以谁都可以安全地引用它。
 */

/** 冷却截止时间戳（毫秒）；0 表示不在冷却中 */
const until = ref(0)

/** 「现在」的快照。让它成为响应式依赖，倒计时才能在界面上自己走 */
const now = ref(Date.now())

let ticker: number | undefined

function stopTicker(): void {
  if (ticker !== undefined) {
    window.clearInterval(ticker)
    ticker = undefined
  }
}

function ensureTicker(): void {
  if (ticker !== undefined) return
  ticker = window.setInterval(() => {
    now.value = Date.now()
    // 冷却结束就自己把定时器停掉，别留一个永远空转的 interval
    if (until.value <= now.value) stopTicker()
  }, 500)
}

/** 剩余冷却秒数（向上取整）。0 = 没在冷却 */
export const cooldownSeconds = computed(() => {
  const left = until.value - now.value
  return left > 0 ? Math.ceil(left / 1000) : 0
})

/**
 * 记一次限流：从现在起 {@code seconds} 秒内视为冷却中。
 * <p>
 * 拿到非法值时兜底成 5 秒 —— 宁可让用户多等 5 秒，也别因为后端漏给字段就退化成「不限流」。
 */
export function noteRateLimit(seconds: number): void {
  const safe = Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds) : 5
  const base = Date.now()
  until.value = base + safe * 1000
  now.value = base
  ensureTicker()
}

/** 主动清掉冷却（登出时用；正常到期由 ticker 自己收尾） */
export function clearCooldown(): void {
  until.value = 0
  stopTicker()
}
