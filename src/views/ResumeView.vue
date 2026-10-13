<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { analyzeResume, type ResumeDiagnosis } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 简历诊断页。
 * <p>
 * 后端返回的是结构化的 {@link ResumeDiagnosis}，不是一段 Markdown 文本，
 * 所以这里不需要 MessageCard 渲染，而是把总分 / 维度 / 建议拆成三块档案式面板展示。
 * 这样比「让模型吐一段评测文字」更可读，也更好维护。
 */

/** 优先级 → 展示文案 + 颜色档位。颜色语义：高=朱砂(要命)、中=赭石(注意)、低=松绿(可缓) */
const PRIORITY = {
  high: { label: '高优先', cls: 'pri--high' },
  medium: { label: '中优先', cls: 'pri--medium' },
  low: { label: '低优先', cls: 'pri--low' },
} as const

const file = ref<File | null>(null)
const pastedText = ref('')
const targetJob = ref('')
const busy = ref(false)
const result = ref<ResumeDiagnosis | null>(null)
const error = ref('')
const elapsed = ref(0)
const dragging = ref(false)
const reportTime = ref('')

const fileInput = ref<HTMLInputElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

let ticker: number | undefined

const fileName = computed(() => file.value?.name ?? '')
const hasInput = computed(() => !!file.value || pastedText.value.trim().length > 0)
const hasOutput = computed(() => !!result.value || !!error.value)

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

function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const picked = input.files?.[0]
  if (picked) file.value = picked
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
  const picked = e.dataTransfer?.files?.[0]
  if (picked) file.value = picked
}

function clearFile() {
  file.value = null
  if (fileInput.value) fileInput.value.value = ''
}

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

async function scrollToTop() {
  await nextTick()
  const node = bodyEl.value
  if (node) node.scrollTop = 0
}

async function run() {
  if (busy.value) return
  if (!file.value && !pastedText.value.trim()) {
    error.value = '请先上传一份 PDF 简历，或直接粘贴简历文本。'
    return
  }

  busy.value = true
  result.value = null
  error.value = ''
  reportTime.value = ''
  startTicker()

  try {
    result.value = await analyzeResume({
      file: file.value,
      text: pastedText.value.trim() || undefined,
      targetJob: targetJob.value.trim() || undefined,
    })
    reportTime.value = hhmmss()
    scrollToTop()
  } catch (e) {
    error.value = (e as Error).message || '诊断失败，请稍后重试。'
  } finally {
    busy.value = false
    stopTicker()
  }
}

function reset() {
  if (busy.value) return
  file.value = null
  pastedText.value = ''
  targetJob.value = ''
  result.value = null
  error.value = ''
  reportTime.value = ''
  elapsed.value = 0
}

onBeforeUnmount(stopTicker)
</script>

<template>
  <section class="resume">
    <!-- 顶栏：当前材料 + 状态 -->
    <div class="resume__bar">
      <div class="resume__id">
        <span class="mono">材料</span>
        <code class="resume__idval">
          {{ fileName || (pastedText.trim() ? '粘贴文本 · ' + pastedText.trim().length + ' 字' : '尚未提供') }}
        </code>
      </div>
      <div class="resume__acts">
        <span class="mono">
          {{ busy ? '诊断中' : hasOutput ? '已出报告' : '待诊断' }}
          <template v-if="busy"> · {{ elapsed }}s</template>
        </span>
        <button class="btn btn--ghost btn--tiny" :disabled="busy || (!hasOutput && !hasInput)" @click="reset">
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
              <button class="btn btn--ghost btn--tiny" type="button" @click.stop="clearFile">移除</button>
            </template>
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
              <button class="btn" type="button" :disabled="busy || !hasInput" @click="run">
                {{ busy ? '诊断中…' : '开始诊断' }}
              </button>
              <span class="mono input__note">每次诊断 = 一次完整的大模型分析，每分钟限 5 次</span>
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
        <!-- 报错 -->
        <section v-if="error" class="panel error rise">
          <header class="panel__head">
            <span class="tag">诊断失败</span>
            <span class="mono">{{ hhmmss() }}</span>
          </header>
          <p class="error__msg">{{ error }}</p>
        </section>

        <!-- 总分卡 -->
        <section v-if="result" class="panel scorecard rise">
          <div class="scorecard__main">
            <div class="scorecard__num">
              <span class="scorecard__val" :class="grade.cls">{{ result.overallScore }}</span>
              <span class="scorecard__unit">/ 100</span>
            </div>
            <div class="scorecard__meta">
              <span class="tag" :class="grade.cls === 'score--pine' ? 'tag--pine' : grade.cls === 'score--ver' ? 'tag--live' : ''">
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
                <span class="dim__fill" :class="dimTone(d.score)" :style="{ width: d.score + '%' }" />
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

.resume__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 26px;
  border-bottom: 1px solid var(--rule);
}

.resume__id {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.resume__idval {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--ink-2);
  background: var(--paper-3);
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  padding: 1px 7px;
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
  padding: 24px 26px 40px;
}

/* ---------- 输入面板 ---------- */

.input {
  max-width: 960px;
  width: 100%;
}

.input__grid {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 22px;
  padding: 18px;
}

.input__label {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-3);
}

.input__ta,
.input__text {
  width: 100%;
  border: 1px solid var(--rule);
  border-radius: var(--r-sm);
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-body);
  font-size: 13px;
  line-height: 1.65;
  padding: 10px 12px;
  resize: vertical;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.input__ta:focus,
.input__text:focus {
  outline: none;
  border-color: var(--vermilion);
  box-shadow: 0 0 0 3px var(--vermilion-wash);
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
  color: var(--ink-3);
}

/* 上传区 */
.dropzone {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 7px;
  text-align: center;
  padding: 26px 18px;
  border: 1.5px dashed var(--rule-2);
  border-radius: var(--r-md);
  background: var(--paper);
  cursor: pointer;
  transition: border-color 0.16s ease, background 0.16s ease;
}

.dropzone:hover,
.dropzone.is-drag {
  border-color: var(--vermilion);
  background: var(--vermilion-wash);
}

.dropzone.has-file {
  border-style: solid;
  border-color: var(--pine);
  background: var(--pine-wash);
}

.dropzone__glyph {
  font-family: var(--font-mono);
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.06em;
  color: var(--ink-3);
}

.dropzone__glyph--ok {
  color: var(--pine);
}

.dropzone__title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink);
  overflow-wrap: anywhere;
}

.dropzone__hint {
  margin: 0;
  font-size: 11.5px;
  color: var(--ink-3);
}

/* ---------- 空态 ---------- */

.intro {
  max-width: 960px;
  width: 100%;
}

.intro .rule-x {
  margin-bottom: 16px;
}

.intro__desc {
  margin: 0;
  max-width: 72ch;
  color: var(--ink-3);
  font-size: 13.5px;
}

.intro__desc strong {
  color: var(--ink);
}

/* ---------- 错误 ---------- */

.error {
  max-width: 960px;
  width: 100%;
}

.error__msg {
  margin: 0;
  padding: 18px;
  color: var(--vermilion-2);
  font-size: 13.5px;
}

/* ---------- 总分卡 ---------- */

.scorecard {
  max-width: 960px;
  width: 100%;
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

/* ---------- 维度 ---------- */

.dims {
  max-width: 960px;
  width: 100%;
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

/* ---------- 建议 ---------- */

.suggs {
  max-width: 960px;
  width: 100%;
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
  .input__grid {
    grid-template-columns: 1fr;
  }
}
</style>
