import { createRouter, createWebHistory } from 'vue-router'

/**
 * 只有两个页面：商品目录（浏览，人找货）与对话导购（Agent，货找人）。
 * 用路由而不是 v-if 切换，是为了让两个页面各自拥有独立的挂载/卸载生命周期——
 * 切走时能真正 abort 在途请求，切回来时状态自然重置，不用手写清理逻辑。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'catalog', component: () => import('../views/CatalogView.vue') },
    // 详情页独立可访问，联调时可直接把 /p/100234 发给别人复现
    { path: '/p/:id', name: 'product', component: () => import('../views/ProductDetailView.vue') },
    { path: '/chat', name: 'chat', component: () => import('../views/ChatView.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
