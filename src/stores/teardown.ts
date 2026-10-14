import { resetAgent } from '@/stores/agent'
import { resetChatSession } from '@/stores/chat'
import { resetMatch } from '@/stores/match'
import { resetResume } from '@/stores/resume'
import { restartInterview } from '@/stores/interview'
import { clearCooldown } from '@/stores/cooldown'

/**
 * 会话收尾：把所有「属于当前登录用户」的业务状态清干净。
 * <p>
 * <b>为什么要有这个文件？</b>因为「切页不丢状态」的代价是状态活在模块作用域里 ——
 * 它不认识用户，谁登进来都看得见。所以登出/掉登录态时必须显式清掉，
 * 否则换个账号登进来，上一任用户的作战报告、简历诊断结果、对话历史全都还挂在页面上。
 * <p>
 * <b>它解决的是"清理漏了一条路"这个真发生过的问题：</b>
 * 原先这 5 个 reset 是写在 {@code App.vue} 的「登出」按钮处理函数里的。
 * 后来发现 token 过期 / 被服务端拉黑时走的是另一条路（{@code api/client.ts} 的 401 处理），
 * 那条路上没有人调用它们 —— 于是「点登出」干净了，「被动掉线」却会残留。
 * <p>
 * 现在的做法是把它收敛成一个入口，并且挂到**登录态本身**上
 * （{@code App.vue} 里 watch {@code isLoggedIn} 变为 false）：无论是点登出、
 * token 过期、还是将来新增的任何一种掉线方式，都只有这一个出口，不会再漏。
 * <p>
 * <b>为什么不放在 stores/session.ts 里？</b>那会形成循环依赖 ——
 * 这几个 store 都要 import {@code api/client}，而 {@code api/client} 要 import
 * {@code stores/session} 去读写 token。收尾逻辑单独一层，谁也不反过来依赖它，链路是单向的。
 * <p>
 * 幂等：每个 reset 都可以重复调用（agent / interview 自己会先中止在途请求），
 * 所以「登出时显式调一次 + watcher 再调一次」也不会出问题。
 */
export function clearAllSessions(): void {
  resetAgent()
  resetChatSession()
  resetResume()
  resetMatch()
  restartInterview()
  // 冷却期也跟登录态走：换个账号登进来，不该继承上一个账号撞出来的倒计时
  clearCooldown()
}
