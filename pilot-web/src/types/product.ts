/**
 * 商品域类型定义。
 * 字段严格对齐 docs/api-contract.md 3.0.1 与后端 com.shoppilot.catalog.dto。
 *
 * 注意：后端 PageResult 是 record(List,long,int,int)，pages() 是方法而非组件，
 * Jackson 不会序列化它，所以前端自己算总页数（见 CatalogView 的 pages computed）。
 */

/** 列表卡片：GET /api/products 的 list 元素 */
export interface ProductCard {
  productId: number
  title: string
  subTitle: string | null
  price: number
  originalPrice: number | null
  coverUrl: string | null
  stock: number | null
  salesCount: number | null
  rating: number | null
  tags: string[]
  categoryName: string | null
  brandName: string | null
  /** 推荐理由：Agent 链路里由模型生成，浏览链路里退化为副标题 */
  reason: string
}

export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 两级品类树：GET /api/categories */
export interface CategoryNode {
  id: number
  name: string
  parentId: number
  level: number
  sort: number
  children: CategoryNode[]
}

export interface Sku {
  skuId: number
  skuCode: string
  spec: Record<string, unknown>
  price: number
  stock: number
  imageUrl: string | null
  available: boolean
}

export interface SceneRef {
  sceneId: number
  sceneCode: string
  sceneName: string
  score: number
  reason: string | null
}

/** 详情：GET /api/products/{id}，比卡片多出参数、SKU、场景 */
export interface ProductDetail {
  productId: number
  productNo: string
  title: string
  subTitle: string | null
  price: number
  originalPrice: number | null
  coverUrl: string | null
  album: string[]
  tags: string[]
  sellingPoints: string | null
  description: string | null
  attributes: Record<string, unknown>
  stock: number | null
  salesCount: number | null
  rating: number | null
  commentCount: number | null
  onShelf: boolean
  giftWrappable: boolean
  categoryId: number | null
  categoryName: string | null
  brandId: number | null
  brandName: string | null
  skus: Sku[]
  scenes: SceneRef[]
}

/** 与 ProductQuery.orderByClause() 的白名单一一对应 */
export type SortKey = 'sales' | 'price_asc' | 'price_desc' | 'rating'
