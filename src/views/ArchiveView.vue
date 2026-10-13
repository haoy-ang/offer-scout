<script setup lang="ts">
import { onMounted, ref } from 'vue'
import MessageCard from '@/components/MessageCard.vue'
import {
  deleteConversation,
  getConversationMessages,
  listConversations,
  type ChatMessage,
  type Conversation,
} from '@/api/client'

const items = ref<Conversation[]>([])
const activeId = ref('')
const messages = ref<ChatMessage[]>([])
const loading = ref(false)
const loadingMsgs = ref(false)
const notice = ref('')

/** 后端返回的是 ISO 字符串（如 2026-10-13T09:12:03），转成 MM-DD HH:mm 更好读 */
function fmt(value: string): string {
  if (!value) return '—'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 只回放有实际内容的问答，system / tool 这类中间消息不展示 */
function replayable(list: ChatMessage[]) {
  return list
    .filter((m) => (m.role === 'user' || m.role === 'assistant') && (m.content ?? '').trim())
    .map((m) => ({ role: m.role as 'user' | 'assistant', text: m.content ?? '' }))
}

async function load() {
  loading.value = true
  notice.value = ''
  try {
    items.value = await listConversations()
    // 当前选中的会话被删掉了，就退回去
    if (activeId.value && !items.value.some((c) => c.id === activeId.value)) {
      activeId.value = ''
      messages.value = []
    }
  } catch (e) {
    notice.value = `读取会话列表失败：${(e as Error).message}`
  } finally {
    loading.value = false
  }
}

async function open(id: string) {
  activeId.value = id
  loadingMsgs.value = true
  messages.value = []
  try {
    messages.value = await getConversationMessages(id)
  } catch (e) {
    notice.value = `读取会话内容失败：${(e as Error).message}`
  } finally {
    loadingMsgs.value = false
  }
}

async function remove(id: string) {
  if (!window.confirm('确定删除这条会话及其全部消息记录吗？此操作不可撤销。')) return
  try {
    await deleteConversation(id)
    await load()
  } catch (e) {
    notice.value = `删除失败：${(e as Error).message}`
  }
}

onMounted(load)
</script>

<template>
  <section class="archive">
    <p v-if="notice" class="archive__notice">{{ notice }}</p>

    <div class="archive__grid">
      <!-- 左：会话列表 -->
      <aside class="panel archive__col archive__col--list">
        <header class="panel__head">
          <span class="mono mono--ver">§ 04 · 档案</span>
          <button class="btn btn--ghost btn--tiny" :disabled="loading" @click="load">
            {{ loading ? '读取中' : '刷新' }}
          </button>
        </header>

        <div class="scroll archive__body">
          <p v-if="loading && !items.length" class="empty mono">读取中…</p>
          <p v-else-if="!items.length" class="empty">
            暂无历史会话。<br />去「对话」页聊几句，这里就会出现记录。
          </p>

          <button
            v-for="c in items"
            :key="c.id"
            class="conv"
            :class="{ 'is-active': c.id === activeId }"
            @click="open(c.id)"
          >
            <span class="conv__title">{{ c.title || '未命名会话' }}</span>
            <span class="conv__meta mono"> {{ c.messageCount }} 条 · {{ fmt(c.updatedAt) }} </span>
            <span class="conv__del" title="删除会话" @click.stop="remove(c.id)">×</span>
          </button>
        </div>
      </aside>

      <!-- 右：会话内容 -->
      <section class="panel archive__col archive__col--view">
        <header class="panel__head">
          <span class="mono">会话内容</span>
          <code v-if="activeId" class="archive__id mono">{{ activeId }}</code>
        </header>

        <div class="scroll archive__body archive__body--msgs">
          <p v-if="!activeId" class="empty">从左侧选择一条会话，这里会回放它的问答记录。</p>
          <p v-else-if="loadingMsgs" class="empty mono">读取中…</p>
          <p v-else-if="!messages.length" class="empty">这条会话还没有可回放的内容。</p>

          <MessageCard
            v-for="(m, i) in replayable(messages)"
            :key="i"
            :role="m.role"
            :text="m.text"
            :badge="m.role === 'assistant' ? '存档回放' : undefined"
          />
        </div>
      </section>
    </div>
  </section>
</template>

<style scoped>
.archive {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 20px 26px 26px;
  gap: 12px;
}

.archive__notice {
  flex: none;
  margin: 0;
  padding: 9px 14px;
  border: 1px solid var(--amber);
  border-radius: var(--r-sm);
  background: #f6ecd7;
  color: #7a550f;
  font-size: 12.5px;
}

.archive__grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(240px, 320px) 1fr;
  gap: 16px;
}

.archive__col {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.archive__body {
  flex: 1;
  min-height: 0;
  padding: 8px;
}

.archive__body--msgs {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 20px 22px 28px;
}

.archive__id {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--ink-3);
  max-width: 34ch;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty {
  margin: 22px 14px;
  color: var(--ink-3);
  font-size: 13px;
  line-height: 1.8;
}

/* --- 会话条目 --- */

.conv {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
  text-align: left;
  padding: 11px 30px 11px 13px;
  border: 1px solid transparent;
  border-left: 2px solid transparent;
  border-radius: var(--r-sm);
  background: transparent;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.conv:hover {
  background: var(--paper-3);
}

.conv.is-active {
  background: var(--paper-3);
  border-left-color: var(--vermilion);
}

.conv__title {
  font-size: 13.5px;
  color: var(--ink);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.conv.is-active .conv__title {
  font-weight: 600;
}

.conv__meta {
  font-size: 9.5px;
}

.conv__del {
  position: absolute;
  top: 50%;
  right: 9px;
  transform: translateY(-50%);
  width: 20px;
  height: 20px;
  display: grid;
  place-items: center;
  border-radius: var(--r-sm);
  color: var(--ink-3);
  font-size: 15px;
  line-height: 1;
  opacity: 0;
  transition:
    opacity 0.15s ease,
    color 0.15s ease,
    background 0.15s ease;
}

.conv:hover .conv__del {
  opacity: 1;
}

.conv__del:hover {
  background: var(--vermilion);
  color: var(--paper-2);
}

@media (max-width: 880px) {
  .archive__grid {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(160px, 34%) 1fr;
  }
}
</style>
