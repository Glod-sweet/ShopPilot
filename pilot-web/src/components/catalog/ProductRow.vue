<script setup lang="ts">
import { computed, ref, onUnmounted } from 'vue'
import { fetchProductDetail, ApiError } from '../../api/catalog'
import type { ProductCard, ProductDetail } from '../../types/product'
import { money, count, discountOf, specText, attrEntries, padNo } from '../../utils/format'

/**
 * 一条商品「账本行」。
 *
 * 为什么不是卡片：卡片网格是电商列表的默认答案，但在这个阶段它的信息密度低、
 * 而且会把注意力吸到图片上。这里图片本来就不可用（种子数据的 coverUrl 指向
 * 不存在的 CDN 域名），所以改成排版驱动的行式布局——序号、标题、卖点、价格
 * 一条基线对齐，扫读时眼睛只需要走一列，比网格更快。
 *
 * 展开是原地手风琴，不是弹窗/抽屉：保持列表上下文不丢失，
 * 而且详情数据是懒加载的（第一次展开才请求），顺带验证了第二个接口。
 */
const props = defineProps<{
  product: ProductCard
  /** 全局序号，跨页连续 */
  no: number
}>()

const expanded = ref(false)
const detail = ref<ProductDetail | null>(null)
const loading = ref(false)
const error = ref<ApiError | null>(null)

let ctrl: AbortController | null = null

/** 序号色块：用品类名首字 + 派生色相，替代不可用的封面图 */
const tile = computed(() => {
  const src = props.product.categoryName || props.product.brandName || props.product.title
  const ch = src.slice(0, 1)
  let h = 0
  for (const c of src) h = (h * 31 + c.charCodeAt(0)) % 360
  return { ch, hue: h }
})

const off = computed(() => discountOf(props.product.price, props.product.originalPrice))

async function toggle() {
  expanded.value = !expanded.value
  if (!expanded.value || detail.value || loading.value) return

  loading.value = true
  error.value = null
  ctrl = new AbortController()
  try {
    detail.value = await fetchProductDetail(props.product.productId, { signal: ctrl.signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return
    error.value = e instanceof ApiError ? e : new ApiError('详情加载失败', 5000, 0, null)
  } finally {
    loading.value = false
  }
}

function retryDetail() {
  detail.value = null
  void toggle()
}

onUnmounted(() => ctrl?.abort())
</script>

<template>
  <article class="row" :class="{ open: expanded }">
    <button class="head" :aria-expanded="expanded" @click="toggle">
      <span class="no">{{ padNo(no) }}</span>

      <span class="tile" :style="{ '--hue': tile.hue }" aria-hidden="true">{{ tile.ch }}</span>

      <span class="main">
        <span class="title-line">
          <span class="title">{{ product.title }}</span>
          <span v-if="product.rating" class="rating">{{ product.rating.toFixed(1) }}</span>
        </span>
        <span class="sub">{{ product.subTitle || product.reason }}</span>
        <span class="tags">
          <span v-for="t in product.tags.slice(0, 3)" :key="t" class="tag">{{ t }}</span>
          <span class="brand">{{ product.brandName }}</span>
        </span>
      </span>

      <span class="sales">
        <span class="sales-num">{{ count(product.salesCount) }}</span>
      </span>

      <span class="price-col">
        <span class="price"><i>¥</i>{{ money(product.price) }}</span>
        <span class="price-meta">
          <s v-if="product.originalPrice && product.originalPrice > product.price">
            ¥{{ money(product.originalPrice) }}
          </s>
          <em v-if="off">{{ off }}</em>
        </span>
      </span>

      <span class="caret" aria-hidden="true">
        <svg viewBox="0 0 12 12" width="12" height="12">
          <path d="M2 4.5 6 8.5 10 4.5" fill="none" stroke="currentColor" stroke-width="1.6" />
        </svg>
      </span>
    </button>

    <div v-if="expanded" class="panel">
      <p v-if="loading" class="panel-loading">
        <span class="dot" />正在读取详情…
      </p>

      <div v-else-if="error" class="panel-error">
        <span>{{ error.message }}</span>
        <button class="mini" @click="retryDetail">重试</button>
      </div>

      <template v-else-if="detail">
        <div class="panel-cols">
          <section class="col">
            <h4>卖点</h4>
            <p class="selling">{{ detail.sellingPoints || detail.description || '暂无描述' }}</p>
            <p v-if="detail.giftWrappable" class="gift">支持礼品包装</p>
          </section>

          <section class="col">
            <h4>参数</h4>
            <dl v-if="attrEntries(detail.attributes).length" class="attrs">
              <div v-for="a in attrEntries(detail.attributes)" :key="a.k">
                <dt>{{ a.k }}</dt>
                <dd>{{ a.v }}</dd>
              </div>
            </dl>
            <p v-else class="muted">该商品未维护参数</p>
          </section>

          <section class="col">
            <h4>规格与库存</h4>
            <ul v-if="detail.skus.length" class="skus">
              <li v-for="s in detail.skus" :key="s.skuId" :class="{ off: !s.available }">
                <span class="sku-spec">{{ specText(s.spec) }}</span>
                <span class="sku-meta">
                  <span class="sku-price">¥{{ money(s.price) }}</span>
                  <span class="sku-stock">{{ s.available ? `余 ${s.stock}` : '缺货' }}</span>
                </span>
              </li>
            </ul>
            <p v-else class="muted">
              单一规格
              <span class="sep">·</span>
              {{ detail.stock && detail.stock > 0 ? `库存 ${detail.stock}` : '暂时缺货' }}
            </p>
          </section>
        </div>

        <div v-if="detail.scenes.length" class="scenes">
          <h4>适用场景</h4>
          <div class="scene-list">
            <span v-for="s in detail.scenes" :key="s.sceneId" class="scene">
              <b>{{ s.sceneName }}</b>
              <span v-if="s.reason">{{ s.reason }}</span>
            </span>
          </div>
        </div>

        <footer class="panel-foot">
          <span class="mono">商品编号 {{ detail.productNo }}</span>
          <span class="mono">{{ detail.categoryName }} / {{ detail.brandName }}</span>
          <span class="mono">GET /api/products/{{ detail.productId }}</span>
          <RouterLink class="to-detail" :to="`/p/${detail.productId}`">完整详情 →</RouterLink>
        </footer>
      </template>
    </div>
  </article>
</template>

<style scoped>
.row {
  border-bottom: 1px solid var(--line);
  transition: background 0.16s;
}

.row:hover {
  background: var(--paper);
}

.row.open {
  background: var(--paper);
  box-shadow: inset 2px 0 0 var(--green);
}

.head {
  display: grid;
  grid-template-columns: 34px 42px minmax(0, 1fr) 62px 108px 20px;
  align-items: center;
  gap: 14px;
  width: 100%;
  padding: 14px 20px 14px 24px;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  color: inherit;
}

.no {
  font-family: var(--font-num);
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.06em;
}

.tile {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 9px;
  font-size: 15px;
  font-weight: 500;
  color: hsl(var(--hue) 32% 32%);
  background: hsl(var(--hue) 40% 93%);
  border: 1px solid hsl(var(--hue) 30% 88%);
}

.main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.title {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rating {
  flex: 0 0 auto;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--green-soft);
  color: var(--green);
  font-family: var(--font-num);
  font-size: 10px;
  line-height: 16px;
}

.sub {
  font-size: 12px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tags {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  overflow: hidden;
}

.tag {
  padding: 0 6px;
  border: 1px solid var(--border);
  border-radius: 3px;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 15px;
  white-space: nowrap;
}

.brand {
  font-size: 10px;
  color: var(--text-faint);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.sales {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  line-height: 1.3;
}

.sales-num {
  font-family: var(--font-num);
  font-size: 12px;
  color: var(--text-muted);
}

.sales-label {
  font-size: 10px;
  color: var(--text-faint);
}

.price-col {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 1px;
}

.price {
  font-family: var(--font-num);
  font-size: 15px;
  font-weight: 500;
  color: var(--price);
  letter-spacing: -0.02em;
}

.price i {
  font-style: normal;
  font-size: 10px;
  margin-right: 1px;
  opacity: 0.8;
}

.price-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
}

.price-meta s {
  text-decoration-thickness: 1px;
}

.price-meta em {
  font-style: normal;
  color: var(--price);
  opacity: 0.75;
}

.caret {
  color: var(--text-faint);
  transition: transform 0.18s, color 0.18s;
  display: grid;
  place-items: center;
}

.row.open .caret {
  transform: rotate(180deg);
  color: var(--green);
}

.panel {
  padding: 4px 40px 20px 90px;
  animation: unfold 0.2s ease-out;
}

@keyframes unfold {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
}

.panel-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 12px;
  color: var(--text-muted);
}

.dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--green);
  animation: pulse 1.1s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.25;
  }
}

.panel-error {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--danger);
}

.mini {
  padding: 2px 10px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--surface);
  font-size: 11px;
  cursor: pointer;
}

.panel-cols {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr) minmax(0, 1fr);
  gap: 28px;
  padding-top: 8px;
  max-width: 980px;
}

.col h4,
.scenes h4 {
  margin: 0 0 6px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.14em;
  color: var(--text-faint);
  text-transform: uppercase;
}

.selling {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: #3d3c37;
}

.gift {
  display: inline-block;
  margin: 8px 0 0;
  padding: 1px 7px;
  border-radius: 3px;
  background: var(--green-soft);
  color: var(--green);
  font-size: 10px;
}

.attrs {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.attrs > div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  border-bottom: 1px dotted var(--line);
  padding-bottom: 3px;
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
  gap: 4px;
}

.skus li {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 4px 0;
  border-bottom: 1px dotted var(--line);
  font-size: 12px;
}

.skus li:last-child {
  border-bottom: 0;
}

.skus li.off {
  opacity: 0.45;
}

.sku-spec {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sku-meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-family: var(--font-num);
  font-size: 11px;
}

.sku-price {
  color: var(--price);
}

.sku-stock {
  color: var(--text-faint);
}

.muted {
  margin: 0;
  font-size: 12px;
  color: var(--text-faint);
}

.scenes {
  margin-top: 14px;
}

.scene-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.scene {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 3px 9px;
  border: 1px solid var(--border);
  border-radius: 5px;
  background: var(--surface);
  font-size: 11px;
}

.scene b {
  font-weight: 500;
  color: var(--green);
}

.scene span {
  color: var(--text-muted);
}

.panel-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 16px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}

.mono {
  font-family: var(--font-num);
  font-size: 10px;
  color: var(--text-faint);
  letter-spacing: 0.02em;
}

.to-detail {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--green);
  text-decoration: none;
  border-bottom: 1px solid transparent;
}

.to-detail:hover {
  border-bottom-color: var(--green);
}

@media (max-width: 900px) {
  .head {
    grid-template-columns: 26px 36px minmax(0, 1fr) 84px 16px;
    gap: 10px;
  }

  .sales {
    display: none;
  }

  .panel {
    padding-left: 16px;
  }

  .panel-cols {
    grid-template-columns: 1fr;
    gap: 16px;
  }
}
</style>
