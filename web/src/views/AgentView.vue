<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import MessageComposer from '@/components/MessageComposer.vue'
import MessageCard from '@/components/MessageCard.vue'
import { hhmmss } from '@/utils/markdown'
import { resetAgent, runAgent, stopAgent, useAgentSession } from '@/stores/agent'
import { cooldownSeconds } from '@/stores/cooldown'

/**
 * 状态全部来自模块级 store（见 `@/stores/agent`）。
 * <p>
 * 为什么必须提上去：路由切换会让 RouterView 里的组件重挂，状态若留在组件实例里
 * 就会随重挂清零 —— 一次跑几十秒的 Agent 任务白跑，切回来只剩一个空页面、还得重来。
 * 提到模块级 ref 之后，切页只重建视图，轨迹与报告都还在。
 * <p>
 * 这里只保留纯 UI 的东西：滚动容器的 DOM 引用，以及从状态派生出来的展示值。
 * SSE 的解析、计时器、请求中止这些「跑一次任务」的逻辑也都搬进了 store。
 */
const { draft, task, busy, traces, report, reportTime, error, elapsed, restoredAt } =
  useAgentSession()

/**
 * 限流冷却剩余秒数。
 * <p>
 * 这一页最容易被撞：Agent 一次任务要跑 3~8 步模型调用，后端把额度压到了 3 次/分钟。
 * 光把按钮变灰，用户会以为是页面卡了，然后反复点 —— 所以要把「还要等几秒」写在输入框下面。
 */
const cooldown = cooldownSeconds

const listEl = ref<HTMLElement | null>(null)

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

// deep：traces 里 push 新元素、report 里逐段追加，都要跟着滚到底
watch([traces, report], scrollToEnd, { deep: true })

// 从别页切回来时滚动容器是新建的、停在顶部；补滚一次，别让用户自己往下找结果
onMounted(() => {
  void scrollToEnd()
})

/**
 * 这里刻意**没有** onBeforeUnmount(abort) —— 以前有，后果是"顺手切个页就把正在跑的
 * Agent 掐了"。现在任务该跑完就跑完，用户切回来正好看到完整结果；真要中止请点「停止」。
 */
function run() {
  void runAgent(draft.value)
}

function stop() {
  stopAgent()
}

function reset() {
  resetAgent()
}
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
            <!-- 从缓存恢复的要如实标注：不然用户会以为这是刚跑出来的 -->
            <span class="mono">
              {{ restoredAt ? '历史结果 · ' + restoredAt : reportTime || hhmmss() }}
            </span>
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
      :cooldown="cooldown"
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

/* 工具条：与对话页的 .chat__bar 同一套（白底 + 同一内边距） */
.agent__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.5);
}

.agent__id {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.agent__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--fg-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 2px 8px;
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
  padding: 28px 28px 36px;
  /* 与对话页共用同一条 900px 内容轴：宽屏上不会一路贴到最右边 */
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

/* --- 侦察轨迹 --- */

.trace {
  width: 100%;
}

.trace__live {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--brand);
}

.trace__pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--brand);
  animation: pulse-ring 2s var(--ease) infinite;
}

.trace__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

/* 步骤之间改实线、加大行内留白 —— 原来的虚线是旧「档案」调性，在 SaaS 风里显碎 */
.trace__step {
  display: grid;
  grid-template-columns: 42px 1fr;
  gap: 12px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
}

.trace__step:last-child {
  border-bottom: 0;
}

.trace__idx {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--brand);
  padding-top: 2px;
  font-variant-numeric: tabular-nums;
}

.trace__main {
  display: flex;
  flex-direction: column;
  gap: 7px;
  min-width: 0;
}

.trace__text {
  margin: 0;
  font-size: 13px;
  line-height: 1.68;
  color: var(--fg-2);
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
  height: 4px;
  background: var(--surface-2);
  border-radius: var(--r-pill);
  overflow: hidden;
}

.trace__sweep {
  position: absolute;
  inset: 0 auto 0 0;
  width: 30%;
  border-radius: var(--r-pill);
  background: var(--brand);
  animation: sweep 1.35s cubic-bezier(0.4, 0, 0.2, 1) infinite;
}

/* --- 作战报告 --- */

.report {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.report__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 2px;
}
</style>
