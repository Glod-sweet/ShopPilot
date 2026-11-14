<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchCategories } from '../api/catalog'
import { useProductQuery } from '../composables/useProductQuery'
import type { CategoryNode, SortKey } from '../types/product'
import { money } from '../utils/format'
import ProductRow from '../components/catalog/ProductRow.vue'
import ListSkeleton from '../components/catalog/ListSkeleton.vue'
import EmptyState from '../components/catalog/EmptyState.vue'
import ErrorState from '../components/catalog/ErrorState.vue'

/**
 * 商品目录页。
 *
 * 布局思路：不用「左侧筛选栏 + 右侧卡片网格」的后台范式，改成
 * 「左侧窄索引 + 右侧账本」的编辑排版。
 * - 左栏只放品类，一条竖线串起来，像书目录而不是表单；
 * - 右栏是行式账本，序号 / 标题 / 已售 / 价格按列对齐，扫读路径单一；
 * - 筛选条件默认收起，进一个可折叠抽屉，页面首屏只留商品本身。
 *
 * 状态来源：条件一律以 URL query 为唯一事实来源。
 * 好处是刷新、分享、后退都能还原现场，联调时把一条 URL 发给后端就能复现。
 */

const route = useRoute()
const router = useRouter()

const { params, status, page, list, total, totalPages, error, meta, run, abort } = useProductQuery()

const categories = ref<CategoryNode[]>([])
const catsLoading = ref(true)
const catsError = ref<string | null>(null)

const filtersOpen = ref(false)
const keywordInput = ref('')
const priceInput = ref({ min: '', max: '' })

const SORTS: { key: SortKey; label: string }[] = [
  { key: 'sales', label: '销量' },
  { key: 'rating', label: '评分' },
  { key: 'price_asc', label: '价格↑' },
  { key: 'price_desc', label: '价格↓' },
]

const PRICE_PRESETS = [
  { label: '100 以下', min: undefined as number | undefined, max: 100 as number | undefined },
  { label: '100–300', min: 100, max: 300 },
  { label: '300–800', min: 300, max: 800 },
  { label: '800 以上', min: 800, max: undefined },
]

/** 当前选中品类及其父级，用于标题与左栏高亮 */
const selectedCat = computed(() => {
  const id = params.value.categoryId
  if (!id) return null
  for (const top of categories.value) {
    if (top.id === id) return { top, leaf: null as CategoryNode | null }
    const leaf = top.children.find((c) => c.id === id)
    if (leaf) return { top, leaf }
  }
  return null
})

const hasFilter = computed(
  () =>
    !!params.value.categoryId ||
    !!params.value.keyword ||
    params.value.minPrice != null ||
    params.value.maxPrice != null,
)

const scopeText = computed(() => {
  const s = selectedCat.value
  if (!s) return '全部在售商品'
  return s.leaf ? `${s.top.name} · ${s.leaf.name}` : s.top.name
})

const priceRangeText = computed(() => {
  const { minPrice, maxPrice } = params.value
  if (minPrice == null && maxPrice == null) return ''
  if (minPrice != null && maxPrice != null) return `¥${minPrice}–${maxPrice}`
  if (minPrice != null) return `¥${minPrice} 以上`
  return `¥${maxPrice} 以下`
})

const pageSum = computed(() => list.value.reduce((s, p) => s + p.price, 0))

// ---------------- URL ↔ 查询条件 ----------------

function numOrUndef(v: unknown): number | undefined {
  if (v === undefined || v === null || v === '') return undefined
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : undefined
}

/** 从 URL 还原查询条件。浏览器前进后退、手改地址栏都走这里 */
function readUrl(): void {
  const sp = route.query
  params.value = {
    categoryId: numOrUndef(sp.categoryId),
    keyword: typeof sp.keyword === 'string' && sp.keyword ? sp.keyword : undefined,
    minPrice: numOrUndef(sp.minPrice),
    maxPrice: numOrUndef(sp.maxPrice),
    sort: typeof sp.sort === 'string' ? (sp.sort as SortKey) : 'sales',
    page: numOrUndef(sp.page) ?? 1,
    size: 24,
  }
  keywordInput.value = params.value.keyword ?? ''
  priceInput.value = {
    min: params.value.minPrice != null ? String(params.value.minPrice) : '',
    max: params.value.maxPrice != null ? String(params.value.maxPrice) : '',
  }
  if (params.value.minPrice != null || params.value.maxPrice != null) filtersOpen.value = true
}

/**
 * 条件写回 URL。
 * 用 replace 而非 push：筛选是「调整视角」而不是「翻新页面」，
 * 每次点品类都压一条历史记录，后退键会变得无法使用。
 */
function writeUrl(patch: Partial<Record<keyof typeof params.value, string | number | undefined>>): void {
  const merged = { ...params.value, ...patch }
  const next: Record<string, string> = {}
  for (const [k, v] of Object.entries(merged)) {
    if (v === undefined || v === null || v === '' || k === 'size') continue
    if (k === 'sort' && v === 'sales') continue
    if (k === 'page' && v === 1) continue
    next[k] = String(v)
  }
  void router.replace({ query: next })
}

// URL 变化是唯一的查询触发点，避免「操作里查一次、watcher 又查一次」的重复请求
watch(
  () => route.query,
  () => {
    if (route.name !== 'catalog') return
    readUrl()
    void run()
  },
)

// ---------------- 用户操作 ----------------

function selectCategory(id?: number): void {
  writeUrl({ categoryId: id, page: 1 })
}

function submitKeyword(): void {
  writeUrl({ keyword: keywordInput.value.trim() || undefined, page: 1 })
}

function applyPrice(): void {
  const min = numOrUndef(priceInput.value.min)
  const max = numOrUndef(priceInput.value.max)
  // 区间填反了自动纠正，比弹错误提示友好
  const fixed = min != null && max != null && min > max ? { minPrice: max, maxPrice: min } : { minPrice: min, maxPrice: max }
  writeUrl({ ...fixed, page: 1 })
}

function usePreset(p: (typeof PRICE_PRESETS)[number]): void {
  priceInput.value = { min: p.min != null ? String(p.min) : '', max: p.max != null ? String(p.max) : '' }
  applyPrice()
}

function clearAll(): void {
  keywordInput.value = ''
  priceInput.value = { min: '', max: '' }
  void router.replace({ query: {} })
}

function setSort(s: SortKey): void {
  writeUrl({ sort: s, page: 1 })
}

function goPage(p: number): void {
  if (p < 1 || p > totalPages.value) return
  writeUrl({ page: p })
}

// ---------------- 初始化 ----------------

onMounted(async () => {
  readUrl()
  void run()

  try {
    categories.value = await fetchCategories()
  } catch {
    // 品类挂了不该阻断商品浏览，降级成「只能看全部」
    catsError.value = '品类加载失败，可继续浏览全部商品'
  } finally {
    catsLoading.value = false
  }
})

onUnmounted(abort)
</script>

<template>
  <div class="page">
    <!-- 左栏：品类索引 -->
    <aside class="index">
      <div class="index-head">
        <span class="eyebrow">CATALOG</span>
        <h2>商品目录</h2>
      </div>

      <nav class="cat-nav">
        <button class="cat all" :class="{ on: !params.categoryId }" @click="selectCategory(undefined)">
          <span class="cat-name">全部商品</span>
          <span v-if="!hasFilter && status !== 'loading'" class="cat-count">{{ total }}</span>
        </button>

        <p v-if="catsLoading" class="cat-note">读取品类…</p>
        <p v-else-if="catsError" class="cat-note err">{{ catsError }}</p>

        <div v-for="top in categories" :key="top.id" class="cat-group">
          <button class="cat" :class="{ on: params.categoryId === top.id }" @click="selectCategory(top.id)">
            <span class="cat-name">{{ top.name }}</span>
          </button>
          <button
            v-for="leaf in top.children"
            :key="leaf.id"
            class="cat leaf"
            :class="{ on: params.categoryId === leaf.id }"
            @click="selectCategory(leaf.id)"
          >
            <span class="cat-name">{{ leaf.name }}</span>
          </button>
        </div>
      </nav>

      <div class="index-foot">
        <RouterLink to="/chat" class="to-chat">
          <span>不知道怎么挑？</span>
          <b>用一句话描述 →</b>
        </RouterLink>
      </div>
    </aside>

    <!-- 右栏：账本 -->
    <main class="ledger">
      <header class="ledger-head">
        <div class="scope">
          <h1>{{ scopeText }}</h1>
          <p class="scope-meta">
            <span v-if="status === 'success' || status === 'empty'">共 <b>{{ total }}</b> 件</span>
            <span v-else-if="status === 'loading'">读取中…</span>
            <span v-if="priceRangeText" class="chip">{{ priceRangeText }}</span>
            <span v-if="params.keyword" class="chip">「{{ params.keyword }}」</span>
          </p>
        </div>

        <div class="tools">
          <div class="search">
            <svg class="ico" viewBox="0 0 14 14" width="13" height="13" aria-hidden="true">
              <circle cx="6" cy="6" r="4.2" fill="none" stroke="currentColor" stroke-width="1.4" />
              <path d="m9.2 9.2 3 3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" />
            </svg>
            <input v-model="keywordInput" type="search" placeholder="搜索标题或卖点" @keydown.enter="submitKeyword" />
            <button v-if="keywordInput" class="clear" aria-label="清空关键词" @click="((keywordInput = ''), submitKeyword())">
              ×
            </button>
          </div>

          <div class="sorts" role="group" aria-label="排序方式">
            <button
              v-for="s in SORTS"
              :key="s.key"
              class="sort"
              :class="{ on: params.sort === s.key }"
              @click="setSort(s.key)"
            >
              {{ s.label }}
            </button>
          </div>

          <button class="filter-toggle" :class="{ on: filtersOpen || hasFilter }" @click="filtersOpen = !filtersOpen">
            筛选
            <span v-if="hasFilter" class="dot-mark" aria-hidden="true" />
          </button>
        </div>
      </header>

      <!-- 筛选抽屉：默认收起，把首屏留给商品 -->
      <Transition name="slide">
        <div v-if="filtersOpen" class="filters">
          <div class="f-row">
            <label>价格区间</label>
            <div class="price-inputs">
              <input v-model="priceInput.min" type="number" min="0" placeholder="最低" />
              <span>—</span>
              <input v-model="priceInput.max" type="number" min="0" placeholder="最高" />
              <button class="apply" @click="applyPrice">应用</button>
            </div>
            <div class="quick">
              <button v-for="p in PRICE_PRESETS" :key="p.label" class="quick-btn" @click="usePreset(p)">
                {{ p.label }}
              </button>
            </div>
          </div>
          <div v-if="hasFilter" class="f-row">
            <button class="reset" @click="clearAll">清空全部条件</button>
          </div>
        </div>
      </Transition>

      <!-- 列头：让四列的对齐关系一直可读 -->
      <div v-if="status === 'success'" class="colhead" aria-hidden="true">
        <span>NO.</span>
        <span class="c-item">商品</span>
        <span class="c-sales">已售</span>
        <span class="c-price">价格</span>
      </div>

      <!-- ===== 三态分支 ===== -->
      <ListSkeleton v-if="status === 'loading' && !list.length" />

      <ErrorState v-else-if="status === 'error' && error" :error="error" :meta="meta" @retry="run()" />

      <EmptyState
        v-else-if="status === 'empty'"
        :keyword="params.keyword"
        :has-filter="hasFilter"
        @clear="clearAll"
      />

      <template v-else-if="list.length">
        <div v-if="status === 'loading'" class="refreshing" />
        <div class="rows" :class="{ dim: status === 'loading' }">
          <ProductRow
            v-for="(p, i) in list"
            :key="p.productId"
            :product="p"
            :no="((params.page ?? 1) - 1) * (params.size ?? 24) + i + 1"
          />
        </div>
      </template>

      <footer v-if="totalPages > 1" class="pager">
        <button :disabled="(params.page ?? 1) <= 1" @click="goPage((params.page ?? 1) - 1)">← 上一页</button>
        <span class="pager-info"><b>{{ params.page }}</b> / {{ totalPages }}</span>
        <button :disabled="(params.page ?? 1) >= totalPages" @click="goPage((params.page ?? 1) + 1)">下一页 →</button>
      </footer>

      <!-- 页脚：把真实链路信息摊开，联调时一眼确认数据来源 -->
      <footer class="provenance">
        <span class="mono">数据来源 GET /api/products</span>
        <span v-if="meta" class="mono">
          HTTP {{ status === 'error' && error?.status === 0 ? '未响应' : meta.status }} · {{ meta.elapsedMs }}ms
          <template v-if="meta.traceId"> · trace {{ meta.traceId }}</template>
        </span>
        <span v-if="page" class="mono">
          本页 {{ list.length }} 条 / 共 {{ total }} 条 · 金额合计 ¥{{ money(pageSum) }}
        </span>
      </footer>
    </main>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  grid-template-columns: 208px minmax(0, 1fr);
  flex: 1;
  min-height: 0;
  overflow: hidden;
}

/* ---------------- 左栏：品类索引 ---------------- */
.index {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--border);
  background: var(--paper);
  overflow-y: auto;
}

.index-head {
  padding: 22px 20px 14px;
}

.eyebrow {
  font-family: var(--font-num);
  font-size: 9px;
  letter-spacing: 0.22em;
  color: var(--text-faint);
}

.index-head h2 {
  margin: 2px 0 0;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.cat-nav {
  flex: 1;
  padding: 0 10px 20px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.cat {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  border: 0;
  border-radius: 6px;
  background: none;
  color: var(--text-muted);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition: background 0.13s, color 0.13s;
}

.cat:hover {
  background: #f0eee8;
  color: var(--text);
}

.cat.on {
  background: var(--surface);
  color: var(--text);
  font-weight: 500;
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}

.cat.on::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  width: 2px;
  height: 14px;
  margin-top: -7px;
  border-radius: 1px;
  background: var(--green);
}

.cat.all {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
  border-radius: 6px 6px 0 0;
}

.cat-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cat-count {
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
}

.cat-group {
  padding-bottom: 2px;
}

.cat.leaf {
  padding-left: 22px;
  font-size: 12.5px;
  color: var(--text-faint);
}

.cat.leaf:hover {
  color: var(--text-muted);
}

.cat.leaf.on {
  color: var(--green);
}

.cat-note {
  margin: 6px 10px;
  font-size: 11px;
  color: var(--text-faint);
}

.cat-note.err {
  color: var(--danger);
}

.index-foot {
  padding: 14px 14px 20px;
  border-top: 1px solid var(--border);
}

.to-chat {
  display: block;
  padding: 10px 12px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  text-decoration: none;
  transition: border-color 0.15s;
}

.to-chat:hover {
  border-color: var(--green);
}

.to-chat span {
  display: block;
  font-size: 10.5px;
  color: var(--text-faint);
}

.to-chat b {
  font-size: 12px;
  font-weight: 500;
  color: var(--green);
}

/* ---------------- 右栏：账本 ---------------- */
.ledger {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow-y: auto;
  background: var(--surface);
}

.ledger-head {
  position: sticky;
  top: 0;
  z-index: 3;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 22px 24px 14px;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
}

.scope h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.scope-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 3px 0 0;
  font-size: 11.5px;
  color: var(--text-muted);
}

.scope-meta b {
  font-family: var(--font-num);
  font-weight: 600;
  color: var(--text);
}

.chip {
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--green-soft);
  color: var(--green);
  font-size: 10.5px;
}

.tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.search {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  height: 30px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--paper);
  transition: border-color 0.15s;
}

.search:focus-within {
  border-color: var(--green);
}

.ico {
  color: var(--text-faint);
  flex: 0 0 auto;
}

.search input {
  width: 130px;
  border: 0;
  background: none;
  outline: none;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--text);
}

.search input::-webkit-search-cancel-button {
  display: none;
}

.clear {
  padding: 0 2px;
  border: 0;
  background: none;
  color: var(--text-faint);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.sorts {
  display: flex;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--paper);
}

.sort {
  padding: 3px 9px;
  border: 0;
  border-radius: 5px;
  background: none;
  color: var(--text-muted);
  font-size: 11.5px;
  cursor: pointer;
  transition: background 0.13s, color 0.13s;
}

.sort:hover {
  color: var(--text);
}

.sort.on {
  background: var(--surface);
  color: var(--text);
  box-shadow: 0 1px 2px rgb(0 0 0 / 5%);
}

.filter-toggle {
  position: relative;
  height: 30px;
  padding: 0 13px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--paper);
  color: var(--text-muted);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s;
}

.filter-toggle:hover,
.filter-toggle.on {
  border-color: var(--green);
  color: var(--green);
}

.dot-mark {
  position: absolute;
  top: 5px;
  right: 6px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--price);
}

.filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 24px 16px;
  border-bottom: 1px solid var(--border);
  background: var(--paper);
}

.f-row {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.f-row label {
  font-size: 11px;
  letter-spacing: 0.08em;
  color: var(--text-faint);
}

.price-inputs {
  display: flex;
  align-items: center;
  gap: 6px;
}

.price-inputs input {
  width: 76px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  font-family: var(--font-num);
  font-size: 12px;
  outline: none;
}

.price-inputs input:focus {
  border-color: var(--green);
}

.price-inputs span {
  color: var(--text-faint);
  font-size: 11px;
}

.apply {
  height: 28px;
  padding: 0 12px;
  border: 0;
  border-radius: 6px;
  background: var(--ink);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.quick {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.quick-btn {
  padding: 3px 10px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text-muted);
  font-size: 11px;
  cursor: pointer;
  transition: border-color 0.13s, color 0.13s;
}

.quick-btn:hover {
  border-color: var(--green);
  color: var(--green);
}

.reset {
  border: 0;
  background: none;
  color: var(--text-muted);
  font-size: 11.5px;
  text-decoration: underline;
  text-underline-offset: 3px;
  cursor: pointer;
}

/* 列头与 ProductRow 的 grid 完全一致，否则表头会错位 */
.colhead {
  position: sticky;
  top: 65px;
  z-index: 2;
  display: grid;
  grid-template-columns: 34px 42px minmax(0, 1fr) 62px 108px 20px;
  gap: 14px;
  padding: 8px 20px 8px 24px;
  border-bottom: 1px solid var(--border);
  background: var(--paper);
  font-family: var(--font-num);
  font-size: 9px;
  letter-spacing: 0.16em;
  color: var(--text-faint);
}

/* 「商品」列头横跨 tile+标题两列，用 padding 推过 tile 的 42px + gap 14px */
.c-item {
  grid-column: 2 / 4;
  padding-left: 56px;
}

.c-sales,
.c-price {
  text-align: right;
}

.rows {
  transition: opacity 0.2s;
}

.rows.dim {
  opacity: 0.45;
  pointer-events: none;
}

.refreshing {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  overflow: hidden;
  background: var(--green-soft);
  z-index: 5;
}

.refreshing::after {
  content: '';
  position: absolute;
  inset: 0;
  width: 40%;
  background: var(--green);
  animation: slide 0.9s ease-in-out infinite;
}

@keyframes slide {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(350%);
  }
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 20px;
  border-top: 1px solid var(--line);
}

.pager button {
  padding: 5px 14px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface);
  color: var(--text);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s;
}

.pager button:hover:not(:disabled) {
  border-color: var(--green);
  color: var(--green);
}

.pager button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.pager-info {
  font-family: var(--font-num);
  font-size: 11.5px;
  color: var(--text-muted);
}

.pager-info b {
  color: var(--text);
}

.provenance {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: auto;
  padding: 12px 24px 18px;
  border-top: 1px dashed var(--border);
}

.mono {
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
  letter-spacing: 0.02em;
}

.slide-enter-active,
.slide-leave-active {
  transition: opacity 0.16s, transform 0.16s;
}

.slide-enter-from,
.slide-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

@media (max-width: 900px) {
  .page {
    grid-template-columns: 1fr;
  }

  .index {
    display: none;
  }

  .ledger-head {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
  }

  .tools {
    justify-content: flex-start;
  }

  .colhead {
    display: none;
  }
}
</style>
