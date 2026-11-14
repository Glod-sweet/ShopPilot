/** 展示层格式化。集中在一处，避免每个组件各写一遍 toFixed。 */

/** 金额：始终两位小数，不用千分位（商品价量级小，加了反而碎） */
export function money(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—'
  return v.toFixed(2)
}

/** 计数：过万折算成「万」，列表里窄，别把 18200 完整写出来 */
export function count(v: number | null | undefined): string {
  if (v == null || Number.isNaN(v)) return '—'
  if (v >= 10000) return `${(v / 10000).toFixed(1)}万`
  return String(v)
}

/** 折扣角标：只在原价确实高于现价时返回，否则返回 null 让模板不渲染 */
export function discountOf(price: number, original: number | null | undefined): string | null {
  if (original == null || original <= price) return null
  const off = Math.round((1 - price / original) * 100)
  return off > 0 ? `-${off}%` : null
}

/** 序号补零：01 / 02，索引列的视觉锚点 */
export function padNo(n: number): string {
  return String(n).padStart(2, '0')
}

/** SKU 规格对象 → 一行可读文本，如「颜色 黑 · 容量 128G」 */
export function specText(spec: Record<string, unknown> | null | undefined): string {
  if (!spec) return '默认规格'
  const parts = Object.entries(spec).map(([k, v]) => `${k} ${String(v)}`)
  return parts.length ? parts.join(' · ') : '默认规格'
}

/** 属性对象 → 有序数组，模板里好遍历 */
export function attrEntries(attrs: Record<string, unknown> | null | undefined): { k: string; v: string }[] {
  if (!attrs) return []
  return Object.entries(attrs).map(([k, v]) => ({ k, v: String(v) }))
}
