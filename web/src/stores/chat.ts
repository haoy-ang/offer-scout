import { ref } from 'vue'
import { chatRagStream, chatStream } from '@/api/client'
import { hhmmss, newChatId } from '@/utils/markdown'

/** 一轮对话（user 提问 / assistant 回答） */
export interface Turn {
  role: 'user' | 'assistant'
  text: string
  time: string
  error?: boolean
}

/**
 * 对话会话状态。
 * <p>
 * 刻意放在**模块作用域**，而不是 ChatView 的组件实例里。
 * <p>
 * 原因是路由切换会让 `<RouterView>` 里的组件重新挂载 —— 只要状态还活在组件内，
 * 用户切到「简历诊断」再切回来，之前聊的就全没了。这在实际使用里很烦人：
 * 正对照着 AI 的建议改简历，回来对话已经清空，还得重新问一遍。
 * <p>
 * 把状态提到模块级 ref，就切断了「组件重挂 = 状态清零」这层绑定：
 * 重挂只重建视图，历史仍在这里。真想清空，只能显式调 {@link resetChatSession}。
 * <p>
 * <b>「跑一次提问」的逻辑也放在这里，连 {@code busy} 和 AbortController 一起。</b>
 * 两者必须同进同退：如果只把 turns 提上来、busy 留在组件里，那么切页回来后
 * busy 已经是 false（组件重挂了）而流其实还在跑 —— 用户能再发一条，
 * 于是两条流同时往同一份历史里追加。所以这里照 `stores/agent.ts` 的做法，
 * 把整个"这一次请求"的状态都收进 store。
 * <p>
 * 顺带的直接收益：**切页不会再掐断正在生成的回答**。
 * 以前的 ChatView 在卸载时会 abort，用户问完一句、回答还在打字时切去别页，
 * 回来只剩半截 —— 而作战规划页切走是照跑不误的，两边行为自相矛盾。
 * <p>
 * 为什么不引 Pinia？和 `stores/session.ts` 同一个理由：需要跨组件共享的就这几个 ref，
 * 再套一层状态库只换来一个依赖和一堆样板。等出现「多模块互相改同一份状态 + 需要
 * 时间旅行调试」再上不迟。
 */
const chatId = ref(newChatId())
const turns = ref<Turn[]>([])
const draft = ref('')
const busy = ref(false)

/** 这一次请求的瞬时控制量：活不到跨渲染就重建，所以不进对外的接口 */
let controller: AbortController | null = null

/**
 * 取会话状态。
 * <p>
 * 返回的是**同一组 ref 本身**，不是副本 —— 所以任何调用点改它，别处都能看到，
 * 这正是"会话能在组件之间存活"的实现方式。
 */
export function useChatSession() {
  return { chatId, turns, draft, busy }
}

/**
 * 发一条消息并流式接收回答。
 *
 * @param useKb 走不走知识库（对应 URL 上的 ?kb=1）。由视图传进来，
 *              而不是在 store 里读路由 —— 状态模块不该反过来依赖路由。
 */
export async function sendChat(useKb: boolean): Promise<void> {
  const text = draft.value.trim()
  if (!text || busy.value) return

  turns.value.push({ role: 'user', text, time: hhmmss() })
  draft.value = ''

  turns.value.push({ role: 'assistant', text: '', time: hhmmss() })
  const reply = turns.value[turns.value.length - 1]
  busy.value = true
  controller = new AbortController()

  try {
    const stream = useKb ? chatRagStream : chatStream
    await stream(text, chatId.value, (chunk) => (reply.text += chunk), controller.signal)
  } catch (e) {
    // 用户主动停止不算错误，保留已生成的内容即可
    if ((e as Error)?.name !== 'AbortError') {
      reply.error = true
      reply.text =
        reply.text ||
        `请求失败：${(e as Error).message}\n\n检查后端是否已启动（默认 http://localhost:8124）。`
    }
  } finally {
    busy.value = false
    controller = null
  }
}

/** 用户主动停止：保留已生成的内容 */
export function stopChat(): void {
  controller?.abort()
  controller = null
  busy.value = false
}

/** 新建会话：中止在途请求、换一个 chatId、清空历史与草稿 */
export function resetChatSession(): void {
  controller?.abort()
  controller = null
  busy.value = false
  chatId.value = newChatId()
  turns.value = []
  draft.value = ''
}

/**
 * 载入一条已有会话（「继续对话」用）。
 * <p>
 * 关键是**沿用原来的 chatId**：后端 ChatMemory 按 chatId 存记忆，
 * 只要接着用同一个 ID 发消息，就能无缝续聊（这就是「继续对话」能成立的原因）。
 */
export function loadChatSession(id: string, history: Turn[]): void {
  chatId.value = id
  turns.value = history
  draft.value = ''
}
