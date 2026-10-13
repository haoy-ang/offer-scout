<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const text = defineModel<string>({ default: '' })

const props = withDefaults(
  defineProps<{
    busy?: boolean
    placeholder?: string
    submitLabel?: string
  }>(),
  { busy: false, placeholder: '说点什么…', submitLabel: '发送' }
)

const emit = defineEmits<{ submit: []; stop: [] }>()

const el = ref<HTMLTextAreaElement | null>(null)
const canSubmit = computed(() => !props.busy && text.value.trim().length > 0)

/** textarea 自适应高度：内容多了长高，到上限后自己滚动 */
async function autosize() {
  await nextTick()
  const node = el.value
  if (!node) return
  node.style.height = 'auto'
  node.style.height = `${Math.min(node.scrollHeight, 190)}px`
}

watch(text, autosize)

function onKeydown(event: KeyboardEvent) {
  if (event.key !== 'Enter' || event.shiftKey) return
  // 中文输入法用 Enter 确认候选词，此时 isComposing 为 true，
  // 不判断它就会出现「拼音还没选完就把半成品发出去了」
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (canSubmit.value) emit('submit')
}

function onStop() {
  emit('stop')
}
</script>

<template>
  <div class="composer">
    <div class="composer__box">
      <textarea
        ref="el"
        v-model="text"
        class="composer__input"
        rows="1"
        :placeholder="placeholder"
        :disabled="busy"
        @keydown="onKeydown"
      />
      <div class="composer__actions">
        <button v-if="busy" class="btn btn--stop btn--tiny" @click="onStop">
          <span class="composer__square" />
          停止
        </button>
        <button v-else class="btn btn--tiny" :disabled="!canSubmit" @click="emit('submit')">
          {{ submitLabel }}
          <span class="composer__arrow">↵</span>
        </button>
      </div>
    </div>
    <div class="composer__foot">
      <span class="mono">ENTER 发送 · SHIFT+ENTER 换行</span>
      <span class="mono">{{ text.length }} 字</span>
    </div>
  </div>
</template>

<style scoped>
.composer {
  flex: none;
  padding: 12px 26px 16px;
  border-top: 1px solid var(--rule);
  background: rgba(250, 247, 240, 0.82);
  backdrop-filter: blur(8px);
}

.composer__box {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 10px 10px 10px 14px;
  border: 1px solid var(--rule-2);
  border-radius: var(--r-md);
  background: var(--paper-2);
  box-shadow: var(--shadow-1);
  transition: border-color 0.16s ease, box-shadow 0.16s ease;
}

.composer__box:focus-within {
  border-color: var(--ink);
  box-shadow: var(--shadow-2);
}

.composer__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  resize: none;
  background: transparent;
  color: var(--ink);
  font-family: var(--font-body);
  font-size: 14.5px;
  line-height: 1.72;
  max-height: 190px;
}

.composer__input::placeholder {
  color: var(--ink-3);
  opacity: 0.75;
}

.composer__actions {
  flex: none;
}

.composer__arrow {
  font-size: 13px;
}

.composer__square {
  width: 7px;
  height: 7px;
  background: currentColor;
}

.composer__foot {
  display: flex;
  justify-content: space-between;
  margin-top: 7px;
  padding: 0 2px;
}

.composer__foot .mono {
  font-size: 9.5px;
}
</style>
