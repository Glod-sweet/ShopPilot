import { chromium } from 'file:///C:/Users/zhang/AppData/Local/npm-cache/_npx/e41f203b7505f1fb/node_modules/playwright/index.mjs'

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const ctx = await browser.newContext({ viewport: { width: 1440, height: 980 }, deviceScaleFactor: 2 })
const page = await ctx.newPage()
await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })

// 展开一个多 SKU 的商品（声浪3 有两个 SKU），更能检验规格栏
await page.goto('http://localhost:5173/?keyword=声浪3', { waitUntil: 'networkidle' })
await page.locator('article.row .head').first().click()
await page.waitForSelector('.panel-cols')
await page.locator('article.row').first().screenshot({ path: 'D:/tmp/sp_zoom_row.png' })

// 整页也拍一张 2x
await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' })
await page.screenshot({ path: 'D:/tmp/sp_2x_normal.png' })
await browser.close()
