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
  <!-- 用户消息：右对齐的墨块 -->
  <article v-if="role === 'user'" class="msg msg--user rise">
    <div class="msg__meta">
      <span class="mono">{{ time }}</span>
      <span class="tag">你 / USER</span>
    </div>
    <div class="msg__bubble">{{ text }}</div>
  </article>

  <!-- 助手消息：左朱砂竖线的「报告块」 -->
  <article v-else class="msg msg--bot rise" :class="{ 'is-error': error }">
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
  </article>
</template>

<style scoped>
.msg {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 74ch;
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
  font-size: 9.5px;
  letter-spacing: 0.12em;
  cursor: pointer;
  color: var(--ink-3);
  border-bottom: 1px solid transparent;
}

.msg__copy:hover {
  color: var(--vermilion);
  border-bottom-color: var(--vermilion);
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
  padding: 11px 15px;
  border-radius: var(--r-sm);
  background: var(--ink);
  color: var(--paper-2);
  font-size: 14.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  box-shadow: 0 10px 24px -18px rgba(22, 20, 15, 0.9);
}

/* --- 助手 --- */

.msg--bot {
  align-self: flex-start;
}

.msg__body {
  position: relative;
  padding: 2px 0 2px 17px;
  border-left: 2px solid var(--vermilion);
}

.msg--bot.is-error .msg__body {
  border-left-color: var(--amber);
  color: var(--vermilion-2);
}
</style>
