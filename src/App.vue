<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import SideRail from '@/components/SideRail.vue'
import { hhmmss } from '@/utils/markdown'

const route = useRoute()

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

onMounted(() => {
  timer = window.setInterval(() => (clock.value = hhmmss()), 1000)
  probe()
})

onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer)
})
</script>

<template>
  <div class="shell">
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
  .topbar__hint {
    display: none;
  }
}
</style>
