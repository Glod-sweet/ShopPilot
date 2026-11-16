<script setup lang="ts">
import { ref, nextTick, computed, onMounted } from 'vue'
import { streamChat } from '../api/chat'
import { fetchProducts } from '../api/catalog'
import { MAX_COMPARE } from '../types/sse'
import type { ChatMessage, ProductCard, SlotAsk, SseEventType, Slots } from '../types/sse'
import type { ProductCard as CatalogCard } from '../types/product'

const messages = ref<ChatMessage[]>([])
const input = ref('')
const sending = ref(false)
const sessionId = ref<string | undefined>()
const slots = ref<Slots>({ category: null, budget: null, scene: null })
/** 当前等待用户回答的槽位。用户下一条消息即视为该槽位的值。 */
const pendingSlot = ref<string | null>(null)
const scrollBox = ref<HTMLElement | null>(null)

/**
 * 对比选择的商品。
 * 跨消息累积：用户可能在第一轮选中 A，第二轮又选中 B，然后一起对比。
 * 用 Map 而不是数组，这样同一件商品重复点选不会产生重复项。
 */
const selected = ref<Map<number, ProductCard>>(new Map())

const selectedCount = computed(() => selected.value.size)
const canCompare = computed(() => selectedCount.value >= 2)

function isSelected(p: ProductCard) {
  return selected.value.has(p.productId)
}

function toggleSelect(p: ProductCard) {
  const next = new Map(selected.value)
  if (next.has(p.productId)) {
    next.delete(p.productId)
  } else {
    // 超出上限时不静默失败——用户点了没反应会以为坏了
    if (next.size >= MAX_COMPARE) {
      compareHint.value = `最多同时对比 ${MAX_COMPARE} 件，请先取消一件`
      window.setTimeout(() => (compareHint.value = ''), 2500)
      return
    }
    next.set(p.productId, p)
  }
  selected.value = next
}

const compareHint = ref('')

function clearSelection() {
  selected.value = new Map()
  compareHint.value = ''
}

/** 发起对比：把选中的商品 ID 随请求带上 */
async function startCompare() {
  if (!canCompare.value || sending.value) return
  const ids = [...selected.value.keys()]
  await send(`帮我对比这 ${ids.length} 件商品，哪个更值得买`, ids)
}

// ---- 商品挑选器 ----
// 为什么要有这个：对比功能原本只能从「助手推荐的卡片」里勾选，
// 用户必须先聊一轮才能用。但真实场景里用户常常已经想好要比哪两件，
// 直接让他从商品库里挑，比逼他先描述需求自然得多。
const entryMode = ref<'describe' | 'pick'>('describe')
const pickerKeyword = ref('')
const pickerItems = ref<CatalogCard[]>([])
const pickerLoading = ref(false)
const pickerError = ref('')

async function loadPicker() {
  if (pickerLoading.value) return
  pickerLoading.value = true
  pickerError.value = ''
  try {
    const page = await fetchProducts({
      keyword: pickerKeyword.value.trim() || undefined,
      size: 12,
      page: 1,
    })
    pickerItems.value = page.list ?? []
    if (!pickerItems.value.length && !pickerKeyword.value.trim()) {
      pickerError.value = '商品库暂时取不到数据，请确认后端已启动'
    }
  } catch (e) {
    pickerError.value = e instanceof Error ? e.message : '加载失败，请重试'
    pickerItems.value = []
  } finally {
    pickerLoading.value = false
  }
}

/** 切到挑选模式时自动拉一批商品，避免用户看到空白 */
function switchToPick() {
  entryMode.value = 'pick'
  if (!pickerItems.value.length && !pickerLoading.value) {
    void loadPicker()
  }
}

/**
 * 把商品库的卡片转成对话卡片。
 * 两个 ProductCard 定义字段兼容（product.ts 的更宽），但 null 与可选不同，
 * 统一在这里收口，避免 selected 里混两种形态。
 */
function toChatCard(c: CatalogCard): ProductCard {
  return {
    productId: c.productId,
    title: c.title,
    subTitle: c.subTitle ?? undefined,
    price: c.price,
    originalPrice: c.originalPrice ?? undefined,
    coverUrl: c.coverUrl ?? undefined,
    stock: c.stock ?? 0,
    salesCount: c.salesCount ?? 0,
    rating: c.rating ?? undefined,
    tags: c.tags ?? [],
    reason: c.reason ?? '',
  }
}

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

async function send(text?: string, compareIds?: number[]) {
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
      {
        message: content,
        sessionId: sessionId.value,
        slots: slots.value,
        compareProductIds: compareIds,
      },
      (type, data) => onEvent(target, type, data),
    )
    if (sid) sessionId.value = sid
    // 对比发起后清空选择，避免用户误以为还在选中状态
    if (compareIds?.length) clearSelection()
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
        <p>两种用法：说一句话让我推荐，或者直接挑几件让我对比</p>

        <div class="entry-tabs">
          <button
            class="entry-tab"
            :class="{ active: entryMode === 'describe' }"
            @click="entryMode = 'describe'"
          >
            描述需求
          </button>
          <button
            class="entry-tab"
            :class="{ active: entryMode === 'pick' }"
            @click="switchToPick"
          >
            挑商品对比
          </button>
        </div>

        <!-- 入口一：一句话描述 -->
        <div v-if="entryMode === 'describe'" class="chips">
          <button v-for="s in suggestions" :key="s" class="chip" @click="send(s)">
            {{ s }}
          </button>
        </div>

        <!-- 入口二：直接从商品库挑，不用先聊天 -->
        <div v-else class="picker">
          <div class="picker-bar">
            <input
              v-model="pickerKeyword"
              placeholder="搜商品名，如「耳机」「香薰」"
              @keydown.enter="loadPicker"
            />
            <button class="btn-ghost" :disabled="pickerLoading" @click="loadPicker">
              {{ pickerLoading ? '…' : '搜索' }}
            </button>
          </div>

          <p v-if="pickerError" class="picker-error">{{ pickerError }}</p>

          <div v-if="pickerLoading && !pickerItems.length" class="picker-loading">加载中…</div>

          <div v-else-if="!pickerItems.length" class="picker-empty">
            没有找到商品，换个关键词试试
          </div>

          <div v-else class="picker-grid">
            <button
              v-for="p in pickerItems"
              :key="p.productId"
              class="picker-item"
              :class="{ picked: isSelected(toChatCard(p)) }"
              @click="toggleSelect(toChatCard(p))"
            >
              <span class="picker-check">{{ isSelected(toChatCard(p)) ? '✓' : '+' }}</span>
              <span class="picker-title">{{ p.title }}</span>
              <span class="picker-price">¥{{ p.price.toFixed(0) }}</span>
            </button>
          </div>

          <p class="picker-tip">
            已选 {{ selectedCount }} 件<span v-if="!canCompare">，至少选 2 件才能对比</span>
            <span v-else>，点下方按钮开始对比</span>
          </p>
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
            <article
              v-for="p in m.products"
              :key="p.productId"
              class="card"
              :class="{ 'card-picked': isSelected(p) }"
              @click="toggleSelect(p)"
            >
              <div class="card-head">
                <label class="pick" @click.stop>
                  <input
                    type="checkbox"
                    :checked="isSelected(p)"
                    @change="toggleSelect(p)"
                  />
                  <span class="pick-label">对比</span>
                </label>
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

    <!-- 对比栏：选中 2 件以上才出现 -->
    <div v-if="selectedCount > 0" class="compare-bar">
      <div class="compare-info">
        <span class="compare-count">已选 {{ selectedCount }} 件</span>
        <span v-if="compareHint" class="compare-hint">{{ compareHint }}</span>
        <span v-else-if="!canCompare" class="compare-hint">再选 1 件即可对比</span>
        <span v-else class="compare-picked">
          {{ [...selected.values()].map((p) => p.title.slice(0, 10)).join(' / ') }}
        </span>
      </div>
      <div class="compare-actions">
        <button class="btn-ghost" @click="clearSelection">清空</button>
        <button class="btn-primary" :disabled="!canCompare || sending" @click="startCompare">
          对比这 {{ selectedCount }} 件
        </button>
      </div>
    </div>

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
  width: 100%;
  max-width: 620px;
  padding: 0 20px;
}

.empty h2 {
  margin: 0 0 6px;
  font-size: 17px;
  font-weight: 500;
  color: var(--text);
}

/* 两个入口的切换标签 */
.entry-tabs {
  display: inline-flex;
  gap: 4px;
  margin: 16px 0 14px;
  padding: 3px;
  border-radius: 9px;
  background: var(--surface-2, #f4f4f5);
}

.entry-tab {
  padding: 5px 14px;
  border: none;
  border-radius: 7px;
  background: transparent;
  font-size: 12px;
  color: var(--text-muted);
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.entry-tab.active {
  background: var(--surface);
  color: var(--text);
  box-shadow: 0 1px 2px rgb(0 0 0 / 8%);
}

/* 商品挑选器 */
.picker {
  text-align: left;
}

.picker-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.picker-bar input {
  flex: 1;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  outline: none;
}

.picker-bar input:focus {
  border-color: var(--accent);
}

.picker-error {
  margin: 0 0 10px;
  font-size: 12px;
  color: #c0392b;
}

.picker-loading,
.picker-empty {
  padding: 24px 0;
  font-size: 12px;
  text-align: center;
  color: var(--text-muted);
}

.picker-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
  max-height: 260px;
  overflow-y: auto;
}

.picker-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 11px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.picker-item:hover {
  border-color: var(--accent);
}

.picker-item.picked {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.picker-check {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 50%;
  font-size: 11px;
  color: var(--text-muted);
}

.picker-item.picked .picker-check {
  border-color: var(--accent);
  background: var(--accent);
  color: #fff;
}

.picker-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text);
}

.picker-price {
  flex-shrink: 0;
  color: var(--text-muted);
}

.picker-tip {
  margin: 10px 0 0;
  font-size: 12px;
  text-align: center;
  color: var(--text-muted);
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
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}

.card:hover {
  border-color: var(--accent);
}

/* 已勾选的卡片：加主色边框 + 浅底，让「选中了什么」一目了然 */
.card-picked {
  border-color: var(--accent);
  background: var(--accent-soft);
}

.pick {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
  flex-shrink: 0;
}

.pick input {
  width: 14px;
  height: 14px;
  accent-color: var(--accent);
  cursor: pointer;
}

.pick-label {
  font-size: 11px;
  color: var(--text-muted);
}

.card-picked .pick-label {
  color: var(--accent);
}

.compare-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px;
  border-top: 1px solid var(--border);
  background: var(--accent-soft);
}

.compare-info {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  font-size: 12px;
}

.compare-count {
  flex-shrink: 0;
  font-weight: 500;
  color: var(--accent);
}

.compare-hint {
  color: var(--text-muted);
}

.compare-picked {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--text-muted);
}

.compare-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.btn-ghost,
.btn-primary {
  padding: 6px 14px;
  border-radius: 8px;
  font-size: 12px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn-ghost {
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-muted);
}

.btn-primary {
  border: none;
  background: var(--accent);
  color: #fff;
}

.btn-primary:disabled {
  opacity: 0.4;
  cursor: not-allowed;
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
