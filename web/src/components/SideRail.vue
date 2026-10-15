<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

/** 从路由表直接生成导航，新增页面不用改这里 */
const navItems = computed(() =>
  router
    .getRoutes()
    // meta.no 只在这里当"业务页标记 + 排序键"用，界面上不再显示这个编号
    .filter((r) => r.meta?.no && r.name)
    .sort((a, b) => String(a.meta.no).localeCompare(String(b.meta.no)))
    .map((r) => ({
      name: r.name as string,
      path: r.path,
      label: r.meta.label as string,
    }))
)

/**
 * 导航图标。
 * <p>
 * 用「name → path 字符串」的映射，而不是在模板里写 6 个 <svg> 分支 ——
 * 加页面时只要多一行。全部统一在 24×24 视口、1.7 描边的线性风格里，
 * 保证一排图标粗细和圆角一致（图标风格不统一是界面"廉价感"的一大来源）。
 */
const ICONS: Record<string, string> = {
  chat: 'M21 15a2 2 0 0 1-2 2H8l-4 4V5a2 2 0 0 1 2-2h13a2 2 0 0 1 2 2z',
  agent: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM15.5 8.5l-1.9 5.1-5.1 1.9 1.9-5.1z',
  archive: 'M3 4h18v3.5H3zM5 7.5h14V19a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2zM10 12h4',
  resume: 'M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8zM14 3v5h5M9 13h6M9 17h4',
  match: 'M8 4 4 8l4 4M4 8h9M16 20l4-4-4-4M20 16h-9',
  interview:
    'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM19 11a7 7 0 0 1-14 0M12 18v3M8.5 21h7',
}

function iconOf(name: string) {
  return ICONS[name] ?? ICONS.chat
}
</script>

<template>
  <aside class="rail">
    <RouterLink to="/chat" class="brand" title="Offer 侦察兵">
      <span class="brand__mark">OS</span>
      <span class="brand__text">
        <span class="brand__name">Offer 侦察兵</span>
        <span class="brand__sub mono">SCOUT CONSOLE</span>
      </span>
    </RouterLink>

    <p class="rail__group mono">功能区</p>

    <nav class="rail__nav">
      <RouterLink
        v-for="item in navItems"
        :key="item.name"
        :to="item.path"
        class="nav-item"
        :class="{ 'is-active': route.name === item.name }"
      >
        <svg
          class="nav-item__icon"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          stroke-linecap="round"
          stroke-linejoin="round"
          aria-hidden="true"
        >
          <path :d="iconOf(item.name)" />
        </svg>
        <span class="nav-item__label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="rail__foot">
      <span class="rail__dot" />
      <span class="mono rail__ver">SPRING AI · v0.1</span>
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
  padding: 18px 14px 16px;
  border-right: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(14px) saturate(1.5);
}

/* --- 品牌区 --- */

.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 6px 8px;
  border-radius: var(--r-md);
  text-decoration: none;
  transition: background 0.16s var(--ease);
}

.brand:hover {
  background: var(--surface-2);
}

.brand__mark {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: var(--r-sm);
  background: var(--brand-grad);
  color: #fff;
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.03em;
  box-shadow: var(--sh-brand);
}

.brand__text {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.brand__name {
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--fg);
  white-space: nowrap;
}

.brand__sub {
  font-size: 9.5px;
  letter-spacing: 0.14em;
}

/* --- 导航 --- */

.rail__group {
  margin: 24px 0 8px;
  padding-left: 10px;
  font-size: 10px;
  letter-spacing: 0.16em;
}

.rail__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 9px 11px;
  border-radius: var(--r-sm);
  text-decoration: none;
  color: var(--fg-2);
  font-size: 13.5px;
  font-weight: 500;
  transition:
    background 0.16s var(--ease),
    color 0.16s var(--ease);
}

.nav-item:hover {
  background: var(--surface-2);
  color: var(--fg);
}

.nav-item__icon {
  width: 18px;
  height: 18px;
  flex: none;
  opacity: 0.85;
}

.nav-item__label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 选中态：品牌浅底 + 品牌字，左侧一条 3px 指示条贴到栏边 */
.nav-item.is-active {
  background: var(--brand-soft);
  color: var(--brand-3);
  font-weight: 600;
}

.nav-item.is-active::before {
  content: '';
  position: absolute;
  left: -14px;
  top: 50%;
  transform: translateY(-50%);
  width: 3px;
  height: 18px;
  border-radius: 0 3px 3px 0;
  background: var(--brand);
}

.nav-item.is-active .nav-item__icon {
  opacity: 1;
}

/* --- 底栏 --- */

.rail__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 10px 0;
  border-top: 1px solid var(--line);
}

.rail__dot {
  width: 6px;
  height: 6px;
  flex: none;
  border-radius: 50%;
  background: var(--teal);
  box-shadow: 0 0 0 3px var(--teal-soft);
}

.rail__ver {
  font-size: 9.5px;
  letter-spacing: 0.1em;
}

/*
 * 窄屏：左栏已收到 76px（见 base.css 的 --rail-w），这里把每一项从「横排」改成「上下叠」。
 * 横排时 76px 装不下图标 + 文字，标签会被 ellipsis 成空白；
 * 叠起来后每个入口还是认得出的（图标 + 两个字），并且仍然是一列，不改导航结构。
 */
@media (max-width: 860px) {
  .rail {
    padding: 12px 8px 12px;
  }

  /* 品牌只留方块、底部版本号收起 —— 窄屏每一像素都要留给导航 */
  .brand {
    justify-content: center;
    padding: 4px 0;
  }

  .brand__text,
  .rail__group,
  .rail__ver {
    display: none;
  }

  .rail__nav {
    gap: 4px;
  }

  .nav-item {
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 8px 2px;
    font-size: 11px;
  }

  .nav-item__label {
    /* 不再 ellipsis：两个字在 60px 内排得下，居中更好看 */
    flex: none;
    overflow: visible;
    text-align: center;
  }

  /* 选中态的左侧指示条贴着栏边，窄屏下位置没有意义，改成整块高亮就够 */
  .nav-item.is-active::before {
    display: none;
  }

  .rail__foot {
    justify-content: center;
    padding: 10px 0 0;
  }
}
</style>
