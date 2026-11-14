<script setup lang="ts">
import type { ApiError } from '../../api/catalog'

/**
 * 请求失败态。
 *
 * 关键在于「可诊断」：不只是说「加载失败，请重试」，而是把
 * HTTP 状态码、业务码、traceId、耗时都摆出来。联调阶段这四样东西
 * 能立刻区分「后端没起」「代理没通」「401 没带 token」「查不到数据」。
 */
defineProps<{
  error: ApiError
  meta?: { path: string; status: number; traceId: string | null; elapsedMs: number } | null
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="err">
    <div class="glyph" aria-hidden="true">
      <svg viewBox="0 0 40 40" width="40" height="40">
        <circle cx="20" cy="20" r="18" fill="none" stroke="currentColor" stroke-width="1.2" opacity="0.35" />
        <path d="M20 11v11" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
        <circle cx="20" cy="27.5" r="1.6" fill="currentColor" />
      </svg>
    </div>

    <h3>没能取到商品数据</h3>
    <p class="msg">{{ error.message }}</p>

    <dl class="diag">
      <div>
        <dt>请求</dt>
        <dd class="mono">{{ meta?.path || '/api/products' }}</dd>
      </div>
      <div>
        <dt>HTTP</dt>
        <!-- 连接失败时 status 归一成 0，此时显示「未响应」比显示代理的 500 更真实 -->
        <dd class="mono">{{ error.status === 0 ? '未响应' : (meta?.status ?? error.status) }}</dd>
      </div>
      <div v-if="error.status !== 0">
        <dt>业务码</dt>
        <dd class="mono">{{ error.code }}</dd>
      </div>
      <div>
        <dt>耗时</dt>
        <dd class="mono">{{ meta ? `${meta.elapsedMs} ms` : '—' }}</dd>
      </div>
      <div v-if="meta?.traceId">
        <dt>traceId</dt>
        <dd class="mono trace">{{ meta.traceId }}</dd>
      </div>
    </dl>

    <div class="acts">
      <button class="primary" @click="$emit('retry')">重新请求</button>
    </div>

    <ul class="hints">
      <li v-if="error.isAuthError">
        401 通常是 token 失效：确认 <code>pilot.auth.dev-mode=true</code> 且请求头带
        <code>Bearer dev-token</code>。
      </li>
      <li v-else-if="error.status === 0">
        连接被拒绝：确认后端已启动在 8080，且 Vite 代理 <code>/api</code> 指向它。
      </li>
      <li v-else>查看后端控制台日志，按 traceId 搜索可定位同一次请求。</li>
    </ul>
  </div>
</template>

<style scoped>
.err {
  padding: 40px 32px;
  text-align: center;
  color: var(--danger);
}

.glyph {
  display: grid;
  place-items: center;
  margin-bottom: 10px;
}

.err h3 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 500;
  color: var(--text);
}

.msg {
  margin: 0 0 18px;
  font-size: 13px;
  color: var(--danger);
  word-break: break-word;
}

.diag {
  display: inline-flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 4px 20px;
  margin: 0 0 20px;
  padding: 12px 18px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--paper);
  text-align: left;
}

.diag > div {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.diag dt {
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--text-faint);
}

.diag dd {
  margin: 0;
  font-size: 11px;
  color: var(--text);
}

.mono {
  font-family: var(--font-num);
}

.trace {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.acts {
  display: flex;
  justify-content: center;
  gap: 10px;
}

.primary {
  padding: 8px 20px;
  border: 0;
  border-radius: var(--radius-sm);
  background: var(--ink);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
  transition: opacity 0.15s;
}

.primary:hover {
  opacity: 0.86;
}

.hints {
  max-width: 460px;
  margin: 20px auto 0;
  padding: 0;
  list-style: none;
  text-align: left;
  font-size: 11.5px;
  line-height: 1.7;
  color: var(--text-muted);
}

.hints code {
  padding: 1px 4px;
  border-radius: 3px;
  background: var(--bg);
  font-family: var(--font-num);
  font-size: 10.5px;
}
</style>
