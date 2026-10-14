<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import MessageCard from '@/components/MessageCard.vue'
import {
  deleteConversation,
  getConversationMessages,
  listConversations,
  type ChatMessage,
  type Conversation,
} from '@/api/client'

const router = useRouter()

const items = ref<Conversation[]>([])
const activeId = ref('')
const messages = ref<ChatMessage[]>([])
const loading = ref(false)
const loadingMsgs = ref(false)
const notice = ref('')

/**
 * 后端 ChatMemory 是 50 条消息的滑动窗口，超过就把**更早的裁剪掉**（真删，不是隐藏），
 * 所以长会话在这页回放时天然只有后半段。
 * <p>
 * 阈值到了就把这件事说出来 —— 否则用户会觉得"我的记录怎么少了"，
 * 而这是"数据真的没了"，比一个报错更让人不安。放在达到上限时提示，
 * 是因为只有那时候它才有信息量，平时挂在那儿只是噪音。
 */
const RETENTION_LIMIT = 50
const reachedLimit = computed(() => messages.value.length >= RETENTION_LIMIT)

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

/**
 * 带着这条会话的 ID 跳到「对话」页继续聊。
 * <p>
 * 后端 ChatMemory 按 chatId 存记忆，所以只要沿用同一个 ID，追问就能接上之前的上下文 ——
 * 这也是「继续对话」能成立的根据。
 * <p>
 * 有一个必须说清的边界：后端的记忆是**50 条消息的滑动窗口**，聊天长了以后，
 * 更早的轮次会被窗口挤掉（是真删，不只是不显示）。所以这里续的是**最近这一段**，
 * 不是全部历史。页面上也如实提示，不让人误以为能接着几个月前的对话聊。
 */
function continueChat(id: string) {
  router.push({ name: 'chat', query: { cid: id } })
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
          <span class="mono mono--ver">档案 / ARCHIVE</span>
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
          <div class="archive__headacts">
            <code v-if="activeId" class="archive__id mono">{{ activeId }}</code>
            <button
              v-if="activeId"
              class="btn btn--ghost btn--tiny"
              title="回到「对话」页，接着这条会话继续聊（后端只保留最近 50 条消息）"
              @click="continueChat(activeId)"
            >
              继续对话 →
            </button>
          </div>
        </header>

        <div class="scroll archive__body archive__body--msgs">
          <p v-if="!activeId" class="empty">从左侧选择一条会话，这里会回放它的问答记录。</p>
          <p v-else-if="loadingMsgs" class="empty mono">读取中…</p>
          <p v-else-if="!messages.length" class="empty">这条会话还没有可回放的内容。</p>

          <p v-if="activeId && reachedLimit" class="archive__retention">
            这条会话已经到后端的保留上限（最近 {{ RETENTION_LIMIT }} 条消息）。
            更早的轮次是被真实裁掉的，往下翻也找不到 —— 所以上面这些不是全部记录。
          </p>

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
  padding: 20px 28px 28px;
  gap: 14px;
}

.archive__notice {
  flex: none;
  margin: 0;
  padding: 10px 14px;
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: var(--r-sm);
  background: var(--warn-soft);
  color: #8a5208;
  font-size: 12.5px;
}

/* 「已到保留上限」的说明：站内提示语，不是错误，所以走中性底色 + 左侧细边 */
.archive__retention {
  margin: 0;
  padding: 10px 14px;
  border: 1px solid var(--line-2);
  border-left: 3px solid var(--warn);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--fg-2);
  font-size: 12.5px;
  line-height: 1.7;
}

.archive__grid {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(250px, 330px) 1fr;
  gap: 18px;
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
  padding: 26px 26px 32px;
}

.archive__headacts {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.archive__id {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-3);
  max-width: 34ch;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty {
  margin: 22px 14px;
  color: var(--fg-3);
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
  border-radius: var(--r-sm);
  background: transparent;
  cursor: pointer;
  transition:
    background 0.15s var(--ease),
    border-color 0.15s var(--ease);
}

.conv:hover {
  background: var(--surface-2);
}

/* 选中态与左栏导航同一套：品牌浅底 + 淡边框（不再用左侧竖条那种旧写法） */
.conv.is-active {
  background: var(--brand-soft);
  border-color: rgba(229, 72, 77, 0.22);
}

.conv__title {
  font-size: 13.5px;
  color: var(--fg);
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.conv.is-active .conv__title {
  font-weight: 600;
  color: var(--brand-3);
}

.conv__meta {
  font-size: 10px;
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
  border-radius: var(--r-xs);
  color: var(--fg-3);
  font-size: 15px;
  line-height: 1;
  opacity: 0;
  transition:
    opacity 0.15s var(--ease),
    color 0.15s var(--ease),
    background 0.15s var(--ease);
}

.conv:hover .conv__del {
  opacity: 1;
}

.conv__del:hover {
  background: var(--brand);
  color: #fff;
}

@media (max-width: 880px) {
  .archive__grid {
    grid-template-columns: 1fr;
    grid-template-rows: minmax(160px, 34%) 1fr;
  }
}
</style>
