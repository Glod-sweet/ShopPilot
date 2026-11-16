import type { SseEventType, SseHandler, Slots } from '../types/sse'

/**
 * 流式对话客户端。
 *
 * 为什么不用原生 EventSource：它不支持 POST，也无法带 Authorization 头。
 * 这里用 fetch + ReadableStream 手动解析 SSE 帧。
 */

interface StreamOptions {
  message: string
  sessionId?: string
  slots?: Slots
  /** 对比模式：参与对比的商品 ID。非空时后端跳过检索直接比较 */
  compareProductIds?: number[]
  signal?: AbortSignal
}

export async function streamChat(
  opts: StreamOptions,
  onEvent: SseHandler,
): Promise<string | null> {
  const resp = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      // 接入鉴权后改为真实 JWT
      Authorization: 'Bearer dev-token',
    },
    body: JSON.stringify({
      sessionId: opts.sessionId ?? null,
      message: opts.message,
      slots: opts.slots ?? { category: null, budget: null, scene: null },
      compareProductIds: opts.compareProductIds ?? [],
      clientTraceId: crypto.randomUUID(),
    }),
    signal: opts.signal,
  })

  if (!resp.ok) {
    const text = await resp.text().catch(() => '')
    throw new Error(`HTTP ${resp.status}${text ? `: ${text}` : ''}`)
  }

  const sessionId = resp.headers.get('X-Session-Id')
  const reader = resp.body?.getReader()
  if (!reader) throw new Error('响应体为空，无法读取流')

  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })

    // SSE 帧以空行分隔，注意同时兼容 \n\n 和 \r\n\r\n
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() ?? ''

    for (const frame of frames) {
      if (!frame.trim()) continue
      let eventName = 'message'
      const dataLines: string[] = []

      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith('event:')) {
          eventName = line.slice(6).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice(5).trim())
        }
      }

      if (!dataLines.length) continue

      try {
        const payload = JSON.parse(dataLines.join('\n')) as Record<string, unknown>
        onEvent(eventName as SseEventType, payload)
      } catch {
        // 半截 JSON 直接丢弃，不要让它打断整条流
        console.warn('[sse] 解析失败:', dataLines.join('\n'))
      }
    }
  }

  return sessionId
}
