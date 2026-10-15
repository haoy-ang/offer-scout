<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

const text = defineModel<string>({ default: '' })

const props = withDefaults(
  defineProps<{
    busy?: boolean
    placeholder?: string
    submitLabel?: string
    /**
     * 限流冷却剩余秒数（0 = 没在冷却）。
     * <p>
     * 撞到后端限流后，光把按钮变灰是不够的 —— 用户会以为界面卡了，然后反复点。
     * 所以这里既禁用它，也把「还要等几秒」写在下面那行小字里，
     * 让人知道这是个有期限的等待，而不是功能坏了。
     */
    cooldown?: number
  }>(),
  { busy: false, placeholder: '说点什么…', submitLabel: '发送', cooldown: 0 }
)

const emit = defineEmits<{ submit: []; stop: [] }>()

const el = ref<HTMLTextAreaElement | null>(null)
const canSubmit = computed(
  () => !props.busy && props.cooldown === 0 && text.value.trim().length > 0
)

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
    <div class="composer__wrap">
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
        <span v-if="cooldown > 0" class="mono composer__cool">
          触发限流，{{ cooldown }} 秒后可重试
        </span>
        <span class="mono">{{ text.length }} 字</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.composer {
  flex: none;
  padding: 12px 28px 16px;
  border-top: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px) saturate(1.5);
}

/* 与消息列表同一个 900px 内容轴，输入框不会在宽屏上拉得又长又空 */
.composer__wrap {
  max-width: 900px;
  margin: 0 auto;
}

.composer__box {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  padding: 10px 10px 10px 16px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-lg);
  background: var(--surface);
  box-shadow: var(--sh-2);
  transition:
    border-color 0.16s var(--ease),
    box-shadow 0.16s var(--ease);
}

/* 聚焦时整块浮起来 + 品牌色光圈，明确"现在在跟你说话的是这里" */
.composer__box:focus-within {
  border-color: var(--brand);
  box-shadow:
    0 0 0 4px var(--brand-ring),
    var(--sh-2);
}

.composer__input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  resize: none;
  background: transparent;
  color: var(--fg);
  font-family: var(--font-sans);
  font-size: 14.5px;
  line-height: 1.72;
  max-height: 190px;
}

.composer__input::placeholder {
  color: var(--fg-4);
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
  border-radius: 1.5px;
  background: currentColor;
}

.composer__foot {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  padding: 0 4px;
}

/* 限流提示：不是错误，用赭石提醒一下就够，别跟「发送」抢注意力 */
.composer__cool {
  color: var(--warn);
}

.composer__foot .mono {
  font-size: 10px;
}
</style>
