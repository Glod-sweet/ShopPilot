<script setup lang="ts">
/**
 * 空数据态。
 *
 * 和「失败」严格区分：这里请求是成功的，只是条件没有命中任何商品。
 * 所以文案要给「怎么改条件」的出口，而不是「重试」——重试同样的条件只会再空一次。
 * 三种成因（关键词无匹配 / 价格区间过窄 / 品类下无在售）分别给不同的建议。
 */
defineProps<{
  keyword?: string
  hasFilter: boolean
}>()

defineEmits<{ clear: [] }>()
</script>

<template>
  <div class="empty">
    <div class="glyph" aria-hidden="true">
      <svg viewBox="0 0 44 44" width="44" height="44">
        <rect x="8" y="10" width="28" height="24" rx="3" fill="none" stroke="currentColor" stroke-width="1.2" />
        <path d="M8 18h28" stroke="currentColor" stroke-width="1.2" opacity="0.5" />
        <circle cx="22" cy="27" r="3.4" fill="none" stroke="currentColor" stroke-width="1.2" opacity="0.7" />
        <path d="m24.6 29.6 2.4 2.4" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" opacity="0.7" />
      </svg>
    </div>

    <h3>没有符合条件的商品</h3>
    <p v-if="keyword" class="msg">
      关键词「<b>{{ keyword }}</b>」在当前筛选下没有命中任何在售商品。
    </p>
    <p v-else-if="hasFilter" class="msg">当前筛选组合下没有在售商品，条件可能收得太紧了。</p>
    <p v-else class="msg">商品库暂时没有在售商品，请确认种子数据已导入。</p>

    <div class="acts">
      <button v-if="hasFilter" class="ghost" @click="$emit('clear')">清空筛选</button>
      <RouterLink to="/chat" class="link">换成对话描述需求 →</RouterLink>
    </div>

    <p class="hint">请求本身是成功的（total = 0），不是接口故障。</p>
  </div>
</template>

<style scoped>
.empty {
  padding: 48px 32px;
  text-align: center;
}

.glyph {
  display: grid;
  place-items: center;
  margin-bottom: 12px;
  color: var(--text-faint);
}

h3 {
  margin: 0 0 6px;
  font-size: 15px;
  font-weight: 500;
}

.msg {
  margin: 0 0 20px;
  font-size: 13px;
  color: var(--text-muted);
}

.msg b {
  font-weight: 500;
  color: var(--text);
}

.acts {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.ghost {
  padding: 7px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}

.ghost:hover {
  border-color: var(--green);
  color: var(--green);
}

.link {
  font-size: 12.5px;
  color: var(--green);
  text-decoration: none;
  border-bottom: 1px solid transparent;
}

.link:hover {
  border-bottom-color: var(--green);
}

.hint {
  margin: 22px 0 0;
  font-family: var(--font-num);
  font-size: 10.5px;
  color: var(--text-faint);
  letter-spacing: 0.02em;
}
</style>
