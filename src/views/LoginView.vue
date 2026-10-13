<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { login, register } from '@/api/client'
import { setSession } from '@/stores/session'

/**
 * 登录 / 注册页。
 * <p>
 * 一个页面同时承担两件事，用 tab 切换而不是两个路由：
 * 二者的表单字段高度重合（只差一个昵称），拆成两个页面会让"我该去注册还是登录"
 * 这个本来就很轻的决定变成一次跳转。
 */
const route = useRoute()
const router = useRouter()

type Mode = 'login' | 'register'
const mode = ref<Mode>('login')

const username = ref('')
const password = ref('')
const nickname = ref('')
const error = ref('')
const busy = ref(false)

const isRegister = computed(() => mode.value === 'register')

const submitText = computed(() => (busy.value ? '处理中…' : isRegister.value ? '注册并进入' : '登录'))

function switchMode(next: Mode) {
  if (busy.value) return
  mode.value = next
  // 切换时清掉上一个模式的报错，否则"用户名已被注册"会挂在新表单上误导人
  error.value = ''
}

/**
 * 前端先做一遍校验。
 * <p>
 * 后端同样会校验（那是真正的防线）—— 这里做只是为了"不用等一次网络往返才看到『密码太短』"。
 * 两者规则必须一致，所以注册的用户名规则直接照搬后端的 `^[a-zA-Z0-9_]{3,32}$`。
 */
function validate(): string {
  if (!username.value.trim()) return '请填写用户名'
  if (isRegister.value) {
    if (!/^[a-zA-Z0-9_]{3,32}$/.test(username.value.trim())) {
      return '用户名只能包含字母、数字和下划线，长度 3~32 位'
    }
  }
  if (!password.value) return '请填写密码'
  if (isRegister.value && password.value.length < 6) return '密码至少 6 位'
  return ''
}

async function onSubmit() {
  error.value = validate()
  if (error.value) return

  busy.value = true
  try {
    const result = isRegister.value
      ? await register(username.value.trim(), password.value, nickname.value.trim())
      : await login(username.value.trim(), password.value)

    // 先落凭证再跳转：守卫会读 isLoggedIn，顺序反了会被原地弹回登录页
    setSession(result.token, result.user)

    const redirect = route.query.redirect
    const target = typeof redirect === 'string' && redirect && !redirect.startsWith('/login')
      ? redirect
      : '/chat'
    await router.replace(target)
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="gate">
    <div class="gate__card panel rise">
      <header class="gate__head">
        <span class="gate__brand">
          <span class="gate__glyph">OS</span>
          <span class="gate__brandbar" />
        </span>
        <div class="gate__title">
          <p class="mono mono--ink">OFFER-SCOUT</p>
          <h1>Offer 侦察兵</h1>
        </div>
      </header>

      <p class="gate__lede">
        面向技术岗的求职作战助手 —— 简历诊断、知识库问答、自主检索规划。
        登录后你的对话会存进只属于你的档案里。
      </p>

      <div class="gate__tabs" role="tablist">
        <button
          type="button"
          class="gate__tab"
          :class="{ 'is-on': mode === 'login' }"
          :aria-selected="mode === 'login'"
          role="tab"
          @click="switchMode('login')"
        >
          登录
        </button>
        <button
          type="button"
          class="gate__tab"
          :class="{ 'is-on': mode === 'register' }"
          :aria-selected="mode === 'register'"
          role="tab"
          @click="switchMode('register')"
        >
          注册
        </button>
      </div>

      <form class="gate__form" @submit.prevent="onSubmit">
        <label class="field">
          <span class="mono field__label">USERNAME · 用户名</span>
          <input
            v-model="username"
            class="field__input"
            type="text"
            autocomplete="username"
            placeholder="字母 / 数字 / 下划线"
            :disabled="busy"
          />
        </label>

        <label class="field">
          <span class="mono field__label">PASSWORD · 密码</span>
          <input
            v-model="password"
            class="field__input"
            type="password"
            :autocomplete="isRegister ? 'new-password' : 'current-password'"
            placeholder="至少 6 位"
            :disabled="busy"
          />
        </label>

        <label v-if="isRegister" class="field">
          <span class="mono field__label">NICKNAME · 昵称（可不填）</span>
          <input
            v-model="nickname"
            class="field__input"
            type="text"
            placeholder="默认与用户名相同"
            :disabled="busy"
          />
        </label>

        <p v-if="error" class="gate__error" role="alert">{{ error }}</p>

        <button class="btn gate__submit" type="submit" :disabled="busy">
          {{ submitText }}
        </button>
      </form>

      <footer class="gate__foot">
        <span class="rule-x" />
        <p class="mono">
          {{ isRegister ? '已有账号？点上面的「登录」' : '还没有账号？点上面的「注册」' }}
        </p>
      </footer>
    </div>

    <p class="gate__note mono">SPRING BOOT · SPRING AI · REDIS · MYSQL</p>
  </div>
</template>

<style scoped>
.gate {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
  height: 100%;
  padding: 32px 20px;
}

.gate__card {
  width: 100%;
  max-width: 430px;
  padding: 30px 30px 22px;
  box-shadow: var(--shadow-2);
}

.gate__head {
  display: flex;
  align-items: center;
  gap: 14px;
}

/* 与左栏品牌方块同一套语言：细描边方块 + 朱砂压角 */
.gate__brand {
  position: relative;
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  flex: none;
  border: 1.5px solid var(--ink);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.gate__glyph {
  font-family: var(--font-mono);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.gate__brandbar {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 12px;
  height: 12px;
  background: var(--vermilion);
}

.gate__title h1 {
  margin: 2px 0 0;
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.01em;
}

.gate__lede {
  margin: 18px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-3);
}

.gate__tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  margin: 20px 0 18px;
  border-bottom: 1px solid var(--rule);
}

.gate__tab {
  padding: 9px 0;
  border: 0;
  border-bottom: 2px solid transparent;
  background: none;
  font-family: var(--font-mono);
  font-size: 11px;
  letter-spacing: 0.16em;
  color: var(--ink-3);
  cursor: pointer;
  transition: color 0.15s ease, border-color 0.15s ease;
}

.gate__tab:hover {
  color: var(--ink);
}

.gate__tab.is-on {
  color: var(--vermilion);
  border-bottom-color: var(--vermilion);
}

.gate__form {
  display: flex;
  flex-direction: column;
}

.field {
  display: block;
  margin-bottom: 14px;
}

.field__label {
  display: block;
  margin-bottom: 6px;
}

.field__input {
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--rule-2);
  border-radius: var(--r-sm);
  background: var(--paper);
  color: var(--ink);
  font-family: var(--font-body);
  font-size: 14px;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.field__input:focus {
  outline: none;
  border-color: var(--vermilion);
  box-shadow: 0 0 0 3px var(--vermilion-wash);
}

.field__input:disabled {
  opacity: 0.6;
}

.field__input::placeholder {
  color: var(--ink-3);
  opacity: 0.75;
}

.gate__error {
  margin: 2px 0 12px;
  padding: 8px 11px;
  border: 1px solid var(--vermilion);
  border-left-width: 3px;
  border-radius: var(--r-sm);
  background: var(--vermilion-wash);
  color: var(--vermilion-2);
  font-size: 12.5px;
}

.gate__submit {
  width: 100%;
  margin-top: 4px;
  padding: 11px 16px;
}

.gate__foot {
  margin-top: 20px;
}

.gate__foot .rule-x {
  margin-bottom: 12px;
}

.gate__foot p {
  margin: 0;
  text-align: center;
  letter-spacing: 0.06em;
}

.gate__note {
  letter-spacing: 0.22em;
  opacity: 0.6;
}
</style>
