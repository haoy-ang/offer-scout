<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import MessageComposer from '@/components/MessageComposer.vue'
import MessageCard from '@/components/MessageCard.vue'
import type { InterviewType, InterviewVerdict } from '@/api/client'
import {
  backToInterview as backToInterviewPhase,
  beginInterview,
  finishInterview,
  restartInterview,
  sendAnswer,
  stopAnswer,
  useInterviewSession,
} from '@/stores/interview'
import { hhmmss } from '@/utils/markdown'
import { cooldownSeconds } from '@/stores/cooldown'

/**
 * 模拟面试间。
 * <p>
 * 这是全站唯一一个「有状态的三段式」页面，用 phase 显式建模：
 *   setup → interview → report
 * 三段之间不靠 URL 承载状态，而是靠内存里的 sessionId 串起来。
 * <p>
 * 状态（phase / 已答记录 / 复盘）全部来自模块级 store（见 `@/stores/interview`）：
 * 一场面试恰恰是最经不起"顺手点一下别的功能"的 —— 状态若留在组件实例里，
 * 用户去别页瞄一眼，答到第 5 题也得从头再来。提到模块级后，切页只重建视图，面试能接着答，
 * 复盘也能回来再看。
 * <p>
 * <b>边界不变</b>：模块级状态只活在"当前这次页面加载"里 —— 按 F5 刷新依然等于重开一场面试，
 * 这是刻意为之（一场面试本来也不该跨刷新续上）。
 * <p>
 * 和后端的分工：前端只负责「收集岗位/类型 → 逐轮转述回答 → 展示复盘」，
 * 面试官的记忆、追问策略、复盘评分全在后端。所以每一项动作都要把岗位/类型原样带回后端
 * （后端是无状态的，不存这些）。
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

const { phase, role, type, turns, draft, busy, report, error } = useInterviewSession()

const listEl = ref<HTMLElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

/**
 * 限流冷却剩余秒数。开始面试与生成复盘都是 5 次/分钟，
 * 追问是 30 次/分钟 —— 额度不同，但冷却共用一条：撞了哪个都得等满窗口。
 */
const cooldown = cooldownSeconds

const typeLabel = computed(() => TYPES.find((t) => t.key === type.value)?.label ?? '技术面')
/** 已问答轮数 = 候选人回答的次数 */
const answered = computed(() => turns.value.filter((t) => t.role === 'user').length)
const canStart = computed(() => !busy.value && cooldown.value === 0 && role.value.trim().length > 0)

async function scrollToEnd() {
  await nextTick()
  const node = listEl.value
  if (node) node.scrollTop = node.scrollHeight
}

// deep：每轮问答都会往 turns 里 push，推完要跟到底
watch(turns, scrollToEnd, { deep: true })

// 从别页切回来时列表是新建的、停在顶部；补滚到底，接着上一轮的上下文看
onMounted(() => {
  void scrollToEnd()
})

/**
 * 复盘结论 → 视觉档位：PASS=松绿，PENDING=琥珀，REJECT=朱砂。
 * <p>
 * 这里的判据以前是**字符串嗅探**：`includes('不建议')` 先判否定、再判 `includes('通过')`。
 * 之所以要"先否定后肯定"，是因为模型可能输出「不通过」这种不带"不建议"三字的表述，
 * 一旦被 "通过" 抢先匹配就掉进绿档 —— 结论明明是"没过"却显示"通过"，
 * 这种错配色比不配色更误导人。
 * <p>
 * 现在 `verdict` 已经是后端枚举（PASS / PENDING / REJECT），配色变成**穷尽映射**：
 * 少写一个分支 TypeScript 直接报错，不需要靠判据顺序兜底。
 * 中文文案留在前端 —— 接口只传枚举名，改文案不影响契约。
 */
const VERDICT_VIEW: Record<InterviewVerdict, { text: string; cls: string; tag: string }> = {
  PASS: { text: '建议通过', cls: 'score--pine', tag: 'tag--pine' },
  PENDING: { text: '待定', cls: 'score--amber', tag: '' },
  REJECT: { text: '不建议通过', cls: 'score--ver', tag: 'tag--live' },
}

const verdict = computed(() => {
  const v = report.value?.verdict
  // 理论上 v 必然是三者之一；`??` 只是防"后端版本比前端旧"这种跨版本错配，不该发生但成本为零
  return (v ? VERDICT_VIEW[v] : undefined) ?? VERDICT_VIEW.PENDING
})

function dimTone(score: number): string {
  if (score >= 75) return 'dim--pine'
  if (score >= 60) return 'dim--amber'
  return 'dim--ver'
}

/*
 * 三段动作：真正的状态流转（phase / turns / report / 请求）都在 store 里，
 * 视图这边只负责滚动位置这类 UI 副作用。
 */

async function begin() {
  await beginInterview()
  await scrollToEnd()
}

async function send() {
  await sendAnswer()
  await scrollToEnd()
}

function stop() {
  stopAnswer()
}

async function finish() {
  await finishInterview()
  // 切到复盘段后回到顶部，先看总分和结论
  if (phase.value === 'report') {
    await nextTick()
    if (bodyEl.value) bodyEl.value.scrollTop = 0
  }
}

function backToInterview() {
  backToInterviewPhase()
  void scrollToEnd()
}

function restart() {
  restartInterview()
}
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
        <button
          v-if="phase === 'interview'"
          class="btn btn--ghost btn--tiny"
          :disabled="busy || cooldown > 0"
          @click="finish"
        >
          结束并复盘
        </button>
        <button v-if="phase === 'report'" class="btn btn--ghost btn--tiny" @click="backToInterview">
          回看对话
        </button>
        <button
          v-if="phase !== 'setup'"
          class="btn btn--ghost btn--tiny"
          :disabled="busy"
          @click="restart"
        >
          重新开始
        </button>
      </div>
    </div>

    <div ref="bodyEl" class="itv__body scroll">
      <!-- ============ 一、准备 ============ -->
      <template v-if="phase === 'setup'">
        <section class="panel setup rise">
          <header class="panel__head">
            <span class="mono mono--ver">模拟面试 / SETUP</span>
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
            <span v-if="cooldown > 0" class="mono setup__note setup__note--warn">
              触发限流了，{{ cooldown }} 秒后可再试
            </span>
            <span v-else class="mono setup__note"
              >一场面试 = 多轮追问，结束后由同一份记录生成复盘</span
            >
          </div>
        </section>

        <div class="intro rise">
          <hr class="rule-x" />
          <p class="intro__desc">
            面试官会<strong>一次只问一个问题</strong>，根据你的回答决定是追问还是换题，问满 6~8
            题后收尾。 面试过程中它不会点评 —— 所有评价集中在最后的复盘里：<strong
              >能不能过 + 四个维度打分 + 具体短板怎么补</strong
            >。 回答得越具体（讲清思路和取舍），复盘才有料可评。
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
                  <span
                    class="dim__fill"
                    :class="dimTone(d.score)"
                    :style="{ width: d.score + '%' }"
                  />
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
    <MessageComposer
      v-if="phase === 'interview'"
      v-model="draft"
      :busy="busy"
      :cooldown="cooldown"
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

/* 工具条：与对话页 / 作战规划页同一套（白底 + 同一内边距） */
.itv__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.5);
}

.itv__id {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.itv__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
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
  padding: 28px 28px 36px;
  /* 与对话页共用同一条 900px 内容轴 */
  padding-left: max(28px, calc((100% - 900px) / 2));
  padding-right: max(28px, calc((100% - 900px) / 2));
}

/* ---------- 准备段 ---------- */
/* 表单刻意收窄到 720px：一段单输入框的表单拉满 900px 会显得空旷，
   数据密集的复盘段才用满内容轴。 */

.setup {
  max-width: 720px;
  width: 100%;
  padding-bottom: 22px;
}

.setup__field {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 18px 22px 0;
}

.setup__label {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--fg-3);
}

.setup__input {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--fg);
  font-family: var(--font-sans);
  font-size: 14px;
  padding: 11px 13px;
  transition:
    border-color 0.15s var(--ease),
    box-shadow 0.15s var(--ease),
    background 0.15s var(--ease);
}

.setup__input:focus {
  outline: none;
  border-color: var(--brand);
  background: var(--surface);
  box-shadow: 0 0 0 3px var(--brand-ring);
}

.segs {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

/* 选中态从「整块变黑」改成「品牌浅底 + 品牌描边」——
   黑底块在浅色 SaaS 皮肤里太重，也和左栏/档案页的选中态不是一套语言。 */
.seg {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  text-align: left;
  padding: 13px 14px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-md);
  background: var(--surface);
  cursor: pointer;
  box-shadow: var(--sh-1);
  transition:
    transform 0.16s var(--ease),
    border-color 0.16s var(--ease),
    background 0.16s var(--ease),
    box-shadow 0.16s var(--ease);
}

.seg:hover:not(:disabled) {
  border-color: var(--brand);
  transform: translateY(-1px);
  box-shadow: var(--sh-2);
}

.seg.is-on {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: inset 0 0 0 1px var(--brand-ring);
}

.seg:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.seg__label {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg);
}

.seg.is-on .seg__label {
  color: var(--brand-3);
}

.seg__hint {
  font-size: 11px;
  line-height: 1.5;
  color: var(--fg-3);
}

.seg.is-on .seg__hint {
  color: var(--fg-2);
}

.setup__submit {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 22px 22px 0;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.setup__note {
  color: var(--fg-3);
}

/* 限流冷却：不是错误，用赭石提醒，别跟「开始面试」抢注意力 */
.setup__note--warn {
  color: var(--warn);
}

.intro {
  max-width: 720px;
  width: 100%;
}

.intro .rule-x {
  margin: 0 0 16px;
}

.intro__desc {
  margin: 0;
  max-width: 74ch;
  color: var(--fg-3);
  font-size: 14px;
  line-height: 1.8;
}

.intro__desc strong {
  color: var(--fg);
}

.intro__err {
  margin: 14px 0 0;
  color: var(--brand-2);
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
  color: var(--brand-2);
  font-size: 13px;
}

/* ---------- 复盘 ---------- */

.error,
.scorecard,
.dims,
.points,
.suggs {
  width: 100%;
}

.error__msg {
  margin: 0;
  padding: 18px;
  color: var(--brand-2);
  font-size: 13.5px;
}

.scorecard {
  padding: 24px;
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
  color: var(--fg);
  font-variant-numeric: tabular-nums;
}

.scorecard__val.score--pine {
  color: var(--teal);
}

.scorecard__val.score--amber {
  color: var(--warn);
}

.scorecard__val.score--ver {
  color: var(--brand);
}

.scorecard__unit {
  font-family: var(--font-mono);
  font-size: 13px;
  color: var(--fg-3);
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
  border-top: 1px solid var(--line);
  font-size: 14.5px;
  line-height: 1.78;
  color: var(--fg-2);
}

.dims__list {
  list-style: none;
  margin: 0;
  padding: 8px 20px 16px;
}

.dim {
  padding: 14px 0;
  border-bottom: 1px solid var(--line);
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
  color: var(--fg);
}

.dim__score {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.dim__score.dim--pine {
  color: var(--teal);
}

.dim__score.dim--amber {
  color: var(--warn);
}

.dim__score.dim--ver {
  color: var(--brand);
}

.dim__track {
  height: 6px;
  margin: 8px 0 6px;
  background: var(--surface-2);
  border-radius: var(--r-pill);
  overflow: hidden;
}

.dim__fill {
  display: block;
  height: 100%;
  border-radius: var(--r-pill);
  transition: width 0.6s var(--ease);
}

.dim__fill.dim--pine {
  background: var(--teal);
}

.dim__fill.dim--amber {
  background: var(--warn);
}

.dim__fill.dim--ver {
  background: var(--brand);
}

.dim__comment {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--fg-3);
}

.points__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.point {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 12px;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
}

.point:last-child {
  border-bottom: 0;
}

.point__idx {
  font-weight: 600;
  padding-top: 2px;
  font-variant-numeric: tabular-nums;
}

.points--hit .point__idx {
  color: var(--teal);
}

.points--gap .point__idx {
  color: var(--brand);
}

.point__main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

.point__req {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg);
  line-height: 1.6;
}

.point__req::before {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  margin-right: 6px;
}

.points--hit .point__req::before {
  content: '✓';
  color: var(--teal);
}

.points--gap .point__req::before {
  content: '✗';
  color: var(--brand);
}

.point__detail {
  margin: 0;
  font-size: 13px;
  color: var(--fg-3);
  line-height: 1.68;
  padding-left: 12px;
  border-left: 2px solid var(--line-2);
}

.points--hit .point__detail {
  border-left-color: var(--teal-edge);
}

.points--gap .point__detail {
  border-left-color: var(--brand-edge);
}

.suggs__list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}

.sugg {
  display: grid;
  grid-template-columns: 40px 1fr;
  gap: 12px;
  padding: 15px 20px;
  border-bottom: 1px solid var(--line);
}

.sugg:last-child {
  border-bottom: 0;
}

.sugg__idx {
  color: var(--brand);
  font-weight: 600;
  padding-top: 2px;
  font-variant-numeric: tabular-nums;
}

.sugg__main {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}

/* 优先级标签改药丸 + 浅底（与 .tag 同一套语言），不再是方角细边框 */
.pri {
  align-self: flex-start;
  display: inline-flex;
  padding: 2px 9px;
  border: 1px solid var(--line-2);
  border-radius: var(--r-pill);
  font-family: var(--font-mono);
  font-size: 9.5px;
  font-weight: 500;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.pri--high {
  border-color: transparent;
  color: var(--brand-2);
  background: var(--brand-soft);
  box-shadow: inset 0 0 0 1px rgba(229, 72, 77, 0.15);
}

.pri--medium {
  border-color: transparent;
  color: var(--warn);
  background: var(--warn-soft);
  box-shadow: inset 0 0 0 1px rgba(217, 119, 6, 0.2);
}

.pri--low {
  border-color: transparent;
  color: var(--teal-2);
  background: var(--teal-soft);
  box-shadow: inset 0 0 0 1px var(--teal-ring);
}

.sugg__problem {
  margin: 0;
  font-size: 13.5px;
  color: var(--fg-2);
  line-height: 1.68;
}

.sugg__fix {
  margin: 0;
  font-size: 13px;
  color: var(--fg-3);
  line-height: 1.68;
  padding-left: 12px;
  border-left: 2px solid var(--teal-edge);
}

.sugg__fix::before {
  content: '改法：';
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.06em;
  color: var(--teal);
}

@media (max-width: 760px) {
  .segs {
    grid-template-columns: 1fr;
  }
}
</style>
