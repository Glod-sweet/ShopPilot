<script setup lang="ts">
/**
 * 列表骨架屏。
 *
 * 刻意做成「行」而不是卡片占位：骨架的形状必须和真实内容一致，
 * 否则数据到位时会出现明显的版式跳动（CLS）。
 * 行数固定 6 条——够暗示「这是一张长列表」，又不会让等待显得更长。
 */
withDefaults(defineProps<{ rows?: number }>(), { rows: 6 })
</script>

<template>
  <div class="skeleton" aria-hidden="true">
    <div v-for="i in rows" :key="i" class="sk-row">
      <span class="sk-no" />
      <span class="sk-tile" />
      <span class="sk-main">
        <span class="sk-bar w-60" />
        <span class="sk-bar w-40 thin" />
      </span>
      <span class="sk-bar w-price" />
    </div>
    <p class="sk-note">正在读取商品数据…</p>
  </div>
</template>

<style scoped>
.skeleton {
  padding: 4px 0;
}

.sk-row {
  display: grid;
  grid-template-columns: 34px 42px minmax(0, 1fr) 108px;
  align-items: center;
  gap: 14px;
  padding: 14px 20px 14px 24px;
  border-bottom: 1px solid var(--line);
}

.sk-no {
  width: 16px;
  height: 9px;
  border-radius: 2px;
  background: #eceae3;
}

.sk-tile {
  width: 42px;
  height: 42px;
  border-radius: 9px;
  background: #eceae3;
}

.sk-main {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.sk-bar {
  height: 11px;
  border-radius: 3px;
  background: linear-gradient(90deg, #eceae3 0%, #f3f1eb 50%, #eceae3 100%);
  background-size: 200% 100%;
  animation: shimmer 1.4s ease-in-out infinite;
}

.sk-bar.thin {
  height: 9px;
}

.w-60 {
  width: 60%;
}

.w-40 {
  width: 38%;
}

.w-price {
  width: 72px;
  justify-self: end;
}

@keyframes shimmer {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}

.sk-note {
  margin: 14px 0 0;
  text-align: center;
  font-size: 11px;
  letter-spacing: 0.1em;
  color: var(--text-faint);
}
</style>
