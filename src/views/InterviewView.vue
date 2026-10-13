<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import Composer from '@/components/Composer.vue'
import MessageCard from '@/components/MessageCard.vue'
import {
  interviewStream,
  reportInterview,
  startInterview,
  type InterviewReport,
  type InterviewType,
} from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 模拟面试间。
 * <p>
 * 这是全站唯一一个「有状态的三段式」页面，用 phase 显式建模：
 *   setup → interview → report
 * 三段之间不靠 URL 承载状态，而是靠内存里的 sessionId 串起来 ——
 * 刷新页面就等于重新开一场面试，这是刻意为之（一场面试本来也不该跨刷新续上）。
 * <p>
 * 和后端的分工：前端只负责「收集岗位/类型 → 逐轮转述回答 → 展示复盘」，
 * 面试官的记忆、追问策略、复盘评分全在后端。所以切走了面试类型/岗位的原始参数
 * 每一轮都要原样带回后端（后端是无状态的，不存这些）。
 */

const TYPES: { key: InterviewType; label: string; hint: string }[] = [
  { key: 'tech', label: '技术面', hint: '考技术深度与项目细节' },
  { key: 'hr', label: 'HR 面', hint: '考动机、稳定性与协作' },
  { key: 'mixed', label: '综合面', hint: '技术 + 行为面结合' },
]

const PRIORITY = {
  high: { label: '高优先', cls: 'pri--high' },
  medium: { label: '中优先', cls: 'pri--medium' },
  low: { label: '低优先', cls: 'pri--low' },
} as const

interface Turn {
  role: 'user' | 'assistant'
  text: string
  time: string
  error?: boolean
}

type Phase = 'setup' | 'interview' | 'report'

const phase = ref<Phase>('setup')
const role = ref('Java 后端开发实习生')
const type = ref<InterviewType>('tech')

const sessionId = ref('')
const turns = ref<Turn[]>([])
const draft = ref('')
const busy = ref(false)

const report = ref<InterviewReport | null>(null)
const error = ref('')

const listEl = ref<HTMLElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)
let controller: AbortController | null = null

const typeLabel = computed(() => TYPES.find((t) => t.key === type.value)?.label ?? '技术面')
/** 已问答轮数 = 候选人回答的次数 */
const answered = computed(() => turns.value.filter((t) => t.role === 'user').length)
const canStart = computed(() => !busy.value && role.value.trim().length > 0)

async function scrollToEnd() {
  await nextTick()
  const node = listEl.value
  if (node) node.scrollTop = node.scrollHeight
}

watch(turns, scrollToEnd, { deep: true })

/** 复盘结论 → 视觉档位：通过=松绿，待定=琥珀，不通过=朱砂 */
const verdict = computed(() => {
  const v = report.value?.verdict ?? ''
  if (v.includes('通过') && !v.includes('不')) return { text: v, cls: 'score--pine', tag: 'tag--pine' }
  if (v.includes('不建议')) return { text: v, cls: 'score--ver', tag: 'tag--live' }
  return { text: v || '待定', cls: 'score--amber', tag: '' }
})

function dimTone(score: number): string {
  if (score >= 75) return 'dim--pine'
  if (score >= 60) return 'dim--amber'
  return 'dim--ver'
}

/* ---------------- 开始面试 ---------------- */

async function begin() {
  if (!canStart.value) return
  busy.value = true
  error.value = ''
  turns.value = []
  report.value = null

  try {
    const res = await startInterview(role.value.trim(), type.value)
    sessionId.value = res.sessionId
    turns.value.push({ role: 'assistant', text: res.opening, time: hhmmss() })
    phase.value = 'interview'
    scrollToEnd()
  } catch (e) {
    error.value = (e as Error).message || '面试开启失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

/* ---------------- 逐轮回答 ---------------- */

async function send() {
  const text = draft.value.trim()
  if (!text || busy.value || phase.value !== 'interview') return

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
    await interviewStream(sessionId.value, text, role.value.trim(), type.value, onChunk, controller.signal)
  } catch (e) {
    if ((e as Error)?.name !== 'AbortError') {
      reply.error = true
      reply.text = reply.text || `请求失败：${(e as Error).message}`
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

/* ---------------- 结束并复盘 ---------------- */

async function finish() {
  if (busy.value || phase.value !== 'interview') return
  if (answered.value === 0) {
    error.value = '至少回答一轮，才能生成有依据的复盘。'
    return
  }
  busy.value = true
  error.value = ''

  try {
    report.value = await reportInterview(sessionId.value, role.value.trim(), type.value)
    phase.value = 'report'
    await nextTick()
    if (bodyEl.value) bodyEl.value.scrollTop = 0
  } catch (e) {
    error.value = (e as Error).message || '复盘生成失败，请稍后重试。'
  } finally {
    busy.value = false
  }
}

function backToInterview() {
  phase.value = 'interview'
  scrollToEnd()
}

function restart() {
  controller?.abort()
  phase.value = 'setup'
  sessionId.value = ''
  turns.value = []
  draft.value = ''
  report.value = null
  error.value = ''
}

onBeforeUnmount(() => controller?.abort())
</script>

<template>
  <section class="itv">
    <!-- 顶栏 -->
    <div class="itv__bar">
      <div class="itv__id">
        <span class="mono">面试间</span>
        <code class="itv__idval">
          {{ phase === 'setup' ? '未开始' : `${role.trim() || 'Java 后端开发'} · ${typeLabel}` }}
        </code>
      </div>
      <div class="itv__acts">
        <span class="mono">
          <template v-if="phase === 'interview'">已答 {{ answered }} 轮</template>
          <template v-else-if="phase === 'report'">已复盘</template>
          <template v-else>待开始</template>
        </span>
        <button v-if="phase === 'interview'" class="btn btn--ghost btn--tiny" :disabled="busy" @click="finish">
          结束并复盘
        </button>
        <button v-if="phase === 'report'" class="btn btn--ghost btn--tiny" @click="backToInterview">
          回看对话
        </button>
        <button v-if="phase !== 'setup'" class="btn btn--ghost btn--tiny" :disabled="busy" @click="restart">
          重新开始
        </button>
      </div>
    </div>

    <div ref="bodyEl" class="itv__body scroll">
      <!-- ============ 一、准备 ============ -->
      <template v-if="phase === 'setup'">
        <section class="panel setup rise">
          <header class="panel__head">
            <span class="mono mono--ver">§ 07 · 模拟面试 / SETUP</span>
            <span class="mono">AI 扮演面试官，多轮追问</span>
          </header>

          <div class="setup__field">
            <label class="setup__label" for="itv-role">目标岗位</label>
            <input
              id="itv-role"
              v-model="role"
              class="setup__input"
              type="text"
              placeholder="例如：Java 后端开发实习生"
              :disabled="busy"
            />
          </div>

          <div class="setup__field">
            <span class="setup__label">面试类型</span>
            <div class="segs">
              <button
                v-for="t in TYPES"
                :key="t.key"
                type="button"
                class="seg"
                :class="{ 'is-on': type === t.key }"
                :disabled="busy"
                @click="type = t.key"
              >
                <span class="seg__label">{{ t.label }}</span>
                <span class="seg__hint">{{ t.hint }}</span>
              </button>
            </div>
          </div>

          <div class="setup__submit">
            <button class="btn" type="button" :disabled="!canStart" @click="begin">
              {{ busy ? '开启中…' : '开始面试' }}
            </button>
            <span class="mono setup__note">一场面试 = 多轮追问，结束后由同一份记录生成复盘</span>
          </div>
        </section>

        <div class="intro rise">
          <hr class="rule-x" />
          <p class="intro__desc">
            面试官会<strong>一次只问一个问题</strong>，根据你的回答决定是追问还是换题，问满 6~8 题后收尾。
            面试过程中它不会点评 —— 所有评价集中在最后的复盘里：<strong>能不能过 + 四个维度打分 + 具体短板怎么补</strong>。
            回答得越具体（讲清思路和取舍），复盘才有料可评。
          </p>
          <p v-if="error" class="intro__err">{{ error }}</p>
        </div>
      </template>

      <!-- ============ 二、面试进行中 ============ -->
      <template v-else-if="phase === 'interview'">
        <div ref="listEl" class="thread">
          <MessageCard
            v-for="(t, i) in turns"
            :key="i"
            :role="t.role"
            :text="t.text"
            :time="t.time"
            :badge="t.role === 'assistant' ? '面试官' : undefined"
            :error="t.error"
            :streaming="busy && i === turns.length - 1 && t.role === 'assistant'"
          />
        </div>
        <p v-if="error" class="thread__err">{{ error }}</p>
      </template>

      <!-- ============ 三、复盘 ============ -->
      <template v-else>
        <section v-if="error" class="panel error rise">
          <header class="panel__head">
            <span class="tag">复盘失败</span>
            <span class="mono">{{ hhmmss() }}</span>
          </header>
          <p class="error__msg">{{ error }}</p>
        </section>

        <template v-if="report">
          <!-- 总分 + 结论 -->
          <section class="panel scorecard rise">
            <div class="scorecard__main">
              <div class="scorecard__num">
                <span class="scorecard__val" :class="verdict.cls">{{ report.overallScore }}</span>
                <span class="scorecard__unit">/ 100</span>
              </div>
              <div class="scorecard__meta">
                <span class="tag" :class="verdict.tag">{{ verdict.text }}</span>
                <span class="mono">模拟面试 · {{ typeLabel }}</span>
              </div>
            </div>
            <p class="scorecard__summary">{{ report.summary }}</p>
          </section>

          <!-- 维度 -->
          <section class="panel dims rise">
            <header class="panel__head">
              <span class="mono mono--ver">维度 / DIMENSIONS</span>
              <span class="mono">共 {{ report.dimensions.length }} 项</span>
            </header>
            <ul class="dims__list">
              <li v-for="d in report.dimensions" :key="d.name" class="dim">
                <div class="dim__top">
                  <span class="dim__name">{{ d.name }}</span>
                  <span class="dim__score" :class="dimTone(d.score)">{{ d.score }}</span>
                </div>
                <div class="dim__track">
                  <span class="dim__fill" :class="dimTone(d.score)" :style="{ width: d.score + '%' }" />
                </div>
                <p class="dim__comment">{{ d.comment }}</p>
              </li>
            </ul>
          </section>

          <!-- 亮点 -->
          <section v-if="report.highlights.length" class="panel points points--hit rise">
            <header class="panel__head">
              <span class="mono mono--pine">答得好 / HIGHLIGHTS</span>
              <span class="mono">{{ report.highlights.length }} 条</span>
            </header>
            <ul class="points__list">
              <li v-for="(h, i) in report.highlights" :key="i" class="point">
                <span class="point__idx mono">{{ String(i + 1).padStart(2, '0') }}</span>
                <div class="point__main">
                  <p class="point__req">{{ h.question }}</p>
                  <p class="point__detail">{{ h.comment }}</p>
                </div>
              </li>
            </ul>
          </section>

          <!-- 问题 -->
          <section v-if="report.issues.length" class="panel points points--gap rise">
            <header class="panel__head">
              <span class="mono mono--ver">没答好 / ISSUES</span>
              <span class="mono">{{ report.issues.length }} 条</span>
            </header>
            <ul class="points__list">
              <li v-for="(p, i) in report.issues" :key="i" class="point">
                <span class="point__idx mono">{{ String(i + 1).padStart(2, '0') }}</span>
                <div class="point__main">
                  <p class="point__req">{{ p.question }}</p>
                  <p class="point__detail">{{ p.comment }}</p>
                </div>
              </li>
            </ul>
          </section>

          <!-- 建议 -->
          <section v-if="report.suggestions.length" class="panel suggs rise">
            <header class="panel__head">
              <span class="mono mono--ver">改进建议 / SUGGESTIONS</span>
              <span class="mono">{{ report.suggestions.length }} 条</span>
            </header>
            <ul class="suggs__list">
              <li v-for="(s, i) in report.suggestions" :key="i" class="sugg">
                <span class="sugg__idx mono">{{ String(i + 1).padStart(2, '0') }}</span>
                <div class="sugg__main">
                  <span class="pri" :class="PRIORITY[s.priority]?.cls ?? 'pri--low'">
                    {{ PRIORITY[s.priority]?.label ?? s.priority }}
                  </span>
                  <p class="sugg__problem">{{ s.problem }}</p>
                  <p class="sugg__fix">{{ s.fix }}</p>
                </div>
              </li>
            </ul>
          </section>
        </template>
      </template>
    </div>

    <!-- 输入框只在面试进行中出现 -->
    <Composer
      v-if="phase === 'interview'"
      v-model="draft"
      :busy="busy"
      placeholder="输入你的回答，回车发送…"
      submit-label="回答"
      @submit="send"
      @stop="stop"
    />
  </section>
</template>

<style scoped>
.itv {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.itv__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 26px;
  border-bottom: 1px solid var(--rule);
}

.itv__id {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.itv__idval {
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

.itv__acts {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.itv__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 24px 26px 40px;
}

/* ---------- 准备段 ---------- */

.setup {
  max-width: 720px;
  width: 100%;
  padding: 6px 22px 22px;
}

.setup__field {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding-top: 18px;
}

.setup__label {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-3);
}

.setup__input {
  width: 100%;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-body);
  font-size: 14px;
  padding: 11px 13px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.setup__input:focus {
  outline: none;
  border-color: var(--vermilion);
  box-shadow: 0 0 0 3px var(--vermilion-wash);
}

.segs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.seg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  text-align: left;
  padding: 12px 13px;
  border: 1px solid var(--rule-2);
  border-radius: var(--r-sm);
  background: var(--paper-2);
  cursor: pointer;
  transition: all 0.16s ease;
}

.seg:hover:not(:disabled) {
  border-color: var(--vermilion);
}

.seg.is-on {
  border-color: var(--ink);
  background: var(--ink);
}

.seg:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.seg__label {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
}

.seg.is-on .seg__label {
  color: var(--paper-2);
}

.seg__hint {
  font-size: 11px;
  line-height: 1.5;
  color: var(--ink-3);
}

.seg.is-on .seg__hint {
  color: var(--paper-3);
}

.setup__submit {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--rule);
}

.setup__note {
  color: var(--ink-3);
}

.intro {
  max-width: 720px;
  width: 100%;
}

.intro .rule-x {
  margin-bottom: 16px;
}

.intro__desc {
  margin: 0;
  max-width: 74ch;
  color: var(--ink-3);
  font-size: 13.5px;
  line-height: 1.8;
}

.intro__desc strong {
  color: var(--ink);
}

.intro__err {
  margin: 14px 0 0;
  color: var(--vermilion-2);
  font-size: 13.5px;
}

/* ---------- 面试进行中 ---------- */

.thread {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 22px;
}

.thread__err {
  margin: 0;
  color: var(--vermilion-2);
  font-size: 13px;
}

/* ---------- 复盘 ---------- */

.error,
.scorecard,
.dims,
.points,
.suggs {
  max-width: 960px;
  width: 100%;
}

.error__msg {
  margin: 0;
  padding: 18px;
  color: var(--vermilion-2);
  font-size: 13.5px;
}

.scorecard {
  padding: 22px;
}

.scorecard__main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.scorecard__num {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.scorecard__val {
  font-family: var(--font-mono);
  font-size: 64px;
  font-weight: 700;
  line-height: 1;
  letter-spacing: -0.03em;
  color: var(--ink);
}

.scorecard__val.score--pine {
  color: var(--pine);
}

.scorecard__val.score--amber {
  color: var(--amber);
}

.scorecard__val.score--ver {
  color: var(--vermilion);
}

.scorecard__unit {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--ink-3);
}

.scorecard__meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8px;
}

.scorecard__summary {
  margin: 16px 0 0;
  padding-top: 16px;
  border-top: 1px solid var(--rule);
  font-size: 14.5px;
  line-height: 1.75;
  color: var(--ink-2);
}

.dims__list {
  list-style: none;
  margin: 0;
  padding: 6px 18px 14px;
}

.dim {
  padding: 12px 0;
  border-bottom: 1px dashed var(--rule);
}

.dim:last-child {
  border-bottom: 0;
}

.dim__top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

.dim__name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}

.dim__score {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
}

.dim__score.dim--pine {
  color: var(--pine);
}

.dim__score.dim--amber {
  color: var(--amber);
}

.dim__score.dim--ver {
  color: var(--vermilion);
}

.dim__track {
  height: 6px;
  margin: 7px 0 5px;
  background: var(--paper-3);
  border-radius: 3px;
  overflow: hidden;
}

.dim__fill {
  display: block;
  height: 100%;
  border-radius: 3px;
  transition: width 0.6s cubic-bezier(0.22, 1, 0.36, 1);
}

.dim__fill.dim--pine {
  background: var(--pine);
}

.dim__fill.dim--amber {
  background: var(--amber);
}

.dim__fill.dim--ver {
  background: var(--vermilion);
}

.dim__comment {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-3);
}

.points__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.point {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 12px;
  padding: 13px 18px;
  border-bottom: 1px dashed var(--rule);
}

.point:last-child {
  border-bottom: 0;
}

.point__idx {
  padding-top: 2px;
}

.points--hit .point__idx {
  color: var(--pine);
}

.points--gap .point__idx {
  color: var(--vermilion);
}

.point__main {
  display: flex;
  flex-direction: column;
  gap: 5px;
  min-width: 0;
}

.point__req {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
}

.point__req::before {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  margin-right: 6px;
}

.points--hit .point__req::before {
  content: '✓';
  color: var(--pine);
}

.points--gap .point__req::before {
  content: '✗';
  color: var(--vermilion);
}

.point__detail {
  margin: 0;
  font-size: 13px;
  color: var(--ink-3);
  line-height: 1.65;
  padding-left: 12px;
  border-left: 2px solid var(--rule-2);
}

.suggs__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.sugg {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 12px;
  padding: 14px 18px;
  border-bottom: 1px dashed var(--rule);
}

.sugg:last-child {
  border-bottom: 0;
}

.sugg__idx {
  color: var(--vermilion);
  padding-top: 2px;
}

.sugg__main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.pri {
  align-self: flex-start;
  display: inline-flex;
  padding: 1px 7px;
  border: 1px solid var(--rule-2);
  border-radius: var(--r-sm);
  font-family: var(--font-mono);
  font-size: 9.5px;
  letter-spacing: 0.08em;
}

.pri--high {
  border-color: var(--vermilion);
  color: var(--vermilion);
  background: var(--vermilion-wash);
}

.pri--medium {
  border-color: var(--amber);
  color: var(--amber);
}

.pri--low {
  border-color: var(--pine);
  color: var(--pine);
  background: var(--pine-wash);
}

.sugg__problem {
  margin: 0;
  font-size: 13.5px;
  color: var(--ink-2);
  line-height: 1.65;
}

.sugg__fix {
  margin: 0;
  font-size: 13px;
  color: var(--ink-3);
  line-height: 1.65;
  padding-left: 12px;
  border-left: 2px solid var(--rule-2);
}

.sugg__fix::before {
  content: '改法：';
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--pine);
}

@media (max-width: 760px) {
  .segs {
    grid-template-columns: 1fr;
  }
}
</style>
