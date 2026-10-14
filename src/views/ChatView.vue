<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import MessageComposer from '@/components/MessageComposer.vue'
import MessageCard from '@/components/MessageCard.vue'
import { getConversationMessages } from '@/api/client'
import {
  loadChatSession,
  resetChatSession,
  sendChat,
  stopChat,
  useChatSession,
  type Turn,
} from '@/stores/chat'
import { cooldownSeconds } from '@/stores/cooldown'

const route = useRoute()
const router = useRouter()

/**
 * 「检索知识库」开关，状态挂在 URL 的 `?kb=1` 上，而不是组件内的 ref。
 * <p>
 * 放 URL 有三个实在好处：刷新后状态还在、链接可以直接发给别人、浏览器后退能还原。
 * 更关键的是它让「通用对话」和「知识库问答」变成了**同一个路由 + 一个开关**，
 * 而不是两个页面 —— 切换开关时组件不会重挂，对话历史自然就留住了。
 */
const kb = computed(() => route.query.kb === '1')

function toggleKb() {
  // replace 而不是 push：开关来回拨不该在浏览历史里堆一串记录
  router.replace({ name: 'chat', query: kb.value ? {} : { kb: '1' } })
}

const config = computed(() =>
  kb.value
    ? {
        badge: '知识库增强',
        title: '先查资料，再开口',
        intro: '先在你的知识库里检索相关资料，再基于资料作答。',
        placeholder: '问一道面试题、某家公司的考察重点、简历某句话该怎么改…',
        submit: '检索并回答',
      }
    : {
        badge: 'AI / ASSISTANT',
        title: '先说清背景，再给建议',
        intro: '带多轮记忆的通用咨询。它会先问清你的背景，再给可执行的具体建议。',
        placeholder: '聊聊你的求职情况，或者直接抛一个具体问题…',
        submit: '发送',
      }
)

const chips = computed(() =>
  kb.value
    ? [
        'MySQL 的 MVCC 是怎么实现的？',
        '字节跳动 Java 后端实习一般考什么？',
        '项目描述怎么写才不像流水账？',
      ]
    : [
        '我是 2027 届，想找 Java 后端，现在该做什么？',
        '帮我判断一下该冲大厂还是先拿中厂保底',
        '简历上只有课程设计，怎么写才有说服力？',
      ]
)

/*
 * 会话状态来自模块级 store，而不是本组件的 ref —— 这样路由切换（组件重挂）不会丢历史。
 * 详见 @/stores/chat 的说明。
 * busy 和 AbortController 也一并交出去了：它们必须和 turns 同一个生命周期，
 * 否则切页回来 busy 是 false 而流其实还在跑，用户能再发一条、两条流同时写同一份历史。
 */
const { chatId, turns, draft, busy } = useChatSession()

/** 限流冷却剩余秒数：撞了限流就如实说还要等多久，别让用户盲点 */
const cooldown = cooldownSeconds

const listEl = ref<HTMLElement | null>(null)

async function scrollToEnd() {
  await nextTick()
  const node = listEl.value
  if (node) node.scrollTop = node.scrollHeight
}

watch(turns, scrollToEnd, { deep: true })

/**
 * 从「会话档案」点「继续对话」进来时，URL 上带 `?cid=<会话ID>`。
 * 这里把它带来的历史灌进 store，并沿用同一个 chatId 续聊。
 * <p>
 * 加 `cid === chatId.value` 这层判断，是为了避免"当前就是这条会话"时白白重新拉一遍。
 */
watch(
  () => route.query.cid,
  async (cid) => {
    if (typeof cid !== 'string' || !cid || cid === chatId.value) return
    try {
      const history = await getConversationMessages(cid)
      loadChatSession(
        cid,
        history
          .filter((m) => (m.role === 'user' || m.role === 'assistant') && (m.content ?? '').trim())
          .map((m) => ({ role: m.role as Turn['role'], text: m.content ?? '', time: '' }))
      )
      await scrollToEnd()
    } catch {
      // 拉不到历史（会话被删 / 越权）就当没带 cid，保持当前会话，不打断使用
    }
  },
  { immediate: true }
)

function reset() {
  resetChatSession()
  // 同时清掉 URL 上可能残留的 ?cid=，否则"新建会话"后地址栏还挂着旧会话 ID
  if (route.query.cid) router.replace({ name: 'chat', query: kb.value ? { kb: '1' } : {} })
}

/**
 * 这里刻意**没有** onBeforeUnmount(abort)：以前有，后果是"顺手切个页就把回答掐断了"
 * —— 回答还在打字时去看一眼别的功能，回来只剩半截。现在它照跑，切回来是完整的。
 * 请求本身（含中止）在 store 里，跟 turns 同生命周期；这里只管"发完滚到底"这件 UI 事。
 */
async function send() {
  await sendChat(kb.value)
  await scrollToEnd()
}

function stop() {
  stopChat()
}
</script>

<template>
  <section class="chat">
    <!-- 工具条：会话标识 + 检索开关 + 操作 -->
    <div class="chat__bar">
      <div class="chat__id">
        <span class="mono">会话 ID</span>
        <code class="chat__idval">{{ chatId }}</code>
      </div>

      <div class="chat__acts">
        <button
          class="switch"
          type="button"
          role="switch"
          :aria-checked="kb"
          :disabled="busy"
          title="开启后先在你的知识库里检索，再基于检索结果作答。关闭则是通用多轮对话。"
          @click="toggleKb"
        >
          <span class="switch__track" :class="{ 'is-on': kb }">
            <span class="switch__knob" />
          </span>
          <span class="switch__label">检索知识库</span>
        </button>

        <span class="mono">{{ turns.length }} 条 · {{ kb ? 'RAG' : 'MEMORY' }}</span>
        <button class="btn btn--ghost btn--tiny" :disabled="busy" @click="reset">新建会话</button>
      </div>
    </div>

    <div ref="listEl" class="chat__list scroll">
      <!-- 空态：把引导和快捷提问塞进「档案卡」里 -->
      <div v-if="!turns.length" class="intro rise">
        <div class="intro__head">
          <h2 class="intro__title">{{ config.title }}</h2>
          <p class="intro__desc">{{ config.intro }}</p>
        </div>

        <hr class="rule-x" />

        <div class="intro__chips">
          <span class="mono">试试这些</span>
          <button
            v-for="c in chips"
            :key="c"
            class="chip"
            :disabled="busy"
            @click="((draft = c), send())"
          >
            {{ c }}
          </button>
        </div>
      </div>

      <MessageCard
        v-for="(t, i) in turns"
        :key="i"
        :role="t.role"
        :text="t.text"
        :time="t.time"
        :badge="t.role === 'assistant' ? config.badge : undefined"
        :error="t.error"
        :streaming="busy && i === turns.length - 1 && t.role === 'assistant'"
      />
    </div>

    <MessageComposer
      v-model="draft"
      :busy="busy"
      :cooldown="cooldown"
      :placeholder="config.placeholder"
      :submit-label="config.submit"
      @submit="send"
      @stop="stop"
    />
  </section>
</template>

<style scoped>
.chat {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.5);
}

.chat__id {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.chat__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 2px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chat__acts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
}

/* --- 检索知识库开关 --- */

.switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px 4px 4px;
  border: 0;
  border-radius: var(--r-pill);
  background: none;
  cursor: pointer;
  color: var(--fg-2);
  font-family: var(--font-sans);
  font-size: 12.5px;
  font-weight: 500;
  transition: background 0.16s var(--ease);
}

.switch:hover:not(:disabled) {
  background: var(--surface-2);
}

.switch:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.switch__track {
  position: relative;
  width: 34px;
  height: 18px;
  flex: none;
  border-radius: var(--r-pill);
  background: var(--fg-4);
  transition: background 0.18s var(--ease);
}

.switch__track.is-on {
  background: var(--brand);
}

.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.3);
  transition: transform 0.18s var(--ease);
}

.switch__track.is-on .switch__knob {
  transform: translateX(16px);
}

.switch__label {
  white-space: nowrap;
}

.chat__list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 28px 28px 36px;
  /* 内容轴限宽 900px 并居中：宽屏上对话框不会一路拉到屏幕最右边。
     靠左右 padding 撑开而不是给列表本身限宽，这样滚动条仍然贴在容器边缘。 */
  padding-left: max(28px, calc((100% - 900px) / 2));
  padding-right: max(28px, calc((100% - 900px) / 2));
}

/* --- 空态 --- */

.intro {
  width: 100%;
  margin: 5vh auto 0;
}

.intro__head {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.intro__title {
  margin: 2px 0 0;
  font-size: 30px;
  line-height: 1.24;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: var(--fg);
}

.intro__desc {
  margin: 4px 0 0;
  color: var(--fg-3);
  font-size: 14px;
}

.intro .rule-x {
  margin: 22px 0 18px;
}

.intro__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.chip {
  padding: 9px 14px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--surface);
  color: var(--fg-2);
  font-family: var(--font-sans);
  font-size: 13px;
  cursor: pointer;
  box-shadow: var(--sh-1);
  transition:
    transform 0.16s var(--ease),
    box-shadow 0.16s var(--ease),
    border-color 0.16s var(--ease),
    background 0.16s var(--ease),
    color 0.16s var(--ease);
}

.chip:hover:not(:disabled) {
  border-color: var(--brand);
  color: var(--brand-3);
  background: var(--brand-soft);
  transform: translateY(-1px);
  box-shadow: var(--sh-2);
}

.chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
