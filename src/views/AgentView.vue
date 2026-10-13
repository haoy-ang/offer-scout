<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import MessageComposer from '@/components/MessageComposer.vue'
import MessageCard from '@/components/MessageCard.vue'
import { agentRunStream } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 后端每一步推来的是形如 `第 3 步｜工具 xxx 返回：…` 的一行文本；
 * 最终结论则是单独一次推送的整段 Markdown。
 * 于是「能匹配这个正则的 = 过程轨迹，匹配不上的 = 最终报告」，就此分开。
 */
const STEP_RE = /^第\s*(\d+)\s*步\s*[｜|]\s*([\s\S]*)$/

interface Trace {
  index: number
  kind: 'think' | 'tool'
  text: string
}

const draft = ref('')
const task = ref('')
const busy = ref(false)
const traces = ref<Trace[]>([])
const report = ref('')
const reportTime = ref('')
const error = ref('')
const elapsed = ref(0)
const listEl = ref<HTMLElement | null>(null)

let controller: AbortController | null = null
let ticker: number | undefined

const examples = [
  '帮我规划未来 3 个月的 Java 后端求职准备，按周拆解',
  '字节 / 美团 / 阿里的后端实习，各自考察重点是什么？',
  '我现在只有一个课程设计项目，怎么补一个能写进简历的项目？',
]

const hasOutput = computed(() => traces.value.length > 0 || !!report.value || !!error.value)

async function scrollToEnd() {
  await nextTick()
  const node = listEl.value
  if (node) node.scrollTop = node.scrollHeight
}
watch(traces, scrollToEnd, { deep: true })
watch(report, scrollToEnd)

function startTicker() {
  elapsed.value = 0
  const began = Date.now()
  ticker = window.setInterval(() => {
    elapsed.value = Math.floor((Date.now() - began) / 1000)
  }, 500)
}

function stopTicker() {
  if (ticker) window.clearInterval(ticker)
  ticker = undefined
}

function onChunk(chunk: string) {
  const match = STEP_RE.exec(chunk)
  if (match) {
    const body = match[2].trim()
    traces.value.push({
      index: Number(match[1]),
      // 工具类的步骤会以「工具 xxx 返回」开头，用颜色区分开，扫一眼就知道它在「想」还是「做」
      kind: body.startsWith('工具') ? 'tool' : 'think',
      text: body || '（本步无输出）',
    })
    return
  }
  // 匹配不上步骤格式的，就是收尾那一次推送 —— 最终报告
  if (!reportTime.value) reportTime.value = hhmmss()
  report.value += chunk
}

async function run() {
  const text = draft.value.trim()
  if (!text || busy.value) return

  task.value = text
  draft.value = ''
  traces.value = []
  report.value = ''
  reportTime.value = ''
  error.value = ''
  busy.value = true
  controller = new AbortController()
  startTicker()
  await scrollToEnd()

  try {
    await agentRunStream(text, onChunk, controller.signal)
  } catch (e) {
    if ((e as Error)?.name !== 'AbortError') {
      error.value =
        report.value ||
        `执行失败：${(e as Error).message}\n\n检查后端是否已启动（默认 http://localhost:8124），Agent 首次运行可能要等十几秒。`
    }
  } finally {
    busy.value = false
    controller = null
    stopTicker()
    scrollToEnd()
  }
}

function stop() {
  controller?.abort()
  busy.value = false
  stopTicker()
}

function reset() {
  if (busy.value) stop()
  task.value = ''
  traces.value = []
  report.value = ''
  reportTime.value = ''
  error.value = ''
  draft.value = ''
}

onBeforeUnmount(() => {
  controller?.abort()
  stopTicker()
})
</script>

<template>
  <section class="agent">
    <!-- 工具条：任务摘要 + 步数耗时 -->
    <div class="agent__bar">
      <div class="agent__id">
        <span class="mono">任务</span>
        <code class="agent__idval">{{ task || '尚未下达' }}</code>
      </div>
      <div class="agent__acts">
        <span class="mono">
          {{ traces.length }} 步 · {{ elapsed }}s ·
          {{ busy ? '执行中' : hasOutput ? '已完成' : '待命' }}
        </span>
        <button class="btn btn--ghost btn--tiny" :disabled="!hasOutput && !busy" @click="reset">
          清空
        </button>
      </div>
    </div>

    <div ref="listEl" class="agent__body scroll">
      <!-- 空态：任务示例 -->
      <div v-if="!hasOutput" class="intro rise">
        <div class="intro__head">
          <span class="mono mono--ver">§ 03</span>
          <h2 class="intro__title">把任务交给它，看它自己拆解</h2>
          <p class="intro__desc">
            它会自主决定检索几次、查哪些资料，过程中每一步都会实时出现在下面。
            跑完再一次性给出带引用来源的完整方案。
          </p>
        </div>

        <hr class="rule-x" />

        <div class="intro__chips">
          <span class="mono">试试这些任务</span>
          <button
            v-for="c in examples"
            :key="c"
            class="chip"
            :disabled="busy"
            @click="((draft = c), run())"
          >
            {{ c }}
          </button>
        </div>
      </div>

      <template v-else>
        <!-- 侦察轨迹：每一步一行，实时追加 -->
        <section class="panel trace">
          <header class="panel__head">
            <span class="mono mono--ver">轨迹 / TRACE</span>
            <span v-if="busy" class="trace__live">
              <span class="trace__pulse" />
              正在侦察
            </span>
            <span v-else class="mono">共 {{ traces.length }} 步</span>
          </header>

          <ol class="trace__list">
            <li v-for="t in traces" :key="t.index" class="trace__step rise">
              <span class="trace__idx mono">{{ String(t.index).padStart(2, '0') }}</span>
              <div class="trace__main">
                <span class="tag" :class="t.kind === 'tool' ? 'tag--pine' : ''">
                  {{ t.kind === 'tool' ? '调用工具' : '思考' }}
                </span>
                <p class="trace__text">{{ t.text }}</p>
              </div>
            </li>

            <!-- 还未产出下一步时的等待条 -->
            <li v-if="busy" class="trace__step trace__step--waiting">
              <span class="trace__idx mono">··</span>
              <div class="trace__wait">
                <span class="trace__sweep" />
              </div>
            </li>
          </ol>
        </section>

        <!-- 作战报告：最终结论 -->
        <div v-if="report || error" class="report rise">
          <div class="report__meta">
            <span class="tag" :class="error ? '' : 'tag--live'">
              {{ error ? '执行失败' : '作战报告 / FINAL' }}
            </span>
            <span class="mono">{{ reportTime || hhmmss() }}</span>
          </div>
          <MessageCard
            role="assistant"
            :text="report || error"
            :badge="error ? undefined : '作战报告'"
            :error="!!error"
          />
        </div>
      </template>
    </div>

    <MessageComposer
      v-model="draft"
      :busy="busy"
      placeholder="描述你的目标或困惑，例如「帮我规划 3 个月的后端求职准备」…"
      submit-label="开始侦察"
      @submit="run"
      @stop="stop"
    />
  </section>
</template>

<style scoped>
.agent {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.agent__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 26px;
  border-bottom: 1px solid var(--rule);
}

.agent__id {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.agent__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-2);
  background: var(--paper-3);
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  padding: 1px 7px;
  max-width: 52ch;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.agent__acts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
}

.agent__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px 26px 34px;
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

/* --- 轨迹 --- */

.trace {
  width: 100%;
  max-width: 84ch;
}

.trace__live {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--vermilion);
}

.trace__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--vermilion);
  animation: blink 1.1s steps(1) infinite;
}

.trace__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.trace__step {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px dashed var(--rule);
}

.trace__step:last-child {
  border-bottom: 0;
}

.trace__idx {
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--vermilion);
  padding-top: 2px;
  font-variant-numeric: tabular-nums;
}

.trace__main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.trace__text {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.62;
  color: var(--ink-2);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  max-height: 8.5em;
  overflow: hidden;
}

.trace__step--waiting {
  grid-template-columns: 42px 1fr;
  align-items: center;
}

.trace__wait {
  position: relative;
  height: 3px;
  background: var(--paper-3);
  border-radius: 2px;
  overflow: hidden;
}

.trace__sweep {
  position: absolute;
  inset: 0 auto 0 0;
  width: 30%;
  background: var(--vermilion);
  animation: sweep 1.35s cubic-bezier(0.4, 0, 0.2, 1) infinite;
}

/* --- 报告 --- */

.report {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-width: 84ch;
}

.report__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 2px;
}
</style>
