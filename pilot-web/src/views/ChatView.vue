<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { streamChat } from '../api/chat'
import type { ChatMessage, ProductCard, SlotAsk, SseEventType, Slots } from '../types/sse'

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const sessionId = ref<string | undefined>()
const slots = ref<Slots>({ category: null, budget: null, scene: null })
/** 当前等待用户回答的槽位。用户下一条消息即视为该槽位的值。 */
const pendingSlot = ref<string | null>(null)
const scrollBox = ref<HTMLElement | null>(null)

const suggestions = [
  '想给女朋友买个生日礼物，300以内',
  '有什么适合露营的装备推荐',
  '再来一单上次买的洗衣液',
]

function uid() {
  return Math.random().toString(36).slice(2, 10)
}

async function scrollToBottom() {
  await nextTick()
  if (scrollBox.value) {
    scrollBox.value.scrollTop = scrollBox.value.scrollHeight
  }
}

function pushAssistant(): ChatMessage {
  const msg: ChatMessage = {
    id: uid(),
    role: 'assistant',
    text: '',
    products: [],
    slotAsk: null,
    toolStatus: null,
    streaming: true,
    error: null,
  }
  messages.value.push(msg)
  return messages.value[messages.value.length - 1]
}

function onEvent(msg: ChatMessage, type: SseEventType, data: Record<string, unknown>) {
  switch (type) {
    case 'token':
      msg.text += String(data.text ?? '')
      break
    case 'tool_call':
      msg.toolStatus = String(data.displayText ?? '处理中…')
      break
    case 'tool_result':
      msg.toolStatus = null
      break
    case 'product_card':
      msg.products = (data.products as ProductCard[]) ?? []
      break
    case 'slot_ask':
      msg.slotAsk = data as unknown as SlotAsk
      pendingSlot.value = msg.slotAsk.slot
      break
    case 'done':
      msg.streaming = false
      msg.toolStatus = null
      break
    case 'error':
      msg.error = String(data.message ?? '出错了')
      msg.streaming = false
      break
  }
  void scrollToBottom()
}

async function send(text?: string) {
  const content = (text ?? input.value).trim()
  if (!content || sending.value) return

  input.value = ''

  // 若正在等待槽位回答，把用户这句话当作该槽位的值
  if (pendingSlot.value) {
    const key = pendingSlot.value as keyof Slots
    if (key in slots.value) {
      slots.value[key] = content
    }
    pendingSlot.value = null
  }

  sending.value = true
  messages.value.push({
    id: uid(),
    role: 'user',
    text: content,
    products: [],
    slotAsk: null,
    toolStatus: null,
    streaming: false,
    error: null,
  })
  await scrollToBottom()

  const target = pushAssistant()

  try {
    const sid = await streamChat(
      { message: content, sessionId: sessionId.value, slots: slots.value },
      (type, data) => onEvent(target, type, data),
    )
    if (sid) sessionId.value = sid
  } catch (e) {
    target.error = e instanceof Error ? e.message : '网络异常'
    target.streaming = false
  } finally {
    target.streaming = false
    sending.value = false
    await scrollToBottom()
  }
}

function pickSlot(opt: string) {
  void send(opt)
}

function priceText(p: ProductCard) {
  return `¥${p.price.toFixed(2)}`
}

onMounted(scrollToBottom)
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">
        <div>
          <h1>对话导购</h1>
          <p>说需求，我来挑</p>
        </div>
      </div>
      <span class="badge">{{ sessionId ? '会话中' : '新会话' }}</span>
    </header>

    <main ref="scrollBox" class="stream">
      <div v-if="!messages.length" class="empty">
        <h2>想买点什么？</h2>
        <p>用一句话描述需求，我来帮你筛选和对比</p>
        <div class="chips">
          <button v-for="s in suggestions" :key="s" class="chip" @click="send(s)">
            {{ s }}
          </button>
        </div>
      </div>

      <div
        v-for="m in messages"
        :key="m.id"
        class="row"
        :class="m.role === 'user' ? 'row-user' : 'row-bot'"
      >
        <div class="bubble" :class="m.role === 'user' ? 'bubble-user' : 'bubble-bot'">
          <p v-if="m.toolStatus" class="tool-status">
            <span class="dot" />{{ m.toolStatus }}
          </p>

          <p v-if="m.text" class="text">{{ m.text }}<span v-if="m.streaming" class="caret" /></p>

          <p v-if="m.error" class="error">{{ m.error }}</p>

          <div v-if="m.products.length" class="cards">
            <article v-for="p in m.products" :key="p.productId" class="card">
              <div class="card-head">
                <h3>{{ p.title }}</h3>
                <span class="price">{{ priceText(p) }}</span>
              </div>
              <p class="sub">{{ p.subTitle }}</p>
              <p class="reason">{{ p.reason }}</p>
              <div class="meta">
                <span v-for="t in p.tags" :key="t" class="tag">{{ t }}</span>
                <span class="sales">已售 {{ p.salesCount }}</span>
              </div>
            </article>
          </div>

          <div v-if="m.slotAsk" class="slot">
            <p class="slot-q">{{ m.slotAsk.question }}</p>
            <div class="chips">
              <button
                v-for="o in m.slotAsk.options"
                :key="o"
                class="chip"
                @click="pickSlot(o)"
              >
                {{ o }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </main>

    <footer class="composer">
      <input
        v-model="input"
        placeholder="例如：想给女朋友买个生日礼物，300以内"
        :disabled="sending"
        @keydown.enter="send()"
      />
      <button :disabled="sending || !input.trim()" @click="send()">
        {{ sending ? '…' : '发送' }}
      </button>
    </footer>
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  height: 100%;
  max-width: 860px;
  margin: 0 auto;
  width: 100%;
  background: var(--surface);
  border-left: 1px solid var(--border);
  border-right: 1px solid var(--border);
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--border);
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 500;
}

.brand p {
  margin: 0;
  font-size: 12px;
  color: var(--text-muted);
}

.badge {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.stream {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.empty {
  margin: auto;
  text-align: center;
  color: var(--text-muted);
}

.empty h2 {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 500;
  color: var(--text);
}

.empty p {
  margin: 0 0 16px;
  font-size: 13px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.chip {
  padding: 7px 14px;
  border: 1px solid var(--border);
  border-radius: 999px;
  background: var(--surface);
  color: var(--text);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}

.chip:hover {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}

.row {
  display: flex;
}

.row-user {
  justify-content: flex-end;
}

.bubble {
  max-width: 82%;
  padding: 11px 15px;
  border-radius: var(--radius);
  font-size: 14px;
}

.bubble-user {
  background: var(--accent);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.bubble-bot {
  background: var(--bg);
  border: 1px solid var(--border);
  border-bottom-left-radius: 4px;
}

.text {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.caret {
  display: inline-block;
  width: 2px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: var(--accent);
  animation: blink 1s step-end infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.tool-status {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--text-muted);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  50% {
    opacity: 0.3;
  }
}

.error {
  margin: 0;
  color: var(--danger);
  font-size: 13px;
}

.cards {
  display: grid;
  gap: 10px;
  margin-top: 12px;
}

.card {
  padding: 12px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  background: var(--surface);
}

.card-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}

.card h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
}

.price {
  color: var(--danger);
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
}

.sub {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

.reason {
  margin: 8px 0 0;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--accent-soft);
  color: var(--accent);
  font-size: 12px;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.tag {
  padding: 1px 7px;
  border-radius: 4px;
  background: var(--bg);
  color: var(--text-muted);
  font-size: 11px;
}

.sales {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-muted);
}

.slot {
  margin-top: 10px;
}

.slot-q {
  margin: 0 0 8px;
  font-size: 13px;
}

.slot .chips {
  justify-content: flex-start;
}

.composer {
  display: flex;
  gap: 10px;
  padding: 14px 20px;
  border-top: 1px solid var(--border);
}

.composer input {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: 10px;
  font-size: 14px;
  font-family: inherit;
  outline: none;
  transition: border-color 0.15s;
}

.composer input:focus {
  border-color: var(--accent);
}

.composer button {
  padding: 0 20px;
  border: none;
  border-radius: 10px;
  background: var(--accent);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

.composer button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
