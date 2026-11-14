<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError, fetchProductDetail } from '../api/catalog'
import type { ProductDetail } from '../types/product'
import { attrEntries, count, discountOf, money, specText } from '../utils/format'
import ListSkeleton from '../components/catalog/ListSkeleton.vue'
import ErrorState from '../components/catalog/ErrorState.vue'

/**
 * 商品详情独立页 /p/:id。
 *
 * 列表里的展开已经能看到大部分信息，这个页面存在的理由是「可分享」：
 * 联调时把一条 /p/100234 发给别人，对方打开就能复现同一条数据，
 * 不用描述「点哪个品类、翻到第几页、展开第几行」。
 *
 * 三态同样齐全：加载 → 骨架；失败 → 带诊断信息的错误态；成功 → 内容。
 * 「空」在详情页语义上等于 404，由后端返回 1004，走失败态并给出返回入口。
 */

const route = useRoute()

const detail = ref<ProductDetail | null>(null)
const loading = ref(true)
const error = ref<ApiError | null>(null)
const meta = ref<{ path: string; status: number; traceId: string | null; elapsedMs: number } | null>(null)

let ctrl: AbortController | null = null

const id = computed(() => Number(route.params.id))

const off = computed(() => (detail.value ? discountOf(detail.value.price, detail.value.originalPrice) : null))

async function load(): Promise<void> {
  ctrl?.abort()
  ctrl = new AbortController()

  loading.value = true
  error.value = null
  detail.value = null

  if (!Number.isFinite(id.value) || id.value <= 0) {
    error.value = new ApiError('商品 ID 不合法', 1001, 400, null)
    loading.value = false
    return
  }

  try {
    detail.value = await fetchProductDetail(id.value, {
      signal: ctrl.signal,
      onMeta: (m) => (meta.value = m),
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    error.value = e instanceof ApiError ? e : new ApiError('未知错误', 5000, 0, null)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(id, load)
onUnmounted(() => ctrl?.abort())
</script>

<template>
  <div class="detail-page">
    <RouterLink to="/" class="back">← 返回商品目录</RouterLink>

    <ListSkeleton v-if="loading" :rows="3" />

    <ErrorState v-else-if="error" :error="error" :meta="meta" @retry="load" />

    <article v-else-if="detail" class="sheet">
      <header class="sheet-head">
        <div class="headline">
          <p class="crumb">{{ detail.categoryName }} / {{ detail.brandName }}</p>
          <h1>{{ detail.title }}</h1>
          <p class="sub">{{ detail.subTitle }}</p>
        </div>

        <div class="price-block">
          <span class="price"><i>¥</i>{{ money(detail.price) }}</span>
          <span class="price-meta">
            <s v-if="detail.originalPrice && detail.originalPrice > detail.price">¥{{ money(detail.originalPrice) }}</s>
            <em v-if="off">{{ off }}</em>
          </span>
        </div>
      </header>

      <div class="facts">
        <div class="fact">
          <span class="k">销量</span>
          <span class="v">{{ count(detail.salesCount) }}</span>
        </div>
        <div class="fact">
          <span class="k">评分</span>
          <span class="v">{{ detail.rating?.toFixed(1) ?? '—' }}</span>
        </div>
        <div class="fact">
          <span class="k">评价</span>
          <span class="v">{{ count(detail.commentCount) }}</span>
        </div>
        <div class="fact">
          <span class="k">库存</span>
          <span class="v">{{ detail.stock ?? '—' }}</span>
        </div>
        <div class="fact">
          <span class="k">状态</span>
          <span class="v" :class="{ off: !detail.onShelf }">{{ detail.onShelf ? '在售' : '已下架' }}</span>
        </div>
      </div>

      <div class="body">
        <section class="block wide">
          <h3>卖点</h3>
          <p class="prose">{{ detail.sellingPoints || '—' }}</p>
          <p v-if="detail.description" class="prose muted">{{ detail.description }}</p>
          <div v-if="detail.tags.length" class="tagline">
            <span v-for="t in detail.tags" :key="t" class="tag">{{ t }}</span>
          </div>
        </section>

        <section class="block">
          <h3>参数</h3>
          <dl v-if="attrEntries(detail.attributes).length" class="attrs">
            <div v-for="a in attrEntries(detail.attributes)" :key="a.k">
              <dt>{{ a.k }}</dt>
              <dd>{{ a.v }}</dd>
            </div>
          </dl>
          <p v-else class="muted">未维护参数</p>
        </section>

        <section class="block">
          <h3>规格</h3>
          <ul v-if="detail.skus.length" class="skus">
            <li v-for="s in detail.skus" :key="s.skuId" :class="{ off: !s.available }">
              <span class="sku-spec">{{ specText(s.spec) }}</span>
              <span class="sku-meta">
                <span class="sku-price">¥{{ money(s.price) }}</span>
                <span class="sku-stock">{{ s.available ? `余 ${s.stock}` : '缺货' }}</span>
              </span>
            </li>
          </ul>
          <p v-else class="muted">单一规格</p>
        </section>

        <section v-if="detail.scenes.length" class="block wide">
          <h3>适用场景</h3>
          <div class="scene-list">
            <div v-for="s in detail.scenes" :key="s.sceneId" class="scene">
              <b>{{ s.sceneName }}</b>
              <span v-if="s.reason">{{ s.reason }}</span>
              <em v-if="s.score != null">契合度 {{ s.score }}</em>
            </div>
          </div>
        </section>
      </div>

      <footer class="sheet-foot">
        <span class="mono">商品编号 {{ detail.productNo }}</span>
        <span class="mono">GET /api/products/{{ detail.productId }}</span>
        <!-- 走到这里说明请求成功，meta.status 必为 2xx，无需再判空 -->
        <span v-if="meta" class="mono">HTTP {{ meta.status }} · {{ meta.elapsedMs }}ms</span>
        <span v-if="meta?.traceId" class="mono">trace {{ meta.traceId }}</span>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.detail-page {
  flex: 1;
  overflow-y: auto;
  padding: 20px 32px 40px;
}

.back {
  display: inline-block;
  margin-bottom: 16px;
  font-size: 12px;
  color: var(--text-muted);
  text-decoration: none;
}

.back:hover {
  color: var(--green);
}

.sheet {
  max-width: 940px;
  margin: 0 auto;
  padding: 28px 32px 22px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
}

.sheet-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 32px;
  padding-bottom: 18px;
  border-bottom: 1px solid var(--line);
}

.crumb {
  margin: 0 0 4px;
  font-family: var(--font-num);
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--text-faint);
}

.headline h1 {
  margin: 0 0 3px;
  font-size: 22px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.35;
}

.sub {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
}

.price-block {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex: 0 0 auto;
}

.price {
  font-family: var(--font-num);
  font-size: 26px;
  font-weight: 500;
  color: var(--price);
  letter-spacing: -0.03em;
}

.price i {
  font-style: normal;
  font-size: 13px;
  margin-right: 2px;
  opacity: 0.8;
}

.price-meta {
  display: flex;
  gap: 7px;
  font-family: var(--font-num);
  font-size: 11px;
  color: var(--text-faint);
}

.price-meta em {
  font-style: normal;
  color: var(--price);
  opacity: 0.8;
}

.facts {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
  margin: 16px 0 22px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  overflow: hidden;
}

.fact {
  flex: 1 1 0;
  min-width: 90px;
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 9px 14px;
  border-right: 1px solid var(--line);
}

.fact:last-child {
  border-right: 0;
}

.fact .k {
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--text-faint);
}

.fact .v {
  font-family: var(--font-num);
  font-size: 14px;
  color: var(--text);
}

.fact .v.off {
  color: var(--danger);
}

/* 两栏即可：卖点与场景整行铺开，参数与规格各占一栏。
   三栏会空出右侧一整列，反而显得页面没排满。 */
.body {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 26px 40px;
}

.block.wide {
  grid-column: 1 / -1;
}

.block h3 {
  margin: 0 0 8px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.14em;
  color: var(--text-faint);
  text-transform: uppercase;
}

.prose {
  margin: 0 0 8px;
  font-size: 13.5px;
  line-height: 1.75;
  color: #35342f;
}

.prose.muted {
  font-size: 12.5px;
  color: var(--text-muted);
}

.tagline {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.tag {
  padding: 1px 8px;
  border: 1px solid var(--border);
  border-radius: 3px;
  font-size: 10.5px;
  color: var(--text-muted);
}

.attrs {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.attrs > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 4px;
  border-bottom: 1px dotted var(--line);
  font-size: 12.5px;
}

.attrs dt {
  color: var(--text-muted);
  white-space: nowrap;
}

.attrs dd {
  margin: 0;
  text-align: right;
}

.skus {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.skus li {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding-bottom: 5px;
  border-bottom: 1px dotted var(--line);
  font-size: 12.5px;
}

.skus li:last-child {
  border-bottom: 0;
}

.skus li.off {
  opacity: 0.45;
}

.sku-meta {
  display: flex;
  gap: 9px;
  font-family: var(--font-num);
  font-size: 11px;
}

.sku-price {
  color: var(--price);
}

.sku-stock {
  color: var(--text-faint);
}

.scene-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.scene {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--paper);
  font-size: 12px;
}

.scene b {
  flex: 0 0 auto;
  font-weight: 500;
  color: var(--green);
}

.scene span {
  flex: 1;
  color: var(--text-muted);
}

.scene em {
  flex: 0 0 auto;
  font-style: normal;
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
}

.muted {
  margin: 0;
  font-size: 12.5px;
  color: var(--text-faint);
}

.sheet-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  margin-top: 24px;
  padding-top: 12px;
  border-top: 1px dashed var(--border);
}

.mono {
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
}

@media (max-width: 820px) {
  .detail-page {
    padding: 16px;
  }

  .sheet {
    padding: 20px;
  }

  .sheet-head {
    flex-direction: column;
    gap: 14px;
  }

  .price-block {
    align-items: flex-start;
  }

  .body {
    grid-template-columns: 1fr;
  }
}
</style>
