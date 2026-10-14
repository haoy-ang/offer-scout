<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { hhmmss } from '@/utils/markdown'
import { cooldownSeconds } from '@/stores/cooldown'
import { resetResume, runResume, stopResume, useResumeSession } from '@/stores/resume'

/**
 * 简历诊断页。
 * <p>
 * 后端返回的是结构化结果，不是一段 Markdown 文本，所以这里不用 MessageCard 渲染，
 * 而是把总分 / 维度 / 建议拆成三块档案式面板展示 —— 比「让模型吐一段评测文字」
 * 更可读，也更好维护。
 * <p>
 * 状态（输入、结果、计时）来自模块级 store（见 `@/stores/resume`）：诊断一次要等十几秒，
 * 状态留在组件实例里就会随切页清零、只能重跑。这里只留 UI 专用的 DOM 引用与拖拽态。
 */

/** 优先级 → 展示文案 + 颜色档位。颜色语义：高=朱砂(要命)、中=赭石(注意)、低=松绿(可缓) */
const PRIORITY = {
  high: { label: '高优先', cls: 'pri--high' },
  medium: { label: '中优先', cls: 'pri--medium' },
  low: { label: '低优先', cls: 'pri--low' },
} as const

const { file, pastedText, targetJob, busy, result, error, stopped, elapsed, reportTime } =
  useResumeSession()

/** 限流冷却：剩余秒数 > 0 时把按钮禁住，并如实告诉用户还要等多久 */
const cooldown = cooldownSeconds

const dragging = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

/**
 * 前端就近拦截的超限提示。
 * <p>
 * 后端对上传体积的上限是 10MB，但那是"传到一半才打回"——用户得先白等一遍上传。
 * 这里就地挡掉，代价只是一次比较。
 */
const MAX_PDF_BYTES = 10 * 1024 * 1024
const fileError = ref('')

const fileName = computed(() => file.value?.name ?? '')
const hasInput = computed(() => !!file.value || pastedText.value.trim().length > 0)
/** 「已停止」也算有过一次动作，不该再把空态引导摆出来 */
const hasOutput = computed(() => !!result.value || !!error.value || stopped.value)

/** 把总分映射成一句档位评语，避免只甩一个冷冰冰的数字 */
const grade = computed(() => {
  const s = result.value?.overallScore ?? 0
  if (s >= 85) return { text: '优秀', cls: 'score--pine' }
  if (s >= 70) return { text: '良好', cls: 'score--pine' }
  if (s >= 55) return { text: '待打磨', cls: 'score--amber' }
  return { text: '需大改', cls: 'score--ver' }
})

/** 单维度分数 → 颜色档位（和总分的配色逻辑保持一致，一眼扫出短板） */
function dimTone(score: number): string {
  if (score >= 75) return 'dim--pine'
  if (score >= 60) return 'dim--amber'
  return 'dim--ver'
}

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 收下一个文件：先过体积这一关，不合格就留在原地并说明原因 */
function acceptFile(picked: File | null | undefined) {
  if (!picked) return
  if (picked.size > MAX_PDF_BYTES) {
    fileError.value = `这份 ${formatSize(picked.size)}，超过了 10 MB 的上限，换一份再试。`
    file.value = null
    return
  }
  fileError.value = ''
  file.value = picked
}

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  acceptFile(input.files?.[0])
  // 允许连续选择同一文件：清掉 value，否则再次选同一文件不触发 change
  input.value = ''
}

function pickFile() {
  if (busy.value) return
  fileInput.value?.click()
}

function dropFile(e: DragEvent) {
  dragging.value = false
  if (busy.value) return
  acceptFile(e.dataTransfer?.files?.[0])
}

function clearFile() {
  file.value = null
  fileError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function scrollToTop() {
  await nextTick()
  const node = bodyEl.value
  if (node) node.scrollTop = 0
}

/**
 * 跑一次诊断；成功出报告后回到顶部先看总分卡（原来的行为）。
 * 状态流转、计时、请求都在 store 里 —— 视图只负责"滚到该看的位置"这件 UI 事。
 */
async function run() {
  await runResume()
  if (!error.value && result.value) await scrollToTop()
}

function stop() {
  stopResume()
}

function reset() {
  resetResume()
}
</script>

<template>
  <section class="resume">
    <!-- 顶栏：当前材料 + 状态 -->
    <div class="resume__bar">
      <div class="resume__id">
        <span class="mono">材料</span>
        <code class="resume__idval">
          {{
            fileName ||
            (pastedText.trim() ? '粘贴文本 · ' + pastedText.trim().length + ' 字' : '尚未提供')
          }}
        </code>
      </div>
      <div class="resume__acts">
        <span class="mono">
          {{
            busy
              ? '诊断中'
              : result
                ? '已出报告'
                : stopped
                  ? '已停止'
                  : error
                    ? '待诊断 · 有提示'
                    : '待诊断'
          }}
          <template v-if="busy"> · {{ elapsed }}s</template>
        </span>
        <button
          class="btn btn--ghost btn--tiny"
          :disabled="busy || (!hasOutput && !hasInput)"
          @click="reset"
        >
          清空
        </button>
      </div>
    </div>

    <div ref="bodyEl" class="resume__body scroll">
      <!-- 输入面板：始终在顶部，方便「换一份简历再诊断」 -->
      <section class="panel input">
        <header class="panel__head">
          <span class="mono mono--ver">材料 / INPUT</span>
          <span class="mono">PDF 与粘贴文本二选一，PDF 优先</span>
        </header>

        <div class="input__grid">
          <!-- 左：文件上传 -->
          <div
            class="dropzone"
            :class="{ 'is-drag': dragging, 'has-file': !!file }"
            @click="pickFile"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="dropFile"
          >
            <input
              ref="fileInput"
              type="file"
              accept="application/pdf,.pdf"
              hidden
              @change="onFileChange"
            />

            <template v-if="!file">
              <span class="dropzone__glyph">PDF</span>
              <p class="dropzone__title">拖入简历，或点击选择文件</p>
              <p class="dropzone__hint">仅支持文字版 PDF（扫描件无法提取文字）</p>
            </template>
            <template v-else>
              <span class="dropzone__glyph dropzone__glyph--ok">✓</span>
              <p class="dropzone__title">{{ file.name }}</p>
              <p class="dropzone__hint">{{ formatSize(file.size) }} · 点击可替换</p>
              <button class="btn btn--ghost btn--tiny" type="button" @click.stop="clearFile">
                移除
              </button>
            </template>

            <p v-if="fileError" class="dropzone__err">{{ fileError }}</p>
          </div>

          <!-- 右：粘贴文本 + 目标岗位 + 提交 -->
          <div class="input__fields">
            <label class="input__label" for="resume-text">粘贴简历文本</label>
            <textarea
              id="resume-text"
              v-model="pastedText"
              class="input__ta"
              rows="7"
              placeholder="没有 PDF 时，直接把简历内容粘到这里……"
              :disabled="busy"
            />

            <label class="input__label" for="resume-job">目标岗位（可选）</label>
            <input
              id="resume-job"
              v-model="targetJob"
              class="input__text"
              type="text"
              placeholder="例如：Java 后端开发实习生（填了会让诊断更贴合岗位）"
              :disabled="busy"
            />

            <div class="input__submit">
              <!-- 跑起来后按钮换成「停止」：这是全站最慢的动作之一（结构化输出失败会重试），
                   没有喊停的口子，用户只能刷新页面 —— 而刷新会把材料一起清掉 -->
              <button v-if="busy" class="btn btn--stop" type="button" @click="stop">停止</button>
              <button
                v-else
                class="btn"
                type="button"
                :disabled="!hasInput || cooldown > 0"
                @click="run"
              >
                开始诊断
              </button>
              <span v-if="cooldown > 0" class="mono input__note input__note--warn">
                触发限流了，{{ cooldown }} 秒后可再试
              </span>
              <span v-else class="mono input__note">
                每次诊断 = 一次完整的大模型分析，每分钟限 5 次
              </span>
            </div>
          </div>
        </div>
      </section>

      <!-- 空态 -->
      <div v-if="!hasOutput" class="intro rise">
        <hr class="rule-x" />
        <p class="intro__desc">
          诊断会从<strong>技术栈清晰度、项目经历深度、量化表达、表达与结构</strong>四个维度逐项打分，
          并给出「把 XX 改成 XX」级别的可执行修改建议，而不是「多练、多写」的套话。
        </p>
      </div>

      <!-- 结果 -->
      <template v-else>
        <!-- 报错 / 用户主动停止：两者刻意分开措辞 —— 主动停下来不是失败，
             用同一个红色警示框去套它，用户会以为自己把什么搞坏了 -->
        <section v-if="error || stopped" class="panel rise" :class="stopped ? 'notice' : 'error'">
          <header class="panel__head">
            <span class="tag">{{ stopped ? '已停止' : '诊断失败' }}</span>
            <span class="mono">{{ hhmmss() }}</span>
          </header>
          <p :class="stopped ? 'notice__msg' : 'error__msg'">
            {{ stopped ? '你手动停了下来。材料还在，点「开始诊断」可以重来。' : error }}
          </p>
        </section>

        <!-- 总分卡 -->
        <section v-if="result" class="panel scorecard rise">
          <div class="scorecard__main">
            <div class="scorecard__num">
              <span class="scorecard__val" :class="grade.cls">{{ result.overallScore }}</span>
              <span class="scorecard__unit">/ 100</span>
            </div>
            <div class="scorecard__meta">
              <span
                class="tag"
                :class="
                  grade.cls === 'score--pine'
                    ? 'tag--pine'
                    : grade.cls === 'score--ver'
                      ? 'tag--live'
                      : ''
                "
              >
                {{ grade.text }}
              </span>
              <span class="mono">总评 · {{ reportTime }}</span>
            </div>
          </div>
          <p class="scorecard__summary">{{ result.summary }}</p>
        </section>

        <!-- 维度分 -->
        <section v-if="result" class="panel dims rise">
          <header class="panel__head">
            <span class="mono mono--ver">维度 / DIMENSIONS</span>
            <span class="mono">共 {{ result.dimensions.length }} 项</span>
          </header>
          <ul class="dims__list">
            <li v-for="d in result.dimensions" :key="d.name" class="dim">
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

        <!-- 修改建议 -->
        <section v-if="result" class="panel suggs rise">
          <header class="panel__head">
            <span class="mono mono--ver">修改建议 / SUGGESTIONS</span>
            <span class="mono">{{ result.suggestions.length }} 条</span>
          </header>
          <ul class="suggs__list">
            <li v-for="(s, i) in result.suggestions" :key="i" class="sugg">
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
    </div>
  </section>
</template>

<style scoped>
.resume {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* 工具条：与对话页 / 作战规划页同一套（白底 + 同一内边距） */
.resume__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.5);
}

.resume__id {
  display: flex;
  align-items: center;
  gap: 9px;
  min-width: 0;
}

.resume__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  color: var(--fg-2);
  background: var(--surface-2);
  border: 1px solid var(--line);
  border-radius: var(--r-xs);
  padding: 2px 8px;
  max-width: 56ch;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.resume__acts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
}

.resume__body {
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

/* ---------- 输入面板 ---------- */

.input {
  width: 100%;
}

.input__grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 22px;
  padding: 20px;
}

.input__label {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--fg-3);
}

.input__ta,
.input__text {
  width: 100%;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--surface-2);
  color: var(--fg);
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.65;
  padding: 10px 12px;
  resize: vertical;
  transition:
    border-color 0.15s var(--ease),
    box-shadow 0.15s var(--ease),
    background 0.15s var(--ease);
}

.input__ta:focus,
.input__text:focus {
  outline: none;
  border-color: var(--brand);
  background: var(--surface);
  box-shadow: 0 0 0 3px var(--brand-ring);
}

.input__text {
  font-size: 13px;
  padding: 8px 12px;
}

.input__fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.input__submit {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 6px;
}

.input__note {
  color: var(--fg-3);
}

/* 限流冷却不是错误，但也得让用户一眼看见「现在点不动、还要等几秒」 */
.input__note--warn {
  color: var(--warn);
}

/* 上传区：拖放区保留虚线（这是「可拖入」的通用视觉语言），
   只把圆角与底色对齐新皮肤，不再用旧「档案」调性的小圆角灰底 */
.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  text-align: center;
  padding: 28px 18px;
  border: 1.5px dashed var(--line-2);
  border-radius: var(--r-lg);
  background: var(--surface-2);
  cursor: pointer;
  transition:
    border-color 0.16s var(--ease),
    background 0.16s var(--ease);
}

.dropzone:hover,
.dropzone.is-drag {
  border-color: var(--brand);
  background: var(--brand-soft);
}

.dropzone.has-file {
  border-style: solid;
  border-color: var(--teal);
  background: var(--teal-soft);
}

.dropzone__glyph {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.dropzone__glyph--ok {
  color: var(--teal);
}

.dropzone__title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--fg);
  overflow-wrap: anywhere;
}

.dropzone__hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--fg-3);
}

/* 本地拦截（体积超限）就写在拖放区里，配上 --warn 让它跟上面的说明区分开 */
.dropzone__err {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--warn);
}

/* ---------- 空态 ---------- */

.intro {
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
  line-height: 1.75;
}

.intro__desc strong {
  color: var(--fg);
}

/* ---------- 错误 ---------- */

.error {
  width: 100%;
}

.error__msg {
  margin: 0;
  padding: 18px;
  color: var(--brand-2);
  font-size: 13.5px;
}

/* 「已停止」用中性说明框，而不是红色的失败框 —— 主动停下来不是错误 */
.notice__msg {
  margin: 0;
  padding: 18px;
  color: var(--fg-2);
  font-size: 13.5px;
}

/* ---------- 总分卡 ---------- */

.scorecard {
  width: 100%;
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

/* ---------- 维度 ---------- */

.dims {
  width: 100%;
}

.dims__list {
  list-style: none;
  margin: 0;
  padding: 8px 20px 16px;
}

/* 分隔线改实线：虚线是旧「档案」调性，在现在的皮肤里显得碎 */
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

/* ---------- 建议 ---------- */

.suggs {
  width: 100%;
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
  .input__grid {
    grid-template-columns: 1fr;
  }
}
</style>
