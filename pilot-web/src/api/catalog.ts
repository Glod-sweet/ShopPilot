import type { CategoryNode, PageResult, ProductCard, ProductDetail, SortKey } from '../types/product'

/**
 * 商品接口客户端。
 *
 * 三个约束：
 * 1. 只走相对路径 `/api/**`，由 Vite 代理转发到 Java。绝不在前端硬编码 host，
 *    否则换个端口/部署方式就要改代码，也绕过了 CORS 配置的意义。
 * 2. 统一解包 ApiResponse：{ code, message, data, traceId }，code != 0 视为业务失败。
 *    业务失败和信息「HTTP 200 但查不到」是两件事，必须区分，否则前端没法给出准确提示。
 * 3. 所有请求带 AbortSignal，组件卸载或参数变更时能真正取消，避免旧响应覆盖新状态。
 */

const BASE = '/api'

/** 后端 ErrorCode 里前端需要区分处理的几个 */
export const ERROR_CODE = {
  OK: 0,
  BAD_REQUEST: 1001,
  UNAUTHORIZED: 1002,
  NOT_FOUND: 1004,
  SERVER: 5000,
} as const

/** 带业务码的错误。message 直接来自后端，前端不重写文案，保持单一事实来源 */
export class ApiError extends Error {
  readonly code: number
  readonly status: number
  readonly traceId: string | null

  constructor(message: string, code: number, status: number, traceId: string | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.traceId = traceId
  }

  /** 鉴权失效需要引导登录，其余错误只需提示重试 */
  get isAuthError(): boolean {
    return this.code === ERROR_CODE.UNAUTHORIZED || this.status === 401
  }
}

interface Envelope<T> {
  code: number
  message: string
  data: T
  traceId: string | null
}

/** 一次请求的链路元信息，页面上直接展示，用于肉眼确认前后端确实连通 */
export interface RequestMeta {
  path: string
  status: number
  traceId: string | null
  elapsedMs: number
}

interface RequestOptions {
  signal?: AbortSignal
  /** 开发模式免登录用的固定 token，见 application.yml 的 pilot.auth.dev-token */
  token?: string
  timeoutMs?: number
  /** 成功或失败都会回调一次，供页面展示链路状态 */
  onMeta?: (meta: RequestMeta) => void
}

const DEFAULT_TIMEOUT = 10_000

async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  const { signal, token = 'dev-token', timeoutMs = DEFAULT_TIMEOUT, onMeta } = opts
  const startedAt = performance.now()
  const report = (status: number, traceId: string | null) =>
    onMeta?.({ path: BASE + path, status, traceId, elapsedMs: Math.round(performance.now() - startedAt) })

  // 超时与外部取消合并成一个信号：任一触发都中断请求
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs)
  const onAbort = () => ctrl.abort(signal?.reason)
  signal?.addEventListener('abort', onAbort, { once: true })

  let resp: Response
  try {
    resp = await fetch(BASE + path, {
      method: 'GET',
      headers: {
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
      },
      signal: ctrl.signal,
    })
  } catch (e) {
    // 外部主动取消不算错误，交给调用方按 AbortError 忽略
    if (signal?.aborted) throw e
    report(0, null)
    if (e instanceof DOMException && e.name === 'TimeoutError') {
      throw new ApiError(`请求超时（${timeoutMs / 1000}s），后端可能未启动`, ERROR_CODE.SERVER, 0, null)
    }
    throw new ApiError('无法连接后端服务，请确认 pilot-server 已在 8080 启动', ERROR_CODE.SERVER, 0, null)
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', onAbort)
  }

  // 先按文本读，避免后端返回非 JSON（比如网关的 HTML 错误页）时抛解析异常
  const raw = await resp.text()
  let body: Envelope<T> | null = null
  try {
    body = raw ? (JSON.parse(raw) as Envelope<T>) : null
  } catch {
    body = null
  }
  report(resp.status, body?.traceId ?? null)

  if (!resp.ok) {
    // 401 时后端返回的也是 ApiResponse 结构，能取到 message 就用它
    const msg = body?.message || `请求失败（HTTP ${resp.status}）`
    const code = body?.code ?? ERROR_CODE.SERVER

    /**
     * 区分「后端没起」和「后端报错」。
     * 后端没启动时，Vite 代理自己会返回 502/503/504（或 500），响应体是纯文本而不是 ApiResponse。
     * 这种情况对使用者来说等价于「连不上」，直接提示去启动后端，比让他去翻 HTTP 500 更有用。
     */
    const proxyDead =
      body === null && (resp.status === 502 || resp.status === 503 || resp.status === 504 || resp.status === 500)
    if (proxyDead) {
      throw new ApiError(
        '无法连接后端服务：代理转发失败，请确认 pilot-server 已在 8080 启动',
        ERROR_CODE.SERVER,
        0,
        null,
      )
    }

    throw new ApiError(msg, code, resp.status, body?.traceId ?? null)
  }

  if (!body) {
    throw new ApiError('后端返回了无法解析的响应体', ERROR_CODE.SERVER, resp.status, null)
  }

  if (body.code !== ERROR_CODE.OK) {
    throw new ApiError(body.message || '业务处理失败', body.code, resp.status, body.traceId)
  }

  return body.data
}

/** 列表查询参数。undefined 一律不拼进 query，避免 `?categoryId=undefined` */
export interface ProductListParams {
  categoryId?: number
  brandId?: number
  minPrice?: number
  maxPrice?: number
  keyword?: string
  sort?: SortKey
  page?: number
  size?: number
  includeChildren?: boolean
}

function toQuery(params: object): string {
  const sp = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue
    sp.set(k, String(v))
  }
  const s = sp.toString()
  return s ? `?${s}` : ''
}

export function fetchProducts(
  params: ProductListParams = {},
  opts: RequestOptions = {},
): Promise<PageResult<ProductCard>> {
  return request<PageResult<ProductCard>>(`/products${toQuery(params)}`, opts)
}

export function fetchProductDetail(id: number, opts: RequestOptions = {}): Promise<ProductDetail> {
  return request<ProductDetail>(`/products/${id}`, opts)
}

export function fetchCategories(opts: RequestOptions = {}): Promise<CategoryNode[]> {
  return request<CategoryNode[]>('/categories', opts)
}
