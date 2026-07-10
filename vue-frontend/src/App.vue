<script setup lang="ts">
import { ref } from 'vue'
import AiImagePanel from './components/AiImagePanel.vue'

const productDir = ref('')
</script>

<template>
  <div class="app-container">
    <div class="header">
      <h1>🎨 AI 主图重绘</h1>
      <p>管理淘宝、抖音、虾皮三个平台的 5 张主图生成提示词</p>
    </div>
    <div class="nav-bar">
      <a href="/index.html">蓝海词筛选</a>
      <a href="/chat.html">AI 对话</a>
      <a href="/trend.html">趋势词挖掘</a>
      <a href="/scraper.html">1688 采集</a>
      <a href="/file-manager.html">文件管理</a>
      <a href="/vue/" class="active">AI 图片生成 (Vue)</a>
    </div>
    <div class="content">
      <AiImagePanel v-model:productDir="productDir" />
    </div>
  </div>
</template>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  background: #f5f5f5; color: #333;
}
.app-container { max-width: 1200px; margin: 0 auto; padding: 20px; }
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
</style>
