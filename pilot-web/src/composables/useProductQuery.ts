import { ref, shallowRef, computed } from 'vue'
import { ApiError, fetchProducts, type ProductListParams, type RequestMeta } from '../api/catalog'
import type { PageResult, ProductCard } from '../types/product'

/**
 * 商品列表查询状态机。
 *
 * 为什么要单独抽出来而不是写在页面里：加载中 / 空 / 失败是三种互斥的渲染分支，
 * 如果散落在模板的 v-if 里，很容易出现「失败时还留着上一次的旧数据」这种脏状态。
 * 这里把 data / error / loading 收敛成一个状态机，页面只负责按 status 渲染。
 *
 * 竞态处理：每次请求带自增序号，回来时比对序号，过期响应直接丢弃。
 * 快速切品类时，慢的那次响应不会覆盖快的那次结果。
 */
export type QueryStatus = 'idle' | 'loading' | 'success' | 'empty' | 'error'

export function useProductQuery(initial: ProductListParams = {}) {
  const params = ref<ProductListParams>({ page: 1, size: 24, sort: 'sales', ...initial })
  const status = ref<QueryStatus>('idle')
  const page = shallowRef<PageResult<ProductCard> | null>(null)
  const error = ref<ApiError | null>(null)
  const meta = ref<RequestMeta | null>(null)

  let seq = 0
  let inflight: AbortController | null = null

  const list = computed<ProductCard[]>(() => page.value?.list ?? [])
  const total = computed(() => page.value?.total ?? 0)
  const totalPages = computed(() => {
    const p = page.value
    if (!p || p.size <= 0) return 1
    return Math.max(1, Math.ceil(p.total / p.size))
  })

  /**
   * 执行查询。
   * keepData=true 用于「翻页/换排序」——保留旧数据只把状态切到 loading，
   * 避免列表整块闪成骨架屏，视觉上更连续。
   */
  async function run(opts: { keepData?: boolean } = {}): Promise<void> {
    const mySeq = ++seq
    inflight?.abort()
    const ctrl = new AbortController()
    inflight = ctrl

    if (!opts.keepData) {
      page.value = null
    }
    error.value = null
    status.value = 'loading'

    try {
      const result = await fetchProducts(params.value, {
        signal: ctrl.signal,
        onMeta: (m) => {
          if (mySeq === seq) meta.value = m
        },
      })
      if (mySeq !== seq) return
      page.value = result
      status.value = result.list.length === 0 ? 'empty' : 'success'
    } catch (e) {
      if (mySeq !== seq) return
      // 主动取消不改变状态：调用方已经发起了新一轮请求
      if (e instanceof DOMException && e.name === 'AbortError') return
      error.value = e instanceof ApiError ? e : new ApiError('未知错误', 5000, 0, null)
      page.value = null
      status.value = 'error'
    } finally {
      if (inflight === ctrl) inflight = null
    }
  }

  /** 改条件并重查，自动回到第一页——换了筛选还停在第 3 页是常见 bug */
  async function apply(patch: Partial<ProductListParams>): Promise<void> {
    params.value = { ...params.value, ...patch, page: patch.page ?? 1 }
    await run()
  }

  async function goPage(p: number): Promise<void> {
    if (p < 1 || p > totalPages.value || p === params.value.page) return
    params.value = { ...params.value, page: p }
    await run({ keepData: true })
  }

  function abort(): void {
    inflight?.abort()
    inflight = null
  }

  return { params, status, page, list, total, totalPages, error, meta, run, apply, goPage, abort }
}
