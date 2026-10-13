<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** 从路由表直接生成导航，新增页面不用改这里 */
const navItems = computed(() =>
  router
    .getRoutes()
    .filter((r) => r.meta?.no && r.name)
    .sort((a, b) => String(a.meta.no).localeCompare(String(b.meta.no)))
    .map((r) => ({
      name: r.name as string,
      path: r.path,
      no: r.meta.no as string,
      label: r.meta.label as string,
    }))
)
</script>

<template>
  <aside class="rail">
    <RouterLink to="/chat" class="rail__brand" title="Offer 侦察兵">
      <span class="rail__glyph">OS</span>
      <span class="rail__brandbar" />
    </RouterLink>

    <nav class="rail__nav">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        :to="item.path"
        class="rail__item"
        :class="{ 'is-active': route.name === item.name }"
      >
        <span class="rail__no">{{ item.no }}</span>
        <span class="rail__label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="rail__foot">
      <span class="rail__dot" />
      <span class="mono rail__ver">v0.1</span>
    </div>
  </aside>
</template>

<style scoped>
.rail {
  position: relative;
  z-index: 3;
  width: var(--rail-w);
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px 0 14px;
  border-right: 1px solid var(--rule);
  background: rgba(250, 247, 240, 0.78);
  backdrop-filter: blur(8px);
}

/* 品牌：方块里两字母 + 朱砂压角 */
.rail__brand {
  position: relative;
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 1.5px solid var(--ink);
  border-radius: var(--r-sm);
  text-decoration: none;
  overflow: hidden;
}

.rail__glyph {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--ink);
}

.rail__brandbar {
  position: absolute;
  right: -1px;
  bottom: -1px;
  width: 11px;
  height: 11px;
  background: var(--vermilion);
}

.rail__nav {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 30px;
  flex: 1;
}

.rail__item {
  position: relative;
  width: 54px;
  padding: 13px 0 11px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 9px;
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  text-decoration: none;
  color: var(--ink-3);
  transition: color 0.16s ease, background 0.16s ease, border-color 0.16s ease;
}

.rail__item:hover {
  color: var(--ink);
  border-color: var(--rule-2);
  background: var(--paper-3);
}

.rail__item.is-active {
  color: var(--paper-2);
  background: var(--ink);
  border-color: var(--ink);
}

/* 竖排中文 —— 这是整条左栏的性格所在 */
.rail__label {
  writing-mode: vertical-rl;
  letter-spacing: 0.3em;
  font-size: 12.5px;
  font-weight: 600;
}

.rail__no {
  font-family: var(--font-mono);
  font-size: 9px;
  letter-spacing: 0.1em;
  opacity: 0.72;
}

.rail__item.is-active .rail__no {
  color: var(--vermilion-wash);
  opacity: 1;
}

.rail__foot {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.rail__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pine);
  box-shadow: 0 0 0 3px var(--pine-wash);
}

.rail__ver {
  writing-mode: vertical-rl;
  letter-spacing: 0.2em;
  font-size: 9px;
}
</style>
