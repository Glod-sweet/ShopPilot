/**
 * SSE 事件类型定义。
 * 必须与 docs/api-contract.md 第 4 节保持一致。
 */

export type SseEventType =
  | 'meta'
  | 'token'
  | 'tool_call'
  | 'tool_result'
  | 'product_card'
  | 'slot_ask'
  | 'done'
  | 'error'

export interface ProductCard {
  productId: number
  title: string
  subTitle?: string
  price: number
  originalPrice?: number
  coverUrl?: string
  stock: number
  salesCount: number
  rating?: number
  tags: string[]
  reason: string
}

export interface SlotAsk {
  slot: string
  question: string
  options: string[]
}

/** 已收集的槽位，需随请求回传给服务端 */
export interface Slots {
  category: string | null
  budget: string | null
  scene: string | null
}

/** 对比模式允许的最大商品数，与后端 ChatRequest.MAX_COMPARE 保持一致 */
export const MAX_COMPARE = 5

export interface ToolCall {
  tool: string
  displayText: string
  args: Record<string, unknown>
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  products: ProductCard[]
  slotAsk: SlotAsk | null
  toolStatus: string | null
  streaming: boolean
  error: string | null
}

export type SseHandler = (type: SseEventType, data: Record<string, unknown>) => void
