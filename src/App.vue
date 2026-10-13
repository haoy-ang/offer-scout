<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SideRail from '@/components/SideRail.vue'
import { logout as logoutApi, me } from '@/api/client'
import { clearSession, currentUser, isLoggedIn, setUser } from '@/stores/session'
import { hhmmss } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()

/** 登录页自成一套版式，不套左栏 + 顶栏的外壳 */
const bare = computed(() => route.meta.bare === true)

const no = computed(() => (route.meta.no as string) ?? '--')
const label = computed(() => (route.meta.label as string) ?? '')
const hint = computed(() => (route.meta.hint as string) ?? '')

const clock = ref(hhmmss())
let timer: number | undefined

/** 后端连通性：进页面就探一次，挂了要让人一眼看见，而不是等发消息才报错 */
type Health = 'checking' | 'up' | 'down'
const health = ref<Health>('checking')

async function probe() {
  health.value = 'checking'
  try {
    const res = await fetch('/api/health/check')
    health.value = res.ok ? 'up' : 'down'
  } catch {
    health.value = 'down'
  }
}

const healthText = computed(
  () => ({ checking: '探测中', up: '后端在线', down: '后端离线' })[health.value]
)

/** 顶栏显示昵称，没有昵称就用用户名 */
const displayName = computed(
  () => currentUser.value?.nickname || currentUser.value?.username || ''
)

/** 没有头像，用昵称首字当标识 —— 比一个通用小人图标更有信息量 */
const initial = computed(() => displayName.value.slice(0, 1).toUpperCase() || '?')

const loggingOut = ref(false)

/**
 * 刷新页面后恢复用户信息。
 * <p>
 * token 在 localStorage 里还在，但内存里的 user 丢了，顶栏会空一块。
 * 顺便这也是一次「token 还有效吗」的真实校验：如果它已过期或被登出拉黑，
 * 这里会拿到 401，由 api 层统一清凭证并跳登录页 —— 不用页面自己处理。
 */
async function restoreUser() {
  if (!isLoggedIn.value || currentUser.value) return
  try {
    setUser(await me())
  } catch {
    // 401 已在 api 层处理（会跳登录页）；其它错误就让它静默，不打断使用
  }
}

async function onLogout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try {
    // 先让服务端把这个 token 拉黑，再清本地 —— 顺序反了的话，
    // 万一请求失败，本地凭证已经没了，那个 token 却还能用到过期
    await logoutApi()
  } catch {
    // 网络异常时依然要能把人放走，不能因为"登出接口没调通"就卡在登录态
  } finally {
    clearSession()
    loggingOut.value = false
    await router.replace({ name: 'login' })
  }
}

onMounted(() => {
  timer = window.setInterval(() => (clock.value = hhmmss()), 1000)
  probe()
  void restoreUser()
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <!-- 登录页：只有内容，没有外壳 -->
  <RouterView v-if="bare" />

  <div v-else class="shell">
    <SideRail />

    <div class="main">
      <header class="topbar">
        <div class="topbar__crumb">
          <span class="mono mono--ink">OFFER-SCOUT</span>
          <span class="topbar__slash">/</span>
          <span class="sec-no">§ {{ no }}</span>
          <span class="topbar__label">{{ label }}</span>
        </div>

        <p class="topbar__hint">{{ hint }}</p>

        <div class="topbar__meta">
          <button
            class="tag"
            :class="{ 'tag--live': health === 'up' }"
            :title="healthText"
            @click="probe"
          >
            <span class="beat" :data-state="health" />
            {{ healthText }}
          </button>
          <span class="mono topbar__clock">{{ clock }}</span>

          <span class="who" :title="displayName">
            <span class="who__avatar">{{ initial }}</span>
            <span class="who__name">{{ displayName }}</span>
          </span>
          <button
            class="btn btn--ghost btn--tiny"
            type="button"
            :disabled="loggingOut"
            title="登出后当前 token 会被服务端拉黑"
            @click="onLogout"
          >
            {{ loggingOut ? '…' : '登出' }}
          </button>
        </div>
      </header>

      <main class="stage">
        <RouterView v-slot="{ Component }">
          <component :is="Component" :key="route.path" />
        </RouterView>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: var(--rail-w) 1fr;
  height: 100%;
}

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}

.topbar {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 18px;
  padding: 14px 26px;
  border-bottom: 1px solid var(--rule);
  background: rgba(242, 237, 227, 0.72);
  backdrop-filter: blur(8px);
  flex: none;
}

.topbar__crumb {
  display: flex;
  align-items: baseline;
  gap: 9px;
  white-space: nowrap;
}

.topbar__slash {
  color: var(--rule-2);
  font-family: var(--font-mono);
}

.topbar__label {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--ink);
}

.topbar__hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--ink-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar__hint::before {
  content: '— ';
  color: var(--rule-2);
}

.topbar__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  white-space: nowrap;
}

.topbar__clock {
  font-size: 11px;
  color: var(--ink-2);
  font-variant-numeric: tabular-nums;
}

.beat {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ink-3);
}

.beat[data-state='up'] {
  background: var(--vermilion);
  animation: blink 1.6s steps(1) infinite;
}

.beat[data-state='down'] {
  background: var(--amber);
}

/* 当前用户：方块首字 + 昵称 */
.who {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 14px;
  border-left: 1px solid var(--rule);
}

.who__avatar {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1px solid var(--ink);
  border-radius: var(--r-sm);
  background: var(--ink);
  color: var(--paper-2);
  font-family: var(--font-mono);
  font-size: 10px;
  font-weight: 700;
}

.who__name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--ink);
}

.stage {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

@media (max-width: 900px) {
  .topbar {
    grid-template-columns: 1fr auto;
  }
  .topbar__hint,
  .who__name {
    display: none;
  }
}
</style>
