<script setup lang="ts">
import { ref, onMounted, provide } from 'vue'
import AiImagePanel from './components/AiImagePanel.vue'
import OrderSupplement from './components/OrderSupplement.vue'
import ImageGallery from './components/ImageGallery.vue'

const currentTab = ref('ai-image')
const productDir = ref('')
const galleryRef = ref<InstanceType<typeof ImageGallery>>()

provide('previewImage', (url: string, allImgs?: string[], idx?: number) => {
  galleryRef.value?.open(url, allImgs, idx)
})

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const dir = params.get('dir')
  if (dir) productDir.value = dir
  const tab = params.get('tab')
  if (tab === 'order-supplement') currentTab.value = 'order-supplement'
})
</script>
<template>
  <div class="app-container" :class="{ 'app-container-wide': currentTab === 'order-supplement' }">
    <div class="header">
      <h1>🎨 AI 主图重绘 <span style="font-size:14px;opacity:0.8;">Vue 版</span></h1>
      <p>管理淘宝、抖音、虾皮三个平台的 5 张主图生成提示词</p>
    </div>
    <div class="nav-bar">
      <a href="/index.html">蓝海词筛选</a>
      <a href="/chat.html">AI 对话</a>
      <a href="/trend.html">趋势词挖掘</a>
      <a href="/scraper.html">1688 采集</a>
      <a href="/file-manager.html">文件管理</a>
      <a :class="{ active: currentTab === 'ai-image' }" href="javascript:void(0)" @click="currentTab = 'ai-image'">AI 图片生成</a>
      <a :class="{ active: currentTab === 'order-supplement' }" href="javascript:void(0)" @click="currentTab = 'order-supplement'">补单管理</a>
    </div>
    <div class="content" :class="{ 'content-wide': currentTab === 'order-supplement' }">
      <AiImagePanel v-if="currentTab === 'ai-image'" v-model:productDir="productDir" />
      <OrderSupplement v-if="currentTab === 'order-supplement'" />
    </div>
    <ImageGallery ref="galleryRef" />
  </div>
</template>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f5f5f5; color: #333;
}
.app-container { max-width: 1200px; margin: 0 auto; padding: 20px; }
.app-container-wide { max-width: 2040px; }
.header {
  background: linear-gradient(135deg, #667eea, #764ba2); color: #fff;
  padding: 24px; border-radius: 12px; margin-bottom: 24px;
}
.header h1 { font-size: 24px; margin-bottom: 8px; }
.header p { opacity: 0.9; font-size: 14px; }
.nav-bar { display: flex; gap: 12px; justify-content: center; margin-bottom: 24px; flex-wrap: wrap; }
.nav-bar a {
  padding: 10px 24px; background: #fff; color: #1890ff; border: 2px solid #1890ff;
  border-radius: 8px; text-decoration: none; font-size: 14px; font-weight: 600;
}
.nav-bar a:hover { background: #1890ff; color: #fff; }
.nav-bar a.active { background: #1890ff; color: #fff; }
.content { background: #fff; border-radius: 12px; padding: 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
.content-wide { max-width: 2040px; margin-left: auto; margin-right: auto; }
</style>
