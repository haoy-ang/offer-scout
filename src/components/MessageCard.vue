<script setup lang="ts">
import { computed, ref } from 'vue'
import { copyText, renderMarkdown } from '@/utils/markdown'

const props = withDefaults(
  defineProps<{
    role: 'user' | 'assistant'
    text: string
    time?: string
    /** 正在流式接收：显示光标 */
    streaming?: boolean
    /** 助手消息的来源标签，例如「知识库增强」「作战报告」 */
    badge?: string
    /** 出错时整块换成警示样式 */
    error?: boolean
  }>(),
  { streaming: false, error: false }
)

const html = computed(() => renderMarkdown(props.text))

const copied = ref(false)
async function onCopy() {
  if (await copyText(props.text)) {
    copied.value = true
    window.setTimeout(() => (copied.value = false), 1400)
  }
}
</script>

<template>
  <!-- 用户消息：右对齐的品牌渐变气泡 -->
  <article v-if="role === 'user'" class="msg msg--user rise">
    <div class="msg__meta">
      <span class="mono">{{ time }}</span>
      <span class="tag">你 / USER</span>
    </div>
    <div class="msg__bubble">{{ text }}</div>
  </article>

  <!-- 助手消息：品牌方块头像 + 无边框正文（留白分隔，不用气泡框） -->
  <article v-else class="msg msg--bot rise" :class="{ 'is-error': error }">
    <div class="msg__avatar">AI</div>

    <div class="msg__main">
      <div class="msg__meta">
        <span class="tag" :class="error ? '' : 'tag--live'">
          {{ error ? '执行失败' : badge || 'AI / ASSISTANT' }}
        </span>
        <span class="mono">{{ time }}</span>
        <button v-if="text" class="msg__copy mono" @click="onCopy">
          {{ copied ? '已复制' : '复制' }}
        </button>
      </div>

      <div class="msg__body">
        <div class="prose" v-html="html" />
        <span v-if="streaming" class="caret" />
      </div>
    </div>
  </article>
</template>

<style scoped>
.msg {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 76ch;
}

.msg__meta {
  display: flex;
  align-items: center;
  gap: 10px;
}

.msg__copy {
  margin-left: auto;
  border: 0;
  background: none;
  padding: 0;
  font-size: 10px;
  letter-spacing: 0.08em;
  cursor: pointer;
  color: var(--fg-3);
  border-bottom: 1px solid transparent;
  transition:
    color 0.15s var(--ease),
    border-color 0.15s var(--ease);
}

.msg__copy:hover {
  color: var(--brand);
  border-bottom-color: var(--brand);
}

/* --- 用户 --- */

.msg--user {
  align-self: flex-end;
  align-items: flex-end;
}

.msg--user .msg__meta {
  flex-direction: row-reverse;
}

.msg__bubble {
  padding: 11px 16px;
  border-radius: var(--r-lg) var(--r-lg) var(--r-xs) var(--r-lg);
  background: var(--brand-grad);
  color: #fff;
  font-size: 14.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  box-shadow: 0 10px 24px -14px rgba(229, 72, 77, 0.75);
}

/* --- 助手 --- */

.msg--bot {
  flex-direction: row;
  align-items: flex-start;
  gap: 12px;
  max-width: 84ch;
}

.msg__avatar {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: none;
  margin-top: 1px;
  border-radius: var(--r-xs);
  background: var(--brand-grad);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.02em;
  box-shadow: var(--sh-brand);
}

.msg__main {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
  width: 100%;
}

.msg__body {
  min-width: 0;
  padding-top: 2px;
}

/* 出错：整块换成警示卡，比一条变色竖线更容易被看见 */
.msg--bot.is-error .msg__body {
  padding: 12px 15px;
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: var(--r-md);
  background: var(--warn-soft);
}

.msg--bot.is-error .prose {
  color: #8a5208;
}
</style>
