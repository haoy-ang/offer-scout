<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import SideRail from '@/components/SideRail.vue'
import { logout as logoutApi, me } from '@/api/client'
import { clearSession, currentUser, isLoggedIn, setUser } from '@/stores/session'
import { clearAllSessions } from '@/stores/teardown'
import { hhmmss } from '@/utils/markdown'

const route = useRoute()
const router = useRouter()

/** 登录页自成一套版式，不套左栏 + 顶栏的外壳 */
const bare = computed(() => route.meta.bare === true)

/*
 * 顶栏不再显示「§ 章节编号」。
 * meta.no 现在只作为左栏导航的筛选与排序依据存在（见 router/index.ts），不呈现给用户 ——
 * 所以这里也不需要读它了。
 */
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
const displayName = computed(() => currentUser.value?.nickname || currentUser.value?.username || '')

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
    // 各功能页的状态都活在模块作用域的 store 里（这是"切页不丢"的代价），
    // 登出时必须一起清掉 —— 否则换个账号登进来，上一任用户的 Agent 报告、
    // 简历诊断结果还挂在页面上。凭证和业务状态清理放在一起，别分两处。
    //
    // 这里显式调一次只是一道保险：clearSession() 会把 isLoggedIn 变成 false，
    // 下面的 watcher 同样会触发清理。两者都幂等，重复调没有副作用。
    clearAllSessions()

    clearSession()
    loggingOut.value = false
    await router.replace({ name: 'login' })
  }
}

/**
 * 登录态一旦变成「未登录」，就把所有业务状态清掉。
 * <p>
 * 这是本文件里最重要的一行保险。「登出」按钮只是掉登录态的其中一条路 ——
 * token 过期 12 小时、被服务端拉黑、在别处登出，走的是 {@code api/client.ts} 里
 * 的 401 处理，它只会 {@code clearSession()}，不会顺路去清那几个功能页的 store。
 * <p>
 * 把清理挂在「登录态」而不是「某个按钮」上，就只有这一个出口了：
 * 谁把 isLoggedIn 变成 false，谁就自动触发清理，将来新增掉线路径也不会漏。
 * <p>
 * 注意这个 watcher 只监听变化（不是 immediate）—— 首次加载时可能本来就是未登录，
 * 那属于正常初始状态，没有东西需要清。
 */
watch(isLoggedIn, (logged) => {
  if (!logged) clearAllSessions()
})

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
          <span class="topbar__label">{{ label }}</span>
        </div>

        <p class="topbar__hint">{{ hint }}</p>

        <div class="topbar__meta">
          <button
            class="tag"
            :class="{ 'tag--pine': health === 'up' }"
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
          <!--
            用 route.name 而不是 route.path 当 key。
            两者在本项目里当前都是"一路由一值"，行为等价；但 name 才是路由记录的稳定标识：
            将来若某个页面换成 alias / query 承载子模式（path 会变、name 不变），
            就不会因为 path 变化把组件整个重挂、连带清空组件内状态。
          -->
          <component :is="Component" :key="route.name" />
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
  /*
   * 必须显式声明行轨道，且最小值为 0。
   * 只写 grid-template-columns 时，行轨道是隐式的 `auto`，其最小尺寸是 `auto` —— 也就是
   * "不小于内容高度"。于是内容一高，整行就被撑高，`.main{height:100%}` 又是相对这一行解析的，
   * 高度链一路被拉长，最后 `.main` 从"视口高"变成"内容高"，里层滚动容器再也等不到溢出 → 页面不滚。
   * minmax(0, 1fr) 把行的最小尺寸压到 0、最大值锁死在可用空间，高度链就稳在视口高以内了。
   */
  grid-template-rows: minmax(0, 1fr);
  height: 100%;
}

.main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
}

/* 顶栏：白底毛玻璃。别改成纯白 —— 透出来的是背景那两团品牌光晕。 */
.topbar {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 18px;
  padding: 13px 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px) saturate(1.5);
  flex: none;
}

.topbar__crumb {
  display: flex;
  align-items: baseline;
  gap: 9px;
  white-space: nowrap;
}

.topbar__slash {
  color: var(--fg-4);
  font-family: var(--font-mono);
}

.topbar__label {
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--fg);
}

.topbar__hint {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar__hint::before {
  content: '— ';
  color: var(--fg-4);
}

.topbar__meta {
  display: flex;
  align-items: center;
  gap: 14px;
  white-space: nowrap;
}

.topbar__clock {
  font-size: 11px;
  color: var(--fg-2);
  font-variant-numeric: tabular-nums;
}

/* 后端探活指示灯 */
.beat {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--fg-4);
}

.beat[data-state='up'] {
  background: var(--teal);
  animation: pulse-ring 2.2s var(--ease) infinite;
}

.beat[data-state='down'] {
  background: var(--warn);
}

/* 当前用户：品牌渐变方块首字 + 昵称 */
.who {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 14px;
  border-left: 1px solid var(--line);
}

.who__avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-xs);
  background: var(--brand-grad);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 700;
  box-shadow: var(--sh-brand);
}

.who__name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--fg);
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
