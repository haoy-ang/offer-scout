<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { analyzeMatch, type JobMatch } from '@/api/client'
import { hhmmss } from '@/utils/markdown'

/**
 * 岗位匹配页。
 * <p>
 * 和简历诊断的区别：这里多一个 JD 输入，结果多「命中点 / 差距点」两块 ——
 * 匹配的核心价值就是把「JD 要什么」和「你有什么 / 缺什么」逐条对出来，
 * 所以这两块放在最显眼的位置，比维度分和总分更重要。
 */

const PRIORITY = {
  high: { label: '高优先', cls: 'pri--high' },
  medium: { label: '中优先', cls: 'pri--medium' },
  low: { label: '低优先', cls: 'pri--low' },
} as const

const file = ref<File | null>(null)
const resumeText = ref('')
const jd = ref('')
const busy = ref(false)
const result = ref<JobMatch | null>(null)
const error = ref('')
const elapsed = ref(0)
const dragging = ref(false)
const reportTime = ref('')

const fileInput = ref<HTMLInputElement | null>(null)
const bodyEl = ref<HTMLElement | null>(null)

let ticker: number | undefined

const fileName = computed(() => file.value?.name ?? '')
const hasResume = computed(() => !!file.value || resumeText.value.trim().length > 0)
const hasInput = computed(() => hasResume.value && jd.value.trim().length > 0)
const hasOutput = computed(() => !!result.value || !!error.value)

/** 匹配度 → 档位评语。判据严格：核心要求没覆盖就该低分，别给人情分 */
const grade = computed(() => {
  const s = result.value?.overallScore ?? 0
  if (s >= 85) return { text: '高度匹配', cls: 'score--pine' }
  if (s >= 70) return { text: '值得投', cls: 'score--pine' }
  if (s >= 55) return { text: '可冲但要补短板', cls: 'score--amber' }
  return { text: '差距较大', cls: 'score--ver' }
})

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
  if (!hasResume.value) {
    error.value = '请先上传一份 PDF 简历，或直接粘贴简历文本。'
    return
  }
  if (!jd.value.trim()) {
    error.value = '请粘贴目标岗位的 JD（职位描述）。'
    return
  }

  busy.value = true
  result.value = null
  error.value = ''
  reportTime.value = ''
  startTicker()

  try {
    result.value = await analyzeMatch({
      file: file.value,
      text: resumeText.value.trim() || undefined,
      jd: jd.value.trim(),
    })
    reportTime.value = hhmmss()
    scrollToTop()
  } catch (e) {
    error.value = (e as Error).message || '匹配失败，请稍后重试。'
  } finally {
    busy.value = false
    stopTicker()
  }
}

function reset() {
  if (busy.value) return
  file.value = null
  resumeText.value = ''
  jd.value = ''
  result.value = null
  error.value = ''
  reportTime.value = ''
  elapsed.value = 0
}

onBeforeUnmount(stopTicker)
</script>

<template>
  <section class="match">
    <!-- 顶栏 -->
    <div class="match__bar">
      <div class="match__id">
        <span class="mono">对照</span>
        <code class="match__idval">
          {{ fileName || (resumeText.trim() ? '简历文本' : '简历未提供') }} · JD
          {{ jd.trim() ? jd.trim().length + ' 字' : '未提供' }}
        </code>
      </div>
      <div class="match__acts">
        <span class="mono">
          {{ busy ? '匹配中' : hasOutput ? '已出报告' : '待匹配' }}
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

    <div ref="bodyEl" class="match__body scroll">
      <!-- 输入面板 -->
      <section class="panel input">
        <header class="panel__head">
          <span class="mono mono--ver">材料 / INPUT</span>
          <span class="mono">简历（PDF 或文本）+ JD（必填）</span>
        </header>

        <div class="input__resume">
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
              <p class="dropzone__title">拖入简历，或点击选择</p>
              <p class="dropzone__hint">文字版 PDF（扫描件无法提取）</p>
            </template>
            <template v-else>
              <span class="dropzone__glyph dropzone__glyph--ok">✓</span>
              <p class="dropzone__title">{{ file.name }}</p>
              <p class="dropzone__hint">{{ formatSize(file.size) }} · 点击可替换</p>
              <button class="btn btn--ghost btn--tiny" type="button" @click.stop="clearFile">
                移除
              </button>
            </template>
          </div>

          <div class="input__fields">
            <label class="input__label" for="match-resume">简历文本（没有 PDF 时粘贴到这里）</label>
            <textarea
              id="match-resume"
              v-model="resumeText"
              class="input__ta"
              rows="6"
              placeholder="直接粘贴简历内容……"
              :disabled="busy"
            />
          </div>
        </div>

        <div class="input__jd">
          <label class="input__label" for="match-jd">目标岗位 JD（职位描述，必填）</label>
          <textarea
            id="match-jd"
            v-model="jd"
            class="input__ta"
            rows="7"
            placeholder="把岗位 JD 整段粘进来：岗位职责 + 任职要求……"
            :disabled="busy"
          />
          <div class="input__submit">
            <button class="btn" type="button" :disabled="busy || !hasInput" @click="run">
              {{ busy ? '匹配中…' : '开始匹配' }}
            </button>
            <span class="mono input__note">每次匹配 = 简历 + JD 全文的完整分析，每分钟限 5 次</span>
          </div>
        </div>
      </section>

      <!-- 空态 -->
      <div v-if="!hasOutput" class="intro rise">
        <hr class="rule-x" />
        <p class="intro__desc">
          把一份岗位 JD 贴进来，AI
          会逐条对照你的简历，告诉你<strong>哪些要求命中了、哪些还差着</strong>， 再给出「补哪个项目
          / 改哪段经历」能直接提高通过率的具体动作。
        </p>
      </div>

      <!-- 结果 -->
      <template v-else>
        <section v-if="error" class="panel error rise">
          <header class="panel__head">
            <span class="tag">匹配失败</span>
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
              <span class="mono">匹配度 · {{ reportTime }}</span>
            </div>
          </div>
          <p class="scorecard__summary">{{ result.summary }}</p>
        </section>

        <!-- 命中点 -->
        <section v-if="result" class="panel points points--hit rise">
          <header class="panel__head">
            <span class="mono mono--pine">命中 / MATCHED</span>
            <span class="mono">{{ result.strengths.length }} 条</span>
          </header>
          <ul class="points__list">
            <li v-for="(s, i) in result.strengths" :key="i" class="point">
              <span class="point__idx mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <div class="point__main">
                <p class="point__req">JD 要求：{{ s.requirement }}</p>
                <p class="point__detail">{{ s.detail }}</p>
              </div>
            </li>
          </ul>
        </section>

        <!-- 差距点 -->
        <section v-if="result" class="panel points points--gap rise">
          <header class="panel__head">
            <span class="mono mono--ver">差距 / GAPS</span>
            <span class="mono">{{ result.gaps.length }} 条</span>
          </header>
          <ul class="points__list">
            <li v-for="(g, i) in result.gaps" :key="i" class="point">
              <span class="point__idx mono">{{ String(i + 1).padStart(2, '0') }}</span>
              <div class="point__main">
                <p class="point__req">JD 要求：{{ g.requirement }}</p>
                <p class="point__detail">{{ g.detail }}</p>
              </div>
            </li>
          </ul>
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

        <!-- 建议 -->
        <section v-if="result" class="panel suggs rise">
          <header class="panel__head">
            <span class="mono mono--ver">改进建议 / SUGGESTIONS</span>
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
.match {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.match__bar {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 9px 26px;
  border-bottom: 1px solid var(--rule);
}

.match__id {
  display: flex;
  align-items: baseline;
  gap: 9px;
  min-width: 0;
}

.match__idval {
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

.match__acts {
  display: flex;
  align-items: center;
  gap: 14px;
  flex: none;
}

.match__body {
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

.input__resume {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 22px;
  padding: 18px 18px 0;
}

.input__label {
  font-family: var(--font-mono);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--ink-3);
}

.input__ta {
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
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}

.input__ta:focus {
  outline: none;
  border-color: var(--vermilion);
  box-shadow: 0 0 0 3px var(--vermilion-wash);
}

.input__fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.input__jd {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px;
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
  transition:
    border-color 0.16s ease,
    background 0.16s ease;
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

/* ---------- 命中 / 差距 ---------- */

.points {
  max-width: 960px;
  width: 100%;
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
  .input__resume {
    grid-template-columns: 1fr;
  }
}
</style>
