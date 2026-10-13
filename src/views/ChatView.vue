<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Composer from '@/components/Composer.vue'
import MessageCard from '@/components/MessageCard.vue'
import { chatRagStream, chatStream } from '@/api/client'
import { newChatId, hhmmss } from '@/utils/markdown'

/** 同一个组件承担两个页面，靠 mode 区分走哪个后端接口 */
const props = defineProps<{ mode: 'chat' | 'rag' }>()

interface Turn {
  role: 'user' | 'assistant'
  text: string
  time: string
  error?: boolean
}

const isRag = computed(() => props.mode === 'rag')

const config = computed(() =>
  isRag.value
    ? {
        badge: '知识库增强',
        placeholder: '问一道面试题、某家公司的考察重点、简历某句话该怎么改…',
        intro: '先在你的知识库里检索相关资料，再基于资料作答 —— 回答会标注来源文件。',
      }
    : {
        badge: 'AI / ASSISTANT',
        placeholder: '聊聊你的求职情况，或者直接抛一个具体问题…',
        intro: '带多轮记忆的通用咨询。它会先问清你的背景，再给可执行的具体建议。',
      }
)

const chips = computed(() =>
  isRag.value
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

const chatId = ref(newChatId())
const turns = ref<Turn[]>([])
const draft = ref('')
const busy = ref(false)
const listEl = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

async function scrollToEnd() {
  await nextTick()
  const node = listEl.value
  if (node) node.scrollTop = node.scrollHeight
}

watch(turns, scrollToEnd, { deep: true })

function reset() {
  chatId.value = newChatId()
  turns.value = []
  draft.value = ''
}

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value) return

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
    const stream = isRag.value ? chatRagStream : chatStream
    await stream(text, chatId.value, onChunk, controller.signal)
  } catch (e) {
    // 用户主动停止不算错误，保留已生成的内容即可
    if ((e as Error)?.name !== 'AbortError') {
      reply.error = true
      reply.text = reply.text || `请求失败：${(e as Error).message}\n\n检查后端是否已启动（默认 http://localhost:8124）。`
    }
  } finally {
    busy.value = false
    controller = null
    scrollToEnd()
  }
}

function stop() {
  controller?.abort()
  busy.value = false
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="chat">
    <!-- 工具条：会话标识 + 操作 -->
    <div class="chat__bar">
      <div class="chat__id">
        <span class="mono">会话 ID</span>
        <code class="chat__idval">{{ chatId }}</code>
      </div>
      <div class="chat__acts">
        <span class="mono">{{ turns.length }} 条 · {{ isRag ? 'RAG' : 'MEMORY' }}</span>
        <button class="btn btn--ghost btn--tiny" :disabled="busy" @click="reset">新建会话</button>
      </div>
    </div>

    <div ref="listEl" class="chat__list scroll">
      <!-- 空态：把引导和快捷提问塞进「档案卡」里 -->
      <div v-if="!turns.length" class="intro rise">
        <div class="intro__head">
          <span class="mono mono--ver">§ {{ isRag ? '02' : '01' }}</span>
          <h2 class="intro__title">
            {{ isRag ? '先查资料，再开口' : '先说清背景，再给建议' }}
          </h2>
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

    <Composer
      v-model="draft"
      :busy="busy"
      :placeholder="config.placeholder"
      :submit-label="isRag ? '检索并回答' : '发送'"
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
  padding: 9px 26px;
  border-bottom: 1px solid var(--rule);
}

.chat__id {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.chat__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-2);
  background: var(--paper-3);
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  padding: 1px 7px;
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

.chat__list {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 26px 26px 34px;
}

/* --- 空态 --- */

.intro {
  max-width: 68ch;
  margin: 3vh auto 0;
  width: 100%;
}

.intro__head {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.intro__title {
  margin: 2px 0 0;
  font-size: 27px;
  line-height: 1.3;
  letter-spacing: -0.015em;
  color: var(--ink);
}

.intro__desc {
  margin: 4px 0 0;
  color: var(--ink-3);
  font-size: 13.5px;
}

.intro .rule-x {
  margin: 20px 0 16px;
}

.intro__chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.chip {
  padding: 7px 12px;
  border: 1px solid var(--rule-2);
  border-radius: 999px;
  background: var(--paper-2);
  color: var(--ink-2);
  font-family: var(--font-body);
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.chip:hover:not(:disabled) {
  border-color: var(--vermilion);
  color: var(--vermilion);
  background: var(--vermilion-wash);
  transform: translateY(-1px);
}

.chip:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
