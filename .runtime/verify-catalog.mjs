// 联调验证脚本：驱动本机 Edge 检查商品页的三态与交互。
// 用法：node .runtime/verify-catalog.mjs [baseUrl]
import { chromium } from 'file:///C:/Users/zhang/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'

const BASE = process.argv[2] || 'http://localhost:5173'
const OUT = 'D:/tmp'

const browser = await chromium.launch({
  channel: 'msedge',
  headless: true,
})
const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 } })
const page = await ctx.newPage()

const logs = []
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`))
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`))

function report(name, ok, extra = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  ' + extra : ''}`)
  return ok
}

let allOk = true

// ---- 1. 正常态 ----
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
const rowCount = await page.locator('article.row').count()
allOk &= report('列表渲染', rowCount > 0, `行数=${rowCount}`)
await page.screenshot({ path: `${OUT}/sp_1_normal.png`, fullPage: false })

// ---- 2. 展开详情（第二个接口）----
await page.locator('article.row .head').first().click()
await page.waitForSelector('.panel-cols', { timeout: 5000 })
const panelTitle = await page.locator('.panel h4').first().textContent()
allOk &= report('展开详情调用 /api/products/{id}', !!panelTitle, `首个分组=${panelTitle}`)
await page.screenshot({ path: `${OUT}/sp_2_expanded.png` })

// ---- 3. 空态：关键词无匹配 ----
await page.goto(BASE + '/?keyword=zzzznotexist', { waitUntil: 'networkidle' })
const emptyVisible = await page.locator('.empty h3').isVisible().catch(() => false)
const emptyText = emptyVisible ? await page.locator('.empty h3').textContent() : ''
allOk &= report('空数据态', emptyVisible, emptyText || '未出现')
await page.screenshot({ path: `${OUT}/sp_3_empty.png` })

// ---- 4. 筛选与排序 ----
await page.goto(BASE + '/?categoryId=101&sort=price_asc', { waitUntil: 'networkidle' })
const prices = await page.locator('article.row .price').allTextContents()
const nums = prices.map((t) => Number(t.replace(/[^\d.]/g, '')))
const asc = nums.every((v, i) => i === 0 || nums[i - 1] <= v)
allOk &= report('品类筛选 + 价格升序', nums.length > 0 && asc, `${nums.length} 条: ${nums.join(',')}`)
await page.screenshot({ path: `${OUT}/sp_4_filter.png` })

// ---- 5. 失败态：拦截接口返回 500 ----
await ctx.route('**/api/products*', (route) =>
  route.fulfill({
    status: 500,
    contentType: 'application/json',
    body: JSON.stringify({ code: 5000, message: '服务器内部错误（验证用）', data: null, traceId: 'trace-mock-500' }),
  }),
)
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.waitForSelector('.err h3', { timeout: 5000 })
const errText = await page.locator('.err h3').textContent()
allOk &= report('请求失败态', !!errText, errText || '')
await page.screenshot({ path: `${OUT}/sp_5_error.png` })
await ctx.unroute('**/api/products*')

// ---- 6. 失败态：网络断开（后端不可达）----
await ctx.route('**/api/products*', (route) => route.abort('connectionrefused'))
await page.goto(BASE + '/', { waitUntil: 'networkidle' })
await page.waitForSelector('.err h3', { timeout: 5000 })
const netMsg = await page.locator('.err .msg').textContent()
allOk &= report('后端不可达提示', !!netMsg, netMsg || '')
await ctx.unroute('**/api/products*')

// ---- 7. 骨架屏 ----
await ctx.route('**/api/products*', async (route) => {
  await new Promise((r) => setTimeout(r, 2500))
  await route.continue()
})
await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' })
const skVisible = await page
  .locator('.skeleton')
  .waitFor({ state: 'visible', timeout: 3000 })
  .then(() => true)
  .catch(() => false)
allOk &= report('加载中骨架屏', skVisible)
await page.screenshot({ path: `${OUT}/sp_6_loading.png` })
await ctx.unroute('**/api/products*')

// ---- 8. 对话页仍可用 ----
await page.goto(BASE + '/chat', { waitUntil: 'networkidle' })
const chatOk = await page.locator('.composer input').isVisible()
allOk &= report('对话页路由', chatOk)

// ---- 9. 详情页 /p/:id ----
await page.goto(BASE + '/p/100234', { waitUntil: 'networkidle' })
const detailTitle = await page.locator('.headline h1').textContent().catch(() => null)
allOk &= report('详情页 /p/100234', !!detailTitle, detailTitle || '')
await page.screenshot({ path: `${OUT}/sp_7_detail.png`, fullPage: true })

// ---- 10. 详情页 404 ----
await page.goto(BASE + '/p/99999999', { waitUntil: 'networkidle' })
await page.waitForSelector('.err h3', { timeout: 5000 })
const notFoundMsg = await page.locator('.err .msg').textContent()
allOk &= report('详情页不存在返回 404', !!notFoundMsg, notFoundMsg || '')

if (logs.length) {
  console.log('\n--- 浏览器控制台 ---')
  console.log(logs.slice(0, 12).join('\n'))
}

console.log(allOk ? '\n全部通过' : '\n存在失败项')
await browser.close()
process.exit(allOk ? 0 : 1)
