<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 顶部导航。两个入口对应两条业务链路，文案上就说清楚区别 */
const navs = [
  { name: 'catalog', to: '/', label: '商品目录', hint: '人找货' },
  { name: 'chat', to: '/chat', label: '对话导购', hint: '货找人' },
]

const current = computed(() => route.name)
</script>

<template>
  <div class="app">
    <header class="appbar">
      <RouterLink to="/" class="brand">
        <span class="mark">SP</span>
        <span class="brand-text">
          <strong>ShopPilot</strong>
          <em>购航</em>
        </span>
      </RouterLink>

      <nav class="nav">
        <RouterLink
          v-for="n in navs"
          :key="n.name"
          :to="n.to"
          class="nav-item"
          :class="{ on: current === n.name }"
        >
          <span class="nav-label">{{ n.label }}</span>
          <span class="nav-hint">{{ n.hint }}</span>
        </RouterLink>
      </nav>
    </header>

    <RouterView v-slot="{ Component }">
      <component :is="Component" />
    </RouterView>
  </div>
</template>

<style scoped>
.app {
  display: flex;
  flex-direction: column;
  height: 100%;
}

.appbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 28px;
  height: 58px;
  flex: 0 0 58px;
  border-bottom: 1px solid var(--border);
  background: var(--surface);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  color: inherit;
}

.mark {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 7px;
  background: var(--ink);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.brand-text {
  display: flex;
  align-items: baseline;
  gap: 7px;
}

.brand-text strong {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.brand-text em {
  font-style: normal;
  font-size: 11px;
  color: var(--text-muted);
  letter-spacing: 0.12em;
}

.nav {
  display: flex;
  gap: 4px;
}

.nav-item {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 8px;
  text-decoration: none;
  color: var(--text-muted);
  transition: background 0.15s, color 0.15s;
}

.nav-item:hover {
  background: var(--bg);
  color: var(--text);
}

.nav-item.on {
  background: var(--bg);
  color: var(--text);
}

.nav-label {
  font-size: 13px;
  font-weight: 500;
}

.nav-hint {
  font-size: 10px;
  letter-spacing: 0.08em;
  opacity: 0.65;
}
</style>
